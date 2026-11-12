package id.harness.hcbridge.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 对比度数学：动态配色下"标签发暗"这类问题必须能算出来，而不是靠肉眼。 */
class ContrastTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val lightPurple = 0xFFEADDFF.toInt()   // M3 tertiaryContainer（浅色主题）
    private val darkSlate = 0xFF2E3A44.toInt()     // 深色主题的卡片底（模拟器上 90% 标签发暗那次）

    @Test
    fun `黑白对比度为 21`() {
        assertEquals(21.0, Contrast.ratio(white, black), 0.01)
        assertEquals(1.0, Contrast.ratio(white, white), 0.001)
    }

    @Test
    fun `对比度与参数顺序无关`() {
        assertEquals(Contrast.ratio(lightPurple, black), Contrast.ratio(black, lightPurple), 0.0001)
    }

    @Test
    fun `亮底取深字 暗底取浅字`() {
        assertEquals(Contrast.DARK_FG, Contrast.readableOn(white))
        assertEquals(Contrast.DARK_FG, Contrast.readableOn(lightPurple))
        assertEquals(Contrast.LIGHT_FG, Contrast.readableOn(black))
        assertEquals(Contrast.LIGHT_FG, Contrast.readableOn(darkSlate))
    }

    @Test
    fun `首选前景对比度不足时按亮度兜底`() {
        // 深字放在深底上（真实故障：onSecondaryContainer 在动态深色下比卡片底还深）
        assertEquals(Contrast.LIGHT_FG, Contrast.readable(darkSlate, darkSlate))
        // 对比度足够时保持首选色
        assertEquals(black, Contrast.readable(black, white))
        // threshold 可调（图形元素用 3:1）；先例：中灰字放在白底上只有 3.4:1
        val mid = 0xFF8A8A8A.toInt()
        assertTrue(Contrast.ratio(mid, white) < Contrast.AA)
        assertEquals(Contrast.DARK_FG, Contrast.readable(mid, white))          // 白底上兕底 → 深字
        assertEquals(mid, Contrast.readable(mid, white, minRatio = 3.0))        // 降到 3:1 则保留首选色
    }

    @Test
    fun `通道提取按 ARGB`() {
        assertEquals(0x1B, Contrast.channel(0xFF1B1F22.toInt(), 16))
        assertEquals(0x1F, Contrast.channel(0xFF1B1F22.toInt(), 8))
        assertEquals(0x22, Contrast.channel(0xFF1B1F22.toInt(), 0))
    }
}
