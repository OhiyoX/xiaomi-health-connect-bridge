package id.harness.hcbridge.logic

import kotlin.math.pow

/**
 * WCAG 相对亮度 / 对比度 —— **纯函数**（不碰 `android.graphics`，JVM 单测可直跑）。
 *
 * 为什么单独抽出来：动态配色下角色色由系统决定，卡片底色与文字/tooltip 的对比度必须能算、
 * 能被测试；过去这段逻辑藏在 View 里、只在实机上"看着不对"才发现（血氧 90% 标签发暗那次）。
 */
object Contrast {

    /** 深色前景（亮底上用）与浅色前景（暗底上用）——全 app 唯一两个取值。 */
    const val DARK_FG = 0xFF1B1F22.toInt()
    const val LIGHT_FG = 0xFFF2F5F7.toInt()

    /** 亮度阈值：底色相对亮度高于它就用深字。 */
    const val LIGHT_BG_LUMINANCE = 0.4

    /** 正文/标签最低对比度（WCAG AA）。 */
    const val AA = 4.5

    /** 图形元素（折线/点阵/图标）最低对比度。 */
    const val GRAPHIC = 3.0

    fun channel(color: Int, shift: Int): Int = (color shr shift) and 0xFF

    fun linearize(v8: Int): Double {
        val v = v8 / 255.0
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    fun luminance(color: Int): Double =
        0.2126 * linearize(channel(color, 16)) +
            0.7152 * linearize(channel(color, 8)) +
            0.0722 * linearize(channel(color, 0))

    /** 对比度 1.0（同色）… 21.0（黑对白）。 */
    fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** 按底色亮度选可读前景；角色色解析失败时也靠它兜底（不会出现同色不可见）。 */
    fun readableOn(bg: Int): Int =
        if (luminance(bg) > LIGHT_BG_LUMINANCE) DARK_FG else LIGHT_FG

    /** 首选前景满足最低对比度就用它，否则按亮度兜底。 */
    fun readable(preferred: Int, bg: Int, minRatio: Double = AA): Int =
        if (ratio(preferred, bg) >= minRatio) preferred else readableOn(bg)
}
