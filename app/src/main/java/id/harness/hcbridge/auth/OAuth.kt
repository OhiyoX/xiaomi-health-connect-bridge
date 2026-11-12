package id.harness.hcbridge.auth

import java.net.URI
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * issuer / resource 的规范形态（计划 Task 4、Review Focus 3）。
 *
 * - 本地：`issuer = http://127.0.0.1:<port>`、`resource = <issuer>/mcp`。
 * - 公网：`issuer = publicBase`（**必须** `https`，否则 [IllegalArgumentException]）、
 *   `resource = <publicBase>/mcp`。
 * - [resourceMetadataUrl] 是 RFC 9728 的「资源元数据」地址：issuer 后接
 *   `/.well-known/oauth-protected-resource` 再加 resource 的路径（`…/mcp`）。
 *
 * 令牌/授权码都绑定生成时的 `resource`：改公网地址就等于让旧令牌失效（客户端重新授权）。
 */
data class Endpoints(val issuer: String, val resource: String) {

    /** `https://h/.well-known/oauth-protected-resource/mcp` 形态。 */
    val resourceMetadataUrl: String
        get() {
            val base = issuer.trimEnd('/')
            val suffix = if (resource.startsWith("$base/")) resource.removePrefix(base) else ""
            return base + "/.well-known/oauth-protected-resource" + suffix
        }

    companion object {

        /**
         * `publicBase == null`（或空白）⇒ [local]；给了就**必须**是 https。
         *
         * 只接受**裸 origin**：`https://<host>[:<port>]`。用 [URI] 解析后逐项拒绝
         * userinfo / query / fragment / 非空且非 `/` 的路径（`https://h/app`），
         * scheme 与 host 折成小写，`/` 与 `:443` 归一化掉。
         * 拒绝而不是"猜"：公网基址写错会让 issuer/resource 静默变成两个值，令牌全部对不上。
         */
        fun from(publicBase: String?): Endpoints {
            val raw = publicBase?.trim()?.takeIf { it.isNotEmpty() } ?: return local()
            val uri = try {
                URI(raw)
            } catch (e: Exception) {
                throw IllegalArgumentException("public base must be a bare https origin")
            }
            if (uri.isOpaque) throw IllegalArgumentException("public base must be a bare https origin")
            require(uri.scheme?.lowercase() == "https") { "public base must be https" }
            if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) {
                throw IllegalArgumentException("public base must be a bare https origin")
            }
            val host = uri.host?.lowercase()
                ?: throw IllegalArgumentException("public base must be a bare https origin")
            val path = uri.path ?: ""
            if (path.isNotEmpty() && path != "/") {
                throw IllegalArgumentException("public base must be a bare https origin")
            }
            val port = uri.port
            val base = if (port == -1 || port == 443) "https://$host" else "https://$host:$port"
            return Endpoints(base, "$base/mcp")
        }

        /**
         * 回环端口（**唯一**的数值来源）：`McpService` 绑定、[local]、tunnel quick 命令都用它。
         * 三处各写一个 8765 迟早会漂。
         */
        const val PORT = 8765

        fun local(port: Int = PORT): Endpoints {
            val issuer = "http://127.0.0.1:$port"
            return Endpoints(issuer, "$issuer/mcp")
        }
    }
}

/**
 * OAuth 错误（RFC 6749 §5.2 / RFC 7591）。
 *
 * [code] 是线上稳定串（`invalid_request`/`invalid_client`/`invalid_grant`/`invalid_scope`/
 * `unauthorized_client`/`unsupported_grant_type`/`unsupported_response_type`/`invalid_target`/
 * `invalid_redirect_uri`/`invalid_client_metadata`/`access_denied`），HTTP 拆包层直接照 [status] 回。
 *
 * [desc] **只能**是固定文案：不得包含令牌、授权码、verifier、client secret 或其片段（错误响应会回到客户端）。
 */
class OAuthError(
    val code: String,
    val desc: String,
    val status: Int = 400,
    /** 可安全回跳的回调（只有 client 仍 active 且该 URL 仍注册时才有值）；否则 null。 */
    val redirectUri: String? = null,
    /** 回跳时带回的 `state`（客户端自己的 CSRF 值，原样回显）。 */
    val state: String? = null,
) : Exception(desc) {

    /**
     * 可 302 的 `error=…&state=…` 跳转；[redirectUri] 没有可信值时为 `null`（调用方按 [status] 直回）。
     *
     * Task 5 的 `/authorize?req=<id>` 分支就是这个形状：**denied 一律 302 回客户端**，
     * 只有"回调本身已不可信 / 行不存在"才退化成 410 文本页。
     */
    val redirectUrl: String?
        get() {
            val base = redirectUri ?: return null
            val q = StringBuilder("error=").append(oauthEncode(code))
            if (state != null) q.append("&state=").append(oauthEncode(state))
            q.append("&error_description=").append(oauthEncode(desc))
            return oauthAppendQuery(base, q.toString())
        }
}

