package id.harness.hcbridge.auth

/**
 * 工具目录：`McpTools.json()` 的 36 个工具各自的 scope 归属，以及 scope → 工具集合。
 *
 * 分类规则（spec §8.1）：只读 = `health.read`（20 个），会写/会触发同步 = `health.write`（16 个）。
 * 新增工具默认归**写**（与 NAS 侧同一约定）：漏分类的老工具会被 [ToolCatalogTest] 挡住，
 * 不会悄悄以只读身份放出去。`ingest` 是保留 scope（只给静态 key 的写入入口），
 * 它**不**授予任何工具 —— 即使拼进 scope 集合也是空集。
 *
 * 本文件**不得** import 任何 `android.*`。
 */
object ToolCatalog {

    const val SCOPE_READ = "health.read"
    const val SCOPE_WRITE = "health.write"
    const val SCOPE_INGEST = "ingest"

    /** 36 个工具，按 [McpTools] 的声明顺序（= `tools/list` 的顺序）。 */
    val ALL_TOOLS: Set<String> = linkedSetOf(
        "hc_status",
        "hc_dump",
        "hc_sync_now",
        "hc_backfill",
        "get_connection_status",
        "get_profile",
        "get_daily_summary",
        "query_metric_series",
        "query_heart_rate",
        "query_sleep",
        "query_workouts",
        "hc_sleep_extras",
        "hc_pull_from_server",
        "hc_sleep_prune",
        "hc_sleep_force_import",
        "hc_sleep_shift",
        "hc_sleep_raw",
        "query_sleep_stages",
        "query_nightly_recovery",
        "query_body_composition",
        "query_body_measurements",
        "query_spo2",
        "hc_steps_breakdown",
        "get_data_coverage",
        "sync_data",
        "get_sync_history",
        "hc_export_csv",
        "export_logs",
        "hc_logs",
        "hc_activity_cleanup",
        "hc_weight_import",
        "hc_sleep_gap_check",
        "hc_sleep_cleanup",
        "hc_set_interval",
        "hc_upstream",
        "hc_kick",
    )

    /** 只读（`health.read`），读 Health Connect 或读本地状态/日志，不写数据、不触发同步。 */
    val READ_TOOLS: Set<String> = linkedSetOf(
        "hc_status",
        "hc_dump",
        "get_connection_status",
        "get_profile",
        "get_daily_summary",
        "query_metric_series",
        "query_heart_rate",
        "query_sleep",
        "query_workouts",
        "hc_sleep_raw",
        "query_sleep_stages",
        "query_nightly_recovery",
        "query_body_composition",
        "query_body_measurements",
        "query_spo2",
        "hc_steps_breakdown",
        "get_data_coverage",
        "get_sync_history",
        "hc_logs",
        "hc_upstream",
    )

    /**
     * 写 / 触发动作（`health.write`）。
     *
     * 判据是「会不会改 Health Connect、改设置、往磁盘写文件或起后台任务」，不是名字里的动词：
     * `hc_export_csv`、`export_logs` 也写 `/sdcard/Download/`，所以按写算。
     */
    val WRITE_TOOLS: Set<String> = linkedSetOf(
        "hc_sync_now",
        "hc_backfill",
        "hc_sleep_extras",
        "hc_pull_from_server",
        "hc_sleep_prune",
        "hc_sleep_force_import",
        "hc_sleep_shift",
        "sync_data",
        "hc_export_csv",
        "export_logs",
        "hc_activity_cleanup",
        "hc_weight_import",
        "hc_sleep_gap_check",
        "hc_sleep_cleanup",
        "hc_set_interval",
        "hc_kick",
    )

    /** 工具所属 scope；未知工具返回 `null`（调用方按 fail closed 处理）。 */
    fun scopeOf(tool: String): String? = when {
        tool in READ_TOOLS -> SCOPE_READ
        tool in WRITE_TOOLS -> SCOPE_WRITE
        else -> null
    }

    /**
     * scope 集合授予的工具（保持 [ALL_TOOLS] 顺序，便于 `tools/list` 稳定输出）。
     *
     * 只给 `health.read` 或 `health.write` 时是对应那一组；两个都给 = 全部 36 个；
     * `ingest` 与未知 scope 一律不授予工具。
     */
    fun toolsForScopes(scopes: Set<String>): Set<String> {
        val read = SCOPE_READ in scopes
        val write = SCOPE_WRITE in scopes
        if (!read && !write) return emptySet()
        val out = LinkedHashSet<String>()
        for (tool in ALL_TOOLS) {
            val scope = scopeOf(tool)
            if (scope == SCOPE_READ && read) out.add(tool)
            if (scope == SCOPE_WRITE && write) out.add(tool)
        }
        return out
    }
}
