package id.harness.hcbridge.auth

import id.harness.hcbridge.TunnelProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cloudflare Tunnel 的**纯文本**合约（计划 Task 8）。
 *
 * 守的四件事：
 * 1. 命令形态：named tunnel 只用 `--token`（**隧道名不作位置参数**，`cloudflared tunnel run <名字>` 会
 *    去读本机的 `cert.pem`，手机上根本没有）；quick tunnel 钉死回环端口并关自动更新。
 * 2. 公网基址：空白 = 只走本机；否则必须是**裸 https origin**（无路径、无查询、无尾斜杠）。
 * 3. 守卫：`tunnelMode != "off"` 或 `publicBase != null` ⇒ `requireAuth` 强制为 true，
 *    回环豁免同时失效（`trustedLoopback` 的两条前置条件）。
 * 4. 探活：只认 2xx **且**元数据 `resource` 与公网基址一致；其余一律不可达。
 *
 * 这里**不碰网络**：`TunnelProbe` 的 `fetch` 是 `fun interface`，测试注入假实现（计划要求
 * "纯 JVM 配置校验/命令构造/探活解析，测试里不出现 Android 网络"）。
 */
class TunnelTest {

    // ------------------------------------------------------------------ 命令

    @Test
    fun namedTunnelCommandHasNoPositionalName() {
        val cmd = Tunnel.commandFor("named", "TOKEN_VALUE", "hc-bridge-phone")
        assertTrue(cmd.startsWith("cloudflared tunnel run --token "))
        assertFalse(cmd.contains(" hc-bridge-phone")) // 不把隧道名当位置参数
        assertTrue(cmd.contains("TOKEN_VALUE"))
    }

    @Test
    fun namedTunnelWithoutTokenUsesEnvPlaceholder() {
        val cmd = Tunnel.commandFor(McpConfig.TUNNEL_NAMED, null, null)
        assertTrue(cmd.startsWith("cloudflared tunnel run --token "))
        assertFalse(cmd.contains("null"))
        assertTrue(cmd.contains("TUNNEL_TOKEN")) // 占位：命令可以安全地贴进聊天/文档
    }

    @Test
    fun quickTunnelCommandPinsLocalPort() {
        val cmd = Tunnel.commandFor("quick", null, null)
        assertEquals("cloudflared tunnel --url http://127.0.0.1:8765 --no-autoupdate", cmd)
    }

