package id.harness.hcbridge.auth

import androidx.annotation.VisibleForTesting

/**
 * 管理页门禁的**进程级**状态（计划 Task 6；问题：门禁若挂在 Activity 上，返回键/旋转就清零）。
 *
 * 三样东西必须活得比 Activity 久：
 * - [AdminGate.AttemptLimiter]：密码试错次数。按 Activity 计数的话，退出再进就是新的 5 次，
 *   限流形同虚设；
 * - [AdminGate.Session]：解锁会话（超时在 `McpAdminActivity` 里由 Handler 兜，这里只存时间戳）；
 * - [Totp.ReplayGuard]：同一个动态码只能用一次，跨 Activity 重建也不能再用。
 *
 * 都是**进程内**内存状态：进程被杀即清空（这是想要的——进程死亡本身已经丢掉一切），不落库、
 * 不写日志；本类不 import `android.*`。测试用 [resetForTest] 复位。
 *
 * 加锁的原因：Activity 主线程与（未来）同意页/服务都可能读写，读改写不能交错。
 */
object AdminThrottle {

    private val limiter = AdminGate.AttemptLimiter()
    private val session = AdminGate.Session()
    private val totpGuard = Totp.ReplayGuard()

    /** 还要等多少毫秒才能再试密码；`0` = 没锁。 */
    @Synchronized
    fun lockedFor(now: Long): Long = limiter.lockedFor(now)

    /** 记一次失败（密码或动态码）；返回本次是否进入锁定。 */
    @Synchronized
    fun failure(now: Long): Boolean = limiter.failure(now)

    /** 一次成功（密码或屏幕锁），清零失败计数。 */
    @Synchronized
    fun success() = limiter.success()

    /** 续期解锁会话（用户还在操作）。 */
    @Synchronized
    fun touch(now: Long) = session.unlock(now)

    @Synchronized
    fun isOpen(now: Long): Boolean = session.isOpen(now)

    @Synchronized
    fun lock() = session.lock()

    /** 校验**并**占用该动态码的时间步（防重放，跨页面生效）。 */
    @Synchronized
    fun claimTotp(secret: String, code: String?, atMillis: Long): Boolean =
        Totp.verifyAndClaim(totpGuard, secret, code, atMillis)

    /** 仪器测试用：把锁、会话、动态码占用全部复位。 */
    @VisibleForTesting
    @Synchronized
    fun resetForTest() {
        session.lock()
        limiter.success()
        totpGuard.clear()
    }
}
