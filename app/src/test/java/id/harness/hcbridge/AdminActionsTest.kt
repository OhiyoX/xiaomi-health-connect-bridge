package id.harness.hcbridge

import id.harness.hcbridge.auth.AdminGate
import id.harness.hcbridge.auth.AdminThrottle
import id.harness.hcbridge.auth.ClientRow
import id.harness.hcbridge.auth.Crypto
import id.harness.hcbridge.auth.InMemoryStore
import id.harness.hcbridge.auth.McpConfig
import id.harness.hcbridge.auth.McpStatus
import id.harness.hcbridge.auth.PendingRow
import id.harness.hcbridge.auth.TokenFormat
import id.harness.hcbridge.auth.TokenRow
import id.harness.hcbridge.auth.Tunnel
import id.harness.hcbridge.auth.Totp
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `AdminActions` 的契约测试（`admin-ui/API.md` §4）：共享前端能发的每个动作在本机要么真的做到，
 * 要么明确 `ok:false`；一次性明文只出现一次；每个动作都写审计，审计里**没有**凭据。
 *
 * 门禁是进程级的（`AdminThrottle`）：每条用例先复位再打开会话，锁定的用例单独关掉。
 */
class AdminActionsTest {

    private lateinit var store: InMemoryStore
    private lateinit var config: McpConfig
    private lateinit var env: FakeAdminEnv
    private lateinit var actions: AdminActions

    private val now = 1_763_700_000_000L

    @Before
    fun setUp() {
        AdminThrottle.resetForTest()
        store = InMemoryStore()
        config = McpConfig(store)
        env = FakeAdminEnv(nowMs = now)
        actions = AdminActions(store, config, env)
        AdminThrottle.touch(env.nowMs)      // 已解锁
    }

    @After
    fun tearDown() {
        AdminThrottle.resetForTest()
    }

    // ------------------------------------------------------------ 小工具

    private fun act(name: String, payload: JSONObject = JSONObject()): JSONObject = actions.act(name, payload)

    private fun ok(res: JSONObject) {
        assertTrue("期望成功，实际：$res", res.optBoolean("ok", false))
    }

    private fun failed(res: JSONObject): String {
        assertFalse("期望失败，实际：$res", res.optBoolean("ok", true))
        val msg = res.optString("error", "")
        assertTrue("失败文案不能为空", msg.isNotEmpty())
        return msg
    }

    private fun client(id: String, status: String = McpStatus.CLIENT_ACTIVE) = ClientRow(
        clientId = id,
        secretHash = null,
        name = "client-$id",
        redirectUrisJson = "[\"http://127.0.0.1:7777/cb\"]",
        toolPolicyJson = null,
        windowJson = null,
        status = status,
        createdAt = now - 86_400_000,
    )

    private fun pending(id: String, clientId: String) = PendingRow(
        id = id,
        clientId = clientId,
        redirectUri = "http://127.0.0.1:7777/cb",
        codeChallenge = "challenge",
        scopes = listOf("health.read"),
        resource = "http://127.0.0.1:8765/mcp",
        state = null,
        createdAt = now - 1_000,
        expiresAt = now + 300_000,
        status = McpStatus.PENDING,
    )

    private fun token(family: String, kind: String, prefix: String, clientId: String) = TokenRow(
        tokenHash = Crypto.sha256Hex("h-$prefix"),
        prefix = prefix,
        kind = kind,
        clientId = clientId,
        familyId = family,
        scopes = listOf("health.read"),
        toolPolicyJson = null,
        windowJson = null,
        resource = "http://127.0.0.1:8765/mcp",
        createdAt = now - 10_000,
        expiresAt = now + 3_600_000,
    )

    /** 绑定并确认 TOTP，返回已入库的 secret（后续用例从"已启用"开始）。 */
    private fun enrollTotp(): String {
        val secret = act("security.totp-begin").getString("secret")
        ok(act("security.totp-confirm", JSONObject().put("code", Totp.code(secret, env.nowMs))))
        assertEquals(secret, env.totp)
        return secret
    }

    // ---------------------------------------------------------------- 门禁

    @Test
    fun lockedSessionRejectsEveryAction() {
        AdminThrottle.lock()
        for (name in listOf(
            "audit.verify", "token.create", "coverage.backfill", "security.totp-disable",
            "security.totp-rebind", "token.policy", "oauth.window", "local.require-auth", "local.save-public",
            "local.probe", "local.command",
        )) {
            val res = act(name)
            assertTrue(failed(res).contains("锁定"))
        }
    }

