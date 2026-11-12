package id.harness.hcbridge.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 体成分趋势的几何与文案：真实时间比例、同天去重、吸附与留边。 */
class BodyMathTest {

    private fun daysFromCivil(y0: Int, m0: Int, d0: Int): Long {
        val y = if (m0 <= 2) y0 - 1 else y0
        val era = Math.floorDiv(y.toLong(), 400L)
        val yoe = y - era * 400
        val mp = if (m0 > 2) m0 - 3 else m0 + 9
        val doy = (153 * mp + 2) / 5 + d0 - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    private fun p(day: String, kg: Double, fat: Double? = null, bmi: Double? = null): BodyMath.Point {
        val parts = day.split("-").map { it.toInt() }
        val days = daysFromCivil(parts[0], parts[1], parts[2])
        return BodyMath.Point(days * 86400 - 8 * 3600, kg, fat, bmi)
    }

    @Test
    fun dayMappingUsesUtc8() {
        val pt = p("2026-09-06", 64.9)
        assertEquals("2026-09-06", pt.day)
        // 本地 00:10 与 23:50 必须落在同一天（pt.t 是本地 00:00）
        val early = BodyMath.Point(pt.t + 10 * 60, 65.0)
        val late = BodyMath.Point(pt.t + 23 * 3600 + 50 * 60, 65.4)
        assertEquals("2026-09-06", early.day)
        assertEquals("2026-09-06", late.day)
    }

    @Test
    fun sameDayKeepsLastMeasurement() {
        val base = p("2025-03-26", 64.8)
        val list = listOf(
            BodyMath.Point(base.t + 60, 64.8),
            base,
            p("2025-03-25", 65.6)
        )
        val out = BodyMath.dedupeByDay(list)
        assertEquals(2, out.size)
        assertEquals("2025-03-25", out[0].day)
        assertEquals(64.8, out[1].kg, 1e-9)
    }

    @Test
    fun limitKeepsMostRecent() {
        val list = (1..10).map { p(String.format("2026-01-%02d", it), 60.0 + it) }
        val out = BodyMath.dedupeByDay(list, limit = 3)
        assertEquals(3, out.size)
        assertEquals(70.0, out.last().kg, 1e-9)
    }

    @Test
    fun xAxisIsRealTimeProportional() {
        val a = p("2026-01-01", 65.0)
        val b = p("2026-01-11", 64.0)
        val c = p("2026-04-11", 63.0)
        val list = listOf(a, b, c)
        val bounds = BodyMath.bounds(list)!!
        val xb = BodyMath.normX(b.t, bounds)
        // 等距下标会是 0.5；真实比例约 10/100 = 0.1
        assertTrue("xb=" + xb + " 应接近 0.1", xb in 0.05..0.15)
    }

    @Test
    fun yAxisHasPadding() {
        val list = listOf(p("2026-01-01", 63.6), p("2026-02-01", 67.3))
        val bounds = BodyMath.bounds(list)!!
        assertTrue(bounds.minKg < 63.6)
        assertTrue(bounds.maxKg > 67.3)
        assertEquals(0.0, BodyMath.normY(67.3, bounds), 0.2)
        assertTrue(BodyMath.normY(63.6, bounds) > 0.8)
    }

    @Test
    fun emptySeriesHasNoBounds() {
        assertNull(BodyMath.bounds(emptyList()))
    }

    @Test
    fun nearestSnapsAndRespectsMaxDistance() {
        val list = listOf(p("2026-01-01", 65.0), p("2026-02-01", 66.0))
        val bounds = BodyMath.bounds(list)!!
        val hit = BodyMath.nearest(list, bounds, 0.95, maxDist = 0.12)
        assertEquals(66.0, hit!!.kg, 1e-9)
        assertNull(BodyMath.nearest(list, bounds, 0.5, maxDist = 0.12))
    }

    @Test
    fun summaryAndTooltipSkipMissingFields() {
        val full = p("2026-09-06", 64.9, 14.3, 20.7)
        assertEquals("BMI 20.7 · 体脂 14.3% · 09-06", BodyMath.summaryLine(full))
        val onlyKg = p("2026-09-06", 64.9)
        assertEquals("09-06", BodyMath.summaryLine(onlyKg))
        assertEquals("09-06 · 64.9 kg", BodyMath.tooltip(onlyKg))
        assertEquals("09-06 · 64.9 kg · 20.7", BodyMath.tooltip(full))
    }

    @Test
    fun changeNeedsTwoPoints() {
        assertNull(BodyMath.change(listOf(p("2026-01-01", 65.0))))
        val d = BodyMath.change(listOf(p("2026-01-01", 67.3), p("2026-09-06", 64.9)))
        assertEquals(-2.4, d!!, 1e-9)
    }
}
