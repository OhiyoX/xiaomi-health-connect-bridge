package id.harness.hcbridge

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Power
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import id.harness.hcbridge.logic.SleepMerge
import id.harness.hcbridge.logic.SleepStages
import java.time.Instant
import java.time.ZoneId

/** 把 (epochSeconds, bpm) 采样写进 Health Connect（按 15 分钟分桶，带 clientRecordId 去重）。 */
object HcWriter {

    private val device = Device(Device.TYPE_FITNESS_BAND, "Xiaomi", "Mi Smart Band 10 Pro")

    /**
     * 睡眠记录用**标准写法**（依据 Health Connect 官方 Sleep 体验指南）：
     *  - `SleepSessionRecord` 带 start/end + 完整 `stages`（深/浅/REM/清醒）；
     *  - `metadata` 里 **显式给 clientRecordId**（官方 Best Practice：防重复、方便重试）+ device（DeviceType 必填）+ recordingMethod=AUTO；
     *  - 夜间心率/血氧/呼吸率作为独立记录写，时间落在会话区间内（HC 就是靠区间重叠把两者关联起来的）。
     *
     * 2026-10-29 排查记录：我们之前也带了 stages，但读回**没有 clientRecordId**（`Metadata.autoRecordedWithId` 那条路没落库），
     * 而官方要求显式 clientRecordId —— 改成 `Metadata(clientRecordId=…)` 后读回就有了（见 hc_sleep_raw）。
     */
    private val sleepDevice: Device get() = device
    private const val BUCKET = 900L // 15 分钟
    private const val SLEEP_ID = "hc-cloud-sleep-"

    /** 睡眠写入结果：写入 / 删除 / 别的来源已覆盖而跳过 / 本来就一致。 */
    data class SleepResult(
        val inserted: Int,
        val deleted: Int,
        val skippedForeign: Int = 0,
        val unchanged: Int = 0,
        /** 其中“同一段补分期”的重写条数（Google 说睡眠数据不足时就看这个） */
        val rewriteStages: Int = 0
    )

    suspend fun insertHeartRate(
        client: HealthConnectClient,
        samples: List<Pair<Long, Long>>,
        zone: ZoneId = ZoneId.systemDefault()
    ): Pair<Int, Int> { // (samples, records)
        if (samples.isEmpty()) return 0 to 0
        val buckets = LinkedHashMap<Long, MutableList<Pair<Long, Long>>>()
        for (s in samples) {
            val b = s.first / BUCKET * BUCKET
            buckets.getOrPut(b) { ArrayList() }.add(s)
        }
        val records = buckets.map { (bucket, list) ->
            val start = Instant.ofEpochSecond(bucket)
            val end = Instant.ofEpochSecond(maxOf(list.last().first + 60, bucket + 60))
            HeartRateRecord(
                startTime = start,
                startZoneOffset = zone.rules.getOffset(start),
                endTime = end,
                endZoneOffset = zone.rules.getOffset(end),
                samples = list.map {
                    HeartRateRecord.Sample(
                        time = Instant.ofEpochSecond(it.first),
                        beatsPerMinute = it.second
                    )
                },
                metadata = Metadata.autoRecordedWithId("hc-cloud-hr-$bucket", device)
            )
        }
        client.insertRecords(records)
        return samples.size to records.size
    }

    /**
     * 睡眠会话（云端 sleep：bedtime → 起床时间）—— **不重复写**版。
     *
     * 小米运动健康自己就往 HC 写睡眠，桥再从云端搬一份 ⇒ 每晚会出现在 HC 里两条完全重叠的记录
     * （2026-10-21 取证：10-10…10-21 共 32 条 = 16 对同段双来源）。另外云端修订起床时间时
     * `hc-cloud-sleep-<bedtime>` 的 id 会变，同一晚还会堆出更多条。
     *
     * 现在的顺序：读回已有记录 → [SleepMerge] 算合并计划 → 先删（own 的旧/重复记录）再写。
     * 读不回来（HC 权限被回收等）时退回按 clientRecordId 幂等的旧写法，不挡同步。
     *
     * @param ownPackage 本应用包名（`applicationContext.packageName`），用来区分「谁写的」。
     */
    suspend fun reconcileSleep(
        client: HealthConnectClient,
        sessions: List<SleepMerge.Session>, // (bedtime, wake, title[, stages])
        zone: ZoneId = ZoneId.systemDefault(),
        ownPackage: String
    ): SleepResult {
        if (sessions.isEmpty()) return SleepResult(0, 0)
        val incoming = sessions
        val existing = runCatching {
            readSleep(client, incoming.minOf { it.start } - 86400, incoming.maxOf { it.end } + 86400)
        }.getOrNull() ?: return SleepResult(insertSleepRecords(client, incoming, zone), 0)
        val plan = SleepMerge.plan(existing, incoming, ownPackage)
        val deleted = deleteSleep(client, plan.deleteRecordIds)
        val inserted = insertSleepRecords(client, plan.insert, zone)
        return SleepResult(inserted, deleted, plan.skippedForeign, plan.unchanged, plan.rewriteStages)
    }

