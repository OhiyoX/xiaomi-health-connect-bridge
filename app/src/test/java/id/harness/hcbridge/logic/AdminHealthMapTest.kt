package id.harness.hcbridge.logic

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * `AdminHealthMap` 的口径测试：HcQuery 的 JSON → 管理页的覆盖/睡眠/指标。
 *
 * 守的回归：
 * - 睡眠**按起床那天**归日（跨零点不算两天），一天多段先合计；
 * - 窗口内没有样本的日子要显式标 `missing`（否则"没同步上来"看起来像"睡得好"）；
 * - 覆盖只用近 30 天的 `records_30d`（**不做总行数求和**），来源/天数/最近写入照搬；
 * - 指标窗口逐日对齐、缺数据是 `null`、睡眠分钟换成小时。
 */
class AdminHealthMapTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val now: Long = LocalDateTime.of(2026, 11, 3, 8, 0).atZone(zone).toInstant().toEpochMilli()

    private fun sessions(vararg rows: JSONObject): JSONObject =
        JSONObject().put("count", rows.size).put("sessions", JSONArray().apply { rows.forEach { put(it) } })

    private fun session(
        start: String,
        end: String,
        minutes: Double,
        awake: Long? = null,
        origin: String = "com.mi.health",
        hasStages: Boolean = false,
    ): JSONObject = JSONObject()
        .put("start", start)
        .put("end", end)
        .put("minutes", minutes)
        .put("origin", origin)
        .put("has_stages", hasStages)
        .apply { if (awake != null) put("awake_minutes", awake) }

    // ---------------------------------------------------------------- 睡眠

    @Test
    fun sleepIsGroupedByWakeDay() {
        val rows = AdminHealthMap.sleep(
            sessions(
                session("2026-11-02 23:00:00", "2026-11-03 07:00:00", 480.0),
            ),
            days = 3,
            nowMs = now,
            zone = zone,
        )
        assertEquals(3, rows.size)
        val today = rows[0]
        assertEquals("2026-11-03", today.date)
        assertEquals("23:00", today.bed)
        assertEquals("07:00", today.wake)
        assertEquals(8.0, today.hours!!, 1e-9)
        assertTrue("8 小时不短也不长", today.flags.isEmpty())
        assertEquals("com.mi.health", today.source)
        assertEquals("无分期", today.note)

        assertEquals("2026-11-02", rows[1].date)
        assertEquals(listOf("missing"), rows[1].flags)
        assertNull(rows[1].hours)
    }

    @Test
    fun multipleSegmentsOnSameWakeDayAreSummed() {
        val rows = AdminHealthMap.sleep(
            sessions(
                session("2026-11-02 23:00:00", "2026-11-03 02:00:00", 180.0),
                session("2026-11-03 02:30:00", "2026-11-03 05:00:00", 150.0),
            ),
            days = 1,
            nowMs = now,
            zone = zone,
        )
        val row = rows.single()
        assertEquals(5.5, row.hours!!, 1e-9)
        assertEquals("23:00", row.bed)
        assertEquals("05:00", row.wake)
        assertTrue(row.note.contains("2 段合计"))
        assertTrue(row.flags.contains("short"))
    }

    @Test
    fun lateBedAndFragmentedFlags() {
        val rows = AdminHealthMap.sleep(
            sessions(session("2026-11-03 02:00:00", "2026-11-03 07:00:00", 300.0, awake = 100)),
            days = 1,
            nowMs = now,
            zone = zone,
        )
        val row = rows.single()
        assertTrue("02:00 入睡算晚", row.flags.contains("late_bed"))
        assertTrue("清醒 100 分钟算易醒", row.flags.contains("fragile"))
        assertTrue("清醒 100 分钟算碎片化", row.flags.contains("fragmented"))
        assertTrue("5 小时算偏短", row.flags.contains("short"))
    }

    @Test
    fun longSleepIsFlagged() {
        val rows = AdminHealthMap.sleep(
            sessions(session("2026-11-02 21:00:00", "2026-11-03 09:30:00", 750.0)),
            days = 1,
            nowMs = now,
            zone = zone,
        )
        assertTrue(rows.single().flags.contains("long"))
    }

    @Test
    fun badSessionIsSkippedNotCrashing() {
        val rows = AdminHealthMap.sleep(
            sessions(
                JSONObject().put("start", "").put("end", "").put("minutes", 30.0),
                session("2026-11-02 23:00:00", "2026-11-03 07:00:00", 480.0),
            ),
            days = 1,
            nowMs = now,
            zone = zone,
        )
        assertEquals(1, rows.size)
        assertEquals(8.0, rows.single().hours!!, 1e-9)
    }

    @Test
    fun nullSleepJsonGivesMissingDays() {
        val rows = AdminHealthMap.sleep(null, days = 2, nowMs = now, zone = zone)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.flags == listOf("missing") })
    }

    // ---------------------------------------------------------------- 覆盖

    @Test
    fun coverageUsesRecords30dAndKeepsUnknownTypes() {
        val json = JSONObject()
            .put(
                "heart_rate",
                JSONObject()
                    .put("first", "2024-01-01 00:00:00")
                    .put("last", "2026-11-03 07:00:00")
                    .put("records_30d", 12_345)
                    .put("days_30d", 15)
                    .put("sources", JSONArray().put("com.mi.health").put("id.harness.hcbridge")),
            )
            .put(
                "sleep",
                JSONObject()
                    .put("first", "")
                    .put("last", "")
                    .put("records_30d", 0)
                    .put("days_30d", 0)
                    .put("sources", JSONArray()),
            )
            .put(
                "future_type",
                JSONObject()
                    .put("first", "")
                    .put("last", "2026-11-02 23:00:00")
                    .put("records_30d", 7)
                    .put("days_30d", 3)
                    .put("sources", JSONArray().put("x")),
            )

        val rows = AdminHealthMap.coverage(json, zone)
        assertEquals("已知类型按固定顺序 + 未知类型排最后", 3, rows.size)
        val hr = rows.first { it.type == "heart_rate" }
        assertEquals("心率", hr.label)
        assertEquals(12_345L, hr.rows)
        assertEquals(15, hr.days30)
        assertEquals(2, hr.sources.size)
        assertEquals(
            LocalDateTime.of(2026, 11, 3, 7, 0).atZone(zone).toInstant().toEpochMilli(),
            hr.lastAt,
        )
        assertNull("空 last ⇒ null（页面显示 —）", rows.first { it.type == "sleep" }.lastAt)
        assertEquals("future_type", rows.last().type)
        assertEquals("future_type", rows.last().label)
    }

    @Test
    fun coverageNullJsonIsEmpty() {
        assertTrue(AdminHealthMap.coverage(null, zone).isEmpty())
    }

    // ---------------------------------------------------------------- 指标

    @Test
    fun metricsAlignToWindowAndConvertSleepToHours() {
        val steps = JSONObject().put(
            "series",
            JSONArray()
                .put(JSONObject().put("date", "2026-11-01").put("value", 8000))
                .put(JSONObject().put("date", "2026-11-02").put("value", JSONObject.NULL))
                .put(JSONObject().put("date", "2026-11-03").put("value", 9842)),
        )
        val heart = JSONObject().put(
            "series",
            JSONArray()
                .put(JSONObject().put("date", "2026-11-02").put("value", 70))
                .put(JSONObject().put("date", "2026-11-03").put("value", 68)),
        )
        val sleepMin = JSONObject().put(
            "series",
            JSONArray()
                .put(JSONObject().put("date", "2026-11-02").put("value", 450))
                .put(JSONObject().put("date", "2026-11-03").put("value", 444)),
        )

        val m = AdminHealthMap.metrics(steps, heart, sleepMin, days = 3, nowMs = now, zone = zone)
        assertEquals(3, m.days)

        val s = m.get("steps")!!
        assertEquals(listOf(8000.0, null, 9842.0), s.series)
        assertEquals(9842.0, s.latest!!, 1e-9)
        assertEquals(8921.0, s.avg!!, 1e-9)
        assertEquals("步", s.unit)

        val h = m.get("heart_rate")!!
        assertEquals(listOf(null, 70.0, 68.0), h.series)
        assertEquals(68.0, h.latest!!, 1e-9)

        val sl = m.get("sleep")!!
        assertEquals(listOf(null, 7.5, 7.4), sl.series)
        assertEquals(7.4, sl.latest!!, 1e-9)
        assertEquals("h", sl.unit)
        assertEquals(1, sl.decimals)
    }

    @Test
    fun missingMetricJsonGivesNullSeries() {
        val m = AdminHealthMap.metrics(null, null, null, days = 2, nowMs = now, zone = zone)
        for (key in listOf("steps", "heart_rate", "sleep")) {
            val e = m.get(key)!!
            assertNull(e.latest)
            assertNull(e.avg)
            assertEquals(listOf(null, null), e.series)
        }
    }

    @Test
    fun instantParsingIsStrict() {
        assertEquals(
            Instant.parse("2026-11-03T07:00:00Z").toEpochMilli(),
            AdminHealthMap.parseTs("2026-11-03 07:00:00", zone),
        )
        assertNull(AdminHealthMap.parseTs("", zone))
        assertNull(AdminHealthMap.parseTs("2026-11-03T07:00:00Z", zone))
        assertNull(AdminHealthMap.parseTs(null, zone))
        assertEquals(LocalDate.of(2026, 11, 3), LocalDate.ofInstant(java.time.Instant.ofEpochMilli(now), zone))
    }
}
