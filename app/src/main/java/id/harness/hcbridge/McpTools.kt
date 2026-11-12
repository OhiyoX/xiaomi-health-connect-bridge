package id.harness.hcbridge

import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 工具目录的**唯一**定义处（36 个：20 只读 + 16 写），与 `McpService.tools/list` 逐字一致。
 *
 * 之前这份定义藏在 [McpService] 的私有 `toolsJson()` 里；鉴权/策略（`auth.ToolCatalog`）要在纯 JVM
 * 单测里核对目录，所以搬到这里并保持 `object`：本文件**不得** import 任何 `android.*`，
 * 也不实例化 Service（`org.json` 在 Android 上是平台类，单测里由 `testImplementation 'org.json:json'` 提供）。
 *
 * 改工具清单时：这里加/删，`auth.ToolCatalog` 同步分类，`ToolCatalogTest` 会挡住漂移。
 */
object McpTools {

    private fun tool(name: String, desc: String, props: JSONObject = JSONObject()): JSONObject =
        JSONObject()
            .put("name", name)
            .put("description", desc)
            .put("inputSchema", JSONObject()
                .put("type", "object")
                .put("properties", props)
                .put("additionalProperties", false))

    /** 每次调用重建（`tools/list` 的结果会被序列化，不共享可变对象）。 */
    private fun build(): JSONArray = JSONArray()
        .put(tool("hc_status", "HC Bridge 当前状态：HC 权限、小米账号、上次写入时间、同步间隔、最近错误。"))
        .put(tool("hc_dump", "读取 Health Connect 现状（7 天汇总/每日步数/睡眠/心率 30 天与 365 天/血氧/体重），返回 JSON。"))
        .put(tool("hc_sync_now", "立刻从小米健康云同步一次心率到 Health Connect（后台，无窗口）。"))
        .put(tool("hc_backfill", "从小米云回补历史到 Health Connect（心率/睡眠/血氧/步数/距离/活动卡路里/体重/体脂；幂等，可重跑）。",
            JSONObject().put("days", JSONObject().put("type", "integer")
            .put("description", "回补多少天，默认 365，最大 1825（云端实测到 2021-10）"))))
        .put(tool("get_connection_status", "同 hc_status：HC/账号/权限/上次写入/间隔。"))
        .put(tool("get_profile", "账号与设备概况（小米账号 userId、HC 数据来源、平台）。"))
        .put(tool("get_daily_summary", "某一天的汇总（步数/距离/卡路里/心率/睡眠）。",
            JSONObject().put("date", JSONObject().put("type", "string").put("description", "YYYY-MM-DD，默认今天"))))
        .put(tool("query_metric_series", "指标序列（按天，可周/月聚合）。",
            JSONObject()
            .put("metric", JSONObject().put("type", "string")
            .put("enum", JSONArray(listOf("steps", "distance_km", "distance_m", "active_kcal", "total_kcal", "heart_rate_avg", "weight_kg", "spo2"))))
            .put("start_date", JSONObject().put("type", "string"))
            .put("end_date", JSONObject().put("type", "string"))
            .put("granularity", JSONObject().put("type", "string").put("enum", JSONArray(listOf("day", "week", "month"))))))
        .put(tool("query_heart_rate", "心率采样（默认最近 1 天）。",
            JSONObject()
            .put("start_date", JSONObject().put("type", "string"))
            .put("end_date", JSONObject().put("type", "string"))
            .put("limit", JSONObject().put("type", "integer").put("description", "最多返回多少条采样，默认 500"))))
        .put(tool("query_sleep", "睡眠会话（默认已把「同一段被写两遍」收敛成一条；小米自己一晚多条也会被收敛）。",
            JSONObject().put("start_date", JSONObject().put("type", "string"))
            .put("end_date", JSONObject().put("type", "string"))
            .put("include_naps", JSONObject().put("type", "boolean"))
            .put("dedupe", JSONObject().put("type", "boolean")
            .put("description", "默认 true：重叠>50% 只留最长那条；要原始数据传 false"))))
        .put(tool("query_workouts", "运动记录。",
            JSONObject().put("start_date", JSONObject().put("type", "string"))
            .put("end_date", JSONObject().put("type", "string"))
            .put("min_duration", JSONObject().put("type", "integer").put("description", "最短分钟数"))))
        .put(tool("hc_sleep_extras", "把夜间呼吸率（avg_breath）与 HRV（avg_hrv）补进 Health Connect。",
            JSONObject().put("days", JSONObject().put("type", "integer")
            .put("description", "回溯多少天，默认 365，最大 1825"))))
        .put(tool("hc_pull_from_server", "从 NAS 的 health MCP 拉一段数据写进 HC（心率/血氧/睡眠/步数）。小米云断了或历史有洞时用；主设备也能用。",
            JSONObject().put("start_date", JSONObject().put("type", "string"))
            .put("end_date", JSONObject().put("type", "string"))))
        .put(tool("hc_sleep_prune", "删掉本应用自己写重叠的睡眠重复项（同段只留一条，留更长/更新的）。",
            JSONObject().put("start_date", JSONObject().put("type", "string"))
            .put("end_date", JSONObject().put("type", "string"))))
        .put(tool("hc_sleep_force_import", "从小米云**无条件**把某几天的睡眠写进 HC（不过去重规则，带分期）—— 用于恢复被误删的一晚。",
            JSONObject().put("start_date", JSONObject().put("type", "string"))
            .put("end_date", JSONObject().put("type", "string"))))
        .put(tool("hc_sleep_shift", "把本应用写的睡眠会话整体平移 N 秒（删旧写新），换取新会话身份 —— 用于让 Google Health/Fitbit 重新导入曾经“没分期”的那几晚。",
            JSONObject().put("start_date", JSONObject().put("type", "string"))
            .put("end_date", JSONObject().put("type", "string"))
            .put("seconds", JSONObject().put("type", "integer").put("description", "平移秒数，默认 1"))))
        .put(tool("hc_sleep_raw", "逐条睡眠记录的原样元数据 + 完整分期段清单（title/notes/recordingMethod/device/clientRecordId + 每个 stage 段），排查“为何同是带分期却显示不一样”。",
            JSONObject().put("start_date", JSONObject().put("type", "string"))
            .put("end_date", JSONObject().put("type", "string"))))
        .put(tool("query_sleep_stages", "睡眠分期（HC 自带 stages）：深睡/浅睡/REM/清醒分钟 + 效率。"
            + "现在小米和本桥写的记录都带分期；同一晚多条先按区间去重。",
            JSONObject().put("start_date", JSONObject().put("type", "string"))
            .put("end_date", JSONObject().put("type", "string"))))
        .put(tool("query_nightly_recovery", "夜间生理：呼吸率与 HRV（RMSSD）最近值 + 近 N 天序列。小米 2024-11 之后才有呼吸率、2026-07 之后才有 HRV，缺就是缺。",
            JSONObject().put("days", JSONObject().put("type", "integer"))))
        .put(tool("query_body_composition", "体成分：体重 + 体脂率 + BMI（由身高推算），按天合并成时间线，带统计量。"
            + "体重是稀疏数据（5 年约 28 次），默认看最近 365 天，要看全历史请显式给 start_date/end_date。",
            JSONObject().put("start_date", JSONObject().put("type", "string"))
            .put("end_date", JSONObject().put("type", "string"))
            .put("source", JSONObject().put("type", "string")
            .put("description", "只看某个来源，如 id.harness.hcbridge / com.google.android.apps.fitness"))))
        .put(tool("query_body_measurements", "体重记录（旧接口，只体重；体脂/BMI 用 query_body_composition）。",
            JSONObject().put("start_date", JSONObject().put("type", "string"))
            .put("end_date", JSONObject().put("type", "string"))))
        .put(tool("query_spo2", "血氧采样。",
            JSONObject().put("start_date", JSONObject().put("type", "string"))
            .put("end_date", JSONObject().put("type", "string"))
            .put("limit", JSONObject().put("type", "integer"))))
        .put(tool("hc_steps_breakdown", "某天步数按来源拆分（诊断重复计算：小米/Google Fit 会相加）。",
            JSONObject().put("date", JSONObject().put("type", "string")
            .put("description", "YYYY-MM-DD，默认今天"))))
        .put(tool("get_data_coverage", "各数据类型在 HC 里的覆盖范围与条数。"))
        .put(tool("sync_data", "从云端同步（缺省回补最近 2 天；给 days 则回补 N 天）。",
            JSONObject().put("days", JSONObject().put("type", "integer"))))
        .put(tool("get_sync_history", "最近 10 次同步结果（时间/结果/采样/窗口）。"))
        .put(tool("hc_export_csv", "把任意区间导出成 CSV 到 /sdcard/Download/hc-bridge/（便于喂 Excel/别的 app）。",
            JSONObject()
            .put("types", JSONObject().put("type", "array")
            .put("items", JSONObject().put("type", "string")
            .put("enum", JSONArray(listOf("heart_rate", "steps", "sleep", "workouts", "spo2", "weight"))))
            .put("description", "默认 heart_rate,steps,sleep"))
            .put("start_date", JSONObject().put("type", "string").put("description", "YYYY-MM-DD"))
            .put("end_date", JSONObject().put("type", "string").put("description", "YYYY-MM-DD"))))
        .put(tool("export_logs", "把 app 内日志 + 最近同步历史导出成 /sdcard/Download/hc-bridge/logs-*.txt，返回文件名。"))
        .put(tool("hc_logs", "读 app 内日志（最近 N 行，默认 80）。",
            JSONObject().put("lines", JSONObject().put("type", "integer").put("description", "行数，默认 80"))))
        .put(tool("hc_activity_cleanup", "清理步数/活动卡路里的历史重复（小米写过的那天删掉我们那份）。",
            JSONObject().put("days", JSONObject().put("type", "integer")
            .put("description", "回溯多少天，默认 365"))))
        .put(tool("hc_weight_import", "把云端体重/体脂补进 Health Connect（体称数据稀疏，5 年约 28 条；同 ts±60s 已有则跳过）。",
            JSONObject().put("days", JSONObject().put("type", "integer")
            .put("description", "回溯多少天，默认 1825"))))
        .put(tool("hc_sleep_gap_check", "睡眠空夜体检：扫 HC 最近 N 天，某晚一条记录都没有就从云端补（保证删掉重复后仍完整）。",
            JSONObject().put("days", JSONObject().put("type", "integer")
            .put("description", "回溯多少天，默认 30"))))
        .put(tool("hc_sleep_cleanup", "清理历史重复睡眠：删掉本应用写的、与别的来源（如 com.mi.health）重叠的睡眠记录。",
            JSONObject().put("days", JSONObject().put("type", "integer")
            .put("description", "回溯多少天，默认 365，最大 1095"))))
        .put(tool("hc_set_interval", "设置后台同步间隔（分钟，最小 15）。",
            JSONObject().put("minutes", JSONObject().put("type", "integer")
            .put("description", "15/30/60 之类"))))
        .put(tool("hc_upstream", "上游（小米运动健康 → 小米云）新不新鲜：上一次判定、哪些指标过期、催过几次。"
            + "小米没同步时桥会主动催它（bind 设备服务 / 发屏幕变化广播），催完再拉云端。"))
        .put(tool("hc_kick", "立刻主动去催一次小米运动健康同步（不等判定），然后补拉云端到 HC。后台执行，3 秒内返回。"))

    /** `tools/list` 的 `tools` 数组。 */
    fun json(): JSONArray = build()

    /** 按声明顺序取 `name`；给目录/策略对账用。 */
    fun names(): Set<String> {
        val arr = build()
        val out = LinkedHashSet<String>(arr.length())
        for (i in 0 until arr.length()) out.add(arr.getJSONObject(i).getString("name"))
        return out
    }
}