    /**
     * 一次性清理历史重复：把「别的来源已经写过、我们又写了一遍」的 own 睡眠记录删掉，
     * 外加同一晚被修订后残留的多条。不动别的应用写的记录。
     */
    suspend fun cleanupSleepDuplicates(
        client: HealthConnectClient,
        days: Long,
        ownPackage: String
    ): SleepResult {
        val now = System.currentTimeMillis() / 1000
        val existing = readSleep(client, now - days * 86400, now + 86400)
        val plan = SleepMerge.plan(existing, emptyList(), ownPackage)
        return SleepResult(0, deleteSleep(client, plan.deleteRecordIds))
    }

    /** 睡眠会话（旧写法：只按 clientRecordId 幂等，不去重；仅作读不回来时的兜底） */
    suspend fun insertSleep(
        client: HealthConnectClient,
        sessions: List<Triple<Long, Long, String>>,
        zone: ZoneId = ZoneId.systemDefault()
    ): Int = insertSleepRecords(
        client,
        sessions.distinctBy { it.first }.map { SleepMerge.Session(it.first, it.second, title = it.third) },
        zone
    )

    private suspend fun insertSleepRecords(
        client: HealthConnectClient,
        sessions: List<SleepMerge.Session>,
        zone: ZoneId
    ): Int {
        if (sessions.isEmpty()) return 0
        val recs = sessions.map {
            val s = Instant.ofEpochSecond(it.start)
            val e = Instant.ofEpochSecond(it.end)
            SleepSessionRecord(
                startTime = s,
                startZoneOffset = zone.rules.getOffset(s),
                endTime = e,
                endZoneOffset = zone.rules.getOffset(e),
                // title 可选（官方：仅元数据）；保留便于人看
                title = it.title.ifBlank { null },
                notes = null,
                // 分期：Google Health 靠它算睡眠质量；没有分期会被判“睡眠数据不足”
                stages = it.stages.map { st ->
                    SleepSessionRecord.Stage(
                        startTime = Instant.ofEpochSecond(st.start),
                        endTime = Instant.ofEpochSecond(st.end),
                        stage = st.type
                    )
                },
                // 官方 Best Practice：显式 clientRecordId（+ device、recordingMethod）
                metadata = Metadata.autoRecorded(sleepDevice, "$SLEEP_ID${it.start}")
            )
        }
        for (chunk in recs.chunked(200)) client.insertRecords(chunk)
        return recs.size
    }

    /**
     * 删自己写过的睡眠记录。
     *
     * 用 HC 的**平台记录 id**（`metadata.id`），不用 clientRecordId：
     * 2026-10-21 实测，我们自己写进去的睡眠记录读回来 `clientRecordId` 是空串（小米那条倒是有），
     * 拿 clientRecordId 删命中 0 条。平台 id 才是不变的那个。
     */
    private suspend fun deleteSleep(client: HealthConnectClient, recordIds: List<String>): Int {
        var n = 0
        for (chunk in recordIds.chunked(200)) {
            client.deleteRecords(SleepSessionRecord::class, chunk, emptyList())
            n += chunk.size
            kotlinx.coroutines.delay(60)
        }
        return n
    }

    /** 直接写入（不过合并规则）：恢复磁盘/手动补录用。 */
    suspend fun insertSleepDirect(
        client: HealthConnectClient,
        sessions: List<SleepMerge.Session>,
        zone: ZoneId = ZoneId.systemDefault()
    ): Int = insertSleepRecords(client, sessions, zone)

