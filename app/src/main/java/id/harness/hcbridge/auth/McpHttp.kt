package id.harness.hcbridge.auth

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import org.json.JSONObject

/**
 * 一次 HTTP 请求的**已拆包**形态（计划 Task 5）。
 *
 * [path] 不含查询串；[query] 是已解码的参数（同名只取第一个）；[headers] 的键**全小写**；
 * [body] 是已解码的文本（字节上限由 [BodyReader] 在拆包阶段就挡住）；
 * [peerLoopback] 由 socket 层给出（对端地址是 `127.0.0.1` / `::1`）。
 *
 * 本文件**不得** import 任何 `android.*`（JVM 单测要用）。
 */
data class HttpReq(
    val method: String,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
    val peerLoopback: Boolean = false,
)

/**
 * 一次 HTTP 响应（状态码 + 头 + 已编码的文本）。
 *
 * 头名按**规范大小写**存（`Content-Type` / `Location` / `WWW-Authenticate` …）：
 * `respond()` 原样写到 socket，测试也照这个名字断言。
 * `Content-Length` / `Connection` 由拆包层补，不在这里写。
 */
data class HttpResp(val status: Int, val headers: Map<String, String>, val body: String) {

    companion object {

        const val JSON_CT = "application/json; charset=utf-8"
        const val HTML_CT = "text/html; charset=utf-8"
        const val TEXT_CT = "text/plain; charset=utf-8"

        /** JSON 响应；`Cache-Control: no-store` 是默认（令牌/授权码/发现文档都不许缓存）。 */
        fun json(status: Int, obj: JSONObject, extra: Map<String, String> = emptyMap()): HttpResp =
            HttpResp(
                status,
                mapOf("Content-Type" to JSON_CT, "Cache-Control" to "no-store") + extra,
                obj.toString(),
            )

        /** HTML 页面；同样 `no-store`，并压掉 MIME 嗅探。 */
        fun html(status: Int, html: String): HttpResp = HttpResp(
            status,
            mapOf(
                "Content-Type" to HTML_CT,
                "Cache-Control" to "no-store",
                "X-Content-Type-Options" to "nosniff",
            ),
            html,
        )

        fun text(status: Int, body: String, extra: Map<String, String> = emptyMap()): HttpResp = HttpResp(
            status,
            mapOf(
                "Content-Type" to TEXT_CT,
                "Cache-Control" to "no-store",
                "X-Content-Type-Options" to "nosniff",
            ) + extra,
            body,
        )
    }
}

/** 状态短语表（`respond()` 用；未知码回 `Status`，与 `HttpURLConnection` 一致）。 */object HttpStatus {

    fun reason(code: Int): String = when (code) {
        200 -> "OK"
        201 -> "Created"
        202 -> "Accepted"
        204 -> "No Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        303 -> "See Other"
        304 -> "Not Modified"
        307 -> "Temporary Redirect"
        308 -> "Permanent Redirect"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        408 -> "Request Timeout"
        409 -> "Conflict"
        410 -> "Gone"
        411 -> "Length Required"
        413 -> "Payload Too Large"
        414 -> "URI Too Long"
        415 -> "Unsupported Media Type"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        501 -> "Not Implemented"
        503 -> "Service Unavailable"
        else -> "Status"
    }
}

/**
 * 按**字节**读请求头与请求体（计划 Task 5 Step 4 / Task 7 Step 3）。
 *
 * 三条硬约束（改代码前先读）：
 * 1. **不按 `Content-Length` 预分配**：`Content-Length: 1GB` 不该让进程先要 1GB 内存；
 *    [read] 只按 8 KB 分块累积，超 [CAP] 立即返回 `null`。
 * 2. [readHead] 只消费到 `\r\n\r\n`（含）为止，body 字节留在**同一个**流里给 [read]；
 *    所以拆包层**不能**用 `BufferedReader`（它会预读、把 body 吃掉），而要先把 socket 的
 *    `InputStream` 套一层 `java.io.BufferedInputStream`（[readHead] 是逐字节读的，裸 socket 等于一次一 syscall）。
 * 3. 上限按**字节**判，不按字符判：多字节 UTF-8 的 256 K 字符可能是 768 KB。
 */
object BodyReader {

    /** 请求体上限 256 KB。 */
    const val CAP = 262_144

