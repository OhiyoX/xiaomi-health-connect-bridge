package id.harness.hcbridge

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 账号自愈页（app 内）：扫码，或走“账号密码登录（网页）”的 WebView。
 * 入口：通知点进来，或 `am start -n id.harness.hcbridge/.LoginActivity`。
 */
class LoginActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var status: TextView
    private lateinit var qrView: ImageView
    private var awaitingWebLogin = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 96, 32, 32)
        }
        status = TextView(this).apply {
            textSize = 15f
            text = "正在获取二维码…"
        }
        qrView = ImageView(this)
        val webLogin = Button(this).apply {
            text = "账号密码登录（网页）"
            setOnClickListener {
                awaitingWebLogin = true
                startActivity(Intent(this@LoginActivity, WebLoginActivity::class.java))
            }
        }
        root.addView(webLogin)
        root.addView(status)
        root.addView(qrView)
        setContentView(root)
        startLogin()
    }

    override fun onResume() {
        super.onResume()
        if (awaitingWebLogin) {
            awaitingWebLogin = false
            if (Prefs.hasCreds(this)) {
                status.text = "登录成功，已恢复同步。"
                qrView.postDelayed({ finish() }, 800)
            }
        }
    }

    private fun startLogin() {
        scope.launch {
            try {
                val qr = withContext(Dispatchers.IO) { CloudApi.fetchQr() }
                val bytes = withContext(Dispatchers.IO) { CloudApi.downloadQrImage(qr.qrUrl) }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                qrView.setImageBitmap(bmp)
                status.text = "用「小米运动健康」或「小米账号」App 扫上面的二维码（${qr.timeoutSec} 秒内）"
                val creds = withContext(Dispatchers.IO) { CloudApi.pollQr(qr.lpUrl, qr.timeoutSec) }
                if (creds == null) {
                    status.text = "扫码超时。点开这个页面重试即可。"
                } else {
                    Prefs.saveCreds(this@LoginActivity, creds.first, creds.second)
                    Prefs.setFails(this@LoginActivity, 0)
                    SyncWorker.schedule(this@LoginActivity)
                    SyncWorker.runNow(this@LoginActivity)
                    AppLog.log(this@LoginActivity, "login", "登录成功 userId=${creds.first}")
                    status.text = "登录成功（userId=${creds.first}），已恢复同步。"
                    qrView.postDelayed({ finish() }, 2000)
                }
            } catch (t: Throwable) {
                AppLog.log(this@LoginActivity, "login", "失败：$t")
                status.text = "失败：$t"
            }
        }
    }
}