/** `/authorize` 通过校验后的规范化请求（尚未入库；入库见 [OAuth.createOrReusePending]）。 */
data class AuthorizeValidation(
    val clientId: String,
    val redirectUri: String,
    val codeChallenge: String,
    val scopes: Set<String>,
    val resource: String,
    val state: String?,
)

/**
 * `GET /authorize?req=<id>` 的状态视图（Task 5 据此选分支，不用自己拼库查询）。
 *
 * [redirectUri] 只在当前仍可信时给值（client active 且该 URL 还在注册列表里）；否则 null。
 */
data class PendingStatus(
    val status: String,
    val redirectUri: String?,
    val state: String?,
    val expired: Boolean,
)

/**
 * 解析 `/mcp` 的 Bearer 之后得到的主体（计划决定 5）。
 *
 * [policies]/[windows] 带**全部**层（OAuth = 客户端层 + 令牌层；静态 key = key 层），
 * 管理页照这个列表展示；真正判定用 [allowedTools]（scope ∩ 各层策略，坏数据 ⇒ 空集）。
 * [kind] ∈ `"oauth" | "static" | "loopback"`；`loopback` 由 Task 5 的回环豁免构造，本文件不产生。
 */
data class Principal(
    val kind: String,
    val subject: String,
    val label: String,
    val scopes: Set<String>,
    val policies: List<String?>,
    val windows: List<String?>,
    val allowedTools: Set<String>,
    val expiresAt: Long,
)

/**
 * OAuth 2.1 授权服务器 + 资源服务器的**协议核心**（纯 JVM，无 `android.*`）。
 *
 * 这里只做协议判定与库操作，不碰 socket/HTML/限流（Task 5）。全部「消费」都走 [McpStore] 的单条 CAS。
 *
 * 不变式（改代码前先读）：
 * - scope 只有 [ALLOWED_SCOPES]；`ingest` 是保留 scope，永远不在这里发放（请求它 ⇒ `invalid_scope`）。
 * - 授权码 5 分钟、一次性；PKCE 只接受 `S256`。
 * - 令牌：访问 1 小时、刷新 30 天；刷新严格轮换，**重用一次吊销整个 family**。
 * - 刷新判定是「先 `tokenByHash` 读状态 ⇒ 不满足才走单条 `consumeRefresh` CAS」，
 *   不把 CAS 结果当授权依据（Review Focus 2）。
 * - client 每次重读：缺失/吊销/非 active ⇒ 拒绝（[Principals] 里还要吊销 family，fail closed）。
 */
object OAuth {

    /** 可发放的 scope；`health.read` + `health.write`，顺序固定（发现文档与默认授予都照它）。 */
    val ALLOWED_SCOPES: Set<String> = setOf(ToolCatalog.SCOPE_READ, ToolCatalog.SCOPE_WRITE)

    /** 默认授予（`scope` 缺省时）。 */
    val DEFAULT_SCOPES: Set<String> = ALLOWED_SCOPES

    /** DCR 客户端上限（spec §6.2）。 */
    const val MAX_CLIENTS = 200

    /** 单个客户端注册的回调上限（够本机多端口，又不给钓鱼页堆地址）。 */
    const val MAX_REDIRECT_URIS = 10

    /** `client_id` 前缀；它不是凭据（公开客户端），只是可读的类型标记。 */
    private const val CLIENT_ID_PREFIX = "hcb_ci_"

    /** `client_name` 入库/展示的长度上限。 */
    const val CLIENT_NAME_MAX = 100

    /** `state` 长度上限；超过就 `invalid_request`（不截断，见 [sanitizeState]）。 */
    private const val STATE_MAX = 512

    private val AUTH_METHODS = setOf("none", "client_secret_post")

    // ------------------------------------------------------------------ 发现文档

    /** `/.well-known/oauth-authorization-server`。 */
    fun serverMetadata(ep: Endpoints): JSONObject = JSONObject()
        .put("issuer", ep.issuer)
        .put("authorization_endpoint", "${ep.issuer}/authorize")
        .put("token_endpoint", "${ep.issuer}/token")
        .put("registration_endpoint", "${ep.issuer}/register")
        .put("revocation_endpoint", "${ep.issuer}/revoke")
        .put("response_types_supported", JSONArray().put("code"))
        .put("grant_types_supported", JSONArray().put("authorization_code").put("refresh_token"))
        .put("code_challenge_methods_supported", JSONArray().put("S256"))
        .put("token_endpoint_auth_methods_supported", JSONArray().put("none").put("client_secret_post"))
        .put("revocation_endpoint_auth_methods_supported", JSONArray().put("none").put("client_secret_post"))
        .put("scopes_supported", scopesArray())