    /**
     * 把**本应用写的**睡眠记录整体平移 [shiftS] 秒（删旧写新），用来换一个“会话身份”。
     *
     * 为什么需要：Google Health（= Fitbit 包）把 HC 里的睡眠会话**按会话缓存**，我们在库里把一条
     * 无分期的记录删旧写新补上分期后，它那一侧还是“当初那条没分期的”，于是继续显示
     * “第三方数据不可用”。把起止平移 1 秒 → 新的会话 → 它重新导入一次。
     */
    suspend fun shiftOwnSleep(
        client: HealthConnectClient,
        from: Long,
        to: Long,
        shiftS: Long,
        ownPackage: String,
        zone: ZoneId = ZoneId.systemDefault()
    ): Pair<Int, Int> {
        val raw = HcQuery.readAll<SleepSessionRecord>(
            client,
            Instant.ofEpochSecond(from), Instant.ofEpochSecond(to), cap = 2000
        ).filter { it.metadata.dataOrigin.packageName == ownPackage }
        if (raw.isEmpty()) return 0 to 0
        val ids = raw.mapNotNull { it.metadata.id }.filter { it.isNotEmpty() }
        val deleted = deleteSleep(client, ids)
        val sessions = raw.map { r ->
            SleepMerge.Session(
                r.startTime.epochSecond + shiftS,
                r.endTime.epochSecond + shiftS,
                title = r.title ?: "小米睡眠",
                stages = r.stages.map { st ->
                    SleepStages.Stage(
                        st.startTime.epochSecond + shiftS,
                        st.endTime.epochSecond + shiftS,
                        st.stage
                    )
                }.filter { it.end > it.start }
            )
        }
        val inserted = insertSleepRecords(client, sessions, zone)
        return inserted to deleted
    }

    /**
     * 删掉“同一个自己”的重复项：本应用写的两条记录重叠超一半、起止差 ≤120s 时，只留一条
     * （留更长的；同长留**开始更晚**的那条，即平移过的新会话）。
     */
    suspend fun pruneOwnSleepDuplicates(
        client: HealthConnectClient,
        from: Long,
        to: Long,
        ownPackage: String
    ): Int {
        val raw = HcQuery.readAll<SleepSessionRecord>(
            client,
            Instant.ofEpochSecond(from), Instant.ofEpochSecond(to), cap = 2000
        ).filter { it.metadata.dataOrigin.packageName == ownPackage }
        if (raw.size < 2) return 0
        val sorted = raw.sortedWith(
            compareByDescending<SleepSessionRecord> { it.endTime.epochSecond - it.startTime.epochSecond }
                .thenByDescending { it.startTime.epochSecond }
        )
        val keep = ArrayList<SleepSessionRecord>()
        val drop = ArrayList<String>()
        for (r in sorted) {
            val dup = keep.firstOrNull {
                val overlap = minOf(it.endTime.epochSecond, r.endTime.epochSecond) -
                    maxOf(it.startTime.epochSecond, r.startTime.epochSecond)
                val shorter = minOf(
                    it.endTime.epochSecond - it.startTime.epochSecond,
                    r.endTime.epochSecond - r.startTime.epochSecond
                )
                overlap > 0 && overlap >= 0.5 * shorter &&
                    Math.abs(it.startTime.epochSecond - r.startTime.epochSecond) <= 120
            }
            if (dup != null) {
                r.metadata.id?.takeIf { it.isNotEmpty() }?.let { drop.add(it) }
            } else {
                keep.add(r)
            }
        }
        return deleteSleep(client, drop)
    }

    /** 读回窗口内的睡眠记录（带来源与平台记录 id，供合并用）。 */
    suspend fun sleepingSessions(
        client: HealthConnectClient,
        from: Long,
        to: Long
    ): List<SleepMerge.Session> = readSleep(client, from, to)

    private suspend fun readSleep(
        client: HealthConnectClient,
        from: Long,
        to: Long
    ): List<SleepMerge.Session> = HcQuery
        .readAll<SleepSessionRecord>(client, Instant.ofEpochSecond(from), Instant.ofEpochSecond(to), cap = 20000)
        .map {
            SleepMerge.Session(
                start = it.startTime.epochSecond,
                end = it.endTime.epochSecond,
                origin = it.metadata.dataOrigin.packageName,
                recordId = it.metadata.id,
                clientRecordId = it.metadata.clientRecordId,
                title = it.title ?: "",
                // 只记“有没有分期”：判定“要不要补分期重写”用
                stages = if (it.stages.isEmpty()) emptyList()
                else listOf(SleepStages.Stage(0, 0, SleepStages.HC_UNKNOWN))
            )
        }

