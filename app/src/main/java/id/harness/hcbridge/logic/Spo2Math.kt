package id.harness.hcbridge.logic

/**
 * 血氧 24h 视图的纯计算：降采样（min–max decimation）、竖条/掉点判据、标签文案。
 *
 * 降采样保留每桶的 **min/max/mean**：临床关心短时掉点，均值会把尖峰吃掉；
 * 但"桶内跨度 <1%"时画竖条只会退化成 2px 短线（实机观感 vs 稀疏模拟器数据不一致那次），
 * 因此竖条只在 [hasBand] 为真时绘制。
 */
object Spo2Math {

    data class Bucket(val min: Double, val max: Double, val mean: Double)

    /** 目标桶数（点阵密度）。 */
    const val TARGET_BUCKETS = 60

    /** 桶内跨度达到它就画 min–max 竖条。 */
    const val BAND_MIN_SPAN = 1.0

    /** 低于它算"掉点"（ODI3 语义；只把真正低于 90% 的桶标红）。 */
    const val DIP_BELOW = 90.0

    /** 纵轴范围 88%–100%，90% 阈值线的位置由它决定。 */
    const val AXIS_MIN = 88.0
    const val AXIS_MAX = 100.0

    /** 中位基线（临床用静息/夜间中位比均值稳），空数据回落 95%。 */
    fun baseline(raw: List<Double>): Double =
        if (raw.isEmpty()) 95.0 else raw.sorted()[raw.size / 2]

    fun bucketize(raw: List<Double>, target: Int = TARGET_BUCKETS): List<Bucket> {
        if (raw.isEmpty()) return emptyList()
        val per = raw.size.toDouble() / target
        val out = ArrayList<Bucket>(target)
        for (i in 0 until target) {
            val from = (i * per).toInt().coerceIn(0, raw.size - 1)
            val to = ((i + 1) * per).toInt().coerceIn(from + 1, raw.size)
            val slice = raw.subList(from, to)
            out.add(Bucket(slice.min(), slice.max(), slice.average()))
        }
        return out
    }

    fun hasBand(b: Bucket): Boolean = b.max - b.min >= BAND_MIN_SPAN

    fun isDip(b: Bucket): Boolean = b.min < DIP_BELOW

    /** 选中标签：有跨度给区间，否则给单值。 */
    fun label(b: Bucket): String =
        if (hasBand(b)) "%.0f–%.0f%%".format(b.min, b.max) else "%.0f%%".format(b.mean)

    /** 数值比例 0（88%，底部）… 1（100%，顶部）；View 侧再用 `height - ratio` 换算成 y。 */
    fun yFraction(v: Double): Double =
        ((v - AXIS_MIN) / (AXIS_MAX - AXIS_MIN)).coerceIn(0.0, 1.0)
}
