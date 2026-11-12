package id.harness.hcbridge

import id.harness.hcbridge.auth.AdminGate
import id.harness.hcbridge.auth.ClientRow
import id.harness.hcbridge.auth.Crypto
import id.harness.hcbridge.auth.InMemoryStore
import id.harness.hcbridge.auth.McpConfig
import id.harness.hcbridge.auth.McpStatus
import id.harness.hcbridge.auth.PendingRow
import id.harness.hcbridge.auth.TokenRow
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `AdminStateJson` 的契约测试（`admin-ui/API.md` §3）：
 *
 * - 形状固定：`me / overview / users / sessions / tokens / oauth / pending / coverage / sleep /
 *   metrics / audit / security / caps`；
 * - 本机是**一个管理员、一个解锁会话**（`caps` 声明 `single`），页面据此隐藏多用户操作；
 * - 状态里**没有秘密**：密码哈希、TOTP secret、令牌明文、client_secret 都不能出现在序列化结果里；
 * - 某段取不到数据时给空数组/`null`，不让整个 `state()` 失败。
 */
class AdminStateJsonTest {

    private lateinit var store: InMemoryStore
    private lateinit var config: McpConfig
    private lateinit var env: FakeAdminEnv

    private val now = 1_763_700_000_000L

    @Before
    fun setUp() {
        store = InMemoryStore()
        config = McpConfig(store)
        env = FakeAdminEnv(nowMs = now, unlockAt = now - 60_000)
    }

    // ------------------------------------------------------------ 小工具

    private fun state(): JSONObject = AdminStateJson.build(store, config, env)

    private fun client(
        id: String,
        name: String,
        status: String = McpStatus.CLIENT_ACTIVE,
        secretHash: String? = null,
        createdAt: Long = now - 86_400_000,
    ) = ClientRow(
        clientId = id,
        secretHash = secretHash,
        name = name,
        redirectUrisJson = "[\"http://127.0.0.1:7777/cb\",\"https://example.com/cb\"]",
        toolPolicyJson = null,
        windowJson = null,
        status = status,
        createdAt = createdAt,
    )

    private fun token(
        family: String,
        kind: String,
        prefix: String,
        clientId: String,
        createdAt: Long = now - 3_600_000,
        expiresAt: Long = now + 3_600_000,
        revokedAt: Long? = null,
    ) = TokenRow(
        tokenHash = Crypto.sha256Hex("hash-$prefix"),
        prefix = prefix,
        kind = kind,
        clientId = clientId,
        familyId = family,
        scopes = listOf("health.read"),
        toolPolicyJson = null,
        windowJson = null,
        resource = "http://127.0.0.1:8765/mcp",
        createdAt = createdAt,
        expiresAt = expiresAt,
        revokedAt = revokedAt,
    )

    private fun pending(id: String, clientId: String) = PendingRow(
        id = id,
        clientId = clientId,
        redirectUri = "http://127.0.0.1:7777/cb",
        codeChallenge = "challenge",
        scopes = listOf("health.read"),
        resource = "http://127.0.0.1:8765/mcp",
        state = "st",
        createdAt = now - 1_000,
        expiresAt = now + 300_000,
        status = McpStatus.PENDING,
    )

    // ---------------------------------------------------------------- 形状

    @Test
    fun stateHasSingleAdminAndSingleSession() {
        val s = state()

        assertEquals("0.9.0-test", s.getString("version"))
        assertEquals(now, s.getLong("now"))
        assertEquals("admin", s.getJSONObject("me").getString("username"))
        assertEquals("admin", s.getJSONObject("me").getString("role"))

        val users = s.getJSONArray("users")
        assertEquals(1, users.length())
        val u = users.getJSONObject(0)
        assertEquals("admin", u.getString("id"))
        assertEquals("admin", u.getString("role"))
        assertEquals("active", u.getString("status"))
        assertEquals(1, u.getInt("sessions"))
        assertFalse(u.getBoolean("totp"))

        val sessions = s.getJSONArray("sessions")
        assertEquals(1, sessions.length())
        val session = sessions.getJSONObject(0)
        assertEquals(AdminStateJson.UNLOCK_SESSION_ID, session.getString("id"))
        assertTrue(session.getBoolean("current"))
        assertEquals("Test Phone", session.getString("device"))
        assertEquals(now - 60_000, session.getLong("createdAt"))

        val caps = s.getJSONObject("caps")
        assertEquals("single", caps.getString("users"))
        assertEquals("single", caps.getString("sessions"))
        assertFalse(caps.getBoolean("settings"))
        assertTrue("本机访问卡片", caps.getBoolean("localAccess"))
        assertTrue("工具策略/时间窗编辑", caps.getBoolean("policies"))
    }