    /** `/.well-known/oauth-protected-resource` 与 `…/mcp` 变体。 */
    fun resourceMetadata(ep: Endpoints): JSONObject = JSONObject()
        .put("resource", ep.resource)
        .put("authorization_servers", JSONArray().put(ep.issuer))
        .put("scopes_supported", scopesArray())
        .put("resource_name", "hc-bridge Health MCP")
        .put("bearer_methods_supported", JSONArray().put("header"))

    private fun scopesArray(): JSONArray =
        JSONArray().put(ToolCatalog.SCOPE_READ).put(ToolCatalog.SCOPE_WRITE)

    // ------------------------------------------------------------------ /authorize

    /**
     * 校验 `/authorize` 参数并规范化。
     *
     * **任何**失败都在暂存之前抛出（不落 pending、不重定向），这样钓鱼参数不会被"存起来等批准"。
     * 顺序：`response_type` → client（存在/未吊销/active）→ `redirect_uri` 精确匹配 → PKCE →
     * scope（未知/`ingest` ⇒ `invalid_scope`）→ `resource`（给了就必须等于当前 resource）。
     */
    fun validateAuthorize(
        store: McpStore,
        params: Map<String, String>,
        ep: Endpoints,
        now: Long,
    ): AuthorizeValidation {
        if (params["response_type"] != "code") {
            throw OAuthError("unsupported_response_type", "response_type must be code")
        }
        val clientId = params["client_id"]?.trim().orEmpty()
        if (clientId.isEmpty()) throw OAuthError("invalid_request", "client_id is required")
        val client = store.client(clientId) ?: throw OAuthError("invalid_request", "unknown client")
        if (client.revokedAt != null || client.status != McpStatus.CLIENT_ACTIVE) {
            throw OAuthError("unauthorized_client", "client is not active", 403)
        }

        val redirect = params["redirect_uri"]?.trim().orEmpty()
        val registered = parseRedirects(client.redirectUrisJson)
        if (redirect.isEmpty() || registered == null || registered.none { it == redirect }) {
            // redirect 没注册 ⇒ 不重定向，直接 400。之后的错误才能安全地 302 回 redirect。
            throw OAuthError("invalid_request", "redirect_uri is not registered for this client")
        }

        if (params["code_challenge_method"] != "S256") {
            throw OAuthError("invalid_request", "code_challenge_method must be S256")
        }
        val challenge = params["code_challenge"]?.trim().orEmpty()
        if (!challengeValid(challenge)) throw OAuthError("invalid_request", "invalid code_challenge")

        val scopes = requestedScopes(params["scope"])

        val resource = params["resource"]?.trim()?.takeIf { it.isNotEmpty() } ?: ep.resource
        if (resource != ep.resource) throw OAuthError("invalid_target", "unknown resource")

        return AuthorizeValidation(clientId, redirect, challenge, scopes, resource, sanitizeState(params["state"]))
    }

    /**
     * 幂等暂存：同一 `client + challenge + redirect + resource + scopes` 还有未过期的 `pending` 行 ⇒
     * 复用（返回 `id to false`），否则新建（`id to true`）。行内 id 是 `hcb_rq_…`（不可猜、非凭据）。
     */
    fun createOrReusePending(
        store: McpStore,
        v: AuthorizeValidation,
        now: Long,
    ): Pair<String, Boolean> {
        val scopes = ScopeJson.normalize(v.scopes.toList())
        val existing = store.findPending(v.clientId, v.codeChallenge, v.redirectUri, v.resource, scopes, v.state, now)
        if (existing != null) return existing.id to false
        val id = TokenFormat.newRequestId()
        store.putPending(
            PendingRow(
                id = id,
                clientId = v.clientId,
                redirectUri = v.redirectUri,
                codeChallenge = v.codeChallenge,
                scopes = scopes,
                resource = v.resource,
                state = v.state,
                createdAt = now,
                expiresAt = now + TokenFormat.PENDING_TTL_MS,
                status = McpStatus.PENDING,
            ),
        )
        return id to true
    }

