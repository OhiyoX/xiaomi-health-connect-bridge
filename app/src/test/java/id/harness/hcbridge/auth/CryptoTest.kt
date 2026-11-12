package id.harness.hcbridge.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯 JVM 单测：SHA-256、常量时间比较、随机令牌、PKCE S256。 */
class CryptoTest {

    @Test
    fun sha256MatchesKnownVector() =
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Crypto.sha256Hex("abc"),
        )

    @Test
    fun sha256MatchesEmptyStringVector() =
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Crypto.sha256Hex(""),
        )

    @Test
    fun sha256BytesOverloadMatchesStringOverload() =
        assertEquals(
            Crypto.sha256Hex("hello 健康"),
            Crypto.sha256Hex("hello 健康".toByteArray(Charsets.UTF_8)),
        )

    @Test
    fun sha256IsLowercaseHex64Chars() {
        val h = Crypto.sha256Hex("some-token-value")
        assertEquals(64, h.length)
        assertEquals(h.lowercase(), h)
        assertTrue(h.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun pkceMatchesRfc7636AppendixB() =
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Crypto.pkceS256("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )

    @Test
    fun pkceIsBase64UrlWithoutPadding() {
        val v = "a".repeat(43)
        val c = Crypto.pkceS256(v)
        assertEquals(43, c.length) // SHA-256 = 32 字节 ⇒ base64 43 字符（无填充）
        assertFalse(c.contains('='))
        assertFalse(c.contains('+'))
        assertFalse(c.contains('/'))
        assertTrue(c.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun verifierLengthBounds() {
        assertFalse(Crypto.pkceVerifierValid("short"))
        assertFalse(Crypto.pkceVerifierValid("a".repeat(42)))
        assertTrue(Crypto.pkceVerifierValid("a".repeat(43)))
        assertTrue(Crypto.pkceVerifierValid("a".repeat(128)))
        assertFalse(Crypto.pkceVerifierValid("a".repeat(129)))
        assertFalse(Crypto.pkceVerifierValid(""))
    }

    @Test
    fun verifierAllowsRfc7636CharsetOnly() {
        // [A-Za-z0-9-._~] 全部出现在合法串里
        val allChars = "AZaz09-._~"
        val ok = allChars + "a".repeat(43 - allChars.length)
        assertEquals(43, ok.length)
        assertTrue(Crypto.pkceVerifierValid(ok))

        for (bad in listOf(" ", "/", "+", "=", "%", "\n", "\u00e9")) {
            assertFalse(
                "verifier 不应接受 '$bad'",
                Crypto.pkceVerifierValid("a".repeat(42) + bad),
            )
        }
    }

    @Test
    fun constantTimeCompareAcceptsEqualHex() {
        val h = Crypto.sha256Hex("same")
        assertTrue(Crypto.constantTimeEqualsHex(h, h))
        assertTrue(Crypto.constantTimeEqualsHex("0f", "0f"))
    }

    @Test
    fun constantTimeCompareRejectsEmptySides() {
        // 缺失的凭据不能因为两边都缺失就放行
        assertFalse(Crypto.constantTimeEqualsHex("", ""))
        assertFalse(Crypto.constantTimeEqualsHex("", "00"))
        assertFalse(Crypto.constantTimeEqualsHex("00", ""))
    }

    @Test
    fun constantTimeCompareDoesNotFoldCase() {
        // 约定：两边都是本模块的小写 hex；大小写不同就不相等（调用方先 lowercase）
        assertFalse(Crypto.constantTimeEqualsHex("ABCD", "abcd"))
        assertFalse(
            Crypto.constantTimeEqualsHex(
                Crypto.sha256Hex("token").uppercase(),
                Crypto.sha256Hex("token"),
            ),
        )
        assertTrue(
            Crypto.constantTimeEqualsHex(
                Crypto.sha256Hex("token").uppercase().lowercase(),
                Crypto.sha256Hex("token"),
            ),
        )
    }

    @Test
    fun constantTimeCompareRejectsSameLengthDifference() {
        assertFalse(Crypto.constantTimeEqualsHex("aa", "ab"))
        assertFalse(
            Crypto.constantTimeEqualsHex(
                Crypto.sha256Hex("left"),
                Crypto.sha256Hex("right"),
            ),
        )
    }

    @Test
    fun constantTimeCompareRejectsDifferentLengths() {
        assertFalse(Crypto.constantTimeEqualsHex("aa", "aaaa"))
        assertFalse(Crypto.constantTimeEqualsHex("", "a"))
        assertFalse(Crypto.constantTimeEqualsHex("aaaa", "aa"))
    }

    @Test
    fun randomTokenDefaultIs32BytesBase64Url() {
        val t = Crypto.randomToken()
        assertEquals(43, t.length) // 32 字节 ⇒ 43 字符（无填充）
        assertFalse(t.contains('='))
        assertFalse(t.contains('+'))
        assertFalse(t.contains('/'))
        assertTrue(t.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun randomTokenUsesRequestedByteCount() {
        assertEquals(32, Crypto.randomToken(24).length) // 24 字节 ⇒ 32 字符
        assertEquals(22, Crypto.randomToken(16).length) // 16 字节 ⇒ 22 字符
    }

    @Test
    fun randomTokensAreUnique() {
        val seen = HashSet<String>(4096)
        repeat(2048) { assertTrue("随机令牌重复", seen.add(Crypto.randomToken())) }
    }

    @Test
    fun randomTokenRejectsOutOfRangeByteCounts() {
        for (bad in listOf(-1, 0, 65, 1000, Int.MIN_VALUE, Int.MAX_VALUE)) {
            try {
                Crypto.randomToken(bad)
                throw AssertionError("randomToken($bad) 应该抛 IllegalArgumentException")
            } catch (expected: IllegalArgumentException) {
                // 消息不得带任何输入内容以外的秘密；这里只保证不返回固定令牌
                assertTrue(expected.message != null)
            }
        }
        // 边界内可用
        assertEquals(2, Crypto.randomToken(1).length) // 1 字节 ⇒ 2 字符
        assertEquals(86, Crypto.randomToken(64).length) // 64 字节 ⇒ 86 字符
    }
}
