package id.harness.hcbridge.logic

/**
 * 长按拖动换位的纯几何决策（不含任何 View 操作，JVM 可直测）。
 *
 * 语义：被拖卡片的中心越过相邻卡片中心就换位，**一次一格**（保持"跟手"，不跳位）。
 */
object ReorderPlan {

    /** 位移超过它才认为用户开始滚动/拖动（dp，调色见 [GestureArbitration.SLOP_DP]）。 */
    const val SLOP_DP = GestureArbitration.SLOP_DP

    /**
     * @param centers 各子视图当前中心 Y（按子视图索引顺序）
     * @param from 正在拖动的子视图索引
     * @param draggedCenter 拖动中卡片的中心 Y（已含手指位移）
     * @return 换位目标索引；没越过任何相邻卡片就返回 [from]
     */
    fun targetIndex(centers: List<Float>, from: Int, draggedCenter: Float): Int {
        if (from !in centers.indices) return from
        for (i in from + 1 until centers.size) if (draggedCenter > centers[i]) return from + 1
        for (i in from - 1 downTo 0) if (draggedCenter < centers[i]) return from - 1
        return from
    }

    /** 松手后的最终顺序：把 [from] 位置的元素移到 [to]。 */
    fun <T> applyMove(items: List<T>, from: Int, to: Int): List<T> {
        if (from !in items.indices || to !in items.indices || from == to) return items
        val m = items.toMutableList()
        m.add(to, m.removeAt(from))
        return m
    }
}
