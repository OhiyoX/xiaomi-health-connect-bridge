package id.harness.hcbridge

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 长按拖动排序 —— 端到端回归（在模拟器上跑；2026-10-18「长按拖动还是不对」那次）。
 *
 * 这里的拖动**从图表区域起手**：卡片里的图表会消费触摸，过去图表在 `ACTION_DOWN` 就
 * `requestDisallowInterceptTouchEvent(true)`，让排序容器的 `onInterceptTouchEvent` 收不到事件，
 * 于是长按检测不到、拖动完全不动。这条路径必须一直是绿的。
 *
 * 同时钉住"顺序确实落盘"：业务键存在**资源键 tag**（`R.id.tag_reorder_key`）上，
 * 而不是 `View.tag`（后者会被卡片着色的 `card.tag = fg` 覆盖，导致松手后读不到键、顺序静默丢失）。
 */
@RunWith(AndroidJUnit4::class)
class ReorderGestureTest {

    private lateinit var scenario: ActivityScenario<MainActivity>

    private val defaultOrder = listOf("steps", "hr", "spo2", "sleep", "body")

    @Before
    fun setUp() {
        Prefs.setCardOrder(ApplicationProvider.getApplicationContext(), defaultOrder)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        onView(withId(R.id.nav_data)).perform(click())
    }

    @After
    fun tearDown() {
        scenario.close()
        Prefs.setCardOrder(ApplicationProvider.getApplicationContext(), defaultOrder)
    }

    @Test
    fun 长按拖动手里的图表卡片能换位并写入顺序() {
        seedHrChart()
        assertEquals("初始顺序应为默认值", defaultOrder, cardOrder())
        // 心率卡（index 1）里是真正会消费触摸的图表；把它往上拖一位
        onView(withId(R.id.cardsBox)).perform(LongPressDragAction(fromIndex = 1, slots = -1.2f))
        assertEquals(
            "起手点必须落在项目自绘的图表上（否则覆盖不到“图表消费触摸”这条路径）",
            "HrSparkView",
            LongPressDragAction.lastAnchorClass
        )
        val after = cardOrder()
        assertEquals("心率卡应换到第一位；现场：${LongPressDragAction.lastDiag}", listOf("hr", "steps", "spo2", "sleep", "body"), after)
        assertEquals(
            "顺序应持久化（MainActivity 通过 onOrderChanged 写 Prefs）",
            after,
            Prefs.cardOrder(ApplicationProvider.getApplicationContext())
        )

        // 重建 Activity：顺序必须从 Prefs 恢复（applyCardOrder 必须真的被调用）
        scenario.recreate()
        onView(withId(R.id.nav_data)).perform(click())
        assertEquals("重建后应按持久化顺序排列", after, cardOrder())
    }

    @Test
    fun 向下拖到底也只换一格并保持全部卡片在场() {
        seedHrChart()
        onView(withId(R.id.cardsBox)).perform(LongPressDragAction(fromIndex = 1, slots = 3f))
        val after = cardOrder()
        assertEquals("卡片数量不能变", 5, after.size)
        assertEquals("集合不能变，只是顺序变了", defaultOrder.toSet(), after.toSet())
        assertTrue("必须发生过换位", after != defaultOrder)
    }

    @Test
    fun 长按图表不移动时不换位且图表tooltip仍工作() {
        seedHrChart()
        assertEquals(defaultOrder, cardOrder())
        val hold = HoldOnChartAction(fromIndex = 1)
        onView(withId(R.id.cardsBox)).perform(hold)
        assertEquals(
            "长按只按不拖不能抬卡/换位（否则与图表的长按看 tooltip、点按进详情抢手势）",
            defaultOrder, cardOrder()
        )
        assertTrue("按住图表时图表自己的 tooltip 应该已经出来", hold.tipShownWhileHolding)
    }

    @Test
    fun 竖向滑动仍然滚动页面() {
        // 数据页在无数据的模拟器上可能不够高（滚不动），所以这项断言放在设置页：
        // 要点是"竖向手势不能被长按检测吃掉"。
        onView(withId(R.id.nav_settings)).perform(click())
        assertTrue("设置页应有可滚动内容，否则这条断言本身无意义", canScroll())
        val before = scrollerScrollY()
        onView(withId(R.id.container)).perform(ScrollPageAction(dy = -600f))
        val after = scrollerScrollY()
        assertTrue("scrollY 应增加：before=$before after=$after", after > before)
    }

    // ---- helpers ----

