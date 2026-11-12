package id.harness.hcbridge.logic

import kotlin.math.abs

/**
 * 手势归属仲裁 —— **纯决策**，不碰 View（JVM 可直接断言）。
 *
 * 这套规则是 2026-10-18 那次「长按拖动在手机上完全没动」的根因总结：
 * 图表在 `ACTION_DOWN` 就把 `requestDisallowInterceptTouchEvent(true)` 打给父容器，
 * 父容器的 `onInterceptTouchEvent` 从此收不到事件 → 长按永远检测不到 → 拖动不启动。
 *
 * 现在的规则（写死在这里，图表与排序容器都按它判）：
 * 1. **DOWN 阶段谁也不抢手势**（否则容器/滚动条看不到事件）；
 * 2. 图表只在**明显横向**拖动时才抢（竖向留给滚动与长按拖动）；
 * 3. 排序容器在长按候选期内一旦竖向位移超过 slop 就取消候选，把滚动交还 `ScrollView`；
 * 4. 手势结束一律释放。
 */
object GestureArbitration {

    /** 判定 slop（dp）：位移小于它算"没动"。 */
    const val SLOP_DP = 12f

    /** DOWN 阶段是否抢占父容器拦截：永远不。 */
    const val CLAIM_ON_DOWN = false

    /**
     * 图表是否该抢占本次手势：横向位移超 slop **且** 横向分量大于竖向分量。
     * 只按横向判，是为了让"竖向滑动滚页面""竖向长按拖动卡片"都能发生。
     */
    fun chartClaimsHorizontal(dx: Float, dy: Float, slopPx: Float): Boolean =
        abs(dx) > slopPx && abs(dx) > abs(dy)

    /** 长按候选期内是否该取消候选（用户其实想滚动）。 */
    fun cancelsLongPress(movedY: Float, slopPx: Float): Boolean = abs(movedY) > slopPx

    /** 拖动中：被拖卡片是否已越过相邻卡片中心，需要换位。 */
    fun crossesNeighbor(draggedCenter: Float, neighborCenter: Float, moveDown: Boolean): Boolean =
        if (moveDown) draggedCenter > neighborCenter else draggedCenter < neighborCenter
}
