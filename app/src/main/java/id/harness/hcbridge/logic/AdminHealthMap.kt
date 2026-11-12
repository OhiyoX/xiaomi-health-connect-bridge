package id.harness.hcbridge.logic

import id.harness.hcbridge.AdminHealth
import id.harness.hcbridge.AdminMetrics
import id.harness.hcbridge.CoverageEntry
import id.harness.hcbridge.MetricEntry
import id.harness.hcbridge.SleepEntry
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.round

/**
 * `HcQuery` 的 JSON → 共享管理前端要的 [CoverageEntry] / [SleepEntry] / [AdminMetrics]。
 *
 * 纯函数（只吃 JSON + 时区），JVM 单测直接喂样例；真正的 Health Connect 调用在 `AdminHealth`。
 *
 * 口径（与 NAS `hcb/stats.py` 对齐，改之前先读 `admin-ui/API.md` §3）：
 * - 覆盖：`records_30d` 是**近 30 天条数**（不是全表总数），`days_30d` 是近 30 天有数据的天数；
 * - 睡眠：**按起床那天**归日（跨零点的睡眠不算两天），一天多段先合计再判标记；
 * - 指标：窗口逐日对齐，缺数据就是 `null`（页面不画断点，也不当 0）。
 */
object AdminHealthMap {

    private val TS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val DS: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /** 覆盖页的展示顺序与中文名；未列出的类型排在后面、名字用原始 type。 */
    val COVERAGE_LABELS: List<Pair<String, String>> = listOf(
        "heart_rate" to "心率",
        "steps" to "步数",
        "distance" to "距离",
        "active_calories" to "活动卡路里",
        "sleep" to "睡眠",
        "workouts" to "运动",
        "spo2" to "血氧",
        "weight" to "体重",
        "body_fat" to "体脂",
        "basal_metabolic_rate" to "基础代谢",
        "bone_mass" to "骨量",
        "lean_body_mass" to "去脂体重",
        "body_water_mass" to "体水分",
        "height" to "身高",
        "respiratory_rate" to "呼吸率",
        "hrv_rmssd" to "HRV（RMSSD）",
    )

    /** 睡眠标记阈值（分钟 / 小时）：写死在这里，测试与页面文案共用一套。 */
    const val SHORT_HOURS = 6.0
    const val LONG_HOURS = 10.0
    const val INVALID_MAX_MIN = 16 * 60
    const val FRAGILE_AWAKE_MIN = 30L
    const val FRAGMENTED_AWAKE_MIN = 90L
    const val LATE_BED_FROM_MIN = 60        // 01:00
    const val LATE_BED_TO_MIN = 6 * 60      // 06:00

    // ------------------------------------------------------------------ 覆盖

