package id.harness.hcbridge.auth

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 策略规则（spec §8.2）：`null` = 不额外限制；`[]` = 全禁止（**不是**不限制）；
 * 非法 JSON **必须抛异常**（由 [ToolGate] 折成 `policy_corrupt` 拒绝），绝不退化成放行。
 */
class ToolPolicyTest {

    @Test
    fun emptyPolicyAllowsNothingButNullAllowsAll() {
        assertFalse(ToolPolicy.allowed("[]", "hc_status", null))
        assertTrue(ToolPolicy.allowed(null, "hc_status", null))
    }

    @Test
    fun blankPolicyIsCorruptNotUnrestricted() {
        // 只有 null 才是"不限制"；空串/空白是坏数据（存了个空值不等于放开）
        for (blank in listOf("", "   ", "\n\t")) {
            assertThrows(IllegalArgumentException::class.java) {
                ToolPolicy.allowed(blank, "hc_status", null)
            }
            assertThrows(IllegalArgumentException::class.java) { ToolPolicy.canonical(blank) }
            assertThrows(IllegalArgumentException::class.java) { ToolPolicy.names(blank) }
        }
        assertNull(ToolPolicy.canonical(null))
    }

    @Test
    fun unknownRuleKeysAreRejected() {
        // `arg` 拼错成少一个 s：当成"忽略未知键"就等于放宽成"任意参数"
        assertThrows(IllegalArgumentException::class.java) {
            ToolPolicy.allowed("""[{"tool":"hc_backfill","arg":{"days":30}}]""", "hc_backfill", JSONObject())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ToolPolicy.canonical("""[{"tool":"hc_status","args":{},"note":"x"}]""")
        }
    }

    @Test
    fun trailingGarbageIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { ToolPolicy.canonical("""["hc_status"] junk""") }
        assertThrows(IllegalArgumentException::class.java) {
            ToolPolicy.allowed("""["hc_status"]]""", "hc_status", null)
        }
    }

    @Test
    fun whitelistAllowsOnlyListedTools() {
        val p = """["hc_status","query_spo2"]"""
        assertTrue(ToolPolicy.allowed(p, "hc_status", null))
        assertTrue(ToolPolicy.allowed(p, "query_spo2", null))
        assertFalse(ToolPolicy.allowed(p, "hc_sync_now", null))
        assertFalse(ToolPolicy.allowed(p, "nope", null))
    }

    @Test
    fun argRuleRequiresSubsetMatch() {
        val p = """[{"tool":"hc_backfill","args":{"days":30}}]"""
        assertTrue(ToolPolicy.allowed(p, "hc_backfill", JSONObject().put("days", 30)))
        assertFalse(ToolPolicy.allowed(p, "hc_backfill", JSONObject().put("days", 365)))
        assertFalse(ToolPolicy.allowed(p, "hc_backfill", JSONObject()))
    }

    @Test
    fun argRuleWithoutArgumentsIsDenied() {
        val p = """[{"tool":"hc_backfill","args":{"days":30}}]"""
        assertFalse(ToolPolicy.allowed(p, "hc_backfill", null))
    }

    @Test
    fun extraArgumentsBeyondTheRuleAreAllowed() {
        val p = """[{"tool":"hc_backfill","args":{"days":30}}]"""
        assertTrue(ToolPolicy.allowed(p, "hc_backfill", JSONObject().put("days", 30).put("force", true)))
    }

    @Test
    fun objectRuleWithoutArgsAllowsAnyArguments() {
        val p = """[{"tool":"hc_sync_now"}]"""
        assertTrue(ToolPolicy.allowed(p, "hc_sync_now", JSONObject()))
        assertTrue(ToolPolicy.allowed(p, "hc_sync_now", JSONObject().put("x", 1)))
        assertFalse(ToolPolicy.allowed(p, "hc_backfill", JSONObject()))
    }

    @Test
    fun stringAndObjectRulesCanMix() {
        val p = """["hc_status",{"tool":"hc_backfill","args":{"days":30}}]"""
        assertTrue(ToolPolicy.allowed(p, "hc_status", null))
        assertTrue(ToolPolicy.allowed(p, "hc_backfill", JSONObject().put("days", 30)))
        assertFalse(ToolPolicy.allowed(p, "hc_dump", null))
    }

    @Test
    fun argValuesCompareByTypeAndValue() {
        val p = """[{"tool":"t","args":{"s":"x","n":1,"b":true,"z":null}}]"""
        assertTrue(ToolPolicy.allowed(p, "t",
            JSONObject().put("s", "x").put("n", 1).put("b", true).put("z", JSONObject.NULL)))
        assertFalse("字符串与数字不同", ToolPolicy.allowed(p, "t",
            JSONObject().put("s", 1).put("n", 1).put("b", true).put("z", JSONObject.NULL)))
        assertFalse("布尔不折叠", ToolPolicy.allowed(p, "t",
            JSONObject().put("s", "x").put("n", 1).put("b", "true").put("z", JSONObject.NULL)))
    }

    @Test
    fun corruptPolicyThrowsFromBothEntryPoints() {
        for (bad in listOf("{not json", "{}", "[1]", """[{"tool":1}]""", "[null]", """[{"args":{"a":1}}]""")) {
            assertThrows("allowed 应拒绝: $bad", IllegalArgumentException::class.java) {
                ToolPolicy.allowed(bad, "hc_status", null)
            }
            assertThrows("canonical 应拒绝: $bad", IllegalArgumentException::class.java) {
                ToolPolicy.canonical(bad)
            }
        }
    }

    @Test
    fun corruptArgsShapeThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            ToolPolicy.allowed("""[{"tool":"t","args":[1]}]""", "t", JSONObject())
        }
    }

    @Test
    fun namesReturnsNullForUnrestrictedAndSetForPolicy() {
        assertNull(ToolPolicy.names(null))
        assertEquals(emptySet<String>(), ToolPolicy.names("[]"))
        assertEquals(setOf("a", "b"), ToolPolicy.names("""["a",{"tool":"b","args":{"x":1}}]"""))
        assertThrows(IllegalArgumentException::class.java) { ToolPolicy.names("{not json") }
    }

    @Test
    fun canonicalNormalizesAndIsIdempotent() {
        assertNull(ToolPolicy.canonical(null))
        val c = ToolPolicy.canonical("""[ "hc_status" , { "tool" : "hc_backfill" , "args" : { "days" : 30 } } ]""")
        assertEquals("""["hc_status",{"tool":"hc_backfill","args":{"days":30}}]""", c)
        assertEquals(c, ToolPolicy.canonical(c))
    }
}
