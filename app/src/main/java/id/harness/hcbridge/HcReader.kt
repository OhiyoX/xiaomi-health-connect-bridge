package id.harness.hcbridge

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.round

/**
 * 把 Health Connect 现状汇总成一个 JSON（无 UI，供 DumpWorker / MainActivity 共用）。
 * 以前只有 MainActivity 能做这件事 ⇒ 每次读数据都要弹一个窗口；现在后台也能读。
 */
object HcReader {

    private val TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val DS = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private fun r1(v: Double) = round(v * 10.0) / 10.0

    suspend fun dump(client: HealthConnectClient, zone: ZoneId = ZoneId.systemDefault()): JSONObject {
        val out = JSONObject()
        val errors = JSONArray()
        val now = Instant.now()
        val from7 = now.minus(7, ChronoUnit.DAYS)
        out.put("generated_at", TS.format(LocalDateTime.now()))
        out.put("generated_at_epoch_ms", System.currentTimeMillis())
        out.put("device_zone", zone.id)
        out.put("window", "last 7 days")

        // 7 天合计 + 来源
        val totals = JSONObject()
        val origins = linkedSetOf<String>()
        runCatching {
            val r = client.aggregate(
                AggregateRequest(
                    setOf(
                        StepsRecord.COUNT_TOTAL,
                        DistanceRecord.DISTANCE_TOTAL,
                        TotalCaloriesBurnedRecord.ENERGY_TOTAL,
                        ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                        HeartRateRecord.BPM_AVG,
                        HeartRateRecord.BPM_MIN,
                        HeartRateRecord.BPM_MAX,
                    ),
                    TimeRangeFilter.between(from7, now)
                )
            )
            r.get(StepsRecord.COUNT_TOTAL)?.let { totals.put("steps", it) }
            r.get(DistanceRecord.DISTANCE_TOTAL)?.let { totals.put("distance_km", r1(it.inKilometers)) }
            r.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.let { totals.put("total_calories_kcal", r1(it.inKilocalories)) }
            r.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)?.let { totals.put("active_calories_kcal", r1(it.inKilocalories)) }
            r.get(HeartRateRecord.BPM_AVG)?.let { totals.put("heart_rate_avg_bpm", it) }
            r.get(HeartRateRecord.BPM_MIN)?.let { totals.put("heart_rate_min_bpm", it) }
            r.get(HeartRateRecord.BPM_MAX)?.let { totals.put("heart_rate_max_bpm", it) }
            r.dataOrigins.forEach { origins.add(it.packageName) }
        }.onFailure { errors.put("totals: $it"); android.util.Log.w("HCBridge", "dump totals failed", it) }
        out.put("totals_7d", totals)

        // 每日步数
        val daily = JSONArray()
        var d = LocalDate.now(zone).minusDays(6)
        while (!d.isAfter(LocalDate.now(zone))) {
            val s = d.atStartOfDay(zone).toInstant()
            val e = d.plusDays(1).atStartOfDay(zone).toInstant()
            val row = JSONObject().put("date", DS.format(d))
            runCatching {
                val r = client.aggregate(
                    AggregateRequest(
                        setOf(StepsRecord.COUNT_TOTAL, DistanceRecord.DISTANCE_TOTAL),
                        TimeRangeFilter.between(s, e)
                    )
                )
                r.get(StepsRecord.COUNT_TOTAL)?.let { row.put("steps", it) }
                r.get(DistanceRecord.DISTANCE_TOTAL)?.let { row.put("distance_km", r1(it.inKilometers)) }
                val o = r.dataOrigins.map { it.packageName }.distinct()
                if (o.isNotEmpty()) row.put("origins", JSONArray(o))
            }.onFailure { if (errors.length() < 3) errors.put("daily: $it") }
            daily.put(row)
            d = d.plusDays(1)
        }
        out.put("daily", daily)