    /**
     * 管理页的「允许 / 拒绝」：只做 `pending → approved | denied` 的单条 CAS。
     *
     * 批准时把**授予 scope**（请求 ∩ [grantedScopes] ∩ [ALLOWED_SCOPES]）写进同一行；
     * 授予集为空、请求已过期或 client 已不可用 ⇒ 返回 `false`（什么都没有改变）。
     */
    fun decidePending(
        store: McpStore,
        id: String,
        approve: Boolean,
        grantedScopes: Set<String>,
        now: Long,
    ): Boolean {
        val p = store.pending(id) ?: return false
        if (p.status != McpStatus.PENDING || p.expiresAt <= now) return false
        val client = store.client(p.clientId) ?: return false
        if (client.revokedAt != null || client.status != McpStatus.CLIENT_ACTIVE) return false
        val grant = if (approve) {
            ScopeJson.normalize(p.scopes.filter { it in ALLOWED_SCOPES && it in grantedScopes })
        } else {
            emptyList()
        }
        if (approve && grant.isEmpty()) return false
        val target = if (approve) McpStatus.APPROVED else McpStatus.DENIED
        return store.decidePending(id, target, now, if (approve) grant else null)
    }

    /**
     * 浏览器刷新 `/authorize?req=<id>` 时调用：**先** `approved → consumed` CAS，成功后才发码。
     *
     * 不存在 / 未批准 / 已消费 / 过期 / client 已不可用 ⇒ `access_denied`（410），**不发码**。
     * 其中**用户拒绝**那一支带 `redirectUri`/`state`（[OAuthError.redirectUrl]），Task 5 应当 302 回客户端；
     * 其余（行不在、过期、已消费、回调不再可信）是裸 410，不重定向。返回拼好 `code` 与 `state` 的回调 URL。
     */
    fun completePending(store: McpStore, id: String, now: Long): String {
        val p = store.pending(id) ?: throw deniedPending()
        if (p.status == McpStatus.DENIED) throw deniedPending(p, trustedRedirect(store, p))
        if (p.status != McpStatus.APPROVED || p.expiresAt <= now) throw deniedPending()
        // Task 3 的交待：批准之后 client 可能被吊销/降回 pending，发码前必须重读。
        // Task 4 review：回调也可能被从注册表里删掉/改掉，所以发码目标同样要重校（[trustedRedirect]）。
        val redirect = trustedRedirect(store, p) ?: throw deniedPending()
        if (!store.consumePending(id, now)) throw deniedPending()

        val code = TokenFormat.newCode()
        store.putCode(
            CodeRow(
                codeHash = Crypto.sha256Hex(code),
                clientId = p.clientId,
                redirectUri = p.redirectUri,
                codeChallenge = p.codeChallenge,
                scopes = ScopeJson.normalize(p.scopes),
                resource = p.resource,
                createdAt = now,
                expiresAt = now + TokenFormat.CODE_TTL_MS,
            ),
        )
        return appendQuery(redirect, code, p.state)
    }

    /**
     * `GET /authorize?req=<id>` 的状态查询（`?req=` 的状态刷新不占限流额度，见计划决定 4）。
     *
     * Task 5 **就照这个分支**，不要自己再查库：
     * - `status == "denied"` ⇒ 302 `redirectUri + error=access_denied&state=…`（[redirectUri] 为 null ⇒ 410 文本页）；
     * - `status == "approved" && !expired` ⇒ 调 [completePending]，它的返回值就是 302 目标；
     * - `status == "pending"` ⇒ 200 等待页（`<a href="hcbridge://oauth/consent?req=…">` + 跨设备说明）；
     * - `status == "consumed"` 或 `expired` ⇒ 410 文本页（**不要**再重定向：码已经发过 / 请求超时）。
     *
     * 行不存在 ⇒ `null`（Task 5 回 404/410 文本页）。
     */
    fun pendingStatus(store: McpStore, id: String, now: Long): PendingStatus? {
        val p = store.pending(id) ?: return null
        val trusted = when (p.status) {
            McpStatus.DENIED, McpStatus.APPROVED -> trustedRedirect(store, p)
            else -> null
        }
        return PendingStatus(
            status = p.status,
            redirectUri = trusted,
            state = p.state,
            expired = p.expiresAt <= now,
        )
    }

    // ------------------------------------------------------------------ /register

