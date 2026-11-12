package id.harness.hcbridge

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/**
 * 无窗口"读一次 HC"：写 /sdcard/Download/hc-bridge/hc-latest.json。
 * 触发：am broadcast -n id.harness.hcbridge/.CmdReceiver -a id.harness.hcbridge.CMD_DUMP
 *
 * 有了它，Termux/pi 想看数据不必再 `am start` 打开 Activity（那会切前台窗口）。
 */
class DumpWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private const val TAG = "HCBridge"
        const val WORK_NAME = "hc-dump-now"

        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<DumpWorker>().build()
            )
        }

        /** 把 app 内日志导出成文件（方便发出去排查），返回文件名 */
        fun exportLogs(context: Context): String? {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.CHINA)
                .format(java.util.Date())
            val name = "logs-$stamp.txt"
            val body = buildString {
                append("HC Bridge 日志导出\n")
                append("时间：").append(stamp).append("\n")
                append("状态：").append(Prefs.status(context)).append("\n\n")
                append("== 日志 ==\n")
                append(AppLog.tail(context, 300).joinToString("\n"))
                append("\n\n== 最近 10 次同步 ==\n")
                append(SyncHistory.asText(context))
                append("\n")
            }
            return if (write(context, name, body, "text/plain")) name else null
        }

        fun write(context: Context, name: String, text: String, mime: String = "application/json"): Boolean {
            // 分区存储（RELATIVE_PATH / Downloads 集合）从 API 29 起；API 28 上不写（本 app 实际设备都 ≥ Android 15）
            val r = DownloadStore.put(context, name, mime, text)
            if (!r.ok) Log.w(TAG, "写文件失败（$name）")
            return r.ok
        }
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val status = HealthConnectClient.getSdkStatus(ctx)
        if (status != HealthConnectClient.SDK_AVAILABLE) {
            Prefs.setStatus(ctx, "dump: Health Connect 不可用（$status）")
            return Result.failure()
        }
        return try {
            val client = HealthConnectClient.getOrCreate(ctx)
            val json = HcReader.dump(client).toString(2)
            val ok = write(ctx, "hc-latest.json", json)
            Prefs.setStatus(ctx, "dump: " + if (ok) "ok（${json.length} 字节）" else "写文件失败")
            AppLog.log(ctx, "dump", if (ok) "写出 hc-latest.json（${json.length} 字节）" else "写文件失败")
            Log.i(TAG, "dump done ok=$ok bytes=${json.length}")
            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "dump failed", t)
            AppLog.log(ctx, "dump", "失败：${t.message}")
            Prefs.setStatus(ctx, "dump error: ${t.message}")
            Result.retry()
        }
    }
}
