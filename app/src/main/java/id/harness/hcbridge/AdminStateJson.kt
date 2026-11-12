package id.harness.hcbridge

import id.harness.hcbridge.auth.AdminGate
import id.harness.hcbridge.auth.AuditRow
import id.harness.hcbridge.auth.McpConfig
import id.harness.hcbridge.auth.McpStatus
import id.harness.hcbridge.auth.McpStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * 把本机事实（`McpStore` + `McpConfig` + [AdminEnv]）拼成共享前端的**状态 JSON**
 * （契约：`admin-ui/API.md` §3）。
 *
 * 铁律：
 * - **状态里没有秘密**：没有密码/密码哈希、TOTP secret、client_secret、令牌明文、Cookie、
 *   tunnel token；静态 key 只有 label 与 id，OAuth 令牌只有 `TokenFormat.prefixOf` 之后的前缀。
 * - 某一段取不到数据时给空数组 / `null`，**不**让整个 `state()` 失败（页面会自己显示「—」）。
 * - 只有一个管理员、一个解锁会话（`caps` 里声明 `single`），页面据此隐藏多用户/多会话操作。
 * - 本文件**不得** import 任何 `android.*`（JVM 单测直接吃 `InMemoryStore` + 假 env）。
 */
object AdminStateJson {

    /** 本机只有一个管理员账号（`caps.users`）。 */
    const val CAP_SINGLE = "single"

    /** 管理员的稳定 id / 用户名（页面用它做归属显示）。 */
    const val ADMIN_ID = "admin"
    const val ADMIN_USERNAME = "admin"

    /** 唯一解锁会话的 id：`session.revoke` 回传它等于「上锁」。 */
    const val UNLOCK_SESSION_ID = "unlock"

    /** 静态 key 在 `tokens[].id` 里的前缀（`token.revoke` 据此分流）。 */
    const val KEY_ID_PREFIX = "key:"

    /** OAuth 令牌在 `tokens[].id` 里的前缀（`tok:<family>:<kind>:<prefix>`）。 */
    const val TOKEN_ID_PREFIX = "tok:"

    /** 解锁会话是 **5 分钟**（`McpAdminActivity.SESSION_TIMEOUT_MS`）。 */
    const val SESSION_TTL_MINUTES = 5

    /** 审计页最多展示多少行（与本机审计保留上限一致的口径）。 */
    const val AUDIT_ROWS = 200

    fun build(store: McpStore, config: McpConfig, env: AdminEnv): JSONObject {
        val now = env.now()
        val totpEnabled = runCatching { env.totpSecret() != null }.getOrDefault(false)
        val health = runCatching { env.health() }.getOrNull() ?: AdminHealth.unavailable("健康数据暂不可用")
        val sync = runCatching { env.sync() }.getOrNull() ?: SyncEntry.UNKNOWN
        val backfill = runCatching { env.backfill() }.getOrNull() ?: BackfillEntry.IDLE
        val auditRows = runCatching { store.auditRows(limit = AUDIT_ROWS) }.getOrDefault(emptyList())

        return JSONObject()
            .put("version", runCatching { env.version() }.getOrDefault(""))
            .put("now", now)
            .put("me", me())
            .put("overview", overview(health, sync, backfill, auditRows, now))
            .put("users", JSONArray().put(user(env, totpEnabled)))
            .put("sessions", JSONArray().put(session(env, now)))
            .put("tokens", tokens(store, now))
            .put("oauth", clients(store))
            .put("pending", pending(store, now))
            .put("coverage", coverage(health))
            .put("sleep", sleep(health))
            .put("metrics", metrics(health))
            .put("audit", audit(store, auditRows))
            .put("security", security(config, totpEnabled))
            .put("localAccess", localAccess(config, env))
            .put("healthPending", health.pending)
            .put("caps", caps())
    }

    // ------------------------------------------------------------------ me / 用户

    private fun me(): JSONObject = JSONObject()
        .put("id", ADMIN_ID)
        .put("username", ADMIN_USERNAME)
        .put("display", ADMIN_DISPLAY)
        .put("role", "admin")

    /** 本机只有一个管理员，显示名固定（不拿设备名冒充账号名）。 */
    private const val ADMIN_DISPLAY = "本机管理员"

