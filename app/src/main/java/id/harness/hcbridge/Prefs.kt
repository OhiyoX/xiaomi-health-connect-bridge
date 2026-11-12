package id.harness.hcbridge

import android.content.Context
import android.content.SharedPreferences

/** app 侧持久状态：小米账号凭据（会轮换）、最近同步时间、连续失败次数。 */
object Prefs {
    private const val FILE = "hc_bridge"
    private const val K_USER = "userId"
    private const val K_PASS = "passToken"
    private const val K_LAST_TS = "lastTs"
    private const val K_FAILS = "fails"
    private const val K_LAST_STATUS = "lastStatus"

    fun get(c: Context): SharedPreferences = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun hasCreds(c: Context): Boolean {
        val p = get(c)
        return !p.getString(K_USER, null).isNullOrEmpty() && !p.getString(K_PASS, null).isNullOrEmpty()
    }

    fun creds(c: Context): Pair<String, String>? {
        val p = get(c)
        val u = p.getString(K_USER, null) ?: return null
        val t = p.getString(K_PASS, null) ?: return null
        return if (u.isEmpty() || t.isEmpty()) null else u to t
    }

    fun saveCreds(c: Context, userId: String, passToken: String) =
        get(c).edit().putString(K_USER, userId).putString(K_PASS, passToken).apply()

    /** 只更新轮换后的 passToken */
    fun savePassToken(c: Context, passToken: String) =
        get(c).edit().putString(K_PASS, passToken).apply()

    fun lastTs(c: Context): Long = get(c).getLong(K_LAST_TS, 0L)

    /**
     * 身高（米）。HC 里通常没有 HeightRecord，而小米体称的 payload 同时有 kg 和 bmi，
     * 可以反推 h = sqrt(kg / bmi)。导入体重时算一次存下来，供 BMI 计算用。
     */
    fun heightM(c: Context): Double? = get(c).getFloat("height_m", 0f).toDouble().takeIf { it > 0.5 }
    fun setHeightM(c: Context, h: Double) {
        if (h in 1.0..2.5) get(c).edit().putFloat("height_m", h.toFloat()).apply()
    }
    fun setLastTs(c: Context, ts: Long) = get(c).edit().putLong(K_LAST_TS, ts).apply()

    fun fails(c: Context): Int = get(c).getInt(K_FAILS, 0)
    fun setFails(c: Context, n: Int) = get(c).edit().putInt(K_FAILS, n).apply()

    fun setStatus(c: Context, s: String) =
        get(c).edit().putString(K_LAST_STATUS, s).apply()

    fun status(c: Context): String = get(c).getString(K_LAST_STATUS, "") ?: ""

    // 本机 MCP 服务器开关（默认关闭 —— 常驻 MCP 由 NAS 上的 docker 提供）
    private const val K_MCP = "mcpEnabled"
    fun mcpEnabled(c: Context): Boolean = get(c).getBoolean(K_MCP, false)
    fun setMcpEnabled(c: Context, on: Boolean) = get(c).edit().putBoolean(K_MCP, on).apply()

    // 调试：允许截屏管理页（默认关闭，避免一次性密钥进入截图/录屏）
    private const val K_ALLOW_SHOT = "adminAllowScreenshot"
    fun adminAllowScreenshot(c: Context): Boolean = get(c).getBoolean(K_ALLOW_SHOT, false)
    fun setAdminAllowScreenshot(c: Context, on: Boolean) =
        get(c).edit().putBoolean(K_ALLOW_SHOT, on).apply()

    // HC 心率覆盖率缓存（coverage 要扫全表，很贵；持久化 + 6h TTL）
    fun coverageCache(c: Context): Triple<Int, Int, Long>? {
        val v = get(c).getString("coverage_hr", null) ?: return null
        val parts = v.split(",")
        if (parts.size != 3) return null
        return Triple(parts[0].toIntOrNull() ?: 0, parts[1].toIntOrNull() ?: 0, parts[2].toLongOrNull() ?: 0L)
    }

    fun clearCoverageCache(c: Context) = get(c).edit().remove("coverage_hr").apply()

    fun setCoverageCache(c: Context, records: Int, samples: Int) =
        get(c).edit().putString("coverage_hr", "$records,$samples,${System.currentTimeMillis()}").apply()

