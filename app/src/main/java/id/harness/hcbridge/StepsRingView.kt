package id.harness.hcbridge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** 步数专属可视化：目标圆环 + 中心大数字（步数是"配额型"指标，进度比趋势更有意义）。 */
class StepsRingView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(ctx, attrs, defStyle) {

    private var steps = 0L
    private var goal = 8000L
    private var accent = Color.parseColor("#4CDADD")
    private val track = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 14f }
    private val arc = Paint().apply {
        isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 14f
        strokeCap = Paint.Cap.ROUND
    }
    private val big = Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER }
    private val small = Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER }
    private val box = RectF()

    fun setData(stepsToday: Long, goalSteps: Long, accentColor: Int) {
        steps = stepsToday
        goal = goalSteps.coerceAtLeast(1)
        accent = accentColor
        arc.color = accentColor
        track.color = Color.argb(48, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        small.color = Color.argb(150, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        big.color = accentColor
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = minOf(width, height).toFloat()
        val pad = 10f
        box.set(pad, pad, d - pad, d - pad)
        val cx = d / 2
        canvas.drawArc(box, 0f, 360f, false, track)
        val sweep = (steps.toFloat() / goal).coerceIn(0f, 1f) * 360f
        canvas.drawArc(box, -90f, sweep, false, arc)

        big.textSize = d * 0.26f
        small.textSize = d * 0.11f
        canvas.drawText(if (steps > 0) "%,d".format(steps) else "—", cx, cx + big.textSize * 0.28f, big)
        canvas.drawText("/ ${"%,d".format(goal)}", cx, cx + big.textSize * 0.28f + small.textSize * 1.6f, small)
    }
}
