package id.harness.hcbridge.auth

import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * HTTP 路由、状态页、鉴权门、限流、JSON-RPC 过滤（计划 Task 5）。
 *
 * 覆盖的是**线上行为**，不是实现细节：
 * - 路由表（含"没有 `/admin`"）、方法不允许、404；
 * - `/authorize` 首屏幂等（303 到 `?req=`）、`?req=` 的三分支（200 等待页 / 302 / 410）；
 * - 只有 pending 等待页免限流，`/token` 60/min、`/authorize` 30/min、伪造 `cf-connecting-ip` 换不到桶；
 * - 回环豁免的六条条件（含转发头反例）；
 * - 401 挑战带 `resource_metadata`、413 在读体前、`tools/list` 按主体过滤、`ingest` 不授予任何工具。
 */
class McpRouterTest {

    private val store = InMemoryStore()
    private val now = 1_700_000_000_000L
    private val limiter = RateLimiter { now }
    private val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    private val challenge = Crypto.pkceS256(verifier)

    private lateinit var clientId: String

    @Before
    fun setUp() {
        clientId = registerClient(confirmNewClients = false)
    }

    // ---------------------------------------------------------------- 测试助手

    private fun req(
        method: String,
        path: String,
        body: String = "",
        peerLoopback: Boolean = false,
        headers: Map<String, String> = emptyMap(),
        query: Map<String, String> = emptyMap(),
    ): HttpReq = HttpReq(
        method = method,
        path = path,
        query = query,
        headers = headers.mapKeys { it.key.lowercase() },
        body = body,
        peerLoopback = peerLoopback,
    )

    private fun cfg(
        requireAuth: Boolean = true,
        allowLoopbackNoAuth: Boolean = false,
        publicBase: String? = null,
        tunnelMode: String = McpConfig.TUNNEL_OFF,
    ): McpConfig =
        // 每个 cfg 用**独立**的 settings store：McpConfig 是懒读 store 的，共用会
        // 让前面一行 cfg(...) 静默改掉后面断言的配置（简报里的转发头循环就曾被这样掩盖）。
        McpConfig(InMemoryStore()).also {
            it.requireAuth = requireAuth
            it.allowLoopbackNoAuth = allowLoopbackNoAuth
            it.publicBase = publicBase
            it.tunnelMode = tunnelMode
        }

    private fun router(
        requireAuth: Boolean = true,
        allowLoopbackNoAuth: Boolean = false,
        publicBase: String? = null,
        tunnelMode: String = McpConfig.TUNNEL_OFF,
        rl: RateLimiter = limiter,
    ): McpRouter = McpRouter(
        store,
        cfg(requireAuth, allowLoopbackNoAuth, publicBase, tunnelMode),
        { now },
        rl,
    )

    private fun registerClient(confirmNewClients: Boolean, redirect: String = REDIRECT): String =
        OAuth.registerClient(
            store,
            JSONObject()
                .put("client_name", "router test")
                .put("redirect_uris", JSONArray().put(redirect)),
            confirmNewClients,
            now,
        ).getString("client_id")

    private fun authorizeQuery(
        client: String = clientId,
        scope: String? = null,
        state: String? = "st-1",
        redirect: String = REDIRECT,
    ): Map<String, String> {
        val q = linkedMapOf(
            "response_type" to "code",
            "client_id" to client,
            "redirect_uri" to redirect,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
        )
        if (scope != null) q["scope"] = scope
        if (state != null) q["state"] = state
        return q
    }

    private fun Route.asResp(): HttpResp = (this as Route.Respond).resp

    private fun Route.asMcp(): Route.Mcp = this as Route.Mcp

    private fun pendingIdFrom(html: String): String =
        Regex("""req=([A-Za-z0-9_-]+)""").find(html)!!.groupValues[1]

    private fun newPendingViaAuthorize(scope: String? = null): String {
        val r = router().route(req("GET", "/authorize", query = authorizeQuery(scope = scope))).asResp()
        assertEquals(200, r.status)
        return pendingIdFrom(r.body)
    }

