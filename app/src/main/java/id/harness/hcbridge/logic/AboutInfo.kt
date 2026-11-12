package id.harness.hcbridge.logic

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 「关于」文案：版本 + **构建时间** + **安装时间**（都按本机时区的当地时间显示）。
 *
 * 为什么要显示：真机排查时最常问的两句是"这个包是什么时候构建的"和"手机上装的是哪一份"
 * —— 0.5.x 那几次排查就因为分不清"我改的已经装上了"和"还是旧包"，白查了好几轮。
 *
 * 只是格式化，不做 IO，方便单测（时区显式传入）。
 */
object AboutInfo {

    private val FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** epoch 毫秒 → 当地时间 `yyyy-MM-dd HH:mm`；拿不到（0/负数）给「—」。 */
    fun formatLocal(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        if (epochMs <= 0L) "—"
        else FMT.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    /** 时区名，用于说明"这是当地时间"：`Asia/Shanghai (UTC+08:00)`。 */
    fun zoneLabel(zone: ZoneId = ZoneId.systemDefault()): String {
        val off = runCatching { zone.rules.getOffset(Instant.now()) }.getOrNull()
            ?: return zone.id
        val mins = off.totalSeconds / 60
        val sign = if (mins < 0) "-" else "+"
        val h = Math.abs(mins) / 60
        val m = Math.abs(mins) % 60
        return zone.id + if (m == 0) " (UTC$sign%02d:00)".format(h) else " (UTC$sign%02d:%02d)".format(h, m)
    }

    /**
     * 设置页「关于」那段文字（多行）。
     *
     * [buildTime] 是构建时写进 `BuildConfig.BUILD_TIME` 的字符串；
     * [firstInstallMs] / [lastUpdateMs] 来自 `PackageManager`（首次安装 / 本次安装或升级）。
     */
    fun aboutText(
        versionName: String,
        packageName: String,
        buildTime: String,
        firstInstallMs: Long,
        lastUpdateMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val first = formatLocal(firstInstallMs, zone)
        val updated = formatLocal(lastUpdateMs, zone)
        val installLine = if (first == updated) {
            "安装 $updated"
        } else {
            "首次安装 $first · 本次安装 $updated"
        }
        return "HC Bridge $versionName · $packageName\n" +
            "构建 ${buildTime.ifBlank { "—" }} · 当地时间 ${zoneLabel(zone)}\n" +
            "$installLine\n" +
            "数据只在本机与自建服务之间流动。"
    }
}