    private fun <T> onActivity(block: (MainActivity) -> T): T {
        var out: T? = null
        scenario.onActivity { out = block(it) }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    private fun cardOrder(): List<String> = onActivity { act ->
        val box = act.findViewById<ViewGroup>(R.id.cardsBox)
        (0 until box.childCount).mapNotNull { box.getChildAt(it).getTag(R.id.tag_reorder_key) as? String }
    }

    private fun scroller(): View? = onActivity { act ->
        findScrollable(act.findViewById<ViewGroup>(R.id.container))
    }

    /** 给心率卡填合成数据并显示图表：拖动必须从"会消费触摸的图表"上起手，才覆盖得到真实路径。 */
    private fun seedHrChart() {
        scenario.onActivity { act ->
            val hr = act.findViewById<HrSparkView>(R.id.hrChart) ?: return@onActivity
            val t0 = 1_760_000_000L
            hr.setData((0 until 40).map { (t0 + it * 600L) to (60L + it % 12) }, 0xFF8FB3FF.toInt())
            hr.visibility = View.VISIBLE
        }
    }

    private fun canScroll(): Boolean = scroller()?.canScrollVertically(1) ?: false

    private fun scrollerScrollY(): Int = scroller()?.scrollY ?: -1
}

/** 找页面上第一个能竖向滚动的容器（ScrollView / NestedScrollView 的公共判据）。 */
private fun findScrollable(v: View): View? {
    if (v.canScrollVertically(1) || v.canScrollVertically(-1)) return v
    if (v is ViewGroup) {
        for (i in 0 until v.childCount) findScrollable(v.getChildAt(i))?.let { return it }
    }
    return null
}

/** 长按后竖向拖动第 [fromIndex] 张卡片，位移 = 卡片高 × [slots]。 */
private class LongPressDragAction(private val fromIndex: Int, private val slots: Float) : ViewAction {

    companion object {
        /** 上一次拖动实际起手在哪个自绘 View 上（断言用：必须真的落在图表上）。 */
        var lastAnchorClass: String? = null
            private set

        /** 上一次拖动的现场（失败时看这个，不用猜）。 */
        var lastDiag: String = ""
            private set
    }

    override fun getConstraints(): Matcher<View> = isAssignableFrom(ReorderLinearLayout::class.java)

    override fun getDescription(): String = "长按拖动第 $fromIndex 张卡片（${slots}×卡片高）"

    override fun perform(uiController: UiController, view: View) {
        val box = view as ReorderLinearLayout
        val child = box.getChildAt(fromIndex) ?: return
        val loc = IntArray(2)
        box.getLocationOnScreen(loc)
        // 起手点故意落在卡片的**图表**上：图表会消费触摸，这里正是出问题的那条路径
        val anchor = chartOf(child) ?: child
        lastAnchorClass = anchor.javaClass.simpleName
        val anchorLoc = IntArray(2)
        anchor.getLocationOnScreen(anchorLoc)
        // 起手点就取图表的屏幕中心：卡片高度中点不一定落在图表上（布局变化后曾偏出）
        val x = anchorLoc[0] + anchor.width / 2f
        val y = anchorLoc[1] + anchor.height / 2f
        val to = y + child.height * slots
        lastDiag = buildString {
            append("box loc=${loc[0]},${loc[1]} size=${box.width}x${box.height}; ")
            append("children=")
            for (i in 0 until box.childCount) {
                val c = box.getChildAt(i)
                append("#").append(i).append('(').append(c.getTag(R.id.tag_reorder_key))
                    .append(" top=").append(c.top).append(" bottom=").append(c.bottom).append(") ")
            }
            append("; anchor=").append(anchor.javaClass.simpleName)
            append(" bounds=${anchorLoc[0]},${anchorLoc[1]},").append(anchor.width).append('x').append(anchor.height)
            append("; down=($x,$y) up=($x,$to)")
        }

        // 直接用 injectMotionEvent：坐标原样进框架，不经中间层换算（MotionEvents 的 MOVE 会被缩幅）
        val downTime = SystemClock.uptimeMillis()
        touch(uiController, MotionEvent.ACTION_DOWN, downTime, x, y)
        // 手指按住不动，等过系统的长按超时（这就是"长按"）
        uiController.loopMainThreadForAtLeast(ViewConfiguration.getLongPressTimeout() + 250L)
        for (i in 1..10) {
            touch(uiController, MotionEvent.ACTION_MOVE, downTime, x, y + (to - y) * i / 10f)
            uiController.loopMainThreadForAtLeast(32)
        }
        touch(uiController, MotionEvent.ACTION_UP, downTime, x, to)
        uiController.loopMainThreadForAtLeast(400)
        lastDiag += "; after=" + (0 until box.childCount).map { box.getChildAt(it).getTag(R.id.tag_reorder_key) }
    }

