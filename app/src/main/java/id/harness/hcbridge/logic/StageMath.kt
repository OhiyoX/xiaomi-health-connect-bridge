package id.harness.hcbridge.logic

/**
 * 睡眠分期的汇总与展示文案（JVM 单测覆盖，见 StageMathTest）。
 *
 * 背景：HC 的 SleepSessionRecord.stages 常量是
 * 0=UNKNOWN 1=AWAKE 2=SLEEPING 3=OUT_OF_BED 4=LIGHT 5=DEEP 6=REM。
 * 2026-10-21 曾按 2=浅睡 3=深睡 4=REM 猜，结果 450 分钟的睡眠报出「REM 311 分钟」；
 * 现在常量只在 STAGE_NAMES 这里定义一次，展示顺序固定为 深 → 浅 → REM → 清醒。
 */
object StageMath {

    const val UNKNOWN = 0
    const val AWAKE = 1
    const val SLEEPING = 2
    const val OUT_OF_BED = 3
    const val LIGHT = 4
    const val DEEP = 5
    const val REM = 6

    val STAGE_NAMES: Map<Int, String> = mapOf(
        UNKNOWN to "未知", AWAKE to "清醒", SLEEPING to "睡眠", OUT_OF_BED to "离床",
        LIGHT to "浅睡", DEEP to "深睡", REM to "REM"
    )

    /** 展示顺序（深睡在最左，符合"睡眠质量"直觉）。 */
    val DISPLAY_ORDER = listOf(DEEP, LIGHT, REM, AWAKE)

    data class Row(val stage: Int, val name: String, val minutes: Long, val pct: Double)

    data class Summary(
        val totalMinutes: Long,
        val asleepMinutes: Long,
        val efficiency: Double,
        val rows: List<Row>,
        val hasData: Boolean
    )

    /** 汇总。totalMinutes 为整段卧床分钟；缺省时用各阶段求和。 */
    fun summarize(minutesByStage: Map<Int, Long>, totalMinutes: Long? = null): Summary {
        val clean = minutesByStage.filterValues { it > 0 }
        val sum = clean.values.sum()
        val total = totalMinutes?.takeIf { it > 0 } ?: sum
        val awakeOut = (clean[AWAKE] ?: 0L) + (clean[OUT_OF_BED] ?: 0L)
        val asleep = (total - awakeOut).coerceAtLeast(0L)
        val eff = if (total > 0) asleep * 100.0 / total else 0.0

        val rows = ArrayList<Row>()
        for (st in DISPLAY_ORDER) {
            val m = clean[st] ?: 0L
            if (m <= 0) continue
            rows.add(Row(st, STAGE_NAMES[st] ?: "阶段" + st, m, if (total > 0) m * 100.0 / total else 0.0))
        }
        // 顺序表之外的阶段追加在后面，便于发现问题而不是静默丢掉
        for ((st, m) in clean.entries.sortedBy { it.key }) {
            if (st in DISPLAY_ORDER) continue
            rows.add(Row(st, STAGE_NAMES[st] ?: "阶段" + st, m, if (total > 0) m * 100.0 / total else 0.0))
        }
        return Summary(total, asleep, eff, rows, clean.isNotEmpty())
    }

    /** 一行文案：深睡 33m（17%）。 */
    fun line(r: Row): String = String.format("%s %dm（%d%%）", r.name, r.minutes, Math.round(r.pct))

    /** 堆叠条的比例片段（0..1，和为 1；无数据返回空）。 */
    fun fractions(minutesByStage: Map<Int, Long>): List<Pair<Int, Float>> {
        val sum = minutesByStage.filterValues { it > 0 }.values.sum()
        if (sum <= 0) return emptyList()
        val out = ArrayList<Pair<Int, Float>>()
        for (st in DISPLAY_ORDER) {
            val m = minutesByStage[st] ?: 0L
            if (m > 0) out.add(st to (m.toFloat() / sum))
        }
        return out
    }

    /** 夜间生理一行：呼吸率 17/min · HRV 33ms，缺失显示「—」。 */
    fun nightLine(respRate: Double?, hrv: Double?): String {
        val r = respRate?.let { String.format("%.0f/min", it) } ?: "—"
        val h = hrv?.let { String.format("%.0fms", it) } ?: "—"
        return "呼吸率 " + r + " · HRV " + h
    }
}