    /**
     * RFC 7591 动态客户端注册。
     *
     * 回调逐条校验（[redirectUriAllowed]）；`token_endpoint_auth_method` 只接受 `none`（公开）与
     * `client_secret_post`（机密，secret 只返回这一次、库里只存 SHA-256）。
     * [confirmNewClients] 为真 ⇒ 落 `status='pending'`，管理员在 app 内批准后才能 `/authorize`（决定 9）。
     */
    fun registerClient(
        store: McpStore,
        body: JSONObject,
        confirmNewClients: Boolean,
        now: Long,
    ): JSONObject {
        if (store.clientCount() >= MAX_CLIENTS) {
            throw OAuthError("invalid_client_metadata", "client limit reached")
        }
        val uris = body.optJSONArray("redirect_uris")
            ?: throw OAuthError("invalid_redirect_uri", "redirect_uris is required")
        if (uris.length() == 0 || uris.length() > MAX_REDIRECT_URIS) {
            throw OAuthError("invalid_redirect_uri", "redirect_uris count is out of range")
        }
        val redirects = LinkedHashSet<String>()
        for (i in 0 until uris.length()) {
            val raw = uris.optString(i, "").trim()
            if (!redirectUriAllowed(raw)) {
                throw OAuthError("invalid_redirect_uri", "redirect_uri must be https or loopback http")
            }
            redirects.add(raw)
        }

        val authMethod = body.optString("token_endpoint_auth_method", "")
            .trim()
            .ifEmpty { "none" }
        if (authMethod !in AUTH_METHODS) {
            throw OAuthError("invalid_client_metadata", "unsupported token_endpoint_auth_method")
        }

        val name = sanitizeName(body.optString("client_name", ""))

        val clientId = CLIENT_ID_PREFIX + Crypto.randomToken(16)
        val secret = if (authMethod == "client_secret_post") TokenFormat.newClientSecret() else null
        val status = if (confirmNewClients) McpStatus.CLIENT_PENDING else McpStatus.CLIENT_ACTIVE
        store.putClient(
            ClientRow(
                clientId = clientId,
                secretHash = secret?.let { Crypto.sha256Hex(it) },
                name = name,
                redirectUrisJson = JSONArray(redirects.toList()).toString(),
                toolPolicyJson = null,
                windowJson = null,
                status = status,
                createdAt = now,
            ),
        )

        val out = JSONObject()
            .put("client_id", clientId)
            .put("client_id_issued_at", now / 1000)
            .put("client_name", name)
            .put("redirect_uris", JSONArray(redirects.toList()))
            .put("grant_types", JSONArray().put("authorization_code").put("refresh_token"))
            .put("response_types", JSONArray().put("code"))
            .put("token_endpoint_auth_method", authMethod)
            .put("scope", ScopeJson.normalize(DEFAULT_SCOPES.toList()).joinToString(" "))
        if (secret != null) {
            out.put("client_secret", secret).put("client_secret_expires_at", 0)
        }
        return out
    }

    /**
     * 回调白名单：`https`（任意主机），或 `http` 且主机恰为 `127.0.0.1` / `localhost` / `[::1]`。
     *
     * 拒绝 fragment（RFC 6749 §3.1.2）、带 userinfo 的地址、非绝对 URI 与未知 scheme；
     * 主机**精确**比较，所以 `http://127.0.0.1.evil.com` 不会被当成本机。
     */
    fun redirectUriAllowed(raw: String): Boolean {
        if (raw.isEmpty() || raw.contains('#')) return false
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        if (uri.isOpaque) return false
        if (uri.userInfo != null) return false
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (host.isEmpty()) return false
        return when (scheme) {
            "https" -> true
            "http" -> host == "127.0.0.1" || host == "localhost" || host == "[::1]"
            else -> false
        }
    }

    // ------------------------------------------------------------------ /token

    /**
     * `grant_type=authorization_code`。
     *
     * 先重读并认证 client（吊销/非 active ⇒ `invalid_client`，**不**消费授权码），再 CAS 取码，
     * 然后绑定校验 client/redirect/`resource` 与 PKCE；任何不匹配都是 `invalid_grant`。
     * 授权码一旦取出即作废（失败也不还回去，避免"试到对为止"）。
     */
    fun exchange(
        store: McpStore,
        params: Map<String, String>,
        ep: Endpoints,
        now: Long,
    ): JSONObject {
        if (params["grant_type"] != "authorization_code") {
            throw OAuthError("unsupported_grant_type", "grant_type must be authorization_code")
        }
        val clientId = required(params, "client_id")
        val rawCode = required(params, "code")
        val redirect = required(params, "redirect_uri")
        val verifier = required(params, "code_verifier")

        val client = authenticate(store, clientId, params["client_secret"])

        val row = store.takeCode(Crypto.sha256Hex(rawCode), now) ?: throw invalidGrant()
        if (row.clientId != clientId) throw invalidGrant()
        if (row.redirectUri != redirect) throw invalidGrant()
        if (row.resource != ep.resource) throw invalidGrant()
        val resourceParam = params["resource"]?.trim().orEmpty()
        if (resourceParam.isNotEmpty() && resourceParam != ep.resource) {
            throw OAuthError("invalid_target", "unknown resource")
        }
        if (!Crypto.pkceVerifierValid(verifier)) throw invalidGrant()
        if (!Crypto.constantTimeEqualsHex(Crypto.pkceS256(verifier), row.codeChallenge)) throw invalidGrant()

        val scopes = ScopeJson.normalize(row.scopes).filter { it in ALLOWED_SCOPES }
        if (scopes.isEmpty()) throw invalidGrant()

        return issue(
            store = store,
            clientId = client.clientId,
            familyId = Crypto.randomToken(16),
            scopes = scopes,
            resource = ep.resource,
            now = now,
            policy = null,
            window = null,
        )
    }

