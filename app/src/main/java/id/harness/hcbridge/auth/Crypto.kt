package id.harness.hcbridge.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * 纯 JVM 密码学原语：SHA-256、常量时间比较、随机令牌、PKCE S256。
 *
 * 约束（见 `docs/superpowers/plans/2026-11-03-app-mcp.md`）：
 * - 只存 SHA-256；哈希一律**小写 hex**。
 * - 比较密钥材料只用 [constantTimeEqualsHex]（内部 `MessageDigest.isEqual`），不用 `==`。
 * - 随机值只来自 [java.security.SecureRandom]；base64url **无填充**。
 * - 本文件**不得** import 任何 `android.*`（要能在 JVM 单测里直接跑）。
 * - 任何异常消息都不得带输入内容（verifier、令牌、密钥都不进日志/消息）。
 */
object Crypto {

    private const val DIGITS = "0123456789abcdef"

    /** 进程内共享；`SecureRandom` 本身线程安全，不必每次新建（新建会拖慢握手）。 */
    private val rng = SecureRandom()

    private val urlEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    /** UTF-8 编码后取 SHA-256，返回 64 字符小写 hex。 */
    fun sha256Hex(input: String): String = sha256Hex(input.toByteArray(Charsets.UTF_8))

    /** SHA-256，返回 64 字符小写 hex。 */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = CharArray(digest.size * 2)
        for (i in digest.indices) {
            val v = digest[i].toInt() and 0xFF
            out[i * 2] = DIGITS[v ushr 4]
            out[i * 2 + 1] = DIGITS[v and 0x0F]
        }
        return String(out)
    }

    /**
     * 常量时间比较两段 hex 摘要。
     *
     * 任一侧为空、或长度不同 ⇒ `false`（长度不是秘密，空串也不构成“相等”—— 
     * 缺失的凭据不能因为两边都缺失就放行）。其余情况走 `MessageDigest.isEqual`。
     *
     * 调用方给的都应是 [sha256Hex] 的输出（小写）；这里按字节比较，**不**做大小写折叠。
     */
    fun constantTimeEqualsHex(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a.length != b.length) return false
        return MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }

    /**
     * [bytes] 个随机字节的 base64url（无填充）。默认 32 字节 ⇒ 43 字符。
     *
     * 合法范围 `1..64`：0 字节没有熵（等于可猜的固定令牌），超过 64 字节没有意义。
     * 越界抛 [IllegalArgumentException]，消息只含数字/常量，不含任何密钥材料。
     */
    fun randomToken(bytes: Int = 32): String {
        require(bytes in 1..64) { "byte count must be in 1..64" }
        val buf = ByteArray(bytes)
        rng.nextBytes(buf)
        return urlEncoder.encodeToString(buf)
    }

    /**
     * RFC 7636 S256：`BASE64URL(SHA256(verifier))`，无填充。
     *
     * verifier 按其 **UTF-8 字节**进哈希（RFC 的合法 verifier 只有 ASCII，此时 UTF-8 与 ASCII 等价；
     * 非法输入也不做编码猜测，避免同一串算出两种 hash）。
     */
    fun pkceS256(verifier: String): String =
        urlEncoder.encodeToString(sha256(verifier.toByteArray(Charsets.UTF_8)))

    /** RFC 7636 §4.1：长度 43..128，字符集 `[A-Za-z0-9-._~]`。 */
    fun pkceVerifierValid(v: String): Boolean {
        if (v.length < 43 || v.length > 128) return false
        for (c in v) {
            val ok = (c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') || c == '-' || c == '.' || c == '_' || c == '~'
            if (!ok) return false
        }
        return true
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
