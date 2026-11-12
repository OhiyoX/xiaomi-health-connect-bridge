package id.harness.hcbridge

import android.content.Context
import id.harness.hcbridge.auth.McpDb
import id.harness.hcbridge.auth.McpStore
import id.harness.hcbridge.auth.SqlMcpStore

/**
 * 全进程**唯一**的 [McpStore]（计划「设计决定 1」）。
 *
 * 为什么必须是单例：`McpService`、管理页、同意页、`McpConfig` 都要读同一份客户端/令牌/审计；
 * 各自 `McpDb(...)` 会开出多条连接与多个 helper，WAL 下"看见旧状态"的窗口足够让 CAS 形同虚设。
 * 所以：Activity/Service/Worker 一律 [Store.of]，不要自己 new 新的 helper。
 *
 * 测试注入：[overrideForTest] 传一个临时库的 store（androidTest 用 `SqlMcpStore(McpDb(ctx, "t-<uuid>.db"))`；
 * JVM 单测不需要它，直接用 `InMemoryStore`）。传 `null` 复位 —— 下次 [of] 重新打开真实 `mcp.db`。
 */
object Store {

    @Volatile
    private var instance: McpStore? = null

    fun of(c: Context): McpStore {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: SqlMcpStore(McpDb(c.applicationContext)).also { instance = it }
        }
    }

    fun overrideForTest(s: McpStore? = null) {
        synchronized(this) { instance = s }
    }
}
