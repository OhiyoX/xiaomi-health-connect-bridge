package id.harness.hcbridge.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Totp` 的 JVM 合约（计划 Task 6 / 设计决定 7）：
 * RFC 6238 的 SHA-1 向量、±1 步窗口、重放闸、base32/URI 形态。
 *
 * RFC 6238 附录 B 的 secret 是 ASCII `12345678901234567890`，base32 就是
 * `GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ`；向量原值 8 位，TOTP 6 位取低位（`94287082` → `287082`）。
 */
class TotpTest {

    private val rfcSecret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    @Test
    fun rfc6238Vectors() {
        assertEquals("287082", Totp.code(rfcSecret, 59_000))
        assertEquals("081804", Totp.code(rfcSecret, 1_111_111_109_000))
        // 同一步内码不变；跨步才变
        assertEquals("287082", Totp.code(rfcSecret, 30_000))
        assertNotEquals(Totp.code(rfcSecret, 59_000), Totp.code(rfcSecret, 89_000))
        // RFC 向量 8 位值的低位：59s → 94287082
        assertEquals(6, Totp.code(rfcSecret, 59_000).length)
    }

    @Test
    fun verifyAcceptsAdjacentStepOnly() {
        val now = 1_700_000_000_000L
        val current = Totp.code(rfcSecret, now)
        assertTrue(Totp.verify(rfcSecret, current, now))
        // ±30s（相邻步）在同一 ±1 窗口内可以被接受（重放由 ReplayGuard 兜）
        assertTrue(Totp.verify(rfcSecret, Totp.code(rfcSecret, now - Totp.STEP_MS), now))
        assertTrue(Totp.verify(rfcSecret, Totp.code(rfcSecret, now + Totp.STEP_MS), now))
        // ±120s 超出窗口
        assertFalse(Totp.verify(rfcSecret, Totp.code(rfcSecret, now - 4 * Totp.STEP_MS), now))
        assertFalse(Totp.verify(rfcSecret, Totp.code(rfcSecret, now + 4 * Totp.STEP_MS), now))
        // window = 0 只认当前步
        assertFalse(Totp.verify(rfcSecret, Totp.code(rfcSecret, now - Totp.STEP_MS), now, window = 0))
    }

    @Test
    fun verifyRejectsBadInputWithoutThrowing() {
        val now = 1_700_000_000_000L
        for (bad in listOf(null, "", "12345", "1234567", "abcdef", "12 34 56", "-12345")) {
            assertFalse("不该通过：$bad", Totp.verify(rfcSecret, bad, now))
        }
        // 坏 secret 一律 false（不是异常穿透到 UI）
        assertFalse(Totp.verify("not-base32-1", Totp.code(rfcSecret, now), now))
        assertFalse(Totp.verify("", Totp.code(rfcSecret, now), now))
        // 空格分组可以接受（用户照着 authenticator 抄）
        val current = Totp.code(rfcSecret, now)
        assertTrue(Totp.verify(rfcSecret, current.substring(0, 3) + " " + current.substring(3), now))
    }

    @Test
    fun replayGuardAcceptsAStepOnlyOnce() {
        val now = 1_700_000_000_000L
        val guard = Totp.ReplayGuard()
        val step = Totp.stepAt(now)
        val code = Totp.code(rfcSecret, now)
        assertTrue(Totp.verifyAndClaim(guard, rfcSecret, code, now))
        assertFalse("同一步第二次必须拒绝", Totp.verifyAndClaim(guard, rfcSecret, code, now))
        // 下一步的新码照常通过；往回的旧步被拒（严格递增）
        assertTrue(Totp.verifyAndClaim(guard, rfcSecret, Totp.code(rfcSecret, now + Totp.STEP_MS), now + Totp.STEP_MS))
        assertFalse(Totp.verifyAndClaim(guard, rfcSecret, code, now))
        guard.clear()
        assertTrue(Totp.verifyAndClaim(guard, rfcSecret, code, now))
    }

    @Test
    fun replayGuardIsPerSecret() {
        val now = 1_700_000_000_000L
        val guard = Totp.ReplayGuard()
        val other = Totp.secret()
        assertTrue(Totp.verifyAndClaim(guard, rfcSecret, Totp.code(rfcSecret, now), now))
        assertTrue(Totp.verifyAndClaim(guard, other, Totp.code(other, now), now))
    }

    @Test
    fun secretIsBase32AndRandom() {
        val a = Totp.secret()
        val b = Totp.secret()
        assertEquals(32, a.length)                       // 20 字节 → 32 个字符
        assertTrue(a.all { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567" })
        assertNotEquals(a, b)
        assertEquals(Totp.SECRET_BYTES, Totp.decode(a).size)
        assertTrue(a.all { it !in "0189" })              // base32 的字母表里没有这几个字符
    }

    @Test
    fun generatedSecretRoundTrips() {
        val s = Totp.secret()
        val now = 1_700_000_000_000L
        assertTrue(Totp.verify(s, Totp.code(s, now), now))
        assertFalse(Totp.verify(s, Totp.code(Totp.secret(), now), now))
    }

    @Test
    fun uriCarriesIssuerAndAlgorithm() {
        val uri = Totp.uri(rfcSecret, "HC Bridge", "phone")
        assertTrue(uri.startsWith("otpauth://totp/HC%20Bridge:phone?"))
        assertTrue(uri.contains("secret=$rfcSecret"))
        assertTrue(uri.contains("issuer=HC%20Bridge"))
        assertTrue(uri.contains("algorithm=SHA1"))
        assertTrue(uri.contains("digits=6"))
        assertTrue(uri.contains("period=30"))
        assertFalse(uri.contains(" "))  // 标签/参数都编码过
    }
}
