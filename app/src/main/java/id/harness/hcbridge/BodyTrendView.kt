package id.harness.hcbridge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import id.harness.hcbridge.logic.BodyMath

/**
 * 体成分专属可视化：**稀疏时间序列**的点 + 连线（5 年约 29 次称重，不是连续曲线）。
 *
 * 与其它图表的差别（见 docs/UI.md §3 指标差异化）：
 * - x 轴按真实时间比例（BodyMath.normX），不按等距下标——否则相隔半年的两次称重会挤在一起；
 * - 只标 min/max 刻度，不画网格；点半径 4.5f、选中 9f；
 * - 触摸吸附最近点（BodyMath.nearest），回调 onSelect 交给卡片标题行的 tooltip；
 * - onDraw 内不分配对象（Path/RectF/Paint 复用）。
 */
class BodyTrendView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(ctx, attrs, defStyle) {

    private var points: List<BodyMath.Point> = emptyList()
    private var bounds: BodyMath.Bounds? = null
    private var selected: BodyMath.Point? = null

    private var accent = Color.parseColor("#4CDADD")
    private var labelColor = Color.GRAY

    private val line = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val dot = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val ring = Paint().apply {
        isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val label = Paint().apply { isAntiAlias = true; textAlign = Paint.Align.LEFT }
    private val labelRight = Paint().apply { isAntiAlias = true; textAlign = Paint.Align.RIGHT }
    private val dash = Paint().apply {
        isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 1f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 6f), 0f)
    }
    private val path = Path()

    var onSelect: ((String?) -> Unit)? = null

    fun setData(list: List<BodyMath.Point>, accentColor: Int) {
        points = list
        bounds = BodyMath.bounds(list)
        accent = accentColor
        line.color = accentColor
        dot.color = accentColor
        ring.color = accentColor
        labelColor = Color.argb(160, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        label.color = labelColor
        labelRight.color = labelColor
        dash.color = Color.argb(60, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        selected = null
        invalidate()
    }

    private fun px(fx: Double): Float = (paddingLeft + fx.toFloat() * innerW).toFloat()
    private fun py(fy: Double): Float = (paddingTop + fy.toFloat() * innerH).toFloat()

    private val innerW: Float get() = (width - paddingLeft - paddingRight).toFloat()
    private val innerH: Float get() = (height - paddingTop - paddingBottom).toFloat()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = bounds ?: return
        if (points.isEmpty() || innerW <= 0f || innerH <= 0f) return

        // y 轴 min/max 刻度（labelSmall 量级，14sp 由布局决定）
        label.textSize = 26f
        labelRight.textSize = 26f
        canvas.drawText("%.1f".format(b.maxKg - (b.maxKg - b.minKg) * 0.05), paddingLeft + 2f,
            paddingTop + 26f, label)
        canvas.drawText("%.1f".format(b.minKg + (b.maxKg - b.minKg) * 0.05), paddingLeft + 2f,
            paddingTop + innerH - 6f, label)

        // 折线
        path.reset()
        var first = true
        for (p in points) {
            val x = px(BodyMath.normX(p.t, b))
            val y = py(BodyMath.normY(p.kg, b))
            if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
        }
        canvas.drawPath(path, line)

        // 点
        for (p in points) {
            val x = px(BodyMath.normX(p.t, b))
            val y = py(BodyMath.normY(p.kg, b))
            canvas.drawCircle(x, y, 4.5f, dot)
        }

        // 选中：竖向虚线 + 放大圆环
        selected?.let { p ->
            val x = px(BodyMath.normX(p.t, b))
            val y = py(BodyMath.normY(p.kg, b))
            canvas.drawLine(x, paddingTop.toFloat(), x, paddingTop + innerH, dash)
            canvas.drawCircle(x, y, 9f, ring)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val b = bounds ?: return false
        if (points.isEmpty() || innerW <= 0f) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val fx = ((e.x - paddingLeft) / innerW).toDouble()
                val hit = BodyMath.nearest(points, b, fx)
                if (hit != selected) {
                    selected = hit
                    onSelect?.invoke(hit?.let { BodyMath.tooltip(it) })
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                postDelayed({
                    selected = null
                    onSelect?.invoke(null)
                    invalidate()
                }, 1500)
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}