    @Test
    fun unknownActionIsRejected() {
        assertTrue(failed(act("nope.nope")).isNotEmpty())
    }

    // ---------------------------------------------------------------- 用户 / 会话

    @Test
    fun userActionsAreUnsupportedButAudited() {
        for (name in listOf("user.create", "user.update", "user.toggle", "user.delete")) {
            assertTrue(failed(act(name, JSONObject().put("id", "1"))).contains("不支持多用户"))
        }
        val rows = store.auditRows()
        assertEquals(4, rows.size)
        assertTrue(rows.all { it.result == "failed" && it.detail == "app-single-admin" })
    }

    @Test
    fun revokeSessionLocksTheGate() {
        val res = act("session.revoke", JSONObject().put("id", "unlock"))
        ok(res)
        assertFalse("撤销会话 = 上锁", AdminThrottle.isOpen(env.nowMs))
        assertEquals(1, env.lockCalls)
        assertTrue(store.auditRows(action = "admin_lock").isNotEmpty())
        // 上锁后同一个动作必须被拒
        assertTrue(failed(act("audit.verify")).contains("锁定"))
    }

    @Test
    fun revokeUnknownSessionIsRejected() {
        assertTrue(failed(act("session.revoke", JSONObject().put("id", "ses_other"))).isNotEmpty())
        assertTrue(AdminThrottle.isOpen(env.nowMs))
        assertEquals(0, env.lockCalls)
    }

    @Test
    fun revokeOthersReportsZero() {
        val res = act("session.revoke-others")
        ok(res)
        assertEquals(0, res.getJSONObject("data").getInt("revoked"))
    }

    // ---------------------------------------------------------------- 令牌

    @Test
    fun tokenCreateReturnsPlaintextOnceAndStoresOnlyHash() {
        val res = act(
            "token.create",
            JSONObject().put("name", "laptop").put("scopes", JSONArray().put("health.read")),
        )
        ok(res)
        val secret = res.getString("secret")
        assertTrue(secret.startsWith(TokenFormat.KEY_PREFIX))
        assertEquals(TokenFormat.prefixOf(secret), res.getString("prefix"))
        assertTrue("一次性明文在顶层（adapter 收进 data）", res.isNull("data"))
        assertEquals("key:1", res.getString("id"))

        val key = store.keys().single()
        assertEquals("laptop", key.label)
        assertEquals(Crypto.sha256Hex(secret), key.hash)
        assertNotEquals("库里不能有明文", secret, key.hash)
        assertNull(key.revokedAt)

        val audits = store.auditRows(action = "key_create")
        assertEquals(1, audits.size)
        assertEquals("laptop", audits.first().detail)
        assertFalse("审计不能出现明文", audits.any { it.detail?.contains(secret) == true })
    }

    @Test
    fun tokenCreateNeedsName() {
        assertTrue(failed(act("token.create", JSONObject().put("name", "  "))).contains("名称"))
        assertTrue(store.keys().isEmpty())
    }

    @Test
    fun tokenRevokeStaticKey() {
        store.putKey("termux", Crypto.sha256Hex("hcb_st_raw"), null, null, now)
        ok(act("token.revoke", JSONObject().put("id", "key:1")))
        assertNotNull(store.keys().single().revokedAt)
        assertTrue(store.auditRows(action = "key_revoke").isNotEmpty())
        // 再撤一次：幂等
        ok(act("token.revoke", JSONObject().put("id", "key:1")))
        assertEquals(1, store.auditRows(action = "key_revoke").size)
    }

    @Test
    fun tokenRevokeOAuthFamily() {
        store.putClient(client("hcb_ci_1"))
        store.putToken(token("fam_1", McpStatus.KIND_ACCESS, "hcb_at_aaaa", "hcb_ci_1"))
        store.putToken(token("fam_1", McpStatus.KIND_REFRESH, "hcb_rt_aaaa", "hcb_ci_1"))
        store.putToken(token("fam_other", McpStatus.KIND_ACCESS, "hcb_at_bbbb", "hcb_ci_1"))

        ok(act("token.revoke", JSONObject().put("id", "tok:fam_1:access:hcb_at_aaaa")))

        val fam1 = store.tokens().filter { it.familyId == "fam_1" }
        assertTrue(fam1.isNotEmpty())
        assertTrue("整族一起撤销", fam1.all { it.revokedAt != null })
        assertNull(store.tokens().first { it.familyId == "fam_other" }.revokedAt)
    }

