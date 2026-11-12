package id.harness.hcbridge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 24h 心率折线（克制版）：
 * - 不画坐标轴/网格，只有折线 + 面积；
 * - 触摸时出现竖向虚线 + 吸附圆环 + 上方浮层（inverseSurface 底、按亮度自动取前景），松手淡出；
 * - 拖动连续跟手（禁父级拦截），吸附变化给一次轻触感。
 */
class HrSparkView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(ctx, attrs, defStyle) {

    private val sp = resources.displayMetrics.scaledDensity
    private var pts: List<Pair<Long, Long>> = emptyList()
    private var accent = Color.parseColor("#4FD1A5")
    private var tooltipBg = Color.parseColor("#E6E9EC")
    private var tooltipFg = Color.parseColor("#1B1F22")

    private val line = Paint().apply { strokeWidth = 3f; isAntiAlias = true; style = Paint.Style.STROKE }
    private val fill = Paint().apply { style = Paint.Style.FILL }
    private val cross = Paint().apply {
        strokeWidth = 2f; isAntiAlias = true
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val dot = Paint().apply { isAntiAlias = true }
    private val ring = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 3f }
    private val bubble = Paint().apply { isAntiAlias = true }
    private val tip = Paint().apply { isAntiAlias = true; textSize = 14f * sp; letterSpacing = 0.01f }
    private val box = RectF()
    private var sel = -1
    private val longPressRunnable = Runnable { performLongClick() }
    private var downX = 0f
    private var downT = 0L
    private var fade = 1f

    /** 选中值变化回调：由卡片把文字放到副标题（比压在图上更"高"、也不挡曲线）。 */
    var onSelect: ((String?) -> Unit)? = null

    /** 顶部预留的 tooltip 带高度（气泡固定在这里，不压曲线）。 */
    private val lane: Float get() = 0f
    private fun plotTop() = lane

    init {
        isClickable = true
        isFocusable = true
    }

    fun setData(raw: List<Pair<Long, Long>>, accentColor: Int) {
        pts = downsample(raw)
        accent = accentColor
        line.color = accent
        fill.color = Color.argb(38, Color.red(accent), Color.green(accent), Color.blue(accent))
        cross.color = Color.argb(150, Color.red(accent), Color.green(accent), Color.blue(accent))
        dot.color = accent
        ring.color = accent
        sel = -1
        invalidate()
    }

    /** 只给底色，前景按亮度自动取（保证对比度）。 */
    fun setTooltipColors(bg: Int) {
        tooltipBg = bg
        tooltipFg = readableOn(bg)
        bubble.color = bg
        tip.color = tooltipFg
        invalidate()
    }

    /** 兼容旧调用（干净版不再有轴）。 */
    fun setAxisColor(@Suppress("UNUSED_PARAMETER") c: Int) = Unit

    private fun downsample(src: List<Pair<Long, Long>>, target: Int = 96): List<Pair<Long, Long>> {
        if (src.size <= target) return src
        val t0 = src.first().first
        val t1 = src.last().first.coerceAtLeast(t0 + 1)
        val span = (t1 - t0).toDouble()
        val sumT = HashMap<Int, Long>()
        val sumB = HashMap<Int, Long>()
        val cnt = HashMap<Int, Int>()
        for ((t, b) in src) {
            val idx = (((t - t0) / span) * target).toInt().coerceIn(0, target - 1)
            sumT[idx] = (sumT[idx] ?: 0L) + t
            sumB[idx] = (sumB[idx] ?: 0L) + b
            cnt[idx] = (cnt[idx] ?: 0) + 1
        }
        return sumT.keys.sorted().map { i -> (sumT[i]!! / cnt[i]!!) to (sumB[i]!! / cnt[i]!!) }
    }

    private fun xOf(i: Int): Float {
        val t0 = pts.first().first.toFloat()
        val t1 = pts.last().first.toFloat().coerceAtLeast(t0 + 1f)
        return (pts[i].first - t0) / (t1 - t0) * width
    }

    private fun yOf(bpm: Long): Float {
        val lo = (pts.minOf { it.second } - 5).coerceAtLeast(30).toFloat()
        val hi = (pts.maxOf { it.second } + 5).coerceAtMost(210).toFloat()
        val span = (hi - lo).coerceAtLeast(1f)
        val usable = height - plotTop()
        return height - ((bpm - lo) / span) * (usable * 0.86f) - usable * 0.07f
    }

    private fun pickAt(x: Float) {
        if (pts.isEmpty()) return
        var best = 0
        var bestD = Float.MAX_VALUE
        for (i in pts.indices) {
            val d = kotlin.math.abs(xOf(i) - x)
            if (d < bestD) { bestD = d; best = i }
        }
        if (best != sel) {
            sel = best
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            onSelect?.invoke(
                "%s · %d bpm".format(
                    SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(pts[best].first * 1000)),
                    pts[best].second
                )
            )
            invalidate()
        }
    }

    private fun fadeOut() {
        val a = android.animation.ValueAnimator.ofFloat(1f, 0f).setDuration(140)
        a.addUpdateListener { fade = it.animatedValue as Float; invalidate() }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                sel = -1; fade = 1f
                onSelect?.invoke(null)
                invalidate()
            }
        })
        a.start()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (pts.isEmpty()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downT = System.currentTimeMillis()
                postDelayed(longPressRunnable, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                // 注意：不要在这里 requestDisallowInterceptTouchEvent —— 会让父容器无法识别长按拖动
                pickAt(e.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> { pickAt(e.x); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                removeCallbacks(longPressRunnable)
                val quick = System.currentTimeMillis() - downT < 260 && kotlin.math.abs(e.x - downX) < 18f
                if (quick) performClick()
                fadeOut()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        if (pts.size < 2) return

        val top = plotTop()
        val path = Path()
        val area = Path()
        var areaTopY = h
        pts.forEachIndexed { i, p ->
            val x = xOf(i)
            val y = yOf(p.second)
            if (i == 0) { path.moveTo(x, y); area.moveTo(x, h); area.lineTo(x, y) }
            else { path.lineTo(x, y); area.lineTo(x, y) }
            if (i == pts.lastIndex) areaTopY = y
        }
        area.lineTo(width.toFloat(), areaTopY)
        area.lineTo(width.toFloat(), h)
        area.close()
        canvas.drawPath(area, fill)
        canvas.drawPath(path, line)

        if (sel in pts.indices && fade > 0.02f) {
            val x = xOf(sel)
            val y = yOf(pts[sel].second)
            cross.alpha = (255 * fade).toInt()
            canvas.drawLine(x, top, x, h, cross)
            canvas.drawCircle(x, y, 9f, ring)
            canvas.drawCircle(x, y, 5f, dot)

            cross.alpha = 255
        }
    }
}
