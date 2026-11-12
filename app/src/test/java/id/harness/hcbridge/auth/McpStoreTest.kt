package id.harness.hcbridge.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [McpStore] 的**合约**测试（跑在 JVM 上，用 [InMemoryStore]）。
 *
 * 同一组合约在 androidTest 的 `McpDbTest` 里对 [SqlMcpStore] 再跑一遍：真库的 CAS 是
 * `UPDATE … WHERE 源态未变`，内存 store 是同一把锁下"判源态 + 改状态"，两边语义必须一致。
 * JVM 侧的意义：这些是 OAuth 的核心不变式，改坏了要秒级发现，不依赖模拟器。
 */
class McpStoreTest {

    private val store = InMemoryStore()
    private val now = 1_700_000_000_000L

    // ------------------------------------------------------------ 构造小工具

    private fun client(id: String, status: String = McpStatus.CLIENT_ACTIVE) = ClientRow(
        clientId = id,
        secretHash = "sh_$id",
        name = "client $id",
        redirectUrisJson = "[\"http://127.0.0.1/cb\"]",
        toolPolicyJson = null,
        windowJson = null,
        status = status,
        createdAt = now,
    )

    private fun pending(id: String, scopes: List<String> = listOf("health.read"), expires: Long = now + 300_000) =
        PendingRow(
            id = id,
            clientId = "c1",
            redirectUri = "http://127.0.0.1/cb",
            codeChallenge = "chal",
            scopes = scopes,
            resource = "http://127.0.0.1:8765/mcp",
            state = "st",
            createdAt = now,
            expiresAt = expires,
            status = McpStatus.PENDING,
        )

    private fun code(hash: String, clientId: String = "c1", expires: Long = now + 300_000) = CodeRow(
        codeHash = hash,
        clientId = clientId,
        redirectUri = "http://127.0.0.1/cb",
        codeChallenge = "chal",
        scopes = listOf("health.read"),
        resource = "http://127.0.0.1:8765/mcp",
        createdAt = now,
        expiresAt = expires,
    )

    private fun token(
        hash: String,
        kind: String = McpStatus.KIND_ACCESS,
        clientId: String = "c1",
        family: String = "f1",
    ) = TokenRow(
        tokenHash = hash,
        prefix = "hcb_at_abc",
        kind = kind,
        clientId = clientId,
        familyId = family,
        scopes = listOf("health.read"),
        toolPolicyJson = null,
        windowJson = null,
        resource = "http://127.0.0.1:8765/mcp",
        createdAt = now,
        expiresAt = now + TokenFormat.ACCESS_TTL_MS,
    )

    // ------------------------------------------------------------------ 测试

    @Test
    fun codeIsSingleUse() {
        store.putCode(code("code1"))
        assertNotNull(store.takeCode("code1", now))
        assertNull(store.takeCode("code1", now))
        // 过期的码也拿不到
        store.putCode(code("code2", expires = now - 1))
        assertNull(store.takeCode("code2", now))
    }

    @Test
    fun pendingStateMachineIsCas() {
        store.putPending(pending("rq_1"))
        assertTrue(store.decidePending("rq_1", McpStatus.APPROVED, now))
        assertFalse(store.decidePending("rq_1", McpStatus.DENIED, now)) // 源态已是 approved
        assertEquals(McpStatus.APPROVED, store.pending("rq_1")!!.status)
        assertTrue(store.consumePending("rq_1", now))
        assertFalse(store.consumePending("rq_1", now))
        assertFalse(store.decidePending("rq_1", McpStatus.DENIED, now))
    }

    @Test
    fun expiredPendingCannotBeConsumed() {
        store.putPending(pending("rq_x", expires = now - 1))
        assertTrue(store.decidePending("rq_x", McpStatus.APPROVED, now))
        assertFalse(store.consumePending("rq_x", now))
    }