    @Test
    fun sectionsExistEvenWhenEmpty() {
        val s = state()
        assertEquals(0, s.getJSONArray("tokens").length())
        assertEquals(0, s.getJSONArray("oauth").length())
        assertEquals(0, s.getJSONArray("pending").length())
        assertEquals(0, s.getJSONArray("coverage").length())
        assertEquals(0, s.getJSONArray("sleep").length())
        assertEquals(0, s.getJSONObject("audit").getJSONArray("rows").length())
        val metrics = s.getJSONObject("metrics")
        assertEquals(AdminHealth.DEFAULT_METRIC_DAYS, metrics.getInt("days"))
        assertTrue(s.has("overview"))
        assertTrue(s.has("security"))
    }

    // ---------------------------------------------------------------- 秘密

    @Test
    fun stateContainsNoSecrets() {
        val passwordHash = AdminGate.hash("admin-password", AdminGate.MIN_ITERATIONS)
        config.adminPasswordHash = passwordHash
        val totpSecret = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"
        env.totp = totpSecret

        store.putClient(client("hcb_ci_1", "桌面客户端", secretHash = Crypto.sha256Hex("hcb_cs_supersecret")))
        val rawKey = "hcb_st_thisIsThePlaintextKey"
        store.putKey("laptop", Crypto.sha256Hex(rawKey), null, null, now - 10_000)
        store.putToken(token("fam_1", McpStatus.KIND_ACCESS, "hcb_at_abcdef", "hcb_ci_1"))
        config.publicBase = "https://bridge.example"
        store.setSetting("tunnelToken", "tunnel-secret-value")

        val text = state().toString()

        assertFalse("密码哈希不能进 state", text.contains(passwordHash))
        assertFalse("密码派生串的片段也不能进", text.contains(passwordHash.take(24)))
        assertFalse("TOTP secret 不能进 state", text.contains(totpSecret))
        assertFalse("client_secret 明文不能进 state", text.contains("hcb_cs_supersecret"))
        assertFalse("静态 key 明文不能进 state", text.contains(rawKey))
        assertFalse("tunnel token 不能进 state", text.contains("tunnel-secret-value"))
    }

    @Test
    fun staticKeyTokenHasOnlyOpaqueIdentifiers() {
        store.putKey("termux", Crypto.sha256Hex("hcb_st_plaintext"), null, null, now - 5_000)
        val t = state().getJSONArray("tokens").getJSONObject(0)
        assertEquals("termux", t.getString("name"))
        assertEquals("key:1", t.getString("id"))
        assertEquals("key#1", t.getString("prefix"))
        assertEquals("static", t.getString("kind"))
        assertEquals("active", t.getString("status"))
    }

    // ---------------------------------------------------------------- 令牌

    @Test
    fun oauthTokensAreListedWithoutRevokedRows() {
        store.putClient(client("hcb_ci_1", "Claude Code"))
        store.putToken(token("fam_live", McpStatus.KIND_ACCESS, "hcb_at_live0001", "hcb_ci_1"))
        store.putToken(token("fam_live", McpStatus.KIND_REFRESH, "hcb_rt_live0001", "hcb_ci_1"))
        store.putToken(token("fam_dead", McpStatus.KIND_ACCESS, "hcb_at_dead0001", "hcb_ci_1", revokedAt = now - 1_000))

        val tokens = state().getJSONArray("tokens")
        assertEquals("撤销的令牌不进列表（历史看审计）", 2, tokens.length())
        val row = (0 until tokens.length()).map { tokens.getJSONObject(it) }
        val access = row.first { it.getString("kind") == McpStatus.KIND_ACCESS }
        assertEquals("Claude Code", access.getString("name"))
        assertEquals("fam_live", access.getString("family"))
        assertEquals("active", access.getString("status"))
        assertTrue(access.getString("id").startsWith("tok:fam_live:access:"))
        assertEquals("hcb_at_live0001", access.getString("prefix"))
    }

