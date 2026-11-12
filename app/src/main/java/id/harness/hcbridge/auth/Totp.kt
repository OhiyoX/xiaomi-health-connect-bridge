package id.harness.hcbridge.auth

import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * TOTP（RFC 6238，HMAC-SHA1 / 30 秒 / 6 位，验证容 ±1 步），计划 Task 6 / 设计决定 7。
 *
 * 不引第三方库：HMAC 用 JDK 的 `Mac`，base32 自写（RFC 4648，无填充）。
 * secret 是 **base32 文本**（20 字节，32 个字符），由 [secret] 生成；存储只经 `TotpSecret`。
 *
 * 不变式：
 * - [verify] 常量时间比较每个候选码，坏 secret/坏码一律 `false`，**不抛**；
 * - 单次校验不等于防重放：同一步的码在 ±1 窗口内可以被提交两次。需要"只能用一次"的调用方
 *   用 [ReplayGuard] + [verifyAndClaim]（管理页解锁就走它）。
 * - 本文件**不得** import 任何 `android.*`（JVM 单测要用），也**不写日志**（secret/码都不进日志）。
 */
object Totp {

    const val DIGITS = 6
    const val STEP_SECONDS = 30
    const val STEP_MS = STEP_SECONDS * 1_000L

    /** secret 的随机字节数（20 字节 = 160 bit，SHA-1 的推荐长度）。 */
    const val SECRET_BYTES = 20

    private const val ALGORITHM = "HmacSHA1"
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val MOD = 1_000_000

    private val random = SecureRandom()

    /** 新的 base32 secret（20 字节 → 32 个字符，无 `=` 填充）。 */
    fun secret(): String = base32(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })

    /** 该时刻的 6 位码（前导零保留）。secret 不是合法 base32 ⇒ [IllegalArgumentException]。 */
    fun code(secret: String, atMillis: Long): String = codeForStep(decode(secret), stepAt(atMillis))

    /** 是否命中 `[step - window, step + window]` 中的一步。 */
    fun verify(secret: String, code: String?, atMillis: Long, window: Int = 1): Boolean =
        matchedStep(secret, code, atMillis, window) != null

    /** `otpauth://totp/<issuer>:<account>?secret=…&issuer=…&algorithm=SHA1&digits=6&period=30`。 */
    fun uri(secret: String, issuer: String, account: String): String {
        val label = enc(issuer) + ":" + enc(account)
        return "otpauth://totp/$label?secret=${enc(secret)}&issuer=${enc(issuer)}" +
            "&algorithm=SHA1&digits=$DIGITS&period=$STEP_SECONDS"
    }

    /** `atMillis` 所在的时间步（自 Unix epoch 起的 30 秒计数）。 */
    fun stepAt(atMillis: Long): Long = Math.floorDiv(atMillis, STEP_MS)

    /**
     * 重放闸：同一个 secret 只接受**严格递增**的时间步。
     *
     * 进程内状态（不落库）：进程重启后同一 30 秒窗口理论上能再重放一次 —— 可接受，
     * 因为重放窗口本身只有 ±30 秒。表最多留 [MAX_ENTRIES] 条，超出清空（不按时间淘汰）。
     */
    class ReplayGuard {

        private val lastAccepted = HashMap<String, Long>()

        /** 占用该步；`true` = 第一次见到（可以放行），`false` = 重放/过期步。 */
        @Synchronized
        fun claim(secret: String, step: Long): Boolean {
            val prev = lastAccepted[secret]
            if (prev != null && step <= prev) return false
            if (lastAccepted.size >= MAX_ENTRIES) lastAccepted.clear()
            lastAccepted[secret] = step
            return true
        }

        @Synchronized
        fun clear() = lastAccepted.clear()

        private companion object {
            const val MAX_ENTRIES = 16
        }
    }

    /**
     * 校验**并**占用该步（防重放）：`true` 只可能返回一次。
     *
     * 从较晚的步往较早的步试，命中就占用那一步 —— 这样同一窗口里"未来"的码不会因为
     * 先用了"当前"的码而被白白判为重放。
     */
    fun verifyAndClaim(
        guard: ReplayGuard,
        secret: String,
        code: String?,
        atMillis: Long,
        window: Int = 1,
    ): Boolean {
        val clean = normalizeCode(code) ?: return false
        val bytes = decodeOrNull(secret) ?: return false
        val step = stepAt(atMillis)
        val w = window.coerceIn(0, 10)
        for (candidate in step + w downTo step - w) {
            if (!constantTimeEquals(codeForStep(bytes, candidate), clean)) continue
            if (guard.claim(secret, candidate)) return true
        }
        return false
    }

    // ------------------------------------------------------------- 内部

    /** 命中的最大步；`null` = 不匹配（坏 secret / 非 6 位数字都算不匹配）。 */
    private fun matchedStep(secret: String, code: String?, atMillis: Long, window: Int): Long? {
        val clean = normalizeCode(code) ?: return null
        val bytes = decodeOrNull(secret) ?: return null
        val step = stepAt(atMillis)
        val w = window.coerceIn(0, 10)
        var hit: Long? = null
        for (candidate in step - w..step + w) {
            if (constantTimeEquals(codeForStep(bytes, candidate), clean)) hit = candidate
        }
        return hit
    }

    /** 只接受纯数字（允许中间/两端有空格，如 `123 456`）；长度必须恰为 [DIGITS]。 */
    private fun normalizeCode(code: String?): String? {
        val digits = code?.filterNot { it == ' ' || it == '\t' }?.trim().orEmpty()
        if (digits.length != DIGITS) return null
        return if (digits.all { it in '0'..'9' }) digits else null
    }

    private fun codeForStep(key: ByteArray, step: Long): String {
        val msg = ByteArray(8)
        var v = step
        for (i in 7 downTo 0) {
            msg[i] = (v and 0xffL).toByte()
            v = v ushr 8
        }
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(key, ALGORITHM))
        val digest = mac.doFinal(msg)
        val offset = digest[digest.size - 1].toInt() and 0x0f
        val binary = ((digest[offset].toInt() and 0x7f) shl 24) or
            ((digest[offset + 1].toInt() and 0xff) shl 16) or
            ((digest[offset + 2].toInt() and 0xff) shl 8) or
            (digest[offset + 3].toInt() and 0xff)
        val value = binary % MOD
        return value.toString().padStart(DIGITS, '0')
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    /** 严格解码：非法字符 ⇒ 抛（[verify] 里转成 `false`）。 */
    fun decode(secret: String): ByteArray =
        decodeOrNull(secret) ?: throw IllegalArgumentException("totp: bad secret")

    private fun decodeOrNull(secret: String): ByteArray? {
        val cleaned = secret.filterNot { it == ' ' || it == '-' || it == '=' }.uppercase()
        if (cleaned.isEmpty()) return null
        val out = ByteArrayOutputStream(cleaned.length * 5 / 8)
        var buffer = 0
        var bits = 0
        for (ch in cleaned) {
            val idx = ALPHABET.indexOf(ch)
            if (idx < 0) return null
            buffer = (buffer shl 5) or idx
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((buffer ushr bits) and 0xff)
            }
        }
        // 余下的不足 8 位必须是 0（否则是被截断的非法输入）
        if (bits > 0 && (buffer and ((1 shl bits) - 1)) != 0) return null
        val bytes = out.toByteArray()
        return if (bytes.isEmpty()) null else bytes
    }

    private fun base32(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(ALPHABET[(buffer ushr bits) and 0x1f])
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 0x1f])
        return sb.toString()
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