    @Test
    fun tokenRevokeUnknownIdIsRejected() {
        assertTrue(failed(act("token.revoke", JSONObject().put("id", "garbage"))).isNotEmpty())
        assertTrue(failed(act("token.revoke", JSONObject().put("id", "key:99"))).isNotEmpty())
    }

    // ---------------------------------------------------------------- 客户端

    @Test
    fun oauthCreatePublicAndConfidential() {
        val pub = act(
            "oauth.create",
            JSONObject().put("name", "Obsidian")
                .put("kind", "public")
                .put("redirects", JSONArray().put("http://127.0.0.1:1234/cb")),
        )
        ok(pub)
        val pubId = pub.getString("clientId")
        assertTrue(pubId.startsWith("hcb_ci_"))
        assertFalse("public 客户端没有 secret", pub.has("secret"))
        assertEquals(McpStatus.CLIENT_ACTIVE, store.client(pubId)!!.status)
        assertNull(store.client(pubId)!!.secretHash)

        val conf = act(
            "oauth.create",
            JSONObject().put("name", "Claude Desktop")
                .put("kind", "confidential")
                .put("redirects", JSONArray().put("https://example.com/cb")),
        )
        ok(conf)
        val confId = conf.getString("clientId")
        val secret = conf.getString("secret")
        assertTrue(secret.startsWith(TokenFormat.CLIENT_SECRET_PREFIX))
        assertEquals(Crypto.sha256Hex(secret), store.client(confId)!!.secretHash)
        assertFalse("审计不能出现 client_secret", store.auditRows().any { it.detail?.contains(secret) == true })
    }

    @Test
    fun oauthCreateRejectsBadRedirect() {
        val res = act(
            "oauth.create",
            JSONObject().put("name", "bad").put("kind", "public")
                .put("redirects", JSONArray().put("http://evil.example/cb")),
        )
        assertTrue(failed(res).contains("回调"))
        assertTrue(store.clients().isEmpty())
    }

    @Test
    fun oauthToggleApprovesPendingAndRevokesActive() {
        store.putClient(client("hcb_ci_p", McpStatus.CLIENT_PENDING))
        ok(act("oauth.toggle", JSONObject().put("id", "hcb_ci_p").put("enabled", true)))
        assertEquals(McpStatus.CLIENT_ACTIVE, store.client("hcb_ci_p")!!.status)
        assertTrue(store.auditRows(action = "client_approve").isNotEmpty())

        ok(act("oauth.toggle", JSONObject().put("id", "hcb_ci_p").put("enabled", false)))
        assertEquals(McpStatus.CLIENT_REVOKED, store.client("hcb_ci_p")!!.status)
        assertTrue(store.auditRows(action = "client_revoke").isNotEmpty())

        // 已吊销不能"启用"复活
        assertTrue(failed(act("oauth.toggle", JSONObject().put("id", "hcb_ci_p").put("enabled", true))).isNotEmpty())
    }

    @Test
    fun oauthToggleRejectsPendingClient() {
        store.putClient(client("hcb_ci_p", McpStatus.CLIENT_PENDING))
        ok(act("oauth.toggle", JSONObject().put("id", "hcb_ci_p").put("enabled", false)))
        assertEquals(McpStatus.CLIENT_REVOKED, store.client("hcb_ci_p")!!.status)
        assertTrue(store.auditRows(action = "client_reject").isNotEmpty())
    }

    @Test
    fun oauthDeleteOnlyRevokedRows() {
        store.putClient(client("hcb_ci_a"))
        assertTrue(failed(act("oauth.delete", JSONObject().put("id", "hcb_ci_a"))).isNotEmpty())
        assertEquals(1, store.clients().size)

        store.revokeClient("hcb_ci_a", now)
        ok(act("oauth.delete", JSONObject().put("id", "hcb_ci_a")))
        assertTrue(store.clients().isEmpty())
        assertTrue(store.auditRows(action = "client_delete").isNotEmpty())
    }

    @Test
    fun pendingDecideApprovesOnce() {
        val id = "hcb_rq_abcdefghijklmnop"
        store.putClient(client("hcb_ci_1"))
        store.putPending(pending(id, "hcb_ci_1"))

        ok(act("pending.decide", JSONObject().put("id", id).put("approve", true)))
        assertEquals(McpStatus.APPROVED, store.pending(id)!!.status)
        val audits = store.auditRows(action = "pending_allow")
        assertEquals(1, audits.size)
        assertEquals("审计只放可展示的前缀", TokenFormat.prefixOf(id), audits.first().detail)

        // 已批准的请求不能再决定
        assertTrue(failed(act("pending.decide", JSONObject().put("id", id).put("approve", true))).isNotEmpty())
    }

