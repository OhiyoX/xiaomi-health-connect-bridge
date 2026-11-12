package id.harness.hcbridge.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 睡眠分期汇总：常量口径、效率、缺失处理、堆叠条比例。 */
class StageMathTest {

    @Test
    fun stageConstantsMatchHealthConnect() {
        // 这组数字是 2026-10-21 实测对照小米原始数据定下来的，改错会让"深睡/浅睡/REM"整体错位
        assertEquals(4, StageMath.LIGHT)
        assertEquals(5, StageMath.DEEP)
        assertEquals(6, StageMath.REM)
        assertEquals(1, StageMath.AWAKE)
        assertEquals("深睡", StageMath.STAGE_NAMES[StageMath.DEEP])
        assertEquals("浅睡", StageMath.STAGE_NAMES[StageMath.LIGHT])
        assertEquals("REM", StageMath.STAGE_NAMES[StageMath.REM])
    }

    @Test
    fun measuredNight1021() {
        val s = StageMath.summarize(mapOf(5 to 33L, 4 to 141L, 6 to 16L), totalMinutes = 190L)
        assertEquals(190, s.totalMinutes)
        assertEquals(190, s.asleepMinutes)
        assertEquals(100.0, s.efficiency, 0.01)
        assertTrue(s.hasData)
        assertEquals(listOf(5, 4, 6), s.rows.map { it.stage })
        assertEquals("深睡 33m（17%）", StageMath.line(s.rows[0]))
    }

    @Test
    fun measuredNight1020AwakeCountsTowardEfficiency() {
        val s = StageMath.summarize(mapOf(5 to 48L, 4 to 237L, 6 to 43L, 1 to 31L), totalMinutes = 359L)
        assertEquals(359, s.totalMinutes)
        assertEquals(328, s.asleepMinutes)
        assertEquals(91.4, s.efficiency, 0.1)
        assertEquals(listOf(5, 4, 6, 1), s.rows.map { it.stage })
    }

    @Test
    fun outOfBedAlsoCountsAsAwake() {
        val s = StageMath.summarize(mapOf(5 to 60L, 3 to 20L), totalMinutes = 80L)
        assertEquals(60, s.asleepMinutes)
        assertEquals(75.0, s.efficiency, 0.01)
    }

    @Test
    fun totalFallsBackToStageSum() {
        val s = StageMath.summarize(mapOf(5 to 30L, 4 to 120L))
        assertEquals(150, s.totalMinutes)
    }

    @Test
    fun noStagesMeansNoData() {
        val s = StageMath.summarize(emptyMap())
        assertFalse(s.hasData)
        assertTrue(s.rows.isEmpty())
        assertEquals(0.0, s.efficiency, 0.0)
    }

    @Test
    fun unknownStageIsAppendedNotDropped() {
        val s = StageMath.summarize(mapOf(5 to 30L, 0 to 10L), totalMinutes = 40L)
        assertEquals(listOf(5, 0), s.rows.map { it.stage })
        assertEquals("未知 10m（25%）", StageMath.line(s.rows[1]))
    }

    @Test
    fun fractionsSumToOneInDisplayOrder() {
        val f = StageMath.fractions(mapOf(5 to 33L, 4 to 141L, 6 to 16L))
        assertEquals(listOf(5, 4, 6), f.map { it.first })
        assertEquals(1.0f, f.map { it.second }.sum(), 1e-6f)
        assertTrue(f.first { it.first == 5 }.second < 0.2f)
    }

    @Test
    fun fractionsEmptyWithoutData() {
        assertTrue(StageMath.fractions(emptyMap()).isEmpty())
    }

    @Test
    fun nightLineShowsDashInsteadOfZero() {
        assertEquals("呼吸率 17/min · HRV 33ms", StageMath.nightLine(17.0, 33.0))
        assertEquals("呼吸率 17/min · HRV —", StageMath.nightLine(17.0, null))
        assertEquals("呼吸率 — · HRV —", StageMath.nightLine(null, null))
    }
}