    @Test
    fun findPendingMatchesOnScopeSetAndReuse() {
        store.putPending(pending("rq_1", scopes = listOf("health.read", "health.write")))
        val hit = store.findPending(
            clientId = "c1",
            codeChallenge = "chal",
            redirectUri = "http://127.0.0.1/cb",
            resource = "http://127.0.0.1:8765/mcp",
            // 顺序不同但集合相同 ⇒ 同一个请求（幂等复用）
            scopes = listOf("health.write", "health.read"),
            state = "st",
            now = now,
        )
        assertNotNull(hit)
        assertEquals("rq_1", hit!!.id)
        // 少一个 scope ⇒ 不是同一个请求
        assertNull(
            store.findPending(
                "c1", "chal", "http://127.0.0.1/cb", "http://127.0.0.1:8765/mcp",
                listOf("health.read"), "st", now,
            ),
        )
        // state 也进键（含 null 与有值之分）：换一个 state 不是同一个请求
        store.putPending(pending("rq_state").copy(state = "s1"))
        assertNull(
            store.findPending(
                "c1", "chal", "http://127.0.0.1/cb", "http://127.0.0.1:8765/mcp",
                listOf("health.read"), "st", now,
            ),
        )
        assertNotNull(
            store.findPending(
                "c1", "chal", "http://127.0.0.1/cb", "http://127.0.0.1:8765/mcp",
                listOf("health.read"), "s1", now,
            ),
        )
        // 已决策的行不再复用
        store.decidePending("rq_1", McpStatus.DENIED, now)
        assertNull(
            store.findPending(
                "c1", "chal", "http://127.0.0.1/cb", "http://127.0.0.1:8765/mcp",
                listOf("health.read", "health.write"), "st", now,
            ),
        )
    }

    @Test
    fun pendingListAndExpirySweep() {
        store.putPending(pending("rq_live"))
        store.putPending(pending("rq_dead", expires = now - 1))
        store.putPending(pending("rq_decided"))
        store.decidePending("rq_decided", McpStatus.APPROVED, now)
        assertEquals(listOf("rq_live"), store.pendingList(now).map { it.id })
        assertEquals(1, store.deleteExpiredPending(now))
        assertNull(store.pending("rq_dead"))
        assertNotNull(store.pending("rq_live"))
    }

    @Test
    fun rejectAndDeletePendingClient() {
        store.putClient(client("c9", status = McpStatus.CLIENT_PENDING))
        store.rejectClient("c9", now)
        assertEquals(McpStatus.CLIENT_REVOKED, store.client("c9")!!.status)
        assertNotNull(store.client("c9")!!.revokedAt)
        store.deleteClient("c9")
        assertNull(store.client("c9"))
    }

    @Test
    fun approveOnlyLiftsPendingClient() {
        store.putClient(client("c2", status = McpStatus.CLIENT_PENDING))
        store.approveClient("c2", now)
        assertEquals(McpStatus.CLIENT_ACTIVE, store.client("c2")!!.status)
        assertNull(store.client("c2")!!.revokedAt)
        // 已吊销的不能被 approve 复活
        store.putClient(client("c3", status = McpStatus.CLIENT_REVOKED).copy(revokedAt = now))
        store.approveClient("c3", now + 1)
        assertEquals(McpStatus.CLIENT_REVOKED, store.client("c3")!!.status)
        assertNotNull(store.client("c3")!!.revokedAt)
    }

    @Test
    fun deleteRefusesActiveClient() {
        store.putClient(client("c4"))
        store.deleteClient("c4")
        assertNotNull(store.client("c4"))
    }

    @Test
    fun consumeRefreshIsSingleUse() {
        store.putToken(token("h1", kind = McpStatus.KIND_REFRESH, family = "fam"))
        assertNotNull(store.consumeRefresh("h1", now, "h2"))
        assertNull(store.consumeRefresh("h1", now))
        assertEquals("h2", store.tokenByHash("h1")!!.replacedByHash)
        assertEquals(now, store.tokenByHash("h1")!!.revokedAt)
        // 访问令牌不能当刷新令牌消费
        store.putToken(token("a9", kind = McpStatus.KIND_ACCESS, family = "fam"))
        assertNull(store.consumeRefresh("a9", now))
        assertNull(store.tokenByHash("a9")!!.revokedAt)
    }

    @Test
    fun revokeFamilyKillsWholeRotation() {
        store.putToken(token("r1", kind = McpStatus.KIND_REFRESH, family = "fam"))
        store.putToken(token("a1", kind = McpStatus.KIND_ACCESS, family = "fam"))
        store.putToken(token("a2", kind = McpStatus.KIND_ACCESS, family = "other"))
        store.revokeFamily("fam", now)
        assertNotNull(store.tokenByHash("r1")!!.revokedAt)
        assertNotNull(store.tokenByHash("a1")!!.revokedAt)
        assertNull(store.tokenByHash("a2")!!.revokedAt)
        assertEquals(2, store.tokens().count { it.revokedAt != null })
        assertEquals(0, store.tokens(clientId = "nobody").size)
    }