    @Test
    fun expiredTokenIsMarkedExpired() {
        store.putClient(client("hcb_ci_1", "old client"))
        store.putToken(
            token("fam_old", McpStatus.KIND_ACCESS, "hcb_at_old00001", "hcb_ci_1", expiresAt = now - 1),
        )
        val t = state().getJSONArray("tokens").getJSONObject(0)
        assertEquals("expired", t.getString("status"))
    }

    // ---------------------------------------------------------------- 客户端

    @Test
    fun clientsMapStatusAndKind() {
        store.putClient(client("hcb_ci_a", "活跃"))
        store.putClient(client("hcb_ci_p", "待批准", status = McpStatus.CLIENT_PENDING))
        store.putClient(client("hcb_ci_r", "已吊销", status = McpStatus.CLIENT_REVOKED))
        store.putClient(client("hcb_ci_s", "机密", secretHash = Crypto.sha256Hex("x")))

        val oauth = state().getJSONArray("oauth")
        assertEquals(4, oauth.length())
        val byId = (0 until oauth.length()).associate { oauth.getJSONObject(it).getString("clientId") to oauth.getJSONObject(it) }
        assertEquals("active", byId.getValue("hcb_ci_a").getString("status"))
        assertEquals("pending", byId.getValue("hcb_ci_p").getString("status"))
        assertEquals("revoked", byId.getValue("hcb_ci_r").getString("status"))
        assertEquals("public", byId.getValue("hcb_ci_a").getString("kind"))
        assertEquals("confidential", byId.getValue("hcb_ci_s").getString("kind"))
        assertEquals(2, byId.getValue("hcb_ci_a").getJSONArray("redirects").length())
    }

    @Test
    fun pendingShowsHostOnly() {
        store.putClient(client("hcb_ci_1", "Obsidian MCP"))
        store.putPending(pending("hcb_rq_1", "hcb_ci_1"))
        val p = state().getJSONArray("pending").getJSONObject(0)
        assertEquals("Obsidian MCP", p.getString("client"))
        assertEquals("127.0.0.1", p.getString("redirect"))
        assertEquals(1, p.getJSONArray("scopes").length())
        assertEquals(now + 300_000, p.getLong("expiresAt"))
    }

    // ---------------------------------------------------------------- 健康数据

    @Test
    fun coverageSleepAndMetricsAreMapped() {
        env.health = AdminHealth(
            coverage = listOf(
                CoverageEntry(
                    type = "heart_rate",
                    label = "心率",
                    rows = 12_345,
                    sources = listOf("id.harness.hcbridge", "com.mi.health"),
                    days30 = 15,
                    lastAt = now - 3_600_000,
                ),
            ),
            sleep = listOf(
                SleepEntry(
                    date = "2026-11-02",
                    bed = "23:42",
                    wake = "07:08",
                    hours = 7.4,
                    source = "com.mi.health",
                    flags = listOf("short"),
                    note = "无分期",
                ),
            ),
            metrics = AdminMetrics(
                days = 14,
                entries = listOf(
                    MetricEntry("steps", "步数", "步", 0, latest = 9_842.0, avg = 8_750.0, series = listOf(8_000.0, null, 9_000.0)),
                ),
            ),
        )

        val s = state()
        val c = s.getJSONArray("coverage").getJSONObject(0)
        assertEquals(12_345L, c.getLong("rows"))
        assertEquals(15, c.getInt("days30"))
        assertEquals(0.5, c.getDouble("pct"), 1e-9)
        assertEquals(2, c.getJSONArray("sources").length())
        assertEquals(now - 3_600_000, c.getLong("lastAt"))

        val sl = s.getJSONArray("sleep").getJSONObject(0)
        assertEquals("23:42", sl.getString("bed"))
        assertEquals(7.4, sl.getDouble("hours"), 1e-9)
        assertEquals("short", sl.getJSONArray("flags").getString(0))

        val steps = s.getJSONObject("metrics").getJSONObject("steps")
        assertEquals(9_842.0, steps.getDouble("latest"), 1e-9)
        assertEquals(3, steps.getJSONArray("series").length())
        assertTrue(steps.getJSONArray("series").isNull(1))
        // KPI 从 coverage 推导
        assertEquals(12_345L, s.getJSONObject("overview").getJSONObject("kpis").getLong("heartRows"))
    }