    /** 请求头上限 16 KB（正常头几百字节；2 万字节的头是攻击或写坏的客户端）。 */
    const val HEAD_CAP = 16_384

    private const val CHUNK = 8_192
    private const val CR = 13
    private const val LF = 10

    /**
     * 读到 `\r\n\r\n`（含）为止，按 ISO-8859-1 解码（头只能是 ASCII，解码不丢字节）。
     *
     * 最多读 [cap] 个字节：第 cap 个字节正好收尾空行 ⇒ 成功（头长这样是合法的）；
     * 再多一个字节就是 `null`。流提前结束也是 `null`；**不**读 body。
     */
    fun readHead(inp: InputStream, cap: Int = HEAD_CAP): String? {
        if (cap <= 0) return null
        val out = ByteArrayOutputStream(256)
        var b0 = -1
        var b1 = -1
        var b2 = -1
        var b3 = -1
        while (out.size() < cap) {
            val b = try {
                inp.read()
            } catch (e: SocketTimeoutException) {
                throw e            // 读超时不是"头太长"：交给调用方回 408
            } catch (e: Exception) {
                return null
            }
            if (b < 0) return null
            out.write(b)
            b0 = b1
            b1 = b2
            b2 = b3
            b3 = b
            if (b0 == CR && b1 == LF && b2 == CR && b3 == LF) {
                return String(out.toByteArray(), Charsets.ISO_8859_1)
            }
        }
        return null
    }

    /** 声明长度就该拒（负数也算坏头 ⇒ 拒）。 */
    fun declaredTooLarge(contentLength: Int?, cap: Int = CAP): Boolean =
        contentLength != null && (contentLength < 0 || contentLength > cap)

    /**
     * 读请求体。
     *
     * - [declared] 非空 ⇒ 最多读这么多字节（不预分配；流提前结束就返回已读部分）；
     * - [declared] 为空 ⇒ 一直读到 EOF，但**攒到 `cap + 1` 就停**并返回 `null`；
     * - 声明超限、读到超限 ⇒ `null`。
     */
    fun read(inp: InputStream, declared: Int?, cap: Int = CAP): ByteArray? {
        if (declaredTooLarge(declared, cap)) return null
        val limit = declared ?: (cap + 1)
        val out = ByteArrayOutputStream(CHUNK)
        val buf = ByteArray(CHUNK)
        var total = 0
        while (total < limit) {
            val want = minOf(buf.size, limit - total)
            val n = try {
                inp.read(buf, 0, want)
            } catch (e: SocketTimeoutException) {
                throw e            // 同上：超时 ≠ 超限
            } catch (e: Exception) {
                return null
            }
            if (n < 0) break
            if (n == 0) continue
            out.write(buf, 0, n)
            total += n
        }
        if (declared == null && total > cap) return null
        return out.toByteArray()
    }
}

/**
 * 把 [HttpResp] 写到 socket 上（HTTP/1.1、`Connection: close`）。
 *
 * - 状态行用 [HttpStatus.reason] 的短语（`401 Unauthorized`、`302 Found`、`410 Gone` …）；
 * - `resp.headers` **全部**写出，只有 `Content-Length` / `Connection` 例外 —— 这两个由这里算，
 *   免得调用方写错长度或让连接被复用（本服务的每条连接只用一次）；
 * - 头值里的 CR/LF 折成空格：`Location` 可能带客户端给的 `state`，绝不能让一个值拆成两行；
 * - body 按 UTF-8 编码，`Content-Length` 报的是**字节数**。
 */
fun respond(out: java.io.OutputStream, resp: HttpResp) {
    val body = resp.body.toByteArray(Charsets.UTF_8)
    val head = buildString(256) {
        append("HTTP/1.1 ").append(resp.status).append(' ').append(HttpStatus.reason(resp.status)).append("\r\n")
        for ((rawName, rawValue) in resp.headers) {
            val name = rawName.trim()
            if (name.isEmpty()) continue
            if (name.equals("Content-Length", ignoreCase = true)) continue
            if (name.equals("Connection", ignoreCase = true)) continue
            append(name).append(": ").append(rawValue.replace('\r', ' ').replace('\n', ' ')).append("\r\n")
        }
        append("Content-Length: ").append(body.size).append("\r\n")
        append("Connection: close\r\n\r\n")
    }
    out.write(head.toByteArray(Charsets.ISO_8859_1))
    out.write(body)
    out.flush()
}
