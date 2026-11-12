package id.harness.hcbridge

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import id.harness.hcbridge.auth.McpConfig
import id.harness.hcbridge.auth.McpDb
import id.harness.hcbridge.auth.SqlMcpStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/**
 * `McpService` 的 HTTP 合约（计划 Task 7）：直接打 `127.0.0.1:8765`。
 *
 * 用**裸 socket** 而不是 `HttpURLConnection`：要能造出「声明 300 KB 但一个字节都不发」
 * 和「20 KB 请求头」这类形状，`HttpURLConnection` 不允许。
 *
 * 每条用例注入临时库（`Store.overrideForTest`），所以服务读到的是干净配置：
 * `requireAuth = true`、`allowLoopbackNoAuth = false`、无公网、无 tunnel ⇒ 一律要 Bearer。
 */
@RunWith(AndroidJUnit4::class)
class McpServiceHttpTest {

    private lateinit var ctx: Context
    private lateinit var store: SqlMcpStore
    private lateinit var dbName: String

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        dbName = "t-${UUID.randomUUID()}.db"
        store = SqlMcpStore(McpDb(ctx, dbName))
        Store.overrideForTest(store)
        McpConfig(store).apply {
            requireAuth = true
            allowLoopbackNoAuth = false
            confirmNewClients = true
            publicBase = null
            tunnelMode = McpConfig.TUNNEL_OFF
        }
        startService()
        waitForPort()
    }

    @After
    fun tearDown() {
        ctx.stopService(Intent(ctx, McpService::class.java))
        waitUntil(5_000) { !McpService.RUNNING }
        Store.overrideForTest(null)
        store.close()
        ctx.deleteDatabase(dbName)
    }

    // ------------------------------------------------------------ socket 小工具

    private fun startService() {
        val intent = Intent(ctx, McpService::class.java)
        try {
            androidx.core.content.ContextCompat.startForegroundService(ctx, intent)
        } catch (t: Throwable) {
            throw AssertionError("启动 McpService 失败（前台服务限制？）：${t.javaClass.simpleName}")
        }
    }

    private fun waitForPort() {
        val ok = waitUntil(10_000) {
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", McpService.PORT), 200) }
                true
            } catch (t: Throwable) {
                false
            }
        }
        assertTrue("127.0.0.1:${McpService.PORT} 没起来", ok)
        assertTrue(McpService.RUNNING)
    }

    private fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(50)
        }
        return cond()
    }

    /** 发一段原始 HTTP，读完整个响应（服务端 `Connection: close` ⇒ 读到 EOF 就是完整响应）。 */
    private fun raw(head: String, body: String = ""): String {
        val out = ByteArrayOutputStream()
        Socket().use { s ->
            s.soTimeout = 5_000
            s.connect(InetSocketAddress("127.0.0.1", McpService.PORT), 2_000)
            val os = s.getOutputStream()
            os.write(head.toByteArray(Charsets.UTF_8))
            if (body.isNotEmpty()) os.write(body.toByteArray(Charsets.UTF_8))
            os.flush()
            val inp = s.getInputStream()
            val buf = ByteArray(8_192)
            while (true) {
                val n = try {
                    inp.read(buf)
                } catch (t: Throwable) {
                    break
                }
                if (n <= 0) break
                out.write(buf, 0, n)
            }
        }
        return out.toString("UTF-8")
    }

    private fun post(path: String, body: String, extraHeaders: String = ""): String {
        val head = "POST $path HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
            extraHeaders + "\r\n"
        return raw(head, body)
    }

    private fun get(path: String): String =
        raw("GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")

    private fun statusOf(resp: String): Int =
        resp.substringAfter("HTTP/1.1 ").substringBefore(' ').trim().toIntOrNull() ?: -1

    private fun bodyOf(resp: String): String = resp.substringAfter("\r\n\r\n")

    // ---------------------------------------------------------------- 用例

    @Test
    fun mcpWithoutTokenIs401() {
        val resp = post("/mcp", """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        assertEquals(401, statusOf(resp))
        // 401 必须告诉客户端去哪儿取令牌（RFC 9728 资源元数据）
        val head = resp.substringBefore("\r\n\r\n").lowercase()
        assertTrue(head.contains("www-authenticate: bearer"))
        assertTrue(head.contains("resource_metadata"))
        assertTrue(head.contains(".well-known/oauth-protected-resource"))
    }

    @Test
    fun discoveryIsPublic() {
        val resp = get("/.well-known/oauth-protected-resource/mcp")
        assertEquals(200, statusOf(resp))
        val body = bodyOf(resp)
        assertTrue(body.contains("\"resource\""))
        assertTrue(body.contains("health.read"))
        assertTrue(body.contains("health.write"))
        // 发现文档不许被缓存
        assertTrue(resp.substringBefore("\r\n\r\n").lowercase().contains("no-store"))
    }

    @Test
    fun adminPathsDoNotExist() {
        val getAdmin = get("/admin")
        assertEquals(404, statusOf(getAdmin))
        val postTokens = post("/admin/tokens", "{}")
        assertEquals(404, statusOf(postTokens))
    }

    @Test
    fun healthIsOk() {
        val resp = get("/health")
        assertEquals(200, statusOf(resp))
        assertTrue(bodyOf(resp).contains("\"ok\""))
    }

    @Test
    fun oversizedDeclaredBodyIs413BeforeRead() {
        // 声明 300 KB，但一个字节都不发：服务必须在读体之前就拒，不能等客户端
        val resp = raw(
            "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 300000\r\n\r\n",
        )
        assertEquals(413, statusOf(resp))
    }

    @Test
    fun oversizedHeadIs413() {
        val pad = "a".repeat(20_000)
        val resp = raw("GET /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nX-Pad: $pad\r\n\r\n")
        assertEquals(413, statusOf(resp))
    }

    @Test
    fun loopbackIsNotExemptWhileRequireAuthIsOn() {
        // allowLoopbackNoAuth 开着、但 requireAuth 仍为开 ⇒ 回环也必须带令牌
        McpConfig(store).allowLoopbackNoAuth = true
        val resp = post("/mcp", """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        assertEquals(401, statusOf(resp))
    }

    @Test
    fun forwardedHeaderNeverGetsLoopbackExemption() {
        // 两个开关都关掉也不够：带转发头（tunnel 借道回环）必须 401
        McpConfig(store).apply {
            requireAuth = false
            allowLoopbackNoAuth = true
            publicBase = null
            tunnelMode = McpConfig.TUNNEL_OFF
        }
        val resp = post(
            "/mcp",
            """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""",
            extraHeaders = "CF-Connecting-IP: 203.0.113.9\r\n",
        )
        assertEquals(401, statusOf(resp))
    }
}