    /**
     * 活动卡路里（云 `calories` 序列 = 活动消耗，小时桶）—— **只给“HC 当天为 0”的日子补**。
     *
     * 小米运动健康自己会写 ActiveCaloriesBurned，所以不做“见空就填”之外的任何写入，
     * 否则同一天会有两份卡路里（和步数那个坑一模一样）。
     */
    suspend fun insertActiveCalories(
        client: HealthConnectClient,
        hourly: List<Pair<Long, Double>>, // (hourStart, kcal)
        zone: ZoneId = ZoneId.systemDefault()
    ): Int {
        if (hourly.isEmpty()) return 0
        val recs = hourly.distinctBy { it.first }.map { (h, kcal) ->
            val s = Instant.ofEpochSecond(h)
            val e = Instant.ofEpochSecond(h + 3600)
            androidx.health.connect.client.records.ActiveCaloriesBurnedRecord(
                startTime = s,
                startZoneOffset = zone.rules.getOffset(s),
                endTime = e,
                endZoneOffset = zone.rules.getOffset(e),
                energy = androidx.health.connect.client.units.Energy.kilocalories(kcal),
                metadata = Metadata.autoRecordedWithId("hc-cloud-active-kcal-$h", device)
            )
        }
        for (chunk in recs.chunked(400)) {
            client.insertRecords(chunk)
            kotlinx.coroutines.delay(60)
        }
        return recs.size
    }


    /**
     * 步数 / 活动卡路里的历史重复清理（2026-10-21 发现的那 6 天）。
     *
     * 成因：给 HC 补 `READ_HEALTH_DATA_HISTORY` 之前，"HC 当天为 0 才补"的判据在 30 天外**永远读到 0**，
     * 于是小米已经写过的日子我们又补了一份（设备 > 云端 1.5 倍）。
     * 规则与睡眠一致：**同一天只要有别的来源写过，就删掉我们那天的记录**（我们不碰别人的数据）。
     */
    suspend fun cleanupDuplicateActivity(
        client: HealthConnectClient,
        days: Long,
        ownPackage: String,
        zone: ZoneId = ZoneId.systemDefault()
    ): Triple<Int, Int, Int> {
        val now = System.currentTimeMillis() / 1000
        val from = Instant.ofEpochSecond(now - days * 86400)
        val to = Instant.ofEpochSecond(now + 86400)

        fun dayOf(i: Instant) = java.time.LocalDate.ofInstant(i, zone)

        var delSteps = 0
        var delKcal = 0
        var dupDays = 0

        // --- 步数 ---
        val steps = HcQuery.readAll<androidx.health.connect.client.records.StepsRecord>(client, from, to, cap = 50000)
        val foreignStepDays = steps.filter { it.metadata.dataOrigin.packageName != ownPackage }
            .map { dayOf(it.startTime) }.toSet()
        val mineSteps = steps.filter { it.metadata.dataOrigin.packageName == ownPackage }
        val doomedSteps = mineSteps.filter { dayOf(it.startTime) in foreignStepDays }.map { it.metadata.id }
        for (chunk in doomedSteps.chunked(200)) {
            client.deleteRecords(androidx.health.connect.client.records.StepsRecord::class, chunk, emptyList())
            delSteps += chunk.size
            kotlinx.coroutines.delay(60)
        }

        // --- 活动卡路里 ---
        val kcals = HcQuery.readAll<androidx.health.connect.client.records.ActiveCaloriesBurnedRecord>(
            client, from, to, cap = 50000
        )
        val foreignKcalDays = kcals.filter { it.metadata.dataOrigin.packageName != ownPackage }
            .map { dayOf(it.startTime) }.toSet()
        val doomedKcal = kcals.filter { it.metadata.dataOrigin.packageName == ownPackage && dayOf(it.startTime) in foreignKcalDays }
            .map { it.metadata.id }
        for (chunk in doomedKcal.chunked(200)) {
            client.deleteRecords(androidx.health.connect.client.records.ActiveCaloriesBurnedRecord::class, chunk, emptyList())
            delKcal += chunk.size
            kotlinx.coroutines.delay(60)
        }

        dupDays = foreignStepDays.intersect(mineSteps.map { dayOf(it.startTime) }.toSet()).size
        return Triple(delSteps, delKcal, dupDays)
    }


