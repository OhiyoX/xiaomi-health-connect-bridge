package id.harness.hcbridge

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 同步状态：`/sdcard/Download/hc-bridge/app-sync-status.json`（给人/Termux 看）
 * + `filesDir/app-sync-status.json`（app 自己的唯一可信来源）。
 *
 * 副机也必须写：2026-10-18 平板四个 `query_*` 全失败时，因为副机路径不写状态，
 * 外部只能看到一个**过期的主机状态**，查了很久才发现是系统按 UID 拦了联网权限。
 *
 * 为什么 UI 不直接读 /sdcard：scoped storage 下没有存储权限的 app 用裸路径读
 * 共享存储会被拒（Permission denied），旧实现因此永远显示「云端未取到」。
 */
object SyncStatus {

    private const val TAG = "HCBridge"
    const val REL_DIR = DownloadStore.REL_DIR
    const val FILE_NAME = "app-sync-status.json"

    private fun privateFile(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    fun write(ctx: Context, state: String, count: Int, maxTs: Long, extra: JSONObject? = null) {
        val json = JSONObject()
            .put("state", state)
            .put("count", count)
            .put("max_ts", maxTs)
            .put("last_ts", Prefs.lastTs(ctx))
            .put("role", Prefs.role(ctx))
            .put("fails", Prefs.fails(ctx))
            .put("at_ts", System.currentTimeMillis() / 1000)
            .put("at", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .format(LocalDateTime.now(ZoneId.systemDefault())))
        extra?.let { for (k in it.keys()) json.put(k, it.get(k)) }
        // 导出版本/构建时间/安装时间（当地时间）：排查时先确认跑的是不是刚构建的包
        runCatching { json.put("app", BuildInfo.statusJson(ctx)) }
        // 导出文件名（重装后可能变成 `app-sync-status (1).json`）：外面读状态时以这个为准
        Prefs.exportName(ctx).takeIf { it.isNotBlank() }?.let { json.put("export_name", it) }
        val text = json.toString(2)

        // 1) app 私有副本：UI 的唯一可靠来源，不受分区存储/MediaStore 影响
        runCatching {
            val dst = privateFile(ctx)
            val tmp = File(ctx.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(dst)) {
                dst.writeText(text, Charsets.UTF_8)
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "写私有状态失败", it) }

        // 2) 导出给 Termux/人看：同一主名只留一份
        val r = DownloadStore.put(ctx, FILE_NAME, "application/json", text)
        if (!r.ok) {
            Log.w(TAG, "状态文件导出失败（私有副本仍在）")
            // 以前只写 logcat（Termux 侧看不到），结果 2026-10-28→29 导出在 13:10 就停了，
            // 外面一直读到旧文件还以为“正常”。现在同步写进 app 内日志。
            AppLog.log(ctx, "status", "导出失败：Termux/人看不到新状态（私有副本仍在）")
        } else if (!r.canonical) {
            val actual = r.actual ?: FILE_NAME
            Prefs.setExportName(ctx, actual)
            AppLog.log(ctx, "status", "导出被改名：$FILE_NAME → $actual（旧文件不可删，请读最新那份）")
        } else {
            Prefs.setExportName(ctx, FILE_NAME)
        }
        Snapshot.invalidate()
    }

    /** UI 读状态：私有副本 → MediaStore 里最新一份 → 裸路径（旧系统/有存储权限时）。 */
    fun read(ctx: Context): JSONObject? {
        return runCatching {
            val f = privateFile(ctx)
            if (f.isFile) JSONObject(f.readText(Charsets.UTF_8)) else null
        }.getOrNull()
            ?: DownloadStore.getText(ctx, FILE_NAME)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: runCatching {
                val f = File("/sdcard/" + REL_DIR + FILE_NAME)
                if (f.isFile) JSONObject(f.readText(Charsets.UTF_8)) else null
            }.getOrNull()
    }

    /** 私有副本 mtime：快照缓存用它判断状态是否变化。 */
    fun lastModified(ctx: Context): Long =
        runCatching { privateFile(ctx).lastModified() }.getOrDefault(0L)
}
