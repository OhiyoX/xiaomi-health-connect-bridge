package id.harness.hcbridge.auth

import org.json.JSONArray

/**
 * MCP 存储的**行类型 + 接口**（spec §6，计划 Task 3）。
 *
 * 两类实现：
 * - [SqlMcpStore]：真实库（`mcp.db`，进程内单例见 `id.harness.hcbridge.Store`）；
 * - `InMemoryStore`（`app/src/test`）：JVM 单测 / OAuth 纯逻辑测试用的假 store，语义必须一致。
 *
 * 约定（实现者不要放宽）：
 * - 一切「消费」都是**单条 CAS**：`UPDATE … WHERE <主键> AND <源状态未变>`，受影响行数为 1 才算成功。
 *   先读后写两步判定的写法不叫 CAS，重复请求能过。
 * - token / code / client secret / 静态 key 只存 SHA-256（[Crypto.sha256Hex]），明文只在生成那一刻返回。
 * - 本文件**不得** import 任何 `android.*`（JVM 单测要用）。
 */
data class ClientRow(
    val clientId: String,
    val secretHash: String?,
    val name: String,
    val redirectUrisJson: String,
    val toolPolicyJson: String?,
    val windowJson: String?,
    /** [McpStatus.CLIENT_ACTIVE] | [McpStatus.CLIENT_PENDING] | [McpStatus.CLIENT_REVOKED]。 */
    val status: String,
    val createdAt: Long,
    val lastUsedAt: Long? = null,
    val revokedAt: Long? = null,
)

/** 待确认的 `/authorize` 请求（浏览器暂存在服务端，见计划决定 2）。 */
data class PendingRow(
    val id: String,
    val clientId: String,
    val redirectUri: String,
    val codeChallenge: String,
    val scopes: List<String>,
    val resource: String,
    val state: String?,
    val createdAt: Long,
    val expiresAt: Long,
    /** [McpStatus.PENDING] | [McpStatus.APPROVED] | [McpStatus.DENIED] | [McpStatus.CONSUMED]。 */
    val status: String,
)

/** 一次性授权码（5 分钟）。 */
data class CodeRow(
    val codeHash: String,
    val clientId: String,
    val redirectUri: String,
    val codeChallenge: String,
    val scopes: List<String>,
    val resource: String,
    val createdAt: Long,
    val expiresAt: Long,
    val consumedAt: Long? = null,
)

/** 访问令牌（[McpStatus.KIND_ACCESS]，1h）或刷新令牌（[McpStatus.KIND_REFRESH]，30d）。 */
data class TokenRow(
    val tokenHash: String,
    val prefix: String,
    val kind: String,
    val clientId: String,
    val familyId: String,
    val scopes: List<String>,
    val toolPolicyJson: String?,
    val windowJson: String?,
    val resource: String,
    val createdAt: Long,
    val expiresAt: Long,
    val revokedAt: Long? = null,
    val replacedByHash: String? = null,
    val lastUsedAt: Long? = null,
)

/** 静态 key（管理员在 app 内创建）。 */
data class KeyRow(
    val id: Long,
    val label: String,
    val hash: String,
    val toolPolicyJson: String?,
    val windowJson: String?,
    val createdAt: Long,
    val lastUsedAt: Long? = null,
    val revokedAt: Long? = null,
)

/** 审计链上的一行；`hash = SHA-256(prevHash 与各字段的长度前缀编码)`。 */
data class AuditRow(
    val id: Long,
    val actor: String,
    val action: String,
    val tool: String?,
    val result: String,
    val detail: String?,
    val prevHash: String,
    val hash: String,
    val createdAt: Long,
)

/** 状态/种类常量：库里存字符串，代码里只引用这里，避免各处手写拼错。 */
object McpStatus {
    const val CLIENT_ACTIVE = "active"
    const val CLIENT_PENDING = "pending"
    const val CLIENT_REVOKED = "revoked"

    const val PENDING = "pending"
    const val APPROVED = "approved"
    const val DENIED = "denied"
    const val CONSUMED = "consumed"

    const val KIND_ACCESS = "access"
    const val KIND_REFRESH = "refresh"
}

