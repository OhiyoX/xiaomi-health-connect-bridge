package id.harness.hcbridge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import id.harness.hcbridge.logic.StageMath

/**
 * 睡眠分期堆叠条：深睡 → 浅睡 → REM → 清醒，宽度按分钟占比。
 *
 * 颜色只用睡眠容器角色的**透明度层次**（on-tertiary 的 100/70/45/25%），不自造 RGB；
 * 深睡最不透明，符合"越好越显眼"的直觉。onDraw 内不分配对象。
 */
class StageBarView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(ctx, attrs, defStyle) {

    private var parts: List<Pair<Int, Float>> = emptyList()
    private var baseColor = Color.parseColor("#4CDADD")
    private var trackColor = Color.argb(40, 120, 120, 120)
    private val paint = Paint().apply { isAntiAlias = true }
    private val box = RectF()

    private val alphas = mapOf(
        StageMath.DEEP to 255, StageMath.LIGHT to 180, StageMath.REM to 120, StageMath.AWAKE to 64
    )

    fun setData(minutesByStage: Map<Int, Long>, color: Int) {
        parts = StageMath.fractions(minutesByStage)
        baseColor = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = height / 2f
        box.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.style = Paint.Style.FILL
        paint.color = trackColor
        canvas.drawRoundRect(box, r, r, paint)

        if (parts.isEmpty()) return
        var x = 0f
        for ((stage, frac) in parts) {
            val w = width * frac
            val a = alphas[stage] ?: 150
            paint.color = Color.argb(a, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
            box.set(x, 0f, x + w, height.toFloat())
            // 首尾按圆角画，中间块方角（避免接缝处出现药丸缝隙）
            val rr = minOf(r, w / 2f)
            canvas.drawRoundRect(box, rr, rr, paint)
            x += w
        }
    }
}
