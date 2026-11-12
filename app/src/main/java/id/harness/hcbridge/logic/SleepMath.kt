package id.harness.hcbridge.logic

/**
 * 睡眠时间轴（actogram）的纯计算：时长、同源去重、按天分组、纵轴映射、tooltip 文案。
 *
 * 纵轴窗口固定 **18:00 → 次日 12:00**（用 36:00 表示次日 12:00）：
 * 一天一列，同一天的多段（夜间 + 午睡）共用该列、按各自真实起止时间上下排列。
 */
object SleepMath {

    const val DAY = 1440.0
    const val WIN_START = 18.0 * 60
    const val WIN_END = 12.0 * 60 + 1440

    /** 同源重复判据：重叠超过较短那段的这个比例，视为同一次睡眠被写了两遍。 */
    const val OVERLAP_DUP_RATIO = 0.5

    /** 图表默认显示的晚数。 */
    const val DEFAULT_DAYS = 7

    /** 一段睡眠：[label] 是归属日期（`MM-DD`），起止是当天分钟数（0..2879 表示跨天）。 */
    data class Seg(val label: String, val start: Double, val end: Double)

    /** 跨零点时长（分钟）；end<=start 视为跨天。 */
    fun duration(start: Double, end: Double): Double =
        if (end > start) end - start else end + DAY - start

    fun duration(seg: Seg): Double = duration(seg.start, seg.end)

    fun total(segs: List<Seg>): Double = segs.sumOf { duration(it) }

    /** 纵轴比例 0（18:00，顶部）… 1（次日 12:00，底部）。 */
    fun yFraction(minute: Double): Double {
        var m = minute
        if (m < WIN_START) m += DAY
        return ((m - WIN_START) / (WIN_END - WIN_START)).coerceIn(0.0, 1.0)
    }

    /** 两段重叠占较短那段的比例（0..1）。 */
    fun overlapRatio(a: Seg, b: Seg): Double {
        val overlap = minOf(a.end, b.end) - maxOf(a.start, b.start)
        if (overlap <= 0) return 0.0
        val shorter = minOf(duration(a), duration(b))
        return if (shorter <= 0.0) 0.0 else overlap / shorter
    }

    /**
     * 同一天内按区间重叠去重（同一来源被写两遍会造成区间交叉），保留更长的那段；
     * 真正的多段（夜间 + 午睡）时段不重叠，两段都留下。
     * 输出：按天（出现顺序）分组，组内按 start 升序。
     */
    fun dedupe(segs: List<Seg>, dupRatio: Double = OVERLAP_DUP_RATIO): List<Seg> {
        val out = ArrayList<Seg>(segs.size)
        for (day in segs.map { it.label }.distinct()) {
            val kept = ArrayList<Seg>()
            for (s in segs.filter { it.label == day }.sortedByDescending { duration(it) }) {
                if (kept.none { overlapRatio(it, s) > dupRatio }) kept.add(s)
            }
            kept.sortBy { it.start }
            out.addAll(kept)
        }
        return out
    }

    /** 只保留最近的 [days] 天（按出现顺序取末尾，与上游查询顺序一致）。 */
    fun recent(segs: List<Seg>, days: Int = DEFAULT_DAYS): List<Seg> {
        val keep = segs.map { it.label }.distinct().takeLast(days).toSet()
        return segs.filter { it.label in keep }
    }

    /** 按天分组（保持出现顺序）。 */
    fun byDay(segs: List<Seg>): LinkedHashMap<String, List<Seg>> {
        val m = LinkedHashMap<String, MutableList<Seg>>()
        for (s in segs) m.getOrPut(s.label) { ArrayList() }.add(s)
        return LinkedHashMap<String, List<Seg>>().also { out -> m.forEach { (k, v) -> out[k] = v.toList() } }
    }

    /** "1394" → "23:14" */
    fun clock(minute: Double): String {
        val m = ((minute % DAY) + DAY) % DAY
        return "%02d:%02d".format((m / 60).toInt(), (m % 60).toInt())
    }

    /** 图表副标题/tooltip 文案：单段给起止，多段给段数与总时长。 */
    fun tooltip(label: String, segs: List<Seg>): String {
        if (segs.isEmpty()) return label
        if (segs.size == 1) return "%s %s–%s".format(label, clock(segs[0].start), clock(segs[0].end))
        val total = total(segs)
        return "%s %d 段 · 共 %dh%02dm".format(label, segs.size, (total / 60).toInt(), (total % 60).toInt())
    }

    /** "9h16m" / "58m"（界面上"睡眠 x"的格式）。 */
    fun hhmm(minutes: Double): String {
        val m = minutes.toInt().coerceAtLeast(0)
        val h = m / 60
        val mm = m % 60
        return if (h > 0) "%dh%02dm".format(h, mm) else "%dm".format(mm)
    }
}