/**
 * 审计链的哈希计算。字段用**长度前缀**编码（`-1:` 表示 null），避免分隔符注入造成的
 * 两条不同记录同哈希；不经过 JSON（JVM 单测与 Android 端字节一致）。
 */
object AuditChain {

    fun hash(
        prevHash: String,
        actor: String,
        action: String,
        tool: String?,
        result: String,
        detail: String?,
        at: Long,
    ): String {
        val sb = StringBuilder()
        field(sb, prevHash)
        field(sb, actor)
        field(sb, action)
        field(sb, tool)
        field(sb, result)
        field(sb, detail)
        sb.append("$at")
        return Crypto.sha256Hex(sb.toString())
    }

    private fun field(sb: StringBuilder, v: String?) {
        if (v == null) sb.append("-1:") else sb.append(v.length).append(':').append(v)
    }

    /** `detail` 允许的最大长度（审计页展示用；超长/多行会撑破 UI 与日志面）。 */
    const val DETAIL_MAX = 200

    /**
     * 入库前清洗 `detail`：换行/回车折成空格、压掉首尾空白、超长截断。
     * 两种 store 都走这一个函数，保证同一份调用在 SQL 与内存里哈希一致。
     */
    fun sanitizeDetail(detail: String?): String? {
        val d = detail ?: return null
        val one = d.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim()
        if (one.isEmpty()) return null
        return if (one.length <= DETAIL_MAX) one else one.take(DETAIL_MAX)
    }
}

/** `scopes_json` 的编解码：**排序去重**后再存，幂等比较才有意义。 */
object ScopeJson {

    fun normalize(scopes: List<String>): List<String> = scopes.filter { it.isNotEmpty() }.distinct().sorted()

    fun encode(scopes: List<String>): String {
        val a = JSONArray()
        for (s in normalize(scopes)) a.put(s)
        return a.toString()
    }

    fun decode(json: String): List<String> {
        val a = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val out = ArrayList<String>(a.length())
        for (i in 0 until a.length()) {
            val s = a.optString(i, "")
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }
}

interface McpStore {

    // ------------------------------------------------------------------ 客户端

    fun putClient(c: ClientRow)
    fun client(clientId: String): ClientRow?
    fun clients(): List<ClientRow>
    fun clientCount(): Int

    /** `status: pending → active`（管理页「批准」）。 */
    fun approveClient(clientId: String, at: Long)

    /**
     * 级联：吊销 client 行 + 该 client 的所有令牌 + 未用授权码（`consumed_at = at`），
     * 并把该 client 仍处于 `pending`/`approved` 的待确认请求置 `denied`（同一事务）。
     */
    fun revokeClient(clientId: String, at: Long)

    /** 待批准客户端被拒：`status='pending' → revoked`（已 active 的不能被这样"拒"）。 */
    fun rejectClient(clientId: String, at: Long)

    /** 删除**已吊销**的 client 行（管理页手工操作；活跃行不删，避免悬空令牌）。 */
    fun deleteClient(clientId: String)

    // ------------------------------------------------------- 待确认请求 / 授权码

    fun putPending(p: PendingRow)
    fun pending(id: String): PendingRow?

    /**
     * 幂等复用：同 `client_id + code_challenge + redirect_uri + resource + scopes + state` 且未过期的
     * `pending` 行。
     *
     * `state` 必须进键（含 `null`）：它是客户端自己的 CSRF 值，复用一行却换成另一个 `state`
     * 会把令牌/授权码回给一个"没发起这次请求"的回调上下文，等于替客户端丢掉 CSRF 防护。
     * `state IS NULL` 与 `state = 'x'` 是两个不同的请求。
     */
    fun findPending(
        clientId: String,
        codeChallenge: String,
        redirectUri: String,
        resource: String,
        scopes: List<String>,
        state: String?,
        now: Long,
    ): PendingRow?

    /** 待人工确认且未过期的请求，按发起时间升序。 */
    fun pendingList(now: Long): List<PendingRow>

