package id.harness.hcbridge

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import id.harness.hcbridge.auth.AdminGate
import id.harness.hcbridge.auth.AdminThrottle
import id.harness.hcbridge.auth.ClientRow
import id.harness.hcbridge.auth.Crypto
import id.harness.hcbridge.auth.McpConfig
import id.harness.hcbridge.auth.McpDb
import id.harness.hcbridge.auth.McpStatus
import id.harness.hcbridge.auth.PendingRow
import id.harness.hcbridge.auth.SqlMcpStore
import id.harness.hcbridge.auth.Totp
import id.harness.hcbridge.auth.TotpSecret
import org.hamcrest.Matchers.containsString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * 管理页的仪器合约（计划 Task 6）：待确认请求的允许/拒绝、待批准客户端的批准、
 * 静态 key 只显示一次且只存哈希、策略/时间窗规范化、TOTP secret 落 `Secure` 不进 settings、审计过滤。
 *
 * 每个用例用 `Store.overrideForTest` 注入临时库（`mcp.db` 不动）；解锁密码用低迭代数，
 * 免得每条用例都跑 12 万轮 PBKDF2。
 */
@RunWith(AndroidJUnit4::class)
class McpAdminTest {

    private lateinit var ctx: Context
    private lateinit var store: SqlMcpStore
    private lateinit var dbName: String

    private val pw = "test-password"

    @Before
    fun setUp() {
        // 门禁状态是进程级的：每条用例都从"未锁、未试错、无动态码占用"开始
        AdminThrottle.resetForTest()
        ctx = ApplicationProvider.getApplicationContext()
        dbName = "t-${UUID.randomUUID()}.db"
        store = SqlMcpStore(McpDb(ctx, dbName))
        Store.overrideForTest(store)
        McpConfig(store).adminPasswordHash = AdminGate.hash(pw, AdminGate.MIN_ITERATIONS)
        TotpSecret.set(ctx, null)
    }

    @After
    fun tearDown() {
        Store.overrideForTest(null)
        store.close()
        ctx.deleteDatabase(dbName)
        TotpSecret.set(ctx, null)
        AdminThrottle.resetForTest()
    }

    // ------------------------------------------------------------ 小工具

    private fun client(id: String, name: String, status: String = McpStatus.CLIENT_ACTIVE) = ClientRow(
        clientId = id,
        secretHash = null,
        name = name,
        redirectUrisJson = "[\"http://127.0.0.1:7777/cb\"]",
        toolPolicyJson = null,
        windowJson = null,
        status = status,
        createdAt = System.currentTimeMillis(),
    )

    private fun pending(id: String, clientId: String): PendingRow {
        val at = System.currentTimeMillis()
        return PendingRow(
            id = id,
            clientId = clientId,
            redirectUri = "http://127.0.0.1:7777/cb",
            codeChallenge = "chal",
            scopes = listOf("health.read"),
            resource = "http://127.0.0.1:8765/mcp",
            state = "st",
            createdAt = at,
            expiresAt = at + 300_000,
            status = McpStatus.PENDING,
        )
    }

    private fun launchAndUnlock(): ActivityScenario<McpAdminActivity> {
        val scenario = ActivityScenario.launch(McpAdminActivity::class.java)
        // 首次进入是"设密码"，所以先在库里放好哈希（setUp），这里只需解锁
        onView(withId(R.id.gatePassword)).perform(replaceText(pw))
        onView(withId(R.id.btnGateUnlock)).perform(click())
        waitUntilVisible(R.id.adminScroll)
        return scenario
    }

