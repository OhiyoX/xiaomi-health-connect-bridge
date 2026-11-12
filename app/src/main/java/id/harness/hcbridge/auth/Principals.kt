package id.harness.hcbridge.auth

import org.json.JSONArray

/**
 * Bearer → [Principal] 的解析（计划 Task 4 / Review Focus 4）。
 *
 * 两条路径按令牌前缀分流：
 * - `hcb_at_` / `hcb_rt_` ⇒ OAuth：**每次都重读 client**；client 缺失、已吊销、非 `active` 或
 *   `redirect_uris_json` 解析失败（我们唯一依赖的结构化字段）⇒ `revokeFamily` + `null`（fail closed），
 *   绝不"只看令牌行"放行。刷新令牌不能当访问令牌用。
 * - `hcb_st_` ⇒ 静态 key：按哈希找行（常量时间比较），已吊销 ⇒ `null`。
 *
 * 令牌还必须：`kind=access`、未过期、未吊销、`resource` 等于当前 resource（改公网基址后旧令牌自然失效）。
 * 命中时刷新 `last_used_at`（store 侧有 60 秒节流）。
 *
 * 本文件**不得** import 任何 `android.*`（JVM 单测要用）。
 */
object Principals {

    /** 静态 key 不受 scope 层限制（它没有 scope 列）；两层策略/时间窗仍是上限。 */
    private val STATIC_SCOPES = setOf(ToolCatalog.SCOPE_READ, ToolCatalog.SCOPE_WRITE)

    fun resolve(store: McpStore, rawToken: String, resource: String, now: Long): Principal? {
        val raw = rawToken.trim()
        if (raw.isEmpty()) return null
        val prefix = TokenFormat.prefixOf(raw)
        return when {
            prefix.startsWith(TokenFormat.ACCESS_PREFIX) ||
                prefix.startsWith(TokenFormat.REFRESH_PREFIX) -> oauth(store, raw, resource, now)

            prefix.startsWith(TokenFormat.KEY_PREFIX) -> staticKey(store, raw, now)
            else -> null
        }
    }

    // ------------------------------------------------------------------ OAuth

    private fun oauth(store: McpStore, raw: String, resource: String, now: Long): Principal? {
        val hash = Crypto.sha256Hex(raw)
        val token = store.tokenByHash(hash) ?: return null

        // 1) client 重读（Review Focus 4）：缺失/吊销/非 active/JSON 损坏 ⇒ 连族吊销
        val client = store.client(token.clientId)
        if (client == null ||
            client.revokedAt != null ||
            client.status != McpStatus.CLIENT_ACTIVE ||
            !redirectsParse(client.redirectUrisJson)
        ) {
            store.revokeFamily(token.familyId, now)
            return null
        }

        // 2) 令牌自身：只有访问令牌能认证；revoked/过期/resource 不匹配一律 null（不连带吊销——
        //    过期的令牌不是攻击信号，resource 不匹配是正常的基址切换）
        if (token.kind != McpStatus.KIND_ACCESS) return null
        if (token.revokedAt != null) return null
        if (token.expiresAt <= now) return null
        if (token.resource != resource) return null

        val scopes = ScopeJson.normalize(token.scopes)
            .filterTo(LinkedHashSet()) { it in OAuth.ALLOWED_SCOPES }
        if (scopes.isEmpty()) return null

        val policies = listOf(client.toolPolicyJson, token.toolPolicyJson)
        val windows = listOf(client.windowJson, token.windowJson)
        store.touchToken(hash, now)
        return Principal(
            kind = "oauth",
            subject = token.clientId,
            label = client.name,
            scopes = scopes,
            policies = policies,
            windows = windows,
            allowedTools = ToolGate.effectiveTools(scopes, listOfNotNull(client.toolPolicyJson, token.toolPolicyJson)),
            expiresAt = token.expiresAt,
        )
    }

    /** `"= JSONArray"` 才算完好；坏数据不是"没有回调"，是"不可信"。 */
    private fun redirectsParse(json: String): Boolean =
        runCatching { JSONArray(json) }.isSuccess

    // ------------------------------------------------------------------ 静态 key

    private fun staticKey(store: McpStore, raw: String, now: Long): Principal? {
        val hash = Crypto.sha256Hex(raw)
        // 表很小（管理员手工建），逐行常量时间比较；没有 by-hash 查询也不放宽存储层
        val key = store.keys().firstOrNull { Crypto.constantTimeEqualsHex(it.hash, hash) } ?: return null
        if (key.revokedAt != null) return null
        store.touchKey(key.id, now)
        return Principal(
            kind = "static",
            subject = "key:${key.id}",
            label = key.label,
            scopes = STATIC_SCOPES,
            policies = listOf(key.toolPolicyJson),
            windows = listOf(key.windowJson),
            allowedTools = ToolGate.effectiveTools(STATIC_SCOPES, listOfNotNull(key.toolPolicyJson)),
            expiresAt = Long.MAX_VALUE,
        )
    }
}
