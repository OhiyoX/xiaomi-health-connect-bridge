package id.harness.hcbridge.auth

import java.time.ZoneId
import org.json.JSONObject

/**
 * 门：scope → 每一层工具策略 → 每一层时间窗，**任一层拒绝就拒绝**（fail closed）。
 *
 * 本层不碰网络、不碰 `android.*`，只在调用点做判定：
 * - `tools/list` 用 [visible]（只看工具名，不看参数）；
 * - `tools/call` 用 [check]（带参数与"现在"）。
 *
 * 拒绝原因用 [Reason] 的稳定串（审计/日志/管理页都对它），拒绝结果**不含**参数值或凭据。
 */
object ToolGate {

    /** 拒绝原因。字符串是稳定契约（审计列、JSON-RPC error message 都写它）。 */
    enum class Reason(val wire: String) {
        /** scope 层不放行（含未知工具、只给了 `ingest`）。 */
        SCOPE("scope"),

        /** 某一层策略不放行（含 `[]` 全禁止、参数不匹配）。 */
        POLICY("policy"),

        /** 某一层时间窗不放行。 */
        WINDOW("window"),

        /** 某一层策略是坏数据 ⇒ 拒绝（绝不退化成放行）。 */
        POLICY_CORRUPT("policy_corrupt"),

        /** 某一层时间窗是坏数据 ⇒ 拒绝。 */
        WINDOW_CORRUPT("window_corrupt"),
        ;

        override fun toString(): String = wire
    }

    /** `all ∩ allowed`；`tools/list` 的过滤就是它。 */
    fun visible(all: Set<String>, allowed: Set<String>): Set<String> {
        val out = LinkedHashSet<String>()
        for (tool in all) if (tool in allowed) out.add(tool)
        return out
    }

    /**
     * 实际生效的工具集合 = scope 授予的 ∩ 各层策略（按名字）。
     *
     * 任何一层策略是坏数据 ⇒ 空集（fail closed）：宁可让客户端看不到工具，
     * 也不能把"解析失败"当成"不限制"。
     */
    fun effectiveTools(scopes: Set<String>, policies: List<String?>): Set<String> {
        var out = ToolCatalog.toolsForScopes(scopes)
        for (policy in policies) {
            if (policy == null) continue          // 只有 null 才是"这层不限制"；空串一律当坏数据
            val names = try {
                ToolPolicy.names(policy) ?: continue
            } catch (t: Exception) {
                return emptySet()
            }
            out = out.filterTo(LinkedHashSet()) { it in names }
        }
        return out
    }

    /**
     * 调用判定；`null` = 放行。
     *
     * 顺序：scope → 策略（逐层）→ 时间窗（逐层）。scope 先判，所以越权调用永远报 `scope`，
     * 不会因为策略/时间窗恰好也坏掉而改变语义。
     */
    fun check(
        scopes: Set<String>,
        policies: List<String?>,
        windows: List<String?>,
        tool: String,
        args: JSONObject?,
        atMillis: Long,
        zone: ZoneId,
    ): String? = checkReason(scopes, policies, windows, tool, args, atMillis, zone)?.wire

    fun checkReason(
        scopes: Set<String>,
        policies: List<String?>,
        windows: List<String?>,
        tool: String,
        args: JSONObject?,
        atMillis: Long,
        zone: ZoneId,
    ): Reason? {
        if (tool !in ToolCatalog.toolsForScopes(scopes)) return Reason.SCOPE
        for (policy in policies) {
            try {
                if (!ToolPolicy.allowed(policy, tool, args)) return Reason.POLICY
            } catch (t: Exception) {
                return Reason.POLICY_CORRUPT
            }
        }
        for (window in windows) {
            try {
                if (!TimeWindow.active(window, atMillis, zone)) return Reason.WINDOW
            } catch (t: Exception) {
                return Reason.WINDOW_CORRUPT
            }
        }
        return null
    }
}
