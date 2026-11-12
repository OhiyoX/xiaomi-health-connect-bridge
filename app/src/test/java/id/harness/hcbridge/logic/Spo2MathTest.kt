package id.harness.hcbridge.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 血氧降采样与判据：竖条只在桶内跨度 ≥1% 时画；只有真正低于 90% 才标红。 */class Spo2MathTest {

    private val flat = (0 until 120).map { 96.0 + (it % 2) * 0.2 }   // 实机形状：桶内几乎没跨度
    private val spiky = listOf(98.0, 90.0, 97.0, 99.0, 91.0, 98.0)   // 模拟器/稀疏数据形状

    @Test
    fun `空数据不分桶`() {
        assertTrue(Spo2Math.bucketize(emptyList()).isEmpty())
    }

    @Test
    fun `分桶保留 min 与 max 不漏掉点`() {
        val buckets = Spo2Math.bucketize(spiky, target = 2)
        assertEquals(2, buckets.size)
        buckets.forEach { b ->
            assertTrue(b.min <= b.mean)
            assertTrue(b.mean <= b.max)
        }
        assertEquals(90.0, buckets.minOf { it.min }, 0.001)
        assertEquals(99.0, buckets.maxOf { it.max }, 0.001)
    }

    @Test
    fun `扁平数据的桶不画竖条`() {
        val buckets = Spo2Math.bucketize(flat, target = 30)
        assertFalse(buckets.any { Spo2Math.hasBand(it) })
    }

    @Test
    fun `跨度阈值是 1 个百分点`() {
        assertFalse(Spo2Math.hasBand(Spo2Math.Bucket(96.0, 96.99, 96.5)))
        assertTrue(Spo2Math.hasBand(Spo2Math.Bucket(96.0, 97.0, 96.5)))
    }

    @Test
    fun `掉点判据是低于 90 而不是小于等于`() {
        assertTrue(Spo2Math.isDip(Spo2Math.Bucket(89.9, 91.0, 90.2)))
        assertFalse(Spo2Math.isDip(Spo2Math.Bucket(90.0, 95.0, 92.0)))
    }

    @Test
    fun `选中标签 有跨度给区间 没跨度给单值`() {
        assertEquals("90–98%", Spo2Math.label(Spo2Math.Bucket(90.0, 98.0, 94.0)))
        assertEquals("96%", Spo2Math.label(Spo2Math.Bucket(95.9, 96.1, 96.0)))
    }

    @Test
    fun `纵轴 88 在底 100 在顶`() {
        assertEquals(0.0, Spo2Math.yFraction(88.0), 0.001)
        assertEquals(1.0, Spo2Math.yFraction(100.0), 0.001)
        assertEquals(0.0, Spo2Math.yFraction(70.0), 0.001)      // 越低越靠底，不越界
        assertEquals(0.167, Spo2Math.yFraction(90.0), 0.002)    // 90% 阈值线位置
    }

    @Test
    fun `中位基线`() {
        assertEquals(95.0, Spo2Math.baseline(emptyList()), 0.001)
        assertEquals(97.0, Spo2Math.baseline(listOf(98.0, 96.0, 97.0)), 0.001)
    }
}
