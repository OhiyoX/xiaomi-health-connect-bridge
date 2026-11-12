package id.harness.hcbridge.logic

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 把同步失败翻译成"人能照着做"的一句话 —— 纯函数，可 JVM 单测。
 *
 * 由来：2026-10-18 平板（副机）四个 `query_*` 全部失败，但日志里只有一行 `UnknownHostException`、
 * 状态文件里 `notes` 是空的，谁也看不出该去哪修。实测根因是 **系统按 UID 拦掉了这个应用的联网权限**
 * （`run-as id.harness.hcbridge ping` → `sendmsg: Operation not permitted`；同机 shell 正常），
 * 所以提示必须直接指向"联网控制"，而不是只说"解析失败"。
 */
object SyncDiagnosis {

    const val NO_NETWORK =
        "解析不了域名/连不出去：多半是系统「联网控制」没给本应用联网权限（设置 → 应用 → 联网控制），或当前无网络"
    const val TIMEOUT = "连接超时：服务端不可达或被网络拦掉"
    const val TLS = "TLS 失败：证书/系统时间/中间设备有问题"
    const val BAD_URL = "远端地址不合法（设置里的服务地址要形如 https://host/mcp）"

    /** 一句话描述：异常类 + 消息 + 可执行提示。 */
    fun describe(t: Throwable): String = when (t) {
        is UnknownHostException -> "${t.javaClass.simpleName}: ${t.message}（$NO_NETWORK）"
        is ConnectException -> "${t.javaClass.simpleName}: ${t.message}（$TIMEOUT）"
        is SocketTimeoutException -> "${t.javaClass.simpleName}（$TIMEOUT）"
        is SSLException -> "${t.javaClass.simpleName}: ${t.message}（$TLS）"
        else -> "${t.javaClass.simpleName}: ${t.message}"
    }

    /** 给概览那行用的短提示（状态文件里也存一份）。 */
    fun shortHint(t: Throwable): String = when (t) {
        is UnknownHostException, is ConnectException, is SocketTimeoutException -> NO_NETWORK
        is SSLException -> TLS
        else -> t.javaClass.simpleName
    }
}
