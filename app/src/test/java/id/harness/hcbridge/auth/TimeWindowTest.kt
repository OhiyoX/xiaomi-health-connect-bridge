package id.harness.hcbridge.auth

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间窗（spec §8.3）：`null` = 不限时；跨午夜、`from`/`until` 含端点、`days` 用 ISO 星期；
 * 非法 JSON 抛异常（由 [ToolGate] 折成 `window_corrupt` 拒绝）。
 */
class TimeWindowTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun at(z: ZoneId, local: String): Long =
        LocalDateTime.parse(local).atZone(z).toInstant().toEpochMilli()

    private fun active(json: String?, local: String, z: ZoneId = zone): Boolean =
        TimeWindow.active(json, at(z, local), z)

    @Test
    fun nullWindowIsAlwaysActive() {
        assertTrue(active(null, "2026-11-03T03:00"))
    }

    @Test
    fun blankWindowIsCorruptNotUnrestricted() {
        for (blank in listOf("", "   ", "\n")) {
            assertThrows(IllegalArgumentException::class.java) { TimeWindow.active(blank, 0L, zone) }
            assertThrows(IllegalArgumentException::class.java) { TimeWindow.canonical(blank) }
            assertEquals("时间窗损坏", TimeWindow.describe(blank))
        }
    }

    @Test
    fun unknownKeysAreRejected() {
        // `day` 拼错/多余的键：当成"忽略"就等于放宽成"每天/全天"
        assertThrows(IllegalArgumentException::class.java) { active("""{"day":[2]}""", "2026-11-03T12:00") }
        assertThrows(IllegalArgumentException::class.java) {
            TimeWindow.canonical("""{"days":[2],"extra":1}""")
        }
    }

    @Test
    fun fromAfterUntilIsCorrupt() {
        assertThrows(IllegalArgumentException::class.java) {
            TimeWindow.canonical("""{"from":"2026-11-05","until":"2026-11-01"}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TimeWindow.canonical("""{"from":"2026-11-05T00:00:00+08:00","until":"2026-11-01T00:00:00+08:00"}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TimeWindow.canonical("""{"from":"2026-11-01T00:00:00+08:00","until":"2026-11-30"}""")
        }
        // 同一天是合法的：那一整天
        assertTrue(active("""{"from":"2026-11-03","until":"2026-11-03"}""", "2026-11-03T12:00"))
    }

    @Test
    fun trailingGarbageIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { active("""{"start":"22:00"} x""", "2026-11-03T23:00") }
        assertThrows(IllegalArgumentException::class.java) { TimeWindow.canonical("""{}{}""") }
    }

    @Test
    fun instantsRenderInTheWindowZoneNotUtc() {
        val win = """{"from":"2026-11-01T00:00:00+08:00"}"""
        assertEquals("""{"from":"2026-11-01T00:00+08:00"}""", TimeWindow.canonical(win, zone))
        assertTrue(TimeWindow.describe(win, zone).contains("+08:00"))
        // 窗里自带 tz 时以它为准（这里是 UTC）
        val utcWin = """{"tz":"UTC","from":"2026-11-01T00:00:00+08:00"}"""
        assertEquals("""{"tz":"UTC","from":"2026-10-31T16:00Z"}""", TimeWindow.canonical(utcWin, zone))
    }

    @Test
    fun windowWrapsMidnight() {
        val w = """{"start":"22:00","end":"06:00"}"""
        assertTrue(active(w, "2026-11-03T23:30"))
        assertTrue(active(w, "2026-11-04T05:30"))
        assertTrue(active(w, "2026-11-03T22:00"))
        assertTrue(active(w, "2026-11-03T00:00"))
        assertFalse(active(w, "2026-11-03T06:00"))
        assertFalse(active(w, "2026-11-03T12:00"))
        assertFalse(active(w, "2026-11-03T21:59"))
    }

    @Test
    fun sameDayWindowIsHalfOpen() {
        val w = """{"start":"09:00","end":"22:00"}"""
        assertTrue(active(w, "2026-11-03T09:00"))
        assertTrue(active(w, "2026-11-03T21:59"))
        assertFalse(active(w, "2026-11-03T22:00"))
        assertFalse(active(w, "2026-11-03T08:59"))
    }

    @Test
    fun missingStartOrEndMeansOpenEnded() {
        assertTrue(active("""{"end":"22:00"}""", "2026-11-03T00:00"))
        assertFalse(active("""{"end":"22:00"}""", "2026-11-03T22:00"))
        assertTrue(active("""{"start":"22:00"}""", "2026-11-03T23:59"))
        assertFalse(active("""{"start":"22:00"}""", "2026-11-03T21:59"))
    }

    @Test
    fun emptyObjectIsAllDay() {
        assertTrue(active("{}", "2026-11-03T00:00"))
        assertTrue(active("{}", "2026-11-03T23:59"))
    }

    @Test
    fun startEqualsEndAtMidnightMeansAllDay() {
        assertTrue(active("""{"start":"00:00","end":"00:00"}""", "2026-11-03T00:00"))
        assertTrue(active("""{"start":"00:00","end":"00:00"}""", "2026-11-03T13:37"))
    }

    @Test
    fun zeroLengthNonMidnightWindowIsNeverActive() {
        val w = """{"start":"09:00","end":"09:00"}"""
        assertFalse(active(w, "2026-11-03T09:00"))
        assertFalse(active(w, "2026-11-03T12:00"))
    }

    @Test
    fun untilIncludesThatDayAndDaysUseIso() {
        assertTrue(TimeWindow.active("""{"until":"2026-11-03"}""", at(zone, "2026-11-03T23:59"), zone))
        assertTrue(TimeWindow.active("""{"until":"2026-11-03"}""", at(zone, "2026-11-03T00:00"), zone))
        assertFalse(TimeWindow.active("""{"until":"2026-11-02"}""", at(zone, "2026-11-03T00:01"), zone))
        assertTrue(TimeWindow.active("""{"days":[2]}""", at(zone, "2026-11-03T12:00"), zone))
        assertFalse(TimeWindow.active("""{"days":[1]}""", at(zone, "2026-11-03T12:00"), zone))
    }

    @Test
    fun fromIncludesThatDay() {
        assertTrue(TimeWindow.active("""{"from":"2026-11-03"}""", at(zone, "2026-11-03T00:00"), zone))
        assertFalse(TimeWindow.active("""{"from":"2026-11-03"}""", at(zone, "2026-11-02T23:59"), zone))
    }

    @Test
    fun absoluteInstantsCompareByInstant() {
        assertTrue(TimeWindow.active("""{"from":"2026-11-01T00:00:00+08:00"}""",
            at(zone, "2026-11-03T12:00"), zone))
        assertFalse(TimeWindow.active("""{"from":"2026-11-05T00:00:00+08:00"}""",
            at(zone, "2026-11-03T12:00"), zone))
        assertTrue(TimeWindow.active("""{"until":"2026-11-03T23:00:00+08:00"}""",
            at(zone, "2026-11-03T22:59"), zone))
        assertFalse(TimeWindow.active("""{"until":"2026-11-03T23:00:00+08:00"}""",
            at(zone, "2026-11-03T23:00"), zone))
    }

    @Test
    fun tzInsideTheWindowOverridesTheCallersZone() {
        // 上海 17:30 = UTC 09:30：窗写的是 UTC 09:00–10:00 ⇒ 只有认窗内 tz 才放行
        val w = """{"tz":"UTC","start":"09:00","end":"10:00"}"""
        assertTrue(TimeWindow.active(w, at(zone, "2026-11-03T17:30"), zone))
        assertFalse(TimeWindow.active("""{"start":"09:00","end":"10:00"}""",
            at(zone, "2026-11-03T17:30"), zone))
        // 同一个瞬间，若调用方本身按 UTC 评估（设备在 UTC）⇒ 无 tz 的窗也命中
        val utc = ZoneId.of("UTC")
        assertTrue(TimeWindow.active("""{"start":"09:00","end":"10:00"}""",
            at(utc, "2026-11-03T09:30"), utc))
    }

    @Test
    fun emptyDaysArrayIsNeverActive() {
        assertFalse(active("""{"days":[]}""", "2026-11-03T12:00"))
    }

    @Test
    fun corruptWindowThrows() {
        for (bad in listOf(
            "{not json",
            """{"days":"x"}""",
            """{"days":[0]}""",
            """{"days":[8]}""",
            """{"days":[1.5]}""",
            """{"start":"25:00"}""",
            """{"end":"7:60"}""",
            """{"start":900}""",
            """{"from":"2026-13-01"}""",
            """{"until":"tomorrow"}""",
            """{"tz":"Mars/Olympus"}""",
            "[]"
        )) {
            assertThrows("应拒绝: $bad", IllegalArgumentException::class.java) {
                TimeWindow.active(bad, 0L, zone)
            }
            assertThrows("应拒绝(canonical): $bad", IllegalArgumentException::class.java) {
                TimeWindow.canonical(bad)
            }
        }
    }

    @Test
    fun canonicalNormalizesAndIsIdempotent() {
        assertNull(TimeWindow.canonical(null))
        val c = TimeWindow.canonical("""{"end":"6:00","start":"22:00","days":[5,1],"tz":"Asia/Shanghai"}""")
        assertEquals("""{"tz":"Asia/Shanghai","days":[1,5],"start":"22:00","end":"06:00"}""", c)
        assertEquals(c, TimeWindow.canonical(c))
        assertEquals("{}", TimeWindow.canonical("""{"start":"00:00","end":"00:00"}"""))
    }

    @Test
    fun describeIsHumanReadableAndNeverThrows() {
        assertEquals("不限", TimeWindow.describe(null))
        assertEquals("时间窗损坏", TimeWindow.describe("{not json"))
        assertEquals("22:00–06:00", TimeWindow.describe("""{"start":"22:00","end":"06:00"}"""))
        assertEquals("全天", TimeWindow.describe("{}"))
        val withDays = TimeWindow.describe("""{"days":[1,2],"start":"09:00","end":"22:00"}""")
        assertTrue(withDays, withDays.contains("周一"))
        assertTrue(withDays, withDays.contains("09:00–22:00"))
    }
}
