package id.harness.hcbridge.auth

import java.time.LocalDateTime
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 门：scope ⊆ 策略层 ⊆ 时间窗层，任一层拒绝即拒绝（fail closed）。
 * `visible` 只按工具名过滤（`tools/list`）；参数在**调用时**才判定。
 */
class ToolGateTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun at(local: String): Long =
        LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()

    private fun check(
        scopes: Set<String>,
        policies: List<String?> = listOf(null),
        windows: List<String?> = listOf(null),
        tool: String,
        args: JSONObject? = null,
        now: Long = 0L
    ): String? = ToolGate.check(scopes, policies, windows, tool, args, now, zone)

    // ---------------------------------------------------------------- 可见集

    @Test
    fun visibleIsScopeIntersectedWithPolicyLayers() {
        assertEquals(20, ToolGate.visible(ToolCatalog.ALL_TOOLS, ToolCatalog.toolsForScopes(setOf("health.read"))).size)
        assertEquals(16, ToolGate.visible(ToolCatalog.ALL_TOOLS, ToolCatalog.toolsForScopes(setOf("health.write"))).size)
        assertEquals(36, ToolGate.visible(ToolCatalog.ALL_TOOLS, ToolCatalog.ALL_TOOLS).size)
        assertEquals(0, ToolGate.visible(ToolCatalog.ALL_TOOLS, ToolGate.effectiveTools(setOf("health.read"), listOf("[]"))).size)
        assertEquals(setOf("hc_status"), ToolGate.visible(ToolCatalog.ALL_TOOLS,
            ToolGate.effectiveTools(setOf("health.read"), listOf("""["hc_status"]"""))))
    }

    @Test
    fun effectiveToolsIntersectsEveryPolicyLayer() {
        val scopes = setOf("health.read", "health.write")
        assertEquals(ToolCatalog.ALL_TOOLS, ToolGate.effectiveTools(scopes, listOf(null, null)))
        assertEquals(ToolCatalog.ALL_TOOLS, ToolGate.effectiveTools(scopes, emptyList()))
        assertEquals(setOf("hc_status", "hc_dump"),
            ToolGate.effectiveTools(scopes, listOf("""["hc_status","hc_dump","nope"]""", null)))
        assertEquals(setOf("hc_dump"),
            ToolGate.effectiveTools(scopes, listOf("""["hc_status","hc_dump"]""", """["hc_dump"]""")))
        assertEquals(emptySet<String>(),
            ToolGate.effectiveTools(scopes, listOf("""["hc_status"]""", """["hc_dump"]""")))
    }

    @Test
    fun effectiveToolsFailsClosedOnCorruptPolicy() {
        assertEquals(emptySet<String>(),
            ToolGate.effectiveTools(setOf("health.read"), listOf(null, "{not json")))
        assertEquals(emptySet<String>(),
            ToolGate.effectiveTools(setOf("health.read"), listOf("""["hc_status"]""", "{}")))
    }

    // ---------------------------------------------------------------- 三层

    @Test
    fun allLayersMustAllow() {
        assertNull(check(setOf("health.read"), listOf(null, null), listOf(null, null), "hc_status"))
        assertEquals("policy", check(setOf("health.read"), listOf(null, "[]"), listOf(null), "hc_status"))
        assertEquals("scope", check(setOf("health.read"), listOf(null), listOf(null), "hc_sync_now"))
    }

    @Test
    fun emptyListsMeanNoRestriction() {
        assertNull(check(setOf("health.read"), emptyList(), emptyList(), "hc_status"))
    }

    @Test
    fun scopeIsCheckedBeforePolicyAndWindow() {
        assertEquals("scope", check(setOf("health.read"), listOf("{not json"), listOf("{not json"), "hc_sync_now"))
        assertEquals("policy_corrupt", check(setOf("health.read"), listOf("{not json"), listOf("{not json"), "hc_status"))
        assertEquals("policy", check(setOf("health.read"), listOf("[]"), listOf("{not json"), "hc_status"))
    }

    @Test
    fun unknownToolIsDeniedByScopeLayer() {
        assertEquals("scope", check(setOf("health.read", "health.write"), tool = "nope"))
    }

    // ---------------------------------------------------------------- 损坏

    @Test
    fun corruptPolicyFailsClosedWithReason() {
        assertEquals("policy_corrupt",
            check(setOf("health.read"), listOf("{not json"), listOf(null), "hc_status"))
        assertEquals("policy_corrupt",
            check(setOf("health.read"), listOf(null, "{}"), listOf(null), "hc_status"))
    }

    @Test
    fun blankPolicyAndWindowAreCorruptNotUnrestricted() {
        assertEquals("policy_corrupt", check(setOf("health.read"), listOf(""), listOf(null), "hc_status"))
        assertEquals("policy_corrupt", check(setOf("health.read"), listOf(null, "   "), listOf(null), "hc_status"))
        assertEquals("window_corrupt", check(setOf("health.read"), listOf(null), listOf(""), "hc_status"))
        assertEquals(emptySet<String>(), ToolGate.effectiveTools(setOf("health.read"), listOf("")))
    }

    @Test
    fun unknownKeysFailClosedWithCorruptReason() {
        assertEquals("policy_corrupt",
            check(setOf("health.write"), listOf("""[{"tool":"hc_backfill","arg":{"days":30}}]"""), listOf(null), "hc_backfill"))
        assertEquals("window_corrupt",
            check(setOf("health.read"), listOf(null), listOf("""{"day":[2]}"""), "hc_status"))
    }

    @Test
    fun corruptWindowFailsClosedWithReason() {
        assertEquals("window_corrupt",
            check(setOf("health.read"), listOf(null), listOf("""{"days":"x"}"""), "hc_status"))
        assertEquals("window_corrupt",
            check(setOf("health.read"), listOf(null), listOf(null, "{not json"), "hc_status"))
    }

    // ---------------------------------------------------------------- 参数

    @Test
    fun argsAreEnforcedAtCallTimeOnly() {
        val policies = listOf("""[{"tool":"hc_backfill","args":{"days":30}}]""")
        // 可见集只看名字：hc_backfill 在列表里
        assertTrue("hc_backfill" in ToolGate.visible(ToolCatalog.ALL_TOOLS,
            ToolGate.effectiveTools(setOf("health.write"), policies)))
        assertNull(check(setOf("health.write"), policies, listOf(null), "hc_backfill", JSONObject().put("days", 30)))
        assertEquals("policy",
            check(setOf("health.write"), policies, listOf(null), "hc_backfill", JSONObject().put("days", 365)))
        assertEquals("policy",
            check(setOf("health.write"), policies, listOf(null), "hc_backfill", JSONObject()))
        assertEquals("policy", check(setOf("health.write"), policies, listOf(null), "hc_backfill", null))
    }

    // ---------------------------------------------------------------- 时间窗

    @Test
    fun windowDeniesOutsideHoursWithReasonWindow() {
        val windows = listOf(null, """{"start":"09:00","end":"22:00"}""")
        assertNull(check(setOf("health.read"), listOf(null), windows, "hc_status", now = at("2026-11-03T12:00")))
        assertEquals("window",
            check(setOf("health.read"), listOf(null), windows, "hc_status", now = at("2026-11-03T23:00")))
    }

    @Test
    fun everyWindowLayerMustAllow() {
        val windows = listOf("""{"days":[2]}""", """{"start":"09:00","end":"22:00"}""")
        assertNull(check(setOf("health.read"), listOf(null), windows, "hc_status", now = at("2026-11-03T12:00")))
        assertEquals("window",
            check(setOf("health.read"), listOf(null), windows, "hc_status", now = at("2026-11-04T12:00")))
    }

    // ---------------------------------------------------------------- scope 语义

    @Test
    fun ingestScopeGrantsNoTool() {
        assertEquals("scope", check(setOf("ingest"), tool = "hc_status"))
        assertEquals("scope", check(setOf("ingest"), tool = "hc_sync_now"))
    }

    @Test
    fun readAndWriteTogetherAllowEveryTool() {
        val scopes = setOf("health.read", "health.write")
        assertEquals(36, ToolGate.effectiveTools(scopes, listOf(null)).size)
        for (tool in ToolCatalog.ALL_TOOLS) {
            assertNull("$tool 应放行", check(scopes, tool = tool))
        }
    }

    // ---------------------------------------------------------------- 原因串

    @Test
    fun reasonStringsAreStable() {
        assertEquals("scope", ToolGate.Reason.SCOPE.wire)
        assertEquals("policy", ToolGate.Reason.POLICY.wire)
        assertEquals("window", ToolGate.Reason.WINDOW.wire)
        assertEquals("policy_corrupt", ToolGate.Reason.POLICY_CORRUPT.wire)
        assertEquals("window_corrupt", ToolGate.Reason.WINDOW_CORRUPT.wire)
        assertEquals(ToolGate.Reason.SCOPE, ToolGate.checkReason(
            emptySet(), listOf(null), listOf(null), "hc_status", null, 0L, zone))
        assertEquals("policy_corrupt", ToolGate.checkReason(
            setOf("health.read"), listOf("{not json"), listOf(null), "hc_status", null, 0L, zone)?.wire)
    }
}
