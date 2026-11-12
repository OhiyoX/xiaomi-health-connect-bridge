package id.harness.hcbridge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import id.harness.hcbridge.logic.GestureArbitration
import id.harness.hcbridge.logic.Spo2Math

private typealias Bucket = Spo2Math.Bucket

/**
 * 血氧 24h 视图。
 *
 * 降采样用 **min–max decimation**（每桶保留最小/最大，文献里对"不能漏尖峰"的信号是首选；
 * 取均值会吃掉短时掉点，LTTB 会平滑掉极值）：
 * - 每桶画一条 min→max 的窄带（浅色）+ 均值点（实色）
 * - 相对 24h 中位基线下降 ≥3% 的桶用 `colorError` 标出（对应临床 ODI3 的"掉点"语义）
 */
class Spo2RangeView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(ctx, attrs, defStyle) {


    private val sp = resources.displayMetrics.scaledDensity
    private var buckets: List<Bucket> = emptyList()
    private var baseline = 95.0
    private var accent = Color.parseColor("#4CDADD")
    private var tooltipBg = Color.parseColor("#E6E9EC")
    private var tooltipFg = Color.parseColor("#1B1F22")
    private var warn = Color.parseColor("#E06C5A")

    private val dot = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val band = Paint().apply { isAntiAlias = true; strokeWidth = 7f; strokeCap = Paint.Cap.ROUND }
    private val line = Paint().apply { isAntiAlias = true; strokeWidth = 2f }
    private val text = Paint().apply {
        isAntiAlias = true; textSize = 14f * sp; textAlign = Paint.Align.RIGHT
        letterSpacing = 0.01f
    }
    private val tip = Paint().apply { isAntiAlias = true; textSize = 14f * sp; letterSpacing = 0.01f }
    private val bubble = Paint().apply { isAntiAlias = true }
    private val box = RectF()
    private var sel = -1
    private val longPressRunnable = Runnable { performLongClick() }
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private val slopPx = GestureArbitration.SLOP_DP * resources.displayMetrics.density
    // 自动消除：也要把卡片上的 tooltip 文字清掉（只清自己的 sel 会留下一个"永不消失的 98%"）
    private val hide = Runnable { sel = -1; onSelect?.invoke(null); invalidate() }

    /** 选中值变化回调：由卡片把文字放到副标题（比压在图上更"高"、也不挡曲线）。 */
    var onSelect: ((String?) -> Unit)? = null

    /** 顶部预留的 tooltip 带高度（气泡固定在这里，不压曲线）。 */
    private val lane: Float get() = 0f
    private fun plotTop() = lane

    init {
        isClickable = true
        isFocusable = true
    }

    fun setData(raw: List<Double>, accentColor: Int) {
        accent = accentColor
        // 中位基线（临床上的 baseline SpO2 用静息/夜间中位更稳）；分桶规则见 logic/Spo2Math
        baseline = Spo2Math.baseline(raw)
        buckets = Spo2Math.bucketize(raw)
        dot.color = accent
        band.color = Color.argb(90, Color.red(accent), Color.green(accent), Color.blue(accent))
        line.color = Color.argb(120, Color.red(accent), Color.green(accent), Color.blue(accent))
        line.pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 8f), 0f)   // 90% 基线是虚线
        text.color = accent                                                           // 90% 标签与点阵同色
        text.alpha = 255
        sel = -1
        invalidate()
    }

    fun setWarnColor(c: Int) {
        warn = c
        invalidate()
    }

    /** 标签色按所在底色取可读色（全不透明）。 */
    fun setLabelColor(c: Int) {
        text.color = c or 0xFF000000.toInt()
        text.alpha = 255
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

    private fun xOf(i: Int): Float =
        if (buckets.size > 1) i * width.toFloat() / (buckets.size - 1) else width / 2f

    private fun yOf(v: Double, h: Float): Float {
        val top = plotTop()
        val usable = h - top
        return (h - Spo2Math.yFraction(v).toFloat() * (usable - 8f) - 4f)
    }

    /** 只把真正低于 90% 的桶标红；ODI3 的计数仍留在副标题里（避免整屏红柱）。 */
    private fun isDip(b: Bucket) = Spo2Math.isDip(b)

    private fun pickAt(x: Float) {
        if (buckets.isEmpty()) return
        var best = 0
        var bestD = Float.MAX_VALUE
        for (i in buckets.indices) {
            val d = kotlin.math.abs(xOf(i) - x)
            if (d < bestD) { bestD = d; best = i }
        }
        if (best != sel) {
            sel = best
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            val b = buckets[best]
            onSelect?.invoke(Spo2Math.label(b))
            invalidate()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (buckets.isEmpty()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; downT = System.currentTimeMillis()
                postDelayed(longPressRunnable, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                removeCallbacks(hide)
                // 注意：不要在这里 requestDisallowInterceptTouchEvent —— 会让父容器无法识别长按拖动
                pickAt(e.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // 只有明显横向拖动才抢手势；竖向留给滚动与长按拖动（见 GestureArbitration 的注释）
                if (GestureArbitration.chartClaimsHorizontal(e.x - downX, e.y - downY, slopPx))
                    parent?.requestDisallowInterceptTouchEvent(true)
                pickAt(e.x)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                removeCallbacks(longPressRunnable)
                val quick = System.currentTimeMillis() - downT < 260 && kotlin.math.abs(e.x - downX) < 18f
                if (quick) performClick()
                postDelayed(hide, 1500)
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val baseY = yOf(90.0, h)
        val labelText = "90%"
        val tw = text.measureText(labelText)
        val gapHalf = tw / 2f + 10f * sp / resources.displayMetrics.scaledDensity * resources.displayMetrics.density
        val cx = w / 2f
        canvas.drawLine(0f, baseY, (cx - gapHalf).coerceAtLeast(0f), baseY, line)
        canvas.drawLine((cx + gapHalf).coerceAtMost(w), baseY, w, baseY, line)
        // 与虚线同一水平线、居中放在线的缺口里
        text.textAlign = Paint.Align.CENTER
        canvas.drawText(labelText, cx, baseY + text.textSize * 0.36f, text)
        if (buckets.isEmpty()) return

        buckets.forEachIndexed { i, b ->
            val x = xOf(i)
            val dip = isDip(b)
            // 桶内跨度 >=1% 才画 min-max 竖条，否则就是单个点（避免"手机没条、模拟器长条"的观感差异）
            if (Spo2Math.hasBand(b)) {
                band.color = if (dip) warn else Color.argb(64, Color.red(accent), Color.green(accent), Color.blue(accent))
                canvas.drawLine(x, yOf(b.max, h), x, yOf(b.min, h), band)
            }
            dot.color = if (dip) warn else accent
            canvas.drawCircle(x, yOf(b.mean, h), 5f, dot)
        }

        if (sel in buckets.indices) {
            val b = buckets[sel]
            val x = xOf(sel)
            val y = yOf(b.mean, h)
            dot.color = if (isDip(b)) warn else accent
            canvas.drawCircle(x, y, 10f, dot)
            canvas.drawCircle(x, y, 10f, dot)
        }
    }
}
