package id.harness.hcbridge

/**
 * JVM 单测用的假宿主：不碰 `android.*`，只记录调用。
 *
 * `AdminStateJsonTest` / `AdminActionsTest` 用它替换真实的 `AndroidAdminEnv`
 * （TOTP 存内存、健康数据可注入、回填只记参数）。
 */
class FakeAdminEnv(
    var nowMs: Long = 1_763_700_000_000L,
    var version: String = "0.9.0-test",
    var device: String = "Test Phone",
    var unlockAt: Long? = null,
    var totp: String? = null,
    var health: AdminHealth = AdminHealth(),
    var sync: SyncEntry = SyncEntry.UNKNOWN,
    var backfill: BackfillEntry = BackfillEntry.IDLE,
    var backfillQueued: Boolean = true,
    var tunnel: String? = null,
    var probeResult: ProbeEntry = ProbeEntry(false, nowMs, "unreachable", 0),
) : AdminEnv {

    /** `enqueueBackfill` 收到的天数（按调用顺序）。 */
    val enqueued = ArrayList<Int>()

    /** `probe()` 收到的公网基址（按调用顺序）。 */
    val probed = ArrayList<String>()

    /** `setTunnelToken()` 写入次数（含清除）。 */
    var tokenWrites = 0
        private set

    /** 进程内"最近一次探活"。 */
    var lastProbeValue: ProbeEntry? = null
        private set

    /** `lockSession()` 被调了几次。 */
    var lockCalls = 0
        private set

    /** 置真后 `health()` 抛异常：验证"健康段失败不影响整个 state"。 */
    var healthThrows: Boolean = false

    override fun now(): Long = nowMs

    override fun version(): String = version

    override fun deviceLabel(): String = device

    override fun lastUnlockAt(): Long? = unlockAt

    override fun totpSecret(): String? = totp

    override fun setTotpSecret(secret: String?) {
        totp = secret
    }

    override fun health(): AdminHealth {
        if (healthThrows) throw IllegalStateException("health unavailable")
        return health
    }

    override fun sync(): SyncEntry = sync

    override fun backfill(): BackfillEntry = backfill

    override fun enqueueBackfill(days: Int): Boolean {
        enqueued.add(days)
        return backfillQueued
    }

    override fun tunnelToken(): String? = tunnel

    override fun setTunnelToken(token: String?) {
        tunnel = token?.trim()?.takeIf { it.isNotEmpty() }
        tokenWrites++
    }

    override fun lastProbe(): ProbeEntry? = lastProbeValue

    override fun setLastProbe(probe: ProbeEntry?) {
        lastProbeValue = probe
    }

    override fun probe(publicBase: String): ProbeEntry {
        probed.add(publicBase)
        return probeResult
    }

    override fun lockSession() {
        lockCalls++
    }
}
