package id.harness.hcbridge

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * app 内日志（环形缓冲）：面板里直接看，也能通过 MCP `hc_logs` 取。
 * 只存最近 [MAX] 行，纯文本落在 app 私有 SharedPreferences，不进日志平台、不出设备。
 */
object AppLog {

    private const val FILE = "hc_bridge_log"
    private const val KEY = "lines"
    private const val MAX = 300
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)

    @Synchronized
    fun log(c: Context, tag: String, msg: String) {
        val prefs = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val line = "${fmt.format(Date())} [$tag] $msg"
        val old = prefs.getString(KEY, "") ?: ""
        val lines = (if (old.isEmpty()) emptyList() else old.split("\n")).toMutableList()
        lines.add(line)
        while (lines.size > MAX) lines.removeAt(0)
        prefs.edit().putString(KEY, lines.joinToString("\n")).apply()
    }

    fun tail(c: Context, n: Int = 80): List<String> {
        val prefs = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val all = (prefs.getString(KEY, "") ?: "").split("\n").filter { it.isNotBlank() }
        return if (all.size <= n) all else all.subList(all.size - n, all.size)
    }

    fun clear(c: Context) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
