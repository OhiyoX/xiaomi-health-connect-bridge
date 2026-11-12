package id.harness.hcbridge

import android.content.Context
import id.harness.hcbridge.logic.AboutInfo
import org.json.JSONObject

/**
 * 这个包是谁、什么时候构建的、手机上什么时候装的 —— 全部按**当地时间**。
 *
 * 两个用处：
 *  1. 设置页「关于」那一块（[aboutText]）；
 *  2. `app-sync-status.json` 的 `app` 段（[statusJson]）—— 读状态时顺手就能确认
 *     "跑的是不是刚构建的那个包"，不用再去 adb/dumpsys 里翻。
 */
object BuildInfo {

    private fun versionName(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
            ?: BuildConfig.VERSION_NAME

    private fun firstInstallMs(ctx: Context): Long =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).firstInstallTime }.getOrDefault(0L)

    /** 本次安装（或升级）的时间 */
    private fun lastUpdateMs(ctx: Context): Long =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime }.getOrDefault(0L)

    /** 构建时间（按设备当地时区显示）；BuildConfig 里存的是 epoch 毫秒 */
    fun buildTime(): String = AboutInfo.formatLocal(BuildConfig.BUILD_EPOCH_MS)

    /** 设置页「关于」：多行文本，当地时间显示。 */
    fun aboutText(ctx: Context): String = AboutInfo.aboutText(
        versionName = versionName(ctx),
        packageName = ctx.packageName,
        buildTime = buildTime(),
        firstInstallMs = firstInstallMs(ctx),
        lastUpdateMs = lastUpdateMs(ctx),
    )

    fun statusJson(ctx: Context): JSONObject = JSONObject()
        .put("version", versionName(ctx))
        .put("version_code", BuildConfig.VERSION_CODE)
        .put("build_time", buildTime())
        .put("build_epoch_ms", BuildConfig.BUILD_EPOCH_MS)
        .put("build_zone", BuildConfig.BUILD_ZONE)
        .put("installed_at", AboutInfo.formatLocal(lastUpdateMs(ctx)))
        .put("first_installed_at", AboutInfo.formatLocal(firstInstallMs(ctx)))
        .put("zone", AboutInfo.zoneLabel())
}
