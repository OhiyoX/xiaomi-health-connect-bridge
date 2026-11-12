package id.harness.hcbridge

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import id.harness.hcbridge.logic.SleepMerge
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
 * Health Connect 查询层（给 MCP 的"看健康数据"工具用）：
 * 指标序列 / 心率采样 / 睡眠 / 运动 / 体重 / 血氧 / 日汇总 / 数据覆盖。
 * 命名与返回形状对齐社区项目 `binglua/mi-fitness-mcp-cn`，便于习惯迁移。
 */
object HcQuery {

    private val TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val DS = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private fun r1(v: Double) = round(v * 10.0) / 10.0

    /** 解析 YYYY-MM-DD 或完整时间；失败返回 null */
    fun parseInstant(s: String?, endOfDay: Boolean = false): Instant? {
        if (s.isNullOrBlank()) return null
        return runCatching { Instant.parse(s) }.getOrNull()
            ?: runCatching {
                val d = LocalDate.parse(s)
                (if (endOfDay) d.plusDays(1).atStartOfDay(zone) else d.atStartOfDay(zone)).toInstant()
            }.getOrNull()
    }

    private fun range(start: String?, end: String?, defaultDays: Long): Pair<Instant, Instant> {
        val to = parseInstant(end, endOfDay = true) ?: Instant.now()
        val from = parseInstant(start) ?: to.minus(defaultDays, ChronoUnit.DAYS)
        return from to to
    }


