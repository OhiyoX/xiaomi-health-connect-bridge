package id.harness.hcbridge

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * 远端健康 MCP（NAS 上的 hc-health-mcp）连通性检查。
 * 不依赖 token 也能判活：无 token 时服务返回 401 = "活着但没授权"。
 */
object Remote {

    data class Check(
        val reachable: Boolean,
        val code: Int,
        val ms: Long,
        val tools: Int,      // 带 token 时才拿到
        val note: String
    )

    fun check(url: String, token: String?, timeoutMs: Int = 8000): Check {
        val started = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json, text/event-stream")
                if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
            }
            val body = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""".toByteArray()
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            val ms = System.currentTimeMillis() - started
            val tools = Regex("\"name\"\\s*:").findAll(text).count()
            val note = when {
                code in 200..299 && tools > 0 -> "$tools 个工具"
                code == 401 -> "服务在，但需要 token"
                code == 403 -> "被拒绝（路径/来源）"
                code in 500..599 -> "服务端错误 $code"
                else -> "HTTP $code"
            }
            Check(reachable = code in 200..499, code = code, ms = ms, tools = tools, note = note)
        } catch (t: Throwable) {
            Log.w("HCBridge", "远端检查失败", t)
            Check(false, 0, System.currentTimeMillis() - started, 0, t.message?.take(60) ?: "不可达")
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
