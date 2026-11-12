package id.harness.hcbridge.logic

/**
 * 体成分趋势图的纯几何/文案计算（JVM 单测覆盖，见 BodyMathTest）。
 *
 * 为什么单独抽出来：体重是**稀疏**时间序列（5 年约 29 次称重），画图有三个容易画错的点：
 * 1. x 轴必须按**真实时间比例**，不能按等距下标（两次相隔半年会挤在一起，视觉上骗人）；
 * 2. 同一天多次称重只留最后一次（否则同一天两个点变竖线）；
 * 3. y 轴范围要给 min/max 留边，否则最高点会贴着卡片边被裁掉。
 */
object BodyMath {

    /** 一次测量。t 用 epoch 秒，kg 为体重。 */
    data class Point(val t: Long, val kg: Double, val fatPct: Double? = null, val bmi: Double? = null) {
        val day: String get() = Day.of(t)
    }

    /** 图的坐标范围（已含留边）。 */
    data class Bounds(val minKg: Double, val maxKg: Double, val minT: Long, val maxT: Long) {
        val spanKg: Double get() = (maxKg - minKg).coerceAtLeast(0.1)
        val spanT: Long get() = (maxT - minT).coerceAtLeast(1L)
    }

    object Day {
        /** 把 epoch 秒映射成 yyyy-MM-dd（用 UTC+8，健康数据都按本地日归档）。 */
        fun of(epochSec: Long): String = civilFromDays(Math.floorDiv(epochSec + 8 * 3600, 86400L))

        /** Howard Hinnant 的 civil_from_days（避免依赖 java.time 以保持纯 JVM 可测）。 */
        fun civilFromDays(z0: Long): String {
            val z = z0 + 719468
            val era = Math.floorDiv(z, 146097L)
            val doe = z - era * 146097
            val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
            val y = yoe + era * 400
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = doy - (153 * mp + 2) / 5 + 1
            val m = if (mp < 10) mp + 3 else mp - 9
            val year = if (m <= 2) y + 1 else y
            return String.format("%04d-%02d-%02d", year, m, d)
        }
    }

    /** 同一天多次称重只留**最后一次**（时间升序输出，最多保留 limit 条）。 */
    fun dedupeByDay(points: List<Point>, limit: Int = 0): List<Point> {
        val byDay = LinkedHashMap<String, Point>()
        for (p in points.sortedBy { it.t }) byDay[p.day] = p
        val list = byDay.values.toList()
        if (limit <= 0 || list.size <= limit) return list
        return list.takeLast(limit)
    }

    /** 计算坐标范围；上下各留 5% 余量（kg），时间两端不缩。 */
    fun bounds(points: List<Point>): Bounds? {
        if (points.isEmpty()) return null
        val minKg = points.minOf { it.kg }
        val maxKg = points.maxOf { it.kg }
        val pad = ((maxKg - minKg) * 0.05).coerceAtLeast(0.2)
        return Bounds(minKg - pad, maxKg + pad, points.minOf { it.t }, points.maxOf { it.t })
    }

    /** 归一化到 [0,1]：x 按真实时间比例，y 反向（画布 y 向下）。 */
    fun normX(t: Long, b: Bounds): Double = ((t - b.minT).toDouble() / b.spanT).coerceIn(0.0, 1.0)
    fun normY(kg: Double, b: Bounds): Double = 1.0 - ((kg - b.minKg) / b.spanKg).coerceIn(0.0, 1.0)

    /** 触摸吸附：按画布 x 比例找最近点，超过 maxDist 认为没选中。 */
    fun nearest(points: List<Point>, b: Bounds, fx: Double, maxDist: Double = 0.12): Point? {
        var best: Point? = null
        var bestD = Double.MAX_VALUE
        for (p in points) {
            val d = Math.abs(normX(p.t, b) - fx)
            if (d < bestD) { bestD = d; best = p }
        }
        return if (bestD <= maxDist) best else null
    }

    /** 卡片副文：BMI 20.7 · 体脂 14.3% · 09-06，缺项自动省略。 */
    fun summaryLine(p: Point, withDate: Boolean = true): String {
        val parts = ArrayList<String>(3)
        p.bmi?.let { parts.add(String.format("BMI %.1f", it)) }
        p.fatPct?.let { parts.add(String.format("体脂 %.1f%%", it)) }
        if (withDate) parts.add(p.day.substring(5))
        return if (parts.isEmpty()) "—" else parts.joinToString(" · ")
    }

    /** tooltip：09-06 · 64.9 kg · 20.7。 */
    fun tooltip(p: Point): String {
        val sb = StringBuilder()
        sb.append(p.day.substring(5)).append(" · ").append(String.format("%.1f kg", p.kg))
        p.bmi?.let { sb.append(" · ").append(String.format("%.1f", it)) }
        return sb.toString()
    }

    /** 区间变化：最新 − 最早（正数=增重）。 */
    fun change(points: List<Point>): Double? =
        if (points.size < 2) null else points.last().kg - points.first().kg
}
