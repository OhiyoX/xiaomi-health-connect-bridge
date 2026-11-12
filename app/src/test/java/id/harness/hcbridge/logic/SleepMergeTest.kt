package id.harness.hcbridge.logic

import id.harness.hcbridge.logic.SleepMerge.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 睡眠写 HC 的合并规则。数字取自 2026-10-21 实机取证（phone，HC 里 10-10…10-21 共 32 条 = 16 对同段双来源）：
 *
 * ```
 * 2026-10-20 02:33 → 07:33  com.mi.health            ← 小米自己写的
 * 2026-10-20 02:33 → 07:33  id.harness.hcbridge      ← 桥又写了一遍（重复）
 * 2026-10-19 06:33 → 07:22 / 10:35 / 11:24  hcbridge ← 云端修订起床时间，同晚堆了三条
 * ```
 */
class SleepMergeTest {

    private val own = "id.harness.hcbridge"
    private val mi = "com.mi.health"

    /** `at(2026,10,20,2,33)` → epoch 秒（Asia/Shanghai）。 */
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond()

    private fun incoming(start: Long, end: Long, title: String = "小米睡眠") =
        Session(start, end, origin = "", title = title)

    private fun foreign(start: Long, end: Long, id: String = "") =
        Session(start, end, origin = mi, clientRecordId = id.ifBlank { null })

    private fun mine(start: Long, end: Long, id: String) =
        Session(start, end, origin = own, recordId = id)

    private fun mineNoId(start: Long, end: Long) = Session(start, end, origin = own)

    // ------------------------------------------------------------ 跨来源

    @Test
    fun `小米已写过的那一晚不再写，并清掉我们过去的重复`() {
        // 10-20：我们 02:33→07:33，小米也是 02:33→07:33（同一段）
        val existing = listOf(
            mine(at(2026, 10, 20, 2, 33), at(2026, 10, 20, 7, 33), "hc-cloud-sleep-1792415580"),
            foreign(at(2026, 10, 20, 2, 33), at(2026, 10, 20, 7, 33))
        )
        val plan = SleepMerge.plan(existing, listOf(incoming(at(2026, 10, 20, 2, 33), at(2026, 10, 20, 7, 33))), own)

        assertEquals(0, plan.insert.size)
        assertEquals(1, plan.skippedForeign)
        assertEquals(listOf("hc-cloud-sleep-1792415580"), plan.deleteRecordIds)
    }

    @Test
    fun `小米的记录比我们短（只覆盖一段）也算重复`() {
        // 10-11 19:54：小米两段（27 分钟、74 分钟），我们也写了一段
        val existing = listOf(
            mine(at(2026, 10, 11, 19, 54), at(2026, 10, 11, 21, 8), "own-1"),
            foreign(at(2026, 10, 11, 19, 54), at(2026, 10, 11, 20, 21))
        )
        val plan = SleepMerge.plan(
            existing,
            listOf(incoming(at(2026, 10, 11, 19, 54), at(2026, 10, 11, 20, 21))),
            own
        )
        assertEquals(0, plan.insert.size)
        assertEquals(1, plan.skippedForeign)
        assertEquals(listOf("own-1"), plan.deleteRecordIds)
    }

    @Test
    fun `历史脏数据：own 被别的来源覆盖、又不在本批入参里，也要删`() {
        val existing = listOf(
            mine(at(2026, 10, 15, 0, 5), at(2026, 10, 15, 7, 24), "old-a"),
            foreign(at(2026, 10, 15, 0, 5), at(2026, 10, 15, 7, 24)),
            // 另一晚的 own，没有别的来源覆盖 → 不动
            mine(at(2026, 10, 14, 1, 30), at(2026, 10, 14, 7, 30), "keep-b")
        )
        val plan = SleepMerge.plan(existing, emptyList(), own)

        assertEquals(emptyList<Session>(), plan.insert)
        assertEquals(listOf("old-a"), plan.deleteRecordIds)
    }

    @Test
    fun `副机（没有别的来源）照旧写入`() {
        val plan = SleepMerge.plan(
            emptyList(),
            listOf(incoming(at(2026, 10, 20, 2, 33), at(2026, 10, 20, 7, 33))),
            own
        )
        assertEquals(1, plan.insert.size)
        assertTrue(plan.deleteRecordIds.isEmpty())
        assertEquals(0, plan.skippedForeign)
    }

    // ------------------------------------------------------------ 同来源修订

