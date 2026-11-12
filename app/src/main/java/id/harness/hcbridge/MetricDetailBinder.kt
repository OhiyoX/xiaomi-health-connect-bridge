package id.harness.hcbridge

import android.app.Activity
import android.view.View
import android.widget.TextView
import id.harness.hcbridge.logic.BodyMath
import id.harness.hcbridge.logic.StageMath
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 指标详情的渲染逻辑（手机=二级页面，平板=右栏就地渲染）。
 * 传入 root，按 id 找控件；两套布局用同一批 id，所以逻辑只写一份。
 */
object MetricDetailBinder {

    fun render(activity: Activity, root: View, metric: String, s: Snapshot) {
        val big = root.findViewById<TextView>(R.id.valueBig)
        val sub = root.findViewById<TextView>(R.id.valueSub)
        val stats = root.findViewById<TextView>(R.id.statsBody)
        val detail = root.findViewById<TextView>(R.id.detailBody)
        val caption = root.findViewById<TextView>(R.id.chartCaption)

        big.setTextColor(big.m3("colorOnSurface"))
        sub.setTextColor(sub.m3("colorOnSurfaceVariant"))
        tintChartCard(root, metric)

        for (v in listOf(R.id.chart, R.id.chartSleep, R.id.chartSpo2, R.id.chartSteps,
                         R.id.chartBody, R.id.stageBar)) {
            root.findViewById<View>(v)?.visibility = View.GONE
        }

        when (metric) {
            "heart_rate" -> {
                big.text = s.hr.lastOrNull()?.second?.toString() ?: "—"
                sub.text = "bpm · 近 24 小时最新"
                root.findViewById<View>(R.id.chart)?.visibility = View.VISIBLE
                caption.text = "近 24 小时折线"
                val ch = root.findViewById<HrSparkView>(R.id.chart)
                ch.setData(s.hr, MetricStyle.accent(ch, "heart_rate"))
                ch.setTooltipColors(ch.m3("colorInverseSurface"))
                wireTip(root, ch)
                stats.text = buildString {
                    append("今日均 ${s.hrAvg ?: "—"} bpm\n")
                    append("最低 ${s.hrMin ?: "—"} / 最高 ${s.hrMax ?: "—"} bpm\n")
                    append("HC 近 30 天 ${s.hcRecords} 条记录\n")
                    append("近 24h 采样 ${s.hr.size}")
                }
                val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                detail.text = if (s.hr.isEmpty()) "无数据"
                else s.hr.takeLast(8).reversed().joinToString("\n") {
                    "${fmt.format(Date(it.first * 1000))}   ${it.second} bpm"
                }
            }
            "body" -> {
                val latest = s.bodyLatest
                big.text = latest?.let { "%.1f".format(it.kg) } ?: "—"
                sub.text = "kg · 最新体成分测量" + (latest?.let { "（${it.day}）" } ?: "")
                root.findViewById<View>(R.id.chartBody)?.visibility = View.VISIBLE
                caption.text = "体重趋势 · ${s.bodySeries.size} 天记录（x 轴按真实时间）"
                val ch = root.findViewById<BodyTrendView>(R.id.chartBody)
                ch.setData(s.bodySeries, MetricStyle.accent(ch, "body"))
                wireTip(root, ch)
                stats.text = if (latest == null) "暂无体成分记录（体秤数据需要点“导入体重”）"
                else buildString {
                    append("最新 ${"%.1f".format(latest.kg)} kg\n")
                    latest.bmi?.let { append("BMI %.1f\n".format(it)) }
                    latest.fatPct?.let { append("体脂 %.1f%%\n".format(it)) }
                    BodyMath.change(s.bodySeries)?.let { append("区间变化 %+.1f kg\n".format(it)) }
                    append("有记录的 ${s.bodySeries.size} 天")
                }
                detail.text = if (s.bodySeries.isEmpty()) "无数据"
                else s.bodySeries.takeLast(8).reversed().joinToString("\n") { p ->
                    "${p.day}  %.1f kg".format(p.kg) +
                        (p.fatPct?.let { "  体脂 %.1f%%".format(it) } ?: "") +
                        (p.bmi?.let { "  BMI %.1f".format(it) } ?: "")
                }
            }
            "sleep" -> {
                big.text = hhmm(s.sleepMin)
                sub.text = "最近一晚最长睡眠"
                root.findViewById<View>(R.id.chartSleep)?.visibility = View.VISIBLE
                caption.text = "近 ${s.sleepWeek.map { it.label }.distinct().size} 晚 · ${s.sleepWeek.size} 段（18:00→12:00）"
                val ch = root.findViewById<SleepBarsView>(R.id.chartSleep)
                ch.setData(s.sleepWeek, MetricStyle.accent(ch, "sleep"))
                ch.setLabelColors(MetricStyle.label(ch, "sleep"), ch.m3("colorInverseSurface"))
                wireTip(root, ch)
                val st = s.nightStages
                val bar = root.findViewById<StageBarView>(R.id.stageBar)
                if (st != null && st.hasData) {
                    bar.visibility = View.VISIBLE
                    bar.setData(st.rows.associate { it.stage to it.minutes }, MetricStyle.accent(bar, "sleep"))
                }
                stats.text = buildString {
                    if (s.sleepWeek.isNotEmpty()) {
                        append("近 ${s.sleepWeek.size} 晚\n")
                        append("最长 ${hhmm(s.sleepWeek.maxOf { dur(it) })}\n")
                        append("平均 ${hhmm(s.sleepWeek.map { dur(it) }.average())}\n")
                    }
                    if (st != null && st.hasData) {
                        append("\n最近一晚分期（${st.totalMinutes} 分钟，效率 ${"%.0f".format(st.efficiency)}%）\n")
                        for (r in st.rows) append(StageMath.line(r)).append("\n")
                    } else {
                        append("\n最近一晚无分期数据（小米未写 stages）\n")
                    }
                    append("\n")
                    append(StageMath.nightLine(s.respRateLatest, s.hrvLatest))
                    if (s.respRateLatest == null && s.hrvLatest == null) append("（设备未提供）")
                }
                detail.text = if (s.sleepWeek.isEmpty()) "无数据"
                else s.sleepWeek.reversed().joinToString("\n") { n ->
                    "${n.label}  ${clock(n.start)} → ${clock(n.end)}  ${hhmm(dur(n))}"
                }
            }
            "spo2" -> {
                big.text = s.spo2.lastOrNull()?.let { "%.0f".format(it) } ?: "—"
                sub.text = "% · 近 24 小时最新"
                root.findViewById<View>(R.id.chartSpo2)?.visibility = View.VISIBLE
                caption.text = "近 24 小时采样（虚线 90%）"
                val ch = root.findViewById<Spo2RangeView>(R.id.chartSpo2)
                ch.setData(s.spo2, MetricStyle.accent(ch, "spo2"))
                ch.setTooltipColors(ch.m3("colorInverseSurface"))
                ch.setLabelColor(MetricStyle.label(ch, "spo2"))
                wireTip(root, ch)
                stats.text = if (s.spo2.isEmpty()) "近 24h 无样本"
                else buildString {
                    val sorted = s.spo2.sorted()
                    val base = sorted[sorted.size / 2]
                    append("近 24h ${s.spo2.size} 样本\n")
                    append("中位基线 ${"%.0f".format(base)}% · 最低 ${"%.0f".format(s.spo2.min())}%\n")
                    append("低于 90% 的样本 ${s.spo2.count { it < 90 }}")
                }
                detail.text = if (s.spo2.isEmpty()) "无数据"
                else "低于 90% 时小米手环会在次日报告；本 app 只做读取与写入，不改数据。"
            }
            else -> {
                big.text = if (s.steps > 0) "%,d".format(s.steps) else "—"
                sub.text = "步 · 今日（目标 ${"%,d".format(Snapshot.STEP_GOAL)}）"
                root.findViewById<View>(R.id.chartSteps)?.visibility = View.VISIBLE
                caption.text = "今日目标完成度"
                root.findViewById<StepsRingView>(R.id.chartSteps)
                    .setData(s.steps, Snapshot.STEP_GOAL, MetricStyle.accent(root, "steps"))
                val pct = (s.steps.toDouble() / Snapshot.STEP_GOAL * 100).toInt()
                stats.text = buildString {
                    append("完成 $pct%\n")
                    append("距离 %.2f km\n".format(s.distanceKm))
                    append("消耗 %.0f kcal\n".format(s.kcal))
                    append("还差 ${"%,d".format((Snapshot.STEP_GOAL - s.steps).coerceAtLeast(0))} 步")
                }
                detail.text = "步数来自 Health Connect 今日聚合；小米手环数据由本 app 定时写入。"
            }
        }
    }

