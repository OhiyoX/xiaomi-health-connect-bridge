package id.harness.hcbridge

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 小米健康云（hlth.io.mi.com）客户端：登录 + 心率拉取。
 *
 * 协议依据公开逆向结果（binglua/mi-fitness-mcp-cn、Misty02600/mi-fitness-python）：
 *   - 登录：GET account.xiaomi.com/pass/serviceLogin?_json=true&sid=miothealth
 *           Cookie: userId=…; passToken=…  → 返回 ssecurity / location / 新 passToken
 *   - 数据：POST hlth.io.mi.com/app/v1/data/get_fitness_data_by_time
 *           表单 data 经 RC4(signed_nonce) 加密，附 rc4_hash__ / signature / _nonce
 *
 * 关键稳定性设计：**每次登录都把返回的新 passToken 回写**（服务端会轮换），
 * 因此只要 30 分钟一次同步在跑，token 就不会"躺到过期"。
 */
object CloudApi {

    private const val TAG = "HCBridge"
    private const val LOGIN_URL = "https://account.xiaomi.com/pass/serviceLogin"
    private const val HEALTH_BASE = "https://hlth.io.mi.com"
    private const val FITNESS_PATH = "/app/v1/data/get_fitness_data_by_time"
    private const val SID = "miothealth"
    private const val UA = "Android-12-3.59.1-OnePlus-PJZ110"

    class AuthException(msg: String) : Exception(msg)

    data class Session(
        val userId: String,
        val passToken: String,
        val ssecurity: ByteArray,
        val cookies: String
    )

    // ---------------------------------------------------------------- 加密

