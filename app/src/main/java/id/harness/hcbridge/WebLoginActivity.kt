package id.harness.hcbridge

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import id.harness.hcbridge.logic.LoginCookie

/**
 * 账号密码登录（app 内 WebView）。
 *
 * 为什么不用 Chrome/Custom Tabs：登录后的 `passToken` 是 account.xiaomi.com 的 HttpOnly cookie，
 * 存在浏览器自己的进程里，本 app 读不到；小米健康云这个 sid 也没有可回调的 OAuth code。
 * WebView 跑在本进程，登录成功后可以从 CookieManager 取到这两个 cookie。
 */
class WebLoginActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var web: WebView
    private val poll = Handler(Looper.getMainLooper())
    private var done = false

    private val checkCookies = object : Runnable {
        override fun run() {
            if (done) return
            val cookie = CookieManager.getInstance().getCookie(LOGIN_ORIGIN)
            val creds = LoginCookie.parse(cookie)
            if (creds != null) {
                done = true
                save(creds.first, creds.second)
            } else {
                poll.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 16)
        }
        status = TextView(this).apply {
            textSize = 14f
            text = "在下面的页面登录小米账号（账号密码或短信验证码）。登录成功会自动保存并恢复同步。"
        }
        web = WebView(this)
        root.addView(status)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            // 小米登录页可能按 UA 识别 WebView；只去掉 "; wv" 标记，不伪装成别的浏览器
            userAgentString = userAgentString.replace("; wv", "")
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                val host = req.url.host.orEmpty()
                val allowed = host.endsWith("xiaomi.com") || host.endsWith("mi.com")
                if (!allowed) Toast.makeText(this@WebLoginActivity, "已拦截跳转：$host", Toast.LENGTH_SHORT).show()
                return !allowed
            }

            override fun onPageFinished(v: WebView, url: String?) {
                checkCookies.run()
            }

            override fun onReceivedError(v: WebView, req: WebResourceRequest, err: WebResourceError) {
                status.text = "网络错误：${err.description}（返回可重试）"
            }
        }
        web.loadUrl(LOGIN_PAGE)
        poll.postDelayed(checkCookies, 1000)
    }

    private fun save(userId: String, passToken: String) {
        Prefs.saveCreds(this, userId, passToken)
        Prefs.setFails(this, 0)
        SyncWorker.schedule(this)
        SyncWorker.runNow(this)
        AppLog.log(this, "login", "网页登录成功 userId=$userId")
        // 凭据已进加密存储，清掉 WebView 里的会话副本
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        status.text = "登录成功（userId=$userId），已恢复同步。"
        Toast.makeText(this, "登录成功", Toast.LENGTH_SHORT).show()
        web.postDelayed({ if (!isFinishing) finish() }, 800)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        poll.removeCallbacksAndMessages(null)
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val LOGIN_ORIGIN = "https://account.xiaomi.com"
        private const val LOGIN_PAGE = "$LOGIN_ORIGIN/pass/serviceLogin?sid=miothealth&_locale=zh_CN"
    }
}
