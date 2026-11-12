package id.harness.hcbridge.auth

import java.net.URLDecoder
import org.json.JSONObject

/**
 * 路由结果（计划 Task 5）。
 *
 * - [Respond]：直接回浏览器（发现文档、等待页、302、401、410、429…）。
 * - [Mcp]：已通过鉴权门的 `/mcp` 请求，交给拆包层执行 JSON-RPC（[McpRpc]）并回写响应。
 *   主体在这里定下来，执行层不再重读令牌。
 */
sealed class Route {

    data class Respond(val resp: HttpResp) : Route()

    data class Mcp(val principal: Principal, val body: String) : Route()
}

/**
 * 回环豁免判定（计划 Review Focus 1 / 全局约束「鉴权优先级」）。
 *
 * **六条全满足**才豁免，少一条都必须 Bearer：
 * 1. `requireAuth == false`；
 * 2. `allowLoopbackNoAuth == true`（唯一的显式开关，`requireAuth=false` 单独不算）；
 * 3. `publicBase == null`（没有公网入口）；
 * 4. `tunnelMode == "off"`（没有 tunnel）；
 * 5. 对端就是回环地址；
 * 6. 请求**不带任何**转发头（`cf-connecting-ip` / `x-forwarded-for` / `cf-ray` / `x-real-ip` /
 *    `forwarded` / `true-client-ip` …）：有转发头就说明它其实来自 tunnel，不是本机。
 *
 * 第 6 条是防"tunnel 借道回环"：`cloudflared` 连的是 `127.0.0.1`，但它一定会带上转发头。
 */
fun trustedLoopback(req: HttpReq, config: McpConfig): Boolean {
    if (config.requireAuth) return false
    if (!config.allowLoopbackNoAuth) return false
    if (config.publicBase != null) return false
    if (config.tunnelMode != McpConfig.TUNNEL_OFF) return false
    if (!req.peerLoopback) return false
    for (key in req.headers.keys) if (key.lowercase() in FORWARDING_HEADERS) return false
    return true
}

/** 只要出现就说明"不是本机直连"的头（大小写不敏感，值不参与判定）。 */
internal val FORWARDING_HEADERS: Set<String> = setOf(
    "cf-connecting-ip",
    "cf-ray",
    "cf-ipcountry",
    "cf-visitor",
    "cdn-loop",
    "x-forwarded-for",
    "x-forwarded-host",
    "x-forwarded-proto",
    "x-real-ip",
    "true-client-ip",
    "forwarded",
)

/**
 * HTTP 路由表 + `/mcp` 鉴权门 + 限流（计划 Task 5，纯 JVM）。
 *
 * 路由表（**HTTP 上没有任何管理路由**，`/admin*` 一律 404）：
 *
 * | 方法 | 路径 | 行为 |
 * |---|---|---|
 * | GET | `/.well-known/oauth-authorization-server` | 200 发现文档 |
 * | GET | `/.well-known/oauth-protected-resource[/mcp]` | 200 资源元数据 |
 * | POST | `/register` | 10/h → 201 / 400 / 429 |
 * | GET | `/authorize` | 无 `req`：30/min → 200 等待页 / 303 `?req=`；有 `req`：状态页 |
 * | POST | `/token` | 60/min → 200 / 400 / 429，恒 `no-store` |
 * | POST | `/revoke` | 恒 200 |
 * | GET | `/health` | `{"ok":true}` |
 * | POST | `/mcp[/…]` | 鉴权门 → [Route.Mcp]（401/413/405） |
 *
 * 状态页 `GET /authorize?req=<id>` 的三个分支（计划决定 4）：
 * - `pending` 且未过期 ⇒ **200 等待页，且这一支不占限流额度**（浏览器每 3 秒刷新）；
 * - `approved` ⇒ `completePending` 后 302 到回调（`code` + `state`）；
 * - `denied` ⇒ 302 到回调（`error=access_denied` + `state`）；
 * - 已 `consumed` / 过期 / 不存在 ⇒ 410，**不重定向**。
 *
 * 没有 `POST /authorize`：同意只在 app 内做（决定 2，无网页表单 ⇒ 无 Cookie 会话 ⇒ 无 CSRF 面）。
 *
 * 本文件**不得** import 任何 `android.*`。
 */