    private fun user(env: AdminEnv, totpEnabled: Boolean): JSONObject = JSONObject()
        .put("id", ADMIN_ID)
        .put("username", ADMIN_USERNAME)
        .put("display", ADMIN_DISPLAY)
        .put("role", "admin")
        .put("status", "active")
        .put("totp", totpEnabled)
        .put("lastLogin", env.lastUnlockAt() ?: JSONObject.NULL)
        .put("createdAt", JSONObject.NULL)
        .put("sessions", 1)
        .put("note", "本机单管理员：没有多用户体系，密码与 TOTP 在安全设置里管理。")
        .put(
            "scopes",
            JSONArray().put("admin").put("health.read").put("health.write"),
        )

    private fun session(env: AdminEnv, now: Long): JSONObject = JSONObject()
        .put("id", UNLOCK_SESSION_ID)
        .put("userId", ADMIN_ID)
        .put("device", runCatching { env.deviceLabel() }.getOrDefault("本机"))
        .put("ip", "127.0.0.1")
        .put("loc", "本机（回环）")
        .put("createdAt", env.lastUnlockAt() ?: now)
        .put("lastSeen", now)
        .put("current", true)
        .put("expiresAt", JSONObject.NULL)

    // ------------------------------------------------------------------ 概览

    private fun overview(
        health: AdminHealth,
        sync: SyncEntry,
        backfill: BackfillEntry,
        auditRows: List<AuditRow>,
        now: Long,
    ): JSONObject {
        val heart = health.coverage.firstOrNull { it.type == "heart_rate" }
        val dayMs = 24L * 3600_000L
        val fails24h = auditRows.count {
            it.result != "ok" && it.result != "approved" && now - it.createdAt in 0..dayMs
        }
        return JSONObject()
            .put("sync", JSONObject()
                .put("lastSyncAt", sync.lastSyncAt ?: JSONObject.NULL)
                .put("lastSyncSource", sync.lastSyncSource)
                .put("lastSyncRows", sync.lastSyncRows ?: JSONObject.NULL)
                .put("lagMin", sync.lagMin ?: JSONObject.NULL)
                .put("pipeline", sync.pipeline)
                .put("lastSyncResult", sync.lastSyncResult))
            .put("backfill", JSONObject()
                .put("status", backfill.status)
                .put("progress", backfill.progress ?: JSONObject.NULL)
                .put("rangeFrom", backfill.rangeFrom)
                .put("rangeTo", backfill.rangeTo)
                .put("lastRun", backfill.lastRun ?: JSONObject.NULL)
                .put("note", backfill.note))
            .put("kpis", JSONObject()
                .put("heartRows", heart?.rows ?: JSONObject.NULL)
                .put("coverageTypes", health.coverage.size)
                .put("lowCoverage", health.coverage.count { it.days30 != null && it.days30 < AdminHealth.COVERAGE_DAYS * 9 / 10 })
                .put("activeSessions", 1)
                .put("activeUsers", 1)
                .put("auditFails24h", fails24h))
    }

    // ------------------------------------------------------------------ 令牌

    /**
     * 列表 = 静态 key + OAuth 访问/刷新令牌。
     *
     * `id` 是不透明句柄：`key:12` / `tok:<family>:<kind>:<hcb_xx_abcd>`；
     * `prefix` 只能是**不可用**的标识（静态 key 只给 `key#12`，OAuth 给 `TokenFormat.prefixOf`）。
     */
    private fun tokens(store: McpStore, now: Long): JSONArray {
        val out = JSONArray()

        val keys = runCatching { store.keys() }.getOrDefault(emptyList())
        for (k in keys.sortedByDescending { it.createdAt }) {
            out.put(JSONObject()
                .put("id", "$KEY_ID_PREFIX${k.id}")
                .put("name", k.label)
                .put("prefix", "key#${k.id}")
                .put("scopes", JSONArray().put("health.read").put("health.write"))
                .put("status", if (k.revokedAt != null) "revoked" else "active")
                .put("userId", ADMIN_ID)
                .put("createdAt", k.createdAt)
                .put("lastUsed", k.lastUsedAt ?: JSONObject.NULL)
                .put("expires", JSONObject.NULL)
                .put("kind", "static")
                .put("family", "")
                // 工具策略 / 时间窗（`caps.policies`）：null = 不限，页面原样展示
                .put("policy", jsonOrNull(k.toolPolicyJson))
                .put("window", jsonOrNull(k.windowJson)))
        }

        val rows = runCatching { store.tokens() }.getOrDefault(emptyList())
        for (t in rows.sortedByDescending { it.createdAt }) {
            if (t.revokedAt != null) continue          // 列表只留有效的；撤销后的历史看审计
            val status = if (t.expiresAt <= now) "expired" else "active"
            val clientName = runCatching { store.client(t.clientId)?.name }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: t.clientId
            out.put(JSONObject()
                .put("id", "$TOKEN_ID_PREFIX${t.familyId}:${t.kind}:${t.prefix}")
                .put("name", clientName)
                .put("prefix", t.prefix)
                .put("scopes", JSONArray().toArray(t.scopes))
                .put("status", status)
                .put("userId", ADMIN_ID)
                .put("createdAt", t.createdAt)
                .put("lastUsed", t.lastUsedAt ?: JSONObject.NULL)
                .put("expires", t.expiresAt)
                .put("kind", t.kind)
                .put("family", t.familyId)
                .put("policy", jsonOrNull(t.toolPolicyJson))
                .put("window", jsonOrNull(t.windowJson)))
        }
        return out
    }

