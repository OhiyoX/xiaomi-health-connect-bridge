package id.harness.hcbridge.auth

/**
 * 固定窗口限流（计划 Task 5 / 全局约束「`/register` 10/h、`/token` 60/min、`/authorize` 30/min」）。
 *
 * 设计取舍：
 * - **固定窗口**（不是滑动/令牌桶）：实现只有一行判定，窗口边界最多放过一个窗口量的突发，
 *   对本机隧道场景够用，也不需要后台清线程。
 * - **桶数有上限**（[MAX_BUCKETS]）：桶键里含 `cf-connecting-ip`，不设上限等于让公网请求
 *   用伪造 IP 把内存撑爆。超限时先清过期，再按插入顺序丢最老的桶。
 * - 时间只来自注入的 [clock]，测试不需要 sleep。
 *
 * 本文件**不得** import 任何 `android.*`。
 */
class RateLimiter(private val clock: () -> Long = System::currentTimeMillis) {

    private class Bucket(val start: Long, var count: Int, val windowMs: Long)

    private val lock = Any()
    private val buckets = LinkedHashMap<String, Bucket>()

    /**
     * 记一次并判定：`true` = 放行，`false` = 超限（调用方回 429）。
     *
     * 时钟回拨（`now < start`）按新窗口处理，不会把人永久锁在外面。
     */
    fun take(key: String, max: Int, windowMs: Long): Boolean {
        if (max <= 0 || windowMs <= 0) return false
        val now = clock()
        synchronized(lock) {
            prune(now)
            val b = buckets[key]
            if (b == null || now < b.start || now - b.start >= windowMs) {
                put(key, Bucket(now, 1, windowMs))
                return true
            }
            if (b.count >= max) return false
            b.count++
            return true
        }
    }

    /** 测试观测点：当前桶数（确认有上限）。 */
    fun size(): Int = synchronized(lock) { buckets.size }

    /** 全清（配置切换/测试用）。 */
    fun clear() = synchronized(lock) { buckets.clear() }

    private fun put(key: String, b: Bucket) {
        if (!buckets.containsKey(key) && buckets.size >= MAX_BUCKETS) {
            val eldest = buckets.keys.firstOrNull()
            if (eldest != null) buckets.remove(eldest)
        }
        buckets[key] = b
    }

    private fun prune(now: Long) {
        val it = buckets.entries.iterator()
        while (it.hasNext()) {
            val b = it.next().value
            if (now < b.start || now - b.start >= b.windowMs) it.remove()
        }
    }

    companion object {
        /** 桶数上限；超了丢最老的桶（丢桶 = 那个键重新获得全额额度，可接受的上限代价）。 */
        const val MAX_BUCKETS = 512
    }
}