    @Test
    fun revokeClientCascades() {
        store.putClient(client("c1"))
        store.putToken(token("a1", clientId = "c1"))
        store.putToken(token("a2", clientId = "c2"))
        store.putCode(code("code1", clientId = "c1"))
        store.putCode(code("code2", clientId = "c2"))
        store.revokeClient("c1", now)
        assertEquals(McpStatus.CLIENT_REVOKED, store.client("c1")!!.status)
        assertNotNull(store.client("c1")!!.revokedAt)
        assertNotNull(store.tokenByHash("a1")!!.revokedAt)
        assertNull(store.takeCode("code1", now))
        // 别的客户端不受影响
        assertNull(store.tokenByHash("a2")!!.revokedAt)
        assertNotNull(store.takeCode("code2", now))
    }

    @Test
    fun revokeClientClearsPendingRequests() {
        store.putClient(client("c1"))
        store.putPending(pending("rq_pending"))
        store.putPending(pending("rq_approved"))
        store.putPending(pending("rq_denied"))
        store.decidePending("rq_approved", McpStatus.APPROVED, now)
        store.decidePending("rq_denied", McpStatus.DENIED, now)
        store.revokeClient("c1", now)
        // pending/approved 一律 → denied（吊销后不能再点"允许"换码）
        assertEquals(McpStatus.DENIED, store.pending("rq_pending")!!.status)
        assertEquals(McpStatus.DENIED, store.pending("rq_approved")!!.status)
        assertEquals(McpStatus.DENIED, store.pending("rq_denied")!!.status)
        assertFalse(store.consumePending("rq_approved", now))
        // 别的客户端的请求不受影响
        store.putClient(client("c2"))
        store.putPending(pending("rq_other").copy(clientId = "c2"))
        store.revokeClient("c2", now)
        assertEquals(McpStatus.DENIED, store.pending("rq_other")!!.status)
    }

    @Test
    fun auditPruneKeepsChainValid() {
        repeat(2_100) { store.audit("admin", "tool_call", "hc_status", "ok", null, now + it) }
        assertTrue(store.auditChainOk())
        assertEquals(McpStore.MAX_AUDIT_ROWS, store.auditRows(limit = 9_999).size)
        assertNotNull(store.setting(McpStore.AUDIT_BASE_KEY))
        // 保留的是**最新**的 2000 行
        assertEquals(now + 2_099, store.auditRows(limit = 1).first().createdAt)
    }

    @Test
    fun auditChainLinksPrevHash() {
        store.audit("admin", "login", null, "ok", null, now)
        store.audit("admin", "tool_call", "hc_status", "denied", "no_scope", now + 1)
        assertTrue(store.auditChainOk())
        val rows = store.auditRows(limit = 10).sortedBy { it.id }
        assertEquals("第一行的 prevHash 是空串（链基）", "", rows[0].prevHash)
        assertEquals("后继行必须指向前一行的 hash", rows[0].hash, rows[1].prevHash)
    }

    @Test
    fun auditChainDetectsTamperedDetail() {
        store.audit("admin", "login", null, "ok", null, now)
        store.audit("admin", "tool_call", "hc_status", "denied", "no_scope", now + 1)
        assertTrue(store.auditChainOk())
        // 直改库（不动 hash）：重算对不上 ⇒ 必须 false
        store.tamperAuditDetail(store.auditRows(limit = 1).first().id, "forged")
        assertFalse("篡改 detail 后 auditChainOk 必须为 false", store.auditChainOk())
    }

    @Test
    fun auditChainDetectsDroppedRow() {
        store.audit("admin", "login", null, "ok", null, now)
        val middle = store.auditRows(limit = 1).first().id
        store.audit("admin", "tool_call", "hc_status", "denied", "no_scope", now + 1)
        store.audit("admin", "tool_call", "hc_dump", "ok", null, now + 2)
        assertTrue(store.auditChainOk())
        // 删中间一行、不写链基（模拟手工 DELETE）：后继 prevHash 指向不存在的 hash ⇒ false
        store.dropAuditRow(middle)
        assertFalse("删中间审计行后 auditChainOk 必须为 false", store.auditChainOk())
    }

    @Test
    fun auditDetailIsSanitized() {
        store.audit("admin", "tool_call", "hc_status", "denied", "line1\nline2\r\tend", now)
        val row = store.auditRows(limit = 1).first()
        assertEquals("line1 line2  end", row.detail)
        store.audit("admin", "tool_call", "hc_status", "denied", "x".repeat(500), now + 1)
        assertEquals(AuditChain.DETAIL_MAX, store.auditRows(limit = 1).first().detail!!.length)
        // 纯空白 ⇒ null（不存一根空串进链）
        store.audit("admin", "tool_call", "hc_status", "ok", "   \n  ", now + 2)
        assertNull(store.auditRows(limit = 1).first().detail)
        assertTrue(store.auditChainOk())
    }