    /**
     * 单条 CAS：只有 `status='pending'` 才能 → `approved`/`denied`。
     * [scopes] 非 null 时在同一句里写回授予的 scope（批准时用）。
     */
    fun decidePending(id: String, status: String, now: Long, scopes: List<String>? = null): Boolean

    /** 单条 CAS：只有 `status='approved'` 且未过期 → `consumed`（发码前的最后一道闸）。 */
    fun consumePending(id: String, now: Long): Boolean

    /** 删除已过期的待确认请求，返回删除行数。 */
    fun deleteExpiredPending(now: Long): Int

    fun putCode(c: CodeRow)

    /** 单条 CAS 消费：未消费且未过期 → 置 `consumed_at`，返回旧行；否则 null。 */
    fun takeCode(codeHash: String, now: Long): CodeRow?

    // -------------------------------------------------------------------- 令牌

    fun putToken(t: TokenRow)
    fun tokenByHash(h: String): TokenRow?

    /** 更新 `last_used_at`；节流：距上次写入不足 [TOUCH_MIN_INTERVAL_MS] 时不动库。 */
    fun touchToken(h: String, at: Long)

    /**
     * 单条 CAS：`kind='refresh'` 且 `revoked_at IS NULL` → 置 `revoked_at`/`last_used_at`
     * （可选写 `replaced_by_hash`），返回旧行；已被吊销/不存在 → null。
     */
    fun consumeRefresh(hash: String, now: Long, replacedByHash: String? = null): TokenRow?

    fun revokeToken(h: String, at: Long)
    fun revokeFamily(familyId: String, at: Long)
    fun tokens(clientId: String? = null): List<TokenRow>

    // TODO(Task 6/7): `deleteExpiredTokens(now)` —— 清 `expires_at <= now` 的 access/refresh 行、
    // 过期/已用授权码与过期 pending。刷新是滑动续期（见 `OAuth.refresh`），没有绝对上限，
    // 所以过期行会持续累积；本轮不动存储层，留给管理页/prune 那条线一起做。

    // ---------------------------------------------------------------- 静态 key

    fun putKey(label: String, hash: String, policy: String?, window: String?, at: Long): Long
    fun keys(): List<KeyRow>

    /**
     * 改静态 key 的策略/时间窗（管理页「工具策略 / 时间窗」）：只动这两列，hash/label 不变，
     * 已吊销的行不更新。返回是否真的改了 1 行。
     *
     * 不能"先 revoke 再 putKey"：那样会换掉 hash，管理员手里的明文 key 直接作废。
     */
    fun updateKey(id: Long, policy: String?, window: String?, at: Long): Boolean
    fun revokeKey(id: Long, at: Long)

    /** 同 [touchToken] 的节流语义。 */
    fun touchKey(id: Long, at: Long)

    // -------------------------------------------------------------------- 审计

    /** 入链；`detail` 会经 [AuditChain.sanitizeDetail] 清洗（≤200 字符、无换行）。 */
    fun audit(actor: String, action: String, tool: String?, result: String, detail: String?, at: Long)
    fun auditRows(
        limit: Int = 200,
        actor: String? = null,
        action: String? = null,
        result: String? = null,
    ): List<AuditRow>

    /** 从 `settings("auditBaseHash")` 起重算整链是否自洽（prune 后仍应成立）。 */
    fun auditChainOk(): Boolean

    // -------------------------------------------------------------------- 设置

    fun setting(key: String): String?

    /** `null`/空白 ⇒ 删除该键（回到缺省值）。 */
    fun setSetting(key: String, value: String?)

    /** 释放底层连接（测试收尾/进程重建用）；内存实现是 no-op。 */
    fun close()

    companion object {
        /** 审计保留行数；超出后从最旧开始删并把被删最后一行的 `hash` 写进 [AUDIT_BASE_KEY]。 */
        const val MAX_AUDIT_ROWS = 2_000

        /** 审计链基：prune 后 `auditChainOk()` 从这里起算。 */
        const val AUDIT_BASE_KEY = "auditBaseHash"

        /** `last_used_at` 的写库节流窗口。 */
        const val TOUCH_MIN_INTERVAL_MS = 60_000L
    }
}
