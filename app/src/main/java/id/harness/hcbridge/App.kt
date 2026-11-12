package id.harness.hcbridge

import android.app.Application
import com.google.android.material.color.DynamicColors

/**
 * 跟随系统 Material You 配色（与 Palette 17 项目同一套 system_palette）。
 * 开关在面板「更多 → 跟随系统配色」；关掉时回落到 themes.xml 里的自带配色。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Prefs.dynColorOn(this)) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }
    }
}