    @Test
    fun pendingDecideDeny() {
        store.putClient(client("hcb_ci_1"))
        store.putPending(pending("hcb_rq_zyxwvutsrqponmlk", "hcb_ci_1"))
        ok(act("pending.decide", JSONObject().put("id", "hcb_rq_zyxwvutsrqponmlk").put("approve", false)))
        assertEquals(McpStatus.DENIED, store.pending("hcb_rq_zyxwvutsrqponmlk")!!.status)
    }

    // ---------------------------------------------------------------- 健康数据

    @Test
    fun backfillQueuesWorker() {
        val res = act("coverage.backfill", JSONObject().put("type", "heart_rate"))
        ok(res)
        assertEquals(listOf(365), env.enqueued)
        assertEquals(365, res.getJSONObject("data").getInt("days"))
        assertTrue(res.getJSONObject("data").getBoolean("queued"))
        assertTrue(store.auditRows(action = "coverage_backfill").isNotEmpty())
    }

    @Test
    fun backfillAcceptsExplicitDaysAndClamps() {
        ok(act("coverage.backfill", JSONObject().put("days", 90)))
        assertEquals(90, env.enqueued.last())
        ok(act("coverage.backfill", JSONObject().put("days", 99_999)))
        assertEquals(730, env.enqueued.last())
    }

    @Test
    fun backfillReportsFailure() {
        env.backfillQueued = false
        assertTrue(failed(act("coverage.backfill")).isNotEmpty())
        assertEquals("failed", store.auditRows(action = "coverage_backfill").first().result)
    }

    @Test
    fun auditVerifyReturnsChainState() {
        store.audit("admin", "x", null, "ok", null, now)
        val res = act("audit.verify")
        ok(res)
        assertTrue(res.getJSONObject("data").getBoolean("chainOk"))
        assertEquals(1, res.getJSONObject("data").getInt("checked"))
    }

    // ---------------------------------------------------------------- 安全

    @Test
    fun changePasswordUpdatesHashAndAudits() {
        val res = act(
            "security.change-password",
            JSONObject().put("current", "").put("password", "new-password-1").put("password2", "new-password-1"),
        )
        ok(res)
        assertTrue(AdminGate.verify("new-password-1", config.adminPasswordHash))
        val audit = store.auditRows(action = "admin_password_set").single()
        assertEquals("web", audit.detail)
        assertFalse("审计里不能有密码", audit.toString().contains("new-password-1"))
    }

    @Test
    fun changePasswordValidatesLengthAndRepeat() {
        config.adminPasswordHash = AdminGate.hash("old-password", AdminGate.MIN_ITERATIONS)
        val before = config.adminPasswordHash
        assertTrue(failed(act("security.change-password", JSONObject().put("password", "short"))).contains("8"))
        assertTrue(
            failed(
                act(
                    "security.change-password",
                    JSONObject().put("password", "new-password-1").put("password2", "other-password-1"),
                ),
            ).contains("不一致"),
        )
        assertEquals(before, config.adminPasswordHash)
    }

    /** 绑定 → 确认（无恢复码）→ 停用需要**当前**动态码。 */
    @Test
    fun totpBeginConfirmThenDisableRequiresCurrentCode() {
        val begin = act("security.totp-begin")
        ok(begin)
        val secret = begin.getString("secret")
        assertTrue(secret.length >= 16)
        assertTrue(begin.getString("uri").startsWith("otpauth://totp/"))
        assertNull("确认前不落库", env.totp)
        assertFalse("确认前不进 settings", store.setting("totpSecret") != null)

        val code = Totp.code(secret, env.nowMs)
        val confirmed = act("security.totp-confirm", JSONObject().put("code", code))
        ok(confirmed)
        assertEquals(secret, env.totp)
        // App 不生成恢复码：页面拿不到就只提示"TOTP 已启用"
        assertTrue(confirmed.isNull("data") || !confirmed.getJSONObject("data").has("recoveryCodes"))
        assertTrue(store.auditRows(action = "totp_enable").isNotEmpty())

        // 同一个动态码不能重放；再来一次必须重新开始
        assertTrue(failed(act("security.totp-confirm", JSONObject().put("code", code))).isNotEmpty())

        // 停用必须带当前动态码：缺码、错码都不改状态
        assertTrue(failed(act("security.totp-disable")).contains("验证码"))
        assertTrue(failed(act("security.totp-disable", JSONObject().put("code", "000000"))).isNotEmpty())
        assertEquals("错码不能停用", secret, env.totp)
        assertTrue(store.auditRows(action = "totp_disable").isEmpty())

        // 下一个 30 秒窗口的码可以停用（刚确认的那一步已被占用）
        val later = Totp.code(secret, env.nowMs + Totp.STEP_MS)
        ok(act("security.totp-disable", JSONObject().put("code", later)))
        assertNull(env.totp)
        assertTrue(store.auditRows(action = "totp_disable").isNotEmpty())

        // 未启用时停用/重绑都明确拒绝
        assertTrue(failed(act("security.totp-disable", JSONObject().put("code", later))).contains("未启用"))
        assertTrue(failed(act("security.totp-rebind", JSONObject().put("code", later))).contains("未启用"))
    }

