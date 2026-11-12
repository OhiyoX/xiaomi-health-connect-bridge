package id.harness.hcbridge

import id.harness.hcbridge.auth.Endpoints
import id.harness.hcbridge.auth.Tunnel
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** 单个探活响应体的读取上限：发现文档只有几百字节，超过就让 JVM 别再吃内存。 */
const val TUNNEL_PROBE_MAX_BODY = 64 * 1024

/**
 * 一次 GET（状态码 + 文本）。抽成接口只为一件事：**单测里不碰网络**。
 *
 * 实现只有 [HttpUrlFetch]；测试注入假实现（计划 Task 8「纯 JVM … 探活解析」）。
 */
fun interface HttpFetch {
    /**
     * 注意：`fun interface` 的抽象方法**不能**带默认值（Kotlin 直接拒绝），
     * 超时由 [TunnelProbe] 显式传（[TunnelProbe.DEFAULT_TIMEOUT_MS]）。
     */
    fun get(url: String, timeoutMs: Int): Pair<Int, String>
}

/**
 * 真网络实现，仿 [Remote]：`HttpURLConnection`、固定超时、**不带任何凭据**。
 *
 * 只读两个公开端点（`/health`、`/.well-known/oauth-protected-resource/mcp`），
 * 所以不需要 tunnel token，也不会把 token 发给 Cloudflare 之外的地方。
 */
class HttpUrlFetch : HttpFetch {

    override fun get(url: String, timeoutMs: Int): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Accept", "application/json")
                // 故意不加 Authorization：探活只证明"公网能到这个 app"，不证明凭据有效
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return code to readCapped(stream)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** 最多读 [TUNNEL_PROBE_MAX_BODY] 字节（字符数按字节上界近似）：边缘出错时不能把整个错误页读进来。 */
    private fun readCapped(stream: InputStream?): String {
        if (stream == null) return ""
        val out = StringBuilder()
        val chunk = CharArray(4096)
        stream.bufferedReader().use { r ->
            while (out.length < TUNNEL_PROBE_MAX_BODY) {
                val n = r.read(chunk, 0, minOf(chunk.size, TUNNEL_PROBE_MAX_BODY - out.length))
                if (n < 0) break
                out.append(chunk, 0, n)
            }
        }
        return out.toString()
    }
}

/**
 * 公网可达性探活（计划 Task 8）：经 Cloudflare 的公网地址回头打自己。
 *
 * 两步都要过才算可达：
 * 1. `GET <base>/health` ⇒ 2xx（Cloudflare 边缘 + tunnel + app 这一整条链路活着）；
 * 2. `GET <base>/.well-known/oauth-protected-resource/mcp` ⇒ 2xx **且** `resource == <base>/mcp`
 *    （证明公网基址填的就是这个 app，而不是别的隧道/别的服务）。
 *
 * 任一失败 ⇒ `reachable = false`，[Result.note] 给可读原因。
 * 异常只记**类名**（message 可能带主机名/端口，不再往外抛）。
 * 调用方负责放到后台线程（本类阻塞，最长约 2 × `DEFAULT_TIMEOUT_MS`）。
 */
class TunnelProbe(private val fetch: HttpFetch = HttpUrlFetch()) {

    data class Result(val reachable: Boolean, val ms: Long, val note: String)

    fun probe(publicBase: String): Result {
        val base = Tunnel.normalizePublicBase(publicBase)
            ?: return Result(false, 0, "公网基址必须是 https://host（不带路径/查询/尾部斜杠）")
        // 期望值取**归一化**后的端点：`Endpoints` 会把 host 折小写、去掉 `:443`，
        // 用原串比 `resource` 会永远不等（假"不可达"）。
        val expected = Endpoints.from(base)
        val started = System.currentTimeMillis()
        return try {
            val (healthCode, _) = fetch.get("${expected.issuer}/health", DEFAULT_TIMEOUT_MS)
            if (healthCode !in 200..299) {
                return Result(false, elapsed(started), "健康检查" + statusNote(healthCode))
            }
            val (code, body) = fetch.get(expected.resourceMetadataUrl, DEFAULT_TIMEOUT_MS)
            if (code !in 200..299) {
                return Result(false, elapsed(started), "元数据端点" + statusNote(code))
            }
            val resource = runCatching { JSONObject(body).optString("resource") }.getOrDefault("")
            when {
                resource.isEmpty() && body.length >= TUNNEL_PROBE_MAX_BODY ->
                    Result(false, elapsed(started), "元数据响应超过 ${TUNNEL_PROBE_MAX_BODY / 1024} KB，读不全")
                resource.isEmpty() ->
                    Result(false, elapsed(started), "元数据里没有 resource（这个地址不是本服务）")
                resource != expected.resource ->
                    Result(false, elapsed(started), "元数据 resource=$resource，与公网基址不一致")
                else -> Result(true, elapsed(started), "可达，公网 issuer 已生效")
            }
        } catch (t: Throwable) {
            Result(false, elapsed(started), "连接失败（${t.javaClass.simpleName}）")
        }
    }

    /**
     * 状态码的可读原因。3xx 单独说：`cloudflared` 没起时公网地址常常是边缘的 301/302，
     * 而"HTTP 301"本身看不出是"被重定向"。
     */
    private fun statusNote(code: Int): String = when (code) {
        in 300..399 -> " 被重定向（HTTP $code，未跟随）：cloudflared 没在跑，或该地址强制 https/跳到了别处"
        in 400..499 -> " HTTP $code：路径不对，或被前置代理拒绝"
        in 500..599 -> " HTTP $code：边缘/隧道/服务端错误"
        else -> " HTTP $code"
    }

    private fun elapsed(started: Long): Long = System.currentTimeMillis() - started

    companion object {
        /** 单个请求的超时（一次探活最多等两轮）。 */
        const val DEFAULT_TIMEOUT_MS = 8000
    }
}