    // ------------------------------------------------------------------ 客户端

    private fun clients(store: McpStore): JSONArray {
        val out = JSONArray()
        val rows = runCatching { store.clients() }.getOrDefault(emptyList())
        for (c in rows.sortedBy { it.createdAt }) {
            out.put(JSONObject()
                .put("id", c.clientId)
                .put("name", c.name.takeIf { it.isNotBlank() } ?: c.clientId)
                .put("clientId", c.clientId)
                .put("kind", if (c.secretHash != null) "confidential" else "public")
                .put("redirects", JSONArray().toArray(redirects(c.redirectUrisJson)))
                .put("status", when (c.status) {
                    McpStatus.CLIENT_ACTIVE -> "active"
                    McpStatus.CLIENT_PENDING -> "pending"
                    else -> "revoked"
                })
                .put("createdAt", c.createdAt)
                .put("lastAuth", c.lastUsedAt ?: JSONObject.NULL)
                .put("policy", jsonOrNull(c.toolPolicyJson))
                .put("window", jsonOrNull(c.windowJson)))
        }
        return out
    }

    private fun redirects(json: String): List<String> {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "")
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    private fun pending(store: McpStore, now: Long): JSONArray {
        val out = JSONArray()
        val rows = runCatching { store.pendingList(now) }.getOrDefault(emptyList())
        for (p in rows) {
            val client = runCatching { store.client(p.clientId) }.getOrNull()
            out.put(JSONObject()
                .put("id", p.id)
                .put("client", client?.name?.takeIf { it.isNotBlank() } ?: p.clientId)
                .put("clientId", p.clientId)
                .put("redirect", hostOf(p.redirectUri))
                .put("scopes", JSONArray().toArray(p.scopes))
                .put("createdAt", p.createdAt)
                .put("expiresAt", p.expiresAt))
        }
        return out
    }

    private fun hostOf(uri: String): String =
        runCatching { java.net.URI(uri).host }.getOrNull() ?: "（无法解析）"

    // ------------------------------------------------------------------ 健康数据

    private fun coverage(health: AdminHealth): JSONArray {
        val out = JSONArray()
        for (c in health.coverage) {
            val pct = c.days30?.let {
                (it.toDouble() / AdminHealth.COVERAGE_DAYS).coerceIn(0.0, 1.0)
            }
            out.put(JSONObject()
                .put("type", c.type)
                .put("label", c.label)
                .put("rows", c.rows ?: JSONObject.NULL)
                .put("sources", JSONArray().toArray(c.sources))
                .put("days30", c.days30 ?: JSONObject.NULL)
                .put("pct", pct ?: JSONObject.NULL)
                .put("lastAt", c.lastAt ?: JSONObject.NULL))
        }
        return out
    }

    private fun sleep(health: AdminHealth): JSONArray {
        val out = JSONArray()
        for (s in health.sleep) {
            out.put(JSONObject()
                .put("date", s.date)
                .put("bed", s.bed)
                .put("wake", s.wake)
                .put("hours", s.hours ?: JSONObject.NULL)
                .put("score", s.score ?: JSONObject.NULL)
                .put("source", s.source)
                .put("flags", JSONArray().toArray(s.flags))
                .put("note", s.note))
        }
        return out
    }