    /** 重绑：先校验当前码，再一次性换新密钥；返回新 secret/uri（只此一次），旧码立刻作废。 */
    @Test
    fun totpRebindVerifiesCurrentCodeAndReplacesSecret() {
        val old = enrollTotp()

        // 错码 / 缺码：密钥不变
        assertTrue(failed(act("security.totp-rebind", JSONObject().put("code", "000000"))).isNotEmpty())
        assertTrue(failed(act("security.totp-rebind")).contains("验证码"))
        assertEquals("失败不能换密钥", old, env.totp)
        assertTrue(store.auditRows(action = "totp_rebind").isEmpty())

        val rebind = act("security.totp-rebind", JSONObject().put("code", Totp.code(old, env.nowMs + Totp.STEP_MS)))
        ok(rebind)
        val fresh = rebind.getString("secret")
        assertNotEquals("必须换新密钥", old, fresh)
        assertTrue(rebind.getString("uri").startsWith("otpauth://totp/"))
        assertEquals("落库的是新密钥", fresh, env.totp)
        assertTrue(store.auditRows(action = "totp_rebind").isNotEmpty())
        assertFalse("审计里不能有 secret", store.auditRows().any { it.detail?.contains(fresh) == true })

        // 旧验证器的码作废：同一个时间步，旧 secret 算出来的码已经不认（换密钥了）
        val oldCode = Totp.code(old, env.nowMs)
        assertTrue(failed(act("security.totp-disable", JSONObject().put("code", oldCode))).isNotEmpty())
        assertEquals(fresh, env.totp)

        // 页面随后的 confirm 是对新密钥做确认（rebind 已把 pending 置成新密钥）
        val newCode = Totp.code(fresh, env.nowMs)
        ok(act("security.totp-confirm", JSONObject().put("code", newCode)))
        assertEquals(fresh, env.totp)
    }

    @Test
    fun totpConfirmWithoutBeginIsRejected() {
        assertTrue(failed(act("security.totp-confirm", JSONObject().put("code", "123456"))).isNotEmpty())
    }

    @Test
    fun settingsUpdateIsRejected() {
        val res = act("settings.update", JSONObject().put("sessionTtlHours", 24))
        assertTrue(failed(res).contains("不能从页面修改"))
        assertEquals("failed", store.auditRows(action = "settings_update").first().result)
    }

    // ------------------------------------------------ 工具策略 / 时间窗（App）

    @Test
    fun keyPolicyIsCanonicalizedAndAudited() {
        store.putKey("termux", Crypto.sha256Hex("hcb_st_x"), null, null, now)

        ok(act("token.policy", JSONObject().put("id", "key:1").put("policy", " [ \"hc_status\" ] ")))
        assertEquals("[\"hc_status\"]", store.keys().single().toolPolicyJson)
        val audit = store.auditRows(action = "key_policy_update").single()
        assertEquals("ok", audit.result)
        assertTrue(audit.detail!!.startsWith("key:1 "))

        // 非法：什么都不写
        assertTrue(
            failed(act("token.policy", JSONObject().put("id", "key:1").put("policy", "{\"tool\":\"x\"}")))
                .contains("格式"),
        )
        assertEquals("[\"hc_status\"]", store.keys().single().toolPolicyJson)

        // null / 空串 = 不限
        ok(act("token.policy", JSONObject().put("id", "key:1").put("policy", JSONObject.NULL)))
        assertNull(store.keys().single().toolPolicyJson)
        ok(act("token.policy", JSONObject().put("id", "key:1").put("policy", "")))
        assertNull(store.keys().single().toolPolicyJson)
    }

