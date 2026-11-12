package id.harness.hcbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机 / 应用升级后自愈：重新注册 WorkManager 周期任务并立刻补一次。
 * WorkManager 本身有 persisted 能力，这里再兜一层（ColorOS 上偶尔会掉任务）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                Log.i(SyncWorker.TAG, "启动/升级广播：${intent.action} → 重排同步 + 补一次")
                SyncWorker.schedule(ctx)
                runCatching {
                    if (Prefs.mcpEnabled(ctx))
                        ctx.startForegroundService(Intent(ctx, McpService::class.java))
                    Log.i(SyncWorker.TAG, "开机拉起 MCP 服务")
                }
                if (Prefs.hasCreds(ctx)) SyncWorker.runNow(ctx)
            }
        }
    }
}
