package id.harness.hcbridge

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * 权限自愈页（app 内，不再需要终端 / rish）：
 * 由通知点进来（或 `am start` 指定），一次点击就能把 HC 的读/写心率权限重新请求回来，
 * 授予后立刻触发一次同步。
 */
class FixActivity : Activity() {

    companion object {
        private const val REQ = 4712

        /** 一次把**同步真正用到的全集**都请回来：以前只要心率那 2 项，结果睡眠/步数/血氧的
         *  写权限被回收后没人管（2026-10-28 实机：WRITE_SLEEP=granted=false 但状态文件仍写 ok-no-new）。 */
        private val NEED: Array<String> = id.harness.hcbridge.logic.HcPermissions.used.toTypedArray()
    }

    private lateinit var tv: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        tv = TextView(this)
        tv.setPadding(48, 160, 48, 48)
        tv.textSize = 15f
        setContentView(tv)
        requestMissing()
    }

    private fun requestMissing() {
        val missing = NEED.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            tv.text = "权限都在，已触发一次同步。"
            SyncWorker.runNow(this)
            tv.postDelayed({ finish() }, 1200)
            return
        }
        tv.text = "需要恢复 Health Connect 权限（${missing.size} 项：" +
            id.harness.hcbridge.logic.HcPermissions.describe(missing, 6) + "），请在弹窗里点“允许”。"
        requestPermissions(missing.toTypedArray(), REQ)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code != REQ) return
        val missing = NEED.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            AppLog.log(this, "perm", "权限已恢复")
            tv.text = "已恢复权限，正在同步…"
            SyncWorker.runNow(this)
            tv.postDelayed({ finish() }, 1500)
        } else {
            AppLog.log(this, "perm", "仍被拒绝：${missing.joinToString(", ")}")
            tv.text = "还有 ${missing.size} 项被拒绝：${missing.joinToString(", ")}\n" +
                "可再点一次通知，或到 系统设置 → 应用 → Health Connect → 应用权限 里打开。"
        }
    }
}