    @Test
    fun keyWindowIsCanonicalized() {
        store.putKey("termux", Crypto.sha256Hex("hcb_st_x"), null, null, now)
        ok(act("token.window", JSONObject().put("id", "key:1").put("window", "{\"start\":\"8:00\",\"end\":\"23:00\"}")))
        assertEquals("{\"start\":\"08:00\",\"end\":\"23:00\"}", store.keys().single().windowJson)
        assertTrue(
            failed(act("token.window", JSONObject().put("id", "key:1").put("window", "{\"day\":[2]}")))
                .contains("时间窗"),
        )
        assertEquals("{\"start\":\"08:00\",\"end\":\"23:00\"}", store.keys().single().windowJson)
        // 策略不被时间窗写丢
        ok(act("token.policy", JSONObject().put("id", "key:1").put("policy", "[\"hc_status\"]")))
        ok(act("token.window", JSONObject().put("id", "key:1").put("window", JSONObject.NULL)))
        assertEquals("[\"hc_status\"]", store.keys().single().toolPolicyJson)
        assertNull(store.keys().single().windowJson)
    }

    @Test
    fun oauthTokenPolicyUpdatesWholeLiveFamily() {
        store.putClient(client("hcb_ci_1"))
        store.putToken(token("fam_1", McpStatus.KIND_ACCESS, "hcb_at_aaaa", "hcb_ci_1"))
        store.putToken(token("fam_1", McpStatus.KIND_REFRESH, "hcb_rt_aaaa", "hcb_ci_1"))
        val dead = token("fam_1", McpStatus.KIND_ACCESS, "hcb_at_dead", "hcb_ci_1").copy(revokedAt = now - 1)
        store.putToken(dead)
        store.putToken(token("fam_other", McpStatus.KIND_ACCESS, "hcb_at_bbbb", "hcb_ci_1"))

        ok(act("token.policy", JSONObject().put("id", "tok:fam_1:access:hcb_at_aaaa").put("policy", "[]")))

        val live = store.tokens().filter { it.familyId == "fam_1" && it.revokedAt == null }
        assertEquals(2, live.size)
        assertTrue("访问与刷新都要收窄（刷新会继承）", live.all { it.toolPolicyJson == "[]" })
        val revokedRow = store.tokens().first { it.revokedAt != null }
        assertNull("已撤销的行不动", revokedRow.toolPolicyJson)
        assertNull(store.tokens().first { it.familyId == "fam_other" }.toolPolicyJson)
        assertEquals("ok", store.auditRows(action = "token_policy_update").single().result)
    }

    @Test
    fun oauthTokenWindowUpdatesFamily() {
        store.putClient(client("hcb_ci_1"))
        store.putToken(token("fam_1", McpStatus.KIND_ACCESS, "hcb_at_aaaa", "hcb_ci_1"))
        ok(act("token.window", JSONObject().put("id", "tok:fam_1:access:hcb_at_aaaa").put("window", "{\"days\":[1,2,3]}")))
        assertEquals("{\"days\":[1,2,3]}", store.tokens().first().windowJson)
        assertTrue(
            failed(act("token.window", JSONObject().put("id", "tok:fam_nope:access:x").put("window", "{}")))
                .isNotEmpty(),
        )
    }

    @Test
    fun clientPolicyAndWindowAreCanonicalized() {
        store.putClient(client("hcb_ci_1"))
        ok(act("oauth.policy", JSONObject().put("id", "hcb_ci_1").put("policy", "[ \"hc_status\" ]")))
        assertEquals("[\"hc_status\"]", store.client("hcb_ci_1")!!.toolPolicyJson)
        ok(act("oauth.window", JSONObject().put("id", "hcb_ci_1").put("window", "{\"start\":\"8:00\",\"end\":\"23:00\"}")))
        assertEquals("{\"start\":\"08:00\",\"end\":\"23:00\"}", store.client("hcb_ci_1")!!.windowJson)

        assertTrue(failed(act("oauth.policy", JSONObject().put("id", "hcb_ci_1").put("policy", "{}"))).isNotEmpty())
        assertTrue(failed(act("oauth.window", JSONObject().put("id", "hcb_ci_1").put("window", "[]"))).isNotEmpty())
        assertEquals("[\"hc_status\"]", store.client("hcb_ci_1")!!.toolPolicyJson)

        assertTrue(failed(act("oauth.policy", JSONObject().put("id", "nope").put("policy", "[]"))).isNotEmpty())
    }