    /** 只取一页（默认 1 条）：用来拿"最早/最新一条"，不把整表读进内存。 */
    internal suspend inline fun <reified T : androidx.health.connect.client.records.Record> pageOf(
        client: HealthConnectClient, from: Instant, to: Instant,
        pageSize: Int = 1, ascending: Boolean = true
    ): List<T> = runCatching {
        client.readRecords(
            ReadRecordsRequest(
                recordType = T::class,
                timeRangeFilter = TimeRangeFilter.between(from, to),
                ascendingOrder = ascending,
                pageSize = pageSize
            )
        ).records
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------ 通用分页读

    internal suspend inline fun <reified T : androidx.health.connect.client.records.Record> readAll(
        client: HealthConnectClient, from: Instant, to: Instant, cap: Int = 5000
    ): List<T> {
        val out = ArrayList<T>()
        var token: String? = null
        do {
            val page = client.readRecords(
                ReadRecordsRequest(
                    recordType = T::class,
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                    ascendingOrder = true,
                    pageSize = 1000,
                    pageToken = token
                )
            )
            out.addAll(page.records)
            token = page.pageToken
        } while (!token.isNullOrEmpty() && out.size < cap)
        return out
    }

    // ------------------------------------------------------------ 工具实现

    /** 心率采样 */
    suspend fun heartRate(client: HealthConnectClient, start: String?, end: String?, limit: Int = 500): JSONObject {
        val (from, to) = range(start, end, 1)
        val recs = readAll<HeartRateRecord>(client, from, to)
        val arr = JSONArray()
        var total = 0
        for (r in recs) {
            for (s in r.samples) {
                total++
                if (arr.length() < limit) {
                    arr.put(
                        JSONObject()
                            .put("at", TS.format(LocalDateTime.ofInstant(s.time, zone)))
                            .put("epoch", s.time.epochSecond)
                            .put("bpm", s.beatsPerMinute)
                            .put("origin", r.metadata.dataOrigin.packageName)
                    )
                }
            }
        }
        return JSONObject()
            .put("window", JSONObject().put("from", TS.format(LocalDateTime.ofInstant(from, zone)))
                .put("to", TS.format(LocalDateTime.ofInstant(to, zone))))
            .put("records", recs.size)
            .put("samples_total", total)
            .put("returned", arr.length())
            .put("samples", arr)
    }

    /**
     * 睡眠会话。
     *
     * [dedupe] 默认 true：**同一段被两个来源写了两遍**的（互相重叠 > 较短那段 50%）只留最长的那条。
     * 我们自己的重复已经写不进去了（`logic/SleepMerge`），但**小米/Google Fit 自己也会一晚写多条**
     * （实测 10-19 三条、10-20 两条）—— 那些不是我们能删的，只能在读的时候收敛。
     * 收敛条数会以 `duplicates` 报出来，不默默藏起来；想看原始数据传 `dedupe=false`。
     */
    suspend fun sleep(
        client: HealthConnectClient,
        start: String?,
        end: String?,
        includeNaps: Boolean = true,
        dedupe: Boolean = true
    ): JSONObject {
        val (from, to) = range(start, end, 7)
        val recs = readAll<SleepSessionRecord>(client, from, to)
        val sessions = recs.map {
            SleepMerge.Session(
                it.startTime.epochSecond, it.endTime.epochSecond,
                origin = it.metadata.dataOrigin.packageName,
                recordId = it.metadata.id,
                title = it.title ?: ""
            )
        }
        val kept = if (dedupe) SleepMerge.dedupeOverlaps(sessions) else sessions
        val arr = JSONArray()
        for (s in kept.sortedBy { it.start }) {
            val mins = s.duration / 60.0
            if (!includeNaps && mins < 120) continue
            arr.put(
                JSONObject()
                    .put("start", TS.format(LocalDateTime.ofInstant(Instant.ofEpochSecond(s.start), zone)))
                    .put("end", TS.format(LocalDateTime.ofInstant(Instant.ofEpochSecond(s.end), zone)))
                    .put("minutes", r1(mins))
                    .put("title", s.title)
                    .put("origin", s.origin)
            )
        }
        return JSONObject()
            .put("count", arr.length())
            .put("raw_count", sessions.size)
            .put("duplicates", sessions.size - kept.size)
            .put("sessions", arr)
    }

    /** 运动记录 */
    suspend fun workouts(client: HealthConnectClient, start: String?, end: String?, minDurationMin: Int = 0): JSONObject {
        val (from, to) = range(start, end, 30)
        val recs = readAll<ExerciseSessionRecord>(client, from, to)
        val arr = JSONArray()
        for (r in recs) {
            val mins = (r.endTime.epochSecond - r.startTime.epochSecond) / 60.0
            if (mins < minDurationMin) continue
            arr.put(
                JSONObject()
                    .put("start", TS.format(LocalDateTime.ofInstant(r.startTime, zone)))
                    .put("end", TS.format(LocalDateTime.ofInstant(r.endTime, zone)))
                    .put("minutes", r1(mins))
                    .put("type", r.exerciseType)
                    .put("title", r.title ?: "")
                    .put("origin", r.metadata.dataOrigin.packageName)
            )
        }
        return JSONObject().put("count", arr.length()).put("workouts", arr)
    }

    /** 体重（+ 最近一条） */
    suspend fun bodyMeasurements(client: HealthConnectClient, start: String?, end: String?): JSONObject {
        val (from, to) = range(start, end, 365)
        val recs = readAll<WeightRecord>(client, from, to)
        val arr = JSONArray()
        for (r in recs) {
            arr.put(
                JSONObject()
                    .put("at", TS.format(LocalDateTime.ofInstant(r.time, zone)))
                    .put("kg", r1(r.weight.inKilograms))
                    .put("origin", r.metadata.dataOrigin.packageName)
            )
        }
        return JSONObject().put("count", arr.length())
            .put("latest", if (arr.length() > 0) arr.getJSONObject(arr.length() - 1) else JSONObject.NULL)
            .put("records", arr)
    }



    // ------------------------------------------------------------ 夜间生理（呼吸率 / HRV）

    /**
     * 夜间生理：呼吸率与 HRV（RMSSD）最近值与近 N 天序列。
     * 小米只在 2024-11 之后有 avg_breath、2026-07 之后有 HRV，缺就是缺，不补 0。
     */
    suspend fun nightlyRecovery(client: HealthConnectClient, days: Int = 7): JSONObject {
        val to = Instant.now()
        val from = to.minus(days.toLong(), ChronoUnit.DAYS)
        fun seriesOf(times: List<Pair<Long, Double>>) = JSONArray().apply {
            for ((t, v) in times.sortedBy { it.first }) put(JSONObject().put("epoch", t).put("value", r1(v)))
        }
        val rr = runCatching {
            readAll<RespiratoryRateRecord>(client, from, to, cap = 2000).map { it.time.epochSecond to it.rate }
        }.getOrDefault(emptyList())
        val hrv = runCatching {
            readAll<HeartRateVariabilityRmssdRecord>(client, from, to, cap = 2000)
                .map { it.time.epochSecond to it.heartRateVariabilityMillis }
        }.getOrDefault(emptyList())
        return JSONObject()
            .put("days", days)
            .put("resp_rate_latest", rr.maxByOrNull { it.first }?.second?.let { r1(it) } ?: JSONObject.NULL)
            .put("hrv_latest", hrv.maxByOrNull { it.first }?.second?.let { r1(it) } ?: JSONObject.NULL)
            .put("resp_rate_nights", rr.size)
            .put("hrv_nights", hrv.size)
            .put("resp_rate", seriesOf(rr))
            .put("hrv", seriesOf(hrv))
    }

    // ------------------------------------------------------------ 体成分（体重/体脂/BMI）

    /**
     * 体成分：体重 + 体脂率 + 由身高推算的 BMI，按天合并成一条时间线。
     *
     * 为什么单独做：体重是**稀疏**数据（5 年约 28 次体称），体脂与体重同源但分属不同类型的
     * 记录（WeightRecord / BodyFatRecord），HC 自带界面只能分开看。这里合并 + 算 BMI + 给统计量。
     * `source` 可按来源过滤（如只看 id.harness.hcbridge 或 com.google.android.apps.fitness）。
     */
    suspend fun bodyComposition(
        client: HealthConnectClient,
        start: String?,
        end: String?,
        source: String? = null,
        fallbackHeightM: Double? = null
    ): JSONObject {
        val (from, to) = range(start, end, 365)
        fun ok(pkg: String) = source == null || source.isBlank() || pkg == source

        val weights = readAll<WeightRecord>(client, from, to, cap = 5000)
            .filter { ok(it.metadata.dataOrigin.packageName) }
        val fats = runCatching { readAll<BodyFatRecord>(client, from, to, cap = 5000) }
            .getOrDefault(emptyList()).filter { ok(it.metadata.dataOrigin.packageName) }
        // 身高：取最近一条（用来推 BMI）
        val heightM = runCatching {
            readAll<HeightRecord>(client, Instant.EPOCH, Instant.now(), cap = 100)
                .lastOrNull { ok(it.metadata.dataOrigin.packageName) }?.height?.inMeters
        }.getOrNull() ?: fallbackHeightM

        fun bmiOf(kg: Double): Double? = heightM?.takeIf { it > 0.5 }?.let { h -> r1(kg / (h * h)) }

        // 按天合并（同一天多次称重取当天最后一次）
        val byDay = java.util.TreeMap<LocalDate, JSONObject>()
        for (r in weights) {
            val d = LocalDate.ofInstant(r.time, zone)
            val o = byDay.getOrPut(d) { JSONObject().put("date", DS.format(d)) }
            o.put("kg", r1(r.weight.inKilograms))
            o.put("weight_at", TS.format(LocalDateTime.ofInstant(r.time, zone)))
            o.put("origin", r.metadata.dataOrigin.packageName)
        }
        for (r in fats) {
            val d = LocalDate.ofInstant(r.time, zone)
            val o = byDay.getOrPut(d) { JSONObject().put("date", DS.format(d)) }
            o.put("body_fat_pct", r1(r.percentage.value))
            o.put("body_fat_at", TS.format(LocalDateTime.ofInstant(r.time, zone)))
        }
        for ((_, o) in byDay) {
            if (o.has("kg")) bmiOf(o.getDouble("kg"))?.let { o.put("bmi", it) }
        }

        fun stats(key: String): JSONObject {
            val vs = byDay.values.mapNotNull { if (it.has(key)) it.getDouble(key) else null }
            return if (vs.isEmpty()) JSONObject().put("count", 0)
            else JSONObject().put("count", vs.size)
                .put("min", r1(vs.min())).put("max", r1(vs.max()))
                .put("avg", r1(vs.average()))
                .put("last", r1(vs.last()))
                .put("change", r1(vs.last() - vs.first()))
        }

        val series = JSONArray()
        for ((_, o) in byDay) series.put(o)
        return JSONObject()
            .put("from", TS.format(LocalDateTime.ofInstant(from, zone)))
            .put("to", TS.format(LocalDateTime.ofInstant(to, zone)))
            .put("height_m", heightM?.let { r1(it) } ?: JSONObject.NULL)
            .put("count_weight", weights.size)
            .put("count_body_fat", fats.size)
            .put("latest", if (series.length() > 0) series.getJSONObject(series.length() - 1) else JSONObject.NULL)
            .put("stats", JSONObject().put("kg", stats("kg"))
                .put("body_fat_pct", stats("body_fat_pct"))
                .put("bmi", stats("bmi")))
            .put("series", series)
    }

    /** 血氧 */
    suspend fun spo2(client: HealthConnectClient, start: String?, end: String?, limit: Int = 200): JSONObject {
        val (from, to) = range(start, end, 7)
        val recs = readAll<OxygenSaturationRecord>(client, from, to)
        val arr = JSONArray()
        for (r in recs.takeLast(limit)) {
            arr.put(
                JSONObject()
                    .put("at", TS.format(LocalDateTime.ofInstant(r.time, zone)))
                    .put("percent", r1(r.percentage.value))
                    .put("origin", r.metadata.dataOrigin.packageName)
            )
        }
        return JSONObject().put("count", arr.length()).put("samples", arr)
    }

    /** 今日步数：直接读 StepsRecord 求和（不依赖 aggregate 的权限组合） */
    suspend fun stepsToday(client: HealthConnectClient): Long {
        val d = LocalDate.now(zone)
        val s = d.atStartOfDay(zone).toInstant()
        val e = d.plusDays(1).atStartOfDay(zone).toInstant()
        return dailyStepsAvg(client, s, e)[d]?.first ?: 0L
    }



    // ------------------------------------------------------------ 睡眠分期（HC 自带 stages）

    /**
     * **逐条睡眠记录的原样元数据 + 完整分期段清单**（不过滤、不去重、不汇总）。
     *
     * 排查“同样带分期，为何 Google 能显示小米那条、显示不了我们这条”：
     * 需要看到两边的 title / notes / recordingMethod / device / clientRecordId /
     * 每一个 stage 段（起止 + 类型）到底一样不一样。
     */
    suspend fun sleepRaw(client: HealthConnectClient, start: String?, end: String?): JSONObject {
        val (from, to) = range(start, end, 7)
        val recs = readAll<SleepSessionRecord>(client, from.minus(1, ChronoUnit.DAYS), to, cap = 2000)
            .sortedBy { it.startTime }
        val arr = JSONArray()
        for (r in recs) {
            val b = r.startTime.epochSecond
            val e = r.endTime.epochSecond
            val segs = JSONArray()
            var covered = 0L
            for (st in r.stages) {
                val sb = st.startTime.epochSecond
                val se = st.endTime.epochSecond
                covered += (se - sb)
                segs.put(
                    JSONObject()
                        .put("from", TS.format(LocalDateTime.ofInstant(st.startTime, zone)))
                        .put("to", TS.format(LocalDateTime.ofInstant(st.endTime, zone)))
                        .put("minutes", (se - sb) / 60)
                        .put("type", st.stage)
                )
            }
            val dev = r.metadata.device
            arr.put(
                JSONObject()
                    .put("start", TS.format(LocalDateTime.ofInstant(r.startTime, zone)))
                    .put("end", TS.format(LocalDateTime.ofInstant(r.endTime, zone)))
                    .put("minutes", (e - b) / 60)
                    .put("origin", r.metadata.dataOrigin.packageName)
                    .put("title", r.title)
                    .put("notes", r.notes)
                    .put("recording_method", r.metadata.recordingMethod)
                    .put("client_record_id", r.metadata.clientRecordId)
                    .put("device", dev?.let { "type=${it.type} ${it.manufacturer}/${it.model}" })
                    .put("stages", segs)
                    .put("stage_count", r.stages.size)
                    // 分期覆盖了会话的多少分钟（不等于总时长就说明中间有洞）
                    .put("stage_covered_minutes", covered / 60)
            )
        }
        return JSONObject().put("count", arr.length()).put("records", arr)
    }

    /**
     * 睡眠分期：从 HC 的 `SleepSessionRecord.stages` 汇总（Android 15+ 才有数据）。
     * stage 编码：0=未知 1=清醒 2=浅睡 3=深睡 4=REM（HC 常量）。
     * 同一晚可能有多条记录（我们 + 小米），先按区间去重再汇总。
     */
    suspend fun sleepStages(client: HealthConnectClient, start: String?, end: String?): JSONObject {
        val (from, to) = range(start, end, 7)
        val recs = readAll<SleepSessionRecord>(client, from.minus(1, ChronoUnit.DAYS), to, cap = 2000)
        // 去重（保留区间最长的那条：通常是小米的原始记录）
        val bounds = recs.map { it.startTime.epochSecond to it.endTime.epochSecond }
        val kept = SleepMerge.dedupeOverlaps(bounds.map { SleepMerge.Session(it.first, it.second) })
        val keptSet = kept.map { it.start to it.end }.toSet()

        // Android Health Connect 的 SleepSessionRecord stage 常量（API 34+）：
        //   0=UNKNOWN 1=AWAKE 2=SLEEPING 3=OUT_OF_BED 4=LIGHT 5=DEEP 6=REM
        // 2026-10-21 实测对照小米原始数据验证过：4=浅睡、5=深睡、6=REM。
        val names = mapOf(0 to "unknown", 1 to "awake", 2 to "sleeping",
                          3 to "out_of_bed", 4 to "light", 5 to "deep", 6 to "rem")
        val out = JSONArray()
        for (r in recs) {
            val b = r.startTime.epochSecond
            val e = r.endTime.epochSecond
            if ((b to e) !in keptSet) continue
            val acc = LinkedHashMap<String, Long>()
            for (st in r.stages) {
                val mins = (st.endTime.epochSecond - st.startTime.epochSecond) / 60
                if (mins <= 0) continue
                val k = names[st.stage] ?: "state_${st.stage}"
                acc[k] = (acc[k] ?: 0L) + mins
            }
            val total = (e - b) / 60
            val asleep = total - (acc["awake"] ?: 0L) - (acc["out_of_bed"] ?: 0L)
            val obj = JSONObject()
                .put("start", TS.format(LocalDateTime.ofInstant(r.startTime, zone)))
                .put("end", TS.format(LocalDateTime.ofInstant(r.endTime, zone)))
                .put("minutes", total)
                .put("origin", r.metadata.dataOrigin.packageName)
                .put("has_stages", r.stages.isNotEmpty())
            for ((k, v) in acc) obj.put("${k}_minutes", v)
            if (total > 0) obj.put("efficiency", r1(asleep * 100.0 / total))
            out.put(obj)
        }
        return JSONObject().put("from", TS.format(LocalDateTime.ofInstant(from, zone)))
            .put("to", TS.format(LocalDateTime.ofInstant(to, zone)))
            .put("count", out.length()).put("sessions", out)
    }

    // ------------------------------------------------------------ 步数：多来源取平均

    /**
     * 按天汇总步数/距离，**每天在各来源之间取平均值**（不相加）。
     *
     * 为什么：设备 HC 里有小米、Google Fit 都在写步数，`COUNT_TOTAL` 聚合是"并集覆盖"，
     * 同一次走路会被算两遍（实测 10-14：云端 6,431 / 聚合 10,314）。
     * 业主 2026-10-21 定的口径：**hcb 的步数取各来源平均值**。
     *
     * `com.android.healthconnect.phone.*` 是 HC 系统模块迁移过来的副本（不是独立测量），
     * 默认不参与平均。
     */
    suspend fun dailyStepsAvg(
        client: HealthConnectClient,
        from: Instant,
        to: Instant,
        includeSystemOrigin: Boolean = false
    ): Map<LocalDate, Pair<Long, Double>> {
        val stepRecs = readAll<StepsRecord>(client, from, to, cap = 100000)
        val distRecs = readAll<DistanceRecord>(client, from, to, cap = 100000)
        fun dayOf(i: Instant): LocalDate = LocalDate.ofInstant(i, zone)
        fun usable(o: String) = includeSystemOrigin || !o.startsWith("com.android.healthconnect.phone")

        val stepBy = HashMap<LocalDate, HashMap<String, Long>>()
        for (r in stepRecs) {
            val o = r.metadata.dataOrigin.packageName
            if (!usable(o)) continue
            val m = stepBy.getOrPut(dayOf(r.startTime)) { HashMap() }
            m[o] = (m[o] ?: 0L) + r.count
        }
        val distBy = HashMap<LocalDate, HashMap<String, Double>>()
        for (r in distRecs) {
            val o = r.metadata.dataOrigin.packageName
            if (!usable(o)) continue
            val m = distBy.getOrPut(dayOf(r.startTime)) { HashMap() }
            m[o] = (m[o] ?: 0.0) + r.distance.inMeters
        }
        val out = LinkedHashMap<LocalDate, Pair<Long, Double>>()
        for (d in (stepBy.keys + distBy.keys).sorted()) {
            val sv = stepBy[d]?.values
            val dv = distBy[d]?.values
            out[d] = (if (sv.isNullOrEmpty()) 0L else Math.round(sv.average()).toLong()) to
                (if (dv.isNullOrEmpty()) 0.0 else dv.average())
        }
        return out
    }

    /** 某天步数**按来源拆分**（诊断"是不是重复计算"用）。 */
    suspend fun stepsByOrigin(
        client: HealthConnectClient,
        date: String?,
        stepRecords: List<androidx.health.connect.client.records.StepsRecord>
    ): JSONObject {
        val d = LocalDate.parse(date ?: DS.format(LocalDate.now(zone)))
        val s = d.atStartOfDay(zone).toInstant()
        val e = d.plusDays(1).atStartOfDay(zone).toInstant()
        val total = client.aggregate(
            AggregateRequest(
                setOf(androidx.health.connect.client.records.StepsRecord.COUNT_TOTAL),
                TimeRangeFilter.between(s, e)
            )
        ).get(androidx.health.connect.client.records.StepsRecord.COUNT_TOTAL) ?: 0L
        val byOrigin = LinkedHashMap<String, Long>()
        for (r in stepRecords) {
            if (r.endTime <= s || r.startTime >= e) continue
            val o = r.metadata.dataOrigin.packageName
            byOrigin[o] = (byOrigin[o] ?: 0L) + r.count
        }
        val arr = JSONArray()
        for ((o, n) in byOrigin) arr.put(JSONObject().put("origin", o).put("steps", n))
        return JSONObject().put("date", DS.format(d))
            .put("aggregate_total", total)
            .put("avg_of_origins", Math.round(byOrigin.values.averageOrZero()))
            .put("by_origin", arr)
    }

    private fun Collection<Long>.averageOrZero(): Double =
        if (isEmpty()) 0.0 else sumOf { it.toDouble() } / size

    /** 某一天的汇总 */
    suspend fun dailySummary(client: HealthConnectClient, date: String?): JSONObject {
        val d = LocalDate.parse(date ?: DS.format(LocalDate.now(zone)))
        val s = d.atStartOfDay(zone).toInstant()
        val e = d.plusDays(1).atStartOfDay(zone).toInstant()
        // 聚合按权限逐级降级：缺某个 READ_* 时整组会抛 SecurityException
        val baseFilter = TimeRangeFilter.between(s, e)
        val full = runCatching {
            client.aggregate(AggregateRequest(setOf(
                StepsRecord.COUNT_TOTAL, DistanceRecord.DISTANCE_TOTAL,
                TotalCaloriesBurnedRecord.ENERGY_TOTAL, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MIN, HeartRateRecord.BPM_MAX
            ), baseFilter))
        }.getOrNull()
        val r = full
            ?: runCatching {
                client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL, HeartRateRecord.BPM_AVG), baseFilter))
            }.getOrNull()
            ?: runCatching {
                client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), baseFilter))
            }.getOrNull()
            ?: throw IllegalStateException("无可用的聚合权限")
        // 睡眠：取“与这一天有交集的会话”，**裁到当天**再求和，并按区间去重。
        //   旧写法读 s..e+1day 且直接求和 ⇒ 一晚被算两次（10-19 实测报 650 分钟，实际 291）；
        //   小米自己一晚写多条时还会再翻一倍。现在：归到**起床那天**（与 NAS MCP 口径一致）。
        val stepsAvg = dailyStepsAvg(client, s, e)[d]
        val sTs = s.epochSecond
        val eTs = e.epochSecond
        // 读窗口往前放 1 天：HC 的 TimeRangeFilter 不会把「前一天 23:51 开始、今天 07:31 结束」的
        // 会话算进 [今天 00:00, 明天 00:00)——2026-10-21 实测 09-15 明明有 460 分钟却报 0。
        val sleepRecs = readAll<SleepSessionRecord>(client, s.minus(1, ChronoUnit.DAYS), e)
        val sleepMin = SleepMerge.dedupeOverlaps(
            sleepRecs.map {
                SleepMerge.Session(
                    maxOf(it.startTime.epochSecond, sTs),
                    minOf(it.endTime.epochSecond, eTs)
                )
            }.filter { it.end > it.start }
        ).sumOf { it.duration / 60.0 }
        return JSONObject()
            .put("date", DS.format(d))
            // 步数/距离：多来源取平均（不相加），见 dailyStepsAvg
            .put("steps", stepsAvg?.first ?: 0)
            .put("distance_km", r1((stepsAvg?.second ?: 0.0) / 1000.0))
            .put("calories_kcal", r.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.let { r1(it.inKilocalories) } ?: 0)
            .put("active_kcal", r.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)?.let { r1(it.inKilocalories) } ?: 0)
            .put("heart_rate_avg", r.get(HeartRateRecord.BPM_AVG) ?: JSONObject.NULL)
            .put("heart_rate_min", r.get(HeartRateRecord.BPM_MIN) ?: JSONObject.NULL)
            .put("heart_rate_max", r.get(HeartRateRecord.BPM_MAX) ?: JSONObject.NULL)
            .put("sleep_minutes", r1(sleepMin))
            .put("origins", JSONArray(r.dataOrigins.map { it.packageName }.distinct()))
    }

    /** 指标序列（按天聚合，granularity 可 day/week/month） */
    suspend fun metricSeries(
        client: HealthConnectClient, metric: String,
        start: String?, end: String?, granularity: String = "day"
    ): JSONObject {
        val (from, to) = range(start, end, 30)
        val days = ArrayList<JSONObject>()
        var d = LocalDate.ofInstant(from, zone)
        val lastDay = LocalDate.ofInstant(to, zone)
        // 步数/距离：多来源取平均（与 dailySummary 同口径）
        val stepsAvg = if (metric == "steps" || metric.startsWith("distance"))
            dailyStepsAvg(client, from, to) else emptyMap()
        while (!d.isAfter(lastDay)) {
            val s = d.atStartOfDay(zone).toInstant()
            val e = d.plusDays(1).atStartOfDay(zone).toInstant()
            val v: Any? = when (metric) {
                "steps" -> stepsAvg[d]?.first
                "distance_m" -> stepsAvg[d]?.second?.let { r1(it) }
                "distance_km" -> stepsAvg[d]?.second?.let { r1(it / 1000.0) }
                "active_kcal", "total_kcal" -> client.aggregate(
                    AggregateRequest(
                        setOf(
                            if (metric == "active_kcal") ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL
                            else TotalCaloriesBurnedRecord.ENERGY_TOTAL
                        ),
                        TimeRangeFilter.between(s, e)
                    )
                ).let { res ->
                    (res.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
                        ?: res.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL))?.let { r1(it.inKilocalories) }
                }
                "heart_rate_avg" -> client.aggregate(
                    AggregateRequest(setOf(HeartRateRecord.BPM_AVG), TimeRangeFilter.between(s, e))
                ).get(HeartRateRecord.BPM_AVG)
                "weight_kg" -> readAll<WeightRecord>(client, s, e).lastOrNull()?.let { r1(it.weight.inKilograms) }
                "body_fat_pct" -> runCatching { readAll<BodyFatRecord>(client, s, e) }
                    .getOrNull()?.lastOrNull()?.let { r1(it.percentage.value) }
                "sleep_minutes" -> {
                    val sess = readAll<SleepSessionRecord>(client, s.minus(1, ChronoUnit.DAYS), e)
                        .map { SleepMerge.Session(
                            maxOf(it.startTime.epochSecond, s.epochSecond),
                            minOf(it.endTime.epochSecond, e.epochSecond)) }
                        .filter { it.end > it.start }
                    r1(SleepMerge.dedupeOverlaps(sess).sumOf { it.duration / 60.0 })
                }
                "spo2" -> readAll<OxygenSaturationRecord>(client, s, e).lastOrNull()?.let { r1(it.percentage.value) }
                else -> throw IllegalArgumentException("unknown metric: $metric")
            }
            days.add(JSONObject().put("date", DS.format(d)).put("value", v ?: JSONObject.NULL))
            d = d.plusDays(1)
        }
        // 周/月聚合（sum 语义：步数/距离/卡路里；均值语义留给调用方自行判断）
        val series = when (granularity) {
            "week", "month" -> {
                val buckets = LinkedHashMap<String, Double>()
                for (o in days) {
                    val date = LocalDate.parse(o.getString("date"))
                    val key = if (granularity == "week") {
                        date.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).toString()
                    } else date.withDayOfMonth(1).toString()
                    val v = if (o.isNull("value")) 0.0 else o.getDouble("value")
                    buckets[key] = (buckets[key] ?: 0.0) + v
                }
                JSONArray(buckets.map { (k, v) -> JSONObject().put("date", k).put("value", r1(v)) })
            }
            else -> JSONArray(days)
        }
        return JSONObject()
            .put("metric", metric)
            .put("granularity", granularity)
            .put("from", DS.format(LocalDate.ofInstant(from, zone)))
            .put("to", DS.format(LocalDate.ofInstant(to, zone)))
            .put("series", series)
    }

    /**
     * 导出 CSV：返回 (文件名后缀, 内容, 各类型行数)。
     * types 支持 heart_rate / steps / sleep / workouts / spo2 / weight，默认前三个。
     */
    suspend fun exportCsv(
        client: HealthConnectClient, types: List<String>, start: String?, end: String?
    ): Triple<String, String, Map<String, Int>> {
        val (from, to) = range(start, end, 7)
        val sb = StringBuilder()
        val counts = LinkedHashMap<String, Int>()
        val dfmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")

        fun esc(v: String) = if (v.contains(',') || v.contains('"')) "\"" + v.replace("\"", "\"\"") + "\"" else v

        for (t in types) {
            when (t) {
                "heart_rate" -> {
                    val recs = readAll<HeartRateRecord>(client, from, to, cap = 400000)
                    sb.append("== heart_rate ==\n")
                    sb.append("at,epoch,bpm,origin\n")
                    var n = 0
                    for (r in recs) for (smp in r.samples) {
                        sb.append(TS.format(LocalDateTime.ofInstant(smp.time, zone))).append(',')
                            .append(smp.time.epochSecond).append(',')
                            .append(smp.beatsPerMinute).append(',')
                            .append(esc(r.metadata.dataOrigin.packageName)).append('\n')
                        n++
                    }
                    counts["heart_rate"] = n
                }
                "steps" -> {
                    sb.append("== steps (daily) ==\n")
                    sb.append("date,steps,distance_km,calories_kcal,active_kcal\n")
                    var d = LocalDate.ofInstant(from, zone)
                    val last = LocalDate.ofInstant(to, zone)
                    var n = 0
                    while (!d.isAfter(last)) {
                        val st = d.atStartOfDay(zone).toInstant()
                        val en = d.plusDays(1).atStartOfDay(zone).toInstant()
                        val r = client.aggregate(
                            AggregateRequest(
                                setOf(
                                    StepsRecord.COUNT_TOTAL,
                                    DistanceRecord.DISTANCE_TOTAL,
                                    TotalCaloriesBurnedRecord.ENERGY_TOTAL,
                                    ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL
                                ),
                                TimeRangeFilter.between(st, en)
                            )
                        )
                        sb.append(dfmt.format(d)).append(',')
                            .append(r.get(StepsRecord.COUNT_TOTAL) ?: 0).append(',')
                            .append(r.get(DistanceRecord.DISTANCE_TOTAL)?.let { r1(it.inKilometers) } ?: 0).append(',')
                            .append(r.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.let { r1(it.inKilocalories) } ?: 0).append(',')
                            .append(r.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)?.let { r1(it.inKilocalories) } ?: 0)
                            .append('\n')
                        n++
                        d = d.plusDays(1)
                    }
                    counts["steps"] = n
                }
                "sleep" -> {
                    val recs = readAll<SleepSessionRecord>(client, from, to)
                    sb.append("== sleep ==\n")
                    sb.append("start,end,minutes,title,origin\n")
                    for (r in recs) {
                        sb.append(TS.format(LocalDateTime.ofInstant(r.startTime, zone))).append(',')
                            .append(TS.format(LocalDateTime.ofInstant(r.endTime, zone))).append(',')
                            .append(r1((r.endTime.epochSecond - r.startTime.epochSecond) / 60.0)).append(',')
                            .append(esc(r.title ?: "")).append(',')
                            .append(esc(r.metadata.dataOrigin.packageName)).append('\n')
                    }
                    counts["sleep"] = recs.size
                }
                "workouts" -> {
                    val recs = readAll<ExerciseSessionRecord>(client, from, to)
                    sb.append("== workouts ==\n")
                    sb.append("start,end,minutes,type,title,origin\n")
                    for (r in recs) {
                        sb.append(TS.format(LocalDateTime.ofInstant(r.startTime, zone))).append(',')
                            .append(TS.format(LocalDateTime.ofInstant(r.endTime, zone))).append(',')
                            .append(r1((r.endTime.epochSecond - r.startTime.epochSecond) / 60.0)).append(',')
                            .append(r.exerciseType).append(',')
                            .append(esc(r.title ?: "")).append(',')
                            .append(esc(r.metadata.dataOrigin.packageName)).append('\n')
                    }
                    counts["workouts"] = recs.size
                }
                "spo2" -> {
                    val recs = readAll<OxygenSaturationRecord>(client, from, to)
                    sb.append("== spo2 ==\n")
                    sb.append("at,percent,origin\n")
                    for (r in recs) {
                        sb.append(TS.format(LocalDateTime.ofInstant(r.time, zone))).append(',')
                            .append(r1(r.percentage.value)).append(',')
                            .append(esc(r.metadata.dataOrigin.packageName)).append('\n')
                    }
                    counts["spo2"] = recs.size
                }
                "weight" -> {
                    val recs = readAll<WeightRecord>(client, from, to)
                    sb.append("== weight ==\n")
                    sb.append("at,kg,origin\n")
                    for (r in recs) {
                        sb.append(TS.format(LocalDateTime.ofInstant(r.time, zone))).append(',')
                            .append(r1(r.weight.inKilograms)).append(',')
                            .append(esc(r.metadata.dataOrigin.packageName)).append('\n')
                    }
                    counts["weight"] = recs.size
                }
                else -> throw IllegalArgumentException("unknown type: $t")
            }
            sb.append('\n')
        }
        val suffix = dfmt.format(LocalDate.ofInstant(from, zone)) + "_" + dfmt.format(LocalDate.ofInstant(to, zone))
        return Triple(suffix, sb.toString(), counts)
    }

    /** 数据覆盖（每个类型的首末时间与条数） */
    /**
     * 数据覆盖率（首末时间 + 近 30 天条数/天数/来源）。
     *
     * **绝不整表读**：2026-10-22 早上 app 连续被 OOM 杀掉，原因就是这里对心率做了
     * `readAll(cap = 200000)`——回补 5 年后 HC 里有 124 万心率采样、11 万血氧采样，
     * 全读进来必爆 256MB 堆。现在：
     * - 首/末时间：各取**一页 1 条**（升序最早 / 降序最新）；
     * - 条数/天数/来源：只统计**最近 30 天**（有界，字节级与旧版相同），字段名是
     *   `records_30d` / `days_30d` / `sources`，不再假装是总数。
     *
     * 管理页（`AdminHealthMap`）直接吃这三个字段；MCP 的 `get_data_coverage` 也是同一份输出
     * （新增字段是向后兼容的加法）。**不要**在这里加"总数"字段：那需要扫全表。
     */
    suspend fun coverage(client: HealthConnectClient): JSONObject {
        val now = Instant.now()
        val old = now.minus(3650, ChronoUnit.DAYS)   // 10 年上限，仅用于取"最早一条"
        val d30 = now.minus(30, ChronoUnit.DAYS)
        val out = JSONObject()

        fun putSpan(
            name: String,
            first: Instant?,
            last: Instant?,
            count30d: Int? = null,
            days30d: Int? = null,
            sources: Collection<String> = emptyList(),
        ) {
            out.put(name, JSONObject()
                .put("first", first?.let { TS.format(LocalDateTime.ofInstant(it, zone)) } ?: "")
                .put("last", last?.let { TS.format(LocalDateTime.ofInstant(it, zone)) } ?: "")
                .put("records_30d", count30d ?: JSONObject.NULL)
                .put("days_30d", days30d ?: JSONObject.NULL)
                .put("sources", JSONArray(sources.sorted())))
        }

        /** 近 30 天的 (时间, 来源) → (条数, 有数天数, 来源去重)。 */
        class Window(val count: Int, val days: Int, val sources: List<String>)
        fun window(rows: List<Pair<Instant, String>>): Window = Window(
            rows.size,
            rows.map { LocalDate.ofInstant(it.first, zone) }.distinct().size,
            rows.map { it.second }.distinct(),
        )

        // 心率：首末各一条 + 近 30 天（30 天 ≈ 3 千条，安全；cap 命中时天数是下界）
        val hrFirst = pageOf<HeartRateRecord>(client, old, now, 1, true).firstOrNull()
        val hrLast = pageOf<HeartRateRecord>(client, d30, now, 1, false).firstOrNull()
        val hr30 = window(
            runCatching { readAll<HeartRateRecord>(client, d30, now, cap = 20000) }.getOrDefault(emptyList())
                .map { it.startTime to it.metadata.dataOrigin.packageName },
        )
        putSpan("heart_rate", hrFirst?.startTime, hrLast?.endTime, hr30.count, hr30.days, hr30.sources)

        // 血氧：同上（11 万条也不许整表读）
        val spFirst = pageOf<OxygenSaturationRecord>(client, old, now, 1, true).firstOrNull()
        val spLast = pageOf<OxygenSaturationRecord>(client, d30, now, 1, false).firstOrNull()
        val sp30 = window(
            runCatching { readAll<OxygenSaturationRecord>(client, d30, now, cap = 20000) }.getOrDefault(emptyList())
                .map { it.time to it.metadata.dataOrigin.packageName },
        )
        putSpan("spo2", spFirst?.time, spLast?.time, sp30.count, sp30.days, sp30.sources)

        // 步数：天粒度记录（每天几条），30 天量级很小
        val stFirst = pageOf<StepsRecord>(client, old, now, 1, true).firstOrNull()
        val stLast = pageOf<StepsRecord>(client, d30, now, 1, false).firstOrNull()
        val st30 = window(
            runCatching { readAll<StepsRecord>(client, d30, now, cap = 20000) }.getOrDefault(emptyList())
                .map { it.startTime to it.metadata.dataOrigin.packageName },
        )
        putSpan("steps", stFirst?.startTime, stLast?.endTime, st30.count, st30.days, st30.sources)

        // 下面这些量级都很小（睡眠 1.5k / 运动 168 / 体重 30 / 体脂 24 / 呼吸 712 / HRV 89），整表读没问题
        fun spans(name: String, rows: List<Pair<Instant, String>>) {
            val in30 = rows.filter { it.first.isAfter(d30) }
            val w = window(in30)
            putSpan(
                name,
                rows.minOfOrNull { it.first },
                rows.maxOfOrNull { it.first },
                w.count,
                w.days,
                w.sources,
            )
        }

        runCatching {
            spans("sleep", readAll<SleepSessionRecord>(client, old, now, cap = 5000)
                .map { it.startTime to it.metadata.dataOrigin.packageName })
            spans("workouts", readAll<ExerciseSessionRecord>(client, old, now, cap = 5000)
                .map { it.startTime to it.metadata.dataOrigin.packageName })
            spans("weight", readAll<WeightRecord>(client, old, now, cap = 5000)
                .map { it.time to it.metadata.dataOrigin.packageName })
            spans("body_fat", readAll<BodyFatRecord>(client, old, now, cap = 5000)
                .map { it.time to it.metadata.dataOrigin.packageName })
            spans("basal_metabolic_rate", readAll<BasalMetabolicRateRecord>(client, old, now, cap = 5000)
                .map { it.time to it.metadata.dataOrigin.packageName })
            spans("bone_mass", readAll<BoneMassRecord>(client, old, now, cap = 5000)
                .map { it.time to it.metadata.dataOrigin.packageName })
            spans("lean_body_mass", readAll<LeanBodyMassRecord>(client, old, now, cap = 5000)
                .map { it.time to it.metadata.dataOrigin.packageName })
            spans("body_water_mass", readAll<BodyWaterMassRecord>(client, old, now, cap = 5000)
                .map { it.time to it.metadata.dataOrigin.packageName })
            spans("height", readAll<HeightRecord>(client, old, now, cap = 5000)
                .map { it.time to it.metadata.dataOrigin.packageName })
            spans("respiratory_rate", readAll<RespiratoryRateRecord>(client, old, now, cap = 20000)
                .map { it.time to it.metadata.dataOrigin.packageName })
            spans("hrv_rmssd", readAll<HeartRateVariabilityRmssdRecord>(client, old, now, cap = 20000)
                .map { it.time to it.metadata.dataOrigin.packageName })
        }

        // 卡路里/距离：2 万量级，只报近 30 天条数 + 首末各一条
        runCatching {
            val c30 = window(
                readAll<ActiveCaloriesBurnedRecord>(client, d30, now, cap = 20000)
                    .map { it.endTime to it.metadata.dataOrigin.packageName },
            )
            val cLast = pageOf<ActiveCaloriesBurnedRecord>(client, d30, now, 1, false).firstOrNull()
            putSpan("active_calories", null, cLast?.endTime, c30.count, c30.days, c30.sources)

            val d30w = window(
                readAll<DistanceRecord>(client, d30, now, cap = 20000)
                    .map { it.startTime to it.metadata.dataOrigin.packageName },
            )
            val dLast = pageOf<DistanceRecord>(client, d30, now, 1, false).firstOrNull()
            putSpan("distance", null, dLast?.endTime, d30w.count, d30w.days, d30w.sources)
        }

        return out
    }

}
