package id.harness.hcbridge.auth

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 请求拆包的 JVM 合约（计划 Task 7 复审 Critical 1 / Minor 2、3）。
 *
 * 这里守的核心回归：**没有 `Content-Length` 的请求必须立刻当成空体**。
 * 旧实现用 `BodyReader.read(inp, null)`（读到 EOF），于是 `GET /health`、
 * 发现文档、`GET /authorize` 全部挂住 —— 客户端等响应、服务端等 EOF。
 *
 * 两个层次：
 * - [socketGetRepliesWithoutClientClosing]：**真 socket** + [HttpConn] + [McpRouter] + [respond]，
 *   客户端写完请求**不关写端**直接读响应（证明不再等 EOF）；
 * - 其余：PipedInputStream / 自定义流直接喂 [HttpConn.read]，覆盖 411/413/408/400。
 */
class HttpConnTest {

    // ------------------------------------------------------- 真 socket 端到端

    /** 最小"服务端"：一条连接、一个请求、`Connection: close`（与 `McpService` 同构）。 */
    private fun exchange(request: String): String? {
        val store = InMemoryStore()
        val router = McpRouter(store, McpConfig(store))
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { ss ->
            val server = thread(name = "http-conn-test") {
                try {
                    ss.accept().use { s ->
                        s.soTimeout = 5_000
                        val inbound = HttpConn.read(BufferedInputStream(s.getInputStream()), peerLoopback = true)
                        val resp = when (inbound) {
                            is Inbound.Reject -> HttpResp.text(inbound.status, inbound.message)
                            is Inbound.Ok -> when (val r = router.route(inbound.req)) {
                                is Route.Respond -> r.resp
                                is Route.Mcp -> HttpResp.text(501, "not wired in this test")
                            }
                        }
                        respond(s.getOutputStream(), resp)
                    }
                } catch (t: Throwable) {
                    // 客户端已经读完了就能接受（close 时的 reset 也算）
                }
            }
            return Socket("127.0.0.1", ss.localPort).use { c ->
                c.soTimeout = 5_000
                val os = c.getOutputStream()
                os.write(request.toByteArray(Charsets.UTF_8))
                os.flush()
                // 故意不 close()：客户端还在等响应（这正是旧实现会挂住的形状）
                val out = c.getInputStream().readBytes().toString(Charsets.UTF_8)
                server.join(5_000)
                out
            }
        }
    }