    /** 走完整授权码流程拿一对令牌（顺带覆盖 `/authorize` → `/token` 的联调）。 */
    private fun newTokenPair(granted: Set<String> = setOf(ToolCatalog.SCOPE_READ)): JSONObject {
        val id = newPendingViaAuthorize(scope = granted.joinToString(" "))
        assertTrue(OAuth.decidePending(store, id, approve = true, grantedScopes = granted, now = now))
        val status = router().route(req("GET", "/authorize", query = mapOf("req" to id))).asResp()
        assertEquals(302, status.status)
        val code = Regex("""[?&]code=([^&]+)""").find(status.headers.getValue("Location"))!!.groupValues[1]
        val form = listOf(
            "grant_type=authorization_code",
            "client_id=$clientId",
            "code=${URLEncoder.encode(code, "UTF-8")}",
            "redirect_uri=${URLEncoder.encode(REDIRECT, "UTF-8")}",
            "code_verifier=$verifier",
        ).joinToString("&")
        val token = router().route(req("POST", "/token", body = form)).asResp()
        assertEquals(200, token.status)
        return JSONObject(token.body)
    }

    private fun newAccessToken(granted: Set<String> = setOf(ToolCatalog.SCOPE_READ)): String =
        newTokenPair(granted).getString("access_token")

    // ---------------------------------------------------------------- 回环豁免

    @Test
    fun loopbackExemptionNeedsExplicitOptInAndNoProxyHeaders() {
        val base = req("POST", "/mcp", body = "{}", peerLoopback = true)
        val optedIn = cfg(false, true, null, "off")
        assertTrue(trustedLoopback(base, optedIn))                                    // 唯一放行组合
        assertFalse(trustedLoopback(base, cfg(false, false, null, "off")))            // 没显式 opt-in ⇒ 仍要鉴权
        assertFalse(trustedLoopback(base, cfg(true, true, null, "off")))              // requireAuth=true 压过 opt-in
        assertFalse(trustedLoopback(base, cfg(false, true, "https://hc.example.com", "named")))
        assertFalse(trustedLoopback(base, cfg(false, true, "https://hc.example.com", "off")))  // 有公网基址 ⇒ 不豁免
        assertFalse(trustedLoopback(base, cfg(false, true, null, "quick")))
        assertFalse(trustedLoopback(base.copy(peerLoopback = false), optedIn))         // 非回环对端
        // 显式重直一次：下面的转发头循环必须在一个**真的会豁免**的配置上跑，
        // 否则“不豁免”可能只是因为别的条件没满足（假阳性）。
        assertTrue(trustedLoopback(base, optedIn))
        for (h in listOf("cf-connecting-ip", "x-forwarded-for", "cf-ray", "x-real-ip", "forwarded", "true-client-ip")) {
            assertFalse(
                "must not exempt with $h",
                trustedLoopback(base.copy(headers = mapOf(h to "203.0.113.9")), optedIn),
            )
        }
    }

    @Test
    fun loopbackExemptionYieldsFullLocalPrincipal() {
        val mcp = router(requireAuth = false, allowLoopbackNoAuth = true)
            .route(req("POST", "/mcp", body = "{}", peerLoopback = true))
            .asMcp()
        assertEquals("loopback", mcp.principal.kind)
        assertEquals(setOf(ToolCatalog.SCOPE_READ, ToolCatalog.SCOPE_WRITE), mcp.principal.scopes)
        assertEquals(ToolCatalog.ALL_TOOLS, mcp.principal.allowedTools)
    }

    @Test
    fun loopbackExemptionIsLostAsSoonAsAForwardingHeaderAppears() {
        val r = router(requireAuth = false, allowLoopbackNoAuth = true)
            .route(
                req(
                    "POST", "/mcp", body = "{}", peerLoopback = true,
                    headers = mapOf("cf-connecting-ip" to "203.0.113.9"),
                ),
            ).asResp()
        assertEquals(401, r.status)
    }

    @Test
    fun publicBaseAloneKillsLoopbackExemptionEndToEnd() {
        // tunnelMode=off 但配了公网基址：豁免必须失效，401 挑战指向公网 resource 元数据
        val r = router(requireAuth = false, allowLoopbackNoAuth = true, publicBase = "https://hc.example.com")
            .route(req("POST", "/mcp", body = "{}", peerLoopback = true))
            .asResp()
        assertEquals(401, r.status)
        assertTrue(
            r.headers.getValue("WWW-Authenticate")
                .contains("https://hc.example.com/.well-known/oauth-protected-resource/mcp"),
        )
    }

