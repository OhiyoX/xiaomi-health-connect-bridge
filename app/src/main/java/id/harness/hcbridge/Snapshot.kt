package id.harness.hcbridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import id.harness.hcbridge.logic.BodyMath
import id.harness.hcbridge.logic.SleepMath
import id.harness.hcbridge.logic.StageMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant

/**
 * 三视图共用的数据快照。
 *
 * 性能约定（切 tab 要立刻有内容）：
 * - 内存缓存两档：快档 45s（状态/24h 心率/今日汇总）、详档 180s（睡眠/血氧）
 * - 心率覆盖率持久化 6h（coverage 要扫全表，最贵）
 * - 四个查询并行发出，不再串行
 */
data class Snapshot(
    val hcOk: Boolean = false,
    val missingPerms: Int = 0,
    val hasCreds: Boolean = false,
    val status: JSONObject? = null,
    val hcRecords: Int = 0,
    val hcSamples: Int = 0,
    val hr: List<Pair<Long, Long>> = emptyList(),
    val spo2: List<Double> = emptyList(),
    val sleepWeek: List<SleepMath.Seg> = emptyList(),
    val sleepSessions: List<JSONObject> = emptyList(),
    val steps: Long = 0,
    val sleepMin: Double = 0.0,
    val hrAvg: Int? = null,
    val hrMin: Int? = null,
    val hrMax: Int? = null,
    val distanceKm: Double = 0.0,
    val kcal: Double = 0.0,
    val remote: Remote.Check? = null,
    // 体成分（详档）：最近一次测量 + 趋势序列
    val bodyLatest: BodyMath.Point? = null,
    val bodySeries: List<BodyMath.Point> = emptyList(),
    // 最近一晚睡眠分期（详档）
    val nightStages: StageMath.Summary? = null,
    // 夜间生理（详档）：呼吸率 / HRV
    val respRateLatest: Double? = null,
    val hrvLatest: Double? = null,
    val respRateSeries: List<Double> = emptyList(),
    val hrvSeries: List<Double> = emptyList(),
    val at: Long = 0L,
) {
    val cloudMaxTs: Long get() = status?.optLong("max_ts", 0L) ?: 0L
    val lastWriteTs: Long get() = status?.optLong("last_ts", 0L) ?: 0L
    val fails: Int get() = status?.optInt("fails", 0) ?: 0

    companion object {
        const val STEP_GOAL = 8000L
        private const val FAST_TTL = 45_000L
        private const val EXTRA_TTL = 180_000L
        private const val COVERAGE_TTL = 6 * 3600_000L

        private val PERMS = listOf(
            "android.permission.health.READ_HEART_RATE",
            "android.permission.health.WRITE_HEART_RATE",
            "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND",
        )
        @Volatile private var cache: Snapshot? = null
        @Volatile private var cacheAt = 0L
        @Volatile private var cacheExtraAt = 0L
        @Volatile private var cacheDeep = false
        @Volatile private var cacheMtime = -1L

        /** 已有快照先给 UI（可能略旧），由调用方自行决定是否再刷新。 */
        fun cached(): Snapshot? = cache

        suspend fun load(ctx: Context, wantDetails: Boolean = false): Snapshot {
            val now = System.currentTimeMillis()
            val mtime = SyncStatus.lastModified(ctx)
            val c = cache
            val fastFresh = c != null && now - cacheAt < FAST_TTL && mtime == cacheMtime
            val extraFresh = c != null && now - cacheExtraAt < EXTRA_TTL
            if (c != null && fastFresh && (!wantDetails || (cacheDeep && extraFresh))) return c

            val s = fetch(ctx, wantDetails || (cacheDeep && !extraFresh))
            cache = s
            cacheAt = System.currentTimeMillis()
            cacheExtraAt = cacheAt
            cacheDeep = wantDetails || cacheDeep
            cacheMtime = mtime
            return s
        }

        fun invalidate() {
            cacheAt = 0L
            cacheExtraAt = 0L
        }

        private suspend fun fetch(ctx: Context, needExtras: Boolean): Snapshot {
            val client = withContext(Dispatchers.IO) {
                runCatching { HealthConnectClient.getOrCreate(ctx) }.getOrNull()
            }
            val granted = client?.let {
                runCatching { it.permissionController.getGrantedPermissions() }.getOrNull()
            } ?: emptySet()
            val missing = PERMS.count { it !in granted }

            val status = withContext(Dispatchers.IO) {
                SyncStatus.read(ctx) ?: prefsStatus(ctx)
            }
            val remote = withContext(Dispatchers.IO) { Remote.check(Prefs.remoteUrl(ctx), Prefs.remoteToken(ctx)) }

            var base = Snapshot(
                hcOk = HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE,
                missingPerms = missing,
                hasCreds = Prefs.hasCreds(ctx),
                status = status,
                remote = remote,
                at = System.currentTimeMillis(),
            )
            if (client == null || missing > 0) return base

            // 覆盖率：先吃持久缓存
            val cov = Prefs.coverageCache(ctx)
            if (cov != null && System.currentTimeMillis() - cov.third < COVERAGE_TTL) {
                base = base.copy(hcRecords = cov.first, hcSamples = cov.second)
            }

            return withContext(Dispatchers.IO) {
                val nowMs = System.currentTimeMillis()
                val iso = { ms: Long -> Instant.ofEpochMilli(ms).toString() }
                coroutineScope {
                    val hrD = async {
                        runCatching {
                            val q = HcQuery.heartRate(client, iso(nowMs - 86400_000L), iso(nowMs), 20000)
                            val arr = q.optJSONArray("samples") ?: return@runCatching emptyList()
                            val list = ArrayList<Pair<Long, Long>>(arr.length())
                            for (i in 0 until arr.length()) {
                                val o = arr.optJSONObject(i) ?: continue
                                list.add(o.optLong("epoch") to o.optLong("bpm"))
                            }
                            list.sortedBy { it.first }
                        }.getOrDefault(emptyList())
                    }
                    val dayD = async {
                        runCatching { HcQuery.dailySummary(client, null) }
                            .onSuccess { android.util.Log.i("HCBridge", "diag: summary steps=${it.optLong("steps")} hrAvg=${it.opt("heart_rate_avg")}") }
                            .onFailure { android.util.Log.w("HCBridge", "diag: summary failed", it) }
                            .getOrNull()
                    }
                    val sleepD = async {
                        runCatching {
                            val q = HcQuery.sleep(client, iso(nowMs - 8 * 86400_000L), iso(nowMs))
                            val arr = q.optJSONArray("sessions") ?: return@runCatching null
                            val list = ArrayList<JSONObject>()
                            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list.add(it) }
                            list
                        }.getOrNull()
                    }
                    val spo2D = async {
                        if (!needExtras) null else runCatching {
                            val q = HcQuery.spo2(client, iso(nowMs - 86400_000L), iso(nowMs), 120)
                            val arr = q.optJSONArray("samples") ?: return@runCatching emptyList()
                            val list = ArrayList<Double>()
                            for (i in 0 until arr.length()) {
                                arr.optJSONObject(i)?.let { list.add(it.optDouble("percent")) }
                            }
                            list
                        }.onFailure { android.util.Log.w("HCBridge", "diag: spo2 read failed", it) }
                         .getOrDefault(emptyList())
                    }
                    val bodyD = async {
                        if (!needExtras) null else runCatching {
                            val q = HcQuery.bodyComposition(client, iso(nowMs - 1825 * 86400_000L), iso(nowMs))
                            val arr = q.optJSONArray("series") ?: return@runCatching null
                            val list = ArrayList<BodyMath.Point>(arr.length())
                            for (i in 0 until arr.length()) {
                                val o = arr.optJSONObject(i) ?: continue
                                val day = o.optString("date")
                                if (day.length < 10) continue
                                val t = Instant.parse(day + "T00:00:00+08:00").epochSecond
                                list.add(BodyMath.Point(
                                    t,
                                    o.optDouble("kg", 0.0),
                                    if (o.has("body_fat_pct")) o.optDouble("body_fat_pct") else null,
                                    if (o.has("bmi")) o.optDouble("bmi") else null
                                ))
                            }
                            list
                        }.onFailure { android.util.Log.w("HCBridge", "diag: body failed", it) }.getOrNull()
                    }
                    val stageD = async {
                        if (!needExtras) null else runCatching {
                            val q = HcQuery.sleepStages(client, iso(nowMs - 3 * 86400_000L), iso(nowMs))
                            val arr = q.optJSONArray("sessions") ?: return@runCatching null
                            val last = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.lastOrNull()
                                ?: return@runCatching null
                            val byStage = HashMap<Int, Long>()
                            val keys = last.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                val minutes = (last.opt(k) as? Number)?.toLong() ?: continue
                                val st = when (k) {
                                    "deep_minutes" -> StageMath.DEEP
                                    "light_minutes" -> StageMath.LIGHT
                                    "rem_minutes" -> StageMath.REM
                                    "awake_minutes" -> StageMath.AWAKE
                                    else -> null
                                } ?: continue
                                byStage[st] = minutes
                            }
                            StageMath.summarize(byStage, last.optLong("minutes", 0L))
                        }.onFailure { android.util.Log.w("HCBridge", "diag: stages failed", it) }.getOrNull()
                    }
                    val recD = async {
                        if (!needExtras) null else runCatching {
                            HcQuery.nightlyRecovery(client, 7)
                        }.onFailure { android.util.Log.w("HCBridge", "diag: recovery failed", it) }.getOrNull()
                    }
                    // 覆盖率缓存过期时补一次（不阻塞：放进同一批 await，但它是慢查询）
                    val needCov = cov == null || System.currentTimeMillis() - cov.third >= COVERAGE_TTL
                    val covD = async {
                        if (!needCov) null else runCatching {
                            // coverage 现在只给「近 30 天条数 + 首末时间」（整表读心率会 OOM，2026-10-22）
                            HcQuery.coverage(client).optJSONObject("heart_rate")?.let {
                                val n = it.optInt("records_30d", 0)
                                android.util.Log.i("HCBridge", "diag: coverage hr30d=$n first=${it.optString("first")}")
                                n to n
                            }
                        }.onFailure { android.util.Log.w("HCBridge", "diag: coverage failed", it) }.getOrNull()
                    }

                    val hr = hrD.await()
                    val day = dayD.await()
                    val sleepList = sleepD.await()
                    val spo2 = spo2D.await() ?: emptyList()
                    val newCov = covD.await()
                    val bodyList = bodyD.await()
                    val stages = stageD.await()
                    val recovery = recD.await()

                    val stepsAgg = day?.optLong("steps") ?: 0L
                    val stepsDirect = runCatching { HcQuery.stepsToday(client) }.getOrDefault(0L)
                    android.util.Log.i("HCBridge", "diag: steps agg=$stepsAgg direct=$stepsDirect")

                    var s = base.copy(
                        hr = hr,
                        steps = maxOf(stepsAgg, stepsDirect),
                        hrAvg = day?.opt("heart_rate_avg")?.toString()?.toIntOrNull(),
                        hrMin = day?.opt("heart_rate_min")?.toString()?.toIntOrNull(),
                        hrMax = day?.opt("heart_rate_max")?.toString()?.toIntOrNull(),
                        distanceKm = day?.optDouble("distance_km", 0.0) ?: 0.0,
                        kcal = day?.optDouble("calories_kcal", 0.0) ?: 0.0,
                        spo2 = spo2,
                    )

                    if (newCov != null) {
                        Prefs.setCoverageCache(ctx, newCov.first, newCov.second)
                        s = s.copy(hcRecords = newCov.first, hcSamples = newCov.second)
                    }

                    if (bodyList != null && bodyList.isNotEmpty()) {
                        s = s.copy(
                            bodySeries = BodyMath.dedupeByDay(bodyList, limit = 60),
                            bodyLatest = BodyMath.dedupeByDay(bodyList).lastOrNull()
                        )
                    }
                    if (stages != null) s = s.copy(nightStages = stages)
                    if (recovery != null) {
                        fun arrOf(key: String): List<Double> {
                            val a = recovery.optJSONArray(key) ?: return emptyList()
                            return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optDouble("value") }
                        }
                        s = s.copy(
                            respRateLatest = if (recovery.isNull("resp_rate_latest")) null
                                else recovery.optDouble("resp_rate_latest"),
                            hrvLatest = if (recovery.isNull("hrv_latest")) null
                                else recovery.optDouble("hrv_latest"),
                            respRateSeries = arrOf("resp_rate"),
                            hrvSeries = arrOf("hrv")
                        )
                    }


                    if (sleepList != null) {
                        // 同一天可能有多段（夜间 + 午睡）：按区间重叠去重（同源多写会交叉），再交给图表按天分列。
                        // 规则、纵轴映射与 tooltip 文案都在 logic/SleepMath（JVM 单测覆盖）。
                        var best = 0.0
                        val raw = ArrayList<SleepMath.Seg>(sleepList.size)
                        for (o in sleepList) {
                            val m = o.optDouble("minutes")
                            best = maxOf(best, m)
                            val st = minuteOfDay(o.optString("start"))
                            var en = minuteOfDay(o.optString("end"))
                            if (en <= st) en += 1440
                            raw.add(SleepMath.Seg(o.optString("start").take(10).takeLast(5), st, en))
                        }
                        val rows = SleepMath.dedupe(raw)
                        s = s.copy(
                            sleepSessions = sleepList.reversed(),
                            sleepWeek = SleepMath.recent(rows),
                            sleepMin = best,
                        )
                    }
                    s
                }
            }
        }

        /** 状态文件还没写出来时的兜底：Prefs 的 lastTs/fails/status 每次同步都会更新。 */
        private fun prefsStatus(ctx: Context): JSONObject? {
            val last = Prefs.lastTs(ctx)
            if (last <= 0L) return null
            return JSONObject()
                .put("state", Prefs.status(ctx))
                .put("count", 0)
                .put("max_ts", last)
                .put("last_ts", last)
                .put("role", Prefs.role(ctx))
                .put("fails", Prefs.fails(ctx))
        }

        /** "2026-10-17 23:14:00" / "...T23:14:00" → 1394.0 */
        private fun minuteOfDay(iso: String): Double {
            val t = when {
                ' ' in iso -> iso.substringAfter(' ')
                'T' in iso -> iso.substringAfter('T')
                else -> ""
            }
            if (t.length < 5) return 0.0
            val hh = t.substring(0, 2).toIntOrNull() ?: 0
            val mm = t.substring(3, 5).toIntOrNull() ?: 0
            return (hh * 60 + mm).toDouble()
        }
    }
}
