package id.harness.hcbridge

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import id.harness.hcbridge.auth.AdminGate
import id.harness.hcbridge.auth.AdminThrottle
import id.harness.hcbridge.auth.McpConfig
import id.harness.hcbridge.auth.McpStore
import id.harness.hcbridge.auth.TotpSecret
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * MCP 管理页的**默认**宿主（0.9.0）：解锁后把共享前端（`admin-ui/`，构建到
 * `app/src/main/assets/admin/`）加载进一个只认本地资产的 WebView，写操作走 [AdminBridge]。
 *
 * 保留了原生管理页（`McpAdminActivity`）的全部边界：
 * - 进入先过 [AdminGate]：首次设密码；之后密码 + 可选 TOTP，或用屏幕锁（同一个 [AdminThrottle]，
 *   失败计数、锁定、动态码重放闸都在进程级，返回键/旋转/切后台都不清零）；
 * - 解锁会话 [SESSION_TIMEOUT_MS] 无交互自动上锁；`onStop` 直接上锁；
 * - 窗口 `FLAG_SECURE`：管理页会短暂显示一次性凭据，禁止截屏/录屏；
 * - 失败解锁写审计（只写原因，不写密码/动态码）。
 *
 * WebView 加固（每一条都对应一个真实风险，别删）：
 * - **只加载本地资产**：`shouldInterceptRequest` 只放行 `file:///android_asset/admin/` 下的文件，
 *   其余一律空 403；`shouldOverrideUrlLoading` 拦掉任何跳转（http/https/intent/file 越界）。
 * - **没有网络**：`blockNetworkLoads = true`（App 有 INTERNET 权限，才能开这个开关），
 *   `allowFileAccess/allowContentAccess = false`（assets 仍可读），跨源文件访问关闭。
 * - **JS 桥只活在解锁会话里**：`addJavascriptInterface` 在解锁后、加载页面前挂；
 *   上锁/`onStop`/销毁时先 `removeJavascriptInterface` 再把 WebView 打到 `about:blank`。
 * - **没有 `/admin` HTTP 面**：管理能力只在这条桥上；`McpService` 对 `/admin*` 仍然 404。
 */
class McpWebAdminActivity : AppCompatActivity(), AdminBridge.Host {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var store: McpStore
    private lateinit var config: McpConfig
    private lateinit var web: WebView

    /** 本次解锁会话的动作层（上锁即丢弃：连 `totp-begin` 的待确认 secret 一起丢）。 */
    private var actions: AdminActions? = null

    /** 本次解锁会话的宿主环境（上锁即丢弃）。 */
    private var env: AndroidAdminEnv? = null

    /** JS 桥是否已挂上（只由 [attachBridge] / [detachBridge] 改）。 */
    private var bridgeAttached = false

    /** 每次加载递增：用它取消上一次的超时兜底，避免误关新页面的加载层。 */
    private var loadSeq = 0

    /** 失败解锁的审计节流（每 [FAIL_AUDIT_MIN_MS] 最多一条；锁定时不受限）。 */
    private var lastFailAuditAt = 0L

    private val sessionTimer = AdminSessionTimer(SESSION_TIMEOUT_MS, onExpired = { showGate() })

