package id.harness.hcbridge.auth

/**
 * JVM 单测用的**内存 store**（计划 Task 4 的测试替身，Task 3 起就能用）。
 *
 * 语义必须与 [SqlMcpStore] 一致，尤其是：
 * - 每个「消费」是**原子 CAS**（同一把锁下判源态 + 改状态），重复调用返回 false/null；
 * - `revokeClient` 级联（client + 全部令牌 + 未用授权码）；
 * - 审计链 + 保留基（超出 [McpStore.MAX_AUDIT_ROWS] 时把被删最后一行的 hash 写进
 *   `settings("auditBaseHash")`）；
 * - `last_used_at` 写库节流。
 *
 * 故意不模拟 `SQLiteOpenHelper`/WAL —— 那是 androidTest 里 `McpDbTest` 的事。
 */
class InMemoryStore : McpStore {

    private val lock = Any()

    private val clients = LinkedHashMap<String, ClientRow>()
    private val pendings = LinkedHashMap<String, PendingRow>()
    private val codes = LinkedHashMap<String, CodeRow>()
    private val tokens = LinkedHashMap<String, TokenRow>()
    private val keys = LinkedHashMap<Long, KeyRow>()
    private val audits = ArrayList<AuditRow>()
    private val settings = HashMap<String, String>()

    private var nextKeyId = 1L
    private var nextAuditId = 1L

    /**
     * 测试观测点：`consumeRefresh` 被调用了几次。
     *
     * Review Focus 2 要求「先 `tokenByHash` 判重用 ⇒ 不满足才走单条 CAS」，所以
     * 重用路径必须**不进** CAS、正常刷新必须**恰好一次**。只断言结果看不住这一点。
     */
    var consumeRefreshCalls = 0
        private set

    // ------------------------------------------------------------------ 客户端

    override fun putClient(c: ClientRow) = sync {
        clients[c.clientId] = c
        Unit
    }

    override fun client(clientId: String): ClientRow? = sync { clients[clientId] }

    override fun clients(): List<ClientRow> = sync {
        clients.values.sortedWith(compareBy({ it.createdAt }, { it.clientId }))
    }

    override fun clientCount(): Int = sync { clients.size }

    override fun approveClient(clientId: String, at: Long) = sync {
        val c = clients[clientId]
        if (c != null && c.status == McpStatus.CLIENT_PENDING) {
            clients[clientId] = c.copy(status = McpStatus.CLIENT_ACTIVE, revokedAt = null)
        }
        Unit
    }

    override fun revokeClient(clientId: String, at: Long) = sync {
        val c = clients[clientId]
        if (c != null && c.revokedAt == null) {
            clients[clientId] = c.copy(status = McpStatus.CLIENT_REVOKED, revokedAt = at)
        }
        for ((h, t) in tokens.entries.toList()) {
            if (t.clientId == clientId && t.revokedAt == null) tokens[h] = t.copy(revokedAt = at)
        }
        for ((h, o) in codes.entries.toList()) {
            if (o.clientId == clientId && o.consumedAt == null) codes[h] = o.copy(consumedAt = at)
        }
        // 待确认/已批准但还没换码的请求一并作废（与 SQL 版同一事务语义）
        for ((id, q) in pendings.entries.toList()) {
            if (q.clientId == clientId &&
                (q.status == McpStatus.PENDING || q.status == McpStatus.APPROVED)
            ) {
                pendings[id] = q.copy(status = McpStatus.DENIED)
            }
        }
        Unit
    }

    override fun rejectClient(clientId: String, at: Long) = sync {
        val c = clients[clientId]
        if (c != null && c.status == McpStatus.CLIENT_PENDING) {
            clients[clientId] = c.copy(status = McpStatus.CLIENT_REVOKED, revokedAt = at)
        }
        Unit
    }

    override fun deleteClient(clientId: String) = sync {
        val c = clients[clientId]
        if (c != null && c.revokedAt != null) clients.remove(clientId)
        Unit
    }

    // ------------------------------------------------------- 待确认请求 / 授权码

    override fun putPending(p: PendingRow) = sync {
        pendings[p.id] = p
        Unit
    }

    override fun pending(id: String): PendingRow? = sync { pendings[id] }

