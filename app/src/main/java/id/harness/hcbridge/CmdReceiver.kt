package id.harness.hcbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject

/**
 * 无界面触发入口 —— 给 Termux / 看门狗 / adb 用，**不弹任何窗口**。
 *
 *   am broadcast -n id.harness.hcbridge/.CmdReceiver -a id.harness.hcbridge.CMD_SYNC
 *   am broadcast -n id.harness.hcbridge/.CmdReceiver -a id.harness.hcbridge.CMD_BOOTSTRAP \
 *       --es creds '{"userId":"…","passToken":"…"}'
 *   am broadcast -n id.harness.hcbridge/.CmdReceiver -a id.harness.hcbridge.CMD_STATUS
 *
 * 之所以用广播而不是 am start：Activity 会切前台窗口（业主实测被弹烦），
 * 而 WorkManager 任务本身不需要 UI。
 */
class CmdReceiver : BroadcastReceiver() {

    companion object {
        const val CMD_WEIGHT_IMPORT = "id.harness.hcbridge.CMD_WEIGHT_IMPORT"
        const val TAG = "HCBridge"
        const val ACTION_SYNC = "id.harness.hcbridge.CMD_SYNC"
        const val ACTION_BOOTSTRAP = "id.harness.hcbridge.CMD_BOOTSTRAP"
        const val ACTION_STATUS = "id.harness.hcbridge.CMD_STATUS"
        const val ACTION_BACKFILL = "id.harness.hcbridge.CMD_BACKFILL"
        const val ACTION_SLEEP_CLEANUP = "id.harness.hcbridge.CMD_SLEEP_CLEANUP"
        const val ACTION_SLEEP_GAP = "id.harness.hcbridge.CMD_SLEEP_GAP"
        const val ACTION_KICK = "id.harness.hcbridge.CMD_KICK"
        const val ACTION_PUSH = "id.harness.hcbridge.CMD_PUSH"
        const val ACTION_ACTIVITY_CLEANUP = "id.harness.hcbridge.CMD_ACTIVITY_CLEANUP"
        const val ACTION_WEIGHT_IMPORT = "id.harness.hcbridge.CMD_WEIGHT_IMPORT"
        const val CMD_SLEEP_EXTRAS = "id.harness.hcbridge.CMD_SLEEP_EXTRAS"
        const val ACTION_RESCHEDULE = "id.harness.hcbridge.CMD_RESCHEDULE"
        const val ACTION_DUMP = "id.harness.hcbridge.CMD_DUMP"
        const val ACTION_MCP_START = "id.harness.hcbridge.CMD_MCP_START"
        const val ACTION_MCP_STOP = "id.harness.hcbridge.CMD_MCP_STOP"
        const val ACTION_SET_TOKEN = "id.harness.hcbridge.CMD_SET_TOKEN"
        const val ACTION_SEED = "id.harness.hcbridge.CMD_SEED"   // 仅 debug：写演示数据
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        AppLog.log(ctx, "cmd", intent.action ?: "null")
        when (intent.action) {
            ACTION_SYNC -> {
                SyncWorker.runNow(ctx)
                Log.i(TAG, "CMD_SYNC 已排队")
            }
            ACTION_BOOTSTRAP -> {
                val raw = intent.getStringExtra("creds") ?: ""
                val force = intent.getBooleanExtra("force", false)
                if (!force && Prefs.hasCreds(ctx)) {
                    // 已经有可用凭据（且 app 会自动轮换 token）→ 不要用旧文件覆盖
                    SyncWorker.schedule(ctx)
                    SyncWorker.runNow(ctx)
                    Log.i(TAG, "CMD_BOOTSTRAP 已跳过（已有凭据，仅重排+跑一次）")
                    return
                }
                val ok = runCatching {
                    val o = JSONObject(raw)
                    val u = o.getString("userId")
                    val t = o.getString("passToken")
                    require(u.isNotEmpty() && t.isNotEmpty()) { "空凭据" }
                    Prefs.saveCreds(ctx, u, t)
                    SyncWorker.schedule(ctx)
                    SyncWorker.runNow(ctx)
                    true
                }.getOrElse {
                    Log.w(TAG, "bootstrap 失败", it); false
                }
                Log.i(TAG, "CMD_BOOTSTRAP ok=$ok")
            }
            ACTION_STATUS -> Log.i(TAG, "CMD_STATUS 收到（当前状态：${Prefs.status(ctx)}）")
            ACTION_SEED -> {
                if (!BuildConfig.DEBUG) {
                    Log.w(TAG, "CMD_SEED 仅 debug 构建可用")
                } else {
                    val pending = goAsync()
                    Thread {
                        try {
                            val msg = kotlinx.coroutines.runBlocking { SeedData.run(ctx) }
                            AppLog.log(ctx, "seed", msg)
                            Log.i(TAG, "CMD_SEED → $msg")
                        } catch (t: Throwable) {
                            Log.e(TAG, "CMD_SEED 失败", t)
                        } finally {
                            pending.finish()
                        }
                    }.start()
                }
            }
            ACTION_SET_TOKEN -> {
                val t = intent.getStringExtra("token")?.trim()?.ifBlank { null }
                if (t != null) Prefs.setRemoteToken(ctx, t)
                intent.getStringExtra("url")?.trim()?.ifBlank { null }?.let { Prefs.setRemoteUrl(ctx, it) }
                intent.getStringExtra("role")?.trim()?.ifBlank { null }?.let {
                    Prefs.setRole(ctx, if (it == "secondary") "secondary" else "primary")
                }
                Prefs.setRemoteToken(ctx, if (intent.hasExtra("token")) t else Prefs.remoteToken(ctx))
                AppLog.log(ctx, "cmd", "CMD_SET_TOKEN → token=${if (t == null) "未变/清除" else "${t.length} 字符"} url=${Prefs.remoteUrl(ctx)}")
                Log.i(TAG, "CMD_SET_TOKEN len=${Prefs.remoteToken(ctx)?.length ?: 0} url=${Prefs.remoteUrl(ctx)}")
            }
            ACTION_MCP_START -> {
                if (!Prefs.mcpEnabled(ctx) && !intent.getBooleanExtra("force", false)) {
                    Log.i(TAG, "CMD_MCP_START 被忽略：本机 MCP 开关是关的")
                    return
                }
                ctx.startForegroundService(android.content.Intent(ctx, McpService::class.java))
                Log.i(TAG, "CMD_MCP_START")
            }
            ACTION_MCP_STOP -> {
                ctx.stopService(android.content.Intent(ctx, McpService::class.java))
                Log.i(TAG, "CMD_MCP_STOP")
            }
            ACTION_DUMP -> {
                DumpWorker.runNow(ctx)
                Log.i(TAG, "CMD_DUMP 已排队（无窗口）")
            }
            ACTION_RESCHEDULE -> {
                SyncWorker.schedule(ctx)
                Log.i(TAG, "CMD_RESCHEDULE 已重排周期任务")
            }
            ACTION_BACKFILL -> {
                val days = intent.getIntExtra("days", 365)
                SyncWorker.runBackfill(ctx, days)
                Log.i(TAG, "CMD_BACKFILL days=$days 已排队")
            }
            ACTION_SLEEP_CLEANUP -> {
                val days = intent.getIntExtra("days", 365)
                SyncWorker.runSleepCleanup(ctx, days)
                Log.i(TAG, "CMD_SLEEP_CLEANUP days=$days 已排队（删历史重复睡眠）")
            }
            ACTION_PUSH -> {
                val pending = goAsync()
                val days = intent.getIntExtra("days", 30).toLong()
                Thread {
                    try {
                        val (n, note) = kotlinx.coroutines.runBlocking { HcPusher.push(ctx, days) }
                        AppLog.log(ctx, "push", "推 $n 条：$note")
                        Log.i(TAG, "CMD_PUSH → $note")
                    } catch (t: Throwable) {
                        Log.e(TAG, "CMD_PUSH 失败", t)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
            ACTION_ACTIVITY_CLEANUP -> {
                val days = intent.getIntExtra("days", 365)
                SyncWorker.runActivityCleanup(ctx, days)
                Log.i(TAG, "CMD_ACTIVITY_CLEANUP days=$days 已排队（步数/卡路里去重）")
            }
            ACTION_SLEEP_GAP -> {
                val days = intent.getIntExtra("days", 30)
                SyncWorker.runSleepGapCheck(ctx, days)
                Log.i(TAG, "CMD_SLEEP_GAP days=$days 已排队（空夜体检）")
            }
            ACTION_KICK -> {
                SyncWorker.runKick(ctx)
                AppLog.log(ctx, "cmd", "CMD_KICK：唤醒小米运动健康并补拉云端")
                Log.i(TAG, "CMD_KICK 已排队（唤醒上游）")
            }
            else -> Log.w(TAG, "未知广播：${intent.action}")
        }
    }
}
