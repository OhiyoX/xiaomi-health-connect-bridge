package id.harness.hcbridge.auth

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一层时间窗（spec §8.3）。JSON：
 *
 * ```
 * null                     该层不限时（**只有 `null`**；空串/空白是坏数据，不是"不限时"）
 * {}                       全天（等价于不限制时段）
 * {"start":"22:00","end":"06:00"}     跨午夜：[22:00,24:00) ∪ [00:00,06:00)
 * {"start":"09:00","end":"22:00"}     同日：[09:00,22:00)（end 不含）
 * {"start":"00:00","end":"00:00"}     只有这种 start==end 是"全天"；其它 start==end 是零长度 = 永不放行
 * {"days":[1,2,3]}          ISO 星期（1=周一 … 7=周日）；省略 = 每天；`[]` = 永不放行
 * {"from":"2026-11-01","until":"2026-11-30"}   本地日期、含端点
 * {"from":"2026-11-01T00:00:00+08:00"}         绝对时刻（含偏移），按 Instant 比较；from 含、until 不含
 * {"tz":"Asia/Shanghai"}   该层自己的时区；省略 = 调用方给的时区（设备本地）
 * ```
 *
 * 未知键必须拒绝：`{"day":[2]}`（少个 s）若被"忽略未知键"就等于**放宽**成"每天"。
 * `from` 晚于 `until`、或日期与绝对时刻混用 ⇒ 坏数据。
 *
 * 非法 JSON / 形状不对 ⇒ 抛 [IllegalArgumentException]（消息只含常量文本，不含数据）；
 * [ToolGate] 把它折成 `window_corrupt` 拒绝，绝不退化成"放行"。
 *
 * 本文件**不得** import 任何 `android.*`。
 */
object TimeWindow {

    private const val DAY_MINUTES = 24 * 60

    private val WINDOW_KEYS = setOf("tz", "days", "start", "end", "from", "until")

    private val DAY_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    private class Window(
        val zone: ZoneId?,
        val days: Set<Int>?,
        val startMin: Int,
        val endMin: Int,
        val fromDate: LocalDate?,
        val untilDate: LocalDate?,
        val fromInstant: Instant?,
        val untilInstant: Instant?,
    ) {
        fun activeAt(atMillis: Long, defaultZone: ZoneId): Boolean {
            val at = Instant.ofEpochMilli(atMillis).atZone(zone ?: defaultZone)
            val minute = at.hour * 60 + at.minute
            val allDay = startMin == 0 && endMin == DAY_MINUTES
            val inTime = when {
                allDay -> true
                startMin == endMin -> false                       // 零长度窗（非午夜）
                startMin < endMin -> minute >= startMin && minute < endMin
                else -> minute >= startMin || minute < endMin      // 跨午夜
            }
            if (!inTime) return false
            if (days != null && at.dayOfWeek.value !in days) return false
            val date = at.toLocalDate()
            if (fromDate != null && date.isBefore(fromDate)) return false
            if (untilDate != null && date.isAfter(untilDate)) return false
            val instant = Instant.ofEpochMilli(atMillis)
            if (fromInstant != null && instant.isBefore(fromInstant)) return false
            if (untilInstant != null && !instant.isBefore(untilInstant)) return false
            return true
        }

        fun renderedZone(defaultZone: ZoneId): ZoneId = zone ?: defaultZone

        fun canonical(defaultZone: ZoneId): String {
            val z = renderedZone(defaultZone)
            val sb = StringBuilder("{")
            var first = true
            fun kv(key: String, jsonValue: String) {
                if (!first) sb.append(',')
                first = false
                sb.append(JsonCanon.quote(key)).append(':').append(jsonValue)
            }
            zone?.let { kv("tz", JsonCanon.quote(it.id)) }
            days?.let { kv("days", it.sorted().joinToString(",", "[", "]")) }
            if (!(startMin == 0 && endMin == DAY_MINUTES)) {
                kv("start", JsonCanon.quote(hhmm(startMin)))
                kv("end", JsonCanon.quote(hhmm(endMin)))
            }
            fromDate?.let { kv("from", JsonCanon.quote(it.toString())) }
            fromInstant?.let { kv("from", JsonCanon.quote(renderInstant(it, z))) }
            untilDate?.let { kv("until", JsonCanon.quote(it.toString())) }
            untilInstant?.let { kv("until", JsonCanon.quote(renderInstant(it, z))) }
            sb.append('}')
            return sb.toString()
        }

        fun describe(defaultZone: ZoneId): String {
            val z = renderedZone(defaultZone)
            val parts = ArrayList<String>(3)
            days?.let { d ->
                parts.add(if (d.isEmpty()) "不允许任何天" else d.sorted().joinToString("、") { DAY_NAMES[it - 1] })
            }
            parts.add(if (startMin == 0 && endMin == DAY_MINUTES) "全天" else "${hhmm(startMin)}–${hhmm(endMin)}")
            fromDate?.let { parts.add("$it 起") }
            fromInstant?.let { parts.add("${renderInstant(it, z)} 起") }
            untilDate?.let { parts.add("至 $it") }
            untilInstant?.let { parts.add("至 ${renderInstant(it, z)}") }
            zone?.let { parts.add("（${it.id}）") }
            return parts.joinToString(" ")
        }
    }

