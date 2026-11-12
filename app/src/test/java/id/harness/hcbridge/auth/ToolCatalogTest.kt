package id.harness.hcbridge.auth

import id.harness.hcbridge.McpTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目录必须与服务器 `tools/list`（= [McpTools.json]）逐字对齐：多一个、少一个、分错类都是回归。
 */
class ToolCatalogTest {

    @Test
    fun catalogMatchesServerToolList() {
        assertEquals(McpTools.names(), ToolCatalog.ALL_TOOLS)
    }

    @Test
    fun catalogKeepsServerDeclarationOrder() {
        // tools/list 的顺序不能变（客户端 diff/审计都按顺序看）
        assertEquals(McpTools.names().toList(), ToolCatalog.ALL_TOOLS.toList())
    }

    @Test
    fun countsAre20Read16Write36Total() {
        assertEquals(20, ToolCatalog.READ_TOOLS.size)
        assertEquals(16, ToolCatalog.WRITE_TOOLS.size)
        assertEquals(36, ToolCatalog.ALL_TOOLS.size)
    }

    @Test
    fun readAndWriteDoNotOverlapAndCoverAll() {
        assertTrue(ToolCatalog.READ_TOOLS.intersect(ToolCatalog.WRITE_TOOLS).isEmpty())
        assertEquals(ToolCatalog.ALL_TOOLS, ToolCatalog.READ_TOOLS + ToolCatalog.WRITE_TOOLS)
    }

    @Test
    fun everyServerToolIsClassified() {
        for (name in McpTools.names()) {
            assertNotNull("未分类的工具: $name", ToolCatalog.scopeOf(name))
        }
    }

    @Test
    fun querySpo2IsReadableAndUnknownIsNull() {
        assertEquals("health.read", ToolCatalog.scopeOf("query_spo2"))
        assertEquals("health.write", ToolCatalog.scopeOf("hc_sync_now"))
        assertNull(ToolCatalog.scopeOf("nope"))
    }

    @Test
    fun scopeConstantsAreTheOauthScopeStrings() {
        assertEquals("health.read", ToolCatalog.SCOPE_READ)
        assertEquals("health.write", ToolCatalog.SCOPE_WRITE)
        assertEquals("ingest", ToolCatalog.SCOPE_INGEST)
    }

    @Test
    fun ingestScopeGrantsNothing() {
        assertTrue(ToolCatalog.toolsForScopes(setOf("ingest")).isEmpty())
        assertTrue(ToolCatalog.toolsForScopes(setOf("ingest", "nope")).isEmpty())
        assertTrue(ToolCatalog.toolsForScopes(emptySet()).isEmpty())
    }

    @Test
    fun readPlusWriteGrantsEveryTool() {
        assertEquals(ToolCatalog.READ_TOOLS, ToolCatalog.toolsForScopes(setOf("health.read")))
        assertEquals(ToolCatalog.WRITE_TOOLS, ToolCatalog.toolsForScopes(setOf("health.write")))
        assertEquals(ToolCatalog.ALL_TOOLS, ToolCatalog.toolsForScopes(setOf("health.read", "health.write")))
    }

    @Test
    fun toolsForScopesKeepsCatalogOrder() {
        val both = ToolCatalog.toolsForScopes(setOf("health.read", "health.write")).toList()
        assertEquals(ToolCatalog.ALL_TOOLS.toList(), both)
    }

    @Test
    fun serverToolJsonIsWellFormed() {
        val arr = McpTools.json()
        assertEquals(36, arr.length())
        val seen = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            val name = t.getString("name")
            assertTrue("重名工具: $name", seen.add(name))
            assertTrue("缺描述: $name", t.getString("description").isNotBlank())
            val schema = t.getJSONObject("inputSchema")
            assertEquals("object", schema.getString("type"))
            assertEquals(false, schema.getBoolean("additionalProperties"))
            assertTrue("缺 properties: $name", schema.has("properties"))
        }
        assertEquals(seen, McpTools.names())
    }

    @Test
    fun jsonIsAFreshCopyEachCall() {
        val first = McpTools.json()
        first.remove(0)
        assertEquals(35, first.length())
        assertEquals(36, McpTools.json().length())
    }
}
