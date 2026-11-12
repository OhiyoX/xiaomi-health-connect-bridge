package id.harness.hcbridge.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * 夜间失败到底算不算失败 —— 守的是 2026-10-29 的回归：
 * 23:57→05:28 连续 5 轮 `Unable to resolve host "account.xiaomi.com"` 被当成普通失败，
 * `fails` 顶到 5 并发「连续失败」通知，纯噪音。
 */
class NetFailureTest {

    @Test
    fun `DNS 解析失败是瞬时的`() {
        assertTrue(NetFailure.isTransient(UnknownHostException("account.xiaomi.com")))
        assertTrue(
            NetFailure.isTransient(
                UnknownHostException("Unable to resolve host \"account.xiaomi.com\": No address associated with hostname")
            )
        )
    }

    @Test
    fun `超时与连不上是瞬时的`() {
        assertTrue(NetFailure.isTransient(SocketTimeoutException("timeout")))
        assertTrue(NetFailure.isTransient(ConnectException("Failed to connect to hlth.io.mi.com")))
        assertTrue(NetFailure.isTransient(SSLHandshakeException("Chain validation failed")))
    }

    @Test
    fun `异常被包在 cause 里也认得出来`() {
        val wrapped = RuntimeException("login failed", IOException("x", UnknownHostException("hlth.io.mi.com")))
        assertTrue(NetFailure.isTransient(wrapped))
    }

    @Test
    fun `SocketException 只认网络不可用那一类`() {
        assertTrue(NetFailure.isTransient(SocketException("Network is unreachable")))
        assertFalse(NetFailure.isTransient(SocketException("Socket closed")))
    }

    @Test
    fun `接口变更_解析报错不算瞬时（要报警）`() {
        assertFalse(NetFailure.isTransient(RuntimeException("接口 code=1001 参数错误")))
        assertFalse(NetFailure.isTransient(org.json.JSONException("expected : after key")))
        assertFalse(NetFailure.isTransient(null))
    }

    @Test
    fun `reason 带原文但不刷屏`() {
        val r = NetFailure.reason(UnknownHostException("account.xiaomi.com"))
        assertTrue(r.startsWith("网络未就绪："))
        assertTrue(r.length <= 96)
    }
}
