package id.harness.hcbridge

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 二级详情：每个指标用各自的图形语义（折线 / 柱 / 点阵 / 圆环）。 */
class MetricDetailActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var metric = "heart_rate"

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        applySystemBarAppearance()
        setContentView(R.layout.activity_metric)
        metric = intent.getStringExtra("metric") ?: "heart_rate"
        val bar = findViewById<MaterialToolbar>(R.id.toolbar)
        bar.title = titleOf(metric)
        bar.setNavigationOnClickListener { finish() }
    }

    private fun titleOf(m: String) = when (m) {
        "sleep" -> "睡眠"; "spo2" -> "血氧"; "steps" -> "步数"
        "body" -> "体成分"; "heart_rate" -> "心率"; else -> "心率"
    }

    override fun onResume() {
        super.onResume()
        scope.launch { load() }
    }

    @Suppress("unused")
    private fun only(id: Int) {
        for (v in listOf(R.id.chart, R.id.chartSleep, R.id.chartSpo2, R.id.chartSteps)) {
            findViewById<View>(v).visibility = if (v == id) View.VISIBLE else View.GONE
        }
    }

    /** 数值与副标题的颜色显式取角色色（Display 样式默认不继承 onSurface，之前显示成灰的）。 */
    /** 图表卡底色与指标同步（与数据页同一组容器角色）。 */
    private suspend fun load() {
        val s = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                Snapshot.load(this@MetricDetailActivity, wantDetails = true)
            }
        } catch (t: Throwable) {
            AppLog.log(this, "ui", "详情页加载失败：${t.message}"); return
        }
        MetricDetailBinder.render(this, findViewById(android.R.id.content), metric, s)
    }

    private fun hhmm(mins: Double): String {
        if (mins <= 0) return "—"
        return "${(mins / 60).toInt()}h${(mins % 60).toInt()}m"
    }
}
