package id.harness.hcbridge.auth

import java.net.URLDecoder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * OAuth 2.1 AS 的协议核心测试（JVM，[InMemoryStore] 假 store）。
 *
 * 覆盖的是**不变式**，不是 HTTP 细节（HTTP 拆包在 Task 5）：
 * - 发现文档只宣告两个 scope，`ingest` 永不可得；
 * - DCR 回调校验（https / 回环 http，含 `[::1]`）、默认落 `pending`、公开客户端无 secret；
 * - 授权码 5 分钟一次性 + PKCE(S256)；
 * - 刷新严格轮换，**重用一次整族作废**（先 `tokenByHash` 判重用，再做单条 CAS）；
 * - 撤销只作用于自己的 client，级联 family；
 * - pending 是一个状态机（`pending → approved|denied → consumed`），未批准绝不发码。
 */
class OAuthTest {

    private val store = InMemoryStore()
    private val now = 1_700_000_000_000L
    private val ep = Endpoints.local()

    /** RFC 7636 附录 B 的 verifier（43 字符，合法）。 */
    private val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    private val cb = "http://127.0.0.1:7777/cb"
    private val cbV6 = "http://[::1]:7777/cb"

    private lateinit var clientId: String

    @Before
    fun setUp() {
        clientId = registerClient(cb, cbV6)
    }

    // ------------------------------------------------------------ 小工具

    private fun dcrBody(vararg uris: String, name: String = "test client", authMethod: String? = null): JSONObject {
        val body = JSONObject()
            .put("client_name", name)
            .put("redirect_uris", JSONArray().apply { uris.forEach { put(it) } })
        if (authMethod != null) body.put("token_endpoint_auth_method", authMethod)
        return body
    }

    private fun registerClient(vararg uris: String, confirm: Boolean = false, authMethod: String? = null): String =
        OAuth.registerClient(store, dcrBody(*uris, authMethod = authMethod), confirm, now)
            .getString("client_id")

    private fun authParams(
        client: String = clientId,
        redirect: String = cb,
        scope: String? = "health.read",
        resource: String? = null,
        challenge: String = Crypto.pkceS256(verifier),
    ): Map<String, String> {
        val p = LinkedHashMap<String, String>()
        p["response_type"] = "code"
        p["client_id"] = client
        p["redirect_uri"] = redirect
        p["code_challenge"] = challenge
        p["code_challenge_method"] = "S256"
        p["state"] = "st-1"
        if (scope != null) p["scope"] = scope
        if (resource != null) p["resource"] = resource
        return p
    }

    private fun codeFromUrl(url: String): String {
        val q = url.substringAfter("code=", "")
        return URLDecoder.decode(q.substringBefore("&"), "UTF-8")
    }

    /** 走完 authorize → 同意 → 发码，返回授权码明文。 */
    private fun codeFor(
        scope: String? = "health.read",
        client: String = clientId,
        redirect: String = cb,
        challengeFor: String = verifier,
    ): String {
        val v = OAuth.validateAuthorize(
            store, authParams(client, redirect, scope, null, Crypto.pkceS256(challengeFor)), ep, now,
        )
        val id = OAuth.createOrReusePending(store, v, now).first
        assertTrue(OAuth.decidePending(store, id, approve = true, grantedScopes = v.scopes, now = now))
        return codeFromUrl(OAuth.completePending(store, id, now))
    }

