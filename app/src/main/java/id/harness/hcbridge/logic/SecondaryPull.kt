package id.harness.hcbridge.logic

import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 副机（secondary）从自建 MCP 拉回来的 JSON → 待写入 Health Connect 的样本。
 *
 * 抽成纯函数是为了**能测**：这段解析原来埋在网络与 HC 之间，完全没有测试；
 * 2026-10-18「副机四个 query_* 全失败」时，因为解析之外抛的异常被吞成空 `notes`，
 * 连"是网络挂还是解析挂"都分不出来。
 */
object SecondaryPull {

    /** 服务端时间格式。 */
    val TS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** `"2026-10-17 23:14:00"` → epoch 秒；解析不了返回 0。 */
    fun epochOf(s: String, zone: ZoneId = ZoneId.systemDefault()): Long =
        runCatching { LocalDateTime.parse(s, TS).atZone(zone).toEpochSecond() }.getOrDefault(0L)

    /** 样本时间：优先 `epoch`（秒），其次 `at` 字符串。 */
    fun tsOf(o: JSONObject, zone: ZoneId = ZoneId.systemDefault()): Long =
        o.optLong("epoch", 0L).takeIf { it > 0 } ?: epochOf(o.optString("at"), zone)

    /** `{"samples":[{epoch,bpm}]}` → (epoch, bpm)；范围外的丢掉。 */
    fun heartRate(json: JSONObject, zone: ZoneId = ZoneId.systemDefault()): List<Pair<Long, Long>> {
        val arr = json.optJSONArray("samples") ?: return emptyList()
        val out = ArrayList<Pair<Long, Long>>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = tsOf(o, zone)
            val bpm = o.optLong("bpm", 0L)
            if (t > 0 && bpm in 20..250) out.add(t to bpm)
        }
        return out
    }

    /** `{"samples":[{epoch,percent|spo2}]}` → (epoch, %)；50–100 之外丢掉。 */
    fun spo2(json: JSONObject, zone: ZoneId = ZoneId.systemDefault()): List<Pair<Long, Double>> {
        val arr = json.optJSONArray("samples") ?: return emptyList()
        val out = ArrayList<Pair<Long, Double>>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = tsOf(o, zone)
            val pct = o.optDouble("percent", o.optDouble("spo2", -1.0))
            if (t > 0 && pct in 50.0..100.0) out.add(t to pct)
        }
        return out
    }

    /** `{"sessions":[{start,end,title}]}` → (start, end, title)；end<=start 的丢掉。 */
    fun sleepSessions(
        json: JSONObject,
        zone: ZoneId = ZoneId.systemDefault(),
        fallbackTitle: String = "小米手环（服务端）"
    ): List<Triple<Long, Long, String>> {
        val arr = json.optJSONArray("sessions") ?: return emptyList()
        val out = ArrayList<Triple<Long, Long, String>>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val start = epochOf(o.optString("start"), zone)
            val end = epochOf(o.optString("end"), zone)
            if (start > 0 && end > start) {
                out.add(Triple(start, end, o.optString("title").ifBlank { fallbackTitle }))
            }
        }
        return out
    }

    /**
     * `{"series":[{date,value}]}` → (当天起点 epoch, 步数, kcal 估算)。
     * 服务端只给按天序列；kcal 用 0.7/步 粗估（与主路径一致的写法）。
     */
    fun dailySteps(json: JSONObject, zone: ZoneId = ZoneId.systemDefault()): List<Triple<Long, Long, Double>> {
        val arr = json.optJSONArray("series") ?: return emptyList()
        val out = ArrayList<Triple<Long, Long, Double>>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val day = o.optString("date")
            val steps = o.optLong("value", 0L)
            if (day.isBlank() || steps <= 0) continue
            val start = runCatching { LocalDate.parse(day).atStartOfDay(zone).toEpochSecond() }.getOrDefault(0L)
            if (start > 0) out.add(Triple(start, steps, steps * 0.7))
        }
        return out
    }
}
