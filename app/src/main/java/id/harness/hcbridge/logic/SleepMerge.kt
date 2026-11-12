package id.harness.hcbridge.logic

import kotlin.math.abs
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 「睡眠写进 HC 但不要写两遍」的合并规则（纯计算，可单测）。
 *
 * 背景（2026-10-21 实机取证）：小米运动健康（`com.mi.health`）自己就把睡眠写进 HC，
 * 桥再从云端搬一份 ⇒ **每一晚在 HC 里都是两条完全重叠的记录**（10-10…10-21 逐晚核对：32 条里
 * 16 对是「同一段、两个来源」）。步数早就有同类护栏（“HC 当天为 0 才补”），睡眠没有。
 * 另一类重复是**同源修订**：云端把起床时间改后（10-19 的 07:22→10:35→11:24），
 * `clientRecordId` 是按 bedtime 秒算的，秒一动就是新 id ⇒ 同一晚堆出三条。
 *
 * 规则（`own` = 本应用 `id.harness.hcbridge`）：
 *  1. 入参先合并：同一批里重叠超过 [DUP_RATIO] 的段视为同一段写了两遍，留更长的那段。
 *  2. 别的来源（非 own）已有与它重叠超过 [DUP_RATIO] 的记录 → **不写**（小米写过了），
 *     并顺手删掉我们过去写下的同区间记录（历史脏数据）。
 *  3. own 自己的记录：起止与之一致（差 ≤ [SAME_EDGE_TOLERANCE_S]）→ 不重复写；
 *     区间变了（云端修订）→ **删旧写新**，保证同一晚 own 只留一条。
 *  4. 顺带清理：own 里被别的来源覆盖、又不在这批入参里的记录，也删。
 *
 * 副机（secondary）同样走这里：平板没装小米运动健康，foreign 为空 ⇒ 照旧写入。
 */
object SleepMerge {

    /** 重叠占「较短那段」的比例超过它，就当作同一次睡眠被写了两遍。 */
    const val DUP_RATIO = 0.5

    /**
     * 外来记录要“顶掉”我们自己的整晚，必须真的盖住这么多比例。
     *
     * 2026-10-30 真机踩到：Google Fit 把一晚切成 172min + 21min 两片写进 HC，
     * 旧规则“有重叠就删我们自己那份”把整晚 670min 删成只剩 193min。
     */
    const val FOREIGN_EVICT_RATIO = 0.7

    /** 起止差在这个秒数内视为「同一段，没变」。 */
    const val SAME_EDGE_TOLERANCE_S = 120L

    /** 一段睡眠。[origin] 只有读回来的记录才有；[recordId] 是 HC 的平台记录 id（用来删）。 */
    data class Session(
        val start: Long,
        val end: Long,
        val origin: String = "",
        val recordId: String = "",
        val clientRecordId: String? = null,
        val title: String = "",
        /** 要写进去的分期（来自小米云端 `items`）；空 = 只写起止。 */
        val stages: List<SleepStages.Stage> = emptyList(),
    ) {
        val duration: Long get() = end - start

        /** 能不能删：必须有平台记录 id。 */
        val deletable: Boolean get() = recordId.isNotEmpty()
    }

    /** 合并结果。[insert] 要写的段；[deleteRecordIds] 要删的 own 记录（平台 id）。
     *  [rewriteStages] = 其中“同一段补分期”的重写条数（调试/验收用）。 */
    data class Plan(
        val insert: List<Session>,
        val deleteRecordIds: List<String>,
        val skippedForeign: Int,
        val unchanged: Int,
        val rewriteStages: Int = 0,
    )

    /** 重叠占较短那段的比例（0..1）；不重叠为 0。 */
    fun overlapRatio(a: Session, b: Session): Double {
        val overlap = minOf(a.end, b.end) - maxOf(a.start, b.start)
        if (overlap <= 0) return 0.0
        val shorter = minOf(a.duration, b.duration)
        return if (shorter <= 0L) 0.0 else overlap.toDouble() / shorter.toDouble()
    }

    /** [cover] 盖住了 [target] 多少比例（按 target 的时长算）。 */
    fun coverage(target: Session, cover: List<Session>): Double {
        if (target.duration <= 0L) return 0.0
        val secs = cover.sumOf { minOf(target.end, it.end) - maxOf(target.start, it.start) }.coerceAtLeast(0L)
        return secs.toDouble() / target.duration.toDouble()
    }

