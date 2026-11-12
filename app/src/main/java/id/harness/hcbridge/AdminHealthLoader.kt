package id.harness.hcbridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import id.harness.hcbridge.logic.AdminHealthMap
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * 管理页的健康数据段（覆盖 / 睡眠 / 指标）：**在 IO 线程现场查 Health Connect**，带 60 秒缓存。
 *
 * 为什么要缓存：`HcQuery.coverage` 近 30 天的心率/血氧各要读上万条、指标序列每条要跑十几次聚合。
 * 页面每次动作后都会重新 `state()`，60 秒内复用同一份快照，既不抖也不反复扫库。
 *
 * 不变式：
 * - **不整表读、不做"总行数"求和**：用 `HcQuery.coverage` 的首末各一页 + 近 30 天有界统计；
 * - HC 不可用 / 权限被回收 / 读取异常 ⇒ 空数组 + `note`，**不抛**、不让整个 `state()` 失败；
 * - 可在任意线程调用（JS 桥线程），内部同步；**不要**在主线程调（会卡 UI）。
 */
object AdminHealthLoader {

    /** 快照有效期：60 秒。 */
    const val TTL_MS = 60_000L

    /** 只保护"缓存指针 + 单飞标志"，绝不包住 Health Connect 查询。 */
    private val lock = Any()

    @Volatile
    private var cached: AdminHealth? = null

    @Volatile
    private var cachedAt = 0L

    @Volatile
    private var warming = false

    /** 只读缓存：没有新鲜快照就返回 null，绝不在这里查 Health Connect，也不会等后台查询。 */
    fun cached(now: Long = System.currentTimeMillis()): AdminHealth? {
        val hit = cached ?: return null
        return hit.takeIf { now - cachedAt in 0 until TTL_MS }
    }

    /**
     * 单飞预热：首屏先渲染，查询在后台跑；同一时间只允许一个线程进 Health Connect。
     * 不阻塞 JS 桥，也不让两次并发 state() 同时扫库。
     */
    fun warm(ctx: Context, now: Long = System.currentTimeMillis()) {
        if (cached(now) != null) return
        synchronized(lock) {
            if (warming) return
            warming = true
        }
        val app = ctx.applicationContext
        Thread({
            try {
                snapshot(app, now, force = true)
            } finally {
                synchronized(lock) { warming = false }
            }
        }, "admin-health-warm").apply {
            isDaemon = true
            start()
        }
    }

    fun snapshot(ctx: Context, now: Long = System.currentTimeMillis(), force: Boolean = false): AdminHealth {
        if (!force) cached(now)?.let { return it }
        // 查询在锁外：页面读 cached() 时不会被一次全量 HC 扫描卡住。
        val fresh = runCatching { load(ctx.applicationContext, now) }
            .getOrElse { AdminHealth.unavailable("健康数据读取失败（${it.javaClass.simpleName}）") }
        synchronized(lock) {
            cached = fresh
            cachedAt = now
        }
        return fresh
    }

    fun invalidate() {
        synchronized(lock) {
            cached = null
            cachedAt = 0L
        }
    }

    // ------------------------------------------------------------------ 读取

    private fun load(ctx: Context, now: Long): AdminHealth {
        val status = runCatching { HealthConnectClient.getSdkStatus(ctx) }
            .getOrDefault(HealthConnectClient.SDK_UNAVAILABLE)
        if (status != HealthConnectClient.SDK_AVAILABLE) {
            return AdminHealth.unavailable("Health Connect 不可用（SDK 状态 $status）")
        }
        val client = runCatching { HealthConnectClient.getOrCreate(ctx) }.getOrNull()
            ?: return AdminHealth.unavailable("Health Connect 无法打开")

        val zone = ZoneId.systemDefault()
        val days = AdminHealth.DEFAULT_METRIC_DAYS
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(now), zone)
        val from = today.minusDays((days - 1).toLong()).toString()
        val to = today.toString()
        val errors = ArrayList<String>()

        val coverage = runCatching {
            runBlocking { AdminHealthMap.coverage(HcQuery.coverage(client), zone) }
        }.onFailure { errors.add("覆盖读取失败") }.getOrDefault(emptyList())

        val sleep = runCatching {
            runBlocking {
                AdminHealthMap.sleep(
                    HcQuery.sleepStages(
                        client,
                        today.minusDays(AdminHealth.SLEEP_DAYS.toLong()).toString(),
                        null,
                    ),
                    days = AdminHealth.SLEEP_DAYS,
                    nowMs = now,
                    zone = zone,
                )
            }
        }.onFailure { errors.add("睡眠读取失败") }.getOrDefault(emptyList())

