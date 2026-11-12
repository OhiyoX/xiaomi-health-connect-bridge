package id.harness.hcbridge.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [Principals.resolve] 的失败关闭测试（JVM，[InMemoryStore]）。
 *
 * 关键点（计划 Review Focus 3/4，Task 3 的交待）：每次解析都**重读 client**；
 * client 缺失、已吊销、非 active 或 `redirect_uris_json` 坏掉 ⇒ 立刻吊销该令牌 family 并返回 null。
 * 令牌本身还要满足：kind=access、未过期、未吊销、`resource` 与当前基址一致。
 */
class PrincipalsTest {

    private val store = InMemoryStore()
    private val now = 1_700_000_000_000L
    private val ep = Endpoints.local()
    private val clientId = "hcb_ci_test"

    @Before
    fun setUp() {
        store.putClient(client())
    }

    // ------------------------------------------------------------ 小工具

    private fun client(
        id: String = clientId,
        status: String = McpStatus.CLIENT_ACTIVE,
        redirects: String = """["http://127.0.0.1:7777/cb"]""",
        policy: String? = null,
        window: String? = null,
        revokedAt: Long? = null,
        name: String = "test client",
    ) = ClientRow(id, null, name, redirects, policy, window, status, now, null, revokedAt)

    private fun token(
        raw: String,
        kind: String = McpStatus.KIND_ACCESS,
        family: String = "f1",
        scopes: List<String> = listOf("health.read"),
        policy: String? = null,
        window: String? = null,
        resource: String = ep.resource,
        expiresAt: Long = now + TokenFormat.ACCESS_TTL_MS,
        revokedAt: Long? = null,
    ): String {
        store.putToken(
            TokenRow(
                tokenHash = Crypto.sha256Hex(raw),
                prefix = TokenFormat.prefixOf(raw),
                kind = kind,
                clientId = clientId,
                familyId = family,
                scopes = scopes,
                toolPolicyJson = policy,
                windowJson = window,
                resource = resource,
                createdAt = now,
                expiresAt = expiresAt,
                revokedAt = revokedAt,
            ),
        )
        return raw
    }

    private fun resolve(raw: String, resource: String = ep.resource, at: Long = now + 1): Principal? =
        Principals.resolve(store, raw, resource, at)

    // ------------------------------------------------------------ OAuth

    @Test
    fun resolvesOauthPrincipalWithEveryLayer() {
        store.putClient(client(policy = """["hc_status"]""", window = """{"days":[2]}"""))
        val raw = token(TokenFormat.newAccessToken(), policy = """["hc_status","query_spo2"]""")
        val p = resolve(raw) ?: error("must resolve")
        assertEquals("oauth", p.kind)
        assertEquals(clientId, p.subject)
        assertEquals("test client", p.label)
        assertEquals(setOf("health.read"), p.scopes)
        // 两层都在，顺序 = 客户端层 + 令牌层（管理页照这个顺序展示）
        assertEquals(listOf("""["hc_status"]"""), listOf(p.policies[0]))
        assertEquals(listOf("""["hc_status","query_spo2"]"""), listOf(p.policies[1]))
        assertEquals(listOf("""{"days":[2]}"""), listOf(p.windows[0]))
        assertNull(p.windows[1])
        assertEquals(setOf("hc_status"), p.allowedTools)
        assertEquals(now + TokenFormat.ACCESS_TTL_MS, p.expiresAt)
    }

    @Test
    fun emptyPolicyMeansNoToolsAtAll() {
        store.putClient(client(policy = "[]"))
        val p = resolve(token(TokenFormat.newAccessToken()))
        assertNotNull(p)
        assertTrue("[] must not mean unrestricted", p!!.allowedTools.isEmpty())
    }

    @Test
    fun corruptPolicyFailsClosedToNoTools() {
        val p = resolve(token(TokenFormat.newAccessToken(), policy = "{not json"))
        assertNotNull(p)
        assertTrue(p!!.allowedTools.isEmpty())
    }

    @Test
    fun accessTokenIsBoundToResource() {
        val raw = token(TokenFormat.newAccessToken())
        assertNotNull(resolve(raw))
        assertNull(resolve(raw, resource = "https://other.example.com/mcp"))
        // 只是不匹配，不该顺手吊销（客户端会重新授权）
        assertNull(store.tokenByHash(Crypto.sha256Hex(raw))!!.revokedAt)
    }

