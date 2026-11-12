package id.harness.hcbridge.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 令牌格式：前缀、随机体、日志用前缀切片、TTL。 */
class TokenFormatTest {

    @Test
    fun prefixesAreStable() {
        assertEquals("hcb_at_", TokenFormat.ACCESS_PREFIX)
        assertEquals("hcb_rt_", TokenFormat.REFRESH_PREFIX)
        assertEquals("hcb_oc_", TokenFormat.CODE_PREFIX)
        assertEquals("hcb_st_", TokenFormat.KEY_PREFIX)
        assertEquals("hcb_cs_", TokenFormat.CLIENT_SECRET_PREFIX)
        assertEquals("hcb_rq_", TokenFormat.REQUEST_PREFIX)
    }

    @Test
    fun tokensArePrefixedAndUnique() {
        val a = TokenFormat.newAccessToken()
        val b = TokenFormat.newAccessToken()
        assertTrue(a.startsWith("hcb_at_"))
        assertNotEquals(a, b)
    }

    @Test
    fun everyKindHasItsOwnPrefixAndRandomBody() {
        // 三元组：前缀、令牌、随机体应有的 base64url 长度（32 字节 ⇒ 43，24 字节 ⇒ 32）
        val cases = listOf(
            Triple(TokenFormat.ACCESS_PREFIX, TokenFormat.newAccessToken(), 43),
            Triple(TokenFormat.REFRESH_PREFIX, TokenFormat.newRefreshToken(), 43),
            Triple(TokenFormat.CODE_PREFIX, TokenFormat.newCode(), 43),
            Triple(TokenFormat.KEY_PREFIX, TokenFormat.newStaticKey(), 43),
            Triple(TokenFormat.CLIENT_SECRET_PREFIX, TokenFormat.newClientSecret(), 43),
            Triple(TokenFormat.REQUEST_PREFIX, TokenFormat.newRequestId(), 32),
        )
        for ((prefix, raw, bodyLen) in cases) {
            assertTrue("前缀不符：$prefix", raw.startsWith(prefix))
            val body = raw.substring(prefix.length)
            assertTrue("令牌体应为 base64url", body.all { it.isLetterOrDigit() || it == '-' || it == '_' })
            assertFalse("令牌体不得含填充", body.contains('='))
            assertEquals("随机体长度（$prefix）", bodyLen, body.length)
            assertEquals("整串长度（$prefix）", prefix.length + bodyLen, raw.length)
        }
        // 同一类型两次调用不同
        assertNotEquals(TokenFormat.newRefreshToken(), TokenFormat.newRefreshToken())
        assertNotEquals(TokenFormat.newCode(), TokenFormat.newCode())
        assertNotEquals(TokenFormat.newStaticKey(), TokenFormat.newStaticKey())
        assertNotEquals(TokenFormat.newClientSecret(), TokenFormat.newClientSecret())
        assertNotEquals(TokenFormat.newRequestId(), TokenFormat.newRequestId())
    }

    @Test
    fun prefixOfKeepsOnlyFirstTwelveChars() {
        val raw = TokenFormat.newAccessToken()
        assertEquals(12, TokenFormat.prefixOf(raw).length)
        assertEquals(raw.take(12), TokenFormat.prefixOf(raw))
        assertEquals("hcb_at_abcde", TokenFormat.prefixOf("hcb_at_abcdefghijklmnop"))
    }

    @Test
    fun prefixOfToleratesShortInput() {
        assertEquals("", TokenFormat.prefixOf(""))
        assertEquals("hcb", TokenFormat.prefixOf("hcb"))
        assertEquals(TokenFormat.REQUEST_PREFIX, TokenFormat.prefixOf(TokenFormat.REQUEST_PREFIX))
    }

    @Test
    fun prefixOfIsExactlyTwelveCharsOfRaw() {
        val raw = TokenFormat.newRefreshToken()
        val p = TokenFormat.prefixOf(raw)
        assertEquals("长度必为 12", 12, p.length)
        assertEquals(raw.take(12), p)
        // 12 = 前缀 7 字符 + 令牌体前 5 字符；前缀必须完整可见
        assertTrue(p.startsWith(TokenFormat.REFRESH_PREFIX))
        assertEquals(TokenFormat.REFRESH_PREFIX + raw.substring(TokenFormat.REFRESH_PREFIX.length, 12), p)
        assertTrue("日志切片不得含完整令牌", raw.length > p.length)
        assertNotEquals(raw, p)
    }

    @Test
    fun ttlsMatchDesign() {
        assertEquals(3_600_000L, TokenFormat.ACCESS_TTL_MS)        // 1 小时
        assertEquals(2_592_000_000L, TokenFormat.REFRESH_TTL_MS)   // 30 天
        assertEquals(300_000L, TokenFormat.CODE_TTL_MS)            // 5 分钟
        assertEquals(300_000L, TokenFormat.PENDING_TTL_MS)         // 5 分钟
    }

    @Test
    fun hashesOfTokensDifferFromRawAndEachOther() {
        val a = TokenFormat.newAccessToken()
        val b = TokenFormat.newAccessToken()
        val ha = Crypto.sha256Hex(a)
        val hb = Crypto.sha256Hex(b)
        // 存的是 hash，不是明文
        assertEquals(64, ha.length)
        assertNotEquals(a, ha)
        assertFalse(ha.contains(a.substring(TokenFormat.ACCESS_PREFIX.length)))
        // 不同令牌 → 不同 hash；同令牌重算稳定
        assertNotEquals(ha, hb)
        assertEquals(ha, Crypto.sha256Hex(a))
        assertTrue(Crypto.constantTimeEqualsHex(ha, Crypto.sha256Hex(a)))
    }
}
