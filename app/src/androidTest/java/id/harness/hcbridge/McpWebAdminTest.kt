package id.harness.hcbridge

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import id.harness.hcbridge.auth.AdminGate
import id.harness.hcbridge.auth.AdminThrottle
import id.harness.hcbridge.auth.McpConfig
import id.harness.hcbridge.auth.McpDb
import id.harness.hcbridge.auth.SqlMcpStore
import id.harness.hcbridge.auth.TotpSecret
import id.harness.hcbridge.auth.Tunnel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * WebView 宿主（0.9.0 默认管理页）的仪器合约：
 *
 * 1. 解锁后加载**本地**共享前端（`file:///android_asset/admin/index.html`），状态 JSON 认得出来
 *    （一个管理员、一个会话、`caps` 单条），并且**没有密码/哈希**；
 * 2. `onStop` 上锁：管理内容藏起、JS 桥移除、WebView 回到 `about:blank`，之后的 `act` 一律拒绝；
 * 3. `adminUiMode` 默认 `web`、可切 `native`（原生页仍然可用，行为见 `McpAdminTest`）；
 * 4. 连续 5 次错密码后仍然是门（不因为换了宿主就绕过 `AdminThrottle`）。
 *
 * 用临时库（`Store.overrideForTest`），`mcp.db` 不动；密码用低迭代数，免得每条用例跑 12 万轮 PBKDF2。
 */
@RunWith(AndroidJUnit4::class)
class McpWebAdminTest {

    private lateinit var ctx: Context
    private lateinit var store: SqlMcpStore
    private lateinit var dbName: String

    private val pw = "test-password"

    private companion object {
        const val TUNNEL_TOKEN_SENTINEL = "it-tunnel-token-must-not-leak"
    }

    @Before
    fun setUp() {
        AdminThrottle.resetForTest()
        ctx = ApplicationProvider.getApplicationContext()
        dbName = "t-${UUID.randomUUID()}.db"
        store = SqlMcpStore(McpDb(ctx, dbName))
        Store.overrideForTest(store)
        McpConfig(store).adminPasswordHash = AdminGate.hash(pw, AdminGate.MIN_ITERATIONS)
        TotpSecret.set(ctx, null)
        AdminHealthLoader.invalidate()
    }

    @After
    fun tearDown() {
        Store.overrideForTest(null)
        store.close()
        ctx.deleteDatabase(dbName)
        TotpSecret.set(ctx, null)
        AdminThrottle.resetForTest()
        AdminHealthLoader.invalidate()
    }

    // ------------------------------------------------------------ 小工具

    private fun unlock(scenario: ActivityScenario<McpWebAdminActivity>) {
        onView(withId(R.id.gatePassword)).perform(replaceText(pw))
        onView(withId(R.id.btnGateUnlock)).perform(click())
        waitUntil { it.adminVisibleForTest() }
    }

