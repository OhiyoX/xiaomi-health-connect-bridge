package id.harness.hcbridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import id.harness.hcbridge.logic.HcPermissions
import id.harness.hcbridge.logic.NetFailure
import id.harness.hcbridge.logic.SleepMerge
import id.harness.hcbridge.logic.SleepStages
import id.harness.hcbridge.logic.UpstreamFreshness
import org.json.JSONObject
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * 后台自愈同步：WorkManager 每 15 分钟一次（persisted，重启后仍在）。
 *
 * 15 分钟是 Android 周期任务下限；再快没意义 —— 瓶颈在小米云的上传节奏
 * （实测：云里最新采样比当前时间落后 ≈ 1 小时）。
 *
 * 与旧链路（Termux 脚本 → am start Activity）相比，少两跳：
 *   - 不依赖 Termux 进程存活
 *   - 不触发后台启动 Activity 限制
 * 账号凭据存在 app 私有 SharedPreferences，**每次同步把服务端轮换的 passToken 回写**，
 * 所以只要同步在跑，token 就不会放坏。
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    /** 本轮的上游判定（`upstream` 字段），写状态文件时自动跟上 */
    private var upstreamExtra: JSONObject? = null

    companion object {
        const val TAG = "HCBridge"
        const val WORK_NAME = "hc-sync-periodic"
        private const val CHANNEL = "hc-sync"
        private const val NOTIF_ID = 4712
        private const val MAX_BACKFILL_DAYS = 30L
        /** 距上次成功写入不到这么久就跳过整轮（省电；周期任务本身已 ≥15 分钟） */
        private const val RECENT_SYNC_SKIP_S = 600L

        /** 睡眠空夜体检：只看最近多少天（云端宽窗口能查回去） */
        private const val DAYS = 30

        /** 催完上游后等多久再补拉（给它连手环 + 上传的时间） */
        private const val KICK_SETTLE_MS = 25_000L

        /** 睡眠记录元数据版本（见 Prefs.sleepMetaVersion） */
        private const val SLEEP_META_V = 3

        /** 上游告警通知的最小间隔（秒）：6 小时 */
        private const val UPSTREAM_NOTIFY_GAP_S = 6 * 3600L

        /** “部分权限缺失”告警的最小间隔（秒）：6 小时 */
        private const val PERM_NOTIFY_GAP_S = 6 * 3600L

        /** 空夜体检间隔（秒）：6 小时一次就够，不必每 15 分钟扫 */
        private const val GAP_CHECK_INTERVAL_S = 6 * 3600L

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = PeriodicWorkRequestBuilder<SyncWorker>(Prefs.interval(context).toLong(), TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        /**
         * 手动"立即同步"。
         *
         * force=true：跳过"距上次写入 <10 分钟就省电跳过"的判定——用户主动点了按钮，
         * 就该真的去拉一次云端（2026-10-21 实测：刚同步完再点，日志只有"跳过"，看着像按钮坏了）。
         */
        fun runNow(context: Context, force: Boolean = true) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(androidx.work.workDataOf("force" to force))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("hc-sync-now", ExistingWorkPolicy.REPLACE, req)
        }

        /** 历史回补：一次性拉 [days] 天（分批写 HC，幂等可重跑） */
        fun runBackfill(context: Context, days: Int) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(androidx.work.workDataOf("days" to days))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("hc-backfill", ExistingWorkPolicy.REPLACE, req)
        }

        /**
         * 一次性把云端体重/体脂补进 HC（指定天数，默认 1825 天）。
         * 单独入口：体重是稀疏数据（5 年 28 次），按天切片经常 0 条，需要能显式跑 + 看见错误。
         */
        fun runWeightImport(context: Context, days: Int = 1825) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(androidx.work.workDataOf("weightImportDays" to days))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("hc-weight-import", ExistingWorkPolicy.REPLACE, req)
        }

        /** 一次性把「夜间呼吸率 + HRV」补进 HC（小米 sleep payload 有值，HC 有对应 record）。 */
        fun runSleepExtrasImport(context: Context, days: Int = 365) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(androidx.work.workDataOf("sleepExtrasDays" to days))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("hc-sleep-extras", ExistingWorkPolicy.REPLACE, req)
        }

        /** 一次性清理步数/活动卡路里的历史重复（小米写过的那天我们不再留自己那份） */
        fun runActivityCleanup(context: Context, days: Int = 365) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(androidx.work.workDataOf("cleanupActivityDays" to days))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("hc-activity-cleanup", ExistingWorkPolicy.REPLACE, req)
        }

        /** 立即跑一次「睡眠空夜体检」（不受 6 小时间隔限制；结果写日志 + 状态文件） */
        fun runSleepGapCheck(context: Context, days: Int = 30) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(androidx.work.workDataOf("sleepGapNow" to true, "sleepGapDays" to days))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("hc-sleep-gap", ExistingWorkPolicy.REPLACE, req)
        }

        /**
         * 手动「唤醒上游」：不先看判定，立刻催一次小米运动健康 + 补拉云端。
         * 面板按钮 / 通知里的「唤醒」 / CMD_KICK / MCP `hc_kick` 都走这里。
         */
        fun runKick(context: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(androidx.work.workDataOf("kickNow" to true))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("hc-upstream-kick", ExistingWorkPolicy.REPLACE, req)
        }

        /** 催完等几分钟再补拉一次（连手环 + 上传不是秒级的）。 */
        private fun retrySoon(context: Context, minutes: Long) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(androidx.work.workDataOf("force" to true))
                .setInitialDelay(minutes, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("hc-upstream-retry", ExistingWorkPolicy.REPLACE, req)
        }

        /**
         * 一次性清理历史重复睡眠：own（本应用）与别的来源重叠的记录删掉。
         * 不需要网络与小米账号，只读/删 HC，所以不挂网络约束。
         */
        fun runSleepCleanup(context: Context, days: Int = 365) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(androidx.work.workDataOf("cleanupSleepDays" to days))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("hc-sleep-cleanup", ExistingWorkPolicy.REPLACE, req)
        }
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = Prefs.get(ctx)

        AppLog.log(ctx, "sync", "开始（上次写入 ${Prefs.lastTs(ctx)}，间隔 ${Prefs.interval(ctx)}min）")
        if (Prefs.role(ctx) == "secondary") {
            val (n, note) = SecondarySync.run(ctx)
            AppLog.log(ctx, "sync", "副设备模式：写入 $n 样本（$note）")
            // 状态文件由 SecondarySync 自己写（state=secondary-ok/secondary-failed + hint + notes）。
            // 这里再写一遍会把失败原因盖掉——2026-10-18 平板那次就是因为被盖成 "ok-no-new"，
            // 外面只能看到一个“正常”的假象。
            SyncHistory.add(ctx, if (n > 0) "secondary" else "no_new", n, 0)
            if (n == 0 && note.contains("失败")) {
                notify("HC Bridge 副机同步失败", note.take(180))
            }
            return Result.success()
        }

        val creds = Prefs.creds(ctx)
        if (creds == null) {
            AppLog.log(ctx, "sync", "失败：未配置账号")
            // 早退也要落状态：否则面板只剩一句“云端未取到”，看不出是没登录（新装后就会撞上）
            writeStatusFile("no-account", 0, Prefs.lastTs(ctx))
            notify("HC Bridge 未配置账号", "先在 Termux 里执行 hc-bridge-bootstrap 导入小米账号凭据")
            return Result.failure()
        }

        val status = HealthConnectClient.getSdkStatus(ctx)
        if (status != HealthConnectClient.SDK_AVAILABLE) {
            return retryOrFail("Health Connect 不可用（$status）")
        }
        val client = HealthConnectClient.getOrCreate(ctx)

        // 权限自检（HC 会在长期不用时回收权限，Android 15 也会自动回收闲置的运行时权限）
        val granted = runCatching { client.permissionController.getGrantedPermissions() }.getOrElse { emptySet() }
        val need = HcPermissions.core
        if (!granted.containsAll(need)) {
            val msg = "HC 心率读写权限被回收（granted=${need.count { it in granted }}/${need.size}）"
            Prefs.setStatus(ctx, msg)
            AppLog.log(ctx, "sync", "失败：$msg")
            SyncHistory.add(ctx, "no_permission", 0, 0, "", msg)
            // 通知点进去就能恢复（FixActivity 里一次点击请求权限）
            notify("HC 权限需要恢复", "$msg —— 点这里恢复（一次授权，随后自动续跑）", FixActivity::class.java)
            writeStatusFile(msg, 0, Prefs.lastTs(ctx))
            return retryOrFail(msg)
        }
        // 其它类型（睡眠/步数/血氧/体成分…）缺权限时心率还能跑，但那几类的写入会被 HC 静默拒绝
        // —— 2026-10-28 实测：WRITE_SLEEP 被回收，日志只有 `sleepErr=HealthConnectException`，
        //    外面看还是 ok-no-new。所以这里必须把它当“待处理”报出来，不能吞。
        val missOther = HcPermissions.missing(granted)
        if (missOther.isNotEmpty()) {
            val desc = HcPermissions.describe(missOther)
            val nowS = System.currentTimeMillis() / 1000
            Prefs.setStatus(ctx, "权限缺 ${missOther.size} 项：$desc（这几类会写不进 HC）")
            AppLog.log(ctx, "perm", "HC 权限缺 ${missOther.size} 项：$desc")
            SyncHistory.add(ctx, "partial_permission", 0, 0, "", desc)
            if (nowS - Prefs.permNotifiedAt(ctx) > PERM_NOTIFY_GAP_S) {
                Prefs.setPermNotifiedAt(ctx, nowS)
                notify(
                    "HC 权限缺 ${missOther.size} 项",
                    "$desc —— 缺的这几类数据写不进去（睡眠/步数/血氧等）。点这里一次授权，随后自动补写",
                    FixActivity::class.java
                )
            }
        }

        // 0b) 一次性清理步数/活动卡路里的历史重复
        val cleanActivityDays = inputData.getInt("cleanupActivityDays", 0)
        if (cleanActivityDays > 0) {
            return try {
                val (ds, dk, dd) = HcWriter.cleanupDuplicateActivity(
                    client, cleanActivityDays.toLong(), ctx.packageName
                )
                val s = "activity-cleanup：删步数 $ds 条 / 卡路里 $dk 条（重复天 ${dd}）"
                Prefs.setStatus(ctx, s)
                AppLog.log(ctx, "activity-cleanup", s)
                SyncHistory.add(ctx, "activity_cleanup", ds + dk, 0, "${cleanActivityDays}d")
                writeStatusFile(
                    "ok-activity-cleanup", ds + dk, Prefs.lastTs(ctx),
                    JSONObject().put("steps", ds).put("kcal", dk).put("dup_days", dd)
                )
                Result.success()
            } catch (e: Throwable) {
                Log.w(TAG, "活动去重失败", e)
                AppLog.log(ctx, "activity-cleanup", "异常：${e.message}")
                retryOrFail(e.message ?: "activity-cleanup failed")
            }
        }

        // 0) 一次性清理历史重复睡眠（不碰小米云，只要 HC 睡眠读写权限）
        val cleanupDays = inputData.getInt("cleanupSleepDays", 0)
        if (cleanupDays > 0) {
            val needSleep = listOf(
                "android.permission.health.READ_SLEEP",
                "android.permission.health.WRITE_SLEEP"
            )
            if (!granted.containsAll(needSleep)) {
                val msg = "睡眠去重需要 HC 睡眠读写权限（granted=${needSleep.count { it in granted }}/2）"
                Prefs.setStatus(ctx, msg)
                AppLog.log(ctx, "sleep-cleanup", "失败：$msg")
                writeStatusFile("sleep-cleanup-failed", 0, Prefs.lastTs(ctx))
                notify("HC 睡眠权限需要恢复", "$msg —— 点这里恢复", FixActivity::class.java)
                return retryOrFail(msg)
            }
            return try {
                val r = HcWriter.cleanupSleepDuplicates(client, cleanupDays.toLong(), ctx.packageName)
                val s = "sleep-cleanup：删除 ${r.deleted} 条重复睡眠（窗口 ${cleanupDays}d）"
                Prefs.setStatus(ctx, s)
                AppLog.log(ctx, "sleep-cleanup", s)
                SyncHistory.add(ctx, "sleep_cleanup", 0, r.deleted, "${cleanupDays}d")
                writeStatusFile(
                    "ok-sleep-cleanup", r.deleted, Prefs.lastTs(ctx),
                    JSONObject().put("deleted", r.deleted).put("window_days", cleanupDays)
                )
                clearNotification()
                Result.success()
            } catch (e: Throwable) {
                Log.w(TAG, "睡眠去重失败", e)
                AppLog.log(ctx, "sleep-cleanup", "异常：${e.message}")
                writeStatusFile("error: sleep-cleanup ${e.message}", 0, Prefs.lastTs(ctx))
                retryOrFail(e.message ?: "sleep-cleanup failed")
            }
        }

        val forceGap = inputData.getBoolean("sleepGapNow", false)
        val gapDays = inputData.getInt("sleepGapDays", DAYS)
        val now = System.currentTimeMillis() / 1000
        val last = Prefs.lastTs(ctx)
        val backfillDays = inputData.getInt("days", 0)
        val force = inputData.getBoolean("force", false)
        if (!force && !forceGap && backfillDays == 0 && last > 0 && (now - last) < RECENT_SYNC_SKIP_S && Prefs.fails(ctx) == 0) {
            AppLog.log(ctx, "sync", "跳过：距上次写入仅 ${now - last}s（省电）")
            return Result.success()
        }
        val from = if (backfillDays > 0) {
            now - backfillDays.toLong() * 86400
        } else {
            // 至少回溯 2 天：小米云对窄窗口（几小时）会返回空，宽窗口才稳定
            val gapFrom = if (last > 0) last - 60 else now - 2 * 86400
            minOf(gapFrom, now - 2 * 86400)
        }

        return try {
            // 1) 登录（拿轮换后的 passToken / ssecurity / cookie）
            val session = CloudApi.login(creds.first, creds.second)
            if (session.passToken != creds.second) {
                Prefs.savePassToken(ctx, session.passToken)
                Log.i(TAG, "passToken 已轮换并回写")
            }

            // 1a) 一次性导入云端体重/体脂（稀疏数据：5 年只有 28 次体称，必须显式跑 + 看得见结果）
            val weightImportDays = inputData.getInt("weightImportDays", 0)
            if (weightImportDays > 0) {
                val zone = java.time.ZoneId.systemDefault()
                val wFrom = now - weightImportDays.toLong() * 86400
                // 小米云对单次查询的时间跨度有上限（实测 >1 年只回最近一年），必须切窗
                val raw = ArrayList<Pair<Long, JSONObject>>()
                var wc = wFrom
                while (wc < now) {
                    val we = minOf(wc + 180L * 86400, now)
                    raw += CloudApi.fetchValues(session, "weight", wc, we)
                    wc = we + 1
                    kotlinx.coroutines.delay(200)
                }
                val samples = raw.mapNotNull { (t, v) ->
                    val kg = v.optDouble("weight", 0.0).takeIf { it > 0 }
                        ?: (v.optDouble("fat_free_body", 0.0) + v.optDouble("fat_mass", 0.0)).takeIf { it > 0 }
                        ?: 0.0
                    if (kg in 20.0..300.0) t to kg else null
                }
                val fat = raw.mapNotNull { (t, v) ->
                    val pct = v.optDouble("body_fat_rate", 0.0)
                    if (pct in 1.0..70.0) t to pct else null
                }
                // 顺便用 (kg, bmi) 反推身高，供 BMI 口径使用（HC 一般没有 HeightRecord）
                if (Prefs.heightM(ctx) == null) {
                    val hs = raw.mapNotNull { (_, v) ->
                        val kg = v.optDouble("weight", 0.0)
                        val bmi = v.optDouble("bmi", 0.0)
                        if (kg > 20 && bmi > 10) Math.sqrt(kg / bmi) else null
                    }.filter { it in 1.0..2.5 }.sorted()
                    if (hs.isNotEmpty()) Prefs.setHeightM(ctx, hs[hs.size / 2])
                }
                val n = HcWriter.insertWeights(client, samples, zone)
                val nf = HcWriter.insertBodyFat(client, fat, zone)
                // 体成分其余维度（HC 支持但一直空着）：基础代谢/骨量/去脂体重/体水分/身高
                val hM = Prefs.heightM(ctx)
                val extras = raw.mapNotNull { (t, v) ->
                    fun pos(k: String) = v.optDouble(k, 0.0).takeIf { it > 0 }
                    HcWriter.BodyExtra(
                        t = t,
                        basalKcalPerDay = pos("basal_metabolism"),
                        boneKg = pos("bone_mass"),
                        leanKg = pos("fat_free_body"),
                        waterKg = pos("body_moisture_mass"),
                        heightM = hM
                    )
                }
                val ex = HcWriter.insertBodyExtras(client, extras, zone)
                val summary = "weight-import：云端 ${raw.size} 条 → 体重 $n / 体脂 $nf / " +
                    "体成分 ${ex.filterValues { it > 0 }}（窗口 ${weightImportDays}d）"
                Prefs.setStatus(ctx, summary)
                AppLog.log(ctx, "weight-import", summary)
                SyncHistory.add(ctx, "weight_import", n + nf, 0, "${weightImportDays}d")
                writeStatusFile("ok-weight-import", n + nf, Prefs.lastTs(ctx),
                    JSONObject().put("cloud", raw.size).put("weight", n).put("body_fat", nf))
                return Result.success()
            }

            // 1a2) 一次性导入「夜间呼吸率 + HRV」（睡眠 payload 的 avg_breath / avg_hrv）
            val sleepExtrasDays = inputData.getInt("sleepExtrasDays", 0)
            if (sleepExtrasDays > 0) {
                val zone = java.time.ZoneId.systemDefault()
                var cursor = now - sleepExtrasDays.toLong() * 86400
                var rr = 0
                var hrv = 0
                var nights = 0
                while (cursor < now) {
                    val sliceEnd = minOf(cursor + 180L * 86400, now)
                    val raw = CloudApi.fetchValues(session, "sleep", cursor, sliceEnd)
                    nights += raw.size
                    val rrS = raw.mapNotNull { (_, v) ->
                        val t = v.optLong("device_wake_up_time", 0).takeIf { it > 0 } ?: v.optLong("bedtime", 0)
                        val r = v.optDouble("avg_breath", 0.0)
                        if (t > 0 && r > 0) t to r else null
                    }
                    val hrvS = raw.mapNotNull { (_, v) ->
                        val t = v.optLong("device_wake_up_time", 0).takeIf { it > 0 } ?: v.optLong("bedtime", 0)
                        val h = v.optDouble("avg_hrv", 0.0).takeIf { it > 0 } ?: v.optDouble("hrv_median", 0.0)
                        if (t > 0 && h > 0) t to h else null
                    }
                    rr += HcWriter.insertRespiratoryRate(client, rrS, zone)
                    hrv += HcWriter.insertHrv(client, hrvS, zone)
                    cursor = sliceEnd + 1
                    kotlinx.coroutines.delay(300)
                }
                val summary = "sleep-extras：云端 $nights 晚 → 呼吸率 $rr / HRV $hrv（窗口 ${sleepExtrasDays}d）"
                Prefs.setStatus(ctx, summary)
                AppLog.log(ctx, "sleep-extras", summary)
                SyncHistory.add(ctx, "sleep_extras", rr + hrv, 0, "${sleepExtrasDays}d")
                writeStatusFile("ok-sleep-extras", rr + hrv, Prefs.lastTs(ctx),
                    JSONObject().put("nights", nights).put("resp_rate", rr).put("hrv", hrv))
                return Result.success()
            }

            // 1b) 历史回补：按 30 天切片，边拉边写（内存可控、可重跑）
            if (backfillDays > 0) {
                var cursor = now - backfillDays.toLong() * 86400
                var totalSamples = 0
                var totalRecords = 0
                var maxSeen = last
                while (cursor < now) {
                    val sliceEnd = minOf(cursor + 30L * 86400, now)
                    val slice = CloudApi.fetchHeartRate(session, cursor, sliceEnd)
                    if (slice.isNotEmpty()) {
                        val (n, r) = HcWriter.insertHeartRateChunked(
                            client, slice,
                            onProgress = { done, total ->
                                if (done % 2000 < 400) {
                                    writeStatusFile(
                                        "backfilling ${totalSamples + done} 采样 / ${totalRecords + done} 记录（窗口 ${backfillDays}d）",
                                        totalSamples + done, maxOf(maxSeen, slice.last().first)
                                    )
                                }
                            }
                        )
                        totalSamples += n
                        totalRecords += r
                        maxSeen = maxOf(maxSeen, slice.last().first)
                    }
                    // 同一窗口也搬 睡眠/血氧/步数缺口
                    val other = syncOtherTypes(client, session, cursor, sliceEnd)
                    if (other.isNotEmpty()) AppLog.log(ctx, "backfill", other)
                    cursor = sliceEnd + 1
                    Log.i(TAG, "回补进度：cursor=$cursor 累计 $totalSamples 采样 / $totalRecords 记录；$other")
                    kotlinx.coroutines.delay(300)
                }
                Prefs.setLastTs(ctx, maxSeen)
                Prefs.setFails(ctx, 0)
                Prefs.setStatus(ctx, "ok-backfill: $totalSamples 采样 / $totalRecords 记录")
                AppLog.log(ctx, "backfill", "完成：$totalSamples 采样 / $totalRecords 记录（窗口 ${backfillDays}d）")
                SyncHistory.add(ctx, "backfill", totalSamples, totalRecords, "${backfillDays}d")
                writeStatusFile("ok-backfill ${backfillDays}d", totalSamples, maxSeen)
                clearNotification()
                return Result.success()
            }

            // 1c) 手动「唤醒上游」（面板按钮 / 通知 / MCP hc_kick）：不看判定，直接催 + 补拉
            if (inputData.getBoolean("kickNow", false)) {
                val seen = HashMap<String, Long>()
                val other = syncOtherTypes(
                    client, session, now - 2 * 86400, now,
                    forceGap = true, gapDays = DAYS, seen = seen
                )
                val up = upstreamPass(client, session, seen, force = true)
                val state = "ok-kick: $up"
                Prefs.setStatus(ctx, state)
                AppLog.log(ctx, "upstream", "手动唤醒：$up（补拉 $other）")
                SyncHistory.add(ctx, "upstream_kick", 0, 0, up)
                writeStatusFile(state, 0, Prefs.lastTs(ctx))
                clearNotification()
                return Result.success()
            }

            // 2) 日常增量：拉心率
            val raw = CloudApi.fetchHeartRate(session, from, now)
            val fresh = raw.filter { it.first > last }
            if (fresh.isEmpty()) {
                Prefs.setFails(ctx, 0)
                Prefs.setStatus(ctx, "ok: 无新采样（窗口拉回 ${raw.size} 条）")
                AppLog.log(ctx, "sync", "无新采样（拉回 ${raw.size} 条，last=${last}）")
                val seen = HashMap<String, Long>()
                raw.lastOrNull()?.let { seen["heart_rate"] = it.first }
                val other = syncOtherTypes(client, session, from, now, forceGap, gapDays, seen)
                if (other.isNotEmpty()) AppLog.log(ctx, "sync", "其它类型：$other")
                val up = upstreamPass(client, session, seen, force = false)
                SyncHistory.add(ctx, "no_new", 0, 0, "窗口 ${raw.size} 条；$up")
                writeStatusFile(
                    "ok-no-new", 0, last,
                    JSONObject().put("from", from).put("to", now).put("fetched", raw.size)
                )
                clearNotification()
                return Result.success()
            }
            // 3) 写 HC —— 写**整个窗口**而不是只写新样本：
            //    record 的 clientRecordId 是按 15 分钟桶算的，HC 对同 id 是“替换”；
            //    若只盖新样本，会把同一个桶里较早的样本顶掉（实测总量会慢慢变少）。
            val (n, recs) = HcWriter.insertHeartRate(client, raw)
            val maxT = fresh.last().first
            Prefs.setLastTs(ctx, maxOf(last, maxT))
            Prefs.setFails(ctx, 0)
            Prefs.setStatus(ctx, "ok: 写入 $n 采样 / $recs 记录（新 ${fresh.size}）")
            val seen = HashMap<String, Long>()
            raw.lastOrNull()?.let { seen["heart_rate"] = it.first }
            val other = syncOtherTypes(client, session, from, now, forceGap, gapDays, seen)
            val up = upstreamPass(client, session, seen, force = false)
            AppLog.log(ctx, "sync", "成功：桶 $n 采样 / $recs 记录（新增 ${fresh.size}），max_t=$maxT；$other；$up")
            SyncHistory.add(ctx, "ok", fresh.size, recs, "max_t=$maxT")
            writeStatusFile("ok", fresh.size, maxT, JSONObject().put("inserted_samples", n))
            clearNotification()
            Log.i(TAG, "同步完成：桶 $n 采样 / $recs 记录（新增 ${fresh.size}），max_t=$maxT")
            Result.success()
        } catch (e: CloudApi.AuthException) {
            // 账号侧失效：不重试（重试也没用），直接报警提示重新导入
            Prefs.setStatus(ctx, "auth: ${e.message}")
            AppLog.log(ctx, "sync", "登录态失效：${e.message}")
            SyncHistory.add(ctx, "auth_failed", 0, 0, "", e.message ?: "")
            notify(
                "小米账号需要重新登录",
                "登录态失效：${e.message}。点这里显示二维码，扫码即恢复（不用终端）",
                LoginActivity::class.java
            )
            writeStatusFile("auth-failed", 0, last)
            Result.failure()
        } catch (e: Throwable) {
            // 环境问题（夜里掉网 / DNS 不解析 / 超时）不算失败：不累加 fails、不发通知、等下个周期。
            // 2026-10-29 实机：23:57→05:28 连续 5 轮 DNS 失败曾把 fails 顶到 5 并报警，纯噪音。
            if (NetFailure.isTransient(e)) {
                val why = NetFailure.reason(e)
                AppLog.log(ctx, "sync", "$why（本轮跳过，等下个周期）")
                SyncHistory.add(ctx, "net_wait", 0, 0, why)
                Prefs.setStatus(ctx, "net: $why")
                writeStatusFile("net-wait", 0, last)
                return Result.success()
            }
            Log.w(TAG, "同步失败", e)
            AppLog.log(ctx, "sync", "异常：${e.message}")
            SyncHistory.add(ctx, "error", 0, 0, "", e.message ?: "")
            Prefs.setStatus(ctx, "error: ${e.message}")
            writeStatusFile("error: ${e.message}", 0, last)
            retryOrFail(e.message ?: e.toString())
        }
    }

    /** 健康时不挂告警：清掉旧通知 */
    private fun clearNotification() {
        runCatching {
            applicationContext.getSystemService(NotificationManager::class.java)
                ?.cancel(NOTIF_ID)
        }
    }

    /**
     * 把云端的 睡眠 / 血氧 / 步数缺口 也搬进 HC。
     * 步数只补"HC 当天为 0"的日子 —— 平时小米运动健康自己会写，避免重复计入。
     */
    private suspend fun syncOtherTypes(
        client: HealthConnectClient,
        session: CloudApi.Session,
        from: Long,
        to: Long,
        forceGap: Boolean = false,
        gapDays: Int = DAYS,
        seen: MutableMap<String, Long>? = null
    ): String {
        val zone = java.time.ZoneId.systemDefault()
        var msg = ""
        // 睡眠
        runCatching {
            val raw = CloudApi.fetchValues(session, "sleep", from, to)
            track(seen, "sleep", raw)
            val sessions = raw.mapNotNull { (_, v) ->
                val bed = v.optLong("bedtime", 0)
                val wake = v.optLong("device_wake_up_time", 0).takeIf { it > 0 }
                    ?: (bed + v.optLong("sleep_duration", 0) * 60)
                if (bed > 0 && wake > bed) {
                    // 带上分期：没分期的睡眠 Google Health 会判“数据不足”（只写起止的话）
                    SleepMerge.Session(
                        bed, wake, title = "小米睡眠",
                        stages = SleepStages.fromXiaomiItems(v.optJSONArray("items"), bed, wake)
                    )
                } else null
            }
            val n = HcWriter.reconcileSleep(client, sessions, zone, applicationContext.packageName)
            // 夜间呼吸率与 HRV：HC 有对应 record 类型，小米云端也有值，以前没写
            runCatching {
                val rr = raw.mapNotNull { (_, v) ->
                    val t = v.optLong("device_wake_up_time", 0).takeIf { it > 0 }
                        ?: v.optLong("bedtime", 0)
                    val r = v.optDouble("avg_breath", 0.0)
                    if (t > 0 && r > 0) t to r else null
                }
                val hrv = raw.mapNotNull { (_, v) ->
                    val t = v.optLong("device_wake_up_time", 0).takeIf { it > 0 }
                        ?: v.optLong("bedtime", 0)
                    val h = v.optDouble("avg_hrv", 0.0).takeIf { it > 0 }
                        ?: v.optDouble("hrv_median", 0.0)
                    if (t > 0 && h > 0) t to h else null
                }
                val nrr = HcWriter.insertRespiratoryRate(client, rr, zone)
                val nh = HcWriter.insertHrv(client, hrv, zone)
                if (nrr > 0) msg += "respRate=$nrr "
                if (nh > 0) msg += "hrv=$nh "
            }.onFailure { msg += "sleepExtrasErr=${it.message?.take(40)} " }
            if (n.inserted > 0 || n.deleted > 0 || n.skippedForeign > 0 || n.rewriteStages > 0) {
                msg += "sleep=+${n.inserted}/-${n.deleted}" +
                    (if (n.rewriteStages > 0) "(补分期${n.rewriteStages})" else "") +
                    (if (n.skippedForeign > 0) "(小米已有${n.skippedForeign})" else "") + " "
            }        }.onFailure { if (msg.length < 100) msg += "sleepErr=${it.message?.take(40)} " }
        // 血氧
        runCatching {
            val raw = CloudApi.fetchValues(session, "spo2", from, to)
            track(seen, "spo2", raw)
            val samples = raw.mapNotNull { (t, v) ->
                val pct = v.optDouble("spo2", -1.0)
                if (pct in 50.0..100.0) t to pct else null
            }
            val n = HcWriter.insertSpo2(client, samples, zone)
            if (n > 0) msg += "spo2=$n "
        }.onFailure { if (msg.length < 100) msg += "spo2Err=${it.message?.take(40)} " }
        // 步数缺口
        runCatching {
            val raw = CloudApi.fetchValues(session, "steps", from, to)
            track(seen, "steps", raw)
            val byDay = raw.groupBy {
                java.time.LocalDate.ofInstant(java.time.Instant.ofEpochSecond(it.first), zone)
            }
            val hourly = ArrayList<Triple<Long, Long, Double>>()
            for ((day, items) in byDay) {
                val s = day.atStartOfDay(zone).toInstant()
                val e = day.plusDays(1).atStartOfDay(zone).toInstant()
                val hcSteps = client.aggregate(
                    androidx.health.connect.client.request.AggregateRequest(
                        setOf(androidx.health.connect.client.records.StepsRecord.COUNT_TOTAL),
                        androidx.health.connect.client.time.TimeRangeFilter.between(s, e)
                    )
                ).get(androidx.health.connect.client.records.StepsRecord.COUNT_TOTAL) ?: 0L
                if (hcSteps > 0) continue
                val perHour = HashMap<Long, Pair<Long, Double>>()
                for ((t, v) in items) {
                    val h = t / 3600 * 3600
                    val st = v.optLong("steps", 0)
                    val dm = v.optDouble("distance", 0.0)
                    val cur = perHour[h] ?: (0L to 0.0)
                    perHour[h] = (cur.first + st) to (cur.second + dm)
                }
                for ((h, pair) in perHour) if (pair.first > 0) hourly.add(Triple(h, pair.first, pair.second))
            }
            val n = HcWriter.insertSteps(client, hourly, zone)
            if (n > 0) msg += "stepsGapFill=$n "
        }.onFailure { if (msg.length < 100) msg += "stepsErr=${it.message?.take(40)} " }
        // 体重（小米 app 不往 HC 写体重，云端有体称记录；同 ts 已有的跳过）
        runCatching {
            val raw = CloudApi.fetchValues(session, "weight", from, to)
            track(seen, "weight", raw)
            val samples = raw.mapNotNull { (t, v) ->
                val kg = v.optDouble("weight", 0.0).takeIf { it > 0 }
                    ?: (v.optDouble("fat_free_body", 0.0) + v.optDouble("fat_mass", 0.0))
                        .takeIf { it > 0 } ?: 0.0
                if (kg in 20.0..300.0) t to kg else null
            }
            val n = HcWriter.insertWeights(client, samples, zone)
            msg += "weight=$n/${samples.size} "
            val fat = raw.mapNotNull { (t, v) ->
                val pct = v.optDouble("body_fat_rate", 0.0)
                if (pct in 1.0..70.0) t to pct else null
            }
            val nf = HcWriter.insertBodyFat(client, fat, zone)
            msg += "bodyFat=$nf/${fat.size} "
            val ex = HcWriter.insertBodyExtras(client, raw.mapNotNull { (t, v) ->
                fun pos(k: String) = v.optDouble(k, 0.0).takeIf { it > 0 }
                HcWriter.BodyExtra(
                    t = t, basalKcalPerDay = pos("basal_metabolism"), boneKg = pos("bone_mass"),
                    leanKg = pos("fat_free_body"), waterKg = pos("body_moisture_mass"),
                    heightM = Prefs.heightM(applicationContext)
                )
            }, zone)
            if (ex.values.any { it > 0 }) msg += "bodyExtras=${ex.filterValues { it > 0 }} "
        }.onFailure { msg += "weightErr=${it.message?.take(60)} " }
        // 活动卡路里缺口（云 calories = 活动消耗；同样只补"HC 当天为 0"的日子，避免与小米重复计入）
        runCatching {
            val raw = CloudApi.fetchValues(session, "calories", from, to)
            track(seen, "calories", raw)
            val byDay = raw.groupBy {
                java.time.LocalDate.ofInstant(java.time.Instant.ofEpochSecond(it.first), zone)
            }
            val hourly = ArrayList<Pair<Long, Double>>()
            for ((day, items) in byDay) {
                val s0 = day.atStartOfDay(zone).toInstant()
                val e0 = day.plusDays(1).atStartOfDay(zone).toInstant()
                val have = client.aggregate(
                    androidx.health.connect.client.request.AggregateRequest(
                        setOf(androidx.health.connect.client.records.ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                        androidx.health.connect.client.time.TimeRangeFilter.between(s0, e0)
                    )
                ).get(androidx.health.connect.client.records.ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
                    ?.inKilocalories ?: 0.0
                if (have > 0.5) continue
                val perHour = HashMap<Long, Double>()
                for ((t, v) in items) {
                    val h = t / 3600 * 3600
                    val kcal = v.optDouble("calories", 0.0)
                    if (kcal > 0) perHour[h] = (perHour[h] ?: 0.0) + kcal
                }
                for ((h, kcal) in perHour) hourly.add(h to kcal)
            }
            val n = HcWriter.insertActiveCalories(client, hourly, zone)
            if (n > 0) msg += "activeKcalGapFill=$n "
        }.onFailure { if (msg.length < 100) msg += "activeKcalErr=${it.message?.take(40)} " }
        // 反方向：把 HC 独有（云端没有）的 workout / weight 推给 NAS
        runCatching { HcPusher.push(applicationContext, 30) }
            .onSuccess { (n, note) -> if (n > 0) msg += "push=$n " else if (note.startsWith("推送失败")) msg += "pushErr " }
            .onFailure { if (msg.length < 100) msg += "pushErr=${it.message?.take(40)} " }
        // 睡眠空夜体检（不分来源：小米那份被删了也能自愈，详见 fillSleepGaps）
        runCatching { fillSleepGaps(client, session, forceGap, gapDays) }.onSuccess { if (it.isNotEmpty()) msg += "$it " }
            .onFailure { if (msg.length < 100) msg += "sleepGapErr=${it.message?.take(40)} " }
        return msg.trim()
    }

    /**
     * 记「这一轮各类型最新到哪、一共拉回多少条」—— 上游新鲜度判定要用，
     * 但不需要再向云端问一遍：[syncOtherTypes] 刚把这些数据拉回来，顺手记下就行。
     */
    private fun track(seen: MutableMap<String, Long>?, key: String, raw: List<Pair<Long, JSONObject>>) {
        val s = seen ?: return
        s["rows"] = (s["rows"] ?: 0L) + raw.size
        val max = raw.maxOfOrNull { it.first } ?: return
        s[key] = maxOf(s[key] ?: 0L, max)
    }

    /**
     * **上游主动同步**：小米运动健康没同步时，桥不再只是报 `ok-no-new`。
     *
     * 1. 用这一轮拉回的时间戳 + HC 里“昨晚有没有睡眠”判上游是否卡住（[UpstreamFreshness]）；
     * 2. 卡住了就静默催它（[UpstreamKick]：bind 它的设备服务 / 发屏幕变化广播），记下每招成没成；
     * 3. 等一会儿再补拉一遍云端（手环→云 要时间）—— 数据一到就落 HC，不需要人干预；
     * 4. 判定 + 催的结果写进状态文件（`upstream` 字段）与 app 日志；6 小时一次上限发通知，
     *    通知点一下直接开小米运动健康，带一个「唤醒」按钮（催不了就让人一指点开）。
     */
    private suspend fun upstreamPass(
        client: HealthConnectClient,
        session: CloudApi.Session,
        seen: Map<String, Long>,
        force: Boolean
    ): String {
        val ctx = applicationContext
        val zone = java.time.ZoneId.systemDefault()
        val nowS = System.currentTimeMillis() / 1000
        val hour = java.time.LocalTime.now(zone).hour
        val today = java.time.LocalDate.now(zone)

        // 昨晚在不在库里：只扫最近 3 天（便宜）；读不到就当作“有”，宁可不催也不误催
        val covered = runCatching {
            HcWriter.sleepingSessions(client, nowS - 3 * 86400, nowS)
                .any { SleepMerge.wakeDate(it.end, zone) == today }
        }.getOrDefault(true)

        val probe = UpstreamFreshness.Probe(
            nowS = nowS,
            hour = hour,
            lastHrTs = seen["heart_rate"] ?: Prefs.lastTs(ctx),
            lastStepsTs = seen["steps"] ?: 0L,
            lastSpo2Ts = seen["spo2"] ?: 0L,
            lastNightCovered = covered,
            cloudRows = (seen["rows"] ?: 0L).toInt()
        )
        val verdict = UpstreamFreshness.assess(probe, Prefs.lastKickTs(ctx))

        // 云端整段空（小米没上传/夜里掉网）时，先向 NAS 要一份：它自己有一份 1825 天回补。
        var serverNote = ""
        if (probe.cloudRows == 0) {
            serverNote = runCatching {
                val (n, note) = SecondarySync.pullWindow(ctx, nowS - 3 * 86400, nowS)
                "server兜底=写$n($note)"
            }.getOrElse { "server兜底失败:${it.message?.take(48)}" }
            AppLog.log(ctx, "upstream", "云端整段空 → $serverNote")
        }

        var kickSummary = ""
        var after = ""
        var finalVerdict = verdict
        if (verdict.needKick || force) {
            val res = UpstreamKick.kick(ctx)
            kickSummary = res.summary()
            Prefs.setUpstreamKick(ctx, nowS, kickSummary)
            AppLog.log(ctx, "upstream", "判定：${verdict.note}；已催（$kickSummary）")
            kotlinx.coroutines.delay(KICK_SETTLE_MS)
            val seen2 = HashMap<String, Long>()
            after = runCatching {
                syncOtherTypes(client, session, nowS - 2 * 86400, nowS, false, DAYS, seen2)
            }.getOrElse { "补拉失败:${it.message?.take(48)}" }
            if (after.isNotEmpty()) AppLog.log(ctx, "upstream", "催完补拉：$after")
            // 重新判一次：催完云端真到了就不再算过期（lastKickTs=刚催过 ⇒ 不会立即再催）
            val covered2 = runCatching {
                HcWriter.sleepingSessions(client, nowS - 3 * 86400, nowS)
                    .any { SleepMerge.wakeDate(it.end, zone) == today }
            }.getOrDefault(covered)
            finalVerdict = UpstreamFreshness.assess(
                probe.copy(
                    nowS = System.currentTimeMillis() / 1000,
                    lastNightCovered = covered2,
                    lastHrTs = maxOf(probe.lastHrTs, seen2["heart_rate"] ?: 0L),
                    lastStepsTs = maxOf(probe.lastStepsTs, seen2["steps"] ?: 0L),
                    lastSpo2Ts = maxOf(probe.lastSpo2Ts, seen2["spo2"] ?: 0L),
                    cloudRows = maxOf(probe.cloudRows, (seen2["rows"] ?: 0L).toInt())
                ),
                lastKickTs = nowS
            )
            // 过几分钟再补一次：连手环 + 上传往往比 25 秒慢得多
            retrySoon(ctx, 5)
        }

        val json = JSONObject()
            .put("hour", hour)
            .put("stale", org.json.JSONArray(finalVerdict.stale))
            .put("verdict", finalVerdict.note)
            .put("last_hr_ts", probe.lastHrTs)
            .put("last_steps_ts", probe.lastStepsTs)
            .put("last_spo2_ts", probe.lastSpo2Ts)
            .put("last_night_covered", covered)
            .put("cloud_rows", probe.cloudRows.toLong())
            .put("kicks_today", Prefs.kicksToday(ctx))
            .put("last_kick_ts", Prefs.lastKickTs(ctx))
        if (kickSummary.isNotEmpty()) json.put("kick", kickSummary)
        if (after.isNotEmpty()) json.put("after", after)
        if (serverNote.isNotEmpty()) json.put("server_pull", serverNote)
        Prefs.setUpstreamJson(ctx, json.toString())
        upstreamExtra = json

        if (verdict.needKick && !force && UpstreamFreshness.notifyWorthy(finalVerdict.stale) &&
            nowS - Prefs.upstreamNotifiedAt(ctx) > UPSTREAM_NOTIFY_GAP_S
        ) {
            Prefs.setUpstreamNotifiedAt(ctx, nowS)
            notifyUpstream(finalVerdict.stale, probe, kickSummary)
        }

        return if (kickSummary.isNotEmpty()) {
            "upstream=${finalVerdict.stale.joinToString("/")}(已催;${kickSummary})"
        } else {
            "upstream=fresh"
        }
    }

    /** 「上游没同步」的通知：点一下开小米运动健康，另带一个「唤醒」按钮（再催一次）。 */
    private fun notifyUpstream(
        stale: List<String>,
        probe: UpstreamFreshness.Probe,
        kickSummary: String
    ) {
        val ctx = applicationContext
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "HC 同步", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val what = stale.joinToString("、") { k ->
            when (k) {
                UpstreamFreshness.SLEEP -> "睡眠没落库"
                UpstreamFreshness.CLOUD_EMPTY -> "云端整段没数据"
                UpstreamFreshness.HEART_RATE ->
                    "心率停在 ${UpstreamFreshness.ageText(probe.nowS - probe.lastHrTs)}前"
                UpstreamFreshness.STEPS ->
                    "步数停在 ${UpstreamFreshness.ageText(probe.nowS - probe.lastStepsTs)}前"
                UpstreamFreshness.SPO2 ->
                    "血氧停在 ${UpstreamFreshness.ageText(probe.nowS - probe.lastSpo2Ts)}前"
                else -> UpstreamFreshness.label(k)
            }
        }
        val body = buildString {
            append("桥已主动催过小米运动健康（")
            append(if (kickSummary.isEmpty()) "无记录" else kickSummary)
            append("），还是：$what。\n点这里打开小米运动健康（或点「唤醒」再催一次）。")
        }
        val open = UpstreamKick.miLaunchIntent(ctx)
            ?: android.content.Intent(ctx, MainActivity::class.java)
        val pi = android.app.PendingIntent.getActivity(
            ctx, 2, open,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val wake = android.app.PendingIntent.getBroadcast(
            ctx, 3,
            android.content.Intent(ctx, CmdReceiver::class.java).setAction(CmdReceiver.ACTION_KICK),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = android.app.Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("小米运动健康没同步（$what）")
            .setContentText(body.lineSequence().first())
            .setStyle(android.app.Notification.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .addAction(android.app.Notification.Action.Builder(null, "唤醒", wake).build())
            .setAutoCancel(true)
        runCatching { mgr.notify(NOTIF_ID, builder.build()) }
        AppLog.log(ctx, "upstream", "发提醒：$what")
    }

    /**
     * 睡眠**空夜自愈**：扫 HC 最近 [DAYS] 天，只看“这一晚有没有记录”而不看是谁写的。
     *
     * 为什么需要：机内为了不重复，只保留一份（小米写过的我们就不写）。那万小米那份被它自己删了、
     * 或小米停写，就会真的变成一个空夜。这个体检每 [GAP_CHECK_INTERVAL_S] 跑一次，
     * 缺的夜从云端补 —— 于是“库里有且只有一份”与“hc-bridge 自己数据完整”可以同时成立。
     */
    private suspend fun fillSleepGaps(
        client: HealthConnectClient,
        session: CloudApi.Session,
        force: Boolean = false,
        days: Int = DAYS
    ): String {
        val now = System.currentTimeMillis() / 1000
        val lastCheck = Prefs.sleepGapCheckedAt(applicationContext)
        if (!force && lastCheck > 0 && now - lastCheck < GAP_CHECK_INTERVAL_S) return ""
        val zone = java.time.ZoneId.systemDefault()
        val existing = HcWriter.sleepingSessions(client, now - (days.toLong() + 2) * 86400, now)
        val missing = SleepMerge.missingNights(existing, zone, java.time.LocalDate.now(zone), days)
        // 另一类需要处理：**本应用写的、但没有分期的**那几晚（Google Health 会判“睡眠数据不足”）。
        // 小米写的那份我们不碰（删不了），但自己的可以删旧写新把分期补上。
        val lackingStages = existing
            .filter { it.origin == applicationContext.packageName && it.stages.isEmpty() }
            .map { SleepMerge.wakeDate(it.end, zone) }
            .distinct()
        // 第三类：元数据还是旧写法（device/title 跟小米不一样，Google 页面只显示时长不显示指标）。
        // 一次性重写一遍，跟小米的元数据对齐。
        val wantMeta = Prefs.sleepMetaVersion(applicationContext) < SLEEP_META_V
        val metaNights = if (wantMeta) {
            existing.filter { it.origin == applicationContext.packageName }
                .map { SleepMerge.wakeDate(it.end, zone) }.distinct()
        } else emptyList()
        val todo = (missing + lackingStages + metaNights).distinct().sorted()
        Prefs.setSleepGapCheckedAt(applicationContext, now)
        AppLog.log(
            applicationContext, "sleep-gap",
            "体检 ${days}d：库内 ${existing.size} 段，空夜 ${missing.size} 个，缺分期 ${lackingStages.size} 晚" +
                (if (wantMeta) "，元数据旧写法 ${metaNights.size} 晚" else "")
        )
        if (todo.isEmpty()) return "sleepGaps=0"
        // 只拉要处理的那几天（云端要宽窗口，前后各放一天）
        val from = todo.first().minusDays(1).atStartOfDay(zone).toEpochSecond()
        val to = minOf(now, todo.last().plusDays(2).atStartOfDay(zone).toEpochSecond())
        val raw = CloudApi.fetchValues(session, "sleep", from, to)
        val wanted = todo.toSet()
        val sessions = raw.mapNotNull { (_, v) ->
            val bed = v.optLong("bedtime", 0)
            val wake = v.optLong("device_wake_up_time", 0).takeIf { it > 0 }
                ?: (bed + v.optLong("sleep_duration", 0) * 60)
            if (bed > 0 && wake > bed) {
                SleepMerge.Session(
                    bed, wake, title = "小米睡眠",
                    stages = SleepStages.fromXiaomiItems(v.optJSONArray("items"), bed, wake)
                )
            } else null
        }.filter { SleepMerge.wakeDate(it.end, zone) in wanted }
        if (sessions.isEmpty()) {
            // 小米云那几天也没有 → 向 NAS 要（server 自己拉过 1825 天）
            val (n, note) = runCatching {
                SecondarySync.pullWindow(
                    applicationContext,
                    todo.first().minusDays(1).atStartOfDay(zone).toEpochSecond(),
                    minOf(now, todo.last().plusDays(2).atStartOfDay(zone).toEpochSecond())
                )
            }.getOrElse { 0 to "失败:${it.message?.take(40)}" }
            AppLog.log(applicationContext, "sleep-gap", "云端也没有 → server 兜底写 $n：$note")
            return "sleepGaps=${missing.size}（云端也没有；server写$n）"
        }
        val res = HcWriter.reconcileSleep(client, sessions, zone, applicationContext.packageName)
        if (wantMeta) Prefs.setSleepMetaVersion(applicationContext, SLEEP_META_V)
        AppLog.log(
            applicationContext, "sleep-gap",
            "空夜 ${missing.size} 个 → 补写 ${res.inserted} 条；缺分期 ${lackingStages.size} 晚 → 重写 ${res.rewriteStages} 条" +
                (if (wantMeta) "；元数据对齐 ${metaNights.size} 晚" else "")
        )
        return "sleepGaps=${missing.size}(补${res.inserted})" +
            (if (lackingStages.isNotEmpty()) " stages=+${res.rewriteStages}" else "")
    }

    private fun retryOrFail(msg: String): Result {
        val fails = Prefs.fails(applicationContext) + 1
        Prefs.setFails(applicationContext, fails)
        if (fails >= 3) {
            notify("HC 心率同步连续失败 $fails 次", msg)
        }
        return Result.retry()
    }

    private fun notify(title: String, body: String, target: Class<*>? = null) {
        val mgr = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "HC 同步", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val builder = android.app.Notification.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(android.app.Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
        if (target != null) {
            val pi = android.app.PendingIntent.getActivity(
                applicationContext, 1,
                android.content.Intent(applicationContext, target)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.setContentIntent(pi)
        }
        runCatching { mgr.notify(NOTIF_ID, builder.build()) }
    }

    /** 把状态写给 Termux/人看（实现搬到 SyncStatus，主/副机共用同一个文件） */
    private fun writeStatusFile(state: String, count: Int, maxT: Long, extra: JSONObject? = null) {
        val merged = extra ?: JSONObject()
        upstreamExtra?.let { merged.put("upstream", it) }
        SyncStatus.write(applicationContext, state, count, maxT, merged)
    }
}
