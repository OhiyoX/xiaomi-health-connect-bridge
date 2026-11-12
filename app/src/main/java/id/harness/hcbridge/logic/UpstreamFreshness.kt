package id.harness.hcbridge.logic

/**
 * 「上游卡住了吗」的判定（纯计算，可单测）。
 *
 * 链路是 `手环 → 小米运动健康(com.mi.health) → 小米健康云 → 本桥 → HC`。
 * 桥自己能做的是**云 → HC** 这半边；手环 → 云 那半边只有小米运动健康能做。
 * 所以当它停摆（被 ColorOS 冻结 / 没开屏 / 后台同步失败）时，桥不能傻等：
 * 要么**催它**（[id.harness.hcbridge.UpstreamKick]），要么把「上游没上传」明确报出来。
 *
 * 判定口径（都是“该有却没有”的保守阈值，宁可晚催不要乱催）：
 *  - 睡眠：10 点后，HC 里**起床日是今天**的记录一条都没有 → 过期（夜里不算，可能还没睡完）；
 *  - 心率：8–23 点之间，最新采样超过 90 分钟 → 过期（云的心率是中位 10 分钟一采）；
 *  - 步数：9–23 点之间，最新步数采样超过 3 小时 → 过期；
 *  - 血氧：最新采样超过 26 小时 → 过期（小米只在夜间/按需测，阈值给宽）；
 *  - 云端整窗为空（白天）：链路整段没动 → 过期（这条最像“小米没同步”）。
 *
 * 催的动作有最小间隔（[KICK_MIN_GAP_S]）：催完要等它连手环 + 上传，几十分钟内再催没有意义。
 */
object UpstreamFreshness {

    const val SLEEP = "sleep"
    const val HEART_RATE = "heart_rate"
    const val STEPS = "steps"
    const val SPO2 = "spo2"
    const val CLOUD_EMPTY = "cloud_empty"

    const val HR_STALE_S = 90 * 60L
    /** 步数：云端是成批上传的，坐着不动几小时没有新采样很正常——给 4 小时，别一惊一乍。 */
    const val STEPS_STALE_S = 4 * 3600L
    const val SPO2_STALE_S = 26 * 3600L

    /** 两次「催上游」之间的最小间隔（秒）。 */
    const val KICK_MIN_GAP_S = 45 * 60L

    /** 这个点之后还缺昨晚，才算缺（之前夜里没睡完，判缺会误催）。 */
    const val NIGHT_CHECK_HOUR = 10

    /** 「白天」区间：心率/步数/整体空窗只在这段时间判过期。 */
    const val DAY_FROM_HOUR = 8
    const val DAY_TO_HOUR = 23

    /** 探针：所有时间都是 epoch 秒；拿不到就传 0（表示“没有”）。 */
    data class Probe(
        val nowS: Long,
        val hour: Int,
        val lastHrTs: Long = 0,
        val lastStepsTs: Long = 0,
        val lastSpo2Ts: Long = 0,
        /** HC 里“起床日=今天”的睡眠记录已经有了（睡完并落库）。 */
        val lastNightCovered: Boolean = true,
        /** 这一轮云端窗口拉回来的总条数（0 = 上游整段没上传）。 */
        val cloudRows: Int = 1,
    )

    /** [stale] 里是要催的原因；[needKick] = 过期且距上次催超过最小时隔。 */
    data class Verdict(val stale: List<String>, val needKick: Boolean, val note: String) {
        val fresh: Boolean get() = stale.isEmpty()
    }

    fun assess(
        p: Probe,
        lastKickTs: Long = 0,
        minKickGapS: Long = KICK_MIN_GAP_S,
    ): Verdict {
        val stale = ArrayList<String>(4)
        val inDay = p.hour in DAY_FROM_HOUR..DAY_TO_HOUR

        if (p.hour >= NIGHT_CHECK_HOUR && !p.lastNightCovered) stale.add(SLEEP)
        if (inDay && p.lastHrTs > 0 && p.nowS - p.lastHrTs > HR_STALE_S) stale.add(HEART_RATE)
        if (inDay && p.lastStepsTs > 0 && p.nowS - p.lastStepsTs > STEPS_STALE_S) stale.add(STEPS)
        if (p.lastSpo2Ts > 0 && p.nowS - p.lastSpo2Ts > SPO2_STALE_S) stale.add(SPO2)
        // 云端整窗为空：只可能在白天判（夜里手环离腕/没戴是正常的）
        if (inDay && p.cloudRows == 0) stale.add(CLOUD_EMPTY)

        val due = stale.isNotEmpty() && (lastKickTs <= 0 || p.nowS - lastKickTs >= minKickGapS)
        val note = when {
            stale.isEmpty() -> "上游新鲜"
            !due -> "过期 " + stale.joinToString("/") + "（刚催过，等它上传）"
            else -> "过期 " + stale.joinToString("/") + " → 催上游"
        }
        return Verdict(stale, due, note)
    }

    /** 中文名（状态文件/面板/通知都用同一套说法）。 */
    fun label(key: String): String = when (key) {
        SLEEP -> "睡眠"
        HEART_RATE -> "心率"
        STEPS -> "步数"
        SPO2 -> "血氧"
        CLOUD_EMPTY -> "云端整段没数据"
        else -> key
    }

    fun labels(keys: List<String>): String = keys.joinToString("、") { label(it) }

    /**
     * 该不该为这些过期项**发通知**。
     *
     * 只报“真的能说明上游停摆”的：缺昨晚睡眠、心率长时间不动、云端整段空。
     * 步数/血氧本来就是成批、按需上传的，只有它们过期时写进状态就够了
     * （2026-10-29 实测：坐着工作 3 小时步数不动，误报了一次提醒）。
     */
    fun notifyWorthy(stale: List<String>): Boolean =
        stale.any { it == SLEEP || it == HEART_RATE || it == CLOUD_EMPTY }

    /** 把 "停在多久前" 说人话。 */
    fun ageText(seconds: Long): String = when {
        seconds <= 0 -> "—"
        seconds < 3600 -> "${seconds / 60} 分钟"
        seconds < 86400 -> "${seconds / 3600} 小时"
        else -> "${seconds / 86400} 天"
    }
}
