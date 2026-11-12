package id.harness.hcbridge.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上游（小米运动健康）是否卡住 —— 守的是「桥傻等」这个回归：
 * 2026-10-28 业主反馈：小米运动健康没同步的时候，桥只会报 `ok-no-new`，看着像正常，其实一条数据都没进来。
 *
 * 阈值都是「该有却没有」的保守值，判错的代价是：要么白催（打扰），要么漏催（继续傻等）。
 */
class UpstreamFreshnessTest {

    private val now = 1_793_164_800L          // 2026-10-28 12:00 +08
    private val min = 60L

    /** 一切正常：各个类型都是刚更新的。 */
    private fun freshProbe(hour: Int = 12) = UpstreamFreshness.Probe(
        nowS = now, hour = hour,
        lastHrTs = now - 10 * min,
        lastStepsTs = now - 20 * min,
        lastSpo2Ts = now - 8 * 3600,
        lastNightCovered = true,
        cloudRows = 120,
    )

    @Test
    fun `全都新鲜时不催`() {
        val v = UpstreamFreshness.assess(freshProbe())
        assertTrue(v.fresh)
        assertFalse(v.needKick)
        assertEquals("上游新鲜", v.note)
    }

    @Test
    fun `10 点后还缺昨晚就算缺睡眠`() {
        val v = UpstreamFreshness.assess(freshProbe(hour = 10).copy(lastNightCovered = false))
        assertEquals(listOf(UpstreamFreshness.SLEEP), v.stale)
        assertTrue(v.needKick)
    }

    @Test
    fun `9 点前缺昨晚不判缺（可能还在睡）`() {
        val v = UpstreamFreshness.assess(freshProbe(hour = 7).copy(lastNightCovered = false))
        assertTrue(v.fresh)
    }

    @Test
    fun `心率超过 90 分钟没新采样 = 过期`() {
        val v = UpstreamFreshness.assess(freshProbe().copy(lastHrTs = now - 91 * min))
        assertEquals(listOf(UpstreamFreshness.HEART_RATE), v.stale)
    }

    @Test
    fun `心率刚好 90 分钟还不算`() {
        val v = UpstreamFreshness.assess(freshProbe().copy(lastHrTs = now - 90 * min))
        assertTrue(v.fresh)
    }

    @Test
    fun `夜里不按心率步数判过期（手环离腕是正常的）`() {
        val p = freshProbe(hour = 3).copy(lastHrTs = now - 5 * 3600, lastStepsTs = now - 8 * 3600)
        assertTrue(UpstreamFreshness.assess(p).fresh)
    }

    @Test
    fun `步数 4 小时没动才算过期（成批上传，坐着不动是常态）`() {
        val v = UpstreamFreshness.assess(freshProbe().copy(lastStepsTs = now - 4 * 3600 - 60))
        assertEquals(listOf(UpstreamFreshness.STEPS), v.stale)
    }

    @Test
    fun `只有步数或血氧过期时不发提醒（写状态就够了）`() {
        assertFalse(UpstreamFreshness.notifyWorthy(listOf(UpstreamFreshness.STEPS, UpstreamFreshness.SPO2)))
        assertTrue(UpstreamFreshness.notifyWorthy(listOf(UpstreamFreshness.STEPS, UpstreamFreshness.HEART_RATE)))
        assertTrue(UpstreamFreshness.notifyWorthy(listOf(UpstreamFreshness.SLEEP)))
        assertTrue(UpstreamFreshness.notifyWorthy(listOf(UpstreamFreshness.CLOUD_EMPTY)))
    }

    @Test
    fun `停在多久前说人话`() {
        assertEquals("45 分钟", UpstreamFreshness.ageText(45 * 60))
        assertEquals("3 小时", UpstreamFreshness.ageText(3 * 3600))
        assertEquals("2 天", UpstreamFreshness.ageText(2 * 86400))
    }

    @Test
    fun `步数 3 小时没动不再算过期（旧阀值误报过）`() {
        val v = UpstreamFreshness.assess(freshProbe().copy(lastStepsTs = now - 3 * 3600 - 60))
        assertTrue(v.fresh)
    }

    @Test
    fun `云端整窗为空（白天）就是上游没同步`() {
        val v = UpstreamFreshness.assess(freshProbe().copy(cloudRows = 0))
        assertTrue(UpstreamFreshness.CLOUD_EMPTY in v.stale)
        assertTrue(v.needKick)
    }

    @Test
    fun `夜里云端为空不判（人没戴手环）`() {
        val v = UpstreamFreshness.assess(freshProbe(hour = 2).copy(cloudRows = 0))
        assertTrue(v.fresh)
    }

    @Test
    fun `45 分钟内刚催过就不重复催`() {
        val p = freshProbe().copy(lastHrTs = now - 3 * 3600)
        val v = UpstreamFreshness.assess(p, lastKickTs = now - 44 * min)
        assertTrue(UpstreamFreshness.HEART_RATE in v.stale)   // 还是过期…
        assertFalse(v.needKick)                               // …但先别催
        assertTrue(v.note.contains("等它上传"))
    }

    @Test
    fun `超过 45 分钟可以再催一次`() {
        val p = freshProbe().copy(lastHrTs = now - 3 * 3600)
        assertTrue(UpstreamFreshness.assess(p, lastKickTs = now - 46 * min).needKick)
    }

    @Test
    fun `从没催过（lastKickTs=0）时过第一批就催`() {
        val p = freshProbe().copy(lastNightCovered = false)
        assertTrue(UpstreamFreshness.assess(p, lastKickTs = 0).needKick)
    }

    @Test
    fun `多个原因同时过期时都报出来`() {
        val p = freshProbe(hour = 15).copy(
            lastNightCovered = false,
            lastHrTs = now - 4 * 3600,
            lastStepsTs = now - 6 * 3600,
            lastSpo2Ts = now - 48 * 3600,
            cloudRows = 0,
        )
        val v = UpstreamFreshness.assess(p)
        assertEquals(
            listOf(
                UpstreamFreshness.SLEEP, UpstreamFreshness.HEART_RATE,
                UpstreamFreshness.STEPS, UpstreamFreshness.SPO2, UpstreamFreshness.CLOUD_EMPTY,
            ),
            v.stale,
        )
        assertEquals("睡眠、心率、步数、血氧、云端整段没数据", UpstreamFreshness.labels(v.stale))
    }

    @Test
    fun `从没有过数据（ts=0）不按“过期”算，只有整窗空时才催`() {
        val p = UpstreamFreshness.Probe(nowS = now, hour = 12, cloudRows = 5)
        assertTrue(UpstreamFreshness.assess(p).fresh)
    }
}