    @Test
    fun `区间一致就不重复写`() {
        val existing = listOf(mine(at(2026, 10, 20, 2, 33), at(2026, 10, 20, 7, 33), "own-x"))
        val plan = SleepMerge.plan(
            existing,
            listOf(incoming(at(2026, 10, 20, 2, 33), at(2026, 10, 20, 7, 33))),
            own
        )
        assertEquals(0, plan.insert.size)
        assertEquals(1, plan.unchanged)
        assertTrue(plan.deleteRecordIds.isEmpty())
    }

    @Test
    fun `起止漂移几十秒仍算同一段（clientRecordId 是按秒算的）`() {
        val existing = listOf(mine(at(2026, 10, 20, 2, 33), at(2026, 10, 20, 7, 33), "own-x"))
        val plan = SleepMerge.plan(
            existing,
            listOf(incoming(at(2026, 10, 20, 2, 33) + 30, at(2026, 10, 20, 7, 33) + 45)),
            own
        )
        assertEquals(0, plan.insert.size)
        assertEquals(1, plan.unchanged)
    }

    @Test
    fun `云端修订起床时间：删旧写新，同一晚只留一条`() {
        // 10-19：06:33→07:22 已被写过，云端改成 06:33→10:35
        val existing = listOf(
            mine(at(2026, 10, 19, 6, 33), at(2026, 10, 19, 7, 22), "own-0722"),
            mine(at(2026, 10, 19, 6, 33), at(2026, 10, 19, 10, 35), "own-1035")
        )
        val plan = SleepMerge.plan(
            existing,
            listOf(incoming(at(2026, 10, 19, 6, 33), at(2026, 10, 19, 11, 24))),
            own
        )
        assertEquals(1, plan.insert.size)
        assertEquals(at(2026, 10, 19, 11, 24), plan.insert[0].end)
        assertEquals(setOf("own-0722", "own-1035"), plan.deleteRecordIds.toSet())
    }

    // ------------------------------------------------------------ 入参自身

    @Test
    fun `同一批里同段写两遍：留更长的那段`() {
        val plan = SleepMerge.plan(
            emptyList(),
            listOf(
                incoming(at(2026, 10, 11, 19, 54), at(2026, 10, 11, 20, 21)),
                incoming(at(2026, 10, 11, 19, 54), at(2026, 10, 11, 21, 8))
            ),
            own
        )
        assertEquals(1, plan.insert.size)
        assertEquals(at(2026, 10, 11, 21, 8), plan.insert[0].end)
    }

    @Test
    fun `夜间睡眠 + 午睡不重叠：两段都写`() {
        val night = incoming(at(2026, 10, 20, 2, 33), at(2026, 10, 20, 7, 33))
        val nap = incoming(at(2026, 10, 20, 14, 0), at(2026, 10, 20, 14, 40))
        val plan = SleepMerge.plan(emptyList(), listOf(nap, night), own)

        assertEquals(2, plan.insert.size)
        // 输出按 start 升序
        assertEquals(night.start, plan.insert[0].start)
        assertEquals(nap.start, plan.insert[1].start)
    }

    @Test
    fun `重叠比例按较短那段算`() {
        val long = Session(at(2026, 10, 19, 6, 33), at(2026, 10, 19, 11, 24))
        val short = Session(at(2026, 10, 19, 6, 33), at(2026, 10, 19, 7, 22))
        assertEquals(1.0, SleepMerge.overlapRatio(long, short), 1e-9)
        // 不相交
        assertEquals(
            0.0,
            SleepMerge.overlapRatio(short, Session(at(2026, 10, 19, 14, 0), at(2026, 10, 19, 15, 0))),
            1e-9
        )
    }

    @Test
    fun `没有平台记录 id 的 own 记录当作已写过（删不掉，也不重写）`() {
        // 理论上不该出现（读回来的都有 metadata.id）；真出现时宁可少写一条，也不叠一条
        val existing = listOf(mineNoId(at(2026, 10, 20, 2, 33), at(2026, 10, 20, 7, 33)))
        val plan = SleepMerge.plan(
            existing,
            listOf(incoming(at(2026, 10, 20, 2, 33), at(2026, 10, 20, 7, 33))),
            own
        )
        assertEquals(0, plan.insert.size)
        assertEquals(1, plan.unchanged)
        assertTrue(plan.deleteRecordIds.isEmpty())
    }

    @Test
    fun `要删的是平台记录 id，不是空的 clientRecordId`() {
        // 2026-10-21 实测：我们写进去的睡眠读回来 clientRecordId 是空串（小米那条有）
        val existing = listOf(
            Session(
                at(2026, 10, 20, 2, 33), at(2026, 10, 20, 7, 33),
                origin = own, recordId = "2a4f-record", clientRecordId = ""
            ),
            foreign(at(2026, 10, 20, 2, 33), at(2026, 10, 20, 7, 33))
        )
        val plan = SleepMerge.plan(existing, emptyList(), own)
        assertEquals(listOf("2a4f-record"), plan.deleteRecordIds)
    }


