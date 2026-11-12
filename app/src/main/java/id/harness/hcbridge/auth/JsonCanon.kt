package id.harness.hcbridge.auth

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 把 JSON 值写成**稳定**字符串（对象键排序），给 `canonical` 归一化用。
 *
 * 为什么不用 `JSONObject.toString()`：`org.json` 用 `HashMap` 存键，同一份语义的两次写入
 * 可能得到不同字节序，导致「同一策略存两遍」看起来不同、去重与幂等断言失效。
 *
 * 本文件**不得** import 任何 `android.*`。
 */
internal object JsonCanon {

    fun quote(s: String): String = JSONObject.quote(s)

    /**
     * 严格解析：`org.json` 的 `JSONObject(String)`/`JSONArray(String)` **不检查输入结尾**，
     * `["hc_status"] junk` 会被当合法（等于放宽策略）⇒ 这里要求整个输入都被消费。
     *
     * 任何解析问题 ⇒ 抛 [IllegalArgumentException]（调用方折成 `policy_corrupt` / `window_corrupt`）。
     */
    fun parse(json: String): Any? {
        val t = JSONTokener(json)
        val v = try {
            t.nextValue()
        } catch (t2: Exception) {
            throw IllegalArgumentException("json: not parseable")
        }
        val rest = try {
            t.nextClean()
        } catch (t2: Exception) {
            throw IllegalArgumentException("json: not parseable")
        }
        if (rest != '\u0000') throw IllegalArgumentException("json: trailing content")
        return v
    }

    fun value(v: Any?): String = when {
        v == null || v === JSONObject.NULL -> "null"
        v is String -> quote(v)
        v is Boolean -> v.toString()
        v is Number -> v.toString()
        v is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { value(v.opt(it)) }
        v is JSONObject -> v.keys().asSequence().sorted()
            .joinToString(",", "{", "}") { quote(it) + ":" + value(v.opt(it)) }
        else -> quote(v.toString())
    }
}
