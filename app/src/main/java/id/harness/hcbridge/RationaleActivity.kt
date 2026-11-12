package id.harness.hcbridge

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * Health Connect 要求的「权限用途说明」入口：
 * 必须有一个 activity 声明 android.intent.action.VIEW_PERMISSION_USAGE
 * + category android.intent.category.HEALTH_PERMISSIONS，
 * 否则 HC 会认为「Incorrect health permission state」而拒绝所有读取。
 */
class RationaleActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val tv = TextView(this)
        tv.setPadding(40, 120, 40, 40)
        tv.textSize = 15f
        tv.text = "HC Bridge 读取你的健康数据（步数/距离/卡路里/心率/睡眠/血氧/呼吸率/体重），" +
            "只写成本地 JSON 到 /sdcard/Download/hc-bridge/，供 Termux 侧脚本读取，不上传任何服务器。"
        setContentView(tv)
    }
}
