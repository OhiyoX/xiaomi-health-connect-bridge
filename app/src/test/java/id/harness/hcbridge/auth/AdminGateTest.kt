package id.harness.hcbridge.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `AdminGate` 的 JVM 合约：PBKDF2 往返、盐随机、坏哈希 fail closed、试错节流与会话超时。
 *
 * 低迭代数（[AdminGate.MIN_ITERATIONS]）只为让单测秒级跑完 —— 生产默认 120k，格式里带
 * 迭代数，所以老哈希在迭代数提高后照样能验。
 */
class AdminGateTest {

    private val fast = AdminGate.MIN_ITERATIONS

    @Test
    fun pbkdf2RoundTripAndWrongPassword() {
        val h = AdminGate.hash("correct horse", iterations = fast)
        assertTrue(h.startsWith("pbkdf2\$sha256\$$fast\$"))
        assertTrue(AdminGate.verify("correct horse", h))
        assertFalse(AdminGate.verify("wrong", h))
        assertFalse(AdminGate.verify("x", null))
        // 密码大小写/空白不折叠：只有原样才对
        assertFalse(AdminGate.verify("Correct horse", h))
        assertFalse(AdminGate.verify("correct horse ", h))
    }

    @Test
    fun saltMakesHashesDiffer() {
        assertNotEquals(AdminGate.hash("p", fast), AdminGate.hash("p", fast))
        assertTrue(AdminGate.verify("p", AdminGate.hash("p", fast)))
    }

    @Test
    fun defaultIterationsAreStrongEnough() {
        assertTrue(AdminGate.DEFAULT_ITERATIONS >= 100_000)
        assertTrue(AdminGate.hash("p", iterations = fast).split('$')[2].toInt() == fast)
    }

    @Test
    fun brokenStoredHashesNeverVerify() {
        val good = AdminGate.hash("p", fast)
        val salt = good.split('$')[3]
        val hash = good.split('$')[4]
        for (bad in listOf(
            "",
            "   ",
            "plain",
            "pbkdf2\$sha256\$0\$$salt\$$hash",            // 迭代数 0
            "pbkdf2\$sha256\$-1\$$salt\$$hash",
            "pbkdf2\$sha256\$99999999\$$salt\$$hash",     // 超上限：不能把 UI 卡死
            "pbkdf2\$sha1\$$fast\$$salt\$$hash",           // 算法不符
            "pbkdf2\$sha256\$" + fast + "\$" + salt,      // 缺字段
            "pbkdf2\$sha256\$" + fast + "\$@@@\$" + hash, // 坏 base64
            "pbkdf2\$sha256\$" + fast + "\$" + salt + "\$",
        )) {
            assertFalse("不该通过：$bad", AdminGate.verify("p", bad))
        }
    }

    @Test
    fun needsSetupOnlyUntilAPasswordIsStored() {
        val config = McpConfig(InMemoryStore())
        assertTrue(AdminGate.needsSetup(config))
        config.adminPasswordHash = AdminGate.hash("pw", fast)
        assertFalse(AdminGate.needsSetup(config))
        // 坏哈希等同未设置：否则设备主人会被永久锁在门外
        config.adminPasswordHash = "garbage"
        assertTrue(AdminGate.needsSetup(config))
        config.adminPasswordHash = null
        assertTrue(AdminGate.needsSetup(config))
    }

    @Test
    fun iterationsAreReadableAndWeakHashesWantARehash() {
        val weak = AdminGate.hash("p", fast)
        assertEquals(fast, AdminGate.iterationsOf(weak))
        assertTrue(AdminGate.needsRehash(weak))
        assertNull(AdminGate.iterationsOf(null))
        assertNull(AdminGate.iterationsOf("garbage"))
        assertTrue(AdminGate.needsRehash(null))
        assertTrue(AdminGate.needsRehash("garbage"))
        val strong = AdminGate.hash("p", AdminGate.DEFAULT_ITERATIONS)
        assertEquals(AdminGate.DEFAULT_ITERATIONS, AdminGate.iterationsOf(strong))
        assertFalse(AdminGate.needsRehash(strong))
        assertTrue(AdminGate.verify("p", strong))
    }

    @Test
    fun limiterLocksAfterRepeatedFailures() {
        val limiter = AdminGate.AttemptLimiter(maxFailures = 3, windowMs = 60_000, lockMs = 30_000)
        assertFalse(limiter.failure(1_000))
        assertFalse(limiter.failure(2_000))
        assertEquals(0L, limiter.lockedFor(2_000))
        assertTrue(limiter.failure(3_000))
        assertEquals(30_000L, limiter.lockedFor(3_000))
        assertEquals(1_000L, limiter.lockedFor(32_000))
        assertEquals(0L, limiter.lockedFor(33_000))
        // 锁定到期后重新计数（不是立刻又锁）
        assertFalse(limiter.failure(34_000))
    }

    @Test
    fun successClearsFailures() {
        val limiter = AdminGate.AttemptLimiter(maxFailures = 3, windowMs = 60_000, lockMs = 30_000)
        limiter.failure(1_000)
        limiter.failure(2_000)
        limiter.success()                            // 成功后计数清零（不是锁前白送一次）
        assertFalse(limiter.failure(3_000))
        assertFalse(limiter.failure(4_000))
        assertEquals(0L, limiter.lockedFor(4_000))
        assertTrue(limiter.failure(5_000))           // 清零后第 3 次才锁
    }

    @Test
    fun staleFailureWindowResets() {
        val limiter = AdminGate.AttemptLimiter(maxFailures = 2, windowMs = 10_000, lockMs = 30_000)
        assertFalse(limiter.failure(1_000))    // 第 1 次
        assertFalse(limiter.failure(20_000))   // 窗口过期 ⇒ 重新从 1 计
        assertTrue(limiter.failure(21_000))    // 同窗口内第 2 次 ⇒ 锁
        assertEquals(30_000L, limiter.lockedFor(21_000))
    }

    @Test
    fun sessionExpiresOnTimeout() {
        val session = AdminGate.Session(timeoutMs = 5_000)
        assertFalse(session.isOpen(0))
        session.unlock(10_000)
        assertTrue(session.isOpen(10_000))
        assertTrue(session.isOpen(14_999))
        assertFalse(session.isOpen(15_000))
        session.unlock(20_000)
        session.lock()
        assertFalse(session.isOpen(20_000))
    }

    @Test
    fun hashDoesNotLeakPasswordIntoItsText() {
        val h = AdminGate.hash("hunter2-长密码", fast)
        assertFalse(h.contains("hunter2"))
        assertFalse(h.contains("长密码"))
        assertEquals(5, h.split('$').size)
    }
}