    override fun findPending(
        clientId: String,
        codeChallenge: String,
        redirectUri: String,
        resource: String,
        scopes: List<String>,
        state: String?,
        now: Long,
    ): PendingRow? = sync {
        val want = ScopeJson.normalize(scopes)
        pendings.values
            .filter {
                it.clientId == clientId && it.codeChallenge == codeChallenge &&
                    it.redirectUri == redirectUri && it.resource == resource &&
                    it.status == McpStatus.PENDING && it.expiresAt > now &&
                    ScopeJson.normalize(it.scopes) == want && it.state == state
            }
            .maxByOrNull { it.createdAt }
    }

    /** 测试观测点：库里现存的授权码行数（410 路径必须**一行都没写**）。 */
    fun codeCount(): Int = sync { codes.size }

    override fun pendingList(now: Long): List<PendingRow> = sync {
        pendings.values
            .filter { it.status == McpStatus.PENDING && it.expiresAt > now }
            .sortedWith(compareBy({ it.createdAt }, { it.id }))
    }

    override fun decidePending(id: String, status: String, now: Long, scopes: List<String>?): Boolean {
        require(status == McpStatus.APPROVED || status == McpStatus.DENIED) { "bad pending status" }
        return sync {
            val p = pendings[id]
            if (p == null || p.status != McpStatus.PENDING) {
                false
            } else {
                pendings[id] = p.copy(status = status, scopes = scopes ?: p.scopes)
                true
            }
        }
    }

    override fun consumePending(id: String, now: Long): Boolean = sync {
        val p = pendings[id]
        if (p == null || p.status != McpStatus.APPROVED || p.expiresAt <= now) {
            false
        } else {
            pendings[id] = p.copy(status = McpStatus.CONSUMED)
            true
        }
    }

    override fun deleteExpiredPending(now: Long): Int = sync {
        val dead = pendings.values.filter { it.expiresAt <= now }.map { it.id }
        for (id in dead) pendings.remove(id)
        dead.size
    }

    override fun putCode(c: CodeRow) = sync {
        codes[c.codeHash] = c
        Unit
    }

    override fun takeCode(codeHash: String, now: Long): CodeRow? = sync {
        val c = codes[codeHash]
        if (c == null || c.consumedAt != null || c.expiresAt <= now) {
            null
        } else {
            codes[codeHash] = c.copy(consumedAt = now)
            c
        }
    }

    // -------------------------------------------------------------------- 令牌

    override fun putToken(t: TokenRow) = sync {
        tokens[t.tokenHash] = t
        Unit
    }

    override fun tokenByHash(h: String): TokenRow? = sync { tokens[h] }

    override fun touchToken(h: String, at: Long) = sync {
        val t = tokens[h]
        if (t != null && (t.lastUsedAt == null || t.lastUsedAt <= at - McpStore.TOUCH_MIN_INTERVAL_MS)) {
            tokens[h] = t.copy(lastUsedAt = at)
        }
        Unit
    }

    override fun consumeRefresh(hash: String, now: Long, replacedByHash: String?): TokenRow? = sync {
        consumeRefreshCalls++
        val t = tokens[hash]
        if (t == null || t.kind != McpStatus.KIND_REFRESH || t.revokedAt != null) {
            null
        } else {
            tokens[hash] = t.copy(
                revokedAt = now,
                lastUsedAt = now,
                replacedByHash = replacedByHash ?: t.replacedByHash,
            )
            t
        }
    }

    override fun revokeToken(h: String, at: Long) = sync {
        val t = tokens[h]
        if (t != null && t.revokedAt == null) tokens[h] = t.copy(revokedAt = at)
        Unit
    }

    override fun revokeFamily(familyId: String, at: Long) = sync {
        for ((h, t) in tokens.entries.toList()) {
            if (t.familyId == familyId && t.revokedAt == null) tokens[h] = t.copy(revokedAt = at)
        }
        Unit
    }

    override fun tokens(clientId: String?): List<TokenRow> = sync {
        tokens.values
            .filter { clientId == null || it.clientId == clientId }
            .sortedWith(compareBy({ it.createdAt }, { it.tokenHash }))
    }

    // ---------------------------------------------------------------- 静态 key