    @Test
    fun settingsRoundTripAndDefaults() {
        val c = McpConfig(store)
        assertTrue(c.requireAuth)
        assertFalse(c.allowLoopbackNoAuth)
        assertTrue(c.confirmNewClients)
        assertEquals("off", c.tunnelMode)
        assertNull(c.publicBase)
        assertNull(c.adminPasswordHash)
        assertFalse(c.migrationNoticeSeen)

        c.publicBase = "https://hc.example.com"
        c.tunnelMode = "named"
        c.requireAuth = true
        c.allowLoopbackNoAuth = true
        c.confirmNewClients = false
        c.adminPasswordHash = "pbkdf2\$sha256\$120000\$salt\$hash"

        val again = McpConfig(store)
        assertEquals("https://hc.example.com", again.publicBase)
        assertEquals("named", again.tunnelMode)
        assertTrue(again.allowLoopbackNoAuth)
        assertFalse(again.confirmNewClients)
        assertEquals("pbkdf2\$sha256\$120000\$salt\$hash", again.adminPasswordHash)

        // 置空 = 回到缺省
        again.publicBase = null
        again.adminPasswordHash = null
        assertNull(McpConfig(store).publicBase)
        assertNull(McpConfig(store).adminPasswordHash)
    }

    @Test
    fun badTunnelModeIsRejected() {
        val c = McpConfig(store)
        c.tunnelMode = "quick"
        assertEquals("quick", c.tunnelMode)
        var threw = false
        try {
            c.tunnelMode = "internet"
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("tunnel mode must be validated", threw)
        assertEquals("quick", McpConfig(store).tunnelMode)
    }

    @Test
    fun touchIsThrottled() {
        store.putToken(token("a1"))
        store.touchToken("a1", now)
        assertEquals(now, store.tokenByHash("a1")!!.lastUsedAt)
        store.touchToken("a1", now + 1_000) // 窗口内：不写库
        assertEquals(now, store.tokenByHash("a1")!!.lastUsedAt)
        store.touchToken("a1", now + McpStore.TOUCH_MIN_INTERVAL_MS + 1)
        assertEquals(now + McpStore.TOUCH_MIN_INTERVAL_MS + 1, store.tokenByHash("a1")!!.lastUsedAt)
    }

    @Test
    fun keysLifecycle() {
        val id = store.putKey("laptop", "kh", null, null, now)
        store.putKey("phone", "kh2", "[]", "{\"days\":[1]}", now + 1)
        assertEquals(2, store.keys().size)
        store.touchKey(id, now)
        assertEquals(now, store.keys().first { it.id == id }.lastUsedAt)
        store.touchKey(id, now + 1_000)
        assertEquals(now, store.keys().first { it.id == id }.lastUsedAt) // 节流窗口内不写
        store.touchKey(id, now + McpStore.TOUCH_MIN_INTERVAL_MS)
        assertEquals(now + McpStore.TOUCH_MIN_INTERVAL_MS, store.keys().first { it.id == id }.lastUsedAt)
        store.revokeKey(id, now + 2)
        assertNotNull(store.keys().first { it.id == id }.revokedAt)
        assertEquals("[]", store.keys().first { it.id != id }.toolPolicyJson)
    }

    @Test
    fun updateKeyChangesOnlyPolicyAndWindow() {
        val id = store.putKey("termux", "kh", null, null, now)
        assertTrue(store.updateKey(id, "[\"hc_status\"]", "{\"days\":[1]}", now + 1))
        val updated = store.keys().first { it.id == id }
        // hash 与 label 不变：改了策略不等于让管理员手里的明文 key 作废
        assertEquals("kh", updated.hash)
        assertEquals("termux", updated.label)
        assertEquals("[\"hc_status\"]", updated.toolPolicyJson)
        assertEquals("{\"days\":[1]}", updated.windowJson)
        assertTrue(store.updateKey(id, null, null, now + 2))
        assertNull(store.keys().first { it.id == id }.toolPolicyJson)
        // 已吊销的行不再更新
        store.revokeKey(id, now + 3)
        assertFalse(store.updateKey(id, "[]", null, now + 4))
        assertNull(store.keys().first { it.id == id }.toolPolicyJson)
        assertFalse(store.updateKey(9_999, "[]", null, now + 5))
    }
}
