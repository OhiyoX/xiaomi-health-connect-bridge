package id.harness.hcbridge.auth

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * `mcp.db` 的 schema（v1）。平台 `SQLiteOpenHelper`，**不用** Room（不引新依赖，见计划「设计决定 1」）。
 *
 * - 文件名刻意与健康数据库不同（健康数据只在 Health Connect，本库只放 MCP 的客户端/令牌/审计）：
 *   打开 `mcp.db` 永远不会碰健康数据；没有旧库 ⇒ 全新 `onCreate`（本计划之前没有鉴权库）。
 * - WAL 打开（[onConfigure]）：读并发不被审计写阻塞，进程被杀也不丢已提交审计。
 * - 未来版本加列/加表时改 [VERSION] 并在 [onUpgrade] 里写迁移；不要靠 `onCreate` 兜底老库。
 */
class McpDb(context: Context, name: String = DB_NAME) :
    SQLiteOpenHelper(context.applicationContext, name, null, VERSION) {

    /** WAL 打开成功过就不再重复探测（`enableWriteAheadLogging()` 每次都会问一遍底层）。 */
    private var walOk = false

    override fun onConfigure(db: SQLiteDatabase) {
        val ok = db.enableWriteAheadLogging()
        if (ok) {
            walOk = true
        } else if (!walOk) {
            // 只告警一次：WAL 不可用只是并发读会等审计写，不影响正确性（CAS 在事务里）。
            android.util.Log.w("HCBridge", "mcp.db: WAL 未能开启，退化为单连接串行")
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        for (sql in SCHEMA) db.execSQL(sql)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 是首版：不存在更老的库。后续版本在这里按 oldVersion 逐级迁移，绝不 DROP 用户数据。
    }

    companion object {
        const val DB_NAME = "mcp.db"

        /** schema 版本；改 schema 必须同时改这里与 [onUpgrade]。 */
        const val VERSION = 1

        /**
         * 建表/索引语句（spec §6；`sessions` 不实现，见计划决定 2；`oauth_clients` 多一列 `status`）。
         * 顺序即执行顺序；全部 `IF NOT EXISTS` 以外语义的 DDL 只在 `onCreate` 里跑一次。
         */
        private val SCHEMA: List<String> = listOf(
            """
            CREATE TABLE oauth_clients(
              client_id TEXT PRIMARY KEY,
              client_secret_hash TEXT,
              client_name TEXT,
              redirect_uris_json TEXT NOT NULL,
              tool_policy_json TEXT,
              window_json TEXT,
              status TEXT NOT NULL DEFAULT 'active',
              created_at INTEGER NOT NULL,
              last_used_at INTEGER,
              revoked_at INTEGER
            )
            """.trimIndent(),
            """
            CREATE TABLE oauth_pending(
              id TEXT PRIMARY KEY,
              client_id TEXT NOT NULL,
              redirect_uri TEXT NOT NULL,
              code_challenge TEXT NOT NULL,
              scopes_json TEXT NOT NULL,
              resource TEXT NOT NULL,
              state TEXT,
              created_at INTEGER NOT NULL,
              expires_at INTEGER NOT NULL,
              status TEXT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE oauth_codes(
              code_hash TEXT PRIMARY KEY,
              client_id TEXT NOT NULL,
              redirect_uri TEXT NOT NULL,
              code_challenge TEXT NOT NULL,
              scopes_json TEXT NOT NULL,
              resource TEXT NOT NULL,
              created_at INTEGER NOT NULL,
              expires_at INTEGER NOT NULL,
              consumed_at INTEGER
            )
            """.trimIndent(),
            """
            CREATE TABLE oauth_tokens(
              token_hash TEXT PRIMARY KEY,
              prefix TEXT NOT NULL,
              kind TEXT NOT NULL,
              client_id TEXT NOT NULL,
              family_id TEXT NOT NULL,
              scopes_json TEXT NOT NULL,
              tool_policy_json TEXT,
              window_json TEXT,
              resource TEXT NOT NULL,
              created_at INTEGER NOT NULL,
              expires_at INTEGER NOT NULL,
              revoked_at INTEGER,
              replaced_by_hash TEXT,
              last_used_at INTEGER
            )
            """.trimIndent(),
            """
            CREATE TABLE static_keys(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              label TEXT NOT NULL,
              hash TEXT NOT NULL UNIQUE,
              tool_policy_json TEXT,
              window_json TEXT,
              created_at INTEGER NOT NULL,
              last_used_at INTEGER,
              revoked_at INTEGER
            )
            """.trimIndent(),
            """
            CREATE TABLE audit_log(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              actor TEXT NOT NULL,
              action TEXT NOT NULL,
              tool TEXT,
              result TEXT NOT NULL,
              detail TEXT,
              prev_hash TEXT NOT NULL,
              hash TEXT NOT NULL,
              created_at INTEGER NOT NULL
            )
            """.trimIndent(),
            "CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT)",
            "CREATE INDEX idx_tokens_client ON oauth_tokens(client_id)",
            "CREATE INDEX idx_tokens_family ON oauth_tokens(family_id)",
            "CREATE INDEX idx_pending_client ON oauth_pending(client_id)",
            "CREATE INDEX idx_audit_created ON audit_log(created_at)",
        )
    }
}