    /**
     * `grant_type=refresh_token`，严格轮换（计划 Review Focus 2）。
     *
     * 步骤固定：
     * 1. 重读并认证 client（吊销/非 active ⇒ `invalid_client`）；
     * 2. **先读** `tokenByHash`：行已 `revoked` 或已 `replacedByHash` ⇒ 这是重用 ⇒
     *    `revokeFamily` + `invalid_grant`（**不进** CAS）；
     * 3. 其余静态不匹配（kind、归属、过期、resource）⇒ `invalid_grant`，不动库；
     * 4. 才算一次 `consumeRefresh` 单条 CAS（并发被抢同样 `invalid_grant`）；
     * 5. 成功后一次 `putToken` 写全新刷新行 + 新访问行（同 family），旧行不再回头写。
     *
     * 令牌级策略/时间窗随新行继承，管理页的收窄不会因为刷新被"洗掉"。
     *
     * **TTL 是滑动窗口**：每轮刷新都把新刷新令牌写成 `now + REFRESH_TTL_MS`，所以常用客户端可以
     * 一直在线（30 天是"不活动期上限"，不是"会话总长"）。代价有两条，都是有意接受、不要私自改：
     * 1. 没有绝对会话上限 —— 想要绝对上限就得在 family 上锚定首次签发时间，那要加列/改 schema；
     * 2. 旧行不删（`revoked_at` 只标记），过期令牌行会持续累积。
     *    **Task 6/7 补 `McpStore.deleteExpiredTokens(now)`**（清过期 access/refresh 行、过期/已用授权码、
     *    过期 pending），在 `/token` 的写路径或管理页 prune 时调用；本轮不动存储层。
     */
    fun refresh(
        store: McpStore,
        params: Map<String, String>,
        ep: Endpoints,
        now: Long,
    ): JSONObject {
        if (params["grant_type"] != "refresh_token") {
            throw OAuthError("unsupported_grant_type", "grant_type must be refresh_token")
        }
        val clientId = required(params, "client_id")
        val raw = required(params, "refresh_token")

        val client = authenticate(store, clientId, params["client_secret"])

        val hash = Crypto.sha256Hex(raw)
        val seen = store.tokenByHash(hash)
        if (seen != null && (seen.revokedAt != null || seen.replacedByHash != null)) {
            store.revokeFamily(seen.familyId, now)
            throw invalidGrant()
        }
        if (seen == null || seen.kind != McpStatus.KIND_REFRESH) throw invalidGrant()
        if (seen.clientId != clientId) throw invalidGrant()
        if (seen.expiresAt <= now) throw invalidGrant()
        if (seen.resource != ep.resource) throw invalidGrant()

        val scopes = ScopeJson.normalize(seen.scopes).filter { it in ALLOWED_SCOPES }
        if (scopes.isEmpty()) throw invalidGrant()

        val nextRefresh = TokenFormat.newRefreshToken()
        val nextHash = Crypto.sha256Hex(nextRefresh)
        store.consumeRefresh(hash, now, nextHash) ?: throw invalidGrant()

        val access = TokenFormat.newAccessToken()
        val nowTokens = listOf(
            TokenRow(
                tokenHash = nextHash,
                prefix = TokenFormat.prefixOf(nextRefresh),
                kind = McpStatus.KIND_REFRESH,
                clientId = client.clientId,
                familyId = seen.familyId,
                scopes = scopes,
                toolPolicyJson = seen.toolPolicyJson,
                windowJson = seen.windowJson,
                resource = seen.resource,
                createdAt = now,
                expiresAt = now + TokenFormat.REFRESH_TTL_MS,
            ),
            TokenRow(
                tokenHash = Crypto.sha256Hex(access),
                prefix = TokenFormat.prefixOf(access),
                kind = McpStatus.KIND_ACCESS,
                clientId = client.clientId,
                familyId = seen.familyId,
                scopes = scopes,
                toolPolicyJson = seen.toolPolicyJson,
                windowJson = seen.windowJson,
                resource = seen.resource,
                createdAt = now,
                expiresAt = now + TokenFormat.ACCESS_TTL_MS,
            ),
        )
        for (t in nowTokens) store.putToken(t)

        return tokenResponse(access, nextRefresh, scopes, seen.resource)
    }

