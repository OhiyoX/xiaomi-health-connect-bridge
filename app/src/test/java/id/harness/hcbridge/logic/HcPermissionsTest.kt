package id.harness.hcbridge.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HC 权限清单 —— 守的是 2026-10-28 实机踩到的坑：
 * `WRITE_SLEEP` / `WRITE_STEPS` / `WRITE_OXYGEN_SATURATION` 被系统回收，但同步前自检只看心率，
 * 于是睡眠/血氧的写入被 HC 拒成 `HealthConnectException`，状态文件却还写 `ok-no-new`。
 */
class HcPermissionsTest {

    @Test
    fun `全集里每一项都能说人话，不漏成裸常量`() {
        val raw = HcPermissions.used.filter { !Regex("^[\\u4e00-\\u9fa5A-Z\\d\\s]+$").matches(HcPermissions.label(it)) }
        assertTrue("有权限没配中文名：$raw", raw.isEmpty())
    }

    @Test
    fun `标签读写分得开`() {
        assertEquals("写睡眠", HcPermissions.label("android.permission.health.WRITE_SLEEP"))
        assertEquals("读睡眠", HcPermissions.label("android.permission.health.READ_SLEEP"))
        assertEquals("后台读", HcPermissions.label("android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"))
        assertEquals("读 30 天前历史", HcPermissions.label("android.permission.health.READ_HEALTH_DATA_HISTORY"))
        assertEquals("读运动", HcPermissions.label("android.permission.health.READ_EXERCISE"))
    }

    @Test
    fun `缺项按全集算，心率齐了但写睡眠缺也要报出来`() {
        val granted = setOf(HcPermissions.READ_HR, HcPermissions.WRITE_HR) +
            HcPermissions.used.filter { !it.endsWith("WRITE_SLEEP") && !it.endsWith("WRITE_STEPS") }.toSet()
        val missing = HcPermissions.missing(granted)
        assertEquals(
            listOf(
                "android.permission.health.WRITE_STEPS",
                "android.permission.health.WRITE_SLEEP",
            ),
            missing,
        )
        assertTrue(HcPermissions.missing(granted, HcPermissions.core).isEmpty())   // 心率这档还是齐的
    }

    @Test
    fun `描述超长时收口成 等 N 项`() {
        val m = HcPermissions.used.take(7)
        assertTrue(HcPermissions.describe(m, max = 4).endsWith("等 7 项"))
        assertEquals("读心率、写心率", HcPermissions.describe(listOf(HcPermissions.READ_HR, HcPermissions.WRITE_HR)))
    }
}