    @Test
    fun policyOnRevokedKeyIsRejected() {
        store.putKey("dead", Crypto.sha256Hex("hcb_st_x"), null, null, now)
        store.revokeKey(1, now)
        assertTrue(failed(act("token.policy", JSONObject().put("id", "key:1").put("policy", "[]"))).contains("撤销"))
        assertNull(store.keys().single().toolPolicyJson)
    }

    // ------------------------------------------------------- 本机访问（App）

    @Test
    fun requireAuthCannotBeDisabledWhilePublicIsOn() {
        config.publicBase = "https://bridge.example"
        val res = act("local.require-auth", JSONObject().put("enabled", false))
        assertTrue(failed(res).contains("不能关"))
        assertTrue("守卫：鉴权仍是开的", config.requireAuth)
        assertTrue(store.auditRows(action = "require_auth").isEmpty())
    }

    @Test
    fun requireAuthTogglesWhenLocalOnly() {
        ok(act("local.require-auth", JSONObject().put("enabled", false)))
        assertFalse(config.requireAuth)
        assertEquals("0", store.auditRows(action = "require_auth").single().detail)
        ok(act("local.require-auth", JSONObject().put("enabled", true)))
        assertTrue(config.requireAuth)
        assertEquals("1", store.auditRows(action = "require_auth").first().detail)
    }

    @Test
    fun loopbackAndConfirmClientsToggle() {
        ok(act("local.loopback", JSONObject().put("enabled", true)))
        assertTrue(config.allowLoopbackNoAuth)
        ok(act("local.loopback", JSONObject().put("enabled", false)))
        assertFalse(config.allowLoopbackNoAuth)
        assertTrue(store.auditRows(action = "allow_loopback_no_auth").isNotEmpty())

        ok(act("local.confirm-clients", JSONObject().put("enabled", false)))
        assertFalse(config.confirmNewClients)
        assertEquals("0", store.auditRows(action = "confirm_new_clients").single().detail)
    }

    @Test
    fun savePublicWritesModeBaseAndTokenTogether() {
        val res = act(
            "local.save-public",
            JSONObject().put("mode", "quick")
                .put("publicBase", "https://Bridge.Example:443")
                .put("token", "tunnel-token-1"),
        )
        ok(res)
        assertEquals("https://bridge.example", config.publicBase)   // 归一化
        assertEquals(McpConfig.TUNNEL_QUICK, config.tunnelMode)
        assertEquals("tunnel-token-1", env.tunnel)
        val audit = store.auditRows(action = "public_settings").single()
        assertEquals("mode=quick base=https://bridge.example token=set", audit.detail)
        assertFalse("审计不能有 token", audit.detail!!.contains("tunnel-token-1"))
    }

    @Test
    fun savePublicValidationWritesNothing() {
        config.publicBase = "https://old.example"
        config.tunnelMode = McpConfig.TUNNEL_OFF
        env.tunnel = "keep-me"

        assertTrue(
            failed(
                act("local.save-public", JSONObject().put("mode", "quick").put("publicBase", "http://insecure.example")),
            ).contains("公网基址"),
        )
        assertEquals("https://old.example", config.publicBase)
        assertEquals(McpConfig.TUNNEL_OFF, config.tunnelMode)

        assertTrue(
            failed(
                act("local.save-public", JSONObject().put("mode", "bogus").put("publicBase", "https://x.example")),
            ).contains("隧道模式"),
        )
        assertEquals("https://old.example", config.publicBase)

        // named 没有 token（也没存过）⇒ 什么都不写
        env.tunnel = null
        assertTrue(
            failed(act("local.save-public", JSONObject().put("mode", "named").put("publicBase", "https://x.example")))
                .contains("token"),
        )
        assertEquals("https://old.example", config.publicBase)
        assertTrue(store.auditRows(action = "public_settings").isEmpty())
    }

    @Test
    fun savePublicForcesAuthAndInvalidatesProbe() {
        config.requireAuth = false
        env.setLastProbe(ProbeEntry(true, now - 1_000, "old", 5))
        ok(act("local.save-public", JSONObject().put("mode", "off").put("publicBase", "https://bridge.example").put("token", "")))
        assertTrue("公网开着 ⇒ 强制鉴权", config.requireAuth)
        assertNull("基址变了：旧探活作废", env.lastProbeValue)

        // 留空 base = 只走本机（清掉公网基址）
        ok(act("local.save-public", JSONObject().put("mode", "off").put("publicBase", "")))
        assertNull(config.publicBase)
        assertEquals(McpConfig.TUNNEL_OFF, config.tunnelMode)
    }