    @Test
    fun pendingHealthIsExposedSoThePageCanRefresh() {
        env.health = AdminHealth(note = "正在读取健康数据…", pending = true)

        val s = state()
        assertTrue(s.getBoolean("healthPending"))
        assertEquals(0, s.getJSONArray("coverage").length())
        assertEquals(0, s.getJSONArray("sleep").length())
    }

    @Test
    fun healthFailureDoesNotBreakState() {
        env.healthThrows = true
        val s = state()
        assertEquals(0, s.getJSONArray("coverage").length())
        assertEquals(0, s.getJSONArray("sleep").length())
        assertTrue(s.getJSONObject("overview").has("sync"))
    }

    // ---------------------------------------------------------------- 审计 / 安全

    @Test
    fun auditRowsMapOutcomeAndTarget() {
        store.audit("admin", "key_create", null, "ok", "termux", now - 1_000)
        store.audit("admin", "admin_unlock", null, "failed", "password", now - 2_000)
        store.audit("hcb_at_abc", "tools_call", "hc_status", "denied", "no_scope", now - 3_000)

        val audit = state().getJSONObject("audit")
        assertTrue("审计链自洽", audit.getBoolean("chainOk"))
        val rows = audit.getJSONArray("rows")
        assertEquals(3, rows.length())
        val byAction = (0 until rows.length()).associate { rows.getJSONObject(it).getString("action") to rows.getJSONObject(it) }
        assertEquals("ok", byAction.getValue("key_create").getString("outcome"))
        assertEquals("termux", byAction.getValue("key_create").getString("detail"))
        assertEquals("fail", byAction.getValue("admin_unlock").getString("outcome"))
        assertEquals("hc_status", byAction.getValue("tools_call").getString("target"))
        assertEquals("fail", byAction.getValue("tools_call").getString("outcome"))
        // KPI：近 24 小时失败数
        assertEquals(2, state().getJSONObject("overview").getJSONObject("kpis").getInt("auditFails24h"))
    }

    @Test
    fun securityIsReadOnlyAndReflectsTotp() {
        config.adminPasswordHash = AdminGate.hash("pw", AdminGate.MIN_ITERATIONS)
        env.totp = "JBSWY3DPEHPK3PXP"
        val sec = state().getJSONObject("security")
        assertTrue(sec.getBoolean("totpEnabled"))
        assertTrue(sec.getBoolean("requireTotp"))
        assertTrue(sec.getBoolean("passwordSet"))
        assertFalse("本机策略只读", sec.getBoolean("settingsEditable"))
        assertEquals(AdminGate.MAX_FAILURES, sec.getInt("maxFailedLogins"))
        assertEquals(AdminGate.LOCK_SECONDS / 60, sec.getInt("lockoutMinutes"))
        // 5 分钟按分钟报：`data.js` 会折成小时，<1 小时显示成"5 分钟"
        assertEquals(AdminStateJson.SESSION_TTL_MINUTES, sec.getInt("sessionTtlMinutes"))
        assertFalse("不再用小时字段", sec.has("sessionTtlHours"))
    }

    // -------------------------------------------------- 工具策略 / 本机访问（0.9.x）

