package id.harness.hcbridge

/**
 * 共享管理前端（`admin-ui/`，见 `admin-ui/API.md`）在 App 端需要的**纯数据**与宿主接口。
 *
 * 这里刻意不 import 任何 `android.*`：`AdminStateJson`、`AdminActions` 与它们的 JVM 单测都只吃这份模型；
 * 真正碰 Health Connect / SharedPreferences / `Secure` / WorkManager 的实现在 `AndroidAdminEnv`。
 *
 * 契约要点（改之前先读 `admin-ui/API.md` §3）：
 * - 时间戳一律 **epoch 毫秒**，`null` = 未知（页面显示「—」，不会当 0）；
 * - 数字可以是 `null`（未知），不要用 0 冒充；
 * - 这里**没有秘密**：密码哈希、TOTP secret、令牌明文、client_secret、tunnel token 都不在模型里。
 */

/** 覆盖页一行：某数据类型在 Health Connect 里的规模与近 30 天覆盖。 */
data class CoverageEntry(
    val type: String,
    val label: String,
    /** 近 30 天记录条数（`HcQuery.coverage` 的 `records_30d`）；`null` = 不统计。 */
    val rows: Long? = null,
    /** 近 30 天出现过的来源包名（去重、排序）。 */
    val sources: List<String> = emptyList(),
    /** 近 30 天里「有数据」的天数；`null` = 未知（页面按 days/30 画覆盖条）。 */
    val days30: Int? = null,
    /** 最近一条记录的时间（epoch ms）；`null` = 从来没写过。 */
    val lastAt: Long? = null,
)

/** 睡眠体检一行：**按起床那天**聚合（与 NAS `stats.sleep_days` 同口径）。 */
data class SleepEntry(
    val date: String,
    /** 已格式化的本地时钟（`HH:mm`），页面直接展示。 */
    val bed: String,
    val wake: String,
    val hours: Double? = null,
    val score: Double? = null,
    /** 来源包名（页面按 `data.js` 的来源表映射，未知就原样显示）。 */
    val source: String = "",
    /** `missing|short|long|late|late_bed|fragile|fragmented|invalid` 的子集。 */
    val flags: List<String> = emptyList(),
    val note: String = "",
)

/** 指标趋势一条（14 天）。`series` 与窗口逐日对齐，缺数据的日期是 `null`。 */
data class MetricEntry(
    val key: String,
    val label: String,
    val unit: String,
    val decimals: Int,
    val latest: Double? = null,
    val avg: Double? = null,
    val series: List<Double?> = emptyList(),
)

data class AdminMetrics(
    val days: Int = AdminHealth.DEFAULT_METRIC_DAYS,
    val entries: List<MetricEntry> = emptyList(),
) {
    fun get(key: String): MetricEntry? = entries.firstOrNull { it.key == key }
}

/** 同步管道（概览页顶部横幅）。 */
data class SyncEntry(
    val lastSyncAt: Long? = null,
    /** `xiaomi-cloud` / `secondary` …（页面按来源表映射）。 */
    val lastSyncSource: String = "",
    val lastSyncRows: Long? = null,
    /** 云端最新采样落后当前时间多少分钟；`null` = 未知。 */
    val lagMin: Long? = null,
    /** `ok` | `warn` | `unknown`。 */
    val pipeline: String = PIPELINE_UNKNOWN,
    /** 最近一次同步结果码（`ok` / `no_new` / `error` …）。 */
    val lastSyncResult: String = "",
) {
    companion object {
        const val PIPELINE_OK = "ok"
        const val PIPELINE_WARN = "warn"
        const val PIPELINE_UNKNOWN = "unknown"
        val UNKNOWN = SyncEntry()
    }
}

/** 回填状态（概览页）。App 的回填是 WorkManager 一次性任务，只能看到"最近一次结果"。 */
data class BackfillEntry(
    /** `idle` | `running` | `error`。 */
    val status: String = STATUS_IDLE,
    /** 0–1；`null` = 未知（页面显示「—」）。 */
    val progress: Double? = null,
    val rangeFrom: String = "",
    val rangeTo: String = "",
    val lastRun: Long? = null,
    val note: String = "",
) {
    companion object {
        const val STATUS_IDLE = "idle"
        const val STATUS_RUNNING = "running"
        const val STATUS_ERROR = "error"
        val IDLE = BackfillEntry()
    }
}

/**
 * 健康数据快照（覆盖 / 睡眠 / 指标）。
 *
 * [note] 非空表示"这一段没取到"（Health Connect 不可用、权限被回收、读取异常），
 * 页面照常渲染空数组 + 给人看的说明，**不**让整个 `state()` 失败。
 */
