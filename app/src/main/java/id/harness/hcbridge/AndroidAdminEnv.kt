package id.harness.hcbridge

import android.content.Context
import android.os.Build
import id.harness.hcbridge.auth.TotpSecret
import id.harness.hcbridge.auth.Tunnel

/**
 * [AdminEnv] 的 Android 实现：把纯逻辑（`AdminStateJson` / `AdminActions`）接到本机的
 * `TotpSecret`、`SyncWorker`、`Health Connect`、版本号与设备名上。
 *
 * 所有方法都可能在 **JS 桥线程**被调用（不是主线程）：
 * - `totpSecret` / `setTotpSecret`：`Secure` 的读写是同步的，内部有锁；
 * - `health`：见 [AdminHealthLoader]（60 秒缓存，第一次可能阻塞几秒）；
 * - `lockSession`：必须切回主线程，由宿主自己负责（传进来的 `onLock` 就是干这个的）。
 */
class AndroidAdminEnv(
    private val ctx: Context,
    private val onLock: () -> Unit,
    private val unlockAt: () -> Long? = { null },
) : AdminEnv {

    private val app: Context = ctx.applicationContext

    override fun now(): Long = System.currentTimeMillis()

    override fun version(): String = runCatching { BuildConfig.VERSION_NAME }.getOrDefault("")

    /** 会话列表里的设备名（`Xiaomi PJZ110` 这种）。 */
    override fun deviceLabel(): String {
        val maker = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        return when {
            maker.isEmpty() && model.isEmpty() -> "本机"
            maker.isEmpty() -> model
            model.isEmpty() || model.startsWith(maker, ignoreCase = true) -> model.ifEmpty { maker }
            else -> "$maker $model"
        }
    }

    override fun lastUnlockAt(): Long? = unlockAt()

    override fun totpSecret(): String? = runCatching { TotpSecret.get(app) }.getOrNull()

    override fun setTotpSecret(secret: String?) {
        runCatching { TotpSecret.set(app, secret) }
    }

    override fun health(): AdminHealth {
        val at = now()
        AdminHealthLoader.cached(at)?.let { return it }
        AdminHealthLoader.warm(app, at)
        return AdminHealth.loading("正在读取健康数据…")
    }

    override fun sync(): SyncEntry = AdminSyncState.sync(app, now())

    override fun backfill(): BackfillEntry = AdminSyncState.backfill(app)

    override fun enqueueBackfill(days: Int): Boolean = runCatching {
        SyncWorker.runBackfill(app, days)
        true
    }.getOrDefault(false)

    override fun tunnelToken(): String? =
        runCatching { Secure.getString(app, Tunnel.SECURE_KEY) }.getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    override fun setTunnelToken(token: String?) {
        runCatching { Secure.putString(app, Tunnel.SECURE_KEY, token?.trim()?.takeIf { it.isNotEmpty() }) }
    }

    override fun lastProbe(): ProbeEntry? = AdminLocalState.lastProbe

    override fun setLastProbe(probe: ProbeEntry?) {
        AdminLocalState.set(probe)
    }

    /**
     * 公网探活：`TunnelProbe` 是阻塞的（最长约 2 × 8 秒），**必须**在后台线程调用。
     * 本方法只把结果折成 [ProbeEntry]（`detail` 是可读短句，不含凭据）。
     */
    override fun probe(publicBase: String): ProbeEntry {
        val started = System.currentTimeMillis()
        val result = runCatching { TunnelProbe().probe(publicBase) }
            .getOrElse { TunnelProbe.Result(false, 0, "探活失败（${it.javaClass.simpleName}）") }
        val at = if (result.ms > 0) started + result.ms else System.currentTimeMillis()
        return ProbeEntry(
            ok = result.reachable,
            at = at,
            detail = "${result.note}（${result.ms} ms）",
            ms = result.ms,
        )
    }

    override fun lockSession() = onLock()

    override fun log(tag: String, message: String) = AppLog.log(app, tag, message)

}

/**
 * 进程内的"最近一次公网探活"（不落库、不进 settings）。
 *
 * 放这里而不是 Activity 字段：锁屏/旋转/重进管理页后，页面上的"最近检测"还应该是同一条结果
 * （原生页也是 Activity 字段，语义一致；进程被杀即清空）。
 */
object AdminLocalState {

    @Volatile
    var lastProbe: ProbeEntry? = null
        private set

    fun set(probe: ProbeEntry?) {
        lastProbe = probe
    }
}
