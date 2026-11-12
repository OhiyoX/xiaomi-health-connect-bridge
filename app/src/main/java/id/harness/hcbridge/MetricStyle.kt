package id.harness.hcbridge

import android.view.View
import android.graphics.Color

/**
 * 指标视觉的唯一来源：卡片容器色 / 图上 accent / 刻度色。
 * 数据页卡片与详情页（含平板右栏）都从这里取，避免两处实现各写一套导致配色不一致。
 */
object MetricStyle {

    data class Spec(
        val container: String,   // 卡片底色角色
        val onContainer: String, // 容器上的文字角色
        val accent: String,      // 图线/点阵/柱子的颜色角色
        val label: String,       // 图表刻度颜色角色
    )

    fun of(metric: String): Spec = when (metric) {
        "steps" -> Spec("colorSurfaceContainerHighest", "colorOnSurface", "colorOnSurface", "colorOnSurfaceVariant")
        "heart_rate" -> Spec("colorPrimaryContainer", "colorOnPrimaryContainer", "colorOnPrimaryContainer", "colorOnPrimaryContainer")
        "spo2" -> Spec("colorSecondaryContainer", "colorOnSecondaryContainer", "colorOnSecondaryContainer", "colorOnSecondaryContainer")
        "sleep" -> Spec("colorTertiaryContainer", "colorOnTertiaryContainer", "colorOnTertiaryContainer", "colorOnTertiaryContainer")
        // 体成分：官方角色 surfaceContainerHigh（与步数的 Highest 区分），文字用 onSurface
        "body" -> Spec("colorSurfaceContainerHigh", "colorOnSurface", "colorOnSurface", "colorOnSurfaceVariant")
        else -> Spec("colorSurfaceContainer", "colorOnSurface", "colorPrimary", "colorOnSurfaceVariant")
    }

    /** 卡片底色 */
    fun container(v: View, metric: String): Int = v.m3(of(metric).container)

    /** 容器上的文字色 */
    fun onContainer(v: View, metric: String): Int = v.m3(of(metric).onContainer)

    /** 图上 accent：优先用 on-container；对比不足 3:1 时按底色亮度取可读色。 */
    fun accent(v: View, metric: String): Int {
        val bg = container(v, metric)
        val candidate = onContainer(v, metric)
        return if (contrast(candidate, bg) >= 3.0) candidate else readableOn(bg)
    }

    /** 刻度色 */
    fun label(v: View, metric: String): Int = accent(v, metric)

    private fun contrast(a: Int, b: Int): Double {
        fun lum(c: Int): Double {
            fun ch(x: Int): Double {
                val s = x / 255.0
                return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
        }
        val la = lum(a); val lb = lum(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