    @Test
    fun refreshTokenCannotAuthenticate() {
        val raw = token(TokenFormat.newRefreshToken(), kind = McpStatus.KIND_REFRESH,
            expiresAt = now + TokenFormat.REFRESH_TTL_MS)
        assertNull(resolve(raw))
    }

    @Test
    fun expiredOrRevokedTokenIsRejected() {
        assertNull(resolve(token(TokenFormat.newAccessToken(), expiresAt = now)))
        assertNull(resolve(token(TokenFormat.newAccessToken(), revokedAt = now)))
    }

    @Test
    fun unknownTokenIsRejected() {
        assertNull(resolve(""))
        assertNull(resolve("   "))
        assertNull(resolve(TokenFormat.newAccessToken()))
    }

    @Test
    fun revokedClientFailsClosedAndKillsFamily() {
        val raw = token(TokenFormat.newAccessToken())
        store.revokeClient(clientId, now + 1)
        assertNull(resolve(raw, at = now + 2))
        assertNotNull(store.tokenByHash(Crypto.sha256Hex(raw))!!.revokedAt)
    }

    @Test
    fun pendingClientFailsClosed() {
        val raw = token(TokenFormat.newAccessToken())
        store.putClient(client(status = McpStatus.CLIENT_PENDING))
        assertNull(resolve(raw))
        assertNotNull(store.tokenByHash(Crypto.sha256Hex(raw))!!.revokedAt)
    }

    @Test
    fun missingClientFailsClosed() {
        val raw = token(TokenFormat.newAccessToken())
        val orphan = token(TokenFormat.newAccessToken(), family = "f-orphan")
        store.putToken(
            store.tokenByHash(Crypto.sha256Hex(orphan))!!.copy(clientId = "hcb_ci_missing"),
        )
        assertNull(resolve(orphan))
        assertNotNull(store.tokenByHash(Crypto.sha256Hex(orphan))!!.revokedAt)
        // 客户还在的那枚照常解析
        assertNotNull(resolve(raw))
    }

    @Test
    fun corruptClientFailsClosed() {
        val raw = token(TokenFormat.newAccessToken())
        store.putClient(client(redirects = "{not json"))
        assertNull(resolve(raw))
        assertNotNull(store.tokenByHash(Crypto.sha256Hex(raw))!!.revokedAt)
    }

    @Test
    fun resolveTouchesLastUsed() {
        val raw = token(TokenFormat.newAccessToken())
        resolve(raw)
        assertEquals(now + 1, store.tokenByHash(Crypto.sha256Hex(raw))!!.lastUsedAt)
    }

    // ------------------------------------------------------------ 静态 key

    @Test
    fun resolvesStaticKeyPrincipal() {
        val raw = TokenFormat.newStaticKey()
        val id = store.putKey("laptop", Crypto.sha256Hex(raw), """["hc_status"]""", null, now)
        val p = resolve(raw) ?: error("must resolve")
        assertEquals("static", p.kind)
        assertEquals("key:$id", p.subject)
        assertEquals("laptop", p.label)
        assertEquals(setOf(ToolCatalog.SCOPE_READ, ToolCatalog.SCOPE_WRITE), p.scopes)
        assertEquals(listOf("""["hc_status"]"""), p.policies)
        assertEquals(listOf<String?>(null), p.windows)
        assertEquals(setOf("hc_status"), p.allowedTools)
        assertEquals(Long.MAX_VALUE, p.expiresAt)
        assertEquals(now + 1, store.keys().first().lastUsedAt)
    }

    @Test
    fun revokedStaticKeyIsRejected() {
        val raw = TokenFormat.newStaticKey()
        val id = store.putKey("laptop", Crypto.sha256Hex(raw), null, null, now)
        store.revokeKey(id, now + 1)
        assertNull(resolve(raw))
    }

    @Test
    fun unknownStaticKeyIsRejected() {
        assertNull(resolve(TokenFormat.newStaticKey()))
    }

    @Test
    fun unknownPrefixIsRejected() {
        assertNull(resolve("hcb_zz_something"))
    }
}