    private fun waitUntilEnabled(id: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            try {
                onView(withId(id)).check(matches(isEnabled()))
                return
            } catch (t: Throwable) {
                Thread.sleep(50)
            }
        }
        onView(withId(id)).check(matches(isEnabled()))
    }

    /** PBKDF2 在后台线程跑，Espresso 不知道它：轮询等管理内容出现。 */
    private fun waitUntilVisible(id: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            try {
                onView(withId(id)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
                return
            } catch (t: Throwable) {
                Thread.sleep(100)
            }
        }
        onView(withId(id)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
    }

    // ---------------------------------------------------------------- 用例

    @Test
    fun pendingListShowsRequestAndDecides() {
        store.putClient(client("hcb_ci_1", "Claude Code"))
        store.putPending(pending("hcb_rq_1", "hcb_ci_1"))
        val scenario = launchAndUnlock()
        try {
            onView(withId(R.id.pendingBox)).check(matches(hasDescendant(withText("Claude Code"))))
            onView(withId(R.id.pendingBox)).check(matches(hasDescendant(withText(containsString("127.0.0.1")))))
            onView(withId(R.id.pendingBox)).check(matches(hasDescendant(withText(containsString("health.read")))))

            onView(withText("允许")).perform(click())
            assertEquals(McpStatus.APPROVED, store.pending("hcb_rq_1")!!.status)

            // 再来一条：刷新后拒绝
            store.putPending(pending("hcb_rq_2", "hcb_ci_1"))
            onView(withId(R.id.btnPendingRefresh)).perform(click())
            onView(withText("拒绝")).perform(click())
            assertEquals(McpStatus.DENIED, store.pending("hcb_rq_2")!!.status)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun pendingClientIsApprovedFromUi() {
        store.putClient(client("hcb_ci_2", "Bridge Client", status = McpStatus.CLIENT_PENDING))
        val scenario = launchAndUnlock()
        try {
            onView(withId(R.id.clientBox)).check(matches(hasDescendant(withText(containsString("待批准")))))
            onView(withText("批准")).perform(click())
            assertEquals(McpStatus.CLIENT_ACTIVE, store.client("hcb_ci_2")!!.status)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun creatingStaticKeyShowsPlaintextOnceAndStoresOnlyHash() {
        store.putClient(client("hcb_ci_3", "laptop"))
        val scenario = launchAndUnlock()
        try {
            onView(withId(R.id.keyLabel)).perform(replaceText("termux"))
            onView(withId(R.id.btnNewKey)).perform(click())
            // 明文只在对话框里出现一次
            onView(withText(containsString("hcb_st_"))).check(matches(isDisplayed()))
            val key = store.keys().single()
            assertEquals("termux", key.label)
            assertEquals(64, key.hash.length)                        // 只存 SHA-256 hex
            onView(withText("好")).perform(click())
            // 关掉对话框后页面上再也找不到明文
            onView(withText(containsString("hcb_st_"))).check(doesNotExist())

            // 契约：同一明文算出的哈希就是库里那行
            var created: String? = null
            scenario.onActivity { created = it.createStaticKey("laptop", "[\"hc_status\"]", null) }
            val raw = requireNotNull(created) { "会话已解锁，createStaticKey 不该返回 null" }
            assertTrue(raw.startsWith("hcb_st_"))
            val second = store.keys().first { it.label == "laptop" }
            assertEquals(Crypto.sha256Hex(raw), second.hash)
            assertNotEquals(raw, second.hash)
            assertEquals("[\"hc_status\"]", second.toolPolicyJson)
            assertTrue(store.auditRows(action = "key_create").size >= 2)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun clientPolicyAndWindowAreCanonicalized() {
        store.putClient(client("hcb_ci_4", "canon"))
        val scenario = launchAndUnlock()
        try {
            var ok = false
            scenario.onActivity { ok = it.applyClientPolicy("hcb_ci_4", " [ \"hc_status\" ] ") }
            assertTrue(ok)
            assertEquals("[\"hc_status\"]", store.client("hcb_ci_4")!!.toolPolicyJson)

            // 非法策略：提示 + 库里保持原值
            scenario.onActivity { ok = it.applyClientPolicy("hcb_ci_4", "{\"tool\":\"hc_status\"}") }
            assertFalse(ok)
            assertEquals("[\"hc_status\"]", store.client("hcb_ci_4")!!.toolPolicyJson)

            var windowOk = false
            scenario.onActivity { windowOk = it.applyClientWindow("hcb_ci_4", "{\"start\":\"8:00\",\"end\":\"23:00\"}") }
            assertTrue(windowOk)
            assertEquals("{\"start\":\"08:00\",\"end\":\"23:00\"}", store.client("hcb_ci_4")!!.windowJson)

            scenario.onActivity { windowOk = it.applyClientWindow("hcb_ci_4", "{\"day\":[2]}") }
            assertFalse(windowOk)
            assertEquals("{\"start\":\"08:00\",\"end\":\"23:00\"}", store.client("hcb_ci_4")!!.windowJson)
            assertTrue(store.auditRows(action = "policy_update").isNotEmpty())
        } finally {
            scenario.close()
        }
    }

    /**
     * 门禁不能被"多试几次 / 退出重进"绕过：连续 5 次错误密码后锁定，
     * 锁定期间即使输入**正确**密码也不进管理页，且失败会进审计（只写原因）。
     */
    @Test
    fun repeatedWrongPasswordsLockTheGate() {
        val scenario = ActivityScenario.launch(McpAdminActivity::class.java)
        try {
            repeat(5) {
                onView(withId(R.id.gatePassword)).perform(replaceText("wrong-password"))
                onView(withId(R.id.btnGateUnlock)).perform(click())
                waitUntilEnabled(R.id.btnGateUnlock)     // PBKDF2 跑完、按钮恢复可用
            }
            onView(withId(R.id.adminScroll)).check(matches(withEffectiveVisibility(Visibility.GONE)))

            // 锁定期内输入正确密码：仍然停在门前
            onView(withId(R.id.gatePassword)).perform(replaceText(pw))
            onView(withId(R.id.btnGateUnlock)).perform(click())
            onView(withId(R.id.adminScroll)).check(matches(withEffectiveVisibility(Visibility.GONE)))
            assertTrue(AdminThrottle.lockedFor(System.currentTimeMillis()) > 0)

            // 审计有失败记录，只有原因、没有密码
            val failures = store.auditRows(action = "admin_unlock")
            assertTrue(failures.isNotEmpty())
            assertTrue(failures.all { it.result == "failed" })
            assertFalse(failures.any { it.detail?.contains("wrong-password") == true })
        } finally {
            scenario.close()
        }
    }

    @Test
    fun totpSecretGoesToSecureNotSettings() {
        assertFalse(TotpSecret.enabled(ctx))
        TotpSecret.set(ctx, Totp.secret())
        assertNotNull(TotpSecret.get(ctx))
        assertTrue(TotpSecret.enabled(ctx))
        assertNull(store.setting(TotpSecret.KEY))     // 绝不进 settings 表
        TotpSecret.set(ctx, null)
        assertFalse(TotpSecret.enabled(ctx))
    }

    @Test
    fun auditListRendersAndFilters() {
        val at = System.currentTimeMillis()
        store.audit("admin", "marker_ok", null, "ok", null, at)
        store.audit("hcb_at_abc", "marker_deny", null, "denied", "no_scope", at + 1)
        store.audit("admin", "marker_deny_two", null, "denied", "no_scope", at + 2)
        val scenario = launchAndUnlock()
        try {
            onView(withId(R.id.auditBox)).check(matches(hasDescendant(withText(containsString("marker_ok")))))
            onView(withText("denied")).perform(click())       // result 过滤 chip
            onView(withId(R.id.auditBox)).check(matches(hasDescendant(withText(containsString("marker_deny_two")))))
            onView(withText(containsString("marker_ok"))).check(doesNotExist())
        } finally {
            scenario.close()
        }
    }
}
