package id.harness.hcbridge

import android.app.Activity
import android.content.Intent
import id.harness.hcbridge.auth.McpConfig

/**
 * 管理页入口的唯一选路点（0.9.0）。
 *
 * - 默认 [McpConfig.ADMIN_UI_WEB] → [McpWebAdminActivity]（内置共享前端 + JS 桥）；
 * - `native` → [McpAdminActivity]（隐藏回退，没有设置界面，出问题手工改 `settings.adminUiMode`）。
 *
 * 选路要读一次 `mcp.db`，所以放在工作线程；读失败按默认 `web` 处理 ——
 * 管理入口不能因为一次读库失败而打不开。`Activity` 只在这个短暂的工作里被引用。
 */
object AdminEntry {

    fun open(activity: Activity) {
        val app = activity.applicationContext
        Thread({
            val mode = runCatching { McpConfig(Store.of(app)).adminUiMode }
                .getOrDefault(McpConfig.ADMIN_UI_WEB)
            val target = if (mode == McpConfig.ADMIN_UI_NATIVE) {
                McpAdminActivity::class.java
            } else {
                McpWebAdminActivity::class.java
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                runCatching { activity.startActivity(Intent(activity, target)) }
            }
        }, "hcb-admin-entry").start()
    }
}