    @Test
    fun tokensAndClientsExposePolicyAndWindow() {
        store.putKey("termux", Crypto.sha256Hex("hcb_st_x"), "[\"hc_status\"]", "{\"start\":\"08:00\",\"end\":\"23:00\"}", now)
        store.putKey("plain", Crypto.sha256Hex("hcb_st_y"), "", "  ", now)

        store.putClient(
            ClientRow(
                clientId = "hcb_ci_pol",
                secretHash = null,
                name = "pol",
                redirectUrisJson = "[\"http://127.0.0.1:7777/cb\"]",
                toolPolicyJson = "[]",
                windowJson = "{}",
                status = McpStatus.CLIENT_ACTIVE,
                createdAt = now - 1_000,
            ),
        )
        store.putToken(
            TokenRow(
                tokenHash = Crypto.sha256Hex("h-at"),
                prefix = "hcb_at_pol0001",
                kind = McpStatus.KIND_ACCESS,
                clientId = "hcb_ci_pol",
                familyId = "fam_pol",
                scopes = listOf("health.read"),
                toolPolicyJson = "[\"hc_status\"]",
                windowJson = "{\"days\":[1,2,3]}",
                resource = "http://127.0.0.1:8765/mcp",
                createdAt = now - 500,
                expiresAt = now + 3_600_000,
            ),
        )

        val s = state()
        val tokens = s.getJSONArray("tokens")
        val rows = (0 until tokens.length()).map { tokens.getJSONObject(it) }
        val key = rows.first { it.getString("id") == "key:1" }
        assertEquals("[\"hc_status\"]", key.getString("policy"))
        assertEquals("{\"start\":\"08:00\",\"end\":\"23:00\"}", key.getString("window"))
        // 空串 = 不限（页面显示「不限（null）」）
        val plain = rows.first { it.getString("id") == "key:2" }
        assertTrue(plain.isNull("policy"))
        assertTrue(plain.isNull("window"))
        val oauthToken = rows.first { it.getString("kind") == McpStatus.KIND_ACCESS }
        assertEquals("[\"hc_status\"]", oauthToken.getString("policy"))
        assertEquals("{\"days\":[1,2,3]}", oauthToken.getString("window"))

        val client = s.getJSONArray("oauth").getJSONObject(0)
        assertEquals("[]", client.getString("policy"))
        assertEquals("{}", client.getString("window"))
    }

    @Test
    fun localAccessReflectsConfigAndNeverExposesToken() {
        config.requireAuth = true
        config.allowLoopbackNoAuth = false
        config.confirmNewClients = true
        config.tunnelMode = "quick"
        config.publicBase = "https://bridge.example"
        env.tunnel = "tunnel-token-that-must-not-leak"
        val probeAt = now - 5_000
        env.setLastProbe(ProbeEntry(true, probeAt, "可达，公网 issuer 已生效（35 ms）", 35))

        val l = state().getJSONObject("localAccess")
        assertTrue(l.getBoolean("requireAuth"))
        assertFalse(l.getBoolean("allowLoopbackNoAuth"))
        assertTrue(l.getBoolean("confirmNewClients"))
        assertEquals("quick", l.getString("tunnelMode"))
        assertEquals("https://bridge.example", l.getString("publicBase"))
        assertTrue("只报存没存", l.getBoolean("hasToken"))
        val probe = l.getJSONObject("lastProbe")
        assertTrue(probe.getBoolean("ok"))
        assertEquals(probeAt, probe.getLong("at"))
        assertTrue(probe.getString("detail").contains("35 ms"))

        val text = state().toString()
        assertFalse("tunnel token 不能进 state", text.contains("tunnel-token-that-must-not-leak"))
    }

    @Test
    fun localAccessDefaultsAreConservative() {
        val l = state().getJSONObject("localAccess")
        assertTrue(l.getBoolean("requireAuth"))
        assertFalse(l.getBoolean("allowLoopbackNoAuth"))
        assertTrue(l.getBoolean("confirmNewClients"))
        assertEquals("off", l.getString("tunnelMode"))
        assertTrue(l.isNull("publicBase"))
        assertFalse(l.getBoolean("hasToken"))
        assertTrue(l.isNull("lastProbe"))
    }

    @Test
    fun overviewCarriesSyncAndBackfill() {
        env.sync = SyncEntry(
            lastSyncAt = now - 7 * 60_000,
            lastSyncSource = "xiaomi-cloud",
            lastSyncRows = 18_420,
            lagMin = 7,
            pipeline = SyncEntry.PIPELINE_OK,
            lastSyncResult = "ok",
        )
        env.backfill = BackfillEntry(
            status = BackfillEntry.STATUS_IDLE,
            progress = null,
            rangeFrom = "2024-01-01",
            rangeTo = "2026-11-02",
            lastRun = now - 3_600_000,
            note = "n",
        )
        val o = state().getJSONObject("overview")
        val sync = o.getJSONObject("sync")
        assertEquals(now - 7 * 60_000, sync.getLong("lastSyncAt"))
        assertEquals("ok", sync.getString("pipeline"))
        assertEquals(18_420L, sync.getLong("lastSyncRows"))
        val b = o.getJSONObject("backfill")
        assertEquals("2024-01-01", b.getString("rangeFrom"))
        assertTrue(b.isNull("progress"))
        assertNotNull(b.getString("note"))
        assertNull(sync.opt("missing"))
    }
}
