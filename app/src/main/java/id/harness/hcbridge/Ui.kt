package id.harness.hcbridge

import android.content.Context
import android.view.View
import com.google.android.material.color.MaterialColors

/** 跨页小工具：取主题色、dp、顶级目的地切换。 */
private val attrIds = HashMap<String, Int>()

/** 按 M3 角色名取色（colorPrimary / colorSurfaceContainer…），运行时解析 attr，不依赖库 R 符号。 */
fun View.m3(role: String): Int {
    // 运行时资源已合并到 app 包名，库包名查不到（曾导致全部回落 colorAccent）
    val id = attrIds.getOrPut(role) {
        resources.getIdentifier(role, "attr", context.packageName)
    }
    if (id == 0) {
        android.util.Log.w("HCBridge", "m3($role) 未解析，回落 colorAccent")
        return MaterialColors.getColor(this, android.R.attr.colorAccent)
    }
    return MaterialColors.getColor(this, id)
}

fun View.color(attr: Int): Int = MaterialColors.getColor(this, attr)
fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/** 透明状态栏/导航栏时，图标明暗必须跟随主题，否则浅色下白图标不可见。 */
fun androidx.appcompat.app.AppCompatActivity.applySystemBarAppearance() {
    val night = (resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = !night
        isAppearanceLightNavigationBars = !night
    }
}

fun Context.toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

/** 按底色亮度选可读前景（tooltip/浮层用），避免角色解析失败导致同色不可见。 */
/** 全 app 唯一的 tooltip 样式：md_inverse_surface 底 + md_inverse_on_surface 字 + 4dp 圆角。 */
fun styleTooltip(tip: android.widget.TextView, background: Int, foreground: Int) {
    val ctx = tip.context
    tip.background = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = 4f * ctx.resources.displayMetrics.density
        setColor(background)
    }
    // 先设文字外观，再设颜色（顺序反了颜色会被 textAppearance 覆盖）
    tip.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
    tip.setTextColor(id.harness.hcbridge.logic.Contrast.readable(foreground, background))
}

// 对比度数学在 logic/Contrast.kt（纯函数，JVM 单测直接覆盖）；这里只留 View 侧的薄封装。
fun contrastRatio(a: Int, b: Int): Double = id.harness.hcbridge.logic.Contrast.ratio(a, b)

fun readableOn(bg: Int): Int = id.harness.hcbridge.logic.Contrast.readableOn(bg)
