package id.harness.hcbridge.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手势归属规则 —— 2026-10-18「长按拖动在手机上完全没动」的回归网。
 *
 * 根因：图表在 ACTION_DOWN 就把拦截打给父容器 → 容器的 onInterceptTouchEvent 收不到事件 → 长按检测不到。
 * 这里把当时的四条结论固定成断言。
 */
class GestureArbitrationTest {

    private val slop = GestureArbitration.SLOP_DP

    @Test
    fun `DOWN 阶段谁也不抢手势`() {
        assertFalse("DOWN 抢占拦截会让父容器/滚动条看不到这次手势", GestureArbitration.CLAIM_ON_DOWN)
    }

    @Test
    fun `图表只在明显横向拖动时抢手势`() {
        assertTrue(GestureArbitration.chartClaimsHorizontal(dx = -30f, dy = 4f, slopPx = slop))
        assertTrue(GestureArbitration.chartClaimsHorizontal(dx = 13f, dy = 2f, slopPx = slop))
        assertFalse("没过 slop 不算拖动", GestureArbitration.chartClaimsHorizontal(12f, 0f, slop))
        assertFalse("竖向为主要留给滚动/长按拖动", GestureArbitration.chartClaimsHorizontal(20f, 40f, slop))
        assertFalse("斜 45 度不抢（否则竖向滚动会被吃掉）", GestureArbitration.chartClaimsHorizontal(20f, 20f, slop))
    }

    @Test
    fun `长按候选在位移超过 slop 时取消`() {
        assertFalse(GestureArbitration.cancelsLongPress(12f, slop))
        assertTrue(GestureArbitration.cancelsLongPress(12.1f, slop))
        assertTrue(GestureArbitration.cancelsLongPress(-40f, slop))
    }

    @Test
    fun `换位判据带方向`() {
        assertTrue(GestureArbitration.crossesNeighbor(draggedCenter = 310f, neighborCenter = 300f, moveDown = true))
        assertFalse(GestureArbitration.crossesNeighbor(draggedCenter = 310f, neighborCenter = 300f, moveDown = false))
        assertTrue(GestureArbitration.crossesNeighbor(draggedCenter = 290f, neighborCenter = 300f, moveDown = false))
    }

    @Test
    fun `拖拽容器与几何决策共用同一 slop`() {
        assertEquals(GestureArbitration.SLOP_DP, ReorderPlan.SLOP_DP, 0.0f)
    }
}