    /** 该时刻是否在窗内；`null` = 不限时（空串/非法一律抛 ⇒ `window_corrupt`）。 */
    fun active(windowJson: String?, atMillis: Long, zone: ZoneId): Boolean =
        parse(windowJson)?.activeAt(atMillis, zone) ?: true

    /**
     * 归一化后的时间窗串（键固定顺序、时间补零）；`null` ⇒ `null`；空串/非法 ⇒ 抛异常。
     *
     * 绝对时刻按窗内 `tz`（没有就用 [zone]）渲染成 `+08:00` 这类偏移，**不**统一改写成 UTC ——
     * 管理页看到的时间要和用户输入的一致。
     */
    fun canonical(json: String?, zone: ZoneId = ZoneId.systemDefault()): String? =
        parse(json)?.canonical(zone)

    /** 给管理页看的一句话；**永不抛异常**（坏数据也能显示）。 */
    fun describe(json: String?, zone: ZoneId = ZoneId.systemDefault()): String {
        val w = try {
            parse(json)
        } catch (t: IllegalArgumentException) {
            return "时间窗损坏"
        }
        return w?.describe(zone) ?: "不限"
    }

    // ------------------------------------------------------------- 内部

    /** 按窗内时区（没有就用调用方时区）渲染绝对时刻；**不**改写成 UTC。 */
    private fun renderInstant(instant: Instant, defaultZone: ZoneId): String =
        OffsetDateTime.ofInstant(instant, defaultZone).toString()

    /**
     * `from` 晚于 `until` ⇒ 坏数据（不是"永不放行"：那是配置写错，必须显式报出来）。
     * 日期与绝对时刻混用也拒绝 —— 跨时区时哪种解释都能自圆其说，宁可让人写清楚。
     */
    private fun checkOrder(from: Pair<LocalDate?, Instant?>?, until: Pair<LocalDate?, Instant?>?) {
        if (from == null || until == null) return
        val (fromDate, fromInstant) = from
        val (untilDate, untilInstant) = until
        if (fromDate != null && untilDate != null) {
            if (fromDate.isAfter(untilDate)) throw IllegalArgumentException("window: from after until")
            return
        }
        if (fromInstant != null && untilInstant != null) {
            if (fromInstant.isAfter(untilInstant)) throw IllegalArgumentException("window: from after until")
            return
        }
        throw IllegalArgumentException("window: from/until must be the same kind")
    }

    private fun hhmm(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return (if (h < 10) "0$h" else "$h") + ":" + (if (m < 10) "0$m" else "$m")
    }

