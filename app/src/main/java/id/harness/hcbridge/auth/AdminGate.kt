package id.harness.hcbridge.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 管理员门（计划 Task 6 / 设计决定 6）。
 *
 * 管理员密码只以 **PBKDF2-HmacSHA256** 派生值落库（`settings("adminPasswordHash")`，格式
 * `pbkdf2$sha256$<iter>$<salt b64>$<hash b64>`）。JDK 自带 `SecretKeyFactory`，**不加依赖**。
 *
 * 不变式（改代码前先读）：
 * - 比较走 [MessageDigest.isEqual]（常量时间）；`salt` 每次随机 16 字节，所以同密码两次哈希不同。
 * - 任何解析失败都返回 `false`/`true`（`needsSetup`），**不抛异常**：坏哈希绝不能让 `verify`
 *   变成"抛异常就放行"，也不能把管理员永久锁死在门外。
 * - 本文件**不得** import 任何 `android.*`（JVM 单测要用）。
 * - 密码只经 [hash]/[verify] 流动；本类不写日志、不把密码或派生值拼进异常消息。
 * - [AttemptLimiter] / [Session] 是**进程内**状态：不落库（重启即清），只用于挡住
 *   连续试错与"解锁后放着不管"。
 */
object AdminGate {

    /** 新密码的默认迭代数（PC 上 ≈ 0.05s，手机上 ≈ 0.1–0.3s；只在设置/解锁各跑一次）。 */
    const val DEFAULT_ITERATIONS = 120_000

    /** 允许的最少迭代数：低于它等于没有 KDF。 */
    const val MIN_ITERATIONS = 1_000

    /** 允许的最大迭代数：库里被改成天文数字时不能把 UI 卡死。 */
    const val MAX_ITERATIONS = 2_000_000

    /** [AttemptLimiter] 默认锁定秒数（管理页文案也用它，避免两处数字漂移）。 */
    const val LOCK_SECONDS = 60

    /**
     * 窗口内允许的连续失败次数（缺省 5，管理页「安全设置」直接展示这个数字）。
     *
     * 它是**策略常量**，不是配置：App 的管理页只读，改这里等于改行为，改完要同步文案。
     */
    const val MAX_FAILURES = 5

    private const val SALT_BYTES = 16
    private const val HASH_BYTES = 32
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val SCHEME = "pbkdf2"
    private const val DIGEST = "sha256"

    private val random = SecureRandom()

    /** `pbkdf2$sha256$<iter>$<salt>$<hash>`；[iterations] 供测试用低值。 */
    fun hash(password: String, iterations: Int = DEFAULT_ITERATIONS): String {
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "iterations out of range" }
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val derived = derive(password, salt, iterations, HASH_BYTES)
        val b64 = Base64.getEncoder().withoutPadding()
        return "$SCHEME\$$DIGEST\$$iterations\$${b64.encodeToString(salt)}\$${b64.encodeToString(derived)}"
    }

    /** 常量时间比较；`stored` 为 null/空/坏格式 ⇒ `false`（不抛）。 */
    fun verify(password: String, stored: String?): Boolean {
        val parsed = parse(stored) ?: return false
        val derived = try {
            derive(password, parsed.salt, parsed.iterations, parsed.hash.size)
        } catch (t: Throwable) {
            return false
        }
        return MessageDigest.isEqual(derived, parsed.hash)
    }

    /**
     * 还没设密码（或存的哈希已损坏）⇒ 进管理页先设一个。
     *
     * 坏哈希按"未设置"处理是**有意**的：能改 `settings` 表的人本来就能删掉这行，
     * 而按"已设置"处理只会让设备主人永久进不了管理页。
     */
    fun needsSetup(config: McpConfig): Boolean = parse(config.adminPasswordHash) == null

    /** 已存哈希的迭代数；没有/坏格式 ⇒ `null`（与 [needsSetup] 的判定同源）。 */
    fun iterationsOf(stored: String?): Int? = parse(stored)?.iterations

    /**
     * 老哈希的迭代数低于当前默认值 ⇒ 下次登录成功后原地重算一版更强的。
     *
     * 不强制登出、不需要旧密码：用户刚刚证明过自己知道密码，此刻是唯一能重算的时机。
     */
    fun needsRehash(stored: String?): Boolean = (iterationsOf(stored) ?: 0) < DEFAULT_ITERATIONS

    // ------------------------------------------------------------- 内部

    private class Stored(val iterations: Int, val salt: ByteArray, val hash: ByteArray)

    private fun parse(stored: String?): Stored? {
        val raw = stored?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val parts = raw.split('$')
        if (parts.size != 5) return null
        if (parts[0] != SCHEME || parts[1] != DIGEST) return null
        val iterations = parts[2].toIntOrNull() ?: return null
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) return null
        val b64 = Base64.getDecoder()
        val salt = runCatching { b64.decode(parts[3]) }.getOrNull() ?: return null
        val hash = runCatching { b64.decode(parts[4]) }.getOrNull() ?: return null
        if (salt.size < 8 || hash.size < 16) return null
        return Stored(iterations, salt, hash)
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int, bytes: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, bytes * 8)
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * 失败尝试节流：窗口内累计 [maxFailures] 次失败后锁 [lockMs]，锁到期重新计数。
     *
     * 只记计数与时间戳，**不记**试过的密码。
     */
    class AttemptLimiter(
        private val maxFailures: Int = MAX_FAILURES,
        private val windowMs: Long = 5 * 60_000,
        private val lockMs: Long = LOCK_SECONDS * 1_000L,
    ) {

        private var failures = 0
        private var windowStartedAt = 0L
        private var lockedUntil = 0L

        /** 还要等多少毫秒才能再试；`0` = 没锁。 */
        fun lockedFor(now: Long): Long = if (lockedUntil > now) lockedUntil - now else 0

        /** 记一次失败；返回本次失败后是否进入锁定。 */
        fun failure(now: Long): Boolean {
            if (windowStartedAt == 0L || now - windowStartedAt > windowMs) {
                windowStartedAt = now
                failures = 0
            }
            failures++
            if (failures >= maxFailures) {
                failures = 0
                windowStartedAt = now
                lockedUntil = now + lockMs
                return true
            }
            return false
        }

        /** 成功后清零（不缩短已经生效的锁定）。 */
        fun success() {
            failures = 0
            windowStartedAt = 0L
        }
    }

    /**
     * 解锁会话：进入管理页后 [timeoutMs] 内不再问密码；超时/切后台（调用方决定）重新上锁。
     */
    class Session(private val timeoutMs: Long = 5 * 60_000) {

        @Volatile
        private var openedAt = 0L

        fun unlock(now: Long) {
            openedAt = now
        }

        fun isOpen(now: Long): Boolean = openedAt > 0L && now >= openedAt && now - openedAt < timeoutMs

        fun lock() {
            openedAt = 0L
        }
    }
}
