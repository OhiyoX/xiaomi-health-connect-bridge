package id.harness.hcbridge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import id.harness.hcbridge.logic.SleepMath

/**
 * 睡眠时间轴（actogram）：纵轴钟点 18:00→次日 12:00。
 * - 一天一列；**同一天的多段睡眠共用该列宽度，各自按真实起止时间上下排列**（时段本身不重叠）；
 * - 刻度按 labelSmall/14sp 绘制，颜色取所在容器的 on-color（不透明）；
 * - 点选某天 → 显示当天所有段的起止与总时长。
 */
class SleepBarsView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(ctx, attrs, defStyle) {

    private val sp = resources.displayMetrics.scaledDensity
    private var nights: List<SleepMath.Seg> = emptyList()
    private var accent = Color.parseColor("#B7C7E9")
    private var labelColor = Color.parseColor("#9AA4AB")
    private var tooltipBg = Color.parseColor("#E6E9EC")
    private var tooltipFg = Color.parseColor("#1B1F22")

    private val bar = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val grid = Paint().apply { isAntiAlias = true; strokeWidth = 1.5f; style = Paint.Style.STROKE }
    private val tick = Paint().apply {
        isAntiAlias = true; textAlign = Paint.Align.LEFT; textSize = 14f * sp
        letterSpacing = 0.01f
    }
    private val rect = RectF()
    private var sel = -1
    private val hide = Runnable { sel = -1; onSelect?.invoke(null); invalidate() }

    var onSelect: ((String?) -> Unit)? = null

    private val gutter: Float get() = tick.measureText("00:00") + 10f
    private val winStart = SleepMath.WIN_START

    init {
        isClickable = true
        isFocusable = true
    }

    fun setData(list: List<SleepMath.Seg>, accentColor: Int) {
        nights = list
        accent = accentColor
        bar.color = accent
        grid.color = Color.argb(40, Color.red(accent), Color.green(accent), Color.blue(accent))
        invalidationTick()
        sel = -1
        invalidate()
    }

    fun setLabelColors(label: Int, @Suppress("UNUSED_PARAMETER") tooltipBackground: Int) {
        labelColor = label
        invalidationTick()
        invalidate()
    }

    private fun invalidationTick() {
        tick.color = labelColor
    }

    private fun yOf(minute: Double, h: Float): Float = (SleepMath.yFraction(minute) * h).toFloat()

    private fun clock(minute: Double): String = SleepMath.clock(minute)

    private fun durOf(n: SleepMath.Seg): Double = SleepMath.duration(n)

    /** 按天分组（保持出现顺序） */
    private fun dayGroups(): LinkedHashMap<String, List<SleepMath.Seg>> = SleepMath.byDay(nights)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (nights.isEmpty()) return false
        val groups = dayGroups()
        val slot = (width - gutter) / groups.size
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                removeCallbacks(hide)
                // 注意：不要在这里 requestDisallowInterceptTouchEvent —— 会让父容器无法识别长按拖动
                val i = (((e.x - gutter) / slot).toInt()).coerceIn(0, groups.size - 1)
                if (i != sel) {
                    sel = i
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    val (label, list) = groups.entries.elementAt(i)
                    onSelect?.invoke(SleepMath.tooltip(label, list))
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                postDelayed(hide, 1800)
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (nights.isEmpty()) {
            canvas.drawLine(0f, h * 0.5f, w, h * 0.5f, grid)
            return
        }
        val g = gutter
        // 钟点刻度（20:00 / 04:00 / 12:00，避免 64dp 内互相压住）
        for ((hour, m) in listOf(20 to 20 * 60.0, 4 to 28 * 60.0, 12 to 36 * 60.0)) {
            val y = yOf(m, h)
            canvas.drawLine(g, y, w, y, grid)
            canvas.drawText("%02d:00".format(hour), 0f, (y - tick.textSize * 0.35f).coerceIn(tick.textSize, h - 2f), tick)
        }

        val groups = dayGroups()
        val density = resources.displayMetrics.density
        val slot = (w - g) / groups.size
        val bw = (slot * 0.5f).coerceIn(6f * density, 26f * density)

        groups.entries.forEachIndexed { di, (_, list) ->
            // 一天一列：当天所有段共用同一列宽，各自按真实起止时间上下排列
            val cx = g + di * slot + slot / 2f
            list.forEach { night ->
                val top = yOf(night.start, h)
                val bottom = yOf(night.end, h)
                bar.alpha = when {
                    di == sel -> 255
                    di == groups.size - 1 -> 230
                    else -> 150
                }
                rect.set(cx - bw / 2f, top, cx + bw / 2f, maxOf(bottom, top + 4f * density))
                canvas.drawRoundRect(rect, bw / 2f, bw / 2f, bar)
            }
        }
        bar.alpha = 255
    }
}
