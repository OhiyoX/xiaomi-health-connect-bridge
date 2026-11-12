package id.harness.hcbridge

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import id.harness.hcbridge.logic.GestureArbitration
import id.harness.hcbridge.logic.ReorderPlan

/**
 * 长按后可直接拖动换位的纵向容器。
 *
 * 为什么不用 ItemTouchHelper：这里是 LinearLayout 里的 4 张卡片，不是 RecyclerView；
 * 而且卡片里的图表会消费触摸，所以由父容器在 ACTION_DOWN 起算长按、长按**成立且有位移**后接管拖动。
 *
 * 两条关键约束（都踩过坑）：
 * 1. **按下瞬间不抢手势**：图表在 ACTION_DOWN 抢拦截会让容器收不到事件，长按永远检测不到；
 * 2. **长按成立但没位移时不抬卡**：否则和「长按图表看 tooltip / 点图表进详情」撞车。
 *
 * 手势归属规则见 logic/GestureArbitration。
 */
class ReorderLinearLayout @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : LinearLayout(ctx, attrs, defStyle) {

    /** 顺序变化回调：返回各子视图 tag（业务键）的顺序。 */
    var onOrderChanged: ((List<String>) -> Unit)? = null

    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val slopPx = GestureArbitration.SLOP_DP * resources.displayMetrics.density
    private var pressedChild: View? = null
    private var downY = 0f
    private var dragging = false
    private var armed = false              // 长按已成立，等第一次位移才真正开始拖
    private var dragOffset = 0f
    private var dragChild: View? = null
    private val flip = Runnable { armed = true }

    init {
        orientation = VERTICAL
        // 被拖动的那张卡要画在其它卡上面
        setChildrenDrawingOrderEnabled(true)
        clipChildren = false
        clipToPadding = false
    }

    /** 把正在拖动的子视图排到最后绘制（即最上层），不改变子视图索引，排序结果不受影响。 */
    override fun getChildDrawingOrder(childCount: Int, drawingPosition: Int): Int {
        val d = dragChild ?: return drawingPosition
        val di = indexOfChild(d)
        if (di < 0) return drawingPosition
        return when {
            drawingPosition == childCount - 1 -> di
            drawingPosition >= di -> drawingPosition + 1
            else -> drawingPosition
        }
    }

    private fun childAtPoint(y: Float): View? {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (y >= c.top && y <= c.bottom) return c
        }
        return null
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(flip)
        super.onDetachedFromWindow()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedChild = childAtPoint(ev.y)
                downY = ev.y
                dragging = false
                armed = false
                // 关键：外层是 ScrollView，长按期间必须先禁止它拦截，否则竖向拖动会被它抢走
                parent?.requestDisallowInterceptTouchEvent(true)
                if (pressedChild != null) postDelayed(flip, longPressTimeout)
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) return true
                val moved = GestureArbitration.cancelsLongPress(ev.y - downY, slopPx)
                if (pressedChild != null && moved) {
                    if (armed) {
                        // 长按 + 位移 → 这一刻才接管手势（长按后只按不拖不会抬卡）
                        removeCallbacks(flip)
                        startDrag()
                        return true
                    }
                    // 还没到长按就滑动了：用户想滚动，取消长按候选并把拦截交还 ScrollView
                    removeCallbacks(flip); pressedChild = null
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(flip)
                pressedChild = null
                armed = false
                if (!dragging) parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return dragging
    }

    private fun startDrag() {
        val c = pressedChild ?: return
        dragChild = c
        dragging = true
        dragOffset = 0f
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        val density = resources.displayMetrics.density
        // 抬起：给卡片真实阴影（MaterialCardView 用 cardElevation），再轻微放大
        if (c is com.google.android.material.card.MaterialCardView) {
            c.cardElevation = 12f * density
        } else {
            c.translationZ = 12f * density
        }
        c.animate().scaleX(1.02f).scaleY(1.02f).setDuration(120).start()
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!dragging) return false
        val c = dragChild ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                dragOffset = ev.y - downY
                c.translationY = dragOffset
                // 越过相邻卡片中点就换位（几何决策见 logic/ReorderPlan）
                val center = (c.top + c.bottom) / 2f + dragOffset
                val from = indexOfChild(c)
                val target = ReorderPlan.targetIndex(
                    centers = (0 until childCount).map { (getChildAt(it).top + getChildAt(it).bottom) / 2f },
                    from = from,
                    draggedCenter = center
                )
                if (target != from) {
                    swap(c, target); downY = ev.y; dragOffset = 0f; c.translationY = 0f
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                endDrag()
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    /** 收尾：缩放/位移/阴影复位，必要时回报顺序。 */
    private fun endDrag() {
        val c = dragChild ?: return
        c.animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(140).start()
        if (c is com.google.android.material.card.MaterialCardView) c.cardElevation = 0f else c.translationZ = 0f
        val wasDragging = dragging
        dragging = false
        armed = false
        dragChild = null
        pressedChild = null
        if (wasDragging) {
            // 业务键存在资源键 tag 上（View.tag 会被卡片着色的代码覆盖）
            val order = (0 until childCount).mapNotNull { getChildAt(it).getTag(R.id.tag_reorder_key) as? String }
            onOrderChanged?.invoke(order)
        }
    }

    private fun swap(child: View, targetIndex: Int) {
        removeView(child)
        addView(child, targetIndex)
        child.translationY = 0f
    }
}