    /**
     * RFC 7009 撤销，**始终静默**（HTTP 层回 200；不区分"存在/不存在"）。
     *
     * - `client_id` 给了但与令牌归属不符 ⇒ 什么都不做（别人的客户端撤不掉你的令牌）；
     * - 机密客户端还要求 secret 正确；
     * - 撤销刷新令牌 ⇒ 该 family 全部作废（访问令牌跟着死）；撤销访问令牌只撤这一枚。
     */
    fun revoke(store: McpStore, params: Map<String, String>, ep: Endpoints, now: Long) {
        val raw = params["token"]?.trim().orEmpty()
        if (raw.isEmpty()) return
        val row = store.tokenByHash(Crypto.sha256Hex(raw)) ?: return
        val claimed = params["client_id"]?.trim().orEmpty()
        if (claimed.isNotEmpty() && claimed != row.clientId) return
        val client = store.client(row.clientId) ?: return
        val expectedSecret = client.secretHash
        if (expectedSecret != null) {
            val given = params["client_secret"]?.trim().orEmpty()
            if (given.isEmpty()) return
            if (!Crypto.constantTimeEqualsHex(Crypto.sha256Hex(given), expectedSecret)) return
        }
        if (row.kind == McpStatus.KIND_REFRESH) {
            store.revokeFamily(row.familyId, now)
        } else {
            store.revokeToken(row.tokenHash, now)
        }
    }

    // ------------------------------------------------------------------ 内部

    private fun issue(
        store: McpStore,
        clientId: String,
        familyId: String,
        scopes: List<String>,
        resource: String,
        now: Long,
        policy: String?,
        window: String?,
    ): JSONObject {
        val access = TokenFormat.newAccessToken()
        val refresh = TokenFormat.newRefreshToken()
        store.putToken(
            TokenRow(
                tokenHash = Crypto.sha256Hex(refresh),
                prefix = TokenFormat.prefixOf(refresh),
                kind = McpStatus.KIND_REFRESH,
                clientId = clientId,
                familyId = familyId,
                scopes = scopes,
                toolPolicyJson = policy,
                windowJson = window,
                resource = resource,
                createdAt = now,
                expiresAt = now + TokenFormat.REFRESH_TTL_MS,
            ),
        )
        store.putToken(
            TokenRow(
                tokenHash = Crypto.sha256Hex(access),
                prefix = TokenFormat.prefixOf(access),
                kind = McpStatus.KIND_ACCESS,
                clientId = clientId,
                familyId = familyId,
                scopes = scopes,
                toolPolicyJson = policy,
                windowJson = window,
                resource = resource,
                createdAt = now,
                expiresAt = now + TokenFormat.ACCESS_TTL_MS,
            ),
        )
        return tokenResponse(access, refresh, scopes, resource)
    }

    private fun tokenResponse(
        access: String,
        refresh: String,
        scopes: List<String>,
        resource: String,
    ): JSONObject = JSONObject()
        .put("access_token", access)
        .put("token_type", "Bearer")
        .put("expires_in", TokenFormat.ACCESS_TTL_MS / 1000)
        .put("refresh_token", refresh)
        .put("scope", ScopeJson.normalize(scopes).joinToString(" "))
        .put("resource", resource)

    /** 重读 client + 认证；任何失败都是 `invalid_client`（401），消息固定。 */
    private fun authenticate(store: McpStore, clientId: String, secret: String?): ClientRow {
        val client = store.client(clientId) ?: throw invalidClient()
        if (client.revokedAt != null || client.status != McpStatus.CLIENT_ACTIVE) throw invalidClient()
        val expected = client.secretHash
        if (expected != null) {
            val given = secret?.trim().orEmpty()
            if (given.isEmpty()) throw invalidClient()
            if (!Crypto.constantTimeEqualsHex(Crypto.sha256Hex(given), expected)) throw invalidClient()
        }
        return client
    }

    private fun required(params: Map<String, String>, key: String): String {
        val v = params[key]?.trim().orEmpty()
        if (v.isEmpty()) throw OAuthError("invalid_request", "$key is required")
        return v
    }