    private fun rc4(key: ByteArray, data: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
        }
        var i = 0
        j = 0
        fun next(): Int {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
            return s[(s[i] + s[j]) and 0xFF]
        }
        repeat(1024) { next() }
        val out = ByteArray(data.size)
        for (k in data.indices) out[k] = (data[k].toInt() xor next()).toByte()
        return out
    }

    private fun nonce(): ByteArray {
        val rnd = ByteArray(8)
        java.security.SecureRandom().nextBytes(rnd)
        val minute = (System.currentTimeMillis() / 60000L).toInt()
        return rnd + byteArrayOf(
            ((minute ushr 24) and 0xFF).toByte(),
            ((minute ushr 16) and 0xFF).toByte(),
            ((minute ushr 8) and 0xFF).toByte(),
            (minute and 0xFF).toByte()
        )
    }

    private fun signedNonce(ssecurity: ByteArray, nonce: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(ssecurity)
        md.update(nonce)
        return md.digest()
    }

    private fun signature(method: String, path: String, values: Map<String, String>, sn: ByteArray): String {
        var base = "$method&$path&data=${values["data"]}"
        values["rc4_hash__"]?.let { base += "&rc4_hash__=$it" }
        base += "&" + Base64.getEncoder().encodeToString(sn)
        val sha1 = MessageDigest.getInstance("SHA-1").digest(base.toByteArray())
        return Base64.getEncoder().encodeToString(sha1)
    }

    // ---------------------------------------------------------------- HTTP

    private fun once(url: String, method: String = "GET", body: ByteArray? = null,
                     headers: Map<String, String> = emptyMap()): Triple<Int, String, List<String>> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20000
            readTimeout = 30000
            instanceFollowRedirects = false
            for ((k, v) in headers) setRequestProperty(k, v)
            setRequestProperty("User-Agent", UA)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                outputStream.use { it.write(body) }
            }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use(BufferedReader::readText) ?: ""
        val setCookies = conn.headerFields["Set-Cookie"] ?: emptyList()
        conn.disconnect()
        return Triple(code, text, setCookies)
    }

    private fun parseMi(text: String): JSONObject {
        val body = text.removePrefix("&&&START&&&")
        return JSONObject(body)
    }

    private fun cookieMap(setCookies: List<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in setCookies) {
            val head = line.substringBefore(';')
            val idx = head.indexOf('=')
            if (idx > 0) out[head.substring(0, idx).trim()] = head.substring(idx + 1).trim()
        }
        return out
    }

    // ---------------------------------------------------------------- 登录

    /** 用 userId/passToken 换会话；返回的 passToken 是**新的**，调用方应回写。 */
    fun login(userId: String, passToken: String): Session {
        val (code, text, hdrs) = once(
            "$LOGIN_URL?_json=true&sid=$SID",
            headers = mapOf("Cookie" to "userId=$userId; passToken=$passToken")
        )
        if (code != 200) throw AuthException("serviceLogin HTTP $code")
        val data = parseMi(text)
        val rc = data.optInt("code", 0)
        if (rc != 0) throw AuthException("serviceLogin code=$rc ${data.optString("description")}")
        val newPass = data.optString("passToken").ifEmpty { passToken }
        val uid = data.optString("userId").ifEmpty { userId }
        val ss = data.optString("ssecurity")
        if (ss.isEmpty()) throw AuthException("serviceLogin 没返回 ssecurity")
        val ssecurity = Base64.getDecoder().decode(ss)

        val cookies = LinkedHashMap(cookieMap(hdrs))
        val location = data.optString("location")
        if (location.isNotEmpty()) {
            // 跟随一次重定向拿业务 cookie（serviceToken 等）
            val (c2, _, h2) = once(location, headers = mapOf("User-Agent" to UA))
            if (c2 in 200..399) cookies.putAll(cookieMap(h2))
        }
        return Session(uid, newPass, ssecurity, cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
    }

    // ---------------------------------------------------------------- 二维码登录（app 内自愈用）

    data class QrInfo(val qrUrl: String, val loginUrl: String, val lpUrl: String, val timeoutSec: Int)

    private const val QR_URL = "https://account.xiaomi.com/longPolling/loginUrl"

    fun fetchQr(): QrInfo {
        val qs = java.net.URLEncoder.encode("?sid=$SID&_json=true", "UTF-8")
        val url = QR_URL + "?" + listOf(
            "_qrsize" to "480",
            "qs" to qs,
            "callback" to "https://sts-hlth.io.mi.com/healthapp/sts",
            "_hasLogo" to "false",
            "sid" to SID,
            "serviceParam" to "",
            "_locale" to "zh_CN",
            "_dc" to System.currentTimeMillis().toString()
        ).joinToString("&") { (k, v) -> "$k=" + java.net.URLEncoder.encode(v, "UTF-8") }
        val (code, text, _) = once(url)
        if (code != 200) throw RuntimeException("取二维码 HTTP $code")
        val d = parseMi(text)
        return QrInfo(
            qrUrl = d.optString("qr"),
            loginUrl = d.optString("loginUrl"),
            lpUrl = d.optString("lp"),
            timeoutSec = d.optInt("timeout", 300)
        )
    }

    /** 下载二维码 PNG 字节 */
    fun downloadQrImage(qrUrl: String): ByteArray {
        val conn = (URL(qrUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 20000
            setRequestProperty("User-Agent", UA)
        }
        val bytes = conn.inputStream.use { it.readBytes() }
        conn.disconnect()
        return bytes
    }

    /** 长轮询等待扫码；返回 (userId, passToken) 或 null（超时） */
    fun pollQr(lpUrl: String, timeoutSec: Int = 300): Pair<String, String>? {
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            val (code, text, hdrs) = once(lpUrl, headers = mapOf("User-Agent" to LOGIN_UA))
            if (code == 200) {
                val d = parseMi(text)
                val ck = cookieMap(hdrs)
                val uid = d.optString("userId").ifEmpty { ck["userId"] ?: "" }
                val pass = d.optString("passToken").ifEmpty { ck["passToken"] ?: "" }
                if (uid.isNotEmpty() && pass.isNotEmpty()) return uid to pass
            }
            Thread.sleep(2000)
        }
        return null
    }

    private const val LOGIN_UA =
        "Dalvik/2.1.0 (Linux; U; Android 12; PJZ110 Build/ab8c0d1.1) APP/mi.health APPV/359001 MK/U0FKMQ SDKV/5.3.0.release.68 CPN/com.mi.health PassportSDK/"

    // ---------------------------------------------------------------- 数据

    private fun apiPost(session: Session, path: String, payload: String): JSONObject {
        val sn: ByteArray
        val form = LinkedHashMap<String, String>()
        form["data"] = payload
        val nonce = nonce()
        sn = signedNonce(session.ssecurity, nonce)
        form["rc4_hash__"] = signature("POST", path, form, sn)
        val enc = LinkedHashMap<String, String>()
        for ((k, v) in form) {
            enc[k] = Base64.getEncoder().encodeToString(rc4(sn, v.toByteArray()))
        }
        enc["signature"] = signature("POST", path, enc, sn)
        enc["_nonce"] = Base64.getEncoder().encodeToString(nonce)
        val body = enc.entries.joinToString("&") { "${it.key}=${urlEnc(it.value)}" }.toByteArray()

        val (code, text, _) = once(
            HEALTH_BASE + path, method = "POST", body = body,
            headers = mapOf("Cookie" to session.cookies, "Content-Type" to "application/x-www-form-urlencoded")
        )
        if (code != 200) throw RuntimeException("接口 HTTP $code")
        val plain = String(rc4(sn, Base64.getDecoder().decode(text.trim())))
        val data = JSONObject(plain)
        if (data.optInt("code", -1) != 0) {
            val msg = data.optString("message")
            if (data.optInt("code") in listOf(401, 403, -6, -10001) ||
                msg.contains("auth", true) || msg.contains("token", true)
            ) throw AuthException("接口 code=${data.optInt("code")} $msg")
            throw RuntimeException("接口 code=${data.optInt("code")} $msg")
        }
        return data.optJSONObject("result") ?: JSONObject()
    }

    /** 通用：拉任意 key（steps/sleep/spo2/calories…），返回 (epoch, value-JSON) 升序。 */
    fun fetchValues(session: Session, key: String, startTs: Long, endTs: Long): List<Pair<Long, JSONObject>> {
        val out = ArrayList<Pair<Long, JSONObject>>()
        var nextKey: String? = null
        var page = 0
        while (true) {
            if (++page > 200) break
            val p = JSONObject()
                .put("start_time", startTs)
                .put("end_time", endTs)
                .put("key", key)
            nextKey?.let { p.put("next_key", it) }
            val result = apiPost(session, FITNESS_PATH, p.toString())
            val arr = result.optJSONArray("data_list")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val t = item.optLong("time", 0)
                    if (t <= 0) continue
                    val v = item.opt("value")
                    val vj = when (v) {
                        is JSONObject -> v
                        is String -> runCatching { JSONObject(v) }.getOrNull()
                        else -> null
                    } ?: continue
                    out.add(t to vj)
                }
            }
            val more = result.optBoolean("has_more", false)
            val nk = result.optString("next_key")
            if (!more || nk.isEmpty() || nk == nextKey) break
            nextKey = nk
        }
        out.sortBy { it.first }
        Log.i(TAG, "fetchValues $key: ${out.size} 条 in [$startTs,$endTs]")
        return out
    }

    private fun urlEnc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** 拉取 [startTs, endTs] 内的心率采样，返回 (epochSeconds, bpm) 升序。 */
    fun fetchHeartRate(session: Session, startTs: Long, endTs: Long): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>()
        var nextKey: String? = null
        var page = 0
        while (true) {
            if (++page > 100) break
            val p = JSONObject()
                .put("start_time", startTs)
                .put("end_time", endTs)
                .put("key", "heart_rate")
            nextKey?.let { p.put("next_key", it) }
            val result = apiPost(session, FITNESS_PATH, p.toString())
            val arr = result.optJSONArray("data_list")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val t = item.optLong("time", 0)
                    if (t <= 0) continue
                    val v = item.opt("value")
                    val vj = when (v) {
                        is JSONObject -> v
                        is String -> runCatching { JSONObject(v) }.getOrNull()
                        else -> null
                    } ?: continue
                    val bpm = when {
                        vj.has("bpm") -> vj.optLong("bpm", 0)
                        vj.has("heart_rate") -> vj.optLong("heart_rate", 0)
                        else -> 0
                    }
                    if (bpm in 20..250) out.add(t to bpm)
                }
            }
            val more = result.optBoolean("has_more", false)
            val nk = result.optString("next_key")
            if (!more || nk.isEmpty() || nk == nextKey) break
            nextKey = nk
        }
        out.sortBy { it.first }
        Log.i(TAG, "fetchHeartRate: ${out.size} samples in [$startTs,$endTs]")
        return out
    }
}