    /**
     * 体重（云 `weight` 序列，含体成分但这里只写体重）。
     *
     * 为什么由我们写：实测**小米运动健康不往 HC 写体重**（设备 HC 里 weight=0），
     * 而云端有 28 次体称记录（小米体重秤）。同 ts（±60s）已有记录就跳过，重复写也幂等。
     */
    suspend fun insertWeights(
        client: HealthConnectClient,
        samples: List<Pair<Long, Double>>, // (epoch, kg)
        zone: ZoneId = ZoneId.systemDefault()
    ): Int {
        if (samples.isEmpty()) return 0
        val now = System.currentTimeMillis() / 1000
        val existing = HcQuery.readAll<androidx.health.connect.client.records.WeightRecord>(
            client,
            Instant.ofEpochSecond(samples.minOf { it.first } - 3600),
            Instant.ofEpochSecond(minOf(now + 3600, samples.maxOf { it.first } + 3600)),
            cap = 5000
        ).map { it.time.epochSecond }.toSet()
        val recs = samples.distinctBy { it.first }
            .filter { (t, _) -> existing.none { kotlin.math.abs(it - t) <= 60 } }
            .map { (t, kg) ->
                val i = Instant.ofEpochSecond(t)
                androidx.health.connect.client.records.WeightRecord(
                    time = i,
                    zoneOffset = zone.rules.getOffset(i),
                    weight = androidx.health.connect.client.units.Mass.kilograms(kg),
                    metadata = Metadata.autoRecordedWithId("hc-cloud-weight-$t", device)
                )
            }
        for (chunk in recs.chunked(200)) { client.insertRecords(chunk) }
        return recs.size
    }


    /** 体脂率（云 weight 记录里的 body_fat_rate，来自小米体称）。同 ts ±60s 已有则跳过。 */
    suspend fun insertBodyFat(
        client: HealthConnectClient,
        samples: List<Pair<Long, Double>>, // (epoch, %)
        zone: ZoneId = ZoneId.systemDefault()
    ): Int {
        if (samples.isEmpty()) return 0
        val existing = HcQuery.readAll<androidx.health.connect.client.records.BodyFatRecord>(
            client,
            Instant.ofEpochSecond(samples.minOf { it.first } - 3600),
            Instant.ofEpochSecond(samples.maxOf { it.first } + 3600),
            cap = 5000
        ).map { it.time.epochSecond }.toSet()
        val recs = samples.distinctBy { it.first }
            .filter { (t, pct) -> pct in 1.0..70.0 && existing.none { kotlin.math.abs(it - t) <= 60 } }
            .map { (t, pct) ->
                val i = Instant.ofEpochSecond(t)
                androidx.health.connect.client.records.BodyFatRecord(
                    time = i,
                    zoneOffset = zone.rules.getOffset(i),
                    percentage = androidx.health.connect.client.units.Percentage(pct),
                    metadata = Metadata.autoRecordedWithId("hc-cloud-body-fat-$t", device)
                )
            }
        for (chunk in recs.chunked(200)) client.insertRecords(chunk)
        return recs.size
    }


    /**
     * 体成分其余维度：基础代谢 / 骨量 / 去脂体重 / 体水分 / 身高。
     *
     * 小米体秤 payload 里都有实测值（basal_metabolism=1571、bone_mass=3.0、fat_free_body=55.6、
     * body_moisture_mass=40.2），以前只写了体重和体脂，这些 HC 支持的 record 一直空着。
     * 同 ts±60s 已有记录则跳过（幂等）。
     */
    data class BodyExtra(
        val t: Long,
        val basalKcalPerDay: Double?,
        val boneKg: Double?,
        val leanKg: Double?,
        val waterKg: Double?,
        val heightM: Double? = null
    )

