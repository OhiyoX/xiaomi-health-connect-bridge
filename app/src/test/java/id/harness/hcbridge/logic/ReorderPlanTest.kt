package id.harness.hcbridge.logic

import org.junit.Assert.assertEquals
import org.junit.Test

/** 拖动换位几何：一次一格、边界不越位。 */
class ReorderPlanTest {

    private val centers = listOf(100f, 300f, 500f)

    @Test
    fun `没越过相邻卡片中心就不换位`() {
        assertEquals(0, ReorderPlan.targetIndex(centers, from = 0, draggedCenter = 150f))
        assertEquals(1, ReorderPlan.targetIndex(centers, from = 1, draggedCenter = 320f))
        assertEquals(2, ReorderPlan.targetIndex(centers, from = 2, draggedCenter = 480f))
    }

    @Test
    fun `向下拖动越过下一位中心换一格`() {
        assertEquals(1, ReorderPlan.targetIndex(centers, from = 0, draggedCenter = 301f))
        assertEquals(2, ReorderPlan.targetIndex(centers, from = 1, draggedCenter = 501f))
    }

    @Test
    fun `向上拖动越过上一位中心换一格`() {
        assertEquals(1, ReorderPlan.targetIndex(centers, from = 2, draggedCenter = 299f))
        assertEquals(0, ReorderPlan.targetIndex(centers, from = 1, draggedCenter = 99f))
    }

    @Test
    fun `一次只移动一格 不跳过邻居`() {
        // 手指一口气拖到最底：也应该只走到相邻一格（跟手，不跳位）
        assertEquals(1, ReorderPlan.targetIndex(centers, from = 0, draggedCenter = 900f))
        assertEquals(1, ReorderPlan.targetIndex(centers, from = 2, draggedCenter = 0f))
    }

    @Test
    fun `越界索引原样返回`() {
        assertEquals(9, ReorderPlan.targetIndex(centers, from = 9, draggedCenter = 100f))
        assertEquals(0, ReorderPlan.targetIndex(emptyList(), from = 0, draggedCenter = 100f))
    }

    @Test
    fun `applyMove 与拖动换位语义一致`() {
        val order = listOf("steps", "hr", "spo2", "sleep")
        assertEquals(listOf("hr", "steps", "spo2", "sleep"), ReorderPlan.applyMove(order, 0, 1))
        assertEquals(listOf("steps", "spo2", "hr", "sleep"), ReorderPlan.applyMove(order, 1, 2))
        assertEquals(order, ReorderPlan.applyMove(order, 2, 2))
    }
}