    @Test
    fun brokenPublicBaseFallsBackToLocalAndIsReported() {
        val broken = router(publicBase = "http://hc.example.com") // 非 https ⇒ Endpoints.from 抛
        val md = broken.route(req("GET", "/.well-known/oauth-authorization-server")).asResp()
        assertEquals(200, md.status)
        assertEquals("http://127.0.0.1:8765", JSONObject(md.body).getString("issuer"))
        assertTrue(broken.endpointsFallback())
        assertFalse(router().endpointsFallback())
    }

    // ---------------------------------------------------------------- /mcp 鉴权门

    @Test
    fun noTokenOnMcpIs401WithResourceMetadata() {
        val r = router().route(req("POST", "/mcp", body = "{}", peerLoopback = true)) as Route.Respond
        assertEquals(401, r.resp.status)
        assertTrue(
            r.resp.headers.getValue("WWW-Authenticate")
                .contains("""resource_metadata="http://127.0.0.1:8765/.well-known/oauth-protected-resource/mcp""""),
        )
        assertFalse("must not echo token material", r.resp.body.contains("hcb_"))
    }

    @Test
    fun unknownTokenIs401WithInvalidTokenError() {
        val r = router().route(
            req("POST", "/mcp", body = "{}", headers = mapOf("authorization" to "Bearer hcb_at_nope")),
        ).asResp()
        assertEquals(401, r.status)
        assertTrue(r.headers.getValue("WWW-Authenticate").contains("error=\"invalid_token\""))
    }

    @Test
    fun mcpAcceptsAccessTokenAndCarriesScopeNarrowedPrincipal() {
        val token = newAccessToken()
        val mcp = router().route(
            req("POST", "/mcp", body = "{}", headers = mapOf("authorization" to "Bearer $token")),
        ).asMcp()
        assertEquals("oauth", mcp.principal.kind)
        assertEquals(setOf(ToolCatalog.SCOPE_READ), mcp.principal.scopes)
        assertEquals(ToolCatalog.READ_TOOLS, mcp.principal.allowedTools)
    }

    @Test
    fun refreshTokenIsNotAcceptedAtMcp() {
        // 真的换一对令牌：刷新令牌不能当访问令牌用，但同 family 的访问令牌必须还能用
        val pair = newTokenPair()
        val refresh = pair.getString("refresh_token")
        assertTrue(refresh.startsWith(TokenFormat.REFRESH_PREFIX))
        val denied = router().route(
            req("POST", "/mcp", body = "{}", headers = mapOf("authorization" to "Bearer $refresh")),
        ).asResp()
        assertEquals(401, denied.status)
        val ok = router().route(
            req("POST", "/mcp", body = "{}", headers = mapOf("authorization" to "Bearer ${pair.getString("access_token")}")),
        )
        assertEquals("refresh rejection must not kill the family", "oauth", ok.asMcp().principal.kind)
    }

    @Test
    fun getOnMcpIs405AndSubPathsAreProtected() {
        assertEquals(405, router().route(req("GET", "/mcp")).asResp().status)
        assertEquals(405, router().route(req("DELETE", "/mcp")).asResp().status)
        assertEquals(
            "POST",
            router().route(req("GET", "/mcp")).asResp().headers.getValue("Allow"),
        )
        assertEquals(401, router().route(req("POST", "/mcp/v1", body = "{}", peerLoopback = true)).asResp().status)
    }

    @Test
    fun oversizedDeclaredBodyIsRejectedBeforeAuth() {
        val r = router().route(req("POST", "/mcp", headers = mapOf("content-length" to "300000"))).asResp()
        assertEquals(413, r.status)
    }

    @Test
    fun oversizedUndeclaredBodyIsRejectedToo() {
        val body = "a".repeat(BodyReader.CAP + 1)
        assertEquals(413, router().route(req("POST", "/mcp", body = body, peerLoopback = true)).asResp().status)
    }

    @Test
    fun bodyCapIsMeasuredInBytesNotChars() {
        // 87_382 个三字节汉字 ≈ 262 146 字节 > CAP，字符数却远小于 CAP
        val body = "\u6c49".repeat(BodyReader.CAP / 3 + 1)
        assertTrue(McpRouter.utf8Length(body) > BodyReader.CAP)
        assertEquals(413, router().route(req("POST", "/mcp", body = body, peerLoopback = true)).asResp().status)
    }

    // ---------------------------------------------------------------- 发现端点 / 健康 / 无管理面

    @Test
    fun discoveryEndpointsArePublicAndOnlyAnnounceTwoScopes() {
        val md = router().route(req("GET", "/.well-known/oauth-authorization-server")).asResp()
        assertEquals(200, md.status)
        assertEquals("http://127.0.0.1:8765", JSONObject(md.body).getString("issuer"))

        for (path in listOf("/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp")) {
            val r = router().route(req("GET", path)).asResp()
            assertEquals(200, r.status)
            val obj = JSONObject(r.body)
            assertEquals("http://127.0.0.1:8765/mcp", obj.getString("resource"))
            val scopes = obj.getJSONArray("scopes_supported")
            assertEquals(2, scopes.length())
            assertFalse(scopes.toList().contains(ToolCatalog.SCOPE_INGEST))
        }
    }

    @Test
    fun healthIsPublic() {
        val r = router().route(req("GET", "/health")).asResp()
        assertEquals(200, r.status)
        assertTrue(r.body.contains("\"ok\""))
    }

    @Test
    fun noAdminRouteExists() {
        assertEquals(404, router().route(req("GET", "/admin")).asResp().status)
        assertEquals(404, router().route(req("POST", "/admin/tokens")).asResp().status)
        assertEquals(404, router().route(req("GET", "/admin/clients")).asResp().status)
    }

    @Test
    fun unknownPathIs404AndWrongMethodIs405() {
        assertEquals(404, router().route(req("GET", "/nope")).asResp().status)
        assertEquals(405, router().route(req("POST", "/authorize")).asResp().status)
        assertEquals("GET", router().route(req("POST", "/authorize")).asResp().headers.getValue("Allow"))
        assertEquals(405, router().route(req("GET", "/token")).asResp().status)
        assertEquals(405, router().route(req("GET", "/register")).asResp().status)
        assertEquals("nosniff", router().route(req("GET", "/mcp")).asResp().headers["X-Content-Type-Options"])
        assertEquals("nosniff", router().route(req("GET", "/nope")).asResp().headers["X-Content-Type-Options"])
    }

    @Test
    fun revokeAlwaysAnswers200() {
        assertEquals(200, router().route(req("POST", "/revoke", body = "token=nope")).asResp().status)
        assertEquals(200, router().route(req("POST", "/revoke")).asResp().status)
        assertEquals(200, router().route(req("POST", "/revoke", body = "%%%")).asResp().status)
    }

    @Test
    fun nonHttpsPublicBaseIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { Endpoints.from("http://hc.example.com") }
    }