        // 睡眠
        val sleeps = JSONArray()
        runCatching {
            val res = client.readRecords(
                ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(from7, now))
            )
            if (res.records.isEmpty()) errors.put("sleep: 0 records")
            for (rec in res.records) {
                sleeps.put(
                    JSONObject()
                        .put("start", TS.format(LocalDateTime.ofInstant(rec.startTime, zone)))
                        .put("end", TS.format(LocalDateTime.ofInstant(rec.endTime, zone)))
                        // origin 一定要带上：睡眠重复写入那次就是靠它才看出
                        // 「小米自己一份 + 我们一份」（2026-10-21）
                        .put("origin", rec.metadata.dataOrigin.packageName)
                        .put("record_id", rec.metadata.id)
                        .put("client_id", rec.metadata.clientRecordId ?: "")
                )
            }
        }.onFailure { errors.put("sleep: $it") }
        out.put("sleep_sessions", sleeps)

        // 心率：30 天（首屏）+ 365 天（总量，分页）
        runCatching {
            val res = client.readRecords(
                ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(now.minus(30, ChronoUnit.DAYS), now))
            )
            val arr = res.records
            val o = JSONObject()
                .put("records_in_page", arr.size)
                .put("samples_in_page", arr.sumOf { it.samples.size })
                .put("origins", JSONArray(arr.map { it.metadata.dataOrigin.packageName }.distinct()))
            arr.lastOrNull()?.let {
                o.put("last_at", TS.format(LocalDateTime.ofInstant(it.endTime, zone)))
                o.put("last_bpm", it.samples.lastOrNull()?.beatsPerMinute)
            }
            out.put("heart_rate_diag_30d", o)
        }
        runCatching {
            var token: String? = null
            var recs = 0
            var samples = 0
            var first: String? = null
            var last: String? = null
            do {
                val page = client.readRecords(
                    ReadRecordsRequest(
                        recordType = HeartRateRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(now.minus(365, ChronoUnit.DAYS), now),
                        ascendingOrder = true,
                        pageSize = 1000,
                        pageToken = token
                    )
                )
                recs += page.records.size
                samples += page.records.sumOf { it.samples.size }
                if (first == null) page.records.firstOrNull()?.let {
                    first = TS.format(LocalDateTime.ofInstant(it.startTime, zone))
                }
                page.records.lastOrNull()?.let {
                    last = TS.format(LocalDateTime.ofInstant(it.endTime, zone))
                }
                token = page.pageToken
            } while (!token.isNullOrEmpty())
            out.put(
                "heart_rate_diag_365d",
                JSONObject().put("records", recs).put("samples", samples)
                    .put("first_at", first ?: "").put("last_at", last ?: "")
            )
        }

        // 最新体重 / 血氧 / 呼吸率
        runCatching {
            client.readRecords(ReadRecordsRequest(WeightRecord::class, TimeRangeFilter.before(now)))
                .records.lastOrNull()?.let {
                    out.put(
                        "weight",
                        JSONObject().put("kg", r1(it.weight.inKilograms))
                            .put("at", TS.format(LocalDateTime.ofInstant(it.time, zone)))
                            .put("origin", it.metadata.dataOrigin.packageName)
                    )
                }
        }
        runCatching {
            client.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class, TimeRangeFilter.before(now)))
                .records.lastOrNull()?.let {
                    out.put(
                        "spo2",
                        JSONObject().put("percent", r1(it.percentage.value))
                            .put("at", TS.format(LocalDateTime.ofInstant(it.time, zone)))
                            .put("origin", it.metadata.dataOrigin.packageName)
                    )
                }
        }
        runCatching {
            client.readRecords(ReadRecordsRequest(RespiratoryRateRecord::class, TimeRangeFilter.before(now)))
                .records.lastOrNull()?.let {
                    out.put(
                        "respiratory_rate",
                        JSONObject().put("per_min", r1(it.rate))
                            .put("at", TS.format(LocalDateTime.ofInstant(it.time, zone)))
                            .put("origin", it.metadata.dataOrigin.packageName)
                    )
                }
        }

        out.put("errors", errors)
        out.put("data_origins", JSONArray(origins.toList()))
        return out
    }
}
