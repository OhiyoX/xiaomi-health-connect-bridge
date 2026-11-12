package id.harness.hcbridge.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 「关于」文案：构建时间 / 安装时间必须按**当地时间**显示（业主明确要求：当地时间）。
 * 守的坑：真机排查时，UTC 字符串和本地时间差 8 小时会让人以为"装的是旧包"。
 */
class AboutInfoTest {

    private val sh = ZoneId.of("Asia/Shanghai")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(sh).toInstant().toEpochMilli()

    @Test
    fun `epoch 按给定时区的当地时间格式化`() {
        val ms = at(2026, 10, 29, 9, 12)
        assertEquals("2026-10-29 09:12", AboutInfo.formatLocal(ms, sh))
        // 同一个时刻在 UTC 下就是 01:12（说明用的确实是时区而不是 UTC）
        assertEquals("2026-10-29 01:12", AboutInfo.formatLocal(ms, ZoneId.of("UTC")))
    }

    @Test
    fun `拿不到时间时显示破折号而不是 1970`() {
        assertEquals("—", AboutInfo.formatLocal(0, sh))
        assertEquals("—", AboutInfo.formatLocal(-1, sh))
    }

    @Test
    fun `时区标签带偏移量`() {
        assertTrue(AboutInfo.zoneLabel(sh).startsWith("Asia/Shanghai"))
        assertTrue(AboutInfo.zoneLabel(sh).contains("UTC+08:00"))
        assertTrue(AboutInfo.zoneLabel(ZoneId.of("UTC")).contains("UTC+00:00"))
    }

    @Test
    fun `首次安装与本次安装相同就只写一行`() {
        val t = at(2026, 10, 29, 9, 15)
        val text = AboutInfo.aboutText("0.5.3", "id.harness.hcbridge", "2026-10-29 09:12", t, t, sh)
        assertTrue(text.contains("安装 2026-10-29 09:15"))
        assertTrue(!text.contains("首次安装"))
    }

    @Test
    fun `升级过就同时显示首次安装和本次安装`() {
        val text = AboutInfo.aboutText(
            "0.5.3", "id.harness.hcbridge", "2026-10-29 09:12",
            at(2026, 10, 24, 0, 10), at(2026, 10, 29, 9, 15), sh,
        )
        assertTrue(text.contains("首次安装 2026-10-24 00:10"))
        assertTrue(text.contains("本次安装 2026-10-29 09:15"))
        assertTrue(text.contains("构建 2026-10-29 09:12"))
        assertTrue(text.contains("Asia/Shanghai"))
    }

    @Test
    fun `构建时间为空时不显示空白`() {
        val t = at(2026, 10, 29, 9, 15)
        val text = AboutInfo.aboutText("0.5.3", "x", "", t, t, sh)
        assertTrue(text.contains("构建 —"))
    }
}