    // ---------------------------------------------------------------- /authorize 首屏

    @Test
    fun authorizePageLinksIntoTheApp() {
        val r = router().route(req("GET", "/authorize", query = authorizeQuery())).asResp()
        assertEquals(200, r.status)
        assertTrue(r.body.contains("""href="hcbridge://oauth/consent?req="""))
        assertTrue(r.body.contains("""http-equiv="refresh" content="3""""))
        assertTrue(r.body.contains("PC 浏览器打不开手机 app"))
    }

    @Test
    fun secondAuthorizeReusesPendingAndRedirectsToStatusUrl() {
        val first = router().route(req("GET", "/authorize", query = authorizeQuery())).asResp()
        val id = pendingIdFrom(first.body)
        val second = router().route(req("GET", "/authorize", query = authorizeQuery())).asResp()
        assertEquals(303, second.status)
        assertEquals("/authorize?req=$id", second.headers["Location"])
        assertEquals(1, store.pendingList(now).size)
    }

    @Test
    fun authorizeValidationFailureIs400HtmlWithoutRedirect() {
        val r = router().route(req("GET", "/authorize", query = authorizeQuery(client = "hcb_ci_nope"))).asResp()
        assertEquals(400, r.status)
        assertNull(r.headers["Location"])
        assertEquals("no-store", r.headers["Cache-Control"])
        assertTrue(store.pendingList(now).isEmpty())
    }

    @Test
    fun pendingClientGets403AtAuthorize() {
        val pendingClient = registerClient(confirmNewClients = true)
        val r = router().route(req("GET", "/authorize", query = authorizeQuery(client = pendingClient))).asResp()
        assertEquals(403, r.status)
        assertTrue(store.pendingList(now).isEmpty())
    }

    // ---------------------------------------------------------------- 状态页三分支

    @Test
    fun statusUrlIsWaitPageWhilePending_andNotRateLimited() {
        val id = newPendingViaAuthorize()
        repeat(100) {
            val r = router().route(req("GET", "/authorize", query = mapOf("req" to id))).asResp()
            assertEquals(200, r.status)
            assertTrue(r.body.contains("hcbridge://oauth/consent?req=$id"))
            assertTrue(r.body.contains("""http-equiv="refresh" content="3""""))
        }
    }

    @Test
    fun approvedStatusUrlRedirectsOnceWithCode() {
        val id = newPendingViaAuthorize()
        assertTrue(OAuth.decidePending(store, id, approve = true, setOf("health.read"), now))
        val r = router().route(req("GET", "/authorize", query = mapOf("req" to id))).asResp()
        assertEquals(302, r.status)
        assertTrue(r.headers.getValue("Location").startsWith(REDIRECT))
        assertTrue(r.headers.getValue("Location").contains("code="))
        assertTrue(r.headers.getValue("Location").contains("state="))
        // 一次性：再刷一次就是 410，不再发码
        assertEquals(410, router().route(req("GET", "/authorize", query = mapOf("req" to id))).asResp().status)
    }

    @Test
    fun deniedStatusUrlRedirectsWithAccessDenied() {
        val id = newPendingViaAuthorize()
        assertTrue(OAuth.decidePending(store, id, approve = false, emptySet(), now))
        val r = router().route(req("GET", "/authorize", query = mapOf("req" to id))).asResp()
        assertEquals(302, r.status)
        val location = r.headers.getValue("Location")
        assertTrue(location.startsWith(REDIRECT))
        assertTrue(location.contains("error=access_denied"))
        assertTrue(location.contains("state=st-1"))
    }

    @Test
    fun consumedOrUnknownStatusUrlIs410() {
        // 不存在的 id
        assertEquals(410, router().route(req("GET", "/authorize", query = mapOf("req" to "hcb_rq_nope"))).asResp().status)
        // 空的 req 等于"没带 req"，走首屏；少了 client/redirect 参数就是 400，不能当成 410
        assertEquals(400, router().route(req("GET", "/authorize", query = mapOf("req" to ""))).asResp().status)
        // 已经换过码：第二次刷同一页是 410（码只发一次）
        val id = newPendingViaAuthorize()
        assertTrue(OAuth.decidePending(store, id, approve = true, setOf("health.read"), now))
        assertEquals(302, router().route(req("GET", "/authorize", query = mapOf("req" to id))).asResp().status)
        assertEquals(410, router().route(req("GET", "/authorize", query = mapOf("req" to id))).asResp().status)
        assertEquals(410, router().route(req("GET", "/authorize", query = mapOf("req" to id))).asResp().status)
    }

    @Test
    fun expiredPendingIsCleanedOnAuthorize() {
        val expiredId = TokenFormat.newRequestId()
        store.putPending(
            PendingRow(
                id = expiredId,
                clientId = clientId,
                redirectUri = REDIRECT,
                codeChallenge = challenge,
                scopes = listOf(ToolCatalog.SCOPE_READ),
                resource = Endpoints.local().resource,
                state = "st-old",
                createdAt = now - 600_000,
                expiresAt = now - 300_000,
                status = McpStatus.PENDING,
            ),
        )
        assertEquals(200, router().route(req("GET", "/authorize", query = authorizeQuery())).asResp().status)
        assertNull(store.pending(expiredId))
    }

    @Test
    fun expiredApprovedRequestIs410() {
        val id = newPendingViaAuthorize()
        assertTrue(OAuth.decidePending(store, id, approve = true, setOf("health.read"), now))
        // 把行改成已过期：批准了但没在 5 分钟内换码
        val row = store.pending(id)!!
        store.putPending(row.copy(expiresAt = now - 1))
        val r = router().route(req("GET", "/authorize", query = mapOf("req" to id))).asResp()
        assertEquals(410, r.status)
        assertNull(r.headers["Location"])
        assertNull("expired rows are pruned", store.pending(id))
    }

    // ---------------------------------------------------------------- 限流

    @Test
    fun authorizeStartLimitsTo30PerMinute() {
        repeat(30) {
            val status = router().route(req("GET", "/authorize", query = authorizeQuery())).asResp().status
            assertTrue("call #$it should be allowed", status == 200 || status == 303)
        }
        assertEquals(429, router().route(req("GET", "/authorize", query = authorizeQuery())).asResp().status)
    }

    @Test
    fun authorizeStatusQuotaCountsNonPendingBranches() {
        // 未知 req ⇒ 410，但仍占 /authorize 额度；第 31 次 429（只有 pending 等待页免限流）
        repeat(30) {
            assertEquals(
                410,
                router().route(req("GET", "/authorize", query = mapOf("req" to "hcb_rq_nope"))).asResp().status,
            )
        }
        val limited = router().route(req("GET", "/authorize", query = mapOf("req" to "hcb_rq_nope"))).asResp()
        assertEquals(429, limited.status)
        assertEquals("60", limited.headers["Retry-After"])
    }

    @Test
    fun tokenEndpointLimitsTo60PerMinute() {
        repeat(60) {
            assertEquals(
                400,
                router().route(req("POST", "/token", body = "grant_type=bogus")).asResp().status,
            )
        }
        val r = router().route(req("POST", "/token", body = "grant_type=bogus")).asResp()
        assertEquals(429, r.status)
        assertEquals("60", r.headers["Retry-After"])
    }

    @Test
    fun spoofedCfHeaderCannotEscapeTheLocalBucket() {
        repeat(60) { i ->
            assertEquals(
                400,
                router().route(
                    req("POST", "/token", body = "grant_type=bogus", headers = mapOf("cf-connecting-ip" to "203.0.113.$i")),
                ).asResp().status,
            )
        }
        // 换一个伪造 IP 也还是同一个 local 桶
        assertEquals(
            429,
            router().route(
                req("POST", "/token", body = "grant_type=bogus", headers = mapOf("cf-connecting-ip" to "198.51.100.7")),
            ).asResp().status,
        )
    }

    @Test
    fun tunnelModeKeysBucketsByCloudflareIp() {
        fun proxied() = router(tunnelMode = McpConfig.TUNNEL_NAMED, publicBase = "https://hc.example.com")
        repeat(60) {
            assertEquals(
                400,
                proxied().route(req("POST", "/token", body = "grant_type=bogus", headers = mapOf("cf-connecting-ip" to "203.0.113.1"))).asResp().status,
            )
        }
        assertEquals(
            429,
            proxied().route(req("POST", "/token", body = "grant_type=bogus", headers = mapOf("cf-connecting-ip" to "203.0.113.1"))).asResp().status,
        )
        // 另一个公网 IP 有自己的桶
        assertEquals(
            400,
            proxied().route(req("POST", "/token", body = "grant_type=bogus", headers = mapOf("cf-connecting-ip" to "203.0.113.2"))).asResp().status,
        )
    }

    @Test
    fun registerLimitsTo10PerHour() {
        val body = JSONObject()
            .put("client_name", "many")
            .put("redirect_uris", JSONArray().put(REDIRECT))
            .toString()
        repeat(10) {
            assertEquals(201, router().route(req("POST", "/register", body = body)).asResp().status)
        }
        val limited = router().route(req("POST", "/register", body = body)).asResp()
        assertEquals(429, limited.status)
        assertEquals("3600", limited.headers["Retry-After"]) // 窗口是 1 小时，不是 60 秒
    }

    // ---------------------------------------------------------------- JSON-RPC

    @Test
    fun toolsListIsFilteredByThePrincipal() {
        val token = newAccessToken(setOf(ToolCatalog.SCOPE_READ))
        val principal = router().route(
            req("POST", "/mcp", body = "{}", headers = mapOf("authorization" to "Bearer $token")),
        ).asMcp().principal

        val r = McpRpc.handle(principal, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", now)
        assertEquals(200, r.status)
        val tools = JSONObject(r.body).getJSONObject("result").getJSONArray("tools")
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }.toSet()
        assertEquals(ToolCatalog.READ_TOOLS.size, names.size)
        assertEquals(ToolCatalog.READ_TOOLS, names)
        assertFalse(names.contains("hc_sync_now"))
    }

    @Test
    fun ingestScopeGrantsNoToolsAndToolCallsAreDenied() {
        val p = Principal(
            kind = "static",
            subject = "key:1",
            label = "ingest key",
            scopes = setOf(ToolCatalog.SCOPE_INGEST),
            policies = emptyList(),
            windows = emptyList(),
            allowedTools = emptySet(),
            expiresAt = Long.MAX_VALUE,
        )
        val list = JSONObject(
            McpRpc.handle(p, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", now).body,
        ).getJSONObject("result").getJSONArray("tools")
        assertEquals(0, list.length())

        var executed = 0
        val call = McpRpc.handle(
            p,
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"hc_dump","arguments":{}}}""",
            now,
            call = { _, _ ->
                executed++
                JSONObject()
            },
        )
        assertEquals(200, call.status)
        val error = JSONObject(call.body).getJSONObject("error")
        assertEquals(McpRpc.ERR_TOOL_NOT_PERMITTED, error.getInt("code"))
        assertTrue(error.getString("message").startsWith("tool not permitted: hc_dump"))
        assertTrue(error.getString("message").contains("scope"))
        assertEquals("ingest must not reach the executor", 0, executed)
    }

    @Test
    fun writeToolIsDeniedForReadOnlyPrincipalAndArgumentsAreNotEchoed() {
        val p = Principal(
            kind = "oauth", subject = clientId, label = "read only",
            scopes = setOf(ToolCatalog.SCOPE_READ), policies = emptyList(), windows = emptyList(),
            allowedTools = ToolCatalog.READ_TOOLS, expiresAt = Long.MAX_VALUE,
        )
        val r = McpRpc.handle(
            p,
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"hc_sync_now","arguments":{"secret":"hunter2"}}}""",
            now,
        )
        val error = JSONObject(r.body).getJSONObject("error")
        assertEquals(McpRpc.ERR_TOOL_NOT_PERMITTED, error.getInt("code"))
        assertFalse(r.body.contains("hunter2"))
    }

    @Test
    fun permittedToolCallReachesTheExecutor() {
        val p = Principal(
            kind = "loopback", subject = "local", label = "本机",
            scopes = setOf(ToolCatalog.SCOPE_READ, ToolCatalog.SCOPE_WRITE),
            policies = emptyList(), windows = emptyList(),
            allowedTools = ToolCatalog.ALL_TOOLS, expiresAt = Long.MAX_VALUE,
        )
        var seen: String? = null
        val r = McpRpc.handle(
            p,
            """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"hc_status","arguments":{}}}""",
            now,
            call = { name, _ ->
                seen = name
                JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "ok")))
            },
        )
        assertEquals(200, r.status)
        assertEquals("hc_status", seen)
        assertEquals("2.0", JSONObject(r.body).getString("jsonrpc"))
        assertEquals(4, JSONObject(r.body).getInt("id"))
    }

