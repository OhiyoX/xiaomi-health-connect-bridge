package id.harness.hcbridge.auth

import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.URLDecoder

/** 从 socket 读到的结果：要么是一个可路由的请求，要么是一个「立刻回这个状态码」的拒绝。 */
sealed class Inbound {

    data class Ok(val req: HttpReq) : Inbound()

    /** [status] 是给客户端的 HTTP 状态；[message] 是固定文案（不含任何请求内容）。 */
    data class Reject(val status: Int, val message: String) : Inbound()
}

/**
 * HTTP/1.1 请求拆包（**纯 JVM**，计划 Task 7；`McpService` 只负责把结果交给 [McpRouter]）。
 *
 * 关键语义（改之前先读，这里踩过坑）：
 * - **没有 `Content-Length`（或它是 0）⇒ body 是空**，绝不"读到 EOF"：GET 的客户端在等响应、
 *   服务端在等 EOF，双方一起挂住（`BodyReader.read(inp, null)` 的 EOF 语义只给字节数组测试用）。
 * - `Transfer-Encoding`（chunked 等）⇒ **411**，不当空体：把 chunked 的体当"空"会让后续字节被
 *   当成下一个请求（请求走私面）。本服务只接受定长体。
 * - 声明长度超 [BodyReader.CAP] ⇒ 413，且**不读** body（`Content-Length: 1GB` 不该先分配 1GB）。
 * - 头 > [BodyReader.HEAD_CAP] ⇒ 413；请求行坏 ⇒ 400；读超时（socket `soTimeout`）⇒ 408。
 * - 参数按 URL 解码（同名只留第一个，坏转义保留原文），头键**全小写**。
 *
 * 本文件**不得** import 任何 `android.*`（JVM 单测直接喂流/真 socket）。
 */
object HttpConn {

    fun read(inp: InputStream, peerLoopback: Boolean): Inbound {
        val head = try {
            BodyReader.readHead(inp)
        } catch (e: SocketTimeoutException) {
            return Inbound.Reject(408, "request timeout")
        } ?: return Inbound.Reject(413, "header too large")

        val requestLine = head.substringBefore("\r\n")
        val parts = requestLine.split(" ")
        if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return Inbound.Reject(400, "bad request")
        }
        val headers = parseHeaders(head)

        // 只支持定长体：chunked 当空体是请求走私的经典入口
        if (!headers["transfer-encoding"].isNullOrBlank()) {
            return Inbound.Reject(411, "length required")
        }
        val declared = if (headers.containsKey("content-length")) {
            headers["content-length"]?.trim()?.toIntOrNull()
                ?: return Inbound.Reject(400, "bad content-length")
        } else {
            null
        }
        if (BodyReader.declaredTooLarge(declared)) {
            return Inbound.Reject(413, "payload too large")
        }
        // 没有声明长度 ⇒ 空体（GET/HEAD 和无体的 POST 都走这里）
        val body: ByteArray = if (declared == null || declared == 0) {
            ByteArray(0)
        } else {
            try {
                BodyReader.read(inp, declared)
            } catch (e: SocketTimeoutException) {
                return Inbound.Reject(408, "request timeout")
            } ?: return Inbound.Reject(413, "payload too large")
        }

        val (path, query) = splitTarget(parts[1])
        return Inbound.Ok(
            HttpReq(
                method = parts[0].trim().uppercase(),
                path = path,
                query = query,
                headers = headers,
                body = String(body, Charsets.UTF_8),
                peerLoopback = peerLoopback,
            ),
        )
    }

    /** 头块 → 小写键的 map（同名只留第一个；值去首尾空白）。 */
    internal fun parseHeaders(head: String): Map<String, String> {
        val out = HashMap<String, String>()
        val lines = head.split("\r\n")
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val name = line.substring(0, idx).trim().lowercase()
            if (name.isEmpty() || out.containsKey(name)) continue
            out[name] = line.substring(idx + 1).trim()
        }
        return out
    }

    /** `/authorize?req=x&y=1` → (`/authorize`, {req=x, y=1})。 */
    internal fun splitTarget(target: String): Pair<String, Map<String, String>> {
        val q = target.indexOf('?')
        val path = if (q < 0) target else target.substring(0, q)
        if (q < 0 || q == target.length - 1) return path to emptyMap()
        val out = HashMap<String, String>()
        for (pair in target.substring(q + 1).split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val rawName = if (eq < 0) pair else pair.substring(0, eq)
            val rawValue = if (eq < 0) "" else pair.substring(eq + 1)
            val name = urlDecode(rawName)
            if (name.isEmpty() || out.containsKey(name)) continue
            out[name] = urlDecode(rawValue)
        }
        return path to out
    }

    private fun urlDecode(v: String): String = try {
        URLDecoder.decode(v, "UTF-8")
    } catch (e: Exception) {
        v
    }
}
