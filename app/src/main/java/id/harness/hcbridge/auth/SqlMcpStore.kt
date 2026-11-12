package id.harness.hcbridge.auth

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/**
 * [McpStore] 的 SQLite 实现（`mcp.db` v1，schema 见 [McpDb]）。
 *
 * 关键约束：
 * - **一切「消费」都是单条 CAS**（`UPDATE … WHERE 主键 AND 源状态未变`，受影响行数 1 才算成功）。
 *   不在 Kotlin 里「先读判状态再写」——那在并发/重放下会放行两次。
 * - 每个多步操作（吊销级联、审计入链 + prune）在一个事务里；单句 CAS 也在事务里跑，
 *   保证「读旧行 + 改状态」看到的是同一份数据。
 * - 库里没有明文凭据：token/code/secret/key 只有 SHA-256；审计 detail 由调用方保证不含凭据。
 * - 线程：SQLiteDatabase 自身串行化，本类不加锁；不要跨进程共享这个文件。
 */
class SqlMcpStore(private val helper: McpDb) : McpStore {

    private val db: SQLiteDatabase get() = helper.writableDatabase

    /** 关掉底层 helper；之后再用本实例会重新打开（测试收尾用）。 */
    override fun close() {
        helper.close()
    }

    // ------------------------------------------------------------------ 内部

    private fun <T> tx(block: (SQLiteDatabase) -> T): T {
        val d = db
        d.beginTransaction()
        return try {
            val out = block(d)
            d.setTransactionSuccessful()
            out
        } finally {
            d.endTransaction()
        }
    }

    private fun one(d: SQLiteDatabase, sql: String, args: Array<String>): Cursor? =
        d.rawQuery(sql, args).use { if (it.moveToFirst()) it else null }

    private fun longOrNull(c: Cursor, name: String): Long? {
        val i = c.getColumnIndexOrThrow(name)
        return if (c.isNull(i)) null else c.getLong(i)
    }

    private fun strOrNull(c: Cursor, name: String): String? {
        val i = c.getColumnIndexOrThrow(name)
        return if (c.isNull(i)) null else c.getString(i)
    }