    private fun requestedScopes(raw: String?): Set<String> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return DEFAULT_SCOPES
        val wanted = LinkedHashSet<String>()
        for (part in text.split(' ', '\t', '\n', '\r')) {
            if (part.isEmpty()) continue
            if (part !in ALLOWED_SCOPES) throw OAuthError("invalid_scope", "unsupported scope")
            wanted.add(part)
        }
        if (wanted.isEmpty()) throw OAuthError("invalid_scope", "unsupported scope")
        return wanted
    }

    /** S256 的输出是 43 字符 base64url；这里按 RFC 7636 §4.2 的字符集与 43..128 边界收。 */
    private fun challengeValid(challenge: String): Boolean {
        if (challenge.length !in 43..128) return false
        for (c in challenge) {
            val ok = (c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') || c == '-' || c == '_'
            if (!ok) return false
        }
        return true
    }

    /** 解析失败返回 null（调用方一律当作"不可信"）；不是 JSON 数组也算失败。 */
    private fun parseRedirects(json: String): List<String>? {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return null
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "")
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    private fun appendQuery(uri: String, code: String, state: String?): String {
        val sb = StringBuilder("code=").append(oauthEncode(code))
        if (state != null) sb.append("&state=").append(oauthEncode(state))
        return oauthAppendQuery(uri, sb.toString())
    }

    /**
     * 回调仍可信才返回值：client 存在、未吊销、`active`，且该 URL 还在当前注册列表里。
     *
     * 发码目标与 denied 的 302 目标都必须过这一关（否则就是拿服务端暂存的 redirect 当开放重定向）。
     */
    private fun trustedRedirect(store: McpStore, p: PendingRow): String? {
        val client = store.client(p.clientId) ?: return null
        if (client.revokedAt != null || client.status != McpStatus.CLIENT_ACTIVE) return null
        val registered = parseRedirects(client.redirectUrisJson) ?: return null
        return p.redirectUri.takeIf { uri -> registered.any { it == uri } }
    }

    /**
     * `state` 清洗：`null`/空 ⇒ `null`；长度上限 [STATE_MAX]（超了直接 `invalid_request`，不截断——
     * 截断会悄悄破坏客户端的 CSRF 比对）；只允许可见 ASCII（0x20..0x7E），
     * 因为它要原样出现在 302 的 Location 与 HTML 里。
     */
    private fun sanitizeState(raw: String?): String? {
        val s = raw ?: return null
        if (s.isEmpty()) return null
        if (s.length > STATE_MAX) throw OAuthError("invalid_request", "state is too long")
        for (c in s) {
            if (c.code < 0x20 || c.code > 0x7E) {
                throw OAuthError("invalid_request", "state contains invalid characters")
            }
        }
        return s
    }

    /** `client_name` 清洗：控制字符（`<0x20` 与 `0x7F`）折成空格、压掉首尾空白、截到 [CLIENT_NAME_MAX]。 */
    private fun sanitizeName(raw: String): String {
        val sb = StringBuilder(minOf(raw.length, CLIENT_NAME_MAX))
        for (c in raw) {
            if (sb.length >= CLIENT_NAME_MAX) break
            sb.append(if (c.code < 0x20 || c.code == 0x7F) ' ' else c)
        }
        return sb.toString().trim().ifEmpty { "MCP client" }
    }

    private fun invalidClient() = OAuthError("invalid_client", "client authentication failed", 401)

    private fun invalidGrant() = OAuthError("invalid_grant", "grant is invalid or expired")

    /**
     * 410 的统一形状。[redirect] 非空（只有 [McpStatus.DENIED] 且回调仍可信时）⇒
     * [OAuthError.redirectUrl] 给出 `error=access_denied&state=…`，Task 5 照它 302 回客户端；
     * 其余情况（不存在/未批准/已消费/过期/client 不可用）是**裸 410**，不重定向。
     */
    private fun deniedPending(p: PendingRow? = null, redirect: String? = null) = OAuthError(
        code = "access_denied",
        desc = "authorization request is not approved",
        status = 410,
        redirectUri = redirect,
        state = if (redirect != null) p?.state else null,
    )
}

// ---------------------------------------------------------------- 顶层小工具

/** 查询串编码（`+` 代表空格；回调里 code/state 都是 ASCII，够用且与 HTTP 层一致）。 */
private fun oauthEncode(v: String): String = URLEncoder.encode(v, "UTF-8")

/** 在已有查询串上追加（保留客户端自带参数）。 */
private fun oauthAppendQuery(uri: String, query: String): String {
    val sep = if (uri.contains('?')) "&" else "?"
    return uri + sep + query
}