    /**
     * 只收敛重叠的同段（两两重叠 > [dupRatio] 只留最长那条）。
     *
     * 用于**读**：小米/Google Fit 自己也有“一晚多条”（实测 10-19 三条、10-20 两条），
     * 那些不是我们的记录、HC 不允许我们删，只能查询时收敛。会保序（按 start 升序返回）。
     */
    fun dedupeOverlaps(sessions: List<Session>, dupRatio: Double = DUP_RATIO): List<Session> {
        val kept = ArrayList<Session>(sessions.size)
        for (s in sessions.sortedByDescending { it.duration }) {
            if (kept.none { overlapRatio(it, s) > dupRatio }) kept.add(s)
        }
        return kept.sortedBy { it.start }
    }

    // ------------------------------------------------------------ 空夜体检（保完整性）

    /** 一段睡眠归到**起床那天**（与 NAS MCP 口径一致）。 */
    fun wakeDate(endEpoch: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochSecond(endEpoch).atZone(zone).toLocalDate()

    /**
     * 哪些夜晚在 HC 里**一条记录都没有**（不分来源）。
     *
     * 这是“删掉重复也不能让 hc-bridge 变残缺”的保险：小米那份如果被它自己/系统删了，
     * 或者小米不再写，下一次体检就能看出这个空夜，从云端补回来。
     *
     * [days] 默认 30（云端宽窗口才能回溯，更长靠回补）；今天不算缺（可能还没睡完）。
     */
    fun missingNights(
        sessions: List<Session>,
        zone: ZoneId,
        today: LocalDate,
        days: Int = 30
    ): List<LocalDate> {
        val covered = sessions.map { wakeDate(it.end, zone) }.toSet()
        val out = ArrayList<LocalDate>(days)
        for (i in days downTo 1) {
            val d = today.minusDays(i.toLong())
            if (d !in covered) out.add(d)
        }
        return out
    }

    fun plan(
        existing: List<Session>,
        incoming: List<Session>,
        ownOrigin: String,
        dupRatio: Double = DUP_RATIO,
        toleranceS: Long = SAME_EDGE_TOLERANCE_S,
    ): Plan {
        // 1) 同一批去重：长段优先，短的重叠段丢掉
        val wanted = ArrayList<Session>(incoming.size)
        for (s in incoming.sortedByDescending { it.duration }) {
            if (wanted.none { overlapRatio(it, s) > dupRatio }) wanted.add(s)
        }
        wanted.sortBy { it.start }

        val mine = existing.filter { it.origin == ownOrigin }
        val foreign = existing.filter { it.origin != ownOrigin }

        val insert = ArrayList<Session>()
        val delete = LinkedHashSet<String>()
        var skipped = 0
        var unchanged = 0
        var rewrites = 0

        // 2) 历史脏数据：own 与别的来源重叠的记录先标记删除（它们就是重复项）
        //    但只有“外来记录真的盖住了这一段”才删（见 FOREIGN_EVICT_RATIO）。
        for (m in mine) {
            if (!m.deletable) continue
            val covering = foreign.filter { overlapRatio(it, m) > dupRatio }
            if (covering.isNotEmpty() && coverage(m, covering) >= FOREIGN_EVICT_RATIO) {
                delete.add(m.recordId)
            }
        }

        for (s in wanted) {
            // 这一步不再考虑上面已标记删除的 own 记录
            val mineAlive = mine.filter { it.recordId.isEmpty() || it.recordId !in delete }

            val foreignHere = foreign.filter { overlapRatio(it, s) > dupRatio }
            if (foreignHere.isNotEmpty() && coverage(s, foreignHere) >= FOREIGN_EVICT_RATIO) {
                // 别的来源已经完整写过这一段：不写，并把我们过去的同段删掉
                skipped++
                mineAlive.filter { overlapRatio(it, s) > dupRatio && it.deletable }
                    .forEach { delete.add(it.recordId) }
                continue
            }

            val overlapping = mineAlive.filter { overlapRatio(it, s) > dupRatio }
            val same = overlapping.firstOrNull {
                abs(it.start - s.start) <= toleranceS && abs(it.end - s.end) <= toleranceS
            }
            if (same != null) {
                // 同一段、但库里那份**没有分期**（Google Health 会判“睡眠数据不足”）→ 删旧写新把分期补上
                if (s.stages.isNotEmpty() && same.stages.isEmpty() && same.deletable) {
                    delete.add(same.recordId)
                    insert.add(s)
                    rewrites++
                    continue
                }
                unchanged++
                // 同一晚 own 只留一条：多出来的（旧版本残留）删掉
                overlapping.filter { it !== same && it.deletable }
                    .forEach { delete.add(it.recordId) }
                continue
            }
            overlapping.filter { it.deletable }.forEach { delete.add(it.recordId) }
            insert.add(s)
        }

        return Plan(insert, delete.toList(), skipped, unchanged, rewrites)
    }
}
