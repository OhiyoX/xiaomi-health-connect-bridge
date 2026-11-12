package id.harness.hcbridge

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import java.nio.charset.StandardCharsets

/**
 * `/sdcard/Download/hc-bridge/` 下的单份文件存取。
 *
 * 不能只按正本名删：MediaStore 遇到重名会自动改名成 `name (1).ext`。旧实现只删正本，
 * 一旦正本删不掉（重装后 UID 变了、或文件 owner 为空），每写一次就多一份副本；
 * 堆到 32 份后 insert 直接抛 `Failed to build unique file`，从此再也写不进去。
 * 2026-10-22 的真机状态文件停更就是这么来的。
 */
object DownloadStore {

    private const val TAG = "HCBridge"
    const val REL_DIR = "Download/hc-bridge/"

    data class PutResult(val ok: Boolean, val canonical: Boolean, val actual: String? = null)

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.Q)
    private fun collection(): Uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI

    /** 同一主名的所有副本 URI（含 MediaStore 自动改名的 `name (N).ext`），新的在前。 */
    fun rows(ctx: Context, name: String): List<Uri> {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return emptyList()
        val dot = name.lastIndexOf('.')
        val base = if (dot >= 0) name.substring(0, dot) else name
        val ext = if (dot >= 0) name.substring(dot + 1) else ""
        val pattern = if (ext.isEmpty()) "$base%" else "$base%.$ext"
        val out = ArrayList<Uri>()
        runCatching {
            ctx.contentResolver.query(
                collection(), arrayOf(MediaStore.MediaColumns._ID),
                MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?",
                arrayOf(REL_DIR, pattern),
                MediaStore.MediaColumns.DATE_MODIFIED + " DESC, " + MediaStore.MediaColumns._ID + " DESC"
            )?.use { c ->
                while (c.moveToNext()) out.add(ContentUris.withAppendedId(collection(), c.getLong(0)))
            }
        }.onFailure { Log.w(TAG, "查导出文件失败（$name）", it) }
        return out
    }

    /** 覆盖写：先删同一主名的所有副本（能删的），再以 IS_PENDING 插入正本、写完发布。 */
    fun put(ctx: Context, name: String, mime: String, text: String): PutResult {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return PutResult(false, false)
        val resolver = ctx.contentResolver
        var leftovers = 0
        for (u in rows(ctx, name)) {
            val n = runCatching { resolver.delete(u, null, null) }.getOrDefault(0)
            if (n <= 0) leftovers++
        }
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, REL_DIR)
            // 不带 IS_PENDING 插入的行 owner 可能落成 NULL：之后本应用既查不到也删不掉，
            // 每写一次就被迫多一份 "(N)" 副本（模拟器上复现，正是真机堆到 32 份的原因）。
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(collection(), cv) }
            .onFailure { Log.w(TAG, "写文件失败（$name）", it) }
            .getOrNull() ?: return PutResult(false, false)
        val wrote = runCatching {
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray(StandardCharsets.UTF_8)) } != null
        }.getOrDefault(false)
        if (!wrote) {
            runCatching { resolver.delete(uri, null, null) }
            return PutResult(false, false)
        }
        runCatching {
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
        val actual = displayName(ctx, uri)
        val canonical = actual == name
        if (!canonical) Log.w(TAG, "导出被改名：$name → $actual（旧副本删不掉 $leftovers 份）")
        return PutResult(true, canonical, actual)
    }

    /** 读最新一份可读的（可能是 `name (N).ext`），没有则 null。 */
    fun getText(ctx: Context, name: String): String? {
        for (uri in rows(ctx, name)) {
            val text = runCatching {
                ctx.contentResolver.openInputStream(uri)?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
            }.onFailure { Log.w(TAG, "读导出文件失败（$name）", it) }.getOrNull()
            if (text != null) return text
        }
        return null
    }

    private fun displayName(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()
}
