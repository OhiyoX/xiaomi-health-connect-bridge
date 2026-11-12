package id.harness.hcbridge

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import id.harness.hcbridge.auth.McpConfig
import id.harness.hcbridge.logic.BodyMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 单 Activity 外壳：底栏切换三个视图（概览 / 数据 / 设置），不重建窗口。
 * 详情仍是二级 Activity（带返回）。
 */
class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 迁移提示的视图引用（`bindSettings` 之后才有效；`onResume` 用它刷新）。 */
    private var mcpAuthNotice: TextView? = null
    private var mcpNoticeBar: View? = null
    private var snap: Snapshot? = null
    private lateinit var container: FrameLayout
    private lateinit var nav: NavigationBarView
    private lateinit var topBar: com.google.android.material.appbar.MaterialToolbar

    private lateinit var overview: View
    private lateinit var data: View
    private lateinit var settings: View
    private var current = R.id.nav_overview
    private var paneMetric = "heart_rate"

    // 概览
    private lateinit var statusChip: Chip
    private lateinit var spark: HrSparkView
    private lateinit var sparkCaption: TextView
    private lateinit var needsBox: LinearLayout
    private lateinit var todaySteps: TextView
    private lateinit var todaySleep: TextView
    private lateinit var todayHr: TextView
    private lateinit var syncLine: TextView

    // 数据
    private lateinit var dataSub: TextView

    // 设置
    private lateinit var intervalGroup: ChipGroup
    private lateinit var tokenInput: TextInputEditText
    private lateinit var remoteState: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        applySystemBarAppearance()
        setContentView(R.layout.activity_shell)
        container = findViewById(R.id.container)
        nav = findViewById(R.id.nav)
        topBar = findViewById(R.id.topBar)

        overview = layoutInflater.inflate(R.layout.view_overview, container, false)
        data = layoutInflater.inflate(R.layout.view_data, container, false)
        settings = layoutInflater.inflate(R.layout.view_settings, container, false)
        for (v in listOf(overview, data, settings)) {
            container.addView(v, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            v.visibility = View.GONE
        }
        bindOverview()
        bindData()
        bindSettings()

        topBar.inflateMenu(R.menu.top_actions)
        topBar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_refresh) {
                SyncWorker.runNow(this@MainActivity)
                toast("已排队同步")
                scope.launch { load(deep = current != R.id.nav_overview) }
                true
            } else false
        }

        nav.setOnItemSelectedListener { item ->
            show(item.itemId)
            true
        }
        show(R.id.nav_overview)

        if (Prefs.mcpEnabled(this)) {
            runCatching { startForegroundService(Intent(this, McpService::class.java)) }
        }
    }

    private fun show(id: Int) {
        current = id
        topBar.title = when (id) {
            R.id.nav_data -> "数据"
            R.id.nav_settings -> "设置"
            else -> "概览"
        }
        overview.visibility = if (id == R.id.nav_overview) View.VISIBLE else View.GONE
        data.visibility = if (id == R.id.nav_data) View.VISIBLE else View.GONE
        settings.visibility = if (id == R.id.nav_settings) View.VISIBLE else View.GONE
        // 立刻用已有快照铺一次（切 tab 不再等查询）
        snap?.let { renderFor(id, it) }
        scope.launch { load(deep = id != R.id.nav_overview) }
    }

    override fun onResume() {
        super.onResume()
        scope.launch { load(deep = current != R.id.nav_overview) }
        renderMigrationNotice()   // 配完鉴权/静态 key 回来，一次性提示要立刻消失
    }

    // ------------------------------------------------------------- 概览

    private fun bindOverview() {
        statusChip = overview.findViewById(R.id.statusChip)
        spark = overview.findViewById(R.id.spark)
        sparkCaption = overview.findViewById(R.id.sparkCaption)
        needsBox = overview.findViewById(R.id.needsBox)
        todaySteps = overview.findViewById(R.id.todaySteps)
        todaySleep = overview.findViewById(R.id.todaySleep)
        todayHr = overview.findViewById(R.id.todayHr)
        syncLine = overview.findViewById(R.id.syncLine)

        overview.findViewById<View>(R.id.cardSpark).setOnClickListener {
            startActivity(Intent(this, MetricDetailActivity::class.java).putExtra("metric", "heart_rate"))
        }
        overview.findViewById<View>(R.id.cardToday).setOnClickListener { nav.selectedItemId = R.id.nav_data }
        overview.findViewById<View>(R.id.btnSync).setOnClickListener {
            SyncWorker.runNow(this); toast("已排队同步"); scope.launch { load() }
        }
        overview.findViewById<View>(R.id.btnDump).setOnClickListener {
            DumpWorker.runNow(this); toast("已排队读取")
        }
    }

    private fun need(msg: String, action: String? = null, act: (() -> Unit)? = null) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        row.addView(TextView(this).apply {
            text = msg
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (action != null && act != null) {
            row.addView(MaterialButton(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = action
                textSize = 13f
                isAllCaps = false
                setOnClickListener { act() }
            })
        }
        needsBox.addView(row)
    }

    private fun renderOverview(s: Snapshot) {
        val remoteBad = s.remote?.let { !it.reachable || it.code >= 500 } ?: true
        val lagMin = if (s.cloudMaxTs > 0 && s.lastWriteTs > 0) (s.cloudMaxTs - s.lastWriteTs) / 60 else 0

        needsBox.removeAllViews()
        var issues = 0
        if (!s.hcOk) { issues++; need("Health Connect 不可用") }
        if (s.missingPerms > 0) {
            issues++
            need("HC 权限缺 ${s.missingPerms} 项", "恢复") { startActivity(Intent(this, FixActivity::class.java)) }
        }
        // 副机从自建服务拉数据，不需要小米账号
        if (!s.hasCreds && Prefs.role(this) == "primary") {
            issues++
            need("小米账号未配置", "登录") { startActivity(Intent(this, LoginActivity::class.java)) }
        }
        if (remoteBad) { issues++; need("远端服务不可达", "重试") { scope.launch { load() } } }
        if (lagMin > 30) {
            issues++
            need("本机落后云端 $lagMin 分钟", "同步") { SyncWorker.runNow(this); toast("已排队同步") }
        }
        // 上游（小米运动健康）自己没同步：桥已经催过但数据还没到 —— 给人一个一键唤醒
        val upStale = Prefs.upstreamStale(this)
        if (upStale.isNotEmpty()) {
            issues++
            need("小米运动健康未同步（${id.harness.hcbridge.logic.UpstreamFreshness.labels(upStale)}）", "唤醒") {
                SyncWorker.runKick(this)
                toast("已在后台唤醒小米运动健康，稍后自动补拉")
            }
        }
        if (issues == 0) {
            needsBox.addView(TextView(this).apply {
                text = "无"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(0, dp(4), 0, 0)
            })
        }

        statusChip.text = if (issues == 0) "正常" else "$issues 项待处理"
        val bg = if (issues == 0) com.google.android.material.R.attr.colorPrimaryContainer
        else com.google.android.material.R.attr.colorErrorContainer
        val fg = if (issues == 0) com.google.android.material.R.attr.colorOnPrimaryContainer
        else com.google.android.material.R.attr.colorOnErrorContainer
        statusChip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(statusChip.color(bg))
        statusChip.setTextColor(statusChip.color(fg))

        spark.setData(s.hr, spark.m3("colorPrimary"))
        spark.setTooltipColors(spark.m3("colorInverseSurface"))
        val ovTip = overview.findViewById<TextView>(R.id.ovTip)
        styleTooltip(ovTip, MetricStyle.onContainer(overview, "heart_rate"), MetricStyle.container(overview, "heart_rate"))
        spark.onSelect = { t ->
            ovTip.text = t
            ovTip.visibility = if (t == null) View.INVISIBLE else View.VISIBLE
        }
        spark.visibility = if (s.hr.size >= 2) View.VISIBLE else View.GONE
        sparkCaption.text = if (s.hr.isEmpty()) "近 24 小时心率 · 暂无数据"
        else "近 24 小时心率 · 最新 ${s.hr.last().second} bpm · ${s.hr.size} 采样"

        todaySteps.text = if (s.steps > 0) "%,d".format(s.steps) else "—"
        todaySleep.text = hhmm(s.sleepMin)
        todayHr.text = s.hrAvg?.toString() ?: "—"

        // 副机（平板）不该显示“云端”那行——它根本没有云端凭据，显示的是主机的字段。
        syncLine.text = "同步  " + if (Prefs.role(this) == "secondary") {
            val hint = s.status?.optString("hint").orEmpty()
            when {
                hint.isNotBlank() -> "副机失败：$hint"
                s.status == null -> "副机未同步过（等待从服务端拉取）"
                else -> "副机 ← 服务端 · ${ts(s.status.optLong("at_ts", s.lastWriteTs))} · 本次 ${s.status.optInt("count", 0)} 条"
            }
        } else when {
            s.cloudMaxTs == 0L -> s.status?.optString("state")?.takeIf { it.isNotBlank() }
                ?.let { "云端未取到 · $it" } ?: "云端未取到"
            s.lastWriteTs == 0L -> "云端 ${ts(s.cloudMaxTs)} · 本机未写入"
            else -> "云端 ${ts(s.cloudMaxTs)} · 差 ${lagMin}m · 失败 ${s.fails} 次"
        }
    }

    // ------------------------------------------------------------- 数据

    private fun metricKeyOf(tipId: Int) = when (tipId) {
        R.id.hrTip -> "heart_rate"
        R.id.spo2Tip -> "spo2"
        R.id.sleepTip -> "sleep"
        else -> "steps"
    }

    private fun bindData() {
        dataSub = data.findViewById(R.id.dataSub)
        // 卡片打 tag（供拖拽排序读取顺序）。用**资源键 tag**，别用 View.tag——它会被别的用途覆盖。
        for ((k, id) in cardIds) data.findViewById<View>(id)?.setTag(R.id.tag_reorder_key, k)
        applyCardOrder()
        (data.findViewById<View>(R.id.cardsBox) as? ReorderLinearLayout)?.onOrderChanged = { order ->
            if (order.size == cardIds.size) {
                Prefs.setCardOrder(this, order)
                AppLog.log(this, "ui", "卡片顺序 → ${order.joinToString(",")}")
            }
        }
        // 点图表区域也能进详情（图表消费触摸，之前点在图上没反应）
        data.findViewById<View>(R.id.hrChart)?.setOnClickListener { open("heart_rate") }
        data.findViewById<View>(R.id.spo2Chart)?.setOnClickListener { open("spo2") }
        data.findViewById<View>(R.id.sleepChart)?.setOnClickListener { open("sleep") }
        data.findViewById<View>(R.id.stepsChart)?.setOnClickListener { open("steps") }
        data.findViewById<View>(R.id.bodyChart)?.setOnClickListener { open("body") }

        data.findViewById<View>(R.id.cardHr).setOnClickListener { open("heart_rate") }
        data.findViewById<View>(R.id.cardSleep).setOnClickListener { open("sleep") }
        data.findViewById<View>(R.id.cardSpo2).setOnClickListener { open("spo2") }
        data.findViewById<View>(R.id.cardSteps).setOnClickListener { open("steps") }
        data.findViewById<View>(R.id.cardBody).setOnClickListener { open("body") }
    }

    private fun open(metric: String) {
        val pane = data.findViewById<View>(R.id.detailPane)
        if (pane != null) {                       // 平板：list-detail，就地渲染
            paneMetric = metric
            snap?.let { MetricDetailBinder.render(this, pane, metric, it) }
        } else {
            startActivity(Intent(this, MetricDetailActivity::class.java).putExtra("metric", metric))
        }
    }



    /** 用官方容器角色区分四类指标（role 配对，不自造色值）。 */
    private fun tintCard(cardId: Int, textIds: List<Int>, metric: String) {
        val card = data.findViewById<com.google.android.material.card.MaterialCardView>(cardId)
        val bg = MetricStyle.container(card, metric)
        val fg = MetricStyle.onContainer(card, metric)
        card.setCardBackgroundColor(bg)
        for (id in textIds) data.findViewById<TextView>(id).setTextColor(fg)
    }

    private val cardIds = mapOf(
        "steps" to R.id.cardSteps, "hr" to R.id.cardHr,
        "spo2" to R.id.cardSpo2, "sleep" to R.id.cardSleep,
        "body" to R.id.cardBody,
    )
    private val cardNames = mapOf(
        "steps" to "步数", "hr" to "心率", "spo2" to "血氧",
        "sleep" to "睡眠", "body" to "体成分"
    )

    /** 按 Prefs 里的顺序重排四张卡（长按可调）。 */
    private fun applyCardOrder() {
        val box = data.findViewById<LinearLayout>(R.id.cardsBox) ?: return
        val views = cardIds.mapNotNull { (k, id) -> data.findViewById<View>(id)?.let { k to it } }.toMap()
        box.removeAllViews()
        for (k in Prefs.cardOrder(this)) views[k]?.let { box.addView(it) }
    }


    private fun renderData(s: Snapshot) {
        dataSub.text = if (s.missingPerms > 0) "缺 ${s.missingPerms} 项 HC 权限 · 去设置→恢复权限"
        else "本机 Health Connect · 近 30 天心率 ${s.hcRecords} 条"

        data.findViewById<TextView>(R.id.hrValue).text =
            "心率 " + (s.hr.lastOrNull()?.second?.let { "$it bpm" } ?: "—")
        data.findViewById<TextView>(R.id.hrSub).text =
            if (s.hrAvg != null) "今日均 ${s.hrAvg} · 低 ${s.hrMin ?: "—"} / 高 ${s.hrMax ?: "—"} · 近 24h ${s.hr.size} 采样"
            else "近 24h ${s.hr.size} 采样"

        data.findViewById<TextView>(R.id.sleepValue).text = "睡眠 " + hhmm(s.sleepMin)
        val stageLine = s.nightStages?.takeIf { it.hasData }?.let { st ->
            " · " + st.rows.joinToString(" ") { "${it.name}${it.minutes}m" }
        } ?: ""
        data.findViewById<TextView>(R.id.sleepSub).text =
            if (s.sleepWeek.isNotEmpty())
                "近 ${s.sleepWeek.map { it.label }.distinct().size} 晚 · ${s.sleepWeek.size} 段$stageLine"
            else "近 7 天无记录"

        val spo2Last = s.spo2.lastOrNull()
        data.findViewById<TextView>(R.id.spo2Value).text =
            "血氧 " + (spo2Last?.let { "%.0f%%".format(it) } ?: "—")
        data.findViewById<TextView>(R.id.spo2Sub).text =
            if (s.spo2.isEmpty()) "近 24h 无样本"
            else {
                val sorted = s.spo2.sorted()
                val base = sorted[sorted.size / 2]
                val dips = s.spo2.count { it <= base - 3.0 }
                "近 24h ${s.spo2.size} 样本 · 最低 ${"%.0f".format(s.spo2.min())}% · 掉点 $dips"
            }

        data.findViewById<TextView>(R.id.stepsValue).text =
            "步数 " + if (s.steps > 0) "%,d".format(s.steps) else "—"
        data.findViewById<View>(R.id.detailPane)?.let { pane ->
            MetricDetailBinder.render(this, pane, paneMetric, s)
        }

        data.findViewById<TextView>(R.id.stepsSub).text =
            if (s.steps > 0) "今日 · %.1f km · %.0f kcal".format(s.distanceKm, s.kcal) else "今日暂无数据"


        val latest = s.bodyLatest
        data.findViewById<TextView>(R.id.bodyValue).text =
            "体成分 " + (latest?.let { "%.1f kg".format(it.kg) } ?: "—")
        data.findViewById<TextView>(R.id.bodySub).text = when {
            latest == null -> "暂无体成分记录"
            else -> BodyMath.summaryLine(latest) + " · ${s.bodySeries.size} 天记录"
        }
        val bodyChart = data.findViewById<BodyTrendView>(R.id.bodyChart)
        bodyChart.setData(s.bodySeries, MetricStyle.accent(bodyChart, "body"))
        bodyChart.visibility = if (s.bodySeries.size >= 2) View.VISIBLE else View.GONE
        bodyChart.contentDescription = if (latest == null) "体成分趋势，暂无数据"
        else "体成分趋势，${s.bodySeries.size} 次测量，最新 %.1f 公斤（${latest.day}）".format(latest.kg)

        tintCard(R.id.cardHr, listOf(R.id.hrValue, R.id.hrSub), "heart_rate")
        tintCard(R.id.cardBody, listOf(R.id.bodyValue, R.id.bodySub), "body")
        tintCard(R.id.cardSleep, listOf(R.id.sleepValue, R.id.sleepSub), "sleep")
        tintCard(R.id.cardSpo2, listOf(R.id.spo2Value, R.id.spo2Sub), "spo2")
        tintCard(R.id.cardSteps, listOf(R.id.stepsValue, R.id.stepsSub), "steps")

        // 四种指标各用各的可视化
        val hrChart = data.findViewById<HrSparkView>(R.id.hrChart)
        hrChart.setData(s.hr, hrChart.m3("colorOnPrimaryContainer"))
        val sleepChart = data.findViewById<SleepBarsView>(R.id.sleepChart)
        sleepChart.setData(s.sleepWeek, sleepChart.m3("colorOnTertiaryContainer"))
        val spo2Chart = data.findViewById<Spo2RangeView>(R.id.spo2Chart)
        spo2Chart.setData(s.spo2, MetricStyle.accent(spo2Chart, "spo2"))
        val stepsChart = data.findViewById<StepsRingView>(R.id.stepsChart)
        stepsChart.setData(s.steps, Snapshot.STEP_GOAL, stepsChart.m3("colorOnSurface"))

        // 触摸选中值 → 卡片顶部的 M3 tooltip（4dp 圆角、inverseSurface 底）
        fun wireTip(v: android.view.View, tipId: Int) {
            val tip = data.findViewById<TextView>(tipId)
            // tooltip 配色跟随所在卡片：底 = 卡片 on-color，字 = 卡片 container color
            styleTooltip(
                tip,
                MetricStyle.onContainer(tip, metricKeyOf(tipId)),
                MetricStyle.container(tip, metricKeyOf(tipId)),
            )
            when (v) {
                is HrSparkView -> v.onSelect = { t -> tip.text = t; tip.visibility = if (t == null) View.INVISIBLE else View.VISIBLE }
                is Spo2RangeView -> v.onSelect = { t -> tip.text = t; tip.visibility = if (t == null) View.INVISIBLE else View.VISIBLE }
                is SleepBarsView -> v.onSelect = { t -> tip.text = t; tip.visibility = if (t == null) View.INVISIBLE else View.VISIBLE }
            }
        }
        wireTip(hrChart, R.id.hrTip)
        val bodyTip = data.findViewById<TextView>(R.id.bodyTip)
        styleTooltip(bodyTip, MetricStyle.onContainer(bodyTip, "body"), MetricStyle.container(bodyTip, "body"))
        bodyChart.onSelect = { t -> bodyTip.text = t; bodyTip.visibility = if (t == null) View.INVISIBLE else View.VISIBLE }
        wireTip(spo2Chart, R.id.spo2Tip)
        wireTip(sleepChart, R.id.sleepTip)

        // 无数据时不显示空图表（避免大片空白）
        hrChart.visibility = if (s.hr.size >= 2) View.VISIBLE else View.GONE
        spo2Chart.visibility = if (s.spo2.isNotEmpty()) View.VISIBLE else View.GONE
        sleepChart.visibility = if (s.sleepWeek.isNotEmpty()) View.VISIBLE else View.GONE

        // 无障碍：图表给文本替代（M3 数据可视化要求"给出图表想表达什么"）
        hrChart.contentDescription = if (s.hr.isEmpty()) "24 小时心率趋势，暂无数据"
            else "24 小时心率趋势，最新 ${s.hr.last().second} bpm，共 ${s.hr.size} 个采样"
        spo2Chart.contentDescription = if (s.spo2.isEmpty()) "24 小时血氧，暂无数据"
            else "24 小时血氧，最低 ${"%.0f".format(s.spo2.min())}%，基线 90%"
        sleepChart.contentDescription = if (s.sleepWeek.isEmpty()) "近 7 晚睡眠，暂无数据"
            else "近 7 晚睡眠，最近一晚 ${(s.sleepMin / 60).toInt()} 小时 ${(s.sleepMin % 60).toInt()} 分"
        stepsChart.contentDescription = "今日步数 ${"%,d".format(s.steps)}，目标 ${"%,d".format(Snapshot.STEP_GOAL)}"

        // 图表文字/浮层用 M3 角色色（labelSmall + surfaceContainerHighest + onSurface）
        val label = hrChart.m3("colorOnPrimaryContainer")
        val tipBg = hrChart.m3("colorOnPrimaryContainer")   // 与图线同色系
        sleepChart.setLabelColors(sleepChart.m3("colorOnTertiaryContainer"), sleepChart.m3("colorOnTertiaryContainer"))
        hrChart.setTooltipColors(tipBg)
        hrChart.setAxisColor(hrChart.m3("colorOnPrimaryContainer"))
        spo2Chart.setTooltipColors(spo2Chart.m3("colorOnSecondaryContainer"))
        spo2Chart.setWarnColor(spo2Chart.m3("colorError"))
        spo2Chart.setLabelColor(MetricStyle.label(spo2Chart, "spo2"))   // 与点阵同色，保证在卡底上可读
    }

    // ------------------------------------------------------------- 设置

    private fun bindSettings() {
        intervalGroup = settings.findViewById(R.id.intervalGroup)
        tokenInput = settings.findViewById(R.id.tokenInput)
        remoteState = settings.findViewById(R.id.remoteState)

        settings.findViewById<TextInputEditText>(R.id.urlInput).setText(Prefs.remoteUrl(this))
        settings.findViewById<MaterialButton>(R.id.btnHeroSync).setOnClickListener {
            SyncWorker.runNow(this)
            toast("已开始同步")
            settings.postDelayed({ renderSettingsStatus() }, 800)
        }
        settings.findViewById<View>(R.id.diagHeader).setOnClickListener {
            val body = settings.findViewById<View>(R.id.diagBody)
            val open = body.visibility != View.VISIBLE
            body.visibility = if (open) View.VISIBLE else View.GONE
            settings.findViewById<TextView>(R.id.diagChevron).text = if (open) "收起" else "展开"
        }
        tokenInput.setText(Prefs.remoteToken(this) ?: "")
        val about = BuildInfo.aboutText(this)
        settings.findViewById<TextView>(R.id.about).text = about
        AppLog.log(this, "ui", "关于：" + about.replace("\n", " | "))

        val roleGroup = settings.findViewById<ChipGroup>(R.id.roleGroup)
        for ((id, label) in listOf("primary" to "主设备（手机）", "secondary" to "副设备（平板/镜像）")) {
            roleGroup.addView(Chip(this).apply {
                text = label
                isCheckable = true
                chipCornerRadius = 20f * resources.displayMetrics.density
                isChecked = Prefs.role(this@MainActivity) == id
                setOnClickListener {
                    Prefs.setRole(this@MainActivity, id)
                    AppLog.log(this@MainActivity, "ui", "设备角色 → $id")
                    SyncWorker.runNow(this@MainActivity)
                    settings.findViewById<TextView>(R.id.roleHint).text =
                        if (id == "secondary") "平板：从服务端拉取全量，写入本机 HC。"
                        else "手机：从小米云取数，写入本机 HC。"
                    toast(if (id == "secondary") "副设备：将只从服务端拉取" else "主设备：从小米云取数")
                }
            })
        }

        for (m in listOf(15, 30, 60, 120)) {
            intervalGroup.addView(Chip(this).apply {
                text = "$m 分钟"
                isCheckable = true
                chipCornerRadius = 20f * resources.displayMetrics.density
                isChecked = Prefs.interval(this@MainActivity) == m
                setOnClickListener {
                    Prefs.setInterval(this@MainActivity, m)
                    SyncWorker.schedule(this@MainActivity)
                    AppLog.log(this@MainActivity, "ui", "同步间隔 → $m 分钟")
                    toast("已设为每 $m 分钟")
                }
            })
        }

        settings.findViewById<MaterialButton>(R.id.btnBackfill90).setOnClickListener { backfill(90) }
        settings.findViewById<MaterialButton>(R.id.btnBackfill365).setOnClickListener { backfill(365) }
        settings.findViewById<MaterialButton>(R.id.btnSaveToken).setOnClickListener { saveToken() }
        settings.findViewById<MaterialButton>(R.id.btnSaveFile).setOnClickListener {
            val f = java.io.File("/sdcard/Download/hc-bridge/remote-token.txt")
            if (f.exists()) { tokenInput.setText(f.readText().trim()); saveToken() } else toast("没有 $f")
        }
        settings.findViewById<MaterialButton>(R.id.btnMcpAdmin).setOnClickListener { openMcpAdmin() }
        settings.findViewById<MaterialButton>(R.id.btnFix).setOnClickListener {
            startActivity(Intent(this, FixActivity::class.java))
        }
        settings.findViewById<MaterialButton>(R.id.btnLogin).setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }
        settings.findViewById<MaterialButton>(R.id.btnExport).setOnClickListener {
            val n = DumpWorker.exportLogs(this)
            toast(if (n != null) "已导出 $n" else "导出失败")
        }
        settings.findViewById<MaterialButton>(R.id.btnClearLog).setOnClickListener {
            AppLog.clear(this); renderSettings()
        }

        settings.findViewById<MaterialSwitch>(R.id.switchDyn).apply {
            isChecked = Prefs.dynColorOn(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                Prefs.setDynColorOn(this@MainActivity, on)
                AppLog.log(this@MainActivity, "ui", "跟随系统配色 → $on")
                toast(if (on) "已开启，重启 app 生效" else "已关闭，重启 app 生效")
            }
        }
        settings.findViewById<MaterialSwitch>(R.id.switchAllowShot).apply {
            visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
            isChecked = Prefs.adminAllowScreenshot(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                Prefs.setAdminAllowScreenshot(this@MainActivity, on)
                AppLog.log(this@MainActivity, "ui", "管理页截屏 → $on")
                toast(if (on) "已允许截屏；重新打开管理页生效" else "已恢复防截屏")
            }
        }

        settings.findViewById<MaterialSwitch>(R.id.switchMcp).apply {
            isChecked = Prefs.mcpEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                Prefs.setMcpEnabled(this@MainActivity, on)
                AppLog.log(this@MainActivity, "ui", "本机 MCP → $on")
                if (on) runCatching { startForegroundService(Intent(this@MainActivity, McpService::class.java)) }
                else runCatching { stopService(Intent(this@MainActivity, McpService::class.java)) }
                toast(
                    if (on) "已开启（默认要 Bearer；公网/隧道开着时空闲不自停）"
                    else "已关闭",
                )
                settings.postDelayed({ renderSettingsStatus() }, 500)
            }
        }
        bindAuthMigrationNotice()
    }

    /**
     * 打开 MCP 管理页（0.9.0 起默认是共享前端 `McpWebAdminActivity`）。
     *
     * 选路（`web` / `native`）在 [AdminEntry] 里，读一次 `settings.adminUiMode`；
     * 原生页只是隐藏回退，没有设置界面。
     */
    private fun openMcpAdmin() = AdminEntry.open(this)

    /**
     * 默认鉴权迁移提示（计划 Task 9）：MCP 从「无鉴权」改成「默认要 Bearer」，
     * 老装机的本机客户端升级后会 401。这里只**提示** —— 不自动打开回环豁免。
     *
     * 显示条件在 [McpConfig.shouldShowMigrationNotice]（纯 JVM、有单测）：
     * 没提示过 **且** 还没配成「两个开关都关掉」的完整豁免。
     */
    private fun bindAuthMigrationNotice() {
        mcpAuthNotice = settings.findViewById(R.id.mcpAuthNotice)
        mcpNoticeBar = settings.findViewById(R.id.mcpNoticeBar)
        renderMigrationNotice()
        settings.findViewById<MaterialButton>(R.id.btnMcpNoticeAdmin).setOnClickListener { openMcpAdmin() }
        settings.findViewById<MaterialButton>(R.id.btnMcpNoticeDismiss).setOnClickListener {
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching { McpConfig(Store.of(this@MainActivity)).migrationNoticeSeen = true }
                }
                renderMigrationNotice()
            }
            toast("以后随时可以在「MCP 管理」里改鉴权与静态 key")
        }
    }

    /**
     * 刷新迁移提示。**不在主线程开库**：`McpConfig` 要读 `mcp.db` 的 settings，
     * 冷启动时那是第一次打开数据库（I/O）。读失败（库坏/没权限）按"不提示"处理，
     * 不能让设置页因为一条提示崩掉。
     *
     * `onResume` 也会调：从「MCP 管理」配完回来，提示要立刻消失（不留过期状态）。
     */
    private fun renderMigrationNotice() {
        val notice: TextView = mcpAuthNotice ?: return
        val bar: View = mcpNoticeBar ?: return
        scope.launch {
            val show = withContext(Dispatchers.IO) {
                runCatching { McpConfig(Store.of(this@MainActivity)).shouldShowMigrationNotice() }
                    .getOrDefault(false)
            }
            notice.visibility = if (show) View.VISIBLE else View.GONE
            bar.visibility = if (show) View.VISIBLE else View.GONE
        }
    }

    /** 切回设置页时重读远端配置：广播/文件导入的 token 不该等 Activity 重建才显示（不覆盖正在输入的内容）。 */
    private fun refreshRemoteInputs() {
        settings.findViewById<TextInputEditText>(R.id.urlInput)?.takeIf { !it.isFocused }
            ?.setText(Prefs.remoteUrl(this))
        tokenInput.takeIf { !it.isFocused }?.setText(Prefs.remoteToken(this) ?: "")
    }

    private fun renderSettingsStatus() {
        val status = SyncStatus.read(this)
        val fails = status?.optInt("fails", 0) ?: Prefs.fails(this)
        val state = status?.optString("state", "") ?: ""
        val at = status?.optString("at", "")?.take(16) ?: "—"
        val bad = fails > 0 || state in setOf("fail", "error", "no_token")
        val warn = state in setOf("net_wait", "no_new")
        settings.findViewById<TextView>(R.id.settingsStatus).text =
            when { bad -> "需处理"; warn -> "留意"; else -> "正常" }
        settings.findViewById<TextView>(R.id.settingsTitle).text =
            when { bad -> "同步有问题"; warn -> "等待网络"; else -> "运行正常" }
        settings.findViewById<TextView>(R.id.settingsSummary).text =
            "最近同步 $at · 失败 $fails 次"

        val mcpOn = Prefs.mcpEnabled(this) && McpService.RUNNING
        settings.findViewById<TextView>(R.id.mcpStatus).text =
            if (mcpOn) "已开启" else "已关闭"
        settings.findViewById<TextView>(R.id.mcpEndpoint).text =
            if (mcpOn) "127.0.0.1:8765 · 需要 Bearer" else "打开后本机客户端可连接"
    }

    private fun renderSettings() {
        refreshRemoteInputs()
        renderSettingsStatus()
        settings.findViewById<TextView>(R.id.historyBody).text = SyncHistory.all(this).take(5).joinToString("\n") {
            "${it.optString("at").take(16)}  ${it.optString("result")}  ${it.optInt("samples")} 采样"
        }.ifBlank { "（还没有记录）" }
        settings.findViewById<TextView>(R.id.logBody).text =
            AppLog.tail(this, 20).joinToString("\n").ifBlank { "（暂无日志）" }
    }

    private suspend fun checkRemote() {
        remoteState.text = "检查中…"
        val tok = Prefs.remoteToken(this)
        android.util.Log.i("HCBridge", "remote token len=${tok?.length ?: 0} url=${Prefs.remoteUrl(this)}")
        val r = withContext(Dispatchers.IO) {
            Remote.check(Prefs.remoteUrl(this@MainActivity), Prefs.remoteToken(this@MainActivity))
        }
        remoteState.text = when {
            !r.reachable -> "不可达 · ${r.note}"
            r.tools > 0 -> "可达 · ${r.ms} ms · ${r.tools} 个工具"
            else -> "可达 · ${r.ms} ms · ${r.note}"
        }
    }

    private fun backfill(days: Int) {
        AppLog.log(this, "ui", "回补 $days 天")
        SyncWorker.runBackfill(this, days)
        settings.findViewById<TextView>(R.id.backfillState).text = "已排队：最近 $days 天"
        toast("已排队回补 $days 天")
    }

    private fun saveToken() {
        // 地址与 token 一起保存（都不写死）
        settings.findViewById<TextInputEditText>(R.id.urlInput)?.text?.toString()?.trim()?.ifBlank { null }
            ?.let { Prefs.setRemoteUrl(this, it) }
        Prefs.setRemoteToken(this, tokenInput.text?.toString()?.ifBlank { null })
        toast("已保存")
        scope.launch { checkRemote() }
    }

    // ------------------------------------------------------------- 加载

    private fun renderFor(id: Int, s: Snapshot) {
        when (id) {
            R.id.nav_overview -> renderOverview(s)
            R.id.nav_data -> renderData(s)
            else -> { renderSettings(); scope.launch { checkRemote() } }
        }
    }

    private suspend fun load(deep: Boolean = false) {
        val t0 = System.currentTimeMillis()
        try {
            // 所有快照读取都离开主线程：健康数据/文件状态再慢也不能让返回原生页卡住。
            val s = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                Snapshot.load(this@MainActivity, wantDetails = deep || current == R.id.nav_data)
            }
            snap = s
            renderFor(current, s)
            android.util.Log.i("HCBridge", "load ${System.currentTimeMillis() - t0}ms deep=$deep tab=$current")
        } catch (t: Throwable) {
            android.util.Log.e("HCBridge", "load failed deep=$deep current=$current", t)
            AppLog.log(this, "ui", "刷新失败：${t.javaClass.simpleName}: ${t.message}")
            statusChip.text = "刷新失败"
            statusChip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                statusChip.m3("colorErrorContainer"))
            statusChip.setTextColor(statusChip.m3("colorOnErrorContainer"))
        }
    }

    private fun ts(epoch: Long) =
        if (epoch <= 0) "—" else SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(epoch * 1000))

    private fun hhmm(mins: Double): String {
        if (mins <= 0) return "—"
        return "${(mins / 60).toInt()}h${(mins % 60).toInt()}m"
    }
}