    @Test
    fun failedToolExecutionBecomesJsonRpcErrorWithoutMessageLeak() {
        val p = Principal(
            kind = "loopback", subject = "local", label = "本机",
            scopes = setOf(ToolCatalog.SCOPE_READ, ToolCatalog.SCOPE_WRITE),
            policies = emptyList(), windows = emptyList(),
            allowedTools = ToolCatalog.ALL_TOOLS, expiresAt = Long.MAX_VALUE,
        )
        val r = McpRpc.handle(
            p,
            """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"hc_status","arguments":{}}}""",
            now,
            call = { _, _ -> throw IllegalStateException("token hcb_at_leak") },
        )
        val error = JSONObject(r.body).getJSONObject("error")
        assertEquals(McpRpc.ERR_SERVER, error.getInt("code"))
        assertFalse(r.body.contains("hcb_at_leak"))
    }

    @Test
    fun notificationGets202AndEmptyBody() {
        val p = loopbackPrincipal()
        val r = McpRpc.handle(p, """{"jsonrpc":"2.0","method":"notifications/initialized"}""", now)
        assertEquals(202, r.status)
        assertEquals("", r.body)
    }

    @Test
    fun notificationsRequireAValidEnvelope() {
        val p = loopbackPrincipal()
        assertEquals(
            202,
            McpRpc.handle(p, """{"jsonrpc":"2.0","method":"notifications/initialized"}""", now).status,
        )
        // 没有 jsonrpc / method 为空的“通知”不能拿 202（202 的含义是“已收下”）
        val noVersion = McpRpc.handle(p, """{"method":"notifications/initialized"}""", now)
        assertEquals(400, noVersion.status)
        assertEquals(McpRpc.ERR_INVALID_REQUEST, JSONObject(noVersion.body).getJSONObject("error").getInt("code"))
        val noMethod = McpRpc.handle(p, """{"jsonrpc":"2.0"}""", now)
        assertEquals(400, noMethod.status)
        assertEquals(McpRpc.ERR_INVALID_REQUEST, JSONObject(noMethod.body).getJSONObject("error").getInt("code"))
    }