    @Test
    fun savePublicNamedReusesStoredToken() {
        env.tunnel = "already-stored"
        ok(act("local.save-public", JSONObject().put("mode", "named").put("publicBase", "https://named.example").put("token", "")))
        assertEquals(McpConfig.TUNNEL_NAMED, config.tunnelMode)
        assertEquals("已存的 token 不被空串覆盖", "already-stored", env.tunnel)
        assertTrue(store.auditRows(action = "public_settings").single().detail!!.endsWith("token=keep"))
    }

    @Test
    fun clearTunnelToken() {
        env.tunnel = "abc"
        ok(act("local.clear-token"))
        assertNull(env.tunnel)
        assertEquals("cleared", store.auditRows(action = "tunnel_token").single().detail)
    }

    @Test
    fun commandPlaceholderAndFull() {
        // 隧道关闭：没有命令（页面提示"没有要执行的命令"），也不写命令审计
        val off = act("local.command", JSONObject().put("full", false))
        ok(off)
        assertEquals("", off.getJSONObject("data").getString("command"))
        assertTrue(store.auditRows(action = "tunnel_command").isEmpty())

        config.tunnelMode = McpConfig.TUNNEL_QUICK
        val quick = act("local.command", JSONObject().put("full", true))
        ok(quick)
        val quickCmd = quick.getJSONObject("data").getString("command")
        assertTrue(quickCmd.contains("cloudflared tunnel --url http://127.0.0.1:8765"))
        assertEquals("placeholder", store.auditRows(action = "tunnel_command").last().detail)

        config.tunnelMode = McpConfig.TUNNEL_NAMED
        env.tunnel = "secret-tunnel-token"
        val placeholder = act("local.command", JSONObject().put("full", false))
        ok(placeholder)
        assertTrue(placeholder.getJSONObject("data").getString("command").contains(Tunnel.ENV_TOKEN))

        val full = act("local.command", JSONObject().put("full", true))
        ok(full)
        assertTrue(full.getJSONObject("data").getString("command").contains("secret-tunnel-token"))
        // 审计只有 full/placeholder，没有命令与 token
        for (row in store.auditRows(action = "tunnel_command")) {
            assertFalse(row.detail?.contains("secret-tunnel-token") == true)
            assertFalse(row.detail?.contains("cloudflared") == true)
        }
    }

    @Test
    fun probeNeedsSavedBaseAndStoresResult() {
        assertTrue(failed(act("local.probe")).contains("公网基址"))

        config.publicBase = "https://bridge.example"
        env.probeResult = ProbeEntry(true, now, "可达，公网 issuer 已生效（12 ms）", 12)
        val res = act("local.probe")
        ok(res)
        assertEquals(listOf("https://bridge.example"), env.probed)
        assertTrue(res.getJSONObject("data").getBoolean("ok"))
        assertEquals(now, res.getJSONObject("data").getLong("at"))
        assertEquals("可达，公网 issuer 已生效（12 ms）", res.getJSONObject("data").getString("detail"))
        assertEquals("探活结果写回宿主（state 里要能看到）", true, env.lastProbeValue?.ok)
        assertEquals("ms=12", store.auditRows(action = "tunnel_probe").single().detail)

        env.probeResult = ProbeEntry(false, now + 1, "健康检查 HTTP 502（30 ms）", 30)
        val bad = act("local.probe")
        ok(bad)
        assertFalse(bad.getJSONObject("data").getBoolean("ok"))
        assertEquals("failed", store.auditRows(action = "tunnel_probe").first().result)
    }

    @Test
    fun auditedDetailsNeverContainSecrets() {
        val token = act("token.create", JSONObject().put("name", "k")).getString("secret")
        val begin = act("security.totp-begin")
        val totp = begin.getString("secret")
        act(
            "security.change-password",
            JSONObject().put("password", "top-secret-password").put("password2", "top-secret-password"),
        )
        for (row in store.auditRows()) {
            val text = "${row.action}|${row.detail}"
            assertFalse(text.contains(token))
            assertFalse(text.contains(totp))
            assertFalse(text.contains("top-secret-password"))
        }
    }
}