    suspend fun insertBodyExtras(
        client: HealthConnectClient,
        items: List<BodyExtra>,
        zone: ZoneId = ZoneId.systemDefault()
    ): Map<String, Int> {
        val scale = Device(Device.TYPE_SCALE, "Xiaomi", "Mi Body Composition Scale 2")
        val out = LinkedHashMap<String, Int>()
        if (items.isEmpty()) return out
        val lo = Instant.ofEpochSecond(items.minOf { it.t } - 3600)
        val hi = Instant.ofEpochSecond(minOf(System.currentTimeMillis() / 1000 + 3600, items.maxOf { it.t } + 3600))

        val hasBasal = runCatching {
            HcQuery.readAll<BasalMetabolicRateRecord>(client, lo, hi, cap = 5000)
                .map { it.time.epochSecond }.toSet()
        }.getOrDefault(emptySet())
        val hasBone = runCatching {
            HcQuery.readAll<BoneMassRecord>(client, lo, hi, cap = 5000).map { it.time.epochSecond }.toSet()
        }.getOrDefault(emptySet())
        val hasLean = runCatching {
            HcQuery.readAll<LeanBodyMassRecord>(client, lo, hi, cap = 5000).map { it.time.epochSecond }.toSet()
        }.getOrDefault(emptySet())
        val hasWater = runCatching {
            HcQuery.readAll<BodyWaterMassRecord>(client, lo, hi, cap = 5000).map { it.time.epochSecond }.toSet()
        }.getOrDefault(emptySet())
        val hasHeight = runCatching {
            HcQuery.readAll<HeightRecord>(client, lo, hi, cap = 5000).map { it.time.epochSecond }.toSet()
        }.getOrDefault(emptySet())

        fun fresh(t: Long, set: Set<Long>) = set.none { kotlin.math.abs(it - t) <= 60 }

        val basal = items.filter { it.basalKcalPerDay != null && fresh(it.t, hasBasal) }.map {
            BasalMetabolicRateRecord(
                time = Instant.ofEpochSecond(it.t),
                zoneOffset = zone.rules.getOffset(Instant.ofEpochSecond(it.t)),
                basalMetabolicRate = Power.kilocaloriesPerDay(it.basalKcalPerDay!!),
                metadata = Metadata.autoRecordedWithId("hc-cloud-bmr-${it.t}", scale)
            )
        }
        val bone = items.filter { it.boneKg != null && fresh(it.t, hasBone) }.map {
            BoneMassRecord(
                time = Instant.ofEpochSecond(it.t),
                zoneOffset = zone.rules.getOffset(Instant.ofEpochSecond(it.t)),
                mass = Mass.kilograms(it.boneKg!!),
                metadata = Metadata.autoRecordedWithId("hc-cloud-bone-${it.t}", scale)
            )
        }
        val lean = items.filter { it.leanKg != null && fresh(it.t, hasLean) }.map {
            LeanBodyMassRecord(
                time = Instant.ofEpochSecond(it.t),
                zoneOffset = zone.rules.getOffset(Instant.ofEpochSecond(it.t)),
                mass = Mass.kilograms(it.leanKg!!),
                metadata = Metadata.autoRecordedWithId("hc-cloud-lean-${it.t}", scale)
            )
        }
        val water = items.filter { it.waterKg != null && fresh(it.t, hasWater) }.map {
            BodyWaterMassRecord(
                time = Instant.ofEpochSecond(it.t),
                zoneOffset = zone.rules.getOffset(Instant.ofEpochSecond(it.t)),
                mass = Mass.kilograms(it.waterKg!!),
                metadata = Metadata.autoRecordedWithId("hc-cloud-water-${it.t}", scale)
            )
        }
        val heights = items.filter { it.heightM != null && fresh(it.t, hasHeight) }.map {
            HeightRecord(
                time = Instant.ofEpochSecond(it.t),
                zoneOffset = zone.rules.getOffset(Instant.ofEpochSecond(it.t)),
                height = Length.meters(it.heightM!!),
                metadata = Metadata.autoRecordedWithId("hc-cloud-height-${it.t}", scale)
            )
        }
        for ((name, recs) in listOf(
            "basal" to basal, "bone" to bone, "lean" to lean, "water" to water, "height" to heights
        )) {
            if (recs.isEmpty()) { out[name] = 0; continue }
            for (chunk in recs.chunked(200)) {
                runCatching { client.insertRecords(chunk) }
                    .onFailure { out[name] = -1 }
            }
            out[name] = if (out[name] == -1) -1 else recs.size
        }
        return out
    }