    override fun putKey(label: String, hash: String, policy: String?, window: String?, at: Long): Long =
        sync {
            val id = nextKeyId++
            keys[id] = KeyRow(id, label, hash, policy, window, at)
            id
        }

    override fun keys(): List<KeyRow> = sync { keys.values.sortedBy { it.id } }

    override fun updateKey(id: Long, policy: String?, window: String?, at: Long): Boolean = sync {
        val k = keys[id]
        if (k == null || k.revokedAt != null) {
            false
        } else {
            keys[id] = k.copy(toolPolicyJson = policy, windowJson = window)
            true
        }
    }

    override fun revokeKey(id: Long, at: Long) = sync {
        val k = keys[id]
        if (k != null && k.revokedAt == null) keys[id] = k.copy(revokedAt = at)
        Unit
    }

    override fun touchKey(id: Long, at: Long) = sync {
        val k = keys[id]
        if (k != null && (k.lastUsedAt == null || k.lastUsedAt <= at - McpStore.TOUCH_MIN_INTERVAL_MS)) {
            keys[id] = k.copy(lastUsedAt = at)
        }
        Unit
    }

    // -------------------------------------------------------------------- 审计

    override fun audit(
        actor: String,
        action: String,
        tool: String?,
        result: String,
        detail: String?,
        at: Long,
    ) = sync {
        val detail = AuditChain.sanitizeDetail(detail)
        // 链的上一环 = 最后一行；只有表为空时才用保留基（prune 后第一行）
        val prev = audits.lastOrNull()?.hash ?: settings[McpStore.AUDIT_BASE_KEY] ?: ""
        val row = AuditRow(
            id = nextAuditId++,
            actor = actor,
            action = action,
            tool = tool,
            result = result,
            detail = detail,
            prevHash = prev,
            hash = AuditChain.hash(prev, actor, action, tool, result, detail, at),
            createdAt = at,
        )
        audits.add(row)
        while (audits.size > McpStore.MAX_AUDIT_ROWS) {
            val dropped = audits.removeAt(0)
            settings[McpStore.AUDIT_BASE_KEY] = dropped.hash
        }
        Unit
    }

    override fun auditRows(
        limit: Int,
        actor: String?,
        action: String?,
        result: String?,
    ): List<AuditRow> = sync {
        audits.asReversed()
            .filter {
                (actor == null || it.actor == actor) &&
                    (action == null || it.action == action) &&
                    (result == null || it.result == result)
            }
            .take(limit.coerceAtLeast(0))
    }

    override fun auditChainOk(): Boolean = sync {
        var prev = settings[McpStore.AUDIT_BASE_KEY] ?: ""
        for (r in audits) {
            if (r.prevHash != prev) return@sync false
            val recomputed = AuditChain.hash(
                prev, r.actor, r.action, r.tool, r.result, r.detail, r.createdAt,
            )
            if (recomputed != r.hash) return@sync false
            prev = r.hash
        }
        true
    }

    // -------------------------------------------------------------------- 设置

    override fun setting(key: String): String? = sync { settings[key] }

    override fun setSetting(key: String, value: String?) = sync {
        if (value.isNullOrBlank()) settings.remove(key) else settings[key] = value
        Unit
    }

    /** 内存实现没有连接要放；保留接口形态，测试收尾可以统一调用。 */
    override fun close() = Unit

    // ---------------------------------------------------- 测试专用：破坏链

    /**
     * 把某行的 `detail` 改掉但**不动 hash**：模拟库被直改（或代码写坏），
     * `auditChainOk()` 必须返回 false。只给测试用，不在 [McpStore] 接口里。
     */
    fun tamperAuditDetail(id: Long, detail: String?) = sync {
        val i = audits.indexOfFirst { it.id == id }
        if (i >= 0) audits[i] = audits[i].copy(detail = detail)
        Unit
    }

    /**
     * 删掉**中间**某行（不动 `auditBaseHash`）：后继行的 `prev_hash` 指向不存在的 hash，
     * `auditChainOk()` 必须返回 false。这就是"手工 DELETE 审计行"的等价物。
     */
    fun dropAuditRow(id: Long) = sync {
        audits.removeAll { it.id == id }
        Unit
    }

    private inline fun <T> sync(block: () -> T): T = synchronized(lock, block)
}
