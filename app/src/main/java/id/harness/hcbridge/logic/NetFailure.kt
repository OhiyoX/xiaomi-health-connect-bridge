package id.harness.hcbridge.logic

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 「这次失败是环境问题，还是同步链路坏了」——纯判定，可单测。
 *
 * 2026-10-29 夜间实测：23:57 → 05:28 连续 5 轮 `Unable to resolve host "account.xiaomi.com"`。
 * 手机夜里掉网/DNS 不解析是常态（深睡 + 省电 + 手环蓝牙），但旧实现把它当普通失败：
 * `fails` 一路顶到 5、状态写 `error: …`、还会发「连续失败 N 次」的通知 —— 纯噪音，
 * 而且真出问题时人已经被训练成忽略它了。
 *
 * 所以：DNS 解析失败 / 超时 / 连不上 / TLS 握手失败 → **瞬时（环境）**，
 * 不算失败、不报警、等下个周期；只有别的异常（接口变更、解析报错、写 HC 被拒…）才计失败。
 */
object NetFailure {

    fun isTransient(t: Throwable?): Boolean {
        var e = t
        var guard = 0
        while (e != null && guard++ < 8) {
            when (e) {
                is UnknownHostException,
                is SocketTimeoutException,
                is ConnectException,
                is NoRouteToHostException,
                is PortUnreachableException,
                is SSLException -> return true
            }
            // SocketException 太宽（也有 "Socket closed" 这种真问题），只认网络不可用那一类
            if (e is SocketException) {
                val m = (e.message ?: "").lowercase()
                if (m.contains("unreachable") || m.contains("network is down") ||
                    m.contains("enetunreach") || m.contains("ehostunreach") ||
                    m.contains("no route") || m.contains("dns")
                ) return true
            }
            val msg = (e.message ?: "").lowercase()
            if (msg.contains("unable to resolve host") || msg.contains("no address associated with hostname") ||
                msg.contains("name or service not known") || msg.contains("timed out") ||
                msg.contains("unreachable") || msg.contains("network is unreachable")
            ) return true
            e = e.cause
        }
        return false
    }

    /** 给日志/状态用的一句话。 */
    fun reason(t: Throwable?): String {
        val e = t ?: return "网络未就绪"
        val msg = (e.message ?: e.javaClass.simpleName).take(80)
        return "网络未就绪：" + msg
    }

    fun isNetworkUnavailable(t: Throwable?): Boolean = isTransient(t)
}
