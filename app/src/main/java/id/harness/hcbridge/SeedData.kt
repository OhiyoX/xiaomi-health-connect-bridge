package id.harness.hcbridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sin

/**
 * 仅供 **debug 构建** 的演示数据（方便在模拟器/无手环环境验证图表观感）。
 * 通过广播触发：`am broadcast -a id.harness.hcbridge.CMD_SEED`。
 * release 构建里 CmdReceiver 会拒绝执行。
 */
object SeedData {

    suspend fun run(ctx: Context): String = withContext(Dispatchers.IO) {
        val client = runCatching { HealthConnectClient.getOrCreate(ctx) }.getOrNull()
            ?: return@withContext "Health Connect 不可用"
        val now = System.currentTimeMillis() / 1000
        var out = ArrayList<String>()

        // 心率：24h，每 5 分钟；白天高、夜间低，带一点噪声
        runCatching {
            val list = ArrayList<Pair<Long, Long>>()
            var t = now - 24 * 3600
            var i = 0
            while (t <= now) {
                val hour = ((t % 86400) / 3600).toInt()
                val base = if (hour in 0..6) 58.0 else 72.0
                val v = (base + 6 * sin(i / 9.0) + ((i * 37) % 9) - 4).toInt().coerceIn(48, 132)
                list.add(t to v.toLong())
                t += 300
                i++
            }
            val (n, _) = HcWriter.insertHeartRate(client, list)
            out.add("心率 $n")
        }

        // 血氧：24h，每 10 分钟；基線 97，穿插 3 段掉到 89-92
        runCatching {
            val list = ArrayList<Pair<Long, Double>>()
            var t = now - 24 * 3600
            var i = 0
            while (t <= now) {
                val dip = (i % 47) in 20..22
                val v = when {
                    dip -> 89.0 + (i % 3)
                    else -> 96.0 + (i % 3)
                }
                list.add(t to v)
                t += 600
                i++
            }
            val n = HcWriter.insertSpo2(client, list)
            out.add("血氧 $n")
        }

        // 睡眠：近 7 晚 23:30 左右入睡 → 07:30 左右起床（跨零点）
        runCatching {
            val sessions = ArrayList<Triple<Long, Long, String>>()
            val day = 86400L
            val midnight = (now / day) * day
            for (k in 1..7) {
                val bed = midnight - k * day + (23 * 3600 + 20 * 60 + k * 90)
                val wake = bed + (7 * 3600 + 40 * 60 - k * 120)
                sessions.add(Triple(bed, wake, "演示睡眠"))
            }
            val n = HcWriter.insertSleep(client, sessions)
            out.add("睡眠 $n")
        }

        // 步数：今天按小时
        runCatching {
            val hourly = ArrayList<Triple<Long, Long, Double>>()
            val day = 86400L
            val midnight = (now / day) * day
            for (h in 0..23) {
                val steps = when (h) {
                    in 0..6 -> 10L
                    in 7..9 -> 900L
                    in 10..17 -> 450L
                    in 18..20 -> 1200L
                    else -> 120L
                }
                hourly.add(Triple(midnight + h * 3600, steps, steps * 0.72))
            }
            val n = HcWriter.insertSteps(client, hourly)
            out.add("步数 $n")
        }

        "已写入：" + out.joinToString("，")
    }
}