    // ------------------------------------------------------------ 读的时候收敛（别人的记录删不掉）

    @Test
    fun `小米自己一晚三条：读的时候只留最长那条`() {
        // 10-19 实测：06:33→07:22 / 06:33→10:35 / 06:33→11:24（同一段被它自己写了三遍）
        val kept = SleepMerge.dedupeOverlaps(
            listOf(
                foreign(at(2026, 10, 19, 6, 33), at(2026, 10, 19, 7, 22)),
                foreign(at(2026, 10, 19, 6, 33), at(2026, 10, 19, 10, 35)),
                foreign(at(2026, 10, 19, 6, 33), at(2026, 10, 19, 11, 24))
            )
        )
        assertEquals(1, kept.size)
        assertEquals(at(2026, 10, 19, 11, 24), kept[0].end)
    }

    @Test
    fun `收敛不会吃掉真正的两段（夜间 + 午睡）`() {
        val kept = SleepMerge.dedupeOverlaps(
            listOf(
                foreign(at(2026, 10, 20, 2, 33), at(2026, 10, 20, 7, 33)),
                foreign(at(2026, 10, 20, 14, 0), at(2026, 10, 20, 14, 40))
            )
        )
        assertEquals(2, kept.size)
        // 输出按 start 升序
        assertEquals(at(2026, 10, 20, 2, 33), kept[0].start)
    }

    @Test
    fun `收敛只看重叠比例：短片段叠在长睡眠里算重复`() {
        // 10-12 22:28→23:57（Google Fit）叠在 22:28→07:22（小米）里
        val kept = SleepMerge.dedupeOverlaps(
            listOf(
                Session(at(2026, 10, 12, 22, 28), at(2026, 10, 12, 23, 57), origin = "com.google.android.apps.fitness"),
                foreign(at(2026, 10, 12, 22, 28), at(2026, 10, 13, 7, 22))
            )
        )
        assertEquals(1, kept.size)
        assertEquals("com.mi.health", kept[0].origin)
    }


    // ------------------------------------------------------------ 空夜体检（保完整性）

