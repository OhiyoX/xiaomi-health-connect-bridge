package id.harness.hcbridge.logic

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

/**
 * 副机拉回的 JSON → 待写 HC 的样本（纯解析）。
 * 这段逻辑原来埋在网络与 DB 之间、没有测试；这些用例把"服务端给什么形状"固定下来。
 */
class SecondaryPullTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private fun json(s: String) = JSONObject(s)

    @Test
    fun `epochOf 解析服务端时间格式`() {
        // 2026-10-17 23:14:00 +08:00 = 1792250040
        assertEquals(1792250040L, SecondaryPull.epochOf("2026-10-17 23:14:00", zone))
        assertEquals(0L, SecondaryPull.epochOf("2026-10-17T23:14:00", zone))   // ISO 不带 T 解析，服务端也不该给
        assertEquals(0L, SecondaryPull.epochOf("", zone))
    }

    @Test
    fun `心率样本 优先 epoch 其次 at 且过滤异常值`() {
        val j = json(
            """{"samples":[
                 {"epoch":1792250040,"bpm":72},
                 {"at":"2026-10-17 23:15:00","bpm":80},
                 {"epoch":1792250160,"bpm":5},
                 {"epoch":1792250220,"bpm":300},
                 {"epoch":0,"bpm":70}
               ]}"""
        )
        val out = SecondaryPull.heartRate(j, zone)
        assertEquals(2, out.size)
        assertEquals(1792250040L to 72L, out[0])
        assertEquals(1792250100L to 80L, out[1])
    }

    @Test
    fun `血氧支持 percent 与 spo2 两种键 且只收 50到100`() {
        val j = json(
            """{"samples":[
                 {"epoch":1000,"percent":96.0},
                 {"epoch":1060,"spo2":98},
                 {"epoch":1120,"percent":30.0},
                 {"epoch":1180,"percent":101.0}
               ]}"""
        )
        val out = SecondaryPull.spo2(j, zone)
        assertEquals(listOf(1000L to 96.0, 1060L to 98.0), out)
    }

    @Test
    fun `睡眠段 end 必须大于 start 缺标题用兜底`() {
        val j = json(
            """{"sessions":[
                 {"start":"2026-10-17 22:28:00","end":"2026-10-18 07:22:00"},
                 {"start":"2026-10-18 08:00:00","end":"2026-10-18 07:00:00"},
                 {"start":"垃圾","end":"2026-10-18 07:00:00"},
                 {"start":"2026-10-18 13:30:00","end":"2026-10-18 14:30:00","title":"午睡"}
               ]}"""
        )
        val out = SecondaryPull.sleepSessions(j, zone)
        assertEquals(2, out.size)
        assertEquals("小米手环（服务端）", out[0].third)
        assertEquals("午睡", out[1].third)
        assertEquals(534L * 60, out[0].second - out[0].first)
    }

    @Test
    fun `按天步数 跳过空日期与零值 kcal 取 0_7 每步`() {
        val j = json(
            """{"series":[
                 {"date":"2026-10-18","value":2398},
                 {"date":"","value":100},
                 {"date":"2026-10-17","value":0}
               ]}"""
        )
        val out = SecondaryPull.dailySteps(j, zone)
        assertEquals(1, out.size)
        assertEquals(2398L, out[0].second)
        assertEquals(2398 * 0.7, out[0].third, 0.001)
    }

    @Test
    fun `缺字段的响应返回空而不是抛异常`() {
        assertEquals(emptyList<Any>(), SecondaryPull.heartRate(json("{}"), zone))
        assertEquals(emptyList<Any>(), SecondaryPull.spo2(json("""{"samples":null}"""), zone))
        assertEquals(emptyList<Any>(), SecondaryPull.sleepSessions(json("{}"), zone))
        assertEquals(emptyList<Any>(), SecondaryPull.dailySteps(json("{}"), zone))
    }
}
