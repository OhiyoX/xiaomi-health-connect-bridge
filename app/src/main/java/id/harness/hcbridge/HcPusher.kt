package id.harness.hcbridge

import android.content.Context
import id.harness.hcbridge.logic.SecondaryPull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 反方向的同步：**手机 HC 独有、云端没有**的数据推到 NAS 的 health MCP（`POST /ingest`）。
 *
 * 为什么要分开（2026-10-21 澄清）：心率/睡眠/血氧/步数/卡路里这五类，NAS 自己会从**小米云**拉，
 * 不需要手机搬；只有手机这边才有的类型才走这条路：
 *   - `workout`：Google Fit 写的 walking 之类（HC 里 16 条，云端一条也没有）
 *   - `weight`：以后真称了体重才有
 *
 * 服务端按 start/ts 做 upsert，所以重复推同一批是幂等的；推送失败不影响主同步。
 */
object HcPusher {

    private const val TAG = "HCBridge"

    /** 推最近 [days] 天的 HC 独有数据；返回 (推送条数, 说明)。 */
    suspend fun push(ctx: Context, days: Long = 30): Pair<Int, String> {
        val base = Prefs.remoteUrl(ctx).trimEnd('/')
        // 优先直连 POST /ingest（edge vhost 已放行）；失败再回落到 MCP 的 ingest 工具
        val ingestUrl = (if (base.endsWith("/mcp")) base.dropLast(4) else base) + "/ingest"
        val mcpUrl = if (base.endsWith("/mcp")) base else "$base/mcp"
        val token = Prefs.remoteToken(ctx)
        if (token.isNullOrBlank()) return 0 to "未配置远端 token"

        val client = runCatching { androidx.health.connect.client.HealthConnectClient.getOrCreate(ctx) }.getOrNull()
            ?: return 0 to "Health Connect 不可用"

        val zone: ZoneId = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val fromDate = today.minusDays(days).toString()
        val toDate = today.toString()
        val items = JSONArray()

        // workout（ExerciseSessionRecord → HcQuery.workouts 的 JSON）
        runCatching {
            val doc = HcQuery.workouts(client, fromDate, toDate, minDurationMin = 1)
            val arr = doc.optJSONArray("workouts") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val s = SecondaryPull.epochOf(o.optString("start"), zone)
                val e = SecondaryPull.epochOf(o.optString("end"), zone)
                if (s <= 0 || e <= s) continue
                items.put(
                    JSONObject().put("type", "workout").put("start", s).put("end", e)
                        .put("minutes", ((e - s) / 60).toInt())
                        .put("exercise_type", o.optInt("type", 0))
                        .put("title", o.optString("title"))
                        .put("origin", o.optString("origin"))
                )
            }
        }.onFailure { android.util.Log.w(TAG, "push: 读 workout 失败", it) }

        // weight（WeightRecord → HcQuery.bodyMeasurements 的 JSON）
        runCatching {
            val doc = HcQuery.bodyMeasurements(client, fromDate, toDate)
            val arr = doc.optJSONArray("records") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val ts = SecondaryPull.epochOf(o.optString("at"), zone)
                val kg = o.optDouble("kg", 0.0)
                if (ts <= 0 || kg <= 0.0) continue
                items.put(
                    JSONObject().put("type", "weight").put("ts", ts).put("kg", kg)
                        .put("origin", o.optString("origin"))
                )
            }
        }.onFailure { android.util.Log.w(TAG, "push: 读 weight 失败", it) }

        if (items.length() == 0) return 0 to "HC 里没有 workout/weight 可推"
        val direct = postDirect(ingestUrl, token, items)
        if (direct.first > 0 || !direct.second.startsWith("失败")) return direct
        val viaMcp = postMcp(mcpUrl, token, items)
        return if (viaMcp.first > 0 || !viaMcp.second.startsWith("失败")) viaMcp
        else direct   // 两条都不行，报直连的那个原因
    }

    /** 直连 `POST /ingest`：body 就是 {"items":[…]}。 */
    private fun postDirect(url: String, token: String, items: JSONArray): Pair<Int, String> =
        send(url, token, JSONObject().put("items", items).toString()) { text ->
            val acc = runCatching { JSONObject(text).optJSONObject("accepted") }.getOrNull()
            val n = (acc?.optInt("workout") ?: 0) + (acc?.optInt("weight") ?: 0)
            n to "直连推送 $n 条（workout ${acc?.optInt("workout") ?: 0} / weight ${acc?.optInt("weight") ?: 0}）"
        }

    /** 回落：MCP `tools/call` 调 ingest 工具。 */
    private fun postMcp(url: String, token: String, items: JSONArray): Pair<Int, String> =
        send(url, token, JSONObject()
            .put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call")
            .put("params", JSONObject().put("name", "ingest")
                .put("arguments", JSONObject().put("items", items))).toString()) { text ->
            val inner = runCatching {
                JSONObject(JSONObject(text).getJSONObject("result").getJSONArray("content")
                    .getJSONObject(0).getString("text"))
            }.getOrNull() ?: return@send 0 to "响应异常：${text.take(100)}"
            val acc = inner.optJSONObject("accepted")
            val n = (acc?.optInt("workout") ?: 0) + (acc?.optInt("weight") ?: 0)
            n to "MCP 推送 $n 条（workout ${acc?.optInt("workout") ?: 0} / weight ${acc?.optInt("weight") ?: 0}）"
        }

    private fun send(
        url: String,
        token: String,
        body: String,
        parse: (String) -> Pair<Int, String>
    ): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 60000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
            }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) return 0 to "失败 HTTP $code ${text.take(100)}"
            parse(text)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "push 失败 $url", t)
            0 to "失败 ${t.message?.take(100)}"
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
