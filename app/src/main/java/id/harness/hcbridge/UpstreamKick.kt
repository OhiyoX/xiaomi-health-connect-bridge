package id.harness.hcbridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * 「叫醒上游」：小米运动健康（`com.mi.health`）停摆时，桥**主动**去催它。
 *
 * 为什么需要：链路是 `手环 → 小米运动健康 → 小米健康云 → 本桥 → HC`。
 * 桥只能做后半段；它被 ColorOS 冻结 / 后台同步失败 / 手环没连上时，云端就一直不变，
 * 桥只会老实报 `ok-no-new` —— 从外面看像“正常”，实际一条新数据都没有（2026-10-28 业主反馈）。
 *
 * 安卓不给第三方 app「命令别的 app 去同步」的权限，所以这里按**代价从小到大**试三招，
 * 全部静默（不抢前台），并且把每一招的结果记进状态文件 —— 用哪招有效，是实测出来的，不是猜的：
 *
 *  1. `bind`：绑它导出的设备服务（`fitness.intent.action.DEVICE`）。绑定不受后台启动限制，
 *     会把它的 `:device` 进程拉起来 —— 小米的蓝牙/周期同步就跑在那个进程里。
 *  2. `bcast`：给它导出的 `PhoneStateBroadcastReceiver` 发一条 `com.xiaomi.cognition.SCRENE_CHANGE`
 *     （屏幕变化）。小米穿戴系在“开屏”时会尝试连手环同步。
 *  3. 通知/按钮里的「打开小米运动健康」：真正把它切到前台（`activity`），最有效但打扰人，
 *     所以**不在后台周期里自动做**，只在人点的时候做。
 *
 * 三招都无效（或它根本没在跑）时，桥会发通知并列出原因，人点一下就能拉起它 —— 这就是兜底。
 */
object UpstreamKick {

    const val MI_PKG = "com.mi.health"
    const val TAG = "HCBridge"

    private const val HOLD_MS = 5_000L

    data class Attempt(val name: String, val ok: Boolean, val note: String = "")

    data class Result(val attempts: List<Attempt>) {
        val woke: Boolean get() = attempts.any { it.ok }

        /** 一行记录，形如 `bind=ok,bcast=ok`；失败带短原因。 */
        fun summary(): String = attempts.joinToString(",") { a ->
            a.name + "=" + (if (a.ok) "ok" else "no") + if (a.note.isEmpty()) "" else "(" + a.note + ")"
        }
    }

    /** 后台静默催一次（不抢前台）。 */
    fun kick(ctx: Context): Result {
        val attempts = ArrayList<Attempt>(3)
        attempts.add(nudge("bind") { bindDevice(ctx, "fitness.intent.action.DEVICE") })
        attempts.add(nudge("bind2") {
            bindDevice(ctx, "com.xiaomi.fitness.DEVICE_SETTINGS_XMS_SERVICE")
        })
        attempts.add(nudge("bcast") { broadcastScreenChange(ctx) })
        val r = Result(attempts)
        Log.i(TAG, "上游唤醒：${r.summary()}")
        AppLog.log(ctx, "upstream", "唤醒小米运动健康：${r.summary()}")
        return r
    }

    /** 把人叫到小米运动健康（前台）。只给人点得着的地方用：通知、面板按钮。 */
    fun openMiFitness(ctx: Context): Attempt = nudge("activity") { startMi(ctx) }

    /** 小米运动健康的启动 Intent；装不上/看不见包时返回 null。 */
    fun miLaunchIntent(ctx: Context): Intent? =
        runCatching { ctx.packageManager.getLaunchIntentForPackage(MI_PKG) }.getOrNull()
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ---------------------------------------------------------------- 三招

    private fun bindDevice(ctx: Context, action: String): String {
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                Log.i(TAG, "上游绑上：$name")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.i(TAG, "上游断开：$name")
            }
        }
        val intent = Intent(action).setPackage(MI_PKG)
        val ok = ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        if (!ok) throw IllegalStateException("bindService=false")
        // 保持几秒：进程起来后它自己的同步链路才有机会跑起来（立刻 unbind 会被系统马上回收）
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { ctx.unbindService(conn) }
        }, HOLD_MS)
        return action.substringAfterLast('.')
    }

    private fun broadcastScreenChange(ctx: Context): String {
        val i = Intent("com.xiaomi.cognition.SCRENE_CHANGE")
            .setComponent(
                ComponentName(
                    MI_PKG,
                    "com.xiaomi.fitness.devicesettings.thirdparty.PhoneStateBroadcastReceiver",
                )
            )
        ctx.sendBroadcast(i)
        return "screen-change"
    }

    private fun startMi(ctx: Context): String {
        val i = miLaunchIntent(ctx) ?: throw IllegalStateException("no-launch-intent")
        ctx.startActivity(i)
        return "foreground"
    }

    private fun nudge(name: String, block: () -> String): Attempt = try {
        Attempt(name, true, block())
    } catch (t: Throwable) {
        Attempt(name, false, t.javaClass.simpleName + ":" + (t.message ?: "").take(48))
    }
}