    // 跟随系统 Material You 配色（与 Palette 17 同一套 system_palette）
    private const val K_DYN = "dynColor"
    fun dynColorOn(c: Context): Boolean = get(c).getBoolean(K_DYN, true)
    fun setDynColorOn(c: Context, on: Boolean) = get(c).edit().putBoolean(K_DYN, on).apply()

    // 远端健康 MCP（NAS）地址与可选 token
    private const val K_REMOTE_URL = "remoteUrl"
    private const val K_REMOTE_TOKEN = "remoteToken"
    fun remoteUrl(c: Context): String =
        get(c).getString(K_REMOTE_URL, "https://health.example.com/mcp") ?: "https://health.example.com/mcp"
    fun setRemoteUrl(c: Context, u: String) = get(c).edit().putString(K_REMOTE_URL, u).apply()
    fun remoteToken(c: Context): String? {
        // 只在 Keystore 加密容器里取值；旧明文（SharedPreferences / 文件）一次性迁移后清除
        Secure.getString(c, K_REMOTE_TOKEN)?.let { if (it.isNotBlank()) return it }
        val legacy = get(c).getString(K_REMOTE_TOKEN, null)
        if (!legacy.isNullOrBlank()) {
            Secure.putString(c, K_REMOTE_TOKEN, legacy)
            get(c).edit().remove(K_REMOTE_TOKEN).apply()
            return legacy
        }
        for (f in listOf(
            java.io.File(c.filesDir, "remote-token.txt"),
            java.io.File("/sdcard/Download/hc-bridge/remote-token.txt"),
        )) {
            val v = runCatching { if (f.exists()) f.readText().trim().ifBlank { null } else null }.getOrNull()
            if (!v.isNullOrBlank()) {
                Secure.putString(c, K_REMOTE_TOKEN, v)
                runCatching { f.delete() }
                return v
            }
        }
        return null
    }

    fun setRemoteToken(c: Context, t: String?) {
        Secure.putString(c, K_REMOTE_TOKEN, t)
        get(c).edit().remove(K_REMOTE_TOKEN).apply()   // 明文不留
    }

    // 数据页卡片顺序（长按卡片可调）；默认 步数-心率-血氧-睡眠
    private const val K_ORDER = "cardOrder"
    private val DEFAULT_ORDER = listOf("steps", "hr", "spo2", "sleep", "body")
    private val KNOWN_CARDS = setOf("steps", "hr", "spo2", "sleep", "body")

    /**
     * 卡片顺序。老版本存的 4 卡顺序缺 body ⇒ 自动补到末尾（而不是整份丢弃回默认，
     * 那样用户调过的顺序会突然失效）。
     */
    fun cardOrder(c: Context): List<String> {
        val raw = (get(c).getString(K_ORDER, null) ?: "").split(",").filter { it.isNotBlank() }
        if (raw.isEmpty()) return DEFAULT_ORDER
        if (raw.any { it !in KNOWN_CARDS }) return DEFAULT_ORDER
        val missing = DEFAULT_ORDER.filter { it !in raw }
        return raw + missing
    }
    fun setCardOrder(c: Context, order: List<String>) =
        get(c).edit().putString(K_ORDER, order.joinToString(",")).apply()

    // 设备角色：primary=有小米账号的来源设备（云 → HC）；secondary=只从自建服务拉全量 → HC（如平板）
    private const val K_ROLE = "role"
    fun role(c: Context): String = get(c).getString(K_ROLE, "primary") ?: "primary"
    fun setRole(c: Context, r: String) = get(c).edit().putString(K_ROLE, r).apply()

    // 睡眠空夜体检（扫 HC 最近 N 天，缺的就从云端补；别每 15 分钟都扫）
    private const val K_SLEEP_GAP_TS = "sleepGapCheckedAt"
    fun sleepGapCheckedAt(c: Context): Long = get(c).getLong(K_SLEEP_GAP_TS, 0L)
    fun setSleepGapCheckedAt(c: Context, ts: Long) = get(c).edit().putLong(K_SLEEP_GAP_TS, ts).apply()