    private fun touch(uiController: UiController, action: Int, downTime: Long, x: Float, y: Float) {
        val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        uiController.injectMotionEvent(e)
        e.recycle()
    }
}

/** 找卡片里的自绘图表（本项目的自定义 View）；找不到就返回 null。 */private fun chartOf(v: View): View? {
    if (v is ViewGroup) {
        for (i in 0 until v.childCount) chartOf(v.getChildAt(i))?.let { return it }
        return null
    }
    return if (v.width > 0 && v.height > 0 && v.javaClass.name.startsWith("id.harness.hcbridge")) v else null
}

/** 在卡片的图表上长按（按住不动）后松手：用于验证"只按不拖"不启动拖动。 */
private class HoldOnChartAction(private val fromIndex: Int) : ViewAction {

    var tipShownWhileHolding = false
        private set

    override fun getConstraints(): Matcher<View> = isAssignableFrom(ReorderLinearLayout::class.java)

    override fun getDescription(): String = "在图表上长按不动（fromIndex=$fromIndex）"

    override fun perform(uiController: UiController, view: View) {
        val box = view as ReorderLinearLayout
        val child = box.getChildAt(fromIndex) ?: return
        val anchor = chartOf(child) ?: child
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val x = loc[0] + anchor.width / 2f
        val y = loc[1] + anchor.height / 2f
        val downTime = SystemClock.uptimeMillis()
        send(uiController, MotionEvent.ACTION_DOWN, downTime, x, y)
        uiController.loopMainThreadForAtLeast(ViewConfiguration.getLongPressTimeout() + 350L)
        // 按住期间：图表自己的 tooltip 应已在显示（卡片 tooltip 由 MainActivity 接到 hrTip）
        val tip = view.rootView.findViewById<View>(R.id.hrTip)
        tipShownWhileHolding = tip != null && tip.visibility == View.VISIBLE && !(tip as android.widget.TextView).text.isNullOrBlank()
        send(uiController, MotionEvent.ACTION_UP, downTime, x, y)
        uiController.loopMainThreadForAtLeast(400)
    }

    private fun send(uiController: UiController, action: Int, downTime: Long, x: Float, y: Float) {
        val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        uiController.injectMotionEvent(e)
        e.recycle()
    }
}

/** 在当前页面的可滚动容器里做一次竖向滑动（不等待长按）。 */
private class ScrollPageAction(private val dy: Float) : ViewAction {

    override fun getConstraints(): Matcher<View> = isAssignableFrom(ViewGroup::class.java)

    override fun getDescription(): String = "页面内竖向滑动 $dy px"

    override fun perform(uiController: UiController, view: View) {
        val scroller = findScrollable(view) ?: throw AssertionError("当前页面没有可滚动的容器")
        val loc = IntArray(2)
        scroller.getLocationOnScreen(loc)
        val x = loc[0] + scroller.width / 2f
        val y0 = loc[1] + scroller.height * 0.7f
        val downTime = SystemClock.uptimeMillis()
        inject(uiController, MotionEvent.ACTION_DOWN, downTime, x, y0)
        for (i in 1..10) {
            inject(uiController, MotionEvent.ACTION_MOVE, downTime, x, y0 + dy * i / 10f)
            uiController.loopMainThreadForAtLeast(32)
        }
        inject(uiController, MotionEvent.ACTION_UP, downTime, x, y0 + dy)
        uiController.loopMainThreadForAtLeast(400)
    }

    private fun inject(uiController: UiController, action: Int, downTime: Long, x: Float, y: Float) {
        val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        uiController.injectMotionEvent(e)
        e.recycle()
    }
}

/** 记录子视图有没有向父容器要"别拦截"（ChartTouchTest 用）。 */
class RecordingParent(context: Context) : FrameLayout(context) {
    var calls = 0
        private set
    var lastDisallow = false
        private set

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        calls++
        lastDisallow = disallowIntercept
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    fun reset() {
        calls = 0
        lastDisallow = false
    }

    fun event(action: Int, x: Float, y: Float): MotionEvent {
        val t = android.os.SystemClock.uptimeMillis()
        return MotionEvent.obtain(t, t, action, x, y, 0)
    }
}
