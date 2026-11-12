package id.harness.hcbridge.auth

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一层工具策略（spec §8.2）。规则格式：
 *
 * ```
 * null           不额外限制（这层没有上限）；**只有 `null`** —— 空串/空白是坏数据，不是"不限制"
 * []             一个工具都不许（**不是**"不限制"；Review Focus 5）
 * ["hc_status"]  白名单
 * [{"tool":"hc_backfill","args":{"days":30}}]
 *                该工具且列出的每个参数**都存在且相等**（子集匹配；多给的参数不算违规）
 * ```
 *
 * 规则里的 `args` 值按**相等**比较（NAS 那份 spec §8.2 还允许"值是数组 = 任一命中"，
 * 这版 app 计划只要求子集相等，所以数组按"整个数组相等"处理；需要 any-of 时再扩，别默认放宽）。
 *
 * 非法 JSON / 形状不对 / 多余的键 ⇒ 抛 [IllegalArgumentException]（消息只含常量文本，不含数据）。
 * 未知键必须拒绝：`{"tool":"x","arg":{...}}`（少个 s）若被当成"忽略未知键"就等于**放宽**成"任意参数"。
 * 空串/空白同样是坏数据（只有 `null` 表示不限制），避免"存了个空值就放开"。
 * 绝不退化成"放行"：[ToolGate] 把它折成 `policy_corrupt` 拒绝。
 *
 * 本文件**不得** import 任何 `android.*`。
 */
object ToolPolicy {

    private class Rule(val tool: String, val args: List<Pair<String, Any?>>?)

    private val RULE_KEYS = setOf("tool", "args")

    /** 这一层是否允许 `tool`（调用时带参数一起判）。 */
    fun allowed(policyJson: String?, tool: String, args: JSONObject?): Boolean {
        val rules = parse(policyJson) ?: return true
        return rules.any { it.allows(tool, args) }
    }

    /**
     * 这一层**按名字**允许的工具集合（`tools/list` 只用名字，不看参数）；
     * `null` = 不限制。非法策略抛异常，调用方必须 fail closed。
     */
    fun names(policyJson: String?): Set<String>? {
        val rules = parse(policyJson) ?: return null
        val out = LinkedHashSet<String>()
        for (r in rules) out.add(r.tool)
        return out
    }

    /**
     * 归一化后的策略串（键按固定顺序/排序、空白去掉），可直接存库并做幂等比较；
     * `null` ⇒ `null`；空串/非法 ⇒ 抛 [IllegalArgumentException]。
     */
    fun canonical(policyJson: String?): String? {
        val rules = parse(policyJson) ?: return null
        return rules.joinToString(",", "[", "]") { ruleJson(it) }
    }

    // ------------------------------------------------------------- 内部

    private fun Rule.allows(tool: String, args: JSONObject?): Boolean {
        if (this.tool != tool) return false
        val need = this.args ?: return true
        if (args == null) return false
        return need.all { (k, v) -> args.has(k) && sameValue(args.opt(k), v) }
    }

    private fun ruleJson(rule: Rule): String {
        val need = rule.args ?: return JsonCanon.quote(rule.tool)
        val args = need.sortedBy { it.first }
            .joinToString(",", "{", "}") { JsonCanon.quote(it.first) + ":" + JsonCanon.value(it.second) }
        return "{" + JsonCanon.quote("tool") + ":" + JsonCanon.quote(rule.tool) + "," +
            JsonCanon.quote("args") + ":" + args + "}"
    }

    private fun parse(policyJson: String?): List<Rule>? {
        if (policyJson == null) return null
        val arr = JsonCanon.parse(policyJson)
        if (arr !is JSONArray) throw IllegalArgumentException("policy: not a JSON array")
        val out = ArrayList<Rule>(arr.length())
        for (i in 0 until arr.length()) {
            out.add(parseRule(arr.opt(i)))
        }
        return out
    }

    private fun parseRule(v: Any?): Rule = when {
        v is String -> if (v.isBlank()) throw IllegalArgumentException("policy: blank tool name") else Rule(v, null)
        v is JSONObject -> {
            for (k in v.keys()) if (k !in RULE_KEYS) throw IllegalArgumentException("policy: unknown rule key")
            val name = v.opt("tool")
            if (name !is String || name.isBlank()) throw IllegalArgumentException("policy: rule without tool")
            val rawArgs = v.opt("args")
            val args = when {
                rawArgs == null || rawArgs === JSONObject.NULL -> null
                rawArgs is JSONObject -> {
                    val pairs = ArrayList<Pair<String, Any?>>(rawArgs.length())
                    for (k in rawArgs.keys()) pairs.add(k to rawArgs.opt(k))
                    pairs.sortedBy { it.first }
                }
                else -> throw IllegalArgumentException("policy: args must be an object")
            }
            Rule(name, args)
        }
        else -> throw IllegalArgumentException("policy: rule must be a string or an object")
    }

    /** JSON 相等：数值按数值比（30 与 30.0 相同），其余按类型严格比，`null` 只等于 `null`。 */
    private fun sameValue(a: Any?, b: Any?): Boolean {
        val x = a ?: JSONObject.NULL
        val y = b ?: JSONObject.NULL
        if (x === JSONObject.NULL || y === JSONObject.NULL) return x === JSONObject.NULL && y === JSONObject.NULL
        if (x is Number && y is Number) return x.toDouble() == y.toDouble()
        if (x is String && y is String) return x == y
        if (x is Boolean && y is Boolean) return x == y
        if (x is JSONArray && y is JSONArray) return JsonCanon.value(x) == JsonCanon.value(y)
        if (x is JSONObject && y is JSONObject) return JsonCanon.value(x) == JsonCanon.value(y)
        return false
    }
}
