package id.harness.hcbridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import id.harness.hcbridge.logic.SecondaryPull
import id.harness.hcbridge.logic.SleepMerge
import id.harness.hcbridge.logic.SyncDiagnosis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 副设备（secondary）同步：不碰小米云，改为从自建 MCP（NAS，存全量）拉数据写入本机 Health Connect，
 * 实现"健康数据跨设备互通"（例如平板只做镜像）。
 *
 * 主设备（primary）：小米云 → 本机 HC（SyncWorker 路径）。
 *
 * 失败必须**说话**：预检 DNS → 每个指标各自的失败原因都进 notes → 写进状态文件。
 * （2026-10-18 平板那次四个 `query_*` 全失败却只有空 notes，人只能看到一个过期状态。）
 */
object SecondarySync {

    private const val TAG = "HCBridge"

    private class Rpc(val json: JSONObject?, val error: String?)

    private fun rpc(url: String, token: String?, tool: String, args: JSONObject): Rpc {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 60000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json, text/event-stream")
                if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
            }
            val body = JSONObject()
                .put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call")
                .put("params", JSONObject().put("name", tool).put("arguments", args))
                .toString().toByteArray()
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                android.util.Log.w(TAG, "secondary 拉取失败 $tool: HTTP $code ${text.take(160)}")
                return Rpc(null, "HTTP $code ${text.take(120)}")
            }
            val content = JSONObject(text).optJSONObject("result")
                ?.optJSONArray("content")?.optJSONObject(0)?.optString("text")
            if (content.isNullOrBlank()) Rpc(null, "响应里没有 result.content[0].text")
            else Rpc(JSONObject(content), null)
        } catch (t: Throwable) {
            val msg = SyncDiagnosis.describe(t)
            android.util.Log.w(TAG, "secondary 拉取失败 $tool: $msg")
            Rpc(null, msg)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** 失败收尾：写状态文件（state=secondary-failed + hint + notes），返回 (0, 说明)。 */
    private fun fail(ctx: Context, msg: String): Pair<Int, String> {
        android.util.Log.w(TAG, "secondary 中止：$msg")
        SyncStatus.write(ctx, "secondary-failed", 0, Prefs.lastTs(ctx), JSONObject()
            .put("hint", msg).put("notes", msg))
        return 0 to msg
    }

    /** 返回 (写入样本数, 说明) */
    suspend fun run(ctx: Context): Pair<Int, String> = withContext(Dispatchers.IO) {
        val pre = prepare(ctx) ?: return@withContext fail(ctx, "Health Connect 不可用")
        val now = System.currentTimeMillis() / 1000
        val fromTs = (Prefs.lastTs(ctx).takeIf { it > 0 } ?: (now - 2 * 86400)).coerceAtLeast(now - 7 * 86400L) - 900
        pullCore(ctx, pre, fromTs, now, now - 14 * 86400, writeStatus = true)
    }

    /**
     * **主设备也能从 server 拉**：只拉不写状态文件（由调用方决定怎么记）。
     *
     * 用的时候：小米云那边断了/整段空（夜间掉网、手环没上传、历史空洞），
     * 而 NAS 上的 health.db 自己有一份 1825 天回补 —— 桥就直接从它那里取，不必等上游。
     */
    suspend fun pullWindow(ctx: Context, fromTs: Long, toTs: Long): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val pre = prepare(ctx) ?: return@withContext (0 to "Health Connect 不可用")
            pullCore(ctx, pre, fromTs, toTs, fromTs, writeStatus = false)
        }

    private class Prepared(val client: HealthConnectClient, val url: String, val token: String)

    /** 共同前置：HC 可用 + 远端配置齐 + 域名能解（失败原因说人话）。 */
    private suspend fun prepare(ctx: Context): Prepared? {
        val client = runCatching { HealthConnectClient.getOrCreate(ctx) }.getOrNull() ?: return null
        val url = Prefs.remoteUrl(ctx)
        val token = Prefs.remoteToken(ctx)
        if (token.isNullOrBlank()) return null
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return null
        val resolved = runCatching { java.net.InetAddress.getByName(host); true }.getOrDefault(false)
        return if (resolved) Prepared(client, url, token) else null
    }

    /** 真正的拉取：心率 / 血氧 / 睡眠 / 步数。 */
    private suspend fun pullCore(
        ctx: Context,
        pre: Prepared,
        fromTs: Long,
        now: Long,
        sleepFromTs: Long,
        writeStatus: Boolean
    ): Pair<Int, String> {
        val client = pre.client
        val url = pre.url
        val token = pre.token
        val zone: ZoneId = ZoneId.systemDefault()
        // 服务端解析器只接受 **日期**（%Y-%m-%d）；带时间或 ISO 都会 ValueError
        val fmt = { t: Long -> LocalDate.ofInstant(Instant.ofEpochSecond(t), zone).toString() }
        var written = 0
        val notes = ArrayList<String>()

        // 心率
        runCatching {
            val r = rpc(url, token, "query_heart_rate", JSONObject()
                .put("start_date", fmt(fromTs)).put("end_date", fmt(now)).put("limit", 20000))
            if (r.error != null) { notes.add("心率失败：${r.error}"); return@runCatching }
            val samples = SecondaryPull.heartRate(r.json!!, zone)
            val (n, _) = HcWriter.insertHeartRate(client, samples)
            written += n
            notes.add("心率 $n/${samples.size}")
        }.onFailure { notes.add("心率失败：${SyncDiagnosis.describe(it)}") }

        // 血氧
        runCatching {
            val r = rpc(url, token, "query_spo2", JSONObject()
                .put("start_date", fmt(fromTs)).put("end_date", fmt(now)).put("limit", 5000))
            if (r.error != null) { notes.add("血氧失败：${r.error}"); return@runCatching }
            val samples = SecondaryPull.spo2(r.json!!, zone)
            written += HcWriter.insertSpo2(client, samples)
            notes.add("血氧 ${samples.size}")
        }.onFailure { notes.add("血氧失败：${SyncDiagnosis.describe(it)}") }

        // 睡眠：服务端 sessions(start/end 为 "yyyy-MM-dd HH:mm:ss")
        runCatching {
            val r = rpc(url, token, "query_sleep", JSONObject()
                .put("start_date", fmt(sleepFromTs)).put("end_date", fmt(now)))
            if (r.error != null) { notes.add("睡眠失败：${r.error}"); return@runCatching }
            val sessions = SecondaryPull.sleepSessions(r.json!!, zone)
                .map { SleepMerge.Session(it.first, it.second, title = it.third) }
            val res = HcWriter.reconcileSleep(client, sessions, zone, ctx.packageName)
            written += res.inserted
            notes.add(
                "睡眠 +${res.inserted}/-${res.deleted}" +
                    (if (res.skippedForeign > 0) "(已有${res.skippedForeign})" else "")
            )
        }.onFailure { notes.add("睡眠失败：${SyncDiagnosis.describe(it)}") }

        // 步数：服务端只给"按天"序列；本机当天已有步数就跳过，避免多次同步叠加
        runCatching {
            val have = HcQuery.stepsToday(client)
            if (have > 0) { notes.add("步数已有($have)"); return@runCatching }
            val r = rpc(url, token, "query_metric_series", JSONObject()
                .put("metric", "steps").put("start_date", fmt(now - 7 * 86400)).put("end_date", fmt(now)))
            if (r.error != null) { notes.add("步数失败：${r.error}"); return@runCatching }
            val daily = SecondaryPull.dailySteps(r.json!!, zone)
            android.util.Log.i(TAG, "diag: steps pull days=${daily.size} first=${daily.firstOrNull()}")
            val n = HcWriter.insertDailySteps(client, daily)
            written += n
            notes.add("步数 $n")
        }.onFailure { notes.add("步数失败：${SyncDiagnosis.describe(it)}") }

        val failed = notes.any { it.contains("失败") }
        val noteText = notes.joinToString("，").ifBlank { "无新数据" }
        android.util.Log.i(TAG, "server pull done: written=$written notes=$noteText")
        if (writeStatus) {
            SyncStatus.write(
                ctx,
                if (failed) "secondary-failed" else "secondary-ok",
                written,
                now,
                JSONObject()
                    .put("notes", noteText)
                    .put("hint", notes.firstOrNull { it.contains("失败") } ?: "")
            )
        }
        if (written > 0) {
            Prefs.clearCoverageCache(ctx)   // 覆盖率缓存作废，UI 才会显示新条数
            Snapshot.invalidate()
        }
        return written to noteText
    }
}