    private fun parse(json: String?): Window? {
        if (json == null) return null
        val o = JsonCanon.parse(json)
        if (o !is JSONObject) throw IllegalArgumentException("window: not a JSON object")
        for (k in o.keys()) if (k !in WINDOW_KEYS) throw IllegalArgumentException("window: unknown key")
        val zone = opt(o, "tz")?.let { parseZone(it) }
        val days = opt(o, "days")?.let { parseDays(it) }
        val start = opt(o, "start")?.let { parseTime(it, allow24 = false) } ?: 0
        var end = opt(o, "end")?.let { parseTime(it, allow24 = true) } ?: DAY_MINUTES
        if (start == end && start == 0) end = DAY_MINUTES   // 00:00–00:00 = 全天
        val from = opt(o, "from")?.let { parseBoundary(it) }
        val until = opt(o, "until")?.let { parseBoundary(it) }
        checkOrder(from, until)
        return Window(
            zone = zone,
            days = days,
            startMin = start,
            endMin = end,
            fromDate = from?.first,
            fromInstant = from?.second,
            untilDate = until?.first,
            untilInstant = until?.second,
        )
    }

    /** `has` 且不是 JSON `null` 才算给了值。 */
    private fun opt(o: JSONObject, key: String): Any? {
        if (!o.has(key)) return null
        val v = o.opt(key)
        return if (v === JSONObject.NULL) null else v
    }

    private fun parseZone(v: Any?): ZoneId {
        if (v !is String) throw IllegalArgumentException("window: tz must be a string")
        return try {
            ZoneId.of(v.trim())
        } catch (t: Exception) {
            throw IllegalArgumentException("window: unknown timezone")
        }
    }

    private fun parseDays(v: Any?): Set<Int> {
        if (v !is JSONArray) throw IllegalArgumentException("window: days must be an array")
        val out = sortedSetOf<Int>()
        for (i in 0 until v.length()) {
            val n = v.opt(i)
            if (n !is Number) throw IllegalArgumentException("window: days must be numbers")
            val d = n.toDouble()
            if (d != Math.floor(d) || d.isInfinite()) throw IllegalArgumentException("window: days must be integers")
            val day = d.toInt()
            if (day !in 1..7) throw IllegalArgumentException("window: day out of range")
            out.add(day)
        }
        return out
    }

    private fun parseTime(v: Any?, allow24: Boolean): Int {
        if (v !is String) throw IllegalArgumentException("window: time must be a string")
        val parts = v.trim().split(":")
        if (parts.size != 2) throw IllegalArgumentException("window: bad time")
        if (parts[0].isEmpty() || parts[0].length > 2 || parts[1].length != 2) {
            throw IllegalArgumentException("window: bad time")
        }
        val h = parts[0].toIntOrNull() ?: throw IllegalArgumentException("window: bad time")
        val m = parts[1].toIntOrNull() ?: throw IllegalArgumentException("window: bad time")
        if (m !in 0..59) throw IllegalArgumentException("window: bad minute")
        val maxHour = if (allow24) 24 else 23
        if (h !in 0..maxHour) throw IllegalArgumentException("window: bad hour")
        if (h == 24 && m != 0) throw IllegalArgumentException("window: bad time")
        return h * 60 + m
    }

    /** `YYYY-MM-DD` ⇒ 本地日期（含端点）；带 `T` 的 ISO 时间 ⇒ `Instant`。 */
    private fun parseBoundary(v: Any?): Pair<LocalDate?, Instant?> {
        if (v !is String) throw IllegalArgumentException("window: date must be a string")
        val s = v.trim()
        if (!s.contains('T')) {
            val d = try {
                LocalDate.parse(s)
            } catch (t: Exception) {
                throw IllegalArgumentException("window: bad date")
            }
            return d to null
        }
        val instant = try {
            OffsetDateTime.parse(s).toInstant()
        } catch (t: Exception) {
            try {
                Instant.parse(s)
            } catch (t2: Exception) {
                throw IllegalArgumentException("window: bad timestamp")
            }
        }
        return null to instant
    }
}
