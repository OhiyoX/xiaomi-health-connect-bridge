package id.harness.hcbridge.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 默认鉴权与迁移提示（计划 Task 9）。
 *
 * 守的三件事：
 * 1. **新装即最保守**：`requireAuth = true`、`allowLoopbackNoAuth = false`、`confirmNewClients = true`、
 *    `publicBase = null`、`tunnelMode = "off"`；
 * 2. **老装机不会悄悄放行**：升级后（settings 表里没有这些键）本机请求照样 401；
 *    只有管理员**显式**把「要求 Bearer」关掉 **且** 打开「仅回环免鉴权」，回环请求才通；
 * 3. 迁移提示只出现一次（`migrationNoticeSeen` 一写就不再提示），而且**不自动**打开任何豁免。
 */
class MigrationDefaultsTest {

    @Test
    fun freshInstallRequiresAuth() {
        val c = McpConfig(InMemoryStore())
        assertTrue(c.requireAuth)
        assertFalse(c.allowLoopbackNoAuth)
        assertTrue(c.confirmNewClients)
        assertNull(c.publicBase)
        assertEquals("off", c.tunnelMode)
        assertFalse(c.migrationNoticeSeen)
    }

    @Test
    fun upgradeKeepsLoopbackClientsOnlyWhenFullyOptedIn() {
        val c = McpConfig(InMemoryStore()) // 老装机：只有 mcpEnabled=true，没有这些 settings 行
        val local = HttpReq("POST", "/mcp", emptyMap(), emptyMap(), "{}", peerLoopback = true)
        assertTrue(c.requireAuth)
        assertFalse(c.allowLoopbackNoAuth)
        assertFalse(trustedLoopback(local, c)) // 默认：401

        c.allowLoopbackNoAuth = true
        assertFalse(trustedLoopback(local, c)) // 只开 opt-in、requireAuth 仍是 true ⇒ 仍 401

        c.requireAuth = false
        assertTrue(trustedLoopback(local, c)) // 两个开关都到位才放行
    }

    @Test
    fun migrationNoticeIsOneShot() {
        val c = McpConfig(InMemoryStore())
        assertTrue(c.shouldShowMigrationNotice())
        c.migrationNoticeSeen = true
        assertFalse(c.shouldShowMigrationNotice())
    }

    @Test
    fun migrationNoticeHiddenOnceFullyOptedIn() {
        val c = McpConfig(InMemoryStore())
        c.allowLoopbackNoAuth = true
        c.requireAuth = false // 两个开关都到位 = 管理员已经知道自己在做什么
        assertFalse(c.shouldShowMigrationNotice())
    }

    @Test
    fun migrationNoticeDoesNotEnableAnyExemption() {
        val c = McpConfig(InMemoryStore())
        assertTrue(c.shouldShowMigrationNotice())
        assertTrue(c.requireAuth) // 只提示，不改配置
        assertFalse(c.allowLoopbackNoAuth)
        assertNull(c.publicBase)
        assertEquals(McpConfig.TUNNEL_OFF, c.tunnelMode)
    }
}
