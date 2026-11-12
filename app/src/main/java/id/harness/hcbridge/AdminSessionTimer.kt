package id.harness.hcbridge

import android.os.Handler
import android.os.Looper
import id.harness.hcbridge.auth.AdminThrottle

/**
 * 管理页/同意页的**前台**解锁定时器（计划 Task 6 复审：只在 `onResume` 判超时不够）。
 *
 * 语义：
 * - [schedule] 从现在起 [timeoutMs] 后回调 [onExpired]（页面还开着就自动上锁）；
 * - [touch] 用户还在操作 ⇒ 把进程级会话与会话定时器一起续期；
 * - 到点时复查 [AdminThrottle.isOpen]：时间戳还有效（例如 [touch] 刚发生）就再排一轮，
 *   保证"最后一次交互 + 超时"那一刻才锁；
 * - [cancel] 在 `onStop`/`onDestroy` 调（`onStop` 由调用方直接上锁）。
 *
 * 只在主线程使用；自己不持有 Activity。
 */
class AdminSessionTimer(
    private val timeoutMs: Long,
    private val onExpired: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {

    private val runnable = Runnable {
        if (AdminThrottle.isOpen(clock())) schedule() else onExpired()
    }

    fun schedule() {
        handler.removeCallbacks(runnable)
        handler.postDelayed(runnable, timeoutMs)
    }

    /** 有交互：先续期进程级会话，再排下一轮定时（会话已关就不排）。 */
    fun touch() {
        if (!AdminThrottle.isOpen(clock())) return
        AdminThrottle.touch(clock())
        schedule()
    }

    fun cancel() {
        handler.removeCallbacks(runnable)
    }
}