    @Test
    fun initializeAndPingAreAnswered() {
        val p = loopbackPrincipal()
        val init = McpRpc.handle(
            p,
            """{"jsonrpc":"2.0","id":"a","method":"initialize","params":{"protocolVersion":"2025-03-26"}}""",
            now,
        )
        assertEquals(200, init.status)
        val result = JSONObject(init.body).getJSONObject("result")
        assertEquals("2025-03-26", result.getString("protocolVersion"))
        assertTrue(result.getJSONObject("capabilities").has("tools"))

        val ping = McpRpc.handle(p, """{"jsonrpc":"2.0","id":2,"method":"ping"}""", now)
        assertEquals(200, ping.status)
        assertEquals(0, JSONObject(ping.body).getJSONObject("result").length())
    }

    @Test
    fun parseErrorAndUnknownMethodAreReportedAsJsonRpcErrors() {
        val p = loopbackPrincipal()
        assertEquals(400, McpRpc.handle(p, "{not json", now).status)
        assertEquals(
            McpRpc.ERR_PARSE,
            JSONObject(McpRpc.handle(p, "{not json", now).body).getJSONObject("error").getInt("code"),
        )
        val unknown = McpRpc.handle(p, """{"jsonrpc":"2.0","id":9,"method":"resources/list"}""", now)
        assertEquals(200, unknown.status)
        assertEquals(
            McpRpc.ERR_METHOD_NOT_FOUND,
            JSONObject(unknown.body).getJSONObject("error").getInt("code"),
        )
    }