    private fun readClient(c: Cursor) = ClientRow(
        clientId = c.getString(c.getColumnIndexOrThrow("client_id")),
        secretHash = strOrNull(c, "client_secret_hash"),
        name = strOrNull(c, "client_name") ?: "",
        redirectUrisJson = c.getString(c.getColumnIndexOrThrow("redirect_uris_json")),
        toolPolicyJson = strOrNull(c, "tool_policy_json"),
        windowJson = strOrNull(c, "window_json"),
        status = c.getString(c.getColumnIndexOrThrow("status")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        lastUsedAt = longOrNull(c, "last_used_at"),
        revokedAt = longOrNull(c, "revoked_at"),
    )

    private fun readPending(c: Cursor) = PendingRow(
        id = c.getString(c.getColumnIndexOrThrow("id")),
        clientId = c.getString(c.getColumnIndexOrThrow("client_id")),
        redirectUri = c.getString(c.getColumnIndexOrThrow("redirect_uri")),
        codeChallenge = c.getString(c.getColumnIndexOrThrow("code_challenge")),
        scopes = ScopeJson.decode(c.getString(c.getColumnIndexOrThrow("scopes_json"))),
        resource = c.getString(c.getColumnIndexOrThrow("resource")),
        state = strOrNull(c, "state"),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        expiresAt = c.getLong(c.getColumnIndexOrThrow("expires_at")),
        status = c.getString(c.getColumnIndexOrThrow("status")),
    )

    private fun readCode(c: Cursor) = CodeRow(
        codeHash = c.getString(c.getColumnIndexOrThrow("code_hash")),
        clientId = c.getString(c.getColumnIndexOrThrow("client_id")),
        redirectUri = c.getString(c.getColumnIndexOrThrow("redirect_uri")),
        codeChallenge = c.getString(c.getColumnIndexOrThrow("code_challenge")),
        scopes = ScopeJson.decode(c.getString(c.getColumnIndexOrThrow("scopes_json"))),
        resource = c.getString(c.getColumnIndexOrThrow("resource")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        expiresAt = c.getLong(c.getColumnIndexOrThrow("expires_at")),
        consumedAt = longOrNull(c, "consumed_at"),
    )

    private fun readToken(c: Cursor) = TokenRow(
        tokenHash = c.getString(c.getColumnIndexOrThrow("token_hash")),
        prefix = c.getString(c.getColumnIndexOrThrow("prefix")),
        kind = c.getString(c.getColumnIndexOrThrow("kind")),
        clientId = c.getString(c.getColumnIndexOrThrow("client_id")),
        familyId = c.getString(c.getColumnIndexOrThrow("family_id")),
        scopes = ScopeJson.decode(c.getString(c.getColumnIndexOrThrow("scopes_json"))),
        toolPolicyJson = strOrNull(c, "tool_policy_json"),
        windowJson = strOrNull(c, "window_json"),
        resource = c.getString(c.getColumnIndexOrThrow("resource")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        expiresAt = c.getLong(c.getColumnIndexOrThrow("expires_at")),
        revokedAt = longOrNull(c, "revoked_at"),
        replacedByHash = strOrNull(c, "replaced_by_hash"),
        lastUsedAt = longOrNull(c, "last_used_at"),
    )

    private fun readKey(c: Cursor) = KeyRow(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        label = c.getString(c.getColumnIndexOrThrow("label")),
        hash = c.getString(c.getColumnIndexOrThrow("hash")),
        toolPolicyJson = strOrNull(c, "tool_policy_json"),
        windowJson = strOrNull(c, "window_json"),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        lastUsedAt = longOrNull(c, "last_used_at"),
        revokedAt = longOrNull(c, "revoked_at"),
    )

    private fun readAudit(c: Cursor) = AuditRow(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        actor = c.getString(c.getColumnIndexOrThrow("actor")),
        action = c.getString(c.getColumnIndexOrThrow("action")),
        tool = strOrNull(c, "tool"),
        result = c.getString(c.getColumnIndexOrThrow("result")),
        detail = strOrNull(c, "detail"),
        prevHash = c.getString(c.getColumnIndexOrThrow("prev_hash")),
        hash = c.getString(c.getColumnIndexOrThrow("hash")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
    )

    // ------------------------------------------------------------------ 客户端

    override fun putClient(c: ClientRow) {
        val v = ContentValues().apply {
            put("client_id", c.clientId)
            put("client_secret_hash", c.secretHash)
            put("client_name", c.name)
            put("redirect_uris_json", c.redirectUrisJson)
            put("tool_policy_json", c.toolPolicyJson)
            put("window_json", c.windowJson)
            put("status", c.status)
            put("created_at", c.createdAt)
            put("last_used_at", c.lastUsedAt)
            put("revoked_at", c.revokedAt)
        }
        db.insertWithOnConflict("oauth_clients", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun client(clientId: String): ClientRow? = db.rawQuery(
        "SELECT * FROM oauth_clients WHERE client_id = ?", arrayOf(clientId),
    ).use { if (it.moveToFirst()) readClient(it) else null }

    override fun clients(): List<ClientRow> = db.rawQuery(
        "SELECT * FROM oauth_clients ORDER BY created_at ASC, client_id ASC", emptyArray(),
    ).use { c ->
        val out = ArrayList<ClientRow>(c.count)
        while (c.moveToNext()) out.add(readClient(c))
        out
    }

    override fun clientCount(): Int = db.rawQuery(
        "SELECT COUNT(*) FROM oauth_clients", emptyArray(),
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    override fun approveClient(clientId: String, at: Long) {
        // 只从 pending 变 active；已 revoked 的客户端不能被"批准"复活（要重新注册）。
        db.execSQL(
            "UPDATE oauth_clients SET status = ?, revoked_at = NULL WHERE client_id = ? AND status = ?",
            arrayOf(McpStatus.CLIENT_ACTIVE, clientId, McpStatus.CLIENT_PENDING),
        )
    }

    override fun revokeClient(clientId: String, at: Long) = tx { d ->
        d.execSQL(
            "UPDATE oauth_clients SET status = ?, revoked_at = ? WHERE client_id = ? AND revoked_at IS NULL",
            arrayOf(McpStatus.CLIENT_REVOKED, at, clientId),
        )
        d.execSQL(
            "UPDATE oauth_tokens SET revoked_at = ? WHERE client_id = ? AND revoked_at IS NULL",
            arrayOf(at, clientId),
        )
        // 未用授权码一并作废（consumed_at 即"这张码已死"）
        d.execSQL(
            "UPDATE oauth_codes SET consumed_at = ? WHERE client_id = ? AND consumed_at IS NULL",
            arrayOf(at, clientId),
        )
        // 待确认/已批准但还没换码的请求同样作废：否则吊销后仍能在同意页点"允许"再换码
        d.execSQL(
            "UPDATE oauth_pending SET status = ? WHERE client_id = ? AND status IN (?, ?)",
            arrayOf(McpStatus.DENIED, clientId, McpStatus.PENDING, McpStatus.APPROVED),
        )
    }

    override fun rejectClient(clientId: String, at: Long) {
        db.execSQL(
            "UPDATE oauth_clients SET status = ?, revoked_at = ? WHERE client_id = ? AND status = ?",
            arrayOf(McpStatus.CLIENT_REVOKED, at, clientId, McpStatus.CLIENT_PENDING),
        )
    }

    override fun deleteClient(clientId: String) {
        db.delete(
            "oauth_clients",
            "client_id = ? AND revoked_at IS NOT NULL",
            arrayOf(clientId),
        )
    }

    // ------------------------------------------------------- 待确认请求 / 授权码

    override fun putPending(p: PendingRow) {
        val v = ContentValues().apply {
            put("id", p.id)
            put("client_id", p.clientId)
            put("redirect_uri", p.redirectUri)
            put("code_challenge", p.codeChallenge)
            put("scopes_json", ScopeJson.encode(p.scopes))
            put("resource", p.resource)
            put("state", p.state)
            put("created_at", p.createdAt)
            put("expires_at", p.expiresAt)
            put("status", p.status)
        }
        db.insertWithOnConflict("oauth_pending", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun pending(id: String): PendingRow? = db.rawQuery(
        "SELECT * FROM oauth_pending WHERE id = ?", arrayOf(id),
    ).use { if (it.moveToFirst()) readPending(it) else null }

    override fun findPending(
        clientId: String,
        codeChallenge: String,
        redirectUri: String,
        resource: String,
        scopes: List<String>,
        state: String?,
        now: Long,
    ): PendingRow? {
        // `state` 进键：`IS NULL` 与 `= ?` 分成两句，避免把 null 绑进 selectionArgs（SQLite 里 `= NULL` 永不成立）
        val head = "SELECT * FROM oauth_pending WHERE client_id = ? AND code_challenge = ? AND redirect_uri = ?" +
            " AND resource = ? AND scopes_json = ? AND status = ? AND expires_at > ?"
        val tail = " ORDER BY created_at DESC LIMIT 1"
        val args = arrayListOf(
            clientId, codeChallenge, redirectUri, resource, ScopeJson.encode(scopes),
            McpStatus.PENDING, now.toString(),
        )
        val sql = if (state == null) {
            "$head AND state IS NULL$tail"
        } else {
            args.add(state)
            "$head AND state = ?$tail"
        }
        return db.rawQuery(sql, args.toTypedArray())
            .use { if (it.moveToFirst()) readPending(it) else null }
    }

    override fun pendingList(now: Long): List<PendingRow> = db.rawQuery(
        "SELECT * FROM oauth_pending WHERE status = ? AND expires_at > ? ORDER BY created_at ASC, id ASC",
        arrayOf(McpStatus.PENDING, now.toString()),
    ).use { c ->
        val out = ArrayList<PendingRow>(c.count)
        while (c.moveToNext()) out.add(readPending(c))
        out
    }

    override fun decidePending(id: String, status: String, now: Long, scopes: List<String>?): Boolean {
        require(status == McpStatus.APPROVED || status == McpStatus.DENIED) { "bad pending status" }
        // 单条 UPDATE：源态必须仍是 pending，否则 0 行受影响（重复批准/批准后再拒都失败）。
        val v = ContentValues().apply {
            put("status", status)
            if (scopes != null) put("scopes_json", ScopeJson.encode(scopes))
        }
        return db.update(
            "oauth_pending", v, "id = ? AND status = ?", arrayOf(id, McpStatus.PENDING),
        ) == 1
    }

    override fun consumePending(id: String, now: Long): Boolean {
        // 单条 UPDATE：只有 approved 且未过期才能 → consumed。
        val v = ContentValues().apply { put("status", McpStatus.CONSUMED) }
        return db.update(
            "oauth_pending",
            v,
            "id = ? AND status = ? AND expires_at > ?",
            arrayOf(id, McpStatus.APPROVED, now.toString()),
        ) == 1
    }

    override fun deleteExpiredPending(now: Long): Int = tx { d ->
        d.delete("oauth_pending", "expires_at <= ?", arrayOf(now.toString()))
    }

    override fun putCode(c: CodeRow) {
        val v = ContentValues().apply {
            put("code_hash", c.codeHash)
            put("client_id", c.clientId)
            put("redirect_uri", c.redirectUri)
            put("code_challenge", c.codeChallenge)
            put("scopes_json", ScopeJson.encode(c.scopes))
            put("resource", c.resource)
            put("created_at", c.createdAt)
            put("expires_at", c.expiresAt)
            put("consumed_at", c.consumedAt)
        }
        db.insertWithOnConflict("oauth_codes", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun takeCode(codeHash: String, now: Long): CodeRow? = tx { d ->
        val row = one(d, "SELECT * FROM oauth_codes WHERE code_hash = ?", arrayOf(codeHash))
            ?.use { readCode(it) } ?: return@tx null
        val v = ContentValues().apply { put("consumed_at", now) }
        val n = d.update(
            "oauth_codes",
            v,
            "code_hash = ? AND consumed_at IS NULL AND expires_at > ?",
            arrayOf(codeHash, now.toString()),
        )
        if (n == 1) row else null
    }

    // -------------------------------------------------------------------- 令牌

    override fun putToken(t: TokenRow) {
        val v = ContentValues().apply {
            put("token_hash", t.tokenHash)
            put("prefix", t.prefix)
            put("kind", t.kind)
            put("client_id", t.clientId)
            put("family_id", t.familyId)
            put("scopes_json", ScopeJson.encode(t.scopes))
            put("tool_policy_json", t.toolPolicyJson)
            put("window_json", t.windowJson)
            put("resource", t.resource)
            put("created_at", t.createdAt)
            put("expires_at", t.expiresAt)
            put("revoked_at", t.revokedAt)
            put("replaced_by_hash", t.replacedByHash)
            put("last_used_at", t.lastUsedAt)
        }
        db.insertWithOnConflict("oauth_tokens", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun tokenByHash(h: String): TokenRow? = db.rawQuery(
        "SELECT * FROM oauth_tokens WHERE token_hash = ?", arrayOf(h),
    ).use { if (it.moveToFirst()) readToken(it) else null }

    override fun touchToken(h: String, at: Long) {
        val v = ContentValues().apply { put("last_used_at", at) }
        db.update(
            "oauth_tokens",
            v,
            "token_hash = ? AND (last_used_at IS NULL OR last_used_at <= ?)",
            arrayOf(h, (at - McpStore.TOUCH_MIN_INTERVAL_MS).toString()),
        )
    }

    override fun consumeRefresh(hash: String, now: Long, replacedByHash: String?): TokenRow? = tx { d ->
        val row = one(d, "SELECT * FROM oauth_tokens WHERE token_hash = ?", arrayOf(hash))
            ?.use { readToken(it) } ?: return@tx null
        if (row.kind != McpStatus.KIND_REFRESH) return@tx null
        val v = ContentValues().apply {
            put("revoked_at", now)
            put("last_used_at", now)
            if (replacedByHash != null) put("replaced_by_hash", replacedByHash)
        }
        val n = d.update(
            "oauth_tokens",
            v,
            "token_hash = ? AND revoked_at IS NULL AND kind = ?",
            arrayOf(hash, McpStatus.KIND_REFRESH),
        )
        if (n == 1) row else null
    }

    override fun revokeToken(h: String, at: Long) {
        db.execSQL(
            "UPDATE oauth_tokens SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL",
            arrayOf(at, h),
        )
    }

    override fun revokeFamily(familyId: String, at: Long) {
        db.execSQL(
            "UPDATE oauth_tokens SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL",
            arrayOf(at, familyId),
        )
    }

    override fun tokens(clientId: String?): List<TokenRow> {
        val sql = if (clientId == null) {
            "SELECT * FROM oauth_tokens ORDER BY created_at ASC, token_hash ASC"
        } else {
            "SELECT * FROM oauth_tokens WHERE client_id = ? ORDER BY created_at ASC, token_hash ASC"
        }
        val args = if (clientId == null) emptyArray() else arrayOf(clientId)
        return db.rawQuery(sql, args).use { c ->
            val out = ArrayList<TokenRow>(c.count)
            while (c.moveToNext()) out.add(readToken(c))
            out
        }
    }

    // ---------------------------------------------------------------- 静态 key

    override fun putKey(label: String, hash: String, policy: String?, window: String?, at: Long): Long {
        val v = ContentValues().apply {
            put("label", label)
            put("hash", hash)
            put("tool_policy_json", policy)
            put("window_json", window)
            put("created_at", at)
        }
        val id = db.insertOrThrow("static_keys", null, v)
        check(id != -1L) { "static key insert failed" }
        return id
    }

    override fun keys(): List<KeyRow> = db.rawQuery(
        "SELECT * FROM static_keys ORDER BY id ASC", emptyArray(),
    ).use { c ->
        val out = ArrayList<KeyRow>(c.count)
        while (c.moveToNext()) out.add(readKey(c))
        out
    }

    override fun updateKey(id: Long, policy: String?, window: String?, at: Long): Boolean {
        val v = ContentValues().apply {
            put("tool_policy_json", policy)
            put("window_json", window)
        }
        // at 只用于审计语义（行上没有 updated_at 列）：保持列集合最小，不动 created_at/last_used_at
        return db.update("static_keys", v, "id = ? AND revoked_at IS NULL", arrayOf(id.toString())) == 1
    }

    override fun revokeKey(id: Long, at: Long) {
        db.execSQL(
            "UPDATE static_keys SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL",
            arrayOf(at, id),
        )
    }

    override fun touchKey(id: Long, at: Long) {
        val v = ContentValues().apply { put("last_used_at", at) }
        db.update(
            "static_keys",
            v,
            "id = ? AND (last_used_at IS NULL OR last_used_at <= ?)",
            arrayOf(id.toString(), (at - McpStore.TOUCH_MIN_INTERVAL_MS).toString()),
        )
    }

    // -------------------------------------------------------------------- 审计

    override fun audit(
        actor: String,
        action: String,
        tool: String?,
        result: String,
        detail: String?,
        at: Long,
    ) = tx { d ->
        val detail = AuditChain.sanitizeDetail(detail)
        // 链的上一环 = 表里最后一行；只有表为空时才用保留基（prune 后第一行从这里起算）
        val last = d.rawQuery("SELECT hash FROM audit_log ORDER BY id DESC LIMIT 1", emptyArray()).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        val prev = last ?: settingIn(d, McpStore.AUDIT_BASE_KEY) ?: ""
        val hash = AuditChain.hash(prev, actor, action, tool, result, detail, at)
        val v = ContentValues().apply {
            put("actor", actor)
            put("action", action)
            put("tool", tool)
            put("result", result)
            put("detail", detail)
            put("prev_hash", prev)
            put("hash", hash)
            put("created_at", at)
        }
        d.insertOrThrow("audit_log", null, v)
        pruneAudit(d)
    }

    /** 保留 [McpStore.MAX_AUDIT_ROWS] 行：先把「被删的最后一行」的 hash 写成链基，再删。 */
    private fun pruneAudit(d: SQLiteDatabase) {
        val count = d.rawQuery("SELECT COUNT(*) FROM audit_log", emptyArray()).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
        val drop = count - McpStore.MAX_AUDIT_ROWS
        if (drop <= 0) return
        val boundary = d.rawQuery(
            "SELECT id, hash FROM audit_log ORDER BY id ASC LIMIT 1 OFFSET ?",
            arrayOf((drop - 1).toString()),
        ).use { c ->
            if (c.moveToFirst()) c.getLong(0) to c.getString(1) else null
        } ?: return
        putSettingIn(d, McpStore.AUDIT_BASE_KEY, boundary.second)
        d.delete("audit_log", "id <= ?", arrayOf(boundary.first.toString()))
    }

    override fun auditRows(
        limit: Int,
        actor: String?,
        action: String?,
        result: String?,
    ): List<AuditRow> {
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        if (actor != null) { where.add("actor = ?"); args.add(actor) }
        if (action != null) { where.add("action = ?"); args.add(action) }
        if (result != null) { where.add("result = ?"); args.add(result) }
        val sql = buildString {
            append("SELECT * FROM audit_log")
            if (where.isNotEmpty()) append(" WHERE ").append(where.joinToString(" AND "))
            append(" ORDER BY id DESC LIMIT ?")
        }
        args.add(limit.coerceAtLeast(0).toString())
        return db.rawQuery(sql, args.toTypedArray()).use { c ->
            val out = ArrayList<AuditRow>(c.count)
            while (c.moveToNext()) out.add(readAudit(c))
            out
        }
    }

    /** 读事务里整链重算：并发写审计时不会读到"半条链"（WAL 下也能拿到一致快照）。 */
    override fun auditChainOk(): Boolean = tx { d ->
        d.rawQuery("SELECT * FROM audit_log ORDER BY id ASC", emptyArray()).use { c ->
            var prev = settingIn(d, McpStore.AUDIT_BASE_KEY) ?: ""
            while (c.moveToNext()) {
                val row = readAudit(c)
                if (row.prevHash != prev) return@use false
                val recomputed = AuditChain.hash(
                    prev, row.actor, row.action, row.tool, row.result, row.detail, row.createdAt,
                )
                if (recomputed != row.hash) return@use false
                prev = row.hash
            }
            true
        }
    }

    // -------------------------------------------------------------------- 设置

    override fun setting(key: String): String? = settingIn(db, key)

    override fun setSetting(key: String, value: String?) {
        putSettingIn(db, key, value)
    }

    private fun settingIn(d: SQLiteDatabase, key: String): String? = d.rawQuery(
        "SELECT value FROM settings WHERE key = ?", arrayOf(key),
    ).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }

    private fun putSettingIn(d: SQLiteDatabase, key: String, value: String?) {
        if (value.isNullOrBlank()) {
            d.delete("settings", "key = ?", arrayOf(key))
            return
        }
        val v = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        d.insertWithOnConflict("settings", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }
}
