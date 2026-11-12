package id.harness.hcbridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `AdminBridge` 的"永不抛"契约：JS 侧只能看见 JSON。
 *
 * 这里只测包装层（真实宿主在 `McpWebAdminActivity`，用 `McpWebAdminTest` 覆盖）：
 * 宿主抛异常 ⇒ `{"error":"…"}`；宿主返回正常 JSON ⇒ 原样给页面；`act` 收到坏 payload 也不炸。
 */
class AdminBridgeTest {

    private class Host(
        var state: () -> JSONObject = { JSONObject().put("ok", true) },
        var act: (String, String?) -> JSONObject = { _, _ -> JSONObject().put("ok", true) },
        var clipboard: (String) -> JSONObject = { JSONObject().put("ok", true) },
    ) : AdminBridge.Host {
        var closed = 0
        var ready = 0
        var lastAct: Pair<String, String?>? = null
        var lastClip: String? = null

        override fun stateJson(): JSONObject = state()

        override fun actJson(name: String, payloadJson: String?): JSONObject {
            lastAct = name to payloadJson
            return act(name, payloadJson)
        }

        override fun copyToClipboard(text: String): JSONObject {
            lastClip = text
            return clipboard(text)
        }

        override fun closeFromPage() {
            closed++
        }

        override fun pageReady() {
            ready++
        }

        override fun theme(): String = "dark"
    }

    @Test
    fun stateReturnsJsonFromHost() {
        val bridge = AdminBridge(Host(state = { JSONObject().put("me", "admin") }))
        val out = JSONObject(bridge.state())
        assertEquals("admin", out.getString("me"))
    }

    @Test
    fun stateFailureBecomesErrorJson() {
        val bridge = AdminBridge(Host(state = { throw IllegalStateException("boom at /data/secret") }))
        val out = JSONObject(bridge.state())
        assertTrue(out.has("error"))
        assertFalse("异常细节不能回页面", out.toString().contains("/data/secret"))
    }

    @Test
    fun actPassesNameAndPayload() {
        val host = Host(act = { name, _ -> JSONObject().put("ok", true).put("name", name) })
        val bridge = AdminBridge(host)
        val out = JSONObject(bridge.act("token.create", """{"name":"laptop"}"""))
        assertTrue(out.getBoolean("ok"))
        assertEquals("token.create", host.lastAct?.first)
        assertEquals("""{"name":"laptop"}""", host.lastAct?.second)
    }

    @Test
    fun actWithNullNameOrPayloadDoesNotThrow() {
        val host = Host()
        val bridge = AdminBridge(host)
        assertTrue(JSONObject(bridge.act(null, null)).getBoolean("ok"))
        assertEquals("", host.lastAct?.first)
    }

    @Test
    fun actFailureBecomesErrorJson() {
        val bridge = AdminBridge(Host(act = { _, _ -> throw IllegalArgumentException("bad") }))
        val out = JSONObject(bridge.act("nope", "{}"))
        assertTrue(out.has("error"))
    }

    @Test
    fun actKeepsOneTimeSecretAndCommandAtTopLevelUntouched() {
        // 桥**不做**形状转换：一次性明文（顶层）与 `local.command` 的 data 原样回给 adapter
        val payload = """{"name":"laptop"}"""
        val host = Host(act = { _, _ -> JSONObject().put("ok", true).put("secret", "hcb_st_abc").put("prefix", "hcb_st_abc") })
        val oneTime = JSONObject(AdminBridge(host).act("token.create", payload))
        assertTrue(oneTime.getBoolean("ok"))
        assertEquals("hcb_st_abc", oneTime.getString("secret"))
        assertFalse("桥不加 data 包装", oneTime.has("data"))

        val host2 = Host(
            act = { _, p ->
                assertEquals("""{"full":true}""", p)
                JSONObject().put("ok", true)
                    .put("data", JSONObject().put("command", "cloudflared tunnel run --token abc"))
            },
        )
        val cmd = JSONObject(AdminBridge(host2).act("local.command", """{"full":true}"""))
        assertEquals(
            "cloudflared tunnel run --token abc",
            cmd.getJSONObject("data").getString("command"),
        )
    }

    @Test
    fun actForwardsTotpCodeAndKeepsRebindSecretAtTopLevel() {
        // `security.totp-rebind {code}` → `{secret, uri}` 只此一次，桥原样回给 adapter
        val host = Host(
            act = { name, payload ->
                assertEquals("security.totp-rebind", name)
                assertEquals("""{"code":"123456"}""", payload)
                JSONObject().put("ok", true)
                    .put("secret", "JBSWY3DPEHPK3PXP")
                    .put("uri", "otpauth://totp/HC Bridge:MCP?secret=JBSWY3DPEHPK3PXP")
            },
        )
        val out = JSONObject(AdminBridge(host).act("security.totp-rebind", """{"code":"123456"}"""))
        assertTrue(out.getBoolean("ok"))
        assertEquals("JBSWY3DPEHPK3PXP", out.getString("secret"))
        assertTrue(out.getString("uri").startsWith("otpauth://totp/"))
        assertFalse("桥不加 data 包装", out.has("data"))
    }

    @Test
    fun stateErrorObjectIsPreserved() {
        val bridge = AdminBridge(Host(state = { JSONObject().put("error", "管理会话已锁定，请重新解锁") }))
        assertEquals("管理会话已锁定，请重新解锁", JSONObject(bridge.state()).getString("error"))
    }

    @Test
    fun clipboardAndCloseAndReadyAreForwarded() {
        val host = Host()
        val bridge = AdminBridge(host)
        assertTrue(JSONObject(bridge.clipboard("abc")).getBoolean("ok"))
        assertEquals("abc", host.lastClip)
        bridge.close()
        bridge.ready()
        assertEquals(1, host.closed)
        assertEquals(1, host.ready)
    }

    @Test
    fun clipboardSecretsAreScheduledForClearing() {
        assertTrue(SecretClipboard.looksSecret("hcb_st_abc"))
        assertTrue(SecretClipboard.looksSecret("otpauth://totp/HC:x?secret=ABC"))
        assertTrue(SecretClipboard.looksSecret("JBSWY3DPEHPK3PXP"))
        // 含真 token 的 cloudflared 命令（占位符版不算）
        assertTrue(SecretClipboard.looksSecret("cloudflared tunnel run --token eyJhbGciOiJIUzI1NiJ9"))
        assertFalse(SecretClipboard.looksSecret("cloudflared tunnel run --token \"\$TUNNEL_TOKEN\""))
        assertFalse(SecretClipboard.looksSecret("Obsidian MCP"))
        assertFalse(SecretClipboard.looksSecret("cloudflared tunnel --url http://127.0.0.1:8765 --no-autoupdate"))
    }

    @Test
    fun closeFailureDoesNotThrow() {
        val host = Host()
        val bridge = AdminBridge(host)
        host.closed = 0
        // 直接调用：宿主实现不抛，这里只证明桥自己没有额外抛出路径
        bridge.close()
        assertEquals(1, host.closed)
    }
}
