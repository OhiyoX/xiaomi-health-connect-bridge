package id.harness.hcbridge.logic

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 睡眠分期映射 —— 守的是「Google Health 说 hcb 睡眠数据不够」这个回归：
 * 10-25…10-28 五晚都是桥写的、`has_stages=false`，Google 就没法算睡眠质量。
 *
 * 小米 state 的编码不是文档查来的，是拿同一条 payload 的汇总字段对出来的实测值。
 */
class SleepStagesTest {

    private fun item(state: Int, start: Long, end: Long) = JSONObject()
        .put("state", state).put("start_time", start).put("end_time", end)

    private fun arr(vararg o: JSONObject) = JSONArray().apply { o.forEach { put(it) } }

    @Test
    fun `状态码按实测映射到 HC 常量`() {
        assertEquals(SleepStages.HC_DEEP, SleepStages.mapXiaomiState(2))
        assertEquals(SleepStages.HC_LIGHT, SleepStages.mapXiaomiState(3))
        assertEquals(SleepStages.HC_REM, SleepStages.mapXiaomiState(4))
        assertEquals(SleepStages.HC_AWAKE, SleepStages.mapXiaomiState(5))
        // 不认识的不要瞎猜（以前把 1 当清醒就是猜的）
        assertEquals(SleepStages.HC_UNKNOWN, SleepStages.mapXiaomiState(1))
        assertEquals(SleepStages.HC_UNKNOWN, SleepStages.mapXiaomiState(0))
        assertEquals(SleepStages.HC_UNKNOWN, SleepStages.mapXiaomiState(99))
    }

    @Test
    fun `分钟数与小米汇总字段对得上（2026-10-28 那晚实数据）`() {
        // 真实 payload 的 items 片段：深 80 / 浅 315 / REM 106 / 清醒 0，总 501 分钟
        val items = arr(
            item(3, 1793200080, 1793200560),   // 浅 8
            item(2, 1793200560, 1793201220),   // 深 11
            item(3, 1793201220, 1793203380),   // 浅 36
            item(4, 1793203380, 1793204340),   // REM 16
            item(3, 1793204340, 1793204580),   // 浅 4
            item(4, 1793204580, 1793205120),   // REM 9
        )
        val stages = SleepStages.fromXiaomiItems(items, 1793200080, 1793205120)
        val m = SleepStages.minutesByType(stages)
        assertEquals(11L, m[SleepStages.HC_DEEP])
        assertEquals(48L, m[SleepStages.HC_LIGHT])   // 8+36+4
        assertEquals(25L, m[SleepStages.HC_REM])     // 16+9
        assertEquals(84L, stages.sumOf { it.minutes })
    }

    @Test
    fun `按会话边界裁剪（离床前后不该算进来）`() {
        val items = arr(
            item(5, 1000, 1100),      // 会话开始前：清醒，应被裁掉
            item(2, 1100, 1500),      // 深
            item(3, 1500, 1700),      // 浅
        )
        val stages = SleepStages.fromXiaomiItems(items, 1100, 1600)
        assertEquals(1100L, stages.first().start)
        assertEquals(1600L, stages.last().end)          // 尾巴裁到会话结束
        assertEquals(
            mapOf(SleepStages.HC_DEEP to 6L, SleepStages.HC_LIGHT to 1L),
            SleepStages.minutesByType(stages).filterValues { it > 0 },
        )
    }

    @Test
    fun `零长与倒序段被丢掉，相邻同类型合并`() {
        val items = arr(
            item(2, 100, 100),        // 零长
            item(2, 200, 100),        // 倒序
            item(2, 100, 200),
            item(2, 200, 300),        // 与上一段相邻同类型 → 合并
            item(3, 300, 400),
        )
        val stages = SleepStages.fromXiaomiItems(items, 100, 400)
        assertEquals(2, stages.size)
        assertEquals(100L, stages[0].start)
        assertEquals(300L, stages[0].end)
        assertEquals(SleepStages.HC_LIGHT, stages[1].type)
    }

    @Test
    fun `没有 items 或边界非法时返回空（不要抛）`() {
        assertTrue(SleepStages.fromXiaomiItems(null, 0, 100).isEmpty())
        assertTrue(SleepStages.fromXiaomiItems(arr(), 0, 100).isEmpty())
        assertTrue(SleepStages.fromXiaomiItems(arr(item(2, 10, 20)), 100, 100).isEmpty())
    }

    @Test
    fun `分段名字用于日志`() {
        assertEquals("deep", SleepStages.name(SleepStages.HC_DEEP))
        assertEquals("rem", SleepStages.name(SleepStages.HC_REM))
        assertEquals("unknown", SleepStages.name(42))
    }
}