    @Test
    fun offModeHasNoCommand() {
        assertEquals("", Tunnel.commandFor(McpConfig.TUNNEL_OFF, "whatever", null))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownModeIsRejected() {
        Tunnel.commandFor("srv", null, null)
    }

    @Test
    fun tokenWithShellMetacharactersIsQuoted() {
        val cmd = Tunnel.commandFor(McpConfig.TUNNEL_NAMED, "a b;rm -rf /", null)
        assertTrue(cmd.startsWith("cloudflared tunnel run --token '"))
        assertTrue(cmd.endsWith("'"))
    }

    @Test
    fun modeLabelsCoverAllThreeModes() {
        assertEquals(3, McpConfig.TUNNEL_MODES.size)
        for (m in McpConfig.TUNNEL_MODES) assertTrue(Tunnel.modeLabel(m).isNotBlank())
        assertEquals("srv", Tunnel.modeLabel("srv")) // 未知模式原样回显，不抛
    }

    // ------------------------------------------------------------ 公网基址

    @Test
    fun publicBaseMustBeHttpsWithoutPath() {
        assertTrue(Tunnel.validatePublicBase(null))
        assertTrue(Tunnel.validatePublicBase("   "))
        assertTrue(Tunnel.validatePublicBase("https://hc.example.com"))
        assertTrue(Tunnel.validatePublicBase("https://hc.example.com:8443"))
        assertTrue(Tunnel.validatePublicBase("https://abc-def.trycloudflare.com"))
        assertFalse(Tunnel.validatePublicBase("http://hc.example.com"))
        assertFalse(Tunnel.validatePublicBase("https://hc.example.com/mcp"))
        assertFalse(Tunnel.validatePublicBase("https://hc.example.com/"))
        assertFalse(Tunnel.validatePublicBase("https://hc.example.com?a=1"))
        assertFalse(Tunnel.validatePublicBase("https://hc.example.com#x"))
        assertFalse(Tunnel.validatePublicBase("https://user:pw@hc.example.com"))
        assertFalse(Tunnel.validatePublicBase("https://"))
        assertFalse(Tunnel.validatePublicBase("hc.example.com"))
        assertFalse(Tunnel.validatePublicBase("ftp://hc.example.com"))
        assertFalse(Tunnel.validatePublicBase("https://hc.example.com:99999"))
    }

    // ------------------------------------------------------------- 归一化

    @Test
    fun publicBaseIsNormalisedBeforeStoring() {
        // host 折小写、`:443` 归一化掉：否则 issuer 有两个写法，探活比对也会假失败
        assertEquals("https://hc.example.com", Tunnel.normalizePublicBase("https://HC.Example.COM:443"))
        assertEquals("https://hc.example.com:8443", Tunnel.normalizePublicBase("https://hc.example.com:8443"))
        assertEquals("https://abc-def.trycloudflare.com", Tunnel.normalizePublicBase(" https://abc-def.trycloudflare.com "))
        assertNull(Tunnel.normalizePublicBase(null))
        assertNull(Tunnel.normalizePublicBase("   "))
        assertNull(Tunnel.normalizePublicBase("http://hc.example.com"))
        assertNull(Tunnel.normalizePublicBase("https://hc.example.com/mcp"))
        assertNull(Tunnel.normalizePublicBase("https://hc.example.com/"))
        // 归一化后的值必须能直接喂给 Endpoints，且归一化幂等
        val once = Tunnel.normalizePublicBase("https://HC.Example.COM:443")
        assertEquals(once, Tunnel.normalizePublicBase(once))
        assertEquals("https://hc.example.com/mcp", Endpoints.from(once).resource)
    }

    // ---------------------------------------------------------------- 守卫

    @Test
    fun guardForcesAuthForTunnelOrPublicBase() {
        val c = McpConfig(InMemoryStore())
        c.requireAuth = false
        c.tunnelMode = "quick"
        c.publicBase = "https://abc.trycloudflare.com"
        Tunnel.applyGuards(c)
        assertTrue(c.requireAuth)

        val d = McpConfig(InMemoryStore())
        d.requireAuth = false
        d.publicBase = "https://hc.example.com"
        Tunnel.applyGuards(d)
        assertTrue(d.requireAuth)

        val e = McpConfig(InMemoryStore())
        e.requireAuth = false
        Tunnel.applyGuards(e)
        assertFalse(e.requireAuth) // 两样都没开 ⇒ 不擅自改管理员设置
    }

    @Test
    fun tunnelModeAloneForcesAuthToo() {
        val c = McpConfig(InMemoryStore())
        c.requireAuth = false
        c.tunnelMode = McpConfig.TUNNEL_NAMED
        Tunnel.applyGuards(c)
        assertTrue(c.requireAuth)
        assertTrue(Tunnel.publicEnabled(c))
    }

    @Test
    fun publicBaseAlsoKillsLoopbackExemption() {
        val c = McpConfig(InMemoryStore())
        c.requireAuth = false
        c.allowLoopbackNoAuth = true
        c.publicBase = "https://hc.example.com"
        val local = HttpReq("POST", "/mcp", emptyMap(), emptyMap(), "{}", peerLoopback = true)
        assertFalse(trustedLoopback(local, c)) // 公网基址一填，回环豁免立即失效
    }

    @Test
    fun tunnelModeAlsoKillsLoopbackExemption() {
        val c = McpConfig(InMemoryStore())
        c.requireAuth = false
        c.allowLoopbackNoAuth = true
        c.tunnelMode = McpConfig.TUNNEL_QUICK
        val local = HttpReq("POST", "/mcp", emptyMap(), emptyMap(), "{}", peerLoopback = true)
        assertFalse(trustedLoopback(local, c))
    }

    // -------------------------------------------------- 公网可转发的路径

    @Test
    fun onlyPublicPathsAreForwardedAndAdminNeverIs() {
        assertTrue(Tunnel.isPublicPath("/mcp"))
        assertTrue(Tunnel.isPublicPath("/mcp/"))
        assertTrue(Tunnel.isPublicPath("/.well-known/oauth-authorization-server"))
        assertTrue(Tunnel.isPublicPath("/authorize"))
        assertTrue(Tunnel.isPublicPath("/token"))
        assertTrue(Tunnel.isPublicPath("/register"))
        assertTrue(Tunnel.isPublicPath("/revoke"))
        assertTrue(Tunnel.isPublicPath("/health"))

        assertFalse(Tunnel.isPublicPath("/admin"))
        assertFalse(Tunnel.isPublicPath("/admin/tokens"))
        assertFalse(Tunnel.isPublicPath("/"))
        assertTrue(Tunnel.isAdminPath("/admin"))
        assertTrue(Tunnel.isAdminPath("/admin/tokens"))
        assertFalse(Tunnel.isAdminPath("/mcp"))
        assertFalse(Tunnel.isAdminPath("/administrator"))
        // 管理面不进隧道放行表：这两张表不能有交集
        for (p in Tunnel.PUBLIC_PATHS) assertFalse(Tunnel.isAdminPath(p))
    }

    // ---------------------------------------------------------------- 探活

    @Test
    fun probeReportsReachability() {
        val ok = TunnelProbe { _, _ -> 200 to """{"resource":"https://hc.example.com/mcp"}""" }
            .probe("https://hc.example.com")
        assertTrue(ok.reachable)
        assertTrue(ok.ms >= 0)

        val down = TunnelProbe { _, _ -> 502 to "" }.probe("https://hc.example.com")
        assertFalse(down.reachable)
        assertTrue(down.note.contains("502"))
    }

    @Test
    fun probeChecksHealthThenResourceAndNeedsBoth() {
        val seen = ArrayList<String>()
        val probe = TunnelProbe { url, _ ->
            seen.add(url)
            200 to """{"resource":"https://hc.example.com/mcp"}"""
        }
        assertTrue(probe.probe("https://hc.example.com").reachable)
        assertTrue(seen.any { it.endsWith("/health") })
        assertTrue(seen.any { it.endsWith("/.well-known/oauth-protected-resource/mcp") })
    }

    @Test
    fun quickCommandUsesTheSharedLoopbackPort() {
        assertTrue(Tunnel.commandFor(McpConfig.TUNNEL_QUICK, null, null).contains("127.0.0.1:${Endpoints.PORT}"))
        assertEquals("http://127.0.0.1:${Endpoints.PORT}", Endpoints.local().issuer)
    }

    @Test
    fun probeComparesNormalisedResource() {
        val seen = ArrayList<String>()
        // 用户粘的是大小写混合 + :443；归一化后 resource 是 https://hc.example.com/mcp
        val r = TunnelProbe { url, _ ->
            seen.add(url)
            if (url.endsWith("/health")) 200 to "{\"ok\":true}"
            else 200 to "{\"resource\":\"https://hc.example.com/mcp\"}"
        }.probe("https://HC.Example.COM:443")
        assertTrue(r.reachable)
        assertTrue(seen.all { it.startsWith("https://hc.example.com/") })
    }

    @Test
    fun probeExplainsRedirectsInsteadOfBareStatus() {
        val r = TunnelProbe { _, _ -> 301 to "" }.probe("https://hc.example.com")
        assertFalse(r.reachable)
        assertTrue(r.note.contains("301"))
        assertTrue(r.note.contains("重定向"))
    }

    @Test
    fun probeRejectsWrongResource() {
        val r = TunnelProbe { url, _ ->
            if (url.endsWith("/health")) 200 to """{"ok":true}"""
            else 200 to """{"resource":"https://other.example.com/mcp"}"""
        }.probe("https://hc.example.com")
        assertFalse(r.reachable)
        assertTrue(r.note.contains("resource"))
    }

    @Test
    fun probeNeedsHttpsAndSurvivesThrowingFetch() {
        var called = false
        val notHttps = TunnelProbe { _, _ ->
            called = true
            200 to ""
        }.probe("http://hc.example.com")
        assertFalse(notHttps.reachable)
        assertFalse(called) // 非 https 不发请求

        val boom = TunnelProbe { _, _ -> throw java.io.IOException("boom") }.probe("https://hc.example.com")
        assertFalse(boom.reachable)
        assertNotNull(boom.note)
        assertNull(boom.note.takeIf { it.contains("boom") }) // 异常文本不进结果，只留类名
    }
}