    @Test
    fun `空夜体检：已覆盖的夜不算缺，缺的按旧到新列出`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val today = java.time.LocalDate.of(2026, 10, 21)
        // 10-20 有（小米写的），10-19 有（我们写的），其余一个月空缺
        val existing = listOf(
            Session(at(2026, 10, 20, 2, 33), at(2026, 10, 20, 8, 32), origin = mi),
            Session(at(2026, 10, 19, 6, 33), at(2026, 10, 19, 11, 24), origin = own)
        )
        val missing = SleepMerge.missingNights(existing, zone, today, days = 3)
        // 只算最近 3 晚：10-20、10-19、10-18 ⇒ 缺 10-18；今天（10-21）不算缺
        assertEquals(listOf(java.time.LocalDate.of(2026, 10, 18)), missing)
    }

    @Test
    fun `空夜体检看的是起床那天，不是入睡那天`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val today = java.time.LocalDate.of(2026, 10, 21)
        // 10-19 22:28 睡到 10-20 07:22 ⇒ 归属 10-20
        val existing = listOf(Session(at(2026, 10, 19, 22, 28), at(2026, 10, 20, 7, 22), origin = mi))
        val missing = SleepMerge.missingNights(existing, zone, today, days = 1)
        assertEquals(emptyList<java.time.LocalDate>(), missing)
    }

    @Test
    fun `空夜体检：小米那份被删了，这一夜就会重新被看见`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val today = java.time.LocalDate.of(2026, 10, 21)
        // 库里只剩 10-18 那晚（我们写的），10-19/10-20 的都没了
        val existing = listOf(Session(at(2026, 10, 18, 2, 38), at(2026, 10, 18, 11, 54), origin = own))
        val missing = SleepMerge.missingNights(existing, zone, today, days = 3)
        assertEquals(
            listOf(
                java.time.LocalDate.of(2026, 10, 18).plusDays(1),
                java.time.LocalDate.of(2026, 10, 18).plusDays(2)
            ),
            missing
        )
    }

    // ---------------------------------------------- 分期（Google Health 判“睡眠数据不足”）

    private fun stage(fromH: Int, fromMi: Int, toH: Int, toMi: Int, type: Int) =
        id.harness.hcbridge.logic.SleepStages.Stage(
            at(2026, 10, 20, fromH, fromMi), at(2026, 10, 20, toH, toMi), type
        )

    @Test
    fun `外来记录只盖了一半时不能顶掉我们整晚（GF 把 670min 切成 193min 的真机事故）`() {
        val start = at(2026, 10, 26, 20, 16)
        val end = at(2026, 10, 27, 7, 26)          // 670 分钟
        val existing = listOf(
            Session(start, end, origin = own, recordId = "own-1"),
            // Google Fit 当晚只写了两小片（172 + 21 分钟）
            Session(start, at(2026, 10, 26, 23, 8), origin = "com.google.android.apps.fitness", recordId = "gf-1"),
            Session(at(2026, 10, 26, 23, 9), at(2026, 10, 26, 23, 29), origin = "com.google.android.apps.fitness", recordId = "gf-2"),
        )
        val plan = SleepMerge.plan(existing, emptyList(), own)
        assertTrue("自己的整晚被误删了：${plan.deleteRecordIds}", "own-1" !in plan.deleteRecordIds)
    }

    @Test
    fun `外来记录完整盖住整晚时仍然不重复写并删我们那份`() {
        val start = at(2026, 10, 19, 23, 23)
        val end = at(2026, 10, 20, 7, 24)
        val existing = listOf(
            Session(start, end, origin = own, recordId = "own-1"),
            Session(start, end, origin = mi, recordId = "mi-1"),
        )
        val plan = SleepMerge.plan(existing, listOf(Session(start, end, title = "小米睡眠")), own)
        assertEquals(listOf("own-1"), plan.deleteRecordIds)
        assertEquals(1, plan.skippedForeign)
    }

    @Test
    fun `覆盖比例按整段算（coverage 函数）`() {
        val a = Session(0, 670 * 60)
        val partial = listOf(Session(0, 193 * 60))
        val full = listOf(Session(0, 670 * 60))
        assertEquals(193.0 / 670.0, SleepMerge.coverage(a, partial), 1e-6)
        assertEquals(1.0, SleepMerge.coverage(a, full), 1e-6)
    }

    @Test
    fun `库里同一段没有分期时，删旧写新把分期补上`() {
        val start = at(2026, 10, 19, 23, 23)
        val end = at(2026, 10, 20, 7, 24)
        val existing = listOf(
            Session(start, end, origin = own, recordId = "rec-1")   // 2026-10-29 实测：我们的记录 has_stages=false
        )
        val incoming = listOf(
            Session(
                start, end, title = "小米睡眠",
                stages = listOf(
                    stage(23, 23, 23, 53, id.harness.hcbridge.logic.SleepStages.HC_LIGHT),
                    stage(23, 53, 0, 43, id.harness.hcbridge.logic.SleepStages.HC_DEEP),
                )
            )
        )
        val plan = SleepMerge.plan(existing, incoming, own)
        assertEquals(1, plan.rewriteStages)
        assertEquals(listOf("rec-1"), plan.deleteRecordIds)
        assertEquals(1, plan.insert.size)
        assertEquals(2, plan.insert.first().stages.size)
        assertEquals(1, plan.insert.first().stages.count { it.type == id.harness.hcbridge.logic.SleepStages.HC_DEEP })
    }

    @Test
    fun `已经有分期的同一段不重复写`() {
        val start = at(2026, 10, 19, 23, 23)
        val end = at(2026, 10, 20, 7, 24)
        val one = id.harness.hcbridge.logic.SleepStages.Stage(
            start, end, id.harness.hcbridge.logic.SleepStages.HC_LIGHT
        )
        val existing = listOf(Session(start, end, origin = own, recordId = "rec-1", stages = listOf(one)))
        val incoming = listOf(Session(start, end, title = "小米睡眠", stages = listOf(one)))
        val plan = SleepMerge.plan(existing, incoming, own)
        assertEquals(0, plan.rewriteStages)
        assertTrue(plan.insert.isEmpty())
        assertEquals(1, plan.unchanged)
    }

    @Test
    fun `别的来源写过一晚时仍然不写（哪怕我们手上有分期）`() {
        val start = at(2026, 10, 19, 23, 23)
        val end = at(2026, 10, 20, 7, 24)
        val existing = listOf(Session(start, end, origin = mi, recordId = "mi-1"))
        val incoming = listOf(
            Session(start, end, stages = listOf(stage(23, 23, 23, 53, id.harness.hcbridge.logic.SleepStages.HC_LIGHT)))
        )
        val plan = SleepMerge.plan(existing, incoming, own)
        assertEquals(1, plan.skippedForeign)
        assertTrue(plan.insert.isEmpty())
        assertEquals(0, plan.rewriteStages)
    }
}