    private fun waitUntil(timeoutMs: Long = 15_000, cond: (McpWebAdminActivity) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var ok = false
            try {
                scenarioActivity { ok = cond(it) }
            } catch (t: Throwable) {
                // Activity 还没起来：继续等
            }
            if (ok) return
            Thread.sleep(100)
        }
        var last = false
        scenarioActivity { last = cond(it) }
        assertTrue("等待条件超时", last)
    }

    /** 最近一次 launch 的 Activity（每条用例只 launch 一次）。 */
    private var current: ActivityScenario<McpWebAdminActivity>? = null

    private fun scenarioActivity(block: (McpWebAdminActivity) -> Unit) {
        val s = requireNotNull(current) { "先 launch" }
        s.onActivity(block)
    }

    private fun launch(): ActivityScenario<McpWebAdminActivity> =
        ActivityScenario.launch(McpWebAdminActivity::class.java).also { current = it }

    // ---------------------------------------------------------------- 用例

    @Test
    fun unlockLoadsLocalAdminPage() {
        // 状态里绝不能出现 tunnel token：先放一个，测完还原
        val previousToken = Secure.getString(ctx, Tunnel.SECURE_KEY)
        Secure.putString(ctx, Tunnel.SECURE_KEY, TUNNEL_TOKEN_SENTINEL)
        val scenario = launch()
        try {
            unlock(scenario)
            waitUntil { it.bridgeAttachedForTest() }
            var url: String? = null
            scenario.onActivity { url = it.webUrlForTest() }
            assertNotNull(url)
            assertTrue("只加载本地资产：$url", url!!.startsWith(McpWebAdminActivity.ASSET_DIR))

            // 状态走真实 AndroidAdminEnv（模拟器上 HC 不可用 ⇒ 健康段空数组 + note，不影响形状）
            // 放到工作线程：真实设备上健康段会读 Health Connect，不能在主线程跑
            scenario.onActivity { activityRef = it }
            var state: JSONObject? = null
            val worker = Thread({ state = requireNotNull(activityRef) { "activity" }.stateJson() })
            worker.start()
            worker.join(20_000)
            val s = requireNotNull(state) { "state 超时" }
            assertEquals(1, s.getJSONArray("users").length())
            assertEquals("single", s.getJSONObject("caps").getString("users"))
            assertEquals("single", s.getJSONObject("caps").getString("sessions"))
            assertTrue("本机访问卡片", s.getJSONObject("caps").getBoolean("localAccess"))
            assertTrue("策略编辑", s.getJSONObject("caps").getBoolean("policies"))
            assertEquals("admin", s.getJSONObject("me").getString("username"))
            val local = s.getJSONObject("localAccess")
            assertTrue("只报存没存", local.getBoolean("hasToken"))
            assertFalse("tunnel token 不能进 state", s.toString().contains(TUNNEL_TOKEN_SENTINEL))
            assertFalse("状态里不能有密码", s.toString().contains(pw))
            assertFalse("状态里不能有密码哈希", s.toString().contains(requireNotNull(McpConfig(store).adminPasswordHash)))
        } finally {
            scenario.close()
            current = null
            activityRef = null
            Secure.putString(ctx, Tunnel.SECURE_KEY, previousToken)
        }
    }

    private var activityRef: McpWebAdminActivity? = null

    @Test
    fun stopLocksAndDetachesBridge() {
        val scenario = launch()
        try {
            unlock(scenario)
            waitUntil { it.bridgeAttachedForTest() }
            scenario.moveToState(Lifecycle.State.CREATED)
            // about:blank 是一次真正的导航：等它提交，别拿"还没加载完"当结论
            val deadline = System.currentTimeMillis() + 10_000
            var url: String? = null
            while (System.currentTimeMillis() < deadline) {
                scenario.onActivity { url = it.webUrlForTest() }
                if (url == McpWebAdminActivity.ABOUT_BLANK) break
                Thread.sleep(50)
            }
            scenario.onActivity { activity ->
                assertFalse("上锁后管理内容不可见", activity.adminVisibleForTest())
                assertFalse("上锁后 JS 桥必须移除", activity.bridgeAttachedForTest())
                assertEquals(McpWebAdminActivity.ABOUT_BLANK, activity.webUrlForTest())
                assertFalse(AdminThrottle.isOpen(System.currentTimeMillis()))
                val res = activity.actJson("audit.verify", "{}")
                assertFalse("上锁后动作一律拒绝", res.optBoolean("ok", true))
                val st = activity.stateJson()
                assertTrue("上锁后不给状态", st.has("error"))
            }
        } finally {
            scenario.close()
            current = null
        }
    }

    @Test
    fun backKeyClosesFromTheGate() {
        val scenario = launch()
        try {
            // 门在：返回键直接关页面（页面没加载过，不评估 JS）
            onView(withId(R.id.gateBox)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline && scenario.state != Lifecycle.State.DESTROYED) {
                Thread.sleep(50)
            }
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
        } finally {
            scenario.close()
            current = null
        }
    }

    @Test
    fun adminUiModeDefaultsToWebAndAllowsNativeFallback() {
        val config = McpConfig(store)
        assertEquals("web", config.adminUiMode)
        config.adminUiMode = McpConfig.ADMIN_UI_NATIVE
        assertEquals("native", config.adminUiMode)
        // 脏值退回默认（不因为一个坏字符串把入口弄丢）
        store.setSetting(McpConfig.KEY_ADMIN_UI_MODE, "bogus")
        assertEquals(McpConfig.ADMIN_UI_WEB, McpConfig(store).adminUiMode)
    }

    @Test
    fun wrongPasswordsStillLockTheGate() {
        val scenario = launch()
        try {
            repeat(5) {
                onView(withId(R.id.gatePassword)).perform(replaceText("wrong-password"))
                onView(withId(R.id.btnGateUnlock)).perform(click())
                waitEnabled(R.id.btnGateUnlock)
            }
            scenario.onActivity {
                assertFalse(it.adminVisibleForTest())
                assertFalse(it.bridgeAttachedForTest())
            }
            assertTrue(AdminThrottle.lockedFor(System.currentTimeMillis()) > 0)
            val failures = store.auditRows(action = "admin_unlock")
            assertTrue(failures.isNotEmpty())
            assertTrue(failures.all { it.result == "failed" })
            assertFalse(failures.any { it.detail?.contains("wrong-password") == true })
        } finally {
            scenario.close()
            current = null
        }
    }

    private fun waitEnabled(id: Int) {
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
}