data class AdminHealth(
    val coverage: List<CoverageEntry> = emptyList(),
    val sleep: List<SleepEntry> = emptyList(),
    val metrics: AdminMetrics = AdminMetrics(),
    val note: String? = null,
    /** 首次读取还在后台进行：页面先用空数组渲染，并自动再来取一次。 */
    val pending: Boolean = false,
) {
    companion object {
        /** 页面固定显示 14 日趋势（SPA 的 14/30/90 切换在 App 端只开放 14）。 */
        const val DEFAULT_METRIC_DAYS = 90

        /** 睡眠体检窗口（天）。 */
        const val SLEEP_DAYS = 14

        /** 覆盖口径窗口（天）：与 `HcQuery.coverage` / `data.js` 的 `COVERAGE_DAYS` 一致。 */
        const val COVERAGE_DAYS = 30

        fun unavailable(reason: String) = AdminHealth(note = reason)

        /** 后台正在读 Health Connect：不要阻塞首屏。 */
        fun loading(reason: String) = AdminHealth(note = reason, pending = true)
    }
}

/**
 * 最近一次公网探活（`local.probe` 的结果）。
 *
 * 只活在进程里：不落 `settings`、不进审计 detail（审计只记 `ms=` 与 ok/failed）。
 * [detail] 是给人看的短句（"可达，公网 issuer 已生效（35 ms）"），不含任何凭据。
 */
data class ProbeEntry(val ok: Boolean, val at: Long, val detail: String, val ms: Long = 0L)

/**
 * `AdminStateJson` / `AdminActions` 对宿主（Android）的**唯一**依赖面。
 *
 * 做成接口是为了两件事：JVM 单测给一个纯内存假宿主；`McpWebAdminActivity` 提供真实实现。
 * 所有方法都可能在**非主线程**（JS 桥线程）被调用，实现要自己保证线程安全；
 * 这里不得抛异常把动作/状态搞崩（`AdminActions` 另有兜底，但不要依赖它）。
 */
interface AdminEnv {

    /** 当前时间（epoch ms）。 */
    fun now(): Long

    /** 版本名（显示在侧栏底部）。 */
    fun version(): String

    /** 设备名（会话列表里显示）。 */
    fun deviceLabel(): String

    /** 最近一次成功解锁的时间（epoch ms）；进程内状态，重启后为 `null`。 */
    fun lastUnlockAt(): Long?

    /** TOTP secret（base32）；未启用 ⇒ `null`。**只用于判断/校验，不进 state JSON。** */
    fun totpSecret(): String?

    /** 写入或清除 TOTP secret（`null` = 关闭）。 */
    fun setTotpSecret(secret: String?)

    /** 健康数据快照（实现里带 60 秒缓存；不可用时给空数组 + note）。 */
    fun health(): AdminHealth

    /** 同步/回填状态（读本机状态文件与同步历史）。 */
    fun sync(): SyncEntry

    fun backfill(): BackfillEntry

    /** 排队一次回填；返回是否真的排上。 */
    fun enqueueBackfill(days: Int): Boolean

    /**
     * tunnel token 明文：**只给 `local.command` 拼命令用**。
     *
     * 绝不进 `state`（状态里只有 [hasTunnelToken] 的布尔）、不进日志、不进审计 detail。
     */
    fun tunnelToken(): String?

    /** 是否存过 tunnel token（状态里只报"存没存"）。 */
    fun hasTunnelToken(): Boolean = !tunnelToken().isNullOrBlank()

    /** 写入或清除 tunnel token（`null`/空白 = 清除）。 */
    fun setTunnelToken(token: String?)

    /** 最近一次公网探活（进程内；`null` = 从没检测过）。 */
    fun lastProbe(): ProbeEntry?

    /** 记住最近一次探活结果（宿主刷新 state 时用）。 */
    fun setLastProbe(probe: ProbeEntry?)

    /**
     * 经公网基址探活。**阻塞**（最长约 2 × 8 秒），调用方保证不在主线程：
     * JS 桥线程可以，主线程一律不行。实现不得抛异常。
     */
    fun probe(publicBase: String): ProbeEntry

    /** 请求宿主**上锁**（撤销当前解锁会话）。实现必须切回主线程再动界面。 */
    fun lockSession()

    /** 只写本机日志（不含任何凭据）；默认不写。 */
    fun log(tag: String, message: String) = Unit
}