    private fun exchangeWith(code: String, verifierUsed: String = verifier): JSONObject =
        OAuth.exchange(
            store,
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to cb,
                "client_id" to clientId,
                "code_verifier" to verifierUsed,
            ),
            ep,
            now + 10,
        )

    private fun exchange(scope: String? = "health.read", verifierUsed: String = verifier): JSONObject =
        exchangeWith(codeFor(scope = scope), verifierUsed)

    private fun exchangeError(scope: String? = "health.read", verifierUsed: String = verifier): OAuthError =
        assertThrows(OAuthError::class.java) { exchange(scope, verifierUsed) }

    private fun refreshWith(raw: String, client: String = clientId, at: Long = now + 1): JSONObject =
        OAuth.refresh(store, mapOf("grant_type" to "refresh_token", "refresh_token" to raw, "client_id" to client), ep, at)

    private fun refreshErr(raw: String, at: Long = now + 1): OAuthError =
        assertThrows(OAuthError::class.java) { refreshWith(raw, at = at) }

    /** 直接种一枚令牌行（需要精确控制策略/时间窗/归属时用，避免为了测试绕整条授权流）。 */
    private fun seedToken(
        kind: String = McpStatus.KIND_REFRESH,
        client: String = clientId,
        family: String = "f-seed",
        scopes: List<String> = listOf("health.read"),
        policy: String? = null,
        window: String? = null,
        expiresAt: Long = now + TokenFormat.REFRESH_TTL_MS,
    ): String {
        val raw = if (kind == McpStatus.KIND_REFRESH) TokenFormat.newRefreshToken() else TokenFormat.newAccessToken()
        store.putToken(
            TokenRow(
                tokenHash = Crypto.sha256Hex(raw),
                prefix = TokenFormat.prefixOf(raw),
                kind = kind,
                clientId = client,
                familyId = family,
                scopes = scopes,
                toolPolicyJson = policy,
                windowJson = window,
                resource = ep.resource,
                createdAt = now,
                expiresAt = expiresAt,
            ),
        )
        return raw
    }

    // ------------------------------------------------------------ 发现文档

    @Test
    fun metadataAdvertisesOnlyTwoScopes() {
        val m = OAuth.serverMetadata(Endpoints.local())
        assertEquals("""["health.read","health.write"]""", m.getJSONArray("scopes_supported").toString())
    }

    @Test
    fun serverMetadataUsesIssuerForEndpoints() {
        val m = OAuth.serverMetadata(ep)
        assertEquals(ep.issuer, m.getString("issuer"))
        assertEquals(ep.issuer + "/authorize", m.getString("authorization_endpoint"))
        assertEquals(ep.issuer + "/token", m.getString("token_endpoint"))
        assertEquals(ep.issuer + "/register", m.getString("registration_endpoint"))
        assertEquals(ep.issuer + "/revoke", m.getString("revocation_endpoint"))
        assertEquals("""["S256"]""", m.getJSONArray("code_challenge_methods_supported").toString())
        assertEquals("""["code"]""", m.getJSONArray("response_types_supported").toString())
    }

    @Test
    fun resourceMetadataUsesCanonicalResourceAndMetadataUrl() {
        val ep = Endpoints.from("https://hc.example.com/")
        val m = OAuth.resourceMetadata(ep)
        assertEquals("https://hc.example.com/mcp", m.getString("resource"))
        assertEquals("""["https://hc.example.com"]""", m.getJSONArray("authorization_servers").toString())
        assertEquals("https://hc.example.com/.well-known/oauth-protected-resource/mcp", ep.resourceMetadataUrl)
    }

    @Test
    fun endpointsNormalizeBareHttpsOrigin() {
        assertEquals(Endpoints("https://hc.example.com", "https://hc.example.com/mcp"),
            Endpoints.from("https://hc.example.com"))
        // 尾斜杠、大小写、默认端口都归一化
        assertEquals(Endpoints("https://hc.example.com", "https://hc.example.com/mcp"),
            Endpoints.from("  HTTPS://HC.Example.COM/  "))
        assertEquals(Endpoints("https://hc.example.com", "https://hc.example.com/mcp"),
            Endpoints.from("https://hc.example.com:443/"))
        assertEquals("https://hc.example.com:8443/mcp", Endpoints.from("https://hc.example.com:8443").resource)
        // 只收裸 origin：路径 / query / fragment / userinfo 都不"猜"
        for (bad in listOf("https://hc.example.com/app", "https://hc.example.com?x=1",
            "https://hc.example.com#frag", "https://user@hc.example.com", "https://")) {
            val e = assertThrows(IllegalArgumentException::class.java) { Endpoints.from(bad) }
            assertNotNull(e.message)
        }
    }

    @Test
    fun endpointsRequireHttpsPublicBase() {
        assertEquals(Endpoints.local(), Endpoints.from(null))
        assertEquals(Endpoints.local(), Endpoints.from("   "))
        assertEquals("http://127.0.0.1:9000/mcp", Endpoints.local(9000).resource)
        assertEquals("http://127.0.0.1:8765/mcp", Endpoints.local().resource)
        val e = assertThrows(IllegalArgumentException::class.java) { Endpoints.from("http://hc.example.com") }
        assertEquals("public base must be https", e.message)
        assertThrows(IllegalArgumentException::class.java) { Endpoints.from("ftp://hc.example.com") }
    }

    // ------------------------------------------------------------ DCR

    @Test
    fun dcrClientStartsPendingByDefault() {
        val pending = OAuth.registerClient(store, dcrBody(cb), confirmNewClients = true, now = 0)
        assertEquals(McpStatus.CLIENT_PENDING, store.client(pending.getString("client_id"))!!.status)
        val active = OAuth.registerClient(store, dcrBody(cb), confirmNewClients = false, now = 0)
        assertEquals(McpStatus.CLIENT_ACTIVE, store.client(active.getString("client_id"))!!.status)
    }

    @Test
    fun dcrPublicClientGetsNoSecret() {
        val m = OAuth.registerClient(store, dcrBody(cb), confirmNewClients = false, now = now)
        assertFalse(m.has("client_secret"))
        assertEquals("none", m.getString("token_endpoint_auth_method"))
        assertNull(store.client(m.getString("client_id"))!!.secretHash)
        assertTrue(m.getString("client_id").startsWith("hcb_ci_"))
        assertEquals("""["http://127.0.0.1:7777/cb"]""", m.getJSONArray("redirect_uris").toString())
    }

    @Test
    fun dcrConfidentialClientGetsSecretOnce() {
        val m = OAuth.registerClient(store, dcrBody(cb, authMethod = "client_secret_post"), false, now)
        val secret = m.getString("client_secret")
        assertTrue(secret.startsWith(TokenFormat.CLIENT_SECRET_PREFIX))
        assertEquals("client_secret_post", m.getString("token_endpoint_auth_method"))
        // 只存哈希：明文既不进库也不能从库里还原
        assertEquals(Crypto.sha256Hex(secret), store.client(m.getString("client_id"))!!.secretHash)
        // 机密客户端在 /token 必须给对的 secret
        val code = codeFor(client = m.getString("client_id"))
        val missing = assertThrows(OAuthError::class.java) {
            OAuth.exchange(store, mapOf("grant_type" to "authorization_code", "code" to code,
                "redirect_uri" to cb, "client_id" to m.getString("client_id"),
                "code_verifier" to verifier), ep, now + 10)
        }
        assertEquals("invalid_client", missing.code)
        assertEquals(401, missing.status)
    }

    @Test
    fun dcrAcceptsOnlyHttpsOrLoopbackHttp() {
        for (ok in listOf("https://app.example.com/cb", "http://127.0.0.1/cb", "http://127.0.0.1:7777/cb",
            "http://localhost:7777/cb", "http://[::1]:7777/cb")) {
            assertTrue("must accept $ok", OAuth.redirectUriAllowed(ok))
        }
        for (bad in listOf("http://evil.example.com/cb", "http://127.0.0.1.evil.com/cb", "ftp://127.0.0.1/cb",
            "https://app.example.com/cb#frag", "", "not a uri", "https://user@app.example.com/cb",
            "javascript:alert(1)")) {
            assertFalse("must reject $bad", OAuth.redirectUriAllowed(bad))
            val e = assertThrows(OAuthError::class.java) {
                OAuth.registerClient(store, dcrBody(bad), false, now)
            }
            assertEquals("invalid_redirect_uri", e.code)
            assertEquals(400, e.status)
        }
    }

    @Test
    fun dcrClientNameStripsControlCharsAndCapsLength() {
        val name = "a\u0000b\u001fc\nd\u007fe" + "x".repeat(200)
        val m = OAuth.registerClient(store, dcrBody(cb, name = name), false, now)
        val stored = store.client(m.getString("client_id"))!!.name
        assertTrue("control chars must be folded", stored.none { it.code < 0x20 || it.code == 0x7F })
        assertEquals(OAuth.CLIENT_NAME_MAX, stored.length)
        assertTrue(stored.startsWith("a b c d ex"))
    }

    @Test
    fun dcrRejectsMissingRedirectUris() {
        val e = assertThrows(OAuthError::class.java) {
            OAuth.registerClient(store, JSONObject().put("client_name", "x"), false, now)
        }
        assertEquals("invalid_redirect_uri", e.code)
    }

    @Test
    fun dcrCapsClientCount() {
        while (store.clientCount() < OAuth.MAX_CLIENTS) {
            OAuth.registerClient(store, dcrBody(cb), false, now)
        }
        assertThrows(OAuthError::class.java) { OAuth.registerClient(store, dcrBody(cb), false, now) }
        assertEquals(OAuth.MAX_CLIENTS, store.clientCount())
    }

    // ------------------------------------------------------------ /authorize

    @Test
    fun mismatchedRedirectIsRejectedBeforeAnyRedirect() {
        val e = assertThrows(OAuthError::class.java) {
            OAuth.validateAuthorize(store, authParams(redirect = "http://evil.example.com/cb"), ep, now)
        }
        assertEquals(400, e.status)
        // 没有落下任何 pending：不能把请求暂存起来等批准
        assertEquals(0, store.pendingList(now).size)
    }

    @Test
    fun loopbackIpv6RedirectIsAccepted() {
        val v = OAuth.validateAuthorize(store, authParams(redirect = cbV6), ep, now)
        assertEquals(cbV6, v.redirectUri)
    }

    @Test
    fun ingestScopeIsNeverGranted() {
        assertEquals("invalid_scope", assertThrows(OAuthError::class.java) {
            OAuth.validateAuthorize(store, authParams(scope = "health.read ingest"), ep, now)
        }.code)
        assertEquals("invalid_scope", assertThrows(OAuthError::class.java) {
            OAuth.validateAuthorize(store, authParams(scope = "ingest"), ep, now)
        }.code)
        assertEquals("invalid_scope", assertThrows(OAuthError::class.java) {
            OAuth.validateAuthorize(store, authParams(scope = "health.read unknown.scope"), ep, now)
        }.code)
    }

    @Test
    fun scopeDefaultsToBothAndResourceMustMatch() {
        val v = OAuth.validateAuthorize(store, authParams(scope = null), ep, now)
        assertEquals(setOf("health.read", "health.write"), v.scopes)
        assertEquals(ep.resource, v.resource)
        assertEquals("st-1", v.state)
        assertEquals("invalid_target", assertThrows(OAuthError::class.java) {
            OAuth.validateAuthorize(store, authParams(resource = "https://other.example.com/mcp"), ep, now)
        }.code)
    }

    @Test
    fun authorizeRequiresPkceS256() {
        assertEquals("unsupported_response_type", assertThrows(OAuthError::class.java) {
            OAuth.validateAuthorize(store, authParams() + ("response_type" to "token"), ep, now)
        }.code)
        assertEquals("invalid_request", assertThrows(OAuthError::class.java) {
            OAuth.validateAuthorize(store, authParams() + ("code_challenge_method" to "plain"), ep, now)
        }.code)
        assertEquals("invalid_request", assertThrows(OAuthError::class.java) {
            OAuth.validateAuthorize(store, authParams(challenge = "short"), ep, now)
        }.code)
    }

    @Test
    fun pendingClientCannotAuthorize() {
        val pending = registerClient(cb, confirm = true)
        val e = assertThrows(OAuthError::class.java) {
            OAuth.validateAuthorize(store, authParams(client = pending), ep, now)
        }
        assertEquals("unauthorized_client", e.code)
        assertEquals(403, e.status)
    }

    @Test
    fun revokedClientCannotAuthorize() {
        store.revokeClient(clientId, now)
        val e = assertThrows(OAuthError::class.java) {
            OAuth.validateAuthorize(store, authParams(), ep, now)
        }
        assertEquals("unauthorized_client", e.code)
        assertEquals(403, e.status)
    }

    // ------------------------------------------------------------ pending 状态机

    @Test
    fun pendingIsReusedForSameRequest() {
        val v = OAuth.validateAuthorize(store, authParams(), ep, now)
        val a = OAuth.createOrReusePending(store, v, now)
        val b = OAuth.createOrReusePending(store, v, now + 2_000)
        assertEquals(a.first, b.first)
        assertTrue(a.second)
        assertFalse("second must reuse", b.second)
        assertTrue(a.first.startsWith(TokenFormat.REQUEST_PREFIX))
        assertEquals(1, store.pendingList(now).size)
    }

    @Test
    fun pendingReuseIsKeyedByState() {
        val base = OAuth.validateAuthorize(store, authParams(), ep, now)
        val other = OAuth.validateAuthorize(store, authParams() + ("state" to "st-2"), ep, now)
        val a = OAuth.createOrReusePending(store, base, now)
        val b = OAuth.createOrReusePending(store, other, now)
        assertFalse("different state must not reuse", a.first == b.first)
        assertEquals(2, store.pendingList(now).size)
        // 同一个 state 才复用（幂等）
        val again = OAuth.createOrReusePending(store, base, now + 1_000)
        assertEquals(a.first, again.first)
        assertFalse(again.second)
    }

    @Test
    fun pendingDiffersByChallenge() {
        val a = OAuth.createOrReusePending(store, OAuth.validateAuthorize(store, authParams(), ep, now), now)
        val b = OAuth.createOrReusePending(
            store, OAuth.validateAuthorize(store, authParams(challenge = Crypto.pkceS256("z".repeat(43))), ep, now), now,
        )
        assertFalse(a.first == b.first)
    }

    @Test
    fun pendingStateMachineIsSingleUse() {
        val id = OAuth.createOrReusePending(store, OAuth.validateAuthorize(store, authParams(), ep, now), now).first
        // 还没批准：不能发码
        assertEquals(410, assertThrows(OAuthError::class.java) { OAuth.completePending(store, id, now) }.status)
        assertEquals("410 must not write a code", 0, store.codeCount())
        assertTrue(OAuth.decidePending(store, id, approve = true, grantedScopes = setOf("health.read"), now))
        assertFalse(OAuth.decidePending(store, id, approve = false, grantedScopes = emptySet(), now))
        val url = OAuth.completePending(store, id, now)
        assertTrue(url.startsWith(cb))
        assertTrue(url.contains("code="))
        assertTrue(url.contains("state=st-1"))
        assertEquals("exactly one code issued", 1, store.codeCount())
        assertEquals(410, assertThrows(OAuthError::class.java) { OAuth.completePending(store, id, now) }.status)
        assertEquals("second 410 must not write a code", 1, store.codeCount())
    }

    @Test
    fun approvalNarrowsScopesToIntersection() {
        val v = OAuth.validateAuthorize(store, authParams(scope = "health.read health.write"), ep, now)
        val id = OAuth.createOrReusePending(store, v, now).first
        assertTrue(OAuth.decidePending(store, id, approve = true, grantedScopes = setOf("health.read"), now))
        assertEquals(listOf("health.read"), store.pending(id)!!.scopes)
        // 授予集为空不能批准（否则会发一个什么都不能干的令牌）
        val id2 = OAuth.createOrReusePending(store, OAuth.validateAuthorize(store, authParams(), ep, now + 60_000), now + 60_000).first
        assertFalse(OAuth.decidePending(store, id2, approve = true, grantedScopes = emptySet(), now + 60_000))
        assertEquals(McpStatus.PENDING, store.pending(id2)!!.status)
    }

    @Test
    fun deniedPendingNeverIssuesCode() {
        val id = OAuth.createOrReusePending(store, OAuth.validateAuthorize(store, authParams(), ep, now), now).first
        assertTrue(OAuth.decidePending(store, id, approve = false, grantedScopes = emptySet(), now))
        assertEquals(410, assertThrows(OAuthError::class.java) { OAuth.completePending(store, id, now) }.status)
        assertNull(store.takeCode(Crypto.sha256Hex("does-not-matter"), now))
        assertEquals("denied must not write a code", 0, store.codeCount())
    }

    @Test
    fun expiredPendingNeverIssuesCode() {
        val id = OAuth.createOrReusePending(store, OAuth.validateAuthorize(store, authParams(), ep, now), now).first
        assertTrue(OAuth.decidePending(store, id, approve = true, grantedScopes = setOf("health.read"), now))
        val late = now + TokenFormat.PENDING_TTL_MS + 1
        assertEquals(410, assertThrows(OAuthError::class.java) { OAuth.completePending(store, id, late) }.status)
        assertEquals("expired must not write a code", 0, store.codeCount())
        assertFalse(OAuth.decidePending(store, id, approve = true, grantedScopes = setOf("health.read"), late))
    }

    @Test
    fun completePendingRefusesRevokedClient() {
        val id = OAuth.createOrReusePending(store, OAuth.validateAuthorize(store, authParams(), ep, now), now).first
        assertTrue(OAuth.decidePending(store, id, approve = true, grantedScopes = setOf("health.read"), now))
        store.revokeClient(clientId, now + 1)
        // 批准之后管理员吊销了客户端：不能再发码（Task 3 的交待）
        assertEquals(410, assertThrows(OAuthError::class.java) { OAuth.completePending(store, id, now + 2) }.status)
        assertEquals(0, store.codeCount())
    }

    @Test
    fun stateIsCappedAndPrintableOnly() {
        val max = "s".repeat(512)
        assertEquals(max, OAuth.validateAuthorize(store, authParams() + ("state" to max), ep, now).state)
        assertEquals("invalid_request", assertThrows(OAuthError::class.java) {
            OAuth.validateAuthorize(store, authParams() + ("state" to "s".repeat(513)), ep, now)
        }.code)
        assertEquals("invalid_request", assertThrows(OAuthError::class.java) {
            OAuth.validateAuthorize(store, authParams() + ("state" to "ok\u0000bad"), ep, now)
        }.code)
        assertNull(OAuth.validateAuthorize(store, authParams() + ("state" to ""), ep, now).state)
    }

    @Test
    fun completePendingReChecksRegisteredRedirect() {
        val id = OAuth.createOrReusePending(store, OAuth.validateAuthorize(store, authParams(), ep, now), now).first
        assertTrue(OAuth.decidePending(store, id, approve = true, grantedScopes = setOf("health.read"), now))
        // 管理员把回调从注册表里删掉（只留另一个）：发码目标不再可信
        store.putClient(
            store.client(clientId)!!.copy(redirectUrisJson = """["http://127.0.0.1:7777/other"]"""),
        )
        val e = assertThrows(OAuthError::class.java) { OAuth.completePending(store, id, now + 1) }
        assertEquals(410, e.status)
        assertNull("untrusted redirect must not be a 302 target", e.redirectUrl)
        assertEquals(0, store.codeCount())
    }

    @Test
    fun deniedPendingCarriesRedirectAndState() {
        val id = OAuth.createOrReusePending(store, OAuth.validateAuthorize(store, authParams(), ep, now), now).first
        assertTrue(OAuth.decidePending(store, id, approve = false, grantedScopes = emptySet(), now))
        val e = assertThrows(OAuthError::class.java) { OAuth.completePending(store, id, now + 1) }
        assertEquals(410, e.status)
        assertEquals("access_denied", e.code)
        val url = e.redirectUrl ?: error("denied must be a 302 target, not a bare 410")
        assertTrue(url.startsWith("$cb?"))
        assertTrue(url.contains("error=access_denied"))
        assertTrue(url.contains("state=st-1"))
        assertEquals(0, store.codeCount())
    }

    @Test
    fun deniedPendingWithoutTrustedRedirectIsBare() {
        val id = OAuth.createOrReusePending(store, OAuth.validateAuthorize(store, authParams(), ep, now), now).first
        assertTrue(OAuth.decidePending(store, id, approve = false, grantedScopes = emptySet(), now))
        store.revokeClient(clientId, now + 1)
        val e = assertThrows(OAuthError::class.java) { OAuth.completePending(store, id, now + 2) }
        assertEquals(410, e.status)
        assertNull("revoked client must not get a redirect", e.redirectUrl)
    }

    @Test
    fun pendingStatusDrivesTask5Branches() {
        val id = OAuth.createOrReusePending(store, OAuth.validateAuthorize(store, authParams(), ep, now), now).first
        val waiting = OAuth.pendingStatus(store, id, now)!!
        assertEquals(McpStatus.PENDING, waiting.status)
        assertNull(waiting.redirectUri)
        assertEquals("st-1", waiting.state)
        assertFalse(waiting.expired)

        assertTrue(OAuth.decidePending(store, id, approve = true, grantedScopes = setOf("health.read"), now))
        val approved = OAuth.pendingStatus(store, id, now)!!
        assertEquals(McpStatus.APPROVED, approved.status)
        assertEquals(cb, approved.redirectUri)

        OAuth.completePending(store, id, now)
        val consumed = OAuth.pendingStatus(store, id, now)!!
        assertEquals(McpStatus.CONSUMED, consumed.status)
        assertNull("consumed must not be redirected again", consumed.redirectUri)

        assertNull(OAuth.pendingStatus(store, "hcb_rq_missing", now))
    }

    @Test
    fun pendingStatusMarksExpired() {
        val id = OAuth.createOrReusePending(store, OAuth.validateAuthorize(store, authParams(), ep, now), now).first
        val late = OAuth.pendingStatus(store, id, now + TokenFormat.PENDING_TTL_MS + 1)!!
        assertTrue(late.expired)
    }

    @Test
    fun completePendingReReadsClientStatus() {
        // 只把 client 降回 pending（不走 revokeClient 的级联）：发码前必须重读 client 才能挡住
        val id = OAuth.createOrReusePending(store, OAuth.validateAuthorize(store, authParams(), ep, now), now).first
        assertTrue(OAuth.decidePending(store, id, approve = true, grantedScopes = setOf("health.read"), now))
        store.putClient(store.client(clientId)!!.copy(status = McpStatus.CLIENT_PENDING))
        assertEquals(410, assertThrows(OAuthError::class.java) { OAuth.completePending(store, id, now + 2) }.status)
        // 码没被发出去：pending 还是 approved，库里一条码都没有
        assertNull(store.takeCode(Crypto.sha256Hex("unused"), now + 2))
        assertEquals(McpStatus.APPROVED, store.pending(id)!!.status)
        assertEquals(0, store.codeCount())
    }

    // ------------------------------------------------------------ /token

    @Test
    fun exchangeIssuesAccessAndRefreshWithTtls() {
        val t = exchange()
        val access = t.getString("access_token")
        val refresh = t.getString("refresh_token")
        assertTrue(access.startsWith(TokenFormat.ACCESS_PREFIX))
        assertTrue(refresh.startsWith(TokenFormat.REFRESH_PREFIX))
        assertEquals("Bearer", t.getString("token_type"))
        assertEquals(3600L, t.getLong("expires_in"))
        assertEquals("health.read", t.getString("scope"))
        val a = store.tokenByHash(Crypto.sha256Hex(access))!!
        val r = store.tokenByHash(Crypto.sha256Hex(refresh))!!
        assertEquals(McpStatus.KIND_ACCESS, a.kind)
        assertEquals(McpStatus.KIND_REFRESH, r.kind)
        assertEquals(a.familyId, r.familyId)
        assertEquals(now + 10 + TokenFormat.ACCESS_TTL_MS, a.expiresAt)
        assertEquals(now + 10 + TokenFormat.REFRESH_TTL_MS, r.expiresAt)
        assertEquals(ep.resource, a.resource)
        assertEquals(listOf("health.read"), a.scopes)
        // 令牌明文不落库（只存哈希）
        assertNull(store.tokenByHash(access))
    }

    @Test
    fun codeIsSingleUseAndPkceIsChecked() {
        val code = codeFor()
        val wrong = assertThrows(OAuthError::class.java) { exchangeWith(code, "y".repeat(43)) }
        assertEquals("invalid_grant", wrong.code)
        assertEquals(400, wrong.status)
        // 授权码被失败的兑换毁掉（不允许"试到对为止"）
        assertEquals("invalid_grant", assertThrows(OAuthError::class.java) { exchangeWith(code) }.code)
    }

    @Test
    fun secondExchangeOfSameCodeFails() {
        val code = codeFor()
        assertNotNull(exchangeWith(code).getString("access_token"))
        assertEquals("invalid_grant", assertThrows(OAuthError::class.java) { exchangeWith(code) }.code)
    }

    @Test
    fun exchangeBindsCodeToClientRedirectAndResource() {
        val other = registerClient(cb)
        assertEquals("invalid_grant", assertThrows(OAuthError::class.java) {
            OAuth.exchange(store, mapOf("grant_type" to "authorization_code", "code" to codeFor(),
                "redirect_uri" to cb, "client_id" to other, "code_verifier" to verifier), ep, now + 10)
        }.code)
        assertEquals("invalid_grant", assertThrows(OAuthError::class.java) {
            OAuth.exchange(store, mapOf("grant_type" to "authorization_code", "code" to codeFor(),
                "redirect_uri" to "http://127.0.0.1:7777/other", "client_id" to clientId,
                "code_verifier" to verifier), ep, now + 10)
        }.code)
        // 公网基址换了：旧 resource 的码不能再换令牌
        assertEquals("invalid_grant", assertThrows(OAuthError::class.java) {
            OAuth.exchange(store, mapOf("grant_type" to "authorization_code", "code" to codeFor(),
                "redirect_uri" to cb, "client_id" to clientId, "code_verifier" to verifier),
                Endpoints.from("https://hc.example.com"), now + 10)
        }.code)
    }

    @Test
    fun exchangeRefusesNonActiveClient() {
        val code = codeFor()
        store.putClient(store.client(clientId)!!.copy(status = McpStatus.CLIENT_PENDING))
        val e = assertThrows(OAuthError::class.java) { exchangeWith(code) }
        assertEquals("invalid_client", e.code)
        assertEquals(401, e.status)
    }

    @Test
    fun exchangeRefusesRevokedClientWithoutIssuing() {
        val code = codeFor()
        store.revokeClient(clientId, now + 1)
        val before = store.tokens().size
        val e = assertThrows(OAuthError::class.java) { exchangeWith(code) }
        assertEquals("invalid_client", e.code)
        assertEquals(401, e.status)
        assertEquals(before, store.tokens().size)
    }

    @Test
    fun exchangeRejectsBadGrantType() {
        assertEquals("unsupported_grant_type", assertThrows(OAuthError::class.java) {
            OAuth.exchange(store, mapOf("grant_type" to "password"), ep, now)
        }.code)
        assertEquals("invalid_request", assertThrows(OAuthError::class.java) {
            OAuth.exchange(store, mapOf("grant_type" to "authorization_code"), ep, now)
        }.code)
    }

    // ------------------------------------------------------------ /token 刷新

    @Test
    fun refreshRotatesAndReuseRevokesWholeFamily() {
        val first = exchange()
        val rt2 = OAuth.refresh(store, mapOf("grant_type" to "refresh_token",
            "refresh_token" to first.getString("refresh_token"), "client_id" to clientId), ep, now + 1)
            .getString("refresh_token")
        assertFalse(first.getString("refresh_token") == rt2)
        val e1 = refreshErr(first.getString("refresh_token"), at = now + 2)
        assertEquals("invalid_grant", e1.code)
        // 重用一次就整族作废：新刷新令牌在**未被使用前**已 revoked
        assertNotNull(store.tokenByHash(Crypto.sha256Hex(rt2))!!.revokedAt)
        val e2 = refreshErr(rt2, at = now + 3)
        assertEquals("invalid_grant", e2.code)
        // 新发的那枚访问令牌也一起死
        assertNotNull(store.tokenByHash(Crypto.sha256Hex(first.getString("access_token")))!!.revokedAt)
    }

    @Test
    fun refreshIsReadThenSingleCas() {
        val first = exchange()
        val raw = first.getString("refresh_token")
        val before = store.consumeRefreshCalls
        refreshWith(raw, at = now + 1)
        assertEquals("normal refresh must CAS exactly once", before + 1, store.consumeRefreshCalls)
        // 重用路径：读到 replacedByHash 就吊销 family，根本不进 CAS
        val afterNormal = store.consumeRefreshCalls
        refreshErr(raw, at = now + 2)
        assertEquals("reuse path must not touch CAS", afterNormal, store.consumeRefreshCalls)
    }

    @Test
    fun refreshKeepsScopesAndLinksReplacedBy() {
        val first = exchange(scope = "health.read")
        val t = refreshWith(first.getString("refresh_token"), at = now + 1)
        assertEquals("health.read", t.getString("scope"))
        val a = store.tokenByHash(Crypto.sha256Hex(t.getString("access_token")))!!
        val r = store.tokenByHash(Crypto.sha256Hex(t.getString("refresh_token")))!!
        assertEquals(listOf("health.read"), a.scopes)
        assertEquals(a.familyId, r.familyId)
        assertEquals(store.tokenByHash(Crypto.sha256Hex(first.getString("refresh_token")))!!.familyId, a.familyId)
        // 轮换把旧行链到新行（同一句 CAS）
        assertEquals(Crypto.sha256Hex(t.getString("refresh_token")),
            store.tokenByHash(Crypto.sha256Hex(first.getString("refresh_token")))!!.replacedByHash)
    }

    @Test
    fun refreshInheritsTokenPolicyAndWindow() {
        // 管理页把令牌收窄成 hc_status + 周二；刷新后新访问/刷新行都必须带着这两层
        val raw = seedToken(policy = """["hc_status"]""", window = """{"days":[2]}""")
        val t = refreshWith(raw, at = now + 1)
        for (fresh in listOf(t.getString("access_token"), t.getString("refresh_token"))) {
            val row = store.tokenByHash(Crypto.sha256Hex(fresh))!!
            assertEquals("policy must survive rotation", """["hc_status"]""", row.toolPolicyJson)
            assertEquals("window must survive rotation", """{"days":[2]}""", row.windowJson)
            assertEquals("f-seed", row.familyId)
            assertEquals(listOf("health.read"), row.scopes)
        }
    }

    @Test
    fun refreshRejectsForeignClientAndExpiredToken() {
        val first = exchange()
        val other = registerClient(cb)
        assertEquals("invalid_grant", assertThrows(OAuthError::class.java) {
            refreshWith(first.getString("refresh_token"), client = other, at = now + 1)
        }.code)
        // 别人的 client_id 撤不掉这枚令牌
        assertNull(store.tokenByHash(Crypto.sha256Hex(first.getString("refresh_token")))!!.revokedAt)
        assertEquals("invalid_grant",
            refreshErr(first.getString("refresh_token"), at = now + 10 + TokenFormat.REFRESH_TTL_MS).code)
    }

    @Test
    fun refreshRejectsAccessTokenAndUnknownToken() {
        val first = exchange()
        assertEquals("invalid_grant", refreshErr(first.getString("access_token")).code)
        assertEquals("invalid_grant", refreshErr("hcb_rt_nope").code)
        assertEquals("unsupported_grant_type", assertThrows(OAuthError::class.java) {
            OAuth.refresh(store, mapOf("grant_type" to "authorization_code"), ep, now)
        }.code)
    }

    @Test
    fun refreshRefusesRevokedClient() {
        val first = exchange()
        store.revokeClient(clientId, now + 1)
        val e = assertThrows(OAuthError::class.java) { refreshWith(first.getString("refresh_token"), at = now + 2) }
        assertEquals("invalid_client", e.code)
    }

    @Test
    fun refreshRejectsTokenOfAnotherResource() {
        // 公网基址切换后旧令牌只能重来（Review Focus 3）
        val first = exchange()
        val pub = Endpoints.from("https://hc.example.com")
        assertEquals("invalid_grant", assertThrows(OAuthError::class.java) {
            OAuth.refresh(store, mapOf("grant_type" to "refresh_token",
                "refresh_token" to first.getString("refresh_token"), "client_id" to clientId), pub, now + 1)
        }.code)
    }

    // ------------------------------------------------------------ /revoke

    @Test
    fun revokeOnlyAffectsOwnClient() {
        val t = exchange()
        val access = t.getString("access_token")
        OAuth.revoke(store, mapOf("token" to access, "client_id" to "someone-else"), ep, now + 1)
        assertNotNull(Principals.resolve(store, access, ep.resource, now + 2))
        OAuth.revoke(store, mapOf("token" to access, "client_id" to clientId), ep, now + 3)
        assertNull(Principals.resolve(store, access, ep.resource, now + 4))
    }

    @Test
    fun revokeConfidentialClientRequiresItsSecret() {
        val m = OAuth.registerClient(store, dcrBody(cb, authMethod = "client_secret_post"), false, now)
        val cid = m.getString("client_id")
        val secret = m.getString("client_secret")
        val raw = seedToken(kind = McpStatus.KIND_ACCESS, client = cid)

        // 没给 secret：忽略（200，令牌还活着）
        OAuth.revoke(store, mapOf("token" to raw, "client_id" to cid), ep, now + 1)
        assertNotNull(Principals.resolve(store, raw, ep.resource, now + 2))
        // secret 给错：同样忽略
        OAuth.revoke(store, mapOf("token" to raw, "client_id" to cid, "client_secret" to "hcb_cs_wrong"), ep, now + 3)
        assertNotNull(Principals.resolve(store, raw, ep.resource, now + 4))
        // secret 正确：才真的撤
        OAuth.revoke(store, mapOf("token" to raw, "client_id" to cid, "client_secret" to secret), ep, now + 5)
        assertNull(Principals.resolve(store, raw, ep.resource, now + 6))
    }

    @Test
    fun revokePublicClientNeedsNoSecret() {
        val raw = seedToken(kind = McpStatus.KIND_ACCESS)
        assertNull(store.client(clientId)!!.secretHash)
        OAuth.revoke(store, mapOf("token" to raw, "client_id" to clientId), ep, now + 1)
        assertNull(Principals.resolve(store, raw, ep.resource, now + 2))
    }

    @Test
    fun revokeRefreshTokenKillsFamily() {
        val t = exchange()
        OAuth.revoke(store, mapOf("token" to t.getString("refresh_token"), "client_id" to clientId), ep, now + 1)
        assertNull(Principals.resolve(store, t.getString("access_token"), ep.resource, now + 2))
        assertEquals("invalid_grant", refreshErr(t.getString("refresh_token"), at = now + 3).code)
    }

    @Test
    fun revokeUnknownTokenIsSilent() {
        OAuth.revoke(store, mapOf("token" to "hcb_at_nope", "client_id" to clientId), ep, now)
        OAuth.revoke(store, emptyMap(), ep, now)
        assertEquals(0, store.tokens().size)
    }

    // ------------------------------------------------------------ 错误卫生

    @Test
    fun errorsNeverCarrySecretMaterial() {
        val code = codeFor()
        val wrongVerifier = "y".repeat(43)
        val e = assertThrows(OAuthError::class.java) { exchangeWith(code, wrongVerifier) }
        for (secret in listOf(code, wrongVerifier, verifier, clientId)) {
            assertFalse("error message leaked material", e.message!!.contains(secret))
        }
    }
}