class McpRouter(
    private val store: McpStore,
    private val config: McpConfig,
    val clock: () -> Long = System::currentTimeMillis,
    private val limiter: RateLimiter = RateLimiter(),
) {

    /** 当前 issuer/resource；公网基址写坏时退回本机（发现文档不能因为一个脏设置就崩）。 */
    fun endpoints(): Endpoints = endpointsOf(config)

    /**
     * [endpoints] 是否退回了本机（`publicBase` 写坏、不是裸 https origin）。
     *
     * 退回是**静默降级**：鉴权与令牌绑定仍然正确（issuer/resource 一起变），但客户端会看到
     * `http://127.0.0.1:8765`。管理页「状态」段与通知文案必须显示这一位，
     * 不能让管理员以为公网地址还在生效（Task 6/7 用）。
     */
    fun endpointsFallback(): Boolean = endpointsFallbackOf(config)

    fun route(req: HttpReq): Route {
        val method = req.method.trim().uppercase()
        val path = normalizePath(req.path)
        val now = clock()

        if (isMcp(path)) return mcp(req, method, now)

        // 请求体上限在**所有** POST 上统一挡（按字节，先看声明长度；body 已在 [BodyReader] 里限过）。
        if (method != "GET" && method != "HEAD") {
            if (BodyReader.declaredTooLarge(req.headers["content-length"]?.trim()?.toIntOrNull())) {
                return Route.Respond(payloadTooLarge())
            }
            if (utf8Length(req.body) > BodyReader.CAP) return Route.Respond(payloadTooLarge())
        }

        return when (path) {
            "/health" -> get(method) { Route.Respond(HttpResp.json(200, JSONObject().put("ok", true))) }

            "/.well-known/oauth-authorization-server" -> get(method) {
                Route.Respond(HttpResp.json(200, OAuth.serverMetadata(endpoints())))
            }

            "/.well-known/oauth-protected-resource",
            "/.well-known/oauth-protected-resource/mcp" -> get(method) {
                Route.Respond(HttpResp.json(200, OAuth.resourceMetadata(endpoints())))
            }

            "/register" -> post(method) { register(req, now) }
            "/authorize" -> get(method) { authorize(req, now) }
            "/token" -> post(method) { token(req, now) }
            "/revoke" -> post(method) { revoke(req, now) }

            else -> Route.Respond(notFound())
        }
    }

    // ------------------------------------------------------------------ 端点

    private fun register(req: HttpReq, now: Long): Route {
        if (!limit(req, "register", REGISTER_MAX, WINDOW_HOUR)) return Route.Respond(tooMany(false, WINDOW_HOUR))
        val parsed = try {
            JsonCanon.parse(req.body)
        } catch (e: Exception) {
            null
        }
        if (parsed !is JSONObject) return Route.Respond(oauthError(400, "invalid_client_metadata", "body must be a JSON object"))
        return try {
            val out = OAuth.registerClient(store, parsed, config.confirmNewClients, now)
            Route.Respond(HttpResp.json(201, out, mapOf("Pragma" to "no-cache")))
        } catch (e: OAuthError) {
            Route.Respond(oauthError(e.status, e.code, e.desc))
        }
    }

    private fun authorize(req: HttpReq, now: Long): Route {
        // 每次进入先清过期 pending（计划决定 4）；状态页与首屏都做，避免过期行堆在库里。
        store.deleteExpiredPending(now)
        val id = req.query["req"]?.trim().orEmpty()
        return if (id.isEmpty()) authorizeStart(req, now) else authorizeStatus(req, id, now)
    }

    private fun authorizeStart(req: HttpReq, now: Long): Route {
        if (!limit(req, "authorize", AUTHORIZE_MAX, WINDOW_MINUTE)) return Route.Respond(tooMany(true, WINDOW_MINUTE))
        val validation = try {
            OAuth.validateAuthorize(store, req.query, endpoints(), now)
        } catch (e: OAuthError) {
            return Route.Respond(
                if (e.status == 403) forbiddenHtml() else errorHtml(e.status, e.desc),
            )
        }
        val (id, created) = OAuth.createOrReusePending(store, validation, now)
        return if (created) {
            Route.Respond(waitPage(id))
        } else {
            Route.Respond(
                HttpResp(
                    303,
                    mapOf("Location" to "/authorize?req=$id", "Cache-Control" to "no-store"),
                    "",
                ),
            )
        }
    }

    private fun authorizeStatus(req: HttpReq, id: String, now: Long): Route {
        val status = OAuth.pendingStatus(store, id, now)
        // 只有「仍是 pending 且未过期」这一支免限流：浏览器每 3 秒刷新，不能把它算成额度。
        val exempt = status != null && status.status == McpStatus.PENDING && !status.expired
        if (!exempt && !limit(req, "authorize", AUTHORIZE_MAX, WINDOW_MINUTE)) {
            return Route.Respond(tooMany(true, WINDOW_MINUTE))
        }
        if (exempt) return Route.Respond(waitPage(id))
        if (status == null) return Route.Respond(gonePage())

        // approved ⇒ 发码；denied ⇒ access_denied；consumed/过期 ⇒ 410（都不重定向）。
        return try {
            Route.Respond(redirect(OAuth.completePending(store, id, now)))
        } catch (e: OAuthError) {
            val url = e.redirectUrl
            if (url != null) Route.Respond(redirect(url)) else Route.Respond(gonePage())
        }
    }

    private fun token(req: HttpReq, now: Long): Route {
        if (!limit(req, "token", TOKEN_MAX, WINDOW_MINUTE)) return Route.Respond(tooMany(false, WINDOW_MINUTE))
        val params = formParams(req.body)
            ?: return Route.Respond(oauthError(400, "invalid_request", "malformed request body"))
        return try {
            val out = when (params["grant_type"]) {
                "authorization_code" -> OAuth.exchange(store, params, endpoints(), now)
                "refresh_token" -> OAuth.refresh(store, params, endpoints(), now)
                else -> throw OAuthError("unsupported_grant_type", "unsupported grant_type")
            }
            Route.Respond(HttpResp.json(200, out, mapOf("Pragma" to "no-cache")))
        } catch (e: OAuthError) {
            Route.Respond(oauthError(e.status, e.code, e.desc))
        }
    }

    private fun revoke(req: HttpReq, now: Long): Route {
        val params = formParams(req.body)
        if (params != null) {
            // RFC 7009：不论令牌是否存在都回 200，且不回任何错误细节。
            runCatching { OAuth.revoke(store, params, endpoints(), now) }
        }
        return Route.Respond(HttpResp(200, mapOf("Cache-Control" to "no-store"), ""))
    }

    // ------------------------------------------------------------------ /mcp 鉴权门

    private fun mcp(req: HttpReq, method: String, now: Long): Route {
        if (method != "POST") return Route.Respond(methodNotAllowed("POST"))
        val declared = req.headers["content-length"]?.trim()?.toIntOrNull()
        if (BodyReader.declaredTooLarge(declared)) return Route.Respond(payloadTooLarge())
        if (utf8Length(req.body) > BodyReader.CAP) return Route.Respond(payloadTooLarge())

        if (trustedLoopback(req, config)) return Route.Mcp(loopbackPrincipal(), req.body)

        val raw = bearer(req) ?: return Route.Respond(unauthorized(invalidToken = false))
        val principal = Principals.resolve(store, raw, endpoints().resource, now)
            ?: return Route.Respond(unauthorized(invalidToken = true))
        return Route.Mcp(principal, req.body)
    }

    /** 回环豁免时的主体：本机管理员本人，全工具、无策略、无时间窗。 */
    private fun loopbackPrincipal(): Principal = Principal(
        kind = "loopback",
        subject = "local",
        label = "本机",
        scopes = setOf(ToolCatalog.SCOPE_READ, ToolCatalog.SCOPE_WRITE),
        policies = emptyList(),
        windows = emptyList(),
        allowedTools = ToolCatalog.ALL_TOOLS,
        expiresAt = Long.MAX_VALUE,
    )

    private fun bearer(req: HttpReq): String? {
        val raw = req.headers["authorization"]?.trim().orEmpty()
        if (raw.length <= 7 || !raw.startsWith("Bearer ", ignoreCase = true)) return null
        return raw.substring(7).trim().ifEmpty { null }
    }

    /**
     * RFC 6750 挑战：`resource_metadata` 一定带上（客户端据此找到 AS），
     * 令牌无效时再加 `error="invalid_token"`；不带任何令牌细节。
     */
    private fun unauthorized(invalidToken: Boolean): HttpResp {
        val challenge = buildString {
            append("Bearer realm=\"mcp\", resource_metadata=\"")
            append(endpoints().resourceMetadataUrl)
            append('"')
            if (invalidToken) append(", error=\"invalid_token\"")
        }
        return HttpResp.json(
            401,
            JSONObject().put("error", "unauthorized"),
            mapOf("WWW-Authenticate" to challenge),
        )
    }

    // ------------------------------------------------------------------ 限流

    /**
     * 桶键：**只有确实在走 tunnel**（`tunnelMode != off` 且 `publicBase != null`）时才按
     * `cf-connecting-ip` 分桶；其余情况一律固定 `local` —— 否则本机客户端随便伪造一个
     * `cf-connecting-ip` 就能换一个新桶，限流等于没有。
     */
    private fun rateKey(req: HttpReq?): String {
        val proxied = config.tunnelMode != McpConfig.TUNNEL_OFF && config.publicBase != null
        if (!proxied || req == null) return "local"
        val ip = req.headers["cf-connecting-ip"]?.trim().orEmpty()
        return if (ip.isEmpty()) "cf:unknown" else "cf:$ip"
    }

    private fun limit(req: HttpReq?, bucket: String, max: Int, windowMs: Long): Boolean =
        limiter.take(rateKey(req) + "|" + bucket, max, windowMs)

    // ------------------------------------------------------------------ 响应构造

    private fun get(method: String, block: () -> Route): Route =
        if (method == "GET") block() else Route.Respond(methodNotAllowed("GET"))

    private fun post(method: String, block: () -> Route): Route =
        if (method == "POST") block() else Route.Respond(methodNotAllowed("POST"))

    private fun redirect(location: String): HttpResp =
        HttpResp(302, mapOf("Location" to location, "Cache-Control" to "no-store"), "")

    private fun oauthError(status: Int, code: String, desc: String): HttpResp =
        HttpResp.json(
            status,
            JSONObject().put("error", code).put("error_description", desc),
            mapOf("Pragma" to "no-cache"),
        )

    private fun payloadTooLarge(): HttpResp = HttpResp.json(
        413,
        JSONObject().put("error", "payload_too_large").put("error_description", "request body is too large"),
    )

    /** 429：`Retry-After` 报的是**这个端点自己的窗口长度**（`/register` 3600、`/token` 60）。 */
    private fun tooMany(html: Boolean, windowMs: Long): HttpResp {
        val retryAfter = (windowMs / 1000).coerceAtLeast(1).toString()
        if (html) {
            val resp = HttpResp.html(
                429,
                page("请求过于频繁", "<p>请求过于频繁，请稍后再试。</p>", refresh = null),
            )
            return resp.copy(headers = resp.headers + mapOf("Retry-After" to retryAfter))
        }
        return HttpResp.json(
            429,
            JSONObject().put("error", "rate_limited").put("error_description", "too many requests"),
            mapOf("Retry-After" to retryAfter),
        )
    }

    private fun methodNotAllowed(allowed: String): HttpResp = HttpResp.text(
        405,
        "method not allowed",
        mapOf("Allow" to allowed),
    )

    private fun notFound(): HttpResp = HttpResp.text(404, "not found")

    private fun errorHtml(status: Int, desc: String): HttpResp = HttpResp.html(
        status,
        page("请求无效", "<p>请求无效：${esc(desc)}</p><p>请回到客户端重新发起连接。</p>", refresh = null),
    )

    private fun forbiddenHtml(): HttpResp = HttpResp.html(
        403,
        page(
            "客户端未批准",
            "<p>该客户端还在等待批准。请在 hc-bridge app → MCP 管理 → 客户端里批准后，再回到客户端重新发起。</p>",
            refresh = null,
        ),
    )

    /**
     * 410：请求已失效（不存在 / 已消费 / 已过期）。**静态**文案，不回显任何请求参数
     * （`?req=` 是外部输入，回显就是反射型 XSS 面）。
     */
    private fun gonePage(): HttpResp = HttpResp.html(
        410,
        page(
            "请求已失效",
            "<p>这次授权请求已失效（已确认过、已过期或不存在）。</p><p>请回到客户端重新发起。</p>",
            refresh = null,
        ),
    )

    /**
     * 等待页：浏览器每 3 秒刷新（刷新 `?req=` 不计限流），页内有 `hcbridge://oauth/consent?req=<id>`
     * 深链；手机与 PC 浏览器分处两台设备时，走 app 内的「待确认请求」列表。
     *
     * [id] 只来自库里自建的 `hcb_rq_…`（字符集 `[A-Za-z0-9_-]`），直接拼进 URL 是安全的；
     * 仍然过一次 [esc]，防御将来有人把外部值传进来。
     */
    private fun waitPage(id: String): HttpResp {
        val safe = esc(id)
        val body = buildString {
            append("<p>请在 hc-bridge app 里确认这次连接请求。</p>")
            append("<p><a class=\"btn\" href=\"hcbridge://oauth/consent?req=").append(safe)
            append("\">在 app 内确认</a></p>")
            append("<p class=\"hint\">PC 浏览器打不开手机 app：请用手机浏览器打开本页，或在 app → MCP 管理 → 待确认请求里直接允许/拒绝。</p>")
            append("<p class=\"hint\">只有你自己发起连接时才允许；不要把本页或授权码发给别人。</p>")
        }
        return HttpResp.html(200, page("等待在 app 内确认", body, refresh = 3))
    }

    private fun page(title: String, body: String, refresh: Int?): String = buildString {
        append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        if (refresh != null) append("<meta http-equiv=\"refresh\" content=\"").append(refresh).append("\">")
        append("<title>").append(esc(title)).append(" · hc-bridge</title>")
        append("<style>")
        append("body{font:16px/1.6 -apple-system,system-ui,sans-serif;margin:0;padding:24px;")
        append("max-width:32rem;color:#1b1b1b;background:#fafafa}")
        append("h1{font-size:1.25rem;margin:0 0 1rem}")
        append(".btn{display:inline-block;padding:.75rem 1.25rem;background:#1b6ef3;color:#fff;")
        append("border-radius:.5rem;text-decoration:none;font-weight:600}")
        append(".hint{color:#555;font-size:.9rem}")
        append("</style></head><body><h1>").append(esc(title)).append("</h1>")
        append(body)
        append("</body></html>")
    }

    // ------------------------------------------------------------------ 小工具

    private fun isMcp(path: String): Boolean = path == "/mcp" || path.startsWith("/mcp/")

    private fun normalizePath(raw: String): String {
        val noQuery = raw.substringBefore('?').ifEmpty { "/" }
        if (noQuery.length > 1 && noQuery.endsWith('/')) {
            return noQuery.trimEnd('/').ifEmpty { "/" }
        }
        return noQuery
    }

    /** `application/x-www-form-urlencoded`；同名参数只取第一个；坏转义 ⇒ `null`（400）。 */
    private fun formParams(body: String): Map<String, String>? {
        val out = LinkedHashMap<String, String>()
        if (body.isEmpty()) return out
        for (pair in body.split('&')) {
            if (pair.isEmpty()) continue
            val i = pair.indexOf('=')
            val rawKey = if (i < 0) pair else pair.substring(0, i)
            val rawValue = if (i < 0) "" else pair.substring(i + 1)
            val key = percentDecode(rawKey) ?: return null
            val value = percentDecode(rawValue) ?: return null
            if (key.isNotEmpty() && !out.containsKey(key)) out[key] = value
        }
        return out
    }

    private fun percentDecode(s: String): String? = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun esc(s: String): String = buildString(s.length + 16) {
        for (c in s) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    companion object {

        /**
         * 只读 issuer/resource（管理页「状态」段用）：**不建 router**、不占限流桶。
         * 与实例方法同源，避免两处判定漂移。
         */
        fun endpointsOf(config: McpConfig): Endpoints =
            runCatching { Endpoints.from(config.publicBase) }.getOrElse { Endpoints.local() }

        /** `publicBase` 已设置但解析不了 ⇒ `true`（发现文档已静默退回本机）。 */
        fun endpointsFallbackOf(config: McpConfig): Boolean =
            config.publicBase != null && runCatching { Endpoints.from(config.publicBase) }.isFailure

        const val REGISTER_MAX = 10
        const val TOKEN_MAX = 60
        const val AUTHORIZE_MAX = 30
        const val WINDOW_MINUTE = 60_000L
        const val WINDOW_HOUR = 3_600_000L

        /**
         * UTF-8 字节数（**不**分配临时数组）。请求体上限按字节判：`HttpReq.body` 是字符，
         * 直接拿 `length` 比 256 K 会把 3 字节字符的上限放大到 768 KB。
         */
        fun utf8Length(s: String): Long {
            var n = 0L
            var i = 0
            while (i < s.length) {
                val c = s[i]
                n += when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                        i++
                        4
                    }
                    else -> 3
                }
                i++
            }
            return n
        }
    }
}