    // ------------------------------------------------ 上游（小米运动健康）主动同步
    // 「上游卡住了」判定与催它（UpstreamFreshness / UpstreamKick）的状态。
    // 全部落在这里：面板、MCP、状态文件都从这几项读，不再各自查一遍 HC/云。
    private const val K_KICK_TS = "lastKickTs"
    private const val K_KICK_N = "kicksToday"
    private const val K_KICK_DAY = "kicksDay"
    private const val K_KICK_NOTE = "lastKickNote"
    private const val K_UPSTREAM = "upstreamJson"
    private const val K_UPSTREAM_NOTIFIED = "upstreamNotifiedAt"

    fun lastKickTs(c: Context): Long = get(c).getLong(K_KICK_TS, 0L)
    fun lastKickNote(c: Context): String = get(c).getString(K_KICK_NOTE, "") ?: ""

    /** 记一次唤醒；跨天自动把当日计数归零（用于「今天催了几次」）。 */
    fun setUpstreamKick(c: Context, ts: Long, note: String) {
        val p = get(c)
        val day = java.time.LocalDate.now().toEpochDay()
        val sameDay = p.getLong(K_KICK_DAY, -1L) == day
        val n = if (sameDay) p.getInt(K_KICK_N, 0) + 1 else 1
        p.edit().putLong(K_KICK_TS, ts).putString(K_KICK_NOTE, note)
            .putLong(K_KICK_DAY, day).putInt(K_KICK_N, n).apply()
    }

    fun kicksToday(c: Context): Int =
        if (get(c).getLong(K_KICK_DAY, -1L) == java.time.LocalDate.now().toEpochDay())
            get(c).getInt(K_KICK_N, 0) else 0

    fun upstreamJson(c: Context): String = get(c).getString(K_UPSTREAM, "") ?: ""
    fun setUpstreamJson(c: Context, s: String) = get(c).edit().putString(K_UPSTREAM, s).apply()

    /** 上次判定里“过期”的指标（面板/通知直接读，不用再查 HC/云）。 */
    fun upstreamStale(c: Context): List<String> {
        val raw = upstreamJson(c)
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = org.json.JSONObject(raw).optJSONArray("stale") ?: return emptyList()
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        }.getOrElse { emptyList() }
    }

    /** 上游告警通知的最小间隔（默认 6 小时，别把“手环没戴”变成刷屏）。 */
    fun upstreamNotifiedAt(c: Context): Long = get(c).getLong(K_UPSTREAM_NOTIFIED, 0L)
    fun setUpstreamNotifiedAt(c: Context, ts: Long) =
        get(c).edit().putLong(K_UPSTREAM_NOTIFIED, ts).apply()

    /**
     * 睡眠记录的元数据版本：1 = 旧的（device=fitness band + title「小米睡眠」），
     * 2 = 模仿小米自己的写法（device=TYPE_UNKNOWN/xiaomi、不写 title）。低于当前值就自动重写一次。
     */
    fun sleepMetaVersion(c: Context): Int = get(c).getInt("sleepMetaV", 1)
    fun setSleepMetaVersion(c: Context, v: Int) = get(c).edit().putInt("sleepMetaV", v).apply()

    /** “部分权限缺失”告警的最小间隔（默认 6 小时）。 */
    fun permNotifiedAt(c: Context): Long = get(c).getLong("permNotifiedAt", 0L)
    fun setPermNotifiedAt(c: Context, ts: Long) =
        get(c).edit().putLong("permNotifiedAt", ts).apply()

    /**
     * 状态文件在 /sdcard 上的**实际文件名**。重装 app（UID 变了）后老的
     * `app-sync-status.json` 本应用删不掉，MediaStore 只能写成 `app-sync-status (1).json`；
     * 这里记下真实名字，状态 JSON 里也带一份，免得外面“读到的是一份不再更新的旧文件”。
     */
    fun exportName(c: Context): String = get(c).getString("exportName", "") ?: ""
    fun setExportName(c: Context, name: String) = get(c).edit().putString("exportName", name).apply()

    // 同步间隔（分钟，15/30/60）
    private const val K_INTERVAL = "intervalMin"
    fun interval(c: Context): Int = get(c).getInt(K_INTERVAL, 15).coerceIn(15, 120)
    fun setInterval(c: Context, min: Int) =
        get(c).edit().putInt(K_INTERVAL, min.coerceIn(15, 120)).apply()
}