    /** 呼吸率（睡眠夜间均值的采样点）。 */
    suspend fun insertRespiratoryRate(
        client: HealthConnectClient,
        samples: List<Pair<Long, Double>>,
        zone: ZoneId = ZoneId.systemDefault()
    ): Int {
        if (samples.isEmpty()) return 0
        val lo = Instant.ofEpochSecond(samples.minOf { it.first } - 3600)
        val hi = Instant.ofEpochSecond(samples.maxOf { it.first } + 3600)
        val existing = runCatching {
            HcQuery.readAll<RespiratoryRateRecord>(client, lo, hi, cap = 5000)
                .map { it.time.epochSecond }.toSet()
        }.getOrDefault(emptySet())
        val recs = samples.distinctBy { it.first }
            .filter { (t, r) -> r in 4.0..40.0 && existing.none { kotlin.math.abs(it - t) <= 60 } }
            .map { (t, r) ->
                val i = Instant.ofEpochSecond(t)
                RespiratoryRateRecord(
                    time = i,
                    zoneOffset = zone.rules.getOffset(i),
                    rate = r,
                    metadata = Metadata.autoRecordedWithId("hc-cloud-rr-$t", device)
                )
            }
        for (chunk in recs.chunked(200)) client.insertRecords(chunk)
        return recs.size
    }

    /** HRV（RMSSD，毫秒）：小米睡眠 payload 的 avg_hrv / hrv_median。 */
    suspend fun insertHrv(
        client: HealthConnectClient,
        samples: List<Pair<Long, Double>>,
        zone: ZoneId = ZoneId.systemDefault()
    ): Int {
        if (samples.isEmpty()) return 0
        val lo = Instant.ofEpochSecond(samples.minOf { it.first } - 3600)
        val hi = Instant.ofEpochSecond(samples.maxOf { it.first } + 3600)
        val existing = runCatching {
            HcQuery.readAll<HeartRateVariabilityRmssdRecord>(client, lo, hi, cap = 5000)
                .map { it.time.epochSecond }.toSet()
        }.getOrDefault(emptySet())
        val recs = samples.distinctBy { it.first }
            .filter { (t, v) -> v in 1.0..300.0 && existing.none { kotlin.math.abs(it - t) <= 60 } }
            .map { (t, v) ->
                val i = Instant.ofEpochSecond(t)
                HeartRateVariabilityRmssdRecord(
                    time = i,
                    zoneOffset = zone.rules.getOffset(i),
                    heartRateVariabilityMillis = v,
                    metadata = Metadata.autoRecordedWithId("hc-cloud-hrv-$t", device)
                )
            }
        for (chunk in recs.chunked(200)) client.insertRecords(chunk)
        return recs.size
    }

    /** 血氧采样 */
    suspend fun insertSpo2(
        client: HealthConnectClient,
        samples: List<Pair<Long, Double>>,
        zone: ZoneId = ZoneId.systemDefault()
    ): Int {
        if (samples.isEmpty()) return 0
        val recs = samples.distinctBy { it.first }.map { (t, pct) ->
            val i = Instant.ofEpochSecond(t)
            androidx.health.connect.client.records.OxygenSaturationRecord(
                time = i,
                zoneOffset = zone.rules.getOffset(i),
                percentage = androidx.health.connect.client.units.Percentage(pct),
                metadata = Metadata.autoRecordedWithId("hc-cloud-spo2-$t", device)
            )
        }
        // 分批（HC 单次不宜过大）
        var n = 0
        for (chunk in recs.chunked(400)) {
            client.insertRecords(chunk)
            n += chunk.size
            kotlinx.coroutines.delay(80)
        }
        return n
    }

    /** 步数（按天，整段覆盖当天）；副设备从服务端按天序列写入用。 */
    suspend fun insertDailySteps(
        client: HealthConnectClient,
        days: List<Triple<Long, Long, Double>>, // (dayStart, steps, distanceM)
        zone: ZoneId = ZoneId.systemDefault()
    ): Int {
        if (days.isEmpty()) return 0
        val steps = ArrayList<androidx.health.connect.client.records.StepsRecord>()
        val dist = ArrayList<androidx.health.connect.client.records.DistanceRecord>()
        for ((startTs, count, meters) in days) {
            val s0 = Instant.ofEpochSecond(startTs)
            val dayEnd = startTs + 86400
            val nowTs = System.currentTimeMillis() / 1000
            val endTs = if (nowTs in (startTs + 1) until dayEnd) nowTs else dayEnd
            val e0 = Instant.ofEpochSecond(endTs)
            if (count > 0) steps.add(
                androidx.health.connect.client.records.StepsRecord(
                    startTime = s0, startZoneOffset = zone.rules.getOffset(s0),
                    endTime = e0, endZoneOffset = zone.rules.getOffset(e0),
                    count = count,
                    metadata = Metadata.autoRecordedWithId("hc-cloud-steps-day-$startTs", device)
                )
            )
            if (meters > 0) dist.add(
                androidx.health.connect.client.records.DistanceRecord(
                    startTime = s0, startZoneOffset = zone.rules.getOffset(s0),
                    endTime = e0, endZoneOffset = zone.rules.getOffset(e0),
                    distance = androidx.health.connect.client.units.Length.meters(meters),
                    metadata = Metadata.autoRecordedWithId("hc-cloud-dist-day-$startTs", device)
                )
            )
        }
        var n = 0
        for (chunk in steps.chunked(200)) { client.insertRecords(chunk); n += chunk.size }
        // 距离可能没有 WRITE_DISTANCE 权限：单独吞掉，别把步数一起弄失败
        runCatching { for (chunk in dist.chunked(200)) client.insertRecords(chunk) }
        return n
    }