    private val deviceUnlock = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) {
            AdminThrottle.success()
            openSession()
        } else {
            auditUnlockFailure("device_credential")
            gateError("屏幕锁未通过")
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        applySystemBarAppearance()
        // 管理页会短暂显示一次性凭据（静态 key / client_secret / TOTP secret）：禁截屏
        if (!Prefs.adminAllowScreenshot(this)) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        setContentView(R.layout.activity_mcp_web_admin)
        store = Store.of(this)
        config = McpConfig(store)
        // 共享前端自带页头；原生 toolbar 只留返回手势，不再占一整行。
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            visibility = View.GONE
            setNavigationOnClickListener { finish() }
        }
        bindGate()
        bindWeb()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = navigateBack()
        })
        // 会话是进程级的：旋转/返回键回来还在有效期内就直接进，不要求重复验证
        if (AdminThrottle.isOpen(now())) openSession() else showGate()
    }

    /** 任何手/键交互都续期解锁会话（拦掉"页面放着不管就永远开着"）。 */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && adminVisible()) sessionTimer.touch()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && adminVisible()) sessionTimer.touch()
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        if (adminVisible()) {
            if (AdminThrottle.isOpen(now())) sessionTimer.schedule() else showGate()
        } else {
            // onStop 上锁后 webBox 已隐藏；回到前台必须把管理员门重新放出来，不能留空页/加载层。
            showGate()
        }
    }

    override fun onStop() {
        android.util.Log.i("HCBridgeAdmin", "onStop finishing=$isFinishing changing=$isChangingConfigurations")
        super.onStop()
        // 切后台就上锁；旋转只是重建界面，不该把进程级会话清掉。
        // finish 时直接卸桥、不再 loadUrl(about:blank)：销毁前再跑一次页面加载会卡住返回动画。
        if (!isChangingConfigurations) {
            if (isFinishing) {
                sessionTimer.cancel()
                AdminThrottle.lock()
                showLoading(false)
                detachBridge(loadBlank = false)
            } else {
                lockNow()
            }
        }
    }

    override fun onDestroy() {
        android.util.Log.i("HCBridgeAdmin", "onDestroy")
        sessionTimer.cancel()
        detachBridge(loadBlank = false)
        if (::web.isInitialized) {
            runCatching {
                findViewById<View>(R.id.webLoading).visibility = View.GONE
                web.visibility = View.GONE
                web.stopLoading()
                (web.parent as? android.view.ViewGroup)?.removeView(web)
                web.destroy()
            }
        }
        super.onDestroy()
        scope.cancel()
    }

    // ------------------------------------------------------------ WebView 宿管

    private fun bindWeb() {
        web = findViewById(R.id.webAdmin)
        with(web.settings) {
            javaScriptEnabled = true
            // 本地资产用 file:///android_asset 加载；这两项关掉后仍然读得到 assets
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            blockNetworkLoads = true          // App 有 INTERNET 权限，才能开这个开关
            blockNetworkImage = true
            domStorageEnabled = true          // 主题是页面唯一持久化状态（localStorage）
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            setGeolocationEnabled(false)
        }
        WebView.setWebContentsDebuggingEnabled(false)
        web.isVerticalScrollBarEnabled = true
        web.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // 只允许本地管理资产内部跳转；其它一律拦下（返回 true = 已处理/不加载）
                return !isAllowedAsset(request.url.toString())
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val url = request.url.toString()
                if (isAllowedAsset(url)) return null
                // 非管理资产（网络、其它 file:、content:）：空 403，不泄漏任何东西
                return WebResourceResponse(
                    "text/plain",
                    "utf-8",
                    403,
                    "Forbidden",
                    mapOf("Cache-Control" to "no-store"),
                    ByteArrayInputStream(ByteArray(0)),
                )
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (url == ABOUT_BLANK) return
                showLoading(true)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (!request.isForMainFrame) return
                showError("管理页加载失败：${error.description}")
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // WebView 渲染进程崩了：整个管理页不能再信，直接上锁并关闭
                lockNow()
                toast("管理页渲染进程异常，已锁定")
                finish()
                return true
            }
        }
    }

    private fun isAllowedAsset(url: String): Boolean =
        url == ABOUT_BLANK || url.startsWith(ASSET_DIR)

    /**
     * 挂上 JS 桥。
     *
     * `@SuppressLint("AddJavascriptInterface")` 的理由：这里**只**加载本应用打包的
     * `file:///android_asset/admin/index.html`（CSP 在页面 meta 里、无内联脚本、无外链资源），
     * 网络被 `blockNetworkLoads` 关闭、任何非资产请求都由 `shouldInterceptRequest` 回 403、
     * 跳转被 `shouldOverrideUrlLoading` 拦下；桥本身也只在解锁会话里存在（上锁即移除 +
     * `about:blank`）。也就是说没有任何第三方内容能拿到这个接口。
     */
    @SuppressLint("AddJavascriptInterface")
    private fun attachBridge() {
        if (bridgeAttached) return
        web.addJavascriptInterface(AdminBridge(this), BRIDGE_NAME)
        bridgeAttached = true
    }

    /**
     * 卸桥。`loadBlank=false` 用于正在 finish 的页面：WebView 马上会被销毁，
     * 再 loadUrl(about:blank) 会把 UI 线程拖在 WebView 回调里，返回原生页时表现为卡死。
     */
    private fun detachBridge(loadBlank: Boolean = true) {
        if (!::web.isInitialized) return
        if (bridgeAttached) runCatching { web.removeJavascriptInterface(BRIDGE_NAME) }
        bridgeAttached = false
        actions = null
        env = null
        findViewById<View>(R.id.webBox).visibility = View.GONE
        if (!loadBlank) return
        runCatching {
            web.stopLoading()
            web.loadUrl(ABOUT_BLANK)
        }
    }

    private fun showLoading(on: Boolean) {
        findViewById<View>(R.id.webLoading).visibility = if (on) View.VISIBLE else View.GONE
        if (on) findViewById<View>(R.id.webError).visibility = View.GONE
    }

    private fun showError(message: String) {
        loadSeq++
        findViewById<View>(R.id.webLoading).visibility = View.GONE
        findViewById<TextView>(R.id.webErrorText).text = message
        findViewById<View>(R.id.webError).visibility = View.VISIBLE
    }

    /** 返回键：先交给页面（抽屉/弹窗 → hash 路由），页面没事可做时才关闭管理页。 */
    private fun navigateBack() {
        android.util.Log.i("HCBridgeAdmin", "back bridge=$bridgeAttached visible=${adminVisible()}")
        showLoading(false)
        if (!bridgeAttached || !adminVisible()) {
            finish()
            return
        }
        var handled = false
        fun closeOnce(shouldFinish: Boolean) {
            if (handled) return
            handled = true
            if (shouldFinish) finish()
        }
        runCatching {
            web.evaluateJavascript(BACK_JS) { result ->
                closeOnce(result == null || !result.contains(BACK_RESULT))
            }
            // JS 正忙（布局/轮询）时 evaluateJavascript 可能迟迟不回调：别让返回键看起来卡死。
            web.postDelayed({ closeOnce(true) }, 350)
        }.onFailure { closeOnce(true) }
    }

    // ------------------------------------------------------- AdminBridge.Host

    override fun stateJson(): JSONObject {
        if (!bridgeAttached || !AdminThrottle.isOpen(now())) {
            return JSONObject().put("error", "管理会话已锁定，请重新解锁")
        }
        val env = this.env ?: return JSONObject().put("error", "管理页还没就绪")
        return AdminStateJson.build(store, config, env)
    }

    override fun actJson(name: String, payloadJson: String?): JSONObject {
        if (!bridgeAttached || !AdminThrottle.isOpen(now())) {
            return JSONObject().put("ok", false).put("error", "管理会话已锁定，请重新解锁")
        }
        val payload = if (payloadJson.isNullOrBlank()) {
            JSONObject()
        } else {
            runCatching { JSONObject(payloadJson) }.getOrElse {
                return JSONObject().put("ok", false).put("error", "参数不合法")
            }
        }
        val actions = this.actions ?: return JSONObject().put("ok", false).put("error", "管理页还没就绪")
        return actions.act(name, payload)
    }

    override fun copyToClipboard(text: String): JSONObject =
        SecretClipboard.copyBlocking(this, text, CLIP_LABEL)

    override fun closeFromPage() = runOnUiThread { if (!isFinishing) finish() }

    override fun pageReady() = runOnUiThread {
        loadSeq++
        findViewById<View>(R.id.webError).visibility = View.GONE
        showLoading(false)
    }

    override fun theme(): String {
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (night == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
    }

    // ------------------------------------------------------------- 管理员门

    private fun bindGate() {
        findViewById<MaterialButton>(R.id.btnGateUnlock).setOnClickListener { doUnlock() }
        findViewById<MaterialButton>(R.id.btnGateSetup).setOnClickListener { doSetup() }
        findViewById<MaterialButton>(R.id.btnGateDevice).setOnClickListener { requestDeviceCredential() }
        findViewById<MaterialButton>(R.id.btnWebRetry).setOnClickListener {
            onRetryLoad()
        }
    }

    private fun onRetryLoad() {
        if (!AdminThrottle.isOpen(now())) return showGate()
        showLoading(true)
        AdminHealthLoader.warm(this)
        attachBridge()
        startLoad()
    }

    private fun showGate() {
        lockNow()
        findViewById<View>(R.id.gateBox).visibility = View.VISIBLE
        findViewById<View>(R.id.webBox).visibility = View.GONE
        val setup = AdminGate.needsSetup(config)
        findViewById<TextView>(R.id.gateTitle).text = if (setup) "设置管理员密码" else "解锁 MCP 管理"
        findViewById<TextView>(R.id.gateHint).text = if (setup) {
            "第一次进入：先设一个管理员密码（至少 $MIN_PASSWORD 位）。它保护静态 key、客户端批准、工具策略与审计。"
        } else {
            "管理页可以发放静态 key、批准客户端、改策略与查看审计。先验证身份。"
        }
        findViewById<TextInputLayout>(R.id.gatePasswordWrap).hint =
            if (setup) "管理员密码（至少 $MIN_PASSWORD 位）" else "管理员密码"
        findViewById<View>(R.id.gatePassword2Wrap).visibility = if (setup) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.btnGateSetup).visibility = if (setup) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.btnGateUnlock).visibility = if (setup) View.GONE else View.VISIBLE
        val totpOn = !setup && TotpSecret.enabled(this)
        findViewById<View>(R.id.gateTotpWrap).visibility = if (totpOn) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.btnGateDevice).visibility =
            if (!setup && hasDeviceCredential()) View.VISIBLE else View.GONE
        clearGateInputs()
        gateError(null)
    }

    private fun doSetup() {
        val pw = text(R.id.gatePassword)
        val pw2 = text(R.id.gatePassword2)
        gateError(null)
        if (pw.length < MIN_PASSWORD) return gateError("密码至少 $MIN_PASSWORD 位")
        if (pw != pw2) return gateError("两次输入不一致")
        setGateBusy(true)
        scope.launch {
            val hash = withContext(Dispatchers.Default) { AdminGate.hash(pw) }
            config.adminPasswordHash = hash
            audit("admin_password_set", "ok", "setup")
            setGateBusy(false)
            openSession()
            toast("管理员密码已设置")
        }
    }

    private fun doUnlock() {
        val at = now()
        val wait = AdminThrottle.lockedFor(at)
        if (wait > 0) return gateError("尝试过多，请 ${(wait + 999) / 1000} 秒后再试")
        val pw = text(R.id.gatePassword)
        gateError(null)
        if (pw.isEmpty()) return gateError("请输入管理员密码")
        setGateBusy(true)
        val stored = config.adminPasswordHash
        scope.launch {
            val ok = withContext(Dispatchers.Default) { AdminGate.verify(pw, stored) }
            if (!ok) {
                setGateBusy(false)
                val locked = AdminThrottle.failure(at)
                auditUnlockFailure(if (locked) "locked" else "password")
                return@launch gateError(
                    if (locked) "尝试过多，已锁定 ${AdminGate.LOCK_SECONDS} 秒" else "密码不正确",
                )
            }
            if (TotpSecret.enabled(this@McpWebAdminActivity)) {
                val secret = TotpSecret.get(this@McpWebAdminActivity)
                val code = text(R.id.gateTotp)
                if (secret == null || !AdminThrottle.claimTotp(secret, code, now())) {
                    setGateBusy(false)
                    // 动态码也计入试错：拿到密码的人不能靠无限猜 6 位码绕过第二因子
                    val locked = AdminThrottle.failure(now())
                    auditUnlockFailure(if (locked) "locked" else "totp")
                    return@launch gateError(
                        if (locked) "动态码连续不正确，已锁定 ${AdminGate.LOCK_SECONDS} 秒"
                        else "动态码不正确或已用过，请等下一个 30 秒",
                    )
                }
            }
            AdminThrottle.success()
            // 老哈希迭代数偏低：本次密码已证明，顺势升级（不打扰用户、不用旧密码再问一遍）
            if (AdminGate.needsRehash(stored)) {
                val stronger = withContext(Dispatchers.Default) { AdminGate.hash(pw) }
                config.adminPasswordHash = stronger
                audit("admin_password_rehash", "ok", "${AdminGate.iterationsOf(stored) ?: 0}->${AdminGate.DEFAULT_ITERATIONS}")
            }
            setGateBusy(false)
            openSession()
        }
    }

    /**
     * 失败解锁写审计：只写原因（`password`/`totp`/`device_credential`/`locked`），**不写**密码或动态码。
     * 普通失败按 [FAIL_AUDIT_MIN_MS] 节流（连点 5 次只留一条），进入锁定那一笔永远写。
     */
    private fun auditUnlockFailure(reason: String) {
        val at = now()
        if (reason != "locked" && at - lastFailAuditAt < FAIL_AUDIT_MIN_MS) return
        lastFailAuditAt = at
        audit("admin_unlock", "failed", reason)
    }

    private fun requestDeviceCredential() {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val intent = km.createConfirmDeviceCredentialIntent("解锁 MCP 管理", "验证身份后才能管理 MCP") ?: run {
            gateError("这台设备没有锁屏")
            return
        }
        runCatching { deviceUnlock.launch(intent) }.onFailure { gateError("无法打开屏幕锁") }
    }

    private fun hasDeviceCredential(): Boolean =
        (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure

    /** 解锁成功：收起门、挂桥、加载本地共享前端。 */
    private fun openSession() {
        // 旋转会重建界面并重新走这里：只有"之前是锁着的"才算一次新的登录
        val fresh = !AdminThrottle.isOpen(now())
        AdminThrottle.touch(now())
        sessionTimer.schedule()
        if (fresh) lastUnlockAt = now()
        clearGateInputs()
        gateError(null)
        findViewById<View>(R.id.gateBox).visibility = View.GONE
        findViewById<View>(R.id.webBox).visibility = View.VISIBLE
        showLoading(true)
        val env = AndroidAdminEnv(
            ctx = this,
            onLock = { runOnUiThread { showGate() } },
            unlockAt = { lastUnlockAt },
        )
        this.env = env
        this.actions = AdminActions(store, config, env)
        attachBridge()
        // 健康查询可能扫几万条 HC 记录：先放后台，首屏 state() 只取缓存，页面会自己轮询。
        AdminHealthLoader.warm(this)
        startLoad()
    }

    /** 加载共享前端；加载层保留到页面真正渲染完成（`pageReady`），最多等 12 秒。 */
    private fun startLoad() {
        val seq = ++loadSeq
        showLoading(true)
        web.loadUrl(ASSET_INDEX)
        web.postDelayed({
            if (seq == loadSeq && findViewById<View>(R.id.webLoading).visibility == View.VISIBLE) {
                loadSeq++
                showError("管理页加载超时，请点重试")
            }
        }, LOAD_TIMEOUT_MS)
    }

    private fun clearGateInputs() {
        findViewById<TextInputEditText>(R.id.gatePassword).setText("")
        findViewById<TextInputEditText>(R.id.gatePassword2).setText("")
        findViewById<TextInputEditText>(R.id.gateTotp).setText("")
    }

    private fun gateError(msg: String?) {
        val v = findViewById<TextView>(R.id.gateError)
        v.text = msg.orEmpty()
        v.visibility = if (msg == null) View.GONE else View.VISIBLE
    }

    private fun setGateBusy(busy: Boolean) {
        findViewById<MaterialButton>(R.id.btnGateUnlock).isEnabled = !busy
        findViewById<MaterialButton>(R.id.btnGateSetup).isEnabled = !busy
        findViewById<View>(R.id.gateBusy).visibility = if (busy) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------- 会话生命周期

    private fun adminVisible(): Boolean =
        findViewById<View>(R.id.webBox).visibility == View.VISIBLE

    /** 上锁：清进程级会话、卸桥、把 WebView 打到 `about:blank`，再回到管理员门。 */
    private fun lockNow() {
        sessionTimer.cancel()
        AdminThrottle.lock()
        detachBridge()
    }

    private fun audit(action: String, result: String, detail: String?) =
        store.audit("admin", action, null, result, detail, now())

    private fun now(): Long = System.currentTimeMillis()

    private fun text(id: Int): String =
        findViewById<EditText>(id).text?.toString()?.trim().orEmpty()

    // ------------------------------------------------------------- 测试钩子

    /** 仪器测试：JS 桥当前是否挂在 WebView 上。 */
    internal fun bridgeAttachedForTest(): Boolean = bridgeAttached

    /** 仪器测试：WebView 当前 URL（上锁后应是 `about:blank`）。 */
    internal fun webUrlForTest(): String? = if (::web.isInitialized) web.url else null

    /** 仪器测试：管理内容是否可见（门是否已收起）。 */
    internal fun adminVisibleForTest(): Boolean = adminVisible()

    companion object {
        /** 共享前端入口（构建产物；`scripts/build-admin-ui.py` 生成，勿手改）。 */
        const val ASSET_INDEX = "file:///android_asset/admin/index.html"

        /** 允许加载的唯一目录（`shouldInterceptRequest` / 跳转白名单）。 */
        const val ASSET_DIR = "file:///android_asset/admin/"

        const val ABOUT_BLANK = "about:blank"

        /** `window.HcbAdmin`（adapter 写死的名字，改这里等于改协议）。 */
        const val BRIDGE_NAME = "HcbAdmin"

        /** 解锁会话在前台的有效期（无交互就自动上锁）：与原生页一致，5 分钟。 */
        const val SESSION_TIMEOUT_MS = 5 * 60_000L

        /** 失败解锁的审计最小间隔（锁定那一笔不受限）。 */
        const val FAIL_AUDIT_MIN_MS = 30_000L

        /** 页面脚本 12 秒还没 ready：收起转圈，给出可重试的错误，而不是永久白屏。 */
        const val LOAD_TIMEOUT_MS = 12_000L

        /** 管理员密码下限（与原生页 / `AdminActions` 一致）。 */
        const val MIN_PASSWORD = 8

        /** 剪贴板标签（只用于识别"是不是我们放的那份"，不含凭据本身）。 */
        const val CLIP_LABEL = "MCP"

        /** 最近一次成功解锁（进程内；页面用它显示"最近登录"）。 */
        @Volatile
        var lastUnlockAt: Long? = null
            private set

        /**
         * 返回键的 JS：先让页面处理（抽屉/弹窗 → hash 路由），页面没地方可退才有 `close`。
         * 结果串里出现 `back` 表示"页面已经消化了这次返回键"。
         */
        private const val BACK_RESULT = "back"

        private const val BACK_JS = "(function(){try{" +
            "if(window.HcbBack&&window.HcbBack()){return 'back';}" +
            "}catch(e){}" +
            "try{if(window.HcbAdmin&&typeof window.HcbAdmin.close==='function'){window.HcbAdmin.close();}}" +
            "catch(e){}return 'close';})()"
    }
}