    private fun tintChartCard(root: View, metric: String) {
        val card = root.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardChart) ?: return
        card.setCardBackgroundColor(MetricStyle.container(card, metric))
        root.findViewById<TextView>(R.id.chartCaption)?.setTextColor(MetricStyle.onContainer(card, metric))
    }

    private fun metricOf(v: View) = when (v) {
        is HrSparkView -> "heart_rate"
        is Spo2RangeView -> "spo2"
        is SleepBarsView -> "sleep"
        is BodyTrendView -> "body"
        else -> "steps"
    }

    private fun wireTip(root: View, v: View) {
        val tip = root.findViewById<TextView>(R.id.metricTip) ?: return
        styleTooltip(tip, MetricStyle.onContainer(v, metricOf(v)), MetricStyle.container(v, metricOf(v)))
        val show = { t: String? ->
            tip.text = t
            tip.visibility = if (t == null) View.INVISIBLE else View.VISIBLE
        }
        when (v) {
            is HrSparkView -> v.onSelect = show
            is Spo2RangeView -> v.onSelect = show
            is SleepBarsView -> v.onSelect = show
            is BodyTrendView -> v.onSelect = show   // 体成分详情页的 tooltip（2026-10-21：漏了这一支，点了没反应）
        }
    }

    fun dur(n: id.harness.hcbridge.logic.SleepMath.Seg): Double =
        if (n.end > n.start) n.end - n.start else (n.end + 1440 - n.start)

    fun clock(minute: Double): String {
        val m = ((minute % 1440) + 1440) % 1440
        return "%02d:%02d".format((m / 60).toInt(), (m % 60).toInt())
    }

    fun hhmm(mins: Double): String {
        if (mins <= 0) return "—"
        return "${(mins / 60).toInt()}h${(mins % 60).toInt()}m"
    }
}