    /** 步数（按小时聚合；只用于"HC 该天为 0"的补洞，避免与小米自身写入重复计入） */
    suspend fun insertSteps(
        client: HealthConnectClient,
        hourly: List<Triple<Long, Long, Double>>, // (hourStart, steps, distanceM)
        zone: ZoneId = ZoneId.systemDefault()
    ): Int {
        if (hourly.isEmpty()) return 0
        val stepRecs = ArrayList<androidx.health.connect.client.records.StepsRecord>()
        val distRecs = ArrayList<androidx.health.connect.client.records.DistanceRecord>()
        for ((h, steps, distM) in hourly) {
            val s = Instant.ofEpochSecond(h)
            val e = Instant.ofEpochSecond(h + 3600)
            if (steps > 0) stepRecs.add(
                androidx.health.connect.client.records.StepsRecord(
                    startTime = s, startZoneOffset = zone.rules.getOffset(s),
                    endTime = e, endZoneOffset = zone.rules.getOffset(e),
                    count = steps,
                    metadata = Metadata.autoRecordedWithId("hc-cloud-steps-$h", device)
                )
            )
            if (distM > 0) distRecs.add(
                androidx.health.connect.client.records.DistanceRecord(
                    startTime = s, startZoneOffset = zone.rules.getOffset(s),
                    endTime = e, endZoneOffset = zone.rules.getOffset(e),
                    distance = androidx.health.connect.client.units.Length.meters(distM),
                    metadata = Metadata.autoRecordedWithId("hc-cloud-dist-$h", device)
                )
            )
        }
        for (chunk in stepRecs.chunked(400)) { client.insertRecords(chunk); kotlinx.coroutines.delay(60) }
        for (chunk in distRecs.chunked(400)) { client.insertRecords(chunk); kotlinx.coroutines.delay(60) }
        return stepRecs.size
    }

    /**
     * 大历史回补：分批写入（HC 单次请求不能无限大）。
     * 按 15 分钟桶 + clientRecordId 去重 ⇒ 重复回补幂等。
     */
    suspend fun insertHeartRateChunked(
        client: HealthConnectClient,
        samples: List<Pair<Long, Long>>,
        batch: Int = 400,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        zone: ZoneId = ZoneId.systemDefault()
    ): Pair<Int, Int> {
        if (samples.isEmpty()) return 0 to 0
        val buckets = LinkedHashMap<Long, MutableList<Pair<Long, Long>>>()
        for (s in samples) {
            val b = s.first / BUCKET * BUCKET
            buckets.getOrPut(b) { ArrayList() }.add(s)
        }
        val all = buckets.map { (bucket, list) ->
            val start = Instant.ofEpochSecond(bucket)
            val end = Instant.ofEpochSecond(maxOf(list.last().first + 60, bucket + 60))
            HeartRateRecord(
                startTime = start,
                startZoneOffset = zone.rules.getOffset(start),
                endTime = end,
                endZoneOffset = zone.rules.getOffset(end),
                samples = list.map {
                    HeartRateRecord.Sample(
                        time = Instant.ofEpochSecond(it.first),
                        beatsPerMinute = it.second
                    )
                },
                metadata = Metadata.autoRecordedWithId("hc-cloud-hr-$bucket", device)
            )
        }
        var done = 0
        for (chunk in all.chunked(batch)) {
            client.insertRecords(chunk)
            done += chunk.size
            onProgress(done, all.size)
            kotlinx.coroutines.delay(120)
        }
        return samples.size to all.size
    }
}
