package id.harness.hcbridge

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 守 2026-10-22 的回归：状态文件只留一份；读取要能穿过 MediaStore 的 "(N)" 改名。
 * 当年正本删不掉 → 副本堆到 32 份 → `Failed to build unique file` → 状态从此停更。
 *
 * 文件名带时间戳：模拟器上重装会换 UID，上一轮留下的同名文件既查不到也删不掉，
 * 会干扰重名判定——这不是被测行为。
 */
@RunWith(AndroidJUnit4::class)
class DownloadStoreTest {

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "hc-test-${System.currentTimeMillis()}.json"

    @Before
    fun clean() = deleteRows(name)

    @After
    fun tearDown() = deleteRows(name)

    private fun deleteRows(n: String) {
        for (u in DownloadStore.rows(ctx, n)) runCatching { ctx.contentResolver.delete(u, null, null) }
    }

    @Test
    fun overwriteKeepsExactlyOneCopy() {
        val first = DownloadStore.put(ctx, name, "application/json", """{"v":1}""")
        val second = DownloadStore.put(ctx, name, "application/json", """{"v":2}""")

        assertTrue(first.ok)
        assertTrue(second.ok)
        assertTrue("新文件名没有冲突，应写成正本名", second.canonical)
        assertEquals("同一主名只留一份", 1, DownloadStore.rows(ctx, name).size)
        assertEquals("""{"v":2}""", DownloadStore.getText(ctx, name))
    }

    @Test
    fun readsNumberedCopyWhenCanonicalWasRenamed() {
        DownloadStore.put(ctx, name, "application/json", """{"v":1}""")
        // 模拟 MediaStore 因重名自动改名的副本（旧安装留下的正是这种）
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name.replace(".json", " (1).json"))
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, DownloadStore.REL_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
        ctx.contentResolver.openOutputStream(uri!!)?.use { it.write("""{"v":2}""".toByteArray()) }
        ctx.contentResolver.update(
            uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null
        )

        assertEquals("应读到最新一份", """{"v":2}""", DownloadStore.getText(ctx, name))
        assertEquals("正本与副本地要都能列出来", 2, DownloadStore.rows(ctx, name).size)
    }

    @Test
    fun syncStatusReadableFromPrivateCopy() {
        val ts1 = 1_792_647_570L
        SyncStatus.write(ctx, "ok", 7, ts1)
        val ts2 = ts1 + 600
        SyncStatus.write(ctx, "ok", 9, ts2)

        val s = SyncStatus.read(ctx)
        assertEquals("应读到最新一次写入", ts2, s?.optLong("max_ts"))
        assertEquals(9, s?.optInt("count"))
        assertEquals("导出仍只留一份", 1, DownloadStore.rows(ctx, SyncStatus.FILE_NAME).size)
        assertTrue("导出内容可读", DownloadStore.getText(ctx, SyncStatus.FILE_NAME)?.contains(ts2.toString()) == true)
    }
}