    @Test
    fun socketGetRepliesWithoutClientClosing() {
        val started = System.nanoTime()
        val resp = exchange("GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("GET /health 没有在 5 秒内回包（又去等 EOF 了？）", elapsedMs < 5_000)
        assertNotNull(resp)
        assertTrue("状态行不对：${resp!!.substringBefore("\r\n")}", resp.startsWith("HTTP/1.1 200 "))
        assertTrue(resp.substringAfter("\r\n\r\n").contains("\"ok\""))
        assertTrue("必须写 Content-Length", resp.contains("Content-Length: "))
    }

    @Test
    fun socketDiscoveryRepliesWithoutClientClosing() {
        val resp = exchange(
            "GET /.well-known/oauth-protected-resource/mcp HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n",
        )
        assertNotNull(resp)
        assertTrue(resp!!.startsWith("HTTP/1.1 200 "))
        val body = resp.substringAfter("\r\n\r\n")
        assertTrue(body.contains("\"resource\""))
        assertTrue(body.contains("health.read"))
        assertTrue(body.contains("health.write"))
    }

    @Test
    fun socketPostWithoutBodyReplies() {
        val resp = exchange("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 0\r\n\r\n")
        assertNotNull(resp)
        // 无令牌 ⇒ 401（说明请求真的被路由到了，没挂住）
        assertTrue(resp!!.startsWith("HTTP/1.1 401 "))
        assertTrue(resp.lowercase().contains("www-authenticate: bearer"))
    }

    // ------------------------------------------------------- 流级（拒绝路径）

    /** 写一段请求但**不关**写端：读操作必须自己停下来。 */
    private fun readFromOpenPipe(text: String): Inbound {
        val out = PipedOutputStream()
        val inp = PipedInputStream(out, 64 * 1024)
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
        return HttpConn.read(inp, peerLoopback = true)
    }

    @Test
    fun getWithoutContentLengthIsEmptyBodyNotEof() {
        val inbound = readFromOpenPipe("GET /authorize?client_id=x HTTP/1.1\r\nHost: h\r\n\r\n")
        assertTrue(inbound is Inbound.Ok)
        val req = (inbound as Inbound.Ok).req
        assertEquals("GET", req.method)
        assertEquals("/authorize", req.path)
        assertEquals("x", req.query["client_id"])
        assertEquals("", req.body)
        assertTrue(req.peerLoopback)
    }

    @Test
    fun headWithoutContentLengthIsEmptyBody() {
        val inbound = readFromOpenPipe("HEAD /health HTTP/1.1\r\nHost: h\r\n\r\n")
        assertEquals("", (inbound as Inbound.Ok).req.body)
    }

    @Test
    fun declaredBodyIsRead() {
        val body = """{"jsonrpc":"2.0"}"""
        val inbound = readFromOpenPipe(
            "POST /mcp HTTP/1.1\r\nHost: h\r\nContent-Length: ${body.length}\r\n\r\n$body",
        )
        assertEquals(body, (inbound as Inbound.Ok).req.body)
    }

    @Test
    fun chunkedIsRejectedWith411() {
        val inbound = readFromOpenPipe(
            "POST /mcp HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n",
        )
        assertEquals(411, (inbound as Inbound.Reject).status)
    }

    @Test
    fun badContentLengthIsRejected() {
        val inbound = readFromOpenPipe("POST /mcp HTTP/1.1\r\nHost: h\r\nContent-Length: abc\r\n\r\n")
        assertEquals(400, (inbound as Inbound.Reject).status)
    }

    @Test
    fun oversizedDeclaredBodyIsRejectedWithoutReading() {
        // 声明 300 KB 但一个字节都不发：必须立刻 413（不能等、也不能先分配）
        val started = System.nanoTime()
        val inbound = readFromOpenPipe("POST /mcp HTTP/1.1\r\nHost: h\r\nContent-Length: 300000\r\n\r\n")
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(413, (inbound as Inbound.Reject).status)
        assertTrue("不该阻塞：${elapsedMs}ms", elapsedMs < 2_000)
    }

    @Test
    fun oversizedHeadIsRejected() {
        val pad = "a".repeat(20_000)
        val inbound = readFromOpenPipe("GET /mcp HTTP/1.1\r\nHost: h\r\nX-Pad: $pad\r\n\r\n")
        assertEquals(413, (inbound as Inbound.Reject).status)
    }

    @Test
    fun badRequestLineIs400() {
        assertEquals(400, (readFromOpenPipe("GARBAGE\r\n\r\n") as Inbound.Reject).status)
        assertEquals(400, (readFromOpenPipe("\r\n\r\n") as Inbound.Reject).status)
    }

    @Test
    fun readTimeoutIs408() {
        val timingOut = object : InputStream() {
            override fun read(): Int = throw SocketTimeoutException("timeout")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw SocketTimeoutException("timeout")
        }
        assertEquals(408, (HttpConn.read(timingOut, peerLoopback = true) as Inbound.Reject).status)
    }

    @Test
    fun headersAreLowercasedAndFirstValueWins() {
        val inbound = readFromOpenPipe(
            "POST /mcp HTTP/1.1\r\nHost: h\r\nX-A: one\r\nX-A: two\r\nContent-Length: 0\r\n\r\n",
        )
        val req = (inbound as Inbound.Ok).req
        assertEquals("one", req.headers["x-a"])
        assertFalse(req.headers.containsKey("X-A"))
    }

    @Test
    fun queryIsDecoded() {
        val inbound = readFromOpenPipe("GET /authorize?state=a%2Bb&req=hcb_rq_1 HTTP/1.1\r\nHost: h\r\n\r\n")
        val q = (inbound as Inbound.Ok).req.query
        assertEquals("a+b", q["state"])
        assertEquals("hcb_rq_1", q["req"])
    }

    @Test
    fun byteArrayInputStillSupportsExplicitBody() {
        // ByteArrayInputStream 是"完整请求"的语义：有 Content-Length 就读那么多，没有就是空体
        val raw = "POST /mcp HTTP/1.1\r\nContent-Length: 2\r\n\r\n{}".toByteArray()
        val inbound = HttpConn.read(ByteArrayInputStream(raw), peerLoopback = false)
        assertEquals("{}", (inbound as Inbound.Ok).req.body)
        val noBody = HttpConn.read(ByteArrayInputStream("GET /health HTTP/1.1\r\n\r\n".toByteArray()), false)
        assertEquals("", (noBody as Inbound.Ok).req.body)
        assertFalse(noBody.req.peerLoopback)
    }

    @Test
    fun routerEndpointsHelpersDoNotNeedAnInstance() {
        val store = InMemoryStore()
        val config = McpConfig(store)
        assertEquals(Endpoints.local().issuer, McpRouter.endpointsOf(config).issuer)
        assertFalse(McpRouter.endpointsFallbackOf(config))
        config.publicBase = "https://hc.example.com"
        assertEquals("https://hc.example.com", McpRouter.endpointsOf(config).issuer)
        assertEquals("https://hc.example.com/mcp", McpRouter.endpointsOf(config).resource)
        assertFalse(McpRouter.endpointsFallbackOf(config))
        // 坏值：退回本机，并如实报告 fallback
        store.setSetting("publicBase", "http://insecure.example.com")
        assertEquals(Endpoints.local().issuer, McpRouter.endpointsOf(config).issuer)
        assertTrue(McpRouter.endpointsFallbackOf(config))
        // 顺带确认实例方法同源（companion 与实例不会分叉）
        assertEquals(McpRouter.endpointsOf(config).issuer, McpRouter(store, config).endpoints().issuer)
    }
}
