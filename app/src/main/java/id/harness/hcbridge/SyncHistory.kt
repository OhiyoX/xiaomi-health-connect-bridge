package id.harness.hcbridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 最近 N 次同步结果（环形，默认 10 条）：面板与 MCP `get_sync_history` 共用。
 * 记的是"结果"而不是完整日志 —— 一眼看出"哪次成功/失败、原因、写了多少"。
 */
object SyncHistory {

    private const val FILE = "hc_bridge_history"
    private const val KEY = "items"
    private const val MAX = 10
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    @Synchronized
    fun add(
        c: Context, result: String, insertedSamples: Int = 0, records: Int = 0,
        window: String = "", note: String = ""
    ) {
        val prefs = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val arr = runCatching { JSONArray(prefs.getString(KEY, "[]") ?: "[]") }.getOrElse { JSONArray() }
        val item = JSONObject()
            .put("at", fmt.format(Date()))
            .put("result", result)
            .put("samples", insertedSamples)
            .put("records", records)
            .put("window", window)
            .put("note", note.take(200))
        val list = ArrayList<JSONObject>()
        list.add(item)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (list.size >= MAX) break
            list.add(o)
        }
        val out = JSONArray()
        list.forEach { out.put(it) }
        prefs.edit().putString(KEY, out.toString()).apply()
    }

    fun all(c: Context): List<JSONObject> {
        val prefs = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val arr = runCatching { JSONArray(prefs.getString(KEY, "[]") ?: "[]") }.getOrElse { JSONArray() }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    fun asText(c: Context): String =
        if (all(c).isEmpty()) "(还没有记录)"
        else all(c).joinToString("\n") {
            "${it.optString("at")}  ${it.optString("result")}  " +
                "采样 ${it.optInt("samples")}/${it.optInt("records")} 条 record" +
                (if (it.optString("window").isNotEmpty()) "（${it.optString("window")}）" else "") +
                (if (it.optString("note").isNotEmpty()) "  ${it.optString("note")}" else "")
        }
}
