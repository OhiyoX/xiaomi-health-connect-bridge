package id.harness.hcbridge.logic

import id.harness.hcbridge.logic.SleepMath.Seg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 睡眠时间轴：跨零点时长、同源去重、按天分组、纵轴映射、tooltip 文案。
 * 数字都取自 2026-10-18 实机数据（当晚 22:28–07:22 + 01:30–07:31 重复写入那次）。
 */
class SleepMathTest {

    /** 构造 Seg：与 Snapshot 的归一化一致（end <= start 视为跨天，+1440）。 */
    private fun seg(label: String, startH: Int, startM: Int, endH: Int, endM: Int, wrap: Boolean = false): Seg {
        val s = (startH * 60 + startM).toDouble()
        var e = (endH * 60 + endM).toDouble()
        if (wrap || e <= s) e += 1440
        return Seg(label, s, e)
    }

    @Test
    fun `跨零点时长补一天`() {
        // 22:28 → 07:22 = 8h54m
        assertEquals(534.0, SleepMath.duration(1348.0, 442.0), 0.001)
        // 不跨零点就是差值
        assertEquals(70.0, SleepMath.duration(1350.0, 1420.0), 0.001)
        // end == start 视为整天（防御性：0 时长会让图表退化成一个点）
        assertEquals(1440.0, SleepMath.duration(600.0, 600.0), 0.001)
    }

    @Test
    fun `重叠超过一半的同源重复只留更长那段`() {
        val night = seg("10-17", 22, 28, 7, 22, wrap = true)   // 1348..1882 = 534m
        val other = seg("10-17", 22, 0, 7, 0, wrap = true)     // 1320..1860 = 540m（更长）
        assertEquals(512.0, minOf(night.end, other.end) - maxOf(night.start, other.start), 0.001)
        val kept = SleepMath.dedupe(listOf(night, other))
        assertEquals("同一段被两个来源写了两遍 → 只留一段", 1, kept.size)
        assertEquals(540.0, SleepMath.duration(kept[0]), 0.001)
    }

    @Test
    fun `真正的多段睡眠都保留 并按时间排序`() {
        val night = seg("10-17", 22, 28, 7, 22, wrap = true)   // 534m
        val nap = seg("10-17", 13, 30, 14, 30)                 // 60m，与夜间不重叠
        val kept = SleepMath.dedupe(listOf(night, nap))
        assertEquals(2, kept.size)
        assertEquals(810.0, kept[0].start, 0.001)               // 午睡在前（start 小）
        assertEquals(594.0, SleepMath.total(kept), 0.001)
    }

    @Test
    fun `同一天多段共用一列 分组保持出现顺序`() {
        val segs = listOf(
            seg("10-18", 22, 0, 23, 0),
            seg("10-17", 22, 28, 7, 22, wrap = true),
            seg("10-18", 13, 0, 14, 0)
        )
        val days = SleepMath.byDay(SleepMath.dedupe(segs))
        assertEquals(listOf("10-18", "10-17"), days.keys.toList())
        assertEquals(2, days["10-18"]!!.size)
        assertEquals(1, days["10-17"]!!.size)
    }

    @Test
    fun `纵轴 18点到次日12点`() {
        assertEquals(0.0, SleepMath.yFraction(18 * 60.0), 0.001)          // 18:00 顶部
        assertEquals(1.0, SleepMath.yFraction(12 * 60.0), 0.001)          // 次日 12:00 底部（12:00 小于窗口起点 → 补一天）
        assertEquals(0.111, SleepMath.yFraction(20 * 60.0), 0.001)        // 20:00
        assertEquals(0.555, SleepMath.yFraction(4 * 60.0), 0.001)         // 04:00
        assertTrue(SleepMath.yFraction(4 * 60.0) > SleepMath.yFraction(20 * 60.0))
    }

    @Test
    fun `只保留最近七天`() {
        val segs = (10..17).map { seg("10-$it", 22, 0, 7, 0) }
        val week = SleepMath.recent(segs, days = 7)
        assertEquals(7, week.size)
        assertFalse(week.any { it.label == "10-10" })
        assertTrue(week.any { it.label == "10-17" })
    }

    @Test
    fun `tooltip 文案 单段给起止 多段给合计`() {
        assertEquals("10-17 22:28–07:22", SleepMath.tooltip("10-17", listOf(seg("10-17", 22, 28, 7, 22))))
        val two = listOf(seg("10-17", 22, 28, 7, 22), seg("10-17", 1, 30, 7, 31))
        assertEquals("10-17 2 段 · 共 14h55m", SleepMath.tooltip("10-17", two))
    }

    @Test
    fun `时长文案`() {
        assertEquals("9h16m", SleepMath.hhmm(556.0))
        assertEquals("58m", SleepMath.hhmm(58.0))
        assertEquals("0m", SleepMath.hhmm(-5.0))
    }
}