        val steps = runCatching {
            runBlocking { HcQuery.metricSeries(client, "steps", from, to, "day") }
        }.onFailure { errors.add("步数读取失败") }.getOrNull()
        val heart = runCatching {
            runBlocking { HcQuery.metricSeries(client, "heart_rate_avg", from, to, "day") }
        }.onFailure { errors.add("心率读取失败") }.getOrNull()
        val sleepMin = runCatching {
            runBlocking { HcQuery.metricSeries(client, "sleep_minutes", from, to, "day") }
        }.onFailure { errors.add("睡眠趋势读取失败") }.getOrNull()

        return AdminHealth(
            coverage = coverage,
            sleep = sleep,
            metrics = AdminHealthMap.metrics(
                steps = steps,
                heart = heart,
                sleepMinutes = sleepMin,
                days = days,
                nowMs = now,
                zone = zone,
            ),
            note = errors.takeIf { it.isNotEmpty() }?.distinct()?.joinToString("；"),
        )
    }
}

/**
 * 概览页的「同步 / 回填」两段：读 `SyncStatus`（本机状态文件）与 `SyncHistory`（最近 10 次结果）。
 *
 * 这里**只显示已经发生的事实**：排队中的回填没有可读进度，就不编一个进度条（`progress = null`）。
 */
object AdminSyncState {

    private val HISTORY_TS = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    fun sync(ctx: Context, now: Long = System.currentTimeMillis()): SyncEntry {
        val status = runCatching { SyncStatus.read(ctx) }.getOrNull()
        val history = runCatching { SyncHistory.all(ctx) }.getOrDefault(emptyList())
        val last = history.firstOrNull()
        val result = last?.optString("result", "").orEmpty()

        val atSec = status?.optLong("at_ts", 0L) ?: 0L
        val lastAt = if (atSec > 0) atSec * 1000L else parseHistoryTs(last?.optString("at"))
        val lastTs = status?.optLong("last_ts", 0L) ?: 0L
        val lagMin = if (lastTs > 0) ((now - lastTs) / 60_000L).coerceAtLeast(0) else null
        val rows = status?.optLong("count", 0L) ?: last?.optLong("samples", 0L)
        val secondary = runCatching { Prefs.role(ctx) == "secondary" }.getOrDefault(false) ||
            status?.optString("role") == "secondary"

        return SyncEntry(
            lastSyncAt = lastAt,
            lastSyncSource = if (secondary) "secondary" else "xiaomi-cloud",
            lastSyncRows = rows,
            lagMin = lagMin,
            pipeline = pipelineOf(result),
            lastSyncResult = result,
        )
    }

    fun backfill(ctx: Context): BackfillEntry {
        val history = runCatching { SyncHistory.all(ctx) }.getOrDefault(emptyList())
        val entry = history.firstOrNull { it.optString("result") == "backfill" }
            ?: return BackfillEntry(
                note = "本机还没跑过回填。回填由后台任务执行，完成后写入同步历史。",
            )
        val at = parseHistoryTs(entry.optString("at"))
        val days = entry.optString("window").removeSuffix("d").toIntOrNull()
        val zone = ZoneId.systemDefault()
        val from = if (at != null && days != null && days > 0) {
            LocalDate.ofInstant(Instant.ofEpochMilli(at), zone).minusDays(days.toLong()).toString()
        } else {
            ""
        }
        val to = at?.let { LocalDate.ofInstant(Instant.ofEpochMilli(it), zone).toString() } ?: ""
        return BackfillEntry(
            status = BackfillEntry.STATUS_IDLE,
            progress = null,          // 排队中的任务没有可读进度：不编数字
            rangeFrom = from,
            rangeTo = to,
            lastRun = at,
            note = "回填由后台任务（WorkManager）执行；这里显示最近一次完成的记录。",
        )
    }

    /** 同步结果码 → 概览横幅的三档（未知结果按 warn 处理，不假装正常）。 */
    fun pipelineOf(result: String): String = when (result.lowercase(Locale.ROOT)) {
        "" -> SyncEntry.PIPELINE_UNKNOWN
        "error", "auth_failed", "no_permission", "partial_permission", "net_wait", "failed" ->
            SyncEntry.PIPELINE_WARN
        else -> SyncEntry.PIPELINE_OK
    }

    private fun parseHistoryTs(text: String?): Long? {
        val s = text?.trim().orEmpty()
        if (s.isEmpty()) return null
        return runCatching { HISTORY_TS.parse(s)?.time }.getOrNull()
    }
}