    private fun metrics(health: AdminHealth): JSONObject {
        val m = health.metrics
        val out = JSONObject().put("days", m.days)
        for (e in m.entries) {
            val series = JSONArray()
            for (v in e.series) series.put(v ?: JSONObject.NULL)
            out.put(e.key, JSONObject()
                .put("label", e.label)
                .put("unit", e.unit)
                .put("decimals", e.decimals)
                .put("latest", e.latest ?: JSONObject.NULL)
                .put("avg", e.avg ?: JSONObject.NULL)
                .put("series", series))
        }
        return out
    }

    // ------------------------------------------------------------------ 审计 / 安全

    private fun audit(store: McpStore, rows: List<AuditRow>): JSONObject {
        val arr = JSONArray()
        for (r in rows) {
            arr.put(JSONObject()
                .put("id", r.id.toString())
                .put("at", r.createdAt)
                .put("actor", r.actor)
                .put("action", r.action)
                .put("target", r.tool ?: "")
                .put("outcome", if (r.result == "ok" || r.result == "approved") "ok" else "fail")
                .put("detail", r.detail ?: ""))
        }
        return JSONObject()
            .put("rows", arr)
            .put("chainOk", runCatching { store.auditChainOk() }.getOrDefault(false))
    }

    /**
     * 安全设置：**只读**（`settingsEditable=false`）。
     *
     * 解锁超时与锁定参数是 `AdminGate` 里的常量（5 分钟 / 5 次 / 60 秒），页面显示但不提供表单；
     * 解锁会话按**分钟**报（`sessionTtlMinutes`，`data.js` 会折成小时，`<1 小时` 显示成"5 分钟"）；
     * `totpEnrolledAt` / `passwordChangedAt` 本机不落库 ⇒ `null`（页面显示「—」，不当 0）。
     */
    private fun security(config: McpConfig, totpEnabled: Boolean): JSONObject = JSONObject()
        .put("totpEnabled", totpEnabled)
        .put("totpEnrolledAt", JSONObject.NULL)
        .put("passwordChangedAt", JSONObject.NULL)
        .put("passwordSet", runCatching { !AdminGate.needsSetup(config) }.getOrDefault(false))
        .put("sessionTtlMinutes", SESSION_TTL_MINUTES)
        .put("allowPasswordLogin", true)
        .put("requireTotp", totpEnabled)
        .put("maxFailedLogins", AdminGate.MAX_FAILURES)
        .put("lockoutMinutes", AdminGate.LOCK_SECONDS / 60)
        .put("settingsEditable", false)

    /**
     * 本机访问（`caps.localAccess`）：鉴权开关 / 隧道模式 / 公网基址 / token **存没存** / 最近探活。
     *
     * **没有秘密**：tunnel token 只以 `hasToken` 布尔出现；完整命令只从 `local.command` 返回一次。
     */
    private fun localAccess(config: McpConfig, env: AdminEnv): JSONObject {
        val probe = runCatching { env.lastProbe() }.getOrNull()
        return JSONObject()
            .put("requireAuth", config.requireAuth)
            .put("allowLoopbackNoAuth", config.allowLoopbackNoAuth)
            .put("confirmNewClients", config.confirmNewClients)
            .put("tunnelMode", config.tunnelMode)
            .put("publicBase", config.publicBase ?: JSONObject.NULL)
            .put("hasToken", runCatching { env.hasTunnelToken() }.getOrDefault(false))
            .put("lastProbe", probe?.let {
                JSONObject().put("ok", it.ok).put("at", it.at).put("detail", it.detail)
            } ?: JSONObject.NULL)
    }

    private fun caps(): JSONObject = JSONObject()
        .put("users", CAP_SINGLE)
        .put("sessions", CAP_SINGLE)
        .put("settings", false)
        .put("localAccess", true)
        .put("policies", true)

    /** 策略/时间窗列：空串等于 `null`（= 不限），页面按「不限（null）」显示。 */
    private fun jsonOrNull(value: String?): Any =
        value?.takeIf { it.isNotBlank() } ?: JSONObject.NULL

    // ------------------------------------------------------------------ 小工具

    /** `List<String>` → `JSONArray`（保持顺序）。 */
    private fun JSONArray.toArray(values: List<String>): JSONArray {
        for (v in values) put(v)
        return this
    }
}
