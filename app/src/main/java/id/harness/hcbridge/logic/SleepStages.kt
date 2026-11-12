package id.harness.hcbridge.logic

import org.json.JSONArray

/**
 * 小米云端 sleep payload 的**分期**（`items[]`）→ Health Connect 的 `SleepSessionRecord.Stage`。
 *
 * 为什么要它：Google 的 Health/Health Connect 会按「睡眠分期」评估睡眠质量，
 * 只有起止时间、没有分期的记录会被判"睡眠数据不足"（2026-10-29 业主看到的提示就是这个）。
 * 小米运动健康自己写 HC 时带分期，而桥从云端搬的那几条以前只写起止 ——
 * 实测 10-25…10-28 五晚全是 `id.harness.hcbridge` 且 `has_stages=false`。
 *
 * 小米的 `state` 编码是实测出来的（用同一条 payload 里的汇总字段对账：
 * `sleep_deep_duration` / `sleep_light_duration` / `sleep_rem_duration` / `sleep_awake_duration`）：
 *
 * | 小米 state | 含义 | 分钟数与汇总字段 |
 * | --- | --- | --- |
 * | 2 | 深睡 | == `sleep_deep_duration` |
 * | 3 | 浅睡 | == `sleep_light_duration` |
 * | 4 | REM | == `sleep_rem_duration` |
 * | 5 | 清醒 | == `sleep_awake_duration` |
 *
 * 其它值**不猜**：映射成 HC 的 0（UNKNOWN），HC 和 Google 都能接受。
 */
object SleepStages {

    // Health Connect 的 SleepSessionRecord stage 常量（API 34+）
    const val HC_UNKNOWN = 0
    const val HC_AWAKE = 1
    const val HC_SLEEPING = 2
    const val HC_OUT_OF_BED = 3
    const val HC_LIGHT = 4
    const val HC_DEEP = 5
    const val HC_REM = 6

    data class Stage(val start: Long, val end: Long, val type: Int) {
        val minutes: Long get() = (end - start) / 60
    }

    fun mapXiaomiState(state: Int): Int = when (state) {
        2 -> HC_DEEP
        3 -> HC_LIGHT
        4 -> HC_REM
        5 -> HC_AWAKE
        else -> HC_UNKNOWN
    }

    /**
     * 从小米 payload 的 `items` 解析分期。
     *
     * - 按会话边界 [sessionStart]..[sessionEnd] 裁剪（云端偶尔把离床前后也塞进来）；
     * - 丢弃零长/负长段；
     * - 相邻且同类型的合并（HC 不介意多段，但碎段会让分期表格很难看）。
     */
    fun fromXiaomiItems(items: JSONArray?, sessionStart: Long, sessionEnd: Long): List<Stage> {
        if (items == null || items.length() == 0 || sessionEnd <= sessionStart) return emptyList()
        val raw = ArrayList<Stage>(items.length())
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val s = maxOf(o.optLong("start_time", 0), sessionStart)
            val e = minOf(o.optLong("end_time", 0), sessionEnd)
            if (s <= 0 || e <= s) continue
            raw.add(Stage(s, e, mapXiaomiState(o.optInt("state", -1))))
        }
        raw.sortBy { it.start }
        val out = ArrayList<Stage>(raw.size)
        for (st in raw) {
            val last = out.lastOrNull()
            if (last != null && last.type == st.type && st.start <= last.end) {
                out[out.size - 1] = last.copy(end = maxOf(last.end, st.end))
            } else {
                out.add(st)
            }
        }
        return out
    }

    /** 各阶段分钟数（日志/验收用）。 */
    fun minutesByType(stages: List<Stage>): Map<Int, Long> {
        val acc = LinkedHashMap<Int, Long>()
        for (s in stages) acc[s.type] = (acc[s.type] ?: 0L) + s.minutes
        return acc
    }

    fun name(type: Int): String = when (type) {
        HC_AWAKE -> "awake"
        HC_SLEEPING -> "sleeping"
        HC_OUT_OF_BED -> "out_of_bed"
        HC_LIGHT -> "light"
        HC_DEEP -> "deep"
        HC_REM -> "rem"
        else -> "unknown"
    }
}