    /**
     * `HcQuery.coverage()` 的返回 → 覆盖页行。
     *
     * 只读 `records_30d`（近 30 天），**不做整表求和**：`first`/`last` 是首末各一页，
     * `days_30d`/`sources` 由 `HcQuery` 在同一批有界读取里算好。
     */
    fun coverage(json: JSONObject?, zone: ZoneId = ZoneId.systemDefault()): List<CoverageEntry> {
        if (json == null) return emptyList()
        val out = ArrayList<CoverageEntry>()
        for ((type, label) in COVERAGE_LABELS) {
            val o = json.optJSONObject(type) ?: continue
            out.add(
                CoverageEntry(
                    type = type,
                    label = label,
                    rows = longOrNull(o, "records_30d"),
                    sources = stringList(o.optJSONArray("sources")),
                    days30 = intOrNull(o, "days_30d"),
                    lastAt = parseTs(o.optString("last", ""), zone),
                ),
            )
        }
        // 未知类型（未来新增的记录类型）：不丢，排在后面
        val known = COVERAGE_LABELS.map { it.first }.toSet()
        val keys = json.keys()
        val extra = ArrayList<String>()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k !in known && json.optJSONObject(k) != null) extra.add(k)
        }
        for (type in extra.sorted()) {
            val o = json.optJSONObject(type) ?: continue
            out.add(
                CoverageEntry(
                    type = type,
                    label = type,
                    rows = longOrNull(o, "records_30d"),
                    sources = stringList(o.optJSONArray("sources")),
                    days30 = intOrNull(o, "days_30d"),
                    lastAt = parseTs(o.optString("last", ""), zone),
                ),
            )
        }
        return out
    }

    // ------------------------------------------------------------------ 睡眠

    /** 睡眠体检窗口内的一天（按起床那天归日）。 */
    private class DayAcc(
        val date: LocalDate,
        var totalMin: Double = 0.0,
        var start: Instant? = null,
        var end: Instant? = null,
        var awakeMin: Long? = null,
        var segments: Int = 0,
        var withStages: Int = 0,
        var origin: String = "",
        var longest: Double = 0.0,
    )

    /**
     * `HcQuery.sleepStages()` 的 `sessions` → 睡眠体检行（最新在前）。
     *
     * 窗口内没有样本的日子给一条 `missing`（页面把「无样本」画成失败行），
     * 这样"连续几天没睡好/没同步上来"一眼能看见，而不是静默跳过。
     */
    fun sleep(
        json: JSONObject?,
        days: Int = AdminHealth.SLEEP_DAYS,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<SleepEntry> {
        val sessions = json?.optJSONArray("sessions") ?: JSONArray()
        val byDay = LinkedHashMap<LocalDate, DayAcc>()
        for (i in 0 until sessions.length()) {
            val o = sessions.optJSONObject(i) ?: continue
            val start = parseInstant(o.optString("start", ""), zone) ?: continue
            val end = parseInstant(o.optString("end", ""), zone) ?: continue
            val minutes = numOrNull(o, "minutes") ?: ((end.epochSecond - start.epochSecond) / 60.0)
            if (minutes <= 0) continue
            // 归到**起床那天**：与 NAS 口径一致（一晚跨零点不会算成两天）
            val day = LocalDate.ofInstant(end, zone)
            val acc = byDay.getOrPut(day) { DayAcc(day) }
            acc.totalMin += minutes
            acc.segments += 1
            if (acc.start == null || start.isBefore(acc.start)) acc.start = start
            if (acc.end == null || end.isAfter(acc.end)) acc.end = end
            acc.awakeMin = (acc.awakeMin ?: 0L) + (numOrNull(o, "awake_minutes")?.toLong() ?: 0L)
            if (o.optBoolean("has_stages", false)) acc.withStages += 1
            if (minutes > acc.longest) {
                acc.longest = minutes
                acc.origin = o.optString("origin", "")
            }
        }

        val today = LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        val out = ArrayList<SleepEntry>(days)
        for (offset in 0 until days) {
            val date = today.minusDays(offset.toLong())
            val acc = byDay[date]
            if (acc == null) {
                out.add(
                    SleepEntry(
                        date = DS.format(date),
                        bed = "—",
                        wake = "—",
                        flags = listOf("missing"),
                        note = "这一天没有睡眠样本",
                    ),
                )
                continue
            }
            out.add(row(acc, zone))
        }
        return out
    }

    private fun row(acc: DayAcc, zone: ZoneId): SleepEntry {
        val hours = round1(acc.totalMin / 60.0)
        val flags = ArrayList<String>()
        if (acc.totalMin <= 0 || acc.totalMin > INVALID_MAX_MIN) flags.add("invalid")
        if (hours < SHORT_HOURS) flags.add("short")
        if (hours > LONG_HOURS) flags.add("long")
        val bedMinute = acc.start?.let { minuteOfDay(it, zone) }
        if (bedMinute != null && bedMinute >= LATE_BED_FROM_MIN && bedMinute < LATE_BED_TO_MIN) {
            flags.add("late_bed")
        }
        val awake = acc.awakeMin
        if (awake != null && awake >= FRAGILE_AWAKE_MIN && awake >= (acc.totalMin * 0.2)) flags.add("fragile")
        if (awake != null && awake >= FRAGMENTED_AWAKE_MIN) flags.add("fragmented")

        val note = ArrayList<String>()
        if (acc.segments > 1) note.add("${acc.segments} 段合计")
        if (acc.withStages == 0) note.add("无分期")
        return SleepEntry(
            date = DS.format(acc.date),
            bed = acc.start?.let { SleepMath.clock(minuteOfDay(it, zone).toDouble()) } ?: "—",
            wake = acc.end?.let { SleepMath.clock(minuteOfDay(it, zone).toDouble()) } ?: "—",
            hours = hours,
            score = null,
            source = acc.origin,
            flags = flags,
            note = note.joinToString(" · "),
        )
    }

    private fun minuteOfDay(at: Instant, zone: ZoneId): Int {
        val t: LocalTime = LocalDateTime.ofInstant(at, zone).toLocalTime()
        return t.hour * 60 + t.minute
    }

    // ------------------------------------------------------------------ 指标

    /**
     * 三条 14 天序列（步数 / 心率日均 / 睡眠时长）。
     *
     * [steps]/[heart]/[sleepMinutes] 是 `HcQuery.metricSeries()` 的原始返回；缺对象 ⇒ 该指标全 `null`。
     * 睡眠的分钟数在这里换成小时（页面单位是 `h`，1 位小数）。
     */
    fun metrics(
        steps: JSONObject?,
        heart: JSONObject?,
        sleepMinutes: JSONObject?,
        days: Int = AdminHealth.DEFAULT_METRIC_DAYS,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): AdminMetrics {
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        val dates = (days - 1 downTo 0).map { today.minusDays(it.toLong()) }
        fun seriesOf(src: JSONObject?, scale: Double, round1: Boolean): List<Double?> {
            val map = HashMap<String, Double>()
            val arr = src?.optJSONArray("series") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val v = numOrNull(o, "value") ?: continue
                val value = v * scale
                map[o.optString("date", "")] = if (round1) round1(value) else value
            }
            return dates.map { map[DS.format(it)] }
        }
        val entries = listOf(
            metric("steps", "步数", "步", 0, seriesOf(steps, 1.0, false)),
            metric("heart_rate", "心率（日均）", "bpm", 0, seriesOf(heart, 1.0, false)),
            metric("sleep", "睡眠时长", "h", 1, seriesOf(sleepMinutes, 1.0 / 60.0, true)),
        )
        return AdminMetrics(days = days, entries = entries)
    }

    private fun metric(key: String, label: String, unit: String, decimals: Int, series: List<Double?>): MetricEntry {
        val known = series.filterNotNull()
        return MetricEntry(
            key = key,
            label = label,
            unit = unit,
            decimals = decimals,
            latest = known.lastOrNull(),
            avg = if (known.isEmpty()) null else roundTo(known.average(), decimals),
            series = series,
        )
    }

    // ------------------------------------------------------------------ 小工具

    /** `yyyy-MM-dd HH:mm:ss`（设备时区）→ [Instant]；空/坏格式 ⇒ `null`。 */
    fun parseInstant(text: String?, zone: ZoneId): Instant? {
        val s = text?.trim().orEmpty()
        if (s.isEmpty()) return null
        return runCatching { LocalDateTime.parse(s, TS).atZone(zone).toInstant() }.getOrNull()
    }

    /** 同上，但给页面要的 **epoch 毫秒**。 */
    fun parseTs(text: String?, zone: ZoneId): Long? = parseInstant(text, zone)?.toEpochMilli()

    private fun stringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = LinkedHashSet<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "")
            if (s.isNotEmpty()) out.add(s)
        }
        return out.toList()
    }

    private fun longOrNull(o: JSONObject, key: String): Long? =
        if (!o.has(key) || o.isNull(key)) null else o.optLong(key)

    private fun intOrNull(o: JSONObject, key: String): Int? =
        if (!o.has(key) || o.isNull(key)) null else o.optInt(key)

    private fun numOrNull(o: JSONObject, key: String): Double? {
        if (!o.has(key) || o.isNull(key)) return null
        val v = o.optDouble(key)
        return if (v.isNaN() || v.isInfinite()) null else v
    }

    private fun round1(v: Double): Double = round(v * 10.0) / 10.0

    private fun roundTo(v: Double, decimals: Int): Double {
        if (decimals <= 0) return round(v)
        val f = Math.pow(10.0, decimals.toDouble())
        return round(v * f) / f
    }
}
