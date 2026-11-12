package id.harness.hcbridge.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * 同步失败的人话翻译 —— 2026-10-18 平板那次（四个 query_* 全失败但提示为空）就是这个功能缺位。
 */
class SyncDiagnosisTest {

    @Test
    fun `域名解析失败要直接指向系统联网控制`() {
        // 实测原文（平板 UID 被拦时 Java 抛的就是这句）
        val t = UnknownHostException("Unable to resolve host \"health.example.com\": No address associated with hostname")
        val msg = SyncDiagnosis.describe(t)
        assertTrue("应带异常原文：$msg", msg.contains("health.example.com"))
        assertTrue("必须指向联网控制，否则看不出该去哪修：$msg", msg.contains("联网控制"))
        assertEquals(SyncDiagnosis.NO_NETWORK, SyncDiagnosis.shortHint(t))
    }

    @Test
    fun `连接超时与 TLS 各自有提示`() {
        assertTrue(SyncDiagnosis.describe(ConnectException("Connection refused")).contains("超时"))
        assertTrue(SyncDiagnosis.describe(SocketTimeoutException("timeout")).contains("超时"))
        assertTrue(SyncDiagnosis.describe(SSLHandshakeException("bad cert")).contains("TLS"))
        assertTrue(SyncDiagnosis.describe(IllegalStateException("boom")).contains("IllegalStateException"))
    }

    @Test
    fun `未知异常只给类名 不编故事`() {
        assertEquals("RuntimeException", SyncDiagnosis.shortHint(RuntimeException("x")))
        assertEquals("RuntimeException: x", SyncDiagnosis.describe(RuntimeException("x")))
    }
}