    // ---------------------------------------------------------------- 响应写出

    @Test
    fun respondWritesStatusPhraseAndFramingHeaders() {
        val resp = router().route(req("POST", "/mcp", body = "{}")).asResp()
        val out = ByteArrayOutputStream()
        respond(out, resp)
        val raw = String(out.toByteArray(), Charsets.ISO_8859_1)
        assertTrue(raw.startsWith("HTTP/1.1 401 Unauthorized\r\n"))
        assertTrue(raw.contains("\r\nWWW-Authenticate: Bearer realm=\"mcp\""))
        assertTrue(raw.contains("\r\nConnection: close\r\n\r\n"))
        val bodyBytes = resp.body.toByteArray(Charsets.UTF_8).size
        assertTrue(raw.contains("\r\nContent-Length: $bodyBytes\r\n"))
        assertEquals("Found", HttpStatus.reason(302))
        assertEquals("Gone", HttpStatus.reason(410))
        assertEquals("Payload Too Large", HttpStatus.reason(413))
        assertEquals("See Other", HttpStatus.reason(303))
        assertEquals("Too Many Requests", HttpStatus.reason(429))
    }

    @Test
    fun respondFoldsHeaderValuesAndOwnsFramingHeaders() {
        val out = ByteArrayOutputStream()
        respond(
            out,
            HttpResp(
                302,
                mapOf(
                    "Location" to "http://x/cb?a=1\r\nX-Evil: 1",
                    "Content-Length" to "99",
                    "Connection" to "keep-alive",
                ),
                "",
            ),
        )
        val raw = String(out.toByteArray(), Charsets.ISO_8859_1)
        assertTrue(raw.startsWith("HTTP/1.1 302 Found\r\n"))
        assertTrue(raw.contains("Location: http://x/cb?a=1  X-Evil: 1\r\n"))
        assertFalse(raw.contains("\r\nX-Evil"))
        assertTrue(raw.contains("Content-Length: 0\r\n"))
        assertFalse(raw.contains("keep-alive"))
    }

    private fun loopbackPrincipal(): Principal = Principal(
        kind = "loopback", subject = "local", label = "本机",
        scopes = setOf(ToolCatalog.SCOPE_READ, ToolCatalog.SCOPE_WRITE),
        policies = emptyList(), windows = emptyList(),
        allowedTools = ToolCatalog.ALL_TOOLS, expiresAt = Long.MAX_VALUE,
    )

    companion object {
        const val REDIRECT = "http://localhost:7777/cb"
    }
}
