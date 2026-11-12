package id.harness.hcbridge

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 图表触摸与"手势归属"契约（在模拟器上跑）。
 *
 * 这三条是 2026-10-18 那轮问题的精确回归：
 * 1. 图表**按下时**不能告诉父容器"别拦截" —— 否则父容器的长按拖动检测永远收不到事件；
 * 2. 图表只在**横向**拖动时才抢手势 —— 竖向要留给滚动与长按拖动；
 * 3. 图表自身的 tooltip（onSelect 回调）不能因此失效。
 */
@RunWith(AndroidJUnit4::class)
class ChartTouchTest {

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
    }

    @Test
    fun 心率图按下时不向父容器要拦截() {
        val (parent, chart) = mount { ctx ->
            HrSparkView(ctx).apply { setData(hrData(), accent) }
        }
        assertNotNull(chart)
        scenario.onActivity {
            parent.reset()
            parent.dispatchTouchEvent(parent.event(MotionEvent.ACTION_DOWN, 400f, 180f))
            assertEquals(
                "ACTION_DOWN 阶段抢拦截 = 长按拖动永久失效（回归点）",
                0,
                parent.calls
            )
            parent.dispatchTouchEvent(parent.event(MotionEvent.ACTION_UP, 400f, 180f))
        }
    }

    @Test
    fun 血氧图只有横向拖动才要拦截() {
        val (parent, chart) = mount { ctx ->
            Spo2RangeView(ctx).apply { setData(spo2Data(), accent) }
        }
        assertNotNull(chart)
        scenario.onActivity {
            parent.reset()
            parent.dispatchTouchEvent(parent.event(MotionEvent.ACTION_DOWN, 400f, 180f))
            assertEquals(0, parent.calls)

            // 竖向为主：留给滚动
            parent.dispatchTouchEvent(parent.event(MotionEvent.ACTION_MOVE, 404f, 300f))
            assertFalse("竖向移动不该抢手势", parent.lastDisallow)

            // 横向为主：抢（图表要能横向刷值）
            parent.dispatchTouchEvent(parent.event(MotionEvent.ACTION_MOVE, 620f, 300f))
            assertTrue("横向拖动应抢手势", parent.lastDisallow)

            parent.dispatchTouchEvent(parent.event(MotionEvent.ACTION_UP, 620f, 300f))
            assertFalse("抬手后必须释放拦截", parent.lastDisallow)
        }
    }

    @Test
    fun 心率图拖动会回调选中值() {
        val seen = ArrayList<String?>()
        val (parent, chart) = mount { ctx ->
            HrSparkView(ctx).apply {
                setData(hrData(), accent)
                onSelect = { seen.add(it) }
            }
        }
        assertNotNull(chart)
        scenario.onActivity {
            parent.dispatchTouchEvent(parent.event(MotionEvent.ACTION_DOWN, 100f, 180f))
            parent.dispatchTouchEvent(parent.event(MotionEvent.ACTION_MOVE, 600f, 180f))
            parent.dispatchTouchEvent(parent.event(MotionEvent.ACTION_MOVE, 800f, 180f))
        }
        val first = seen.firstOrNull { it != null }
        assertNotNull("长按/拖动图表应给出 tooltip 文案", first)
        assertTrue("文案形如 `HH:mm · NN bpm`，实际=$first", first!!.contains("bpm"))
        assertTrue("拖动过程中应有多次更新，实际=${seen.size}", seen.size >= 1)
    }

    @Test
    fun 血氧tooltip会自己消除() {
        val seen = java.util.Collections.synchronizedList(ArrayList<String?>())
        val (parent, chart) = mount { ctx ->
            Spo2RangeView(ctx).apply {
                setData(spo2Data(), accent)
                onSelect = { seen.add(it) }
            }
        }
        assertNotNull(chart)
        scenario.onActivity {
            parent.dispatchTouchEvent(parent.event(MotionEvent.ACTION_DOWN, 400f, 180f))
            parent.dispatchTouchEvent(parent.event(MotionEvent.ACTION_UP, 400f, 180f))
        }
        assertTrue("按下应给出选中值，实际=$seen", seen.any { it != null })
        // 抬手后 1.5s 自动消除（曾经只清自己的选中态，卡片上留下一个永不消失的 98%）
        Thread.sleep(1900)
        assertEquals("抬手 1.5s 后应回调 null 把 tooltip 清掉，实际=$seen", null, seen.lastOrNull())
    }

    // ---- helpers ----

    private val accent = 0xFF8FB3FF.toInt()

    private fun hrData(): List<Pair<Long, Long>> {
        val t0 = 1_760_000_000L
        return (0 until 60).map { (t0 + it * 600L) to (58L + (it % 17)) }
    }

    private fun spo2Data(): List<Double> = (0 until 120).map { 96.0 + (it % 3) * 0.6 - if (it % 40 == 0) 4.0 else 0.0 }

    private fun <T : View> mount(make: (Context) -> T): Pair<RecordingParent, T> {
        lateinit var parent: RecordingParent
        lateinit var child: T
        scenario.onActivity { act ->
            parent = RecordingParent(act)
            child = make(act)
            parent.addView(child, ViewGroup.LayoutParams(900, 360))
            act.findViewById<ViewGroup>(R.id.container)
                .addView(parent, ViewGroup.LayoutParams(900, 360))
            parent.measure(
                View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY)
            )
            parent.layout(0, 0, 900, 360)
        }
        return parent to child
    }
}
