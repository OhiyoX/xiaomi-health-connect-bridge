package id.harness.hcbridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import id.harness.hcbridge.auth.ClientRow
import id.harness.hcbridge.auth.CodeRow
import id.harness.hcbridge.auth.McpConfig
import id.harness.hcbridge.auth.McpDb
import id.harness.hcbridge.auth.McpStatus
import id.harness.hcbridge.auth.McpStore
import id.harness.hcbridge.auth.PendingRow
import id.harness.hcbridge.auth.SqlMcpStore
import id.harness.hcbridge.auth.TokenFormat
import id.harness.hcbridge.auth.TokenRow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * `mcp.db` 的真库合约（在模拟器上跑）：schema 建得出来、CAS 真的只成功一次、级联与审计链成立。
 *
 * 这些不变式在 JVM 侧由 `McpStoreTest`（[id.harness.hcbridge.auth.InMemoryStore]）覆盖语义；
 * 这里验证的是 **SQL 实现**本身：`UPDATE … WHERE 源状态未变` 的受影响行数、索引/表名、WAL 打开。
 */
@RunWith(AndroidJUnit4::class)
class McpDbTest {

    private lateinit var ctx: Context
    private lateinit var store: SqlMcpStore
    private lateinit var dbName: String
    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        dbName = "t-${UUID.randomUUID()}.db"
        store = SqlMcpStore(McpDb(ctx, dbName))
        Store.overrideForTest(store)
    }

    @After
    fun tearDown() {
        Store.overrideForTest(null)
        store.close()
        ctx.deleteDatabase(dbName)
        // storeIsSingletonUnlessOverridden 会打开真实 mcp.db：测试结束删掉，别把开发机配置留成脏状态
        ctx.deleteDatabase(McpDb.DB_NAME)
    }

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

    private fun pending(id: String, expires: Long = now + 300_000) = PendingRow(
        id = id,
        clientId = "c1",
        redirectUri = "http://127.0.0.1/cb",
        codeChallenge = "chal",
        scopes = listOf("health.read"),
        resource = "http://127.0.0.1:8765/mcp",
        state = "st",
        createdAt = now,
        expiresAt = expires,
        status = McpStatus.PENDING,
    )

    private fun code(hash: String, clientId: String = "c1") = CodeRow(
        codeHash = hash,
        clientId = clientId,
        redirectUri = "http://127.0.0.1/cb",
        codeChallenge = "chal",
        scopes = listOf("health.read"),
        resource = "http://127.0.0.1:8765/mcp",
        createdAt = now,
        expiresAt = now + 300_000,
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
    fun freshDatabaseHasSchemaAndWal() {
        // 第一次访问才真正建库：没有旧库 ⇒ onCreate 一次建全（迁移 = 全新创建）
        store.setting("probe") // 触发 onCreate
        val db = McpDb(ctx, dbName).readableDatabase
        val tables = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name", emptyArray(),
        ).use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) out.add(c.getString(0))
            out
        }
        for (t in listOf(
            "oauth_clients", "oauth_pending", "oauth_codes", "oauth_tokens",
            "static_keys", "audit_log", "settings",
        )) {
            assertTrue("missing table $t", t in tables)
        }
        val indexes = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'index' ORDER BY name", emptyArray(),
        ).use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) out.add(c.getString(0))
            out
        }
        for (i in listOf("idx_tokens_client", "idx_tokens_family", "idx_pending_client", "idx_audit_created")) {
            assertTrue("missing index $i", i in indexes)
        }
        assertEquals(McpDb.VERSION, db.version)
        assertTrue("WAL must be on", db.isWriteAheadLoggingEnabled)
        db.close()
    }

    @Test
    fun codeIsSingleUse() {
        store.putCode(code("code1"))
        assertNotNull(store.takeCode("code1", now))
        assertNull(store.takeCode("code1", now))
        store.putCode(code("code2").copy(expiresAt = now - 1))
        assertNull(store.takeCode("code2", now))
    }

    @Test
    fun pendingStateMachineIsCas() {
        store.putPending(pending("rq_1"))
        assertTrue(store.decidePending("rq_1", McpStatus.APPROVED, now))
        assertFalse(store.decidePending("rq_1", McpStatus.DENIED, now))
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
    fun findPendingMatchesScopesAsSet() {
        store.putPending(pending("rq_1").copy(scopes = listOf("health.read", "health.write")))
        val hit = store.findPending(
            "c1", "chal", "http://127.0.0.1/cb", "http://127.0.0.1:8765/mcp",
            listOf("health.write", "health.read"), "st", now,
        )
        assertNotNull(hit)
        assertEquals("rq_1", hit!!.id)
        assertNull(
            store.findPending(
                "c1", "chal", "http://127.0.0.1/cb", "http://127.0.0.1:8765/mcp",
                listOf("health.read"), "st", now,
            ),
        )
        // state 进键：同一个请求换个 state 不复用（null 与有值也分开）
        store.putPending(pending("rq_state").copy(state = "s1"))
        assertNull(
            store.findPending(
                "c1", "chal", "http://127.0.0.1/cb", "http://127.0.0.1:8765/mcp",
                listOf("health.read"), null, now,
            ),
        )
        assertNotNull(
            store.findPending(
                "c1", "chal", "http://127.0.0.1/cb", "http://127.0.0.1:8765/mcp",
                listOf("health.read"), "s1", now,
            ),
        )
    }

    @Test
    fun rejectAndDeletePendingClient() {
        store.putClient(client("c9", status = McpStatus.CLIENT_PENDING))
        store.rejectClient("c9", now)
        assertEquals(McpStatus.CLIENT_REVOKED, store.client("c9")!!.status)
        assertNotNull(store.client("c9")!!.revokedAt)
        store.deleteClient("c9")
        assertNull(store.client("c9"))
        // 活跃客户端不能被删
        store.putClient(client("c8"))
        store.deleteClient("c8")
        assertNotNull(store.client("c8"))
    }

    @Test
    fun consumeRefreshIsSingleUse() {
        store.putToken(token("h1", kind = McpStatus.KIND_REFRESH, family = "f1"))
        assertNotNull(store.consumeRefresh("h1", now, "h2"))
        assertNull(store.consumeRefresh("h1", now))
        val old = store.tokenByHash("h1")!!
        assertEquals(now, old.revokedAt)
        assertEquals("h2", old.replacedByHash)
        // 访问令牌不能当刷新令牌消费
        store.putToken(token("a9", kind = McpStatus.KIND_ACCESS))
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
        assertNull(store.tokenByHash("a2")!!.revokedAt)
        assertNotNull(store.takeCode("code2", now))
    }

    @Test
    fun revokeClientClearsPendingRequests() {
        store.putClient(client("c1"))
        store.putPending(pending("rq_pending"))
        store.putPending(pending("rq_approved"))
        store.decidePending("rq_approved", McpStatus.APPROVED, now)
        store.revokeClient("c1", now)
        assertEquals(McpStatus.DENIED, store.pending("rq_pending")!!.status)
        assertEquals(McpStatus.DENIED, store.pending("rq_approved")!!.status)
        assertFalse(store.consumePending("rq_approved", now))
    }

    @Test
    fun auditPruneKeepsChainValid() {
        repeat(2_100) { store.audit("admin", "tool_call", "hc_status", "ok", null, now + it) }
        assertTrue(store.auditChainOk())
        assertEquals(McpStore.MAX_AUDIT_ROWS, store.auditRows(limit = 9_999).size)
        assertNotNull(store.setting(McpStore.AUDIT_BASE_KEY))
        assertEquals(now + 2_099, store.auditRows(limit = 1).first().createdAt)
    }

    @Test
    fun auditRowsFilterAndOrder() {
        store.audit("admin", "tool_call", "hc_status", "ok", null, now)
        store.audit("hcb_at_abc", "tool_call", "hc_dump", "denied", "no_scope", now + 1)
        store.audit("admin", "login", null, "ok", null, now + 2)
        assertEquals(3, store.auditRows().size)
        assertEquals(now + 2, store.auditRows(limit = 1).first().createdAt) // 新的在前
        assertEquals(1, store.auditRows(actor = "admin", action = "tool_call").size)
        assertEquals(1, store.auditRows(result = "denied").size)
        assertEquals("no_scope", store.auditRows(result = "denied").first().detail)
    }

    @Test
    fun auditChainDetectsRawUpdate() {
        store.audit("admin", "login", null, "ok", null, now)
        store.audit("admin", "tool_call", "hc_status", "denied", "no_scope", now + 1)
        assertTrue(store.auditChainOk())
        // 绕过 store 直接改库（等价于有人手工 UPDATE）：链必须自证被篡改
        val raw = McpDb(ctx, dbName)
        try {
            val id = store.auditRows(limit = 1).first().id
            raw.writableDatabase.execSQL(
                "UPDATE audit_log SET detail = 'forged' WHERE id = ?", arrayOf<Any>(id),
            )
        } finally {
            raw.close()
        }
        assertFalse("直改审计行后 auditChainOk 必须为 false", store.auditChainOk())
    }

    @Test
    fun auditChainDetectsDeletedRow() {
        store.audit("admin", "login", null, "ok", null, now)
        val middle = store.auditRows(limit = 1).first().id
        store.audit("admin", "tool_call", "hc_status", "denied", "no_scope", now + 1)
        store.audit("admin", "tool_call", "hc_dump", "ok", null, now + 2)
        assertTrue(store.auditChainOk())
        val raw = McpDb(ctx, dbName)
        try {
            raw.writableDatabase.execSQL("DELETE FROM audit_log WHERE id = ?", arrayOf<Any>(middle))
        } finally {
            raw.close()
        }
        assertFalse("删中间审计行后 auditChainOk 必须为 false", store.auditChainOk())
    }

    @Test
    fun settingsRoundTripAndDefaults() {
        val c = McpConfig(store)
        assertTrue(c.requireAuth)
        assertFalse(c.allowLoopbackNoAuth)
        assertTrue(c.confirmNewClients)
        assertEquals("off", c.tunnelMode)
        assertNull(c.publicBase)

        c.publicBase = "https://hc.example.com"
        c.allowLoopbackNoAuth = true
        c.confirmNewClients = false
        val again = McpConfig(store)
        assertEquals("https://hc.example.com", again.publicBase)
        assertTrue(again.allowLoopbackNoAuth)
        assertFalse(again.confirmNewClients)
        assertTrue(again.requireAuth)
        again.publicBase = null
        assertNull(McpConfig(store).publicBase)
    }

    @Test
    fun touchIsThrottled() {
        store.putToken(token("a1"))
        store.touchToken("a1", now)
        assertEquals(now, store.tokenByHash("a1")!!.lastUsedAt)
        store.touchToken("a1", now + 1_000)
        assertEquals(now, store.tokenByHash("a1")!!.lastUsedAt)
        store.touchToken("a1", now + McpStore.TOUCH_MIN_INTERVAL_MS + 1)
        assertEquals(now + McpStore.TOUCH_MIN_INTERVAL_MS + 1, store.tokenByHash("a1")!!.lastUsedAt)
    }

    @Test
    fun keysLifecycle() {
        val id = store.putKey("laptop", "kh", null, null, now)
        store.putKey("phone", "kh2", "[]", "{\"days\":[1]}", now + 1)
        assertEquals(2, store.keys().size)
        store.revokeKey(id, now + 2)
        assertNotNull(store.keys().first { it.id == id }.revokedAt)
    }

    @Test
    fun updateKeyKeepsHashAndSkipsRevokedRows() {
        val id = store.putKey("termux", "kh", null, null, now)
        assertTrue(store.updateKey(id, "[\"hc_status\"]", "{\"days\":[1]}", now + 1))
        val row = store.keys().first { it.id == id }
        assertEquals("kh", row.hash)                       // hash 不变：明文 key 不作废
        assertEquals("[\"hc_status\"]", row.toolPolicyJson)
        assertEquals("{\"days\":[1]}", row.windowJson)
        store.revokeKey(id, now + 2)
        // 已吊销 ⇒ 不更新（策略改不动已失效的 key）
        assertFalse(store.updateKey(id, null, null, now + 3))
        assertEquals("[\"hc_status\"]", store.keys().first { it.id == id }.toolPolicyJson)
        assertFalse(store.updateKey(9_999, null, null, now + 4))
    }

    @Test
    fun storeIsSingletonUnlessOverridden() {
        // 注入期间 Store.of 返回注入的临时库（不是真实 mcp.db）
        assertTrue(Store.of(ctx) === store)
        assertTrue(Store.of(ctx) === store)
        Store.overrideForTest(null)
        val real = Store.of(ctx)
        assertTrue(real is SqlMcpStore)
        assertTrue(Store.of(ctx) === real)
        // 关掉真实库并清掉进程单例，避免影响后续测试（@After 还会删掉 mcp.db）
        Store.overrideForTest(null)
        real.close()
    }
}
