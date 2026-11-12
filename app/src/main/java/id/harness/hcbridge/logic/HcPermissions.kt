package id.harness.hcbridge.logic

/**
 * Health Connect 权限清单（纯常量 + 纯函数，可单测）。
 *
 * 2026-10-28 实机取证：系统把一部分 HC 权限回收了（`WRITE_SLEEP`/`WRITE_STEPS`/`WRITE_OXYGEN_SATURATION`
 * 全是 `granted=false`），但 app 的同步前自检**只看心率的 2 项**，于是：
 * 心率照常写、睡眠/血氧的写入被 HC 拒绝成 `sleepErr=android.health.connect.HealthConnectException`，
 * 状态文件还是写着 `ok-no-new` —— 外面看“正常”，实际那几类一条都没进来。
 *
 * 所以权限检查必须按**同步真正用到的全集**来算，并且要能说人话（缺哪项、影响哪类数据）。
 * 分两档：
 *  - [core]：缺了整轮同步没法进行（心率读 + 写）；
 *  - [used]：缺了不整轮失败，但对应的数据类型会静默失败 → 必须报警，不能吞。
 */
object HcPermissions {

    private const val P = "android.permission.health."

    const val READ_HR = P + "READ_HEART_RATE"
    const val WRITE_HR = P + "WRITE_HEART_RATE"
    const val BACKGROUND = P + "READ_HEALTH_DATA_IN_BACKGROUND"
    const val HISTORY = P + "READ_HEALTH_DATA_HISTORY"

    /** 缺了就别同步了（心率的读 + 写是整条链路的地基）。 */
    val core: List<String> = listOf(READ_HR, WRITE_HR)

    /** 同步链路真正会用到的全集（缺任意一项 ⇒ 某类数据会静默写不进去）。 */
    val used: List<String> = listOf(
        READ_HR, WRITE_HR, BACKGROUND, HISTORY,
        P + "READ_STEPS", P + "WRITE_STEPS",
        P + "READ_SLEEP", P + "WRITE_SLEEP",
        P + "READ_OXYGEN_SATURATION", P + "WRITE_OXYGEN_SATURATION",
        P + "READ_DISTANCE", P + "WRITE_DISTANCE",
        P + "READ_ACTIVE_CALORIES_BURNED", P + "WRITE_ACTIVE_CALORIES_BURNED",
        P + "READ_TOTAL_CALORIES_BURNED", P + "WRITE_TOTAL_CALORIES_BURNED",
        P + "READ_WEIGHT", P + "WRITE_WEIGHT",
        P + "READ_BODY_FAT", P + "WRITE_BODY_FAT",
        P + "READ_RESPIRATORY_RATE", P + "WRITE_RESPIRATORY_RATE",
        P + "READ_HEART_RATE_VARIABILITY", P + "WRITE_HEART_RATE_VARIABILITY",
        P + "READ_EXERCISE",
        P + "READ_BASAL_METABOLIC_RATE", P + "WRITE_BASAL_METABOLIC_RATE",
        P + "READ_BONE_MASS", P + "WRITE_BONE_MASS",
        P + "READ_LEAN_BODY_MASS", P + "WRITE_LEAN_BODY_MASS",
        P + "READ_BODY_WATER_MASS", P + "WRITE_BODY_WATER_MASS",
        P + "READ_HEIGHT", P + "WRITE_HEIGHT",
    )

    fun missing(granted: Set<String>, from: List<String> = used): List<String> =
        from.filter { it !in granted }

    /** 说人话：`WRITE_SLEEP` → 「写睡眠」。 */
    fun label(perm: String): String {
        val k = perm.substringAfterLast('.')
        val write = k.startsWith("WRITE_")
        val what = k.removePrefix("READ_").removePrefix("WRITE_")
        val cn = when (what) {
            "HEART_RATE" -> "心率"
            "STEPS" -> "步数"
            "SLEEP" -> "睡眠"
            "OXYGEN_SATURATION" -> "血氧"
            "DISTANCE" -> "距离"
            "ACTIVE_CALORIES_BURNED" -> "活动卡路里"
            "TOTAL_CALORIES_BURNED" -> "总卡路里"
            "WEIGHT" -> "体重"
            "BODY_FAT" -> "体脂"
            "RESPIRATORY_RATE" -> "呼吸率"
            "HEART_RATE_VARIABILITY" -> "HRV"
            "EXERCISE" -> "运动"
            "BASAL_METABOLIC_RATE" -> "基础代谢"
            "BONE_MASS" -> "骨量"
            "LEAN_BODY_MASS" -> "去脂体重"
            "BODY_WATER_MASS" -> "体水分"
            "HEIGHT" -> "身高"
            "HEALTH_DATA_IN_BACKGROUND" -> "后台读"
            "HEALTH_DATA_HISTORY" -> "读 30 天前历史"
            else -> what.lowercase()
        }
        return when {
            what == "HEALTH_DATA_IN_BACKGROUND" || what == "HEALTH_DATA_HISTORY" -> cn
            what == "EXERCISE" -> "读运动"
            write -> "写$cn"
            else -> "读$cn"
        }
    }

    /** 一行的说法：`写睡眠、写步数 等 5 项`。 */
    fun describe(missing: List<String>, max: Int = 4): String {
        val head = missing.take(max).joinToString("、") { label(it) }
        return if (missing.size > max) "$head 等 ${missing.size} 项" else head
    }
}
