package id.harness.hcbridge

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import id.harness.hcbridge.auth.AdminGate
import id.harness.hcbridge.auth.AdminThrottle
import id.harness.hcbridge.auth.ClientRow
import id.harness.hcbridge.auth.McpConfig
import id.harness.hcbridge.auth.McpStatus
import id.harness.hcbridge.auth.McpStore
import id.harness.hcbridge.auth.OAuth
import id.harness.hcbridge.auth.PendingRow
import id.harness.hcbridge.auth.ToolGate
import id.harness.hcbridge.auth.TotpSecret
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

/**
 * 手机侧的授权同意页（计划 Task 7 / 决定 2、4）。
 *
 * 浏览器停在 `/authorize?req=<id>` 等待页时，页面里有 `hcbridge://oauth/consent?req=<id>` 链接；
 * 点开进这里，看过客户端名/回调主机/scope/工具数后**允许**或**拒绝**，浏览器刷新就拿到 code 或
 * `error=access_denied`。全程没有网页表单、没有 Cookie 会话 ⇒ 没有 CSRF 面。
 *
 * 门禁与 [McpAdminActivity] 共用进程级 [AdminThrottle]：刚在管理页解锁过就直接可点；
 * 会话过期/切后台要重新验证（密码 + 可选 TOTP，或屏幕锁）。
 *
 * 失效分支（都只给文案，不泄漏任何客户端细节）：请求不存在 / 已过期 / 已处理 / 客户端不可用 /
 * 客户端仍在待批准。
 */
class McpConsentActivity : AppCompatActivity() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main)

    private lateinit var store: McpStore
    private lateinit var config: McpConfig

    private val sessionTimer = AdminSessionTimer(SESSION_TIMEOUT_MS, onExpired = { showGate() })

    private var reqId: String? = null

    private val deviceUnlock = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) {
            AdminThrottle.success()
            openContent()
        } else {
            auditFailure("device_credential")
            gateError("屏幕锁未通过")
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        applySystemBarAppearance()
        setContentView(R.layout.activity_mcp_consent)
        store = Store.of(this)
        config = McpConfig(store)
        reqId = reqFrom(intent)
        findViewById<MaterialToolbar>(R.id.consentToolbar).setNavigationOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnConsentUnlock).setOnClickListener { doUnlock() }
        findViewById<MaterialButton>(R.id.btnConsentDevice).setOnClickListener { requestDeviceCredential() }
        findViewById<MaterialButton>(R.id.btnConsentAllow).setOnClickListener { decide(approve = true) }
        findViewById<MaterialButton>(R.id.btnConsentDeny).setOnClickListener { decide(approve = false) }
        findViewById<MaterialButton>(R.id.btnConsentAdmin).setOnClickListener { AdminEntry.open(this) }
        if (AdminThrottle.isOpen(now())) openContent() else showGate()
    }

    override fun onResume() {
        super.onResume()
        if (contentVisible()) {
            if (AdminThrottle.isOpen(now())) sessionTimer.schedule() else showGate()
        }
    }

    override fun onStop() {
        super.onStop()
        // 切后台就上锁；旋转只是重建界面，不该把进程级会话清掉
        sessionTimer.cancel()
        if (!isChangingConfigurations) AdminThrottle.lock()
    }

    override fun onDestroy() {
        sessionTimer.cancel()
        super.onDestroy()
        job.cancel()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN && contentVisible()) sessionTimer.touch()
        return super.dispatchTouchEvent(ev)
    }

    // ------------------------------------------------------------------ 查询

    /** `hcbridge://oauth/consent?req=<id>`；也接受 `Intent.putExtra("req", …)`（测试/内部调用）。 */
    private fun reqFrom(intent: Intent?): String? {
        val fromExtra = intent?.getStringExtra("req")?.trim()?.takeIf { it.isNotEmpty() }
        if (fromExtra != null) return fromExtra
        val data: Uri = intent?.data ?: return null
        return data.getQueryParameter("req")?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun pending(now: Long): PendingRow? = reqId?.let { store.pending(it) }

    private fun client(): ClientRow? = pending(now())?.let { store.client(it.clientId) }

    // ------------------------------------------------------------------ 门禁

    private fun showGate() {
        sessionTimer.cancel()
        AdminThrottle.lock()
        findViewById<View>(R.id.consentBox).visibility = View.GONE
        findViewById<View>(R.id.consentGateBox).visibility = View.VISIBLE
        val setupNeeded = AdminGate.needsSetup(config)
        findViewById<TextView>(R.id.consentGateTitle).text =
            if (setupNeeded) "先在 MCP 管理里设置管理员密码" else "先验证管理员身份"
        findViewById<TextView>(R.id.consentGateHint).text = if (setupNeeded) {
            "同意页会发放令牌，必须有管理员密码。打开 MCP 管理设置密码后，请在管理页的「待确认请求」里允许或拒绝。"
        } else {
            "允许/拒绝授权会发放令牌，只有管理员能点。"
        }
        findViewById<View>(R.id.consentPasswordWrap).visibility = if (setupNeeded) View.GONE else View.VISIBLE
        val totpOn = !setupNeeded && TotpSecret.enabled(this)
        findViewById<View>(R.id.consentTotpWrap).visibility = if (totpOn) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.btnConsentUnlock).visibility =
            if (setupNeeded) View.GONE else View.VISIBLE
        findViewById<MaterialButton>(R.id.btnConsentDevice).visibility =
            if (!setupNeeded && hasDeviceCredential()) View.VISIBLE else View.GONE
        findViewById<TextInputEditText>(R.id.consentPassword).setText("")
        findViewById<TextInputEditText>(R.id.consentTotp).setText("")
        gateError(null)
    }

    private fun doUnlock() {
        val at = now()
        val wait = AdminThrottle.lockedFor(at)
        if (wait > 0) return gateError("尝试过多，请 ${(wait + 999) / 1000} 秒后再试")
        val pw = findViewById<TextInputEditText>(R.id.consentPassword).text?.toString().orEmpty()
        if (pw.isEmpty()) return gateError("请输入管理员密码")
        gateError(null)
        val stored = config.adminPasswordHash
        scope.launch {
            val ok = withContext(Dispatchers.Default) { AdminGate.verify(pw, stored) }
            if (!ok) {
                val locked = AdminThrottle.failure(at)
                auditFailure(if (locked) "locked" else "password")
                return@launch gateError(
                    if (locked) "尝试过多，已锁定 ${AdminGate.LOCK_SECONDS} 秒" else "密码不正确",
                )
            }
            if (TotpSecret.enabled(this@McpConsentActivity)) {
                val secret = TotpSecret.get(this@McpConsentActivity)
                val code = findViewById<TextInputEditText>(R.id.consentTotp).text?.toString()
                if (secret == null || !AdminThrottle.claimTotp(secret, code, now())) {
                    val locked = AdminThrottle.failure(now())
                    auditFailure(if (locked) "locked" else "totp")
                    return@launch gateError(
                        if (locked) "动态码连续不正确，已锁定 ${AdminGate.LOCK_SECONDS} 秒"
                        else "动态码不正确或已用过，请等下一个 30 秒",
                    )
                }
            }
            AdminThrottle.success()
            if (AdminGate.needsRehash(stored)) {
                val stronger = withContext(Dispatchers.Default) { AdminGate.hash(pw) }
                config.adminPasswordHash = stronger
            }
            openContent()
        }
    }

    private fun requestDeviceCredential() {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val intent = km.createConfirmDeviceCredentialIntent("授权 MCP 客户端", "验证身份后才能允许或拒绝") ?: run {
            gateError("这台设备没有锁屏")
            return
        }
        runCatching { deviceUnlock.launch(intent) }.onFailure { gateError("无法打开屏幕锁") }
    }

    private fun hasDeviceCredential(): Boolean =
        (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure

    private fun gateError(msg: String?) {
        val v = findViewById<TextView>(R.id.consentError)
        v.text = msg.orEmpty()
        v.visibility = if (msg == null) View.GONE else View.VISIBLE
    }

    private fun contentVisible(): Boolean = findViewById<View>(R.id.consentBox).visibility == View.VISIBLE

    /** 解锁后：会话续期 + 渲染这次请求（可能已经失效）。 */
    private fun openContent() {
        AdminThrottle.touch(now())
        sessionTimer.schedule()
        gateError(null)
        findViewById<View>(R.id.consentGateBox).visibility = View.GONE
        findViewById<View>(R.id.consentBox).visibility = View.VISIBLE
        render()
    }

    // ------------------------------------------------------------------ 渲染

    private fun render() {
        val at = now()
        val row = pending(at)
        val allow = findViewById<MaterialButton>(R.id.btnConsentAllow)
        val deny = findViewById<MaterialButton>(R.id.btnConsentDeny)
        findViewById<TextView>(R.id.consentTitle).text = "授权请求"
        status(null)

        if (row == null) {
            detail("请求已失效，请回到浏览器重新发起。")
            allow.visibility = View.GONE
            deny.visibility = View.GONE
            return
        }
        if (row.expiresAt <= at) {
            detail("请求已过期，请回到浏览器重新发起。")
            allow.visibility = View.GONE
            deny.visibility = View.GONE
            return
        }
        if (row.status != McpStatus.PENDING) {
            detail(
                when (row.status) {
                    McpStatus.APPROVED -> "这条请求已经允许过，请回到浏览器刷新页面。"
                    McpStatus.DENIED -> "这条请求已经拒绝过，请回到浏览器刷新页面。"
                    else -> "这条请求已经处理过，请回到浏览器重新发起。"
                },
            )
            allow.visibility = View.GONE
            deny.visibility = View.GONE
            return
        }

        val client = store.client(row.clientId)
        when {
            client == null || client.revokedAt != null -> {
                detail("客户端不可用（可能已被吊销），请回到浏览器重新发起。")
                allow.visibility = View.GONE
                deny.visibility = View.GONE
            }
            client.status == McpStatus.CLIENT_PENDING -> {
                detail("该客户端待批准，请先在 MCP 管理里批准后再授权。")
                allow.visibility = View.GONE
                deny.visibility = View.GONE
            }
            else -> {
                findViewById<TextView>(R.id.consentTitle).text = "「${client.name.ifBlank { client.clientId }}」请求授权"
                detail(
                    buildString {
                        append("回调 ").append(hostOf(row.redirectUri))
                        append("\nscope ").append(row.scopes.joinToString(" "))
                        append("\n可用工具 ").append(toolCount(client, row.scopes)).append(" 个")
                        append("\n还剩 ").append((row.expiresAt - at) / 1000).append(" 秒过期")
                    },
                )
                allow.visibility = View.VISIBLE
                deny.visibility = View.VISIBLE
            }
        }
    }

    private fun detail(text: String) {
        findViewById<TextView>(R.id.consentDetail).text = text
    }

    private fun status(text: String?) {
        val v = findViewById<TextView>(R.id.consentStatus)
        v.text = text.orEmpty()
        v.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------ 决定

    private fun decide(approve: Boolean) {
        if (!AdminThrottle.isOpen(now())) return showGate()
        sessionTimer.touch()
        val id = reqId ?: return
        val at = now()
        val ok = OAuth.decidePending(store, id, approve, OAuth.ALLOWED_SCOPES, at)
        store.audit(
            "admin",
            if (approve) "pending_allow" else "pending_deny",
            null,
            if (ok) "ok" else "failed",
            "consent:" + id.takeLast(6),
            at,
        )
        findViewById<MaterialButton>(R.id.btnConsentAllow).visibility = View.GONE
        findViewById<MaterialButton>(R.id.btnConsentDeny).visibility = View.GONE
        if (!ok) {
            status("请求已失效，请回到浏览器重新发起。")
            return
        }
        status(
            if (approve) "已允许。回到浏览器刷新页面即可继续（授权码 5 分钟内有效）。"
            else "已拒绝。回到浏览器刷新页面即可看到结果。",
        )
    }

    // ---------------------------------------------------------------- 小工具

    private fun auditFailure(reason: String) {
        // 失败只记原因（不含密码/动态码）；锁定那笔一定写，普通失败由 store 的 2000 行上限兜住
        store.audit("admin", "admin_unlock", null, "failed", reason, now())
    }

    private fun now(): Long = System.currentTimeMillis()

    private fun hostOf(uri: String): String =
        runCatching { URI(uri).host }.getOrNull() ?: "（无法解析）"

    private fun toolCount(client: ClientRow, scopes: List<String>): Int {
        val granted = scopes.filterTo(LinkedHashSet()) { it in OAuth.ALLOWED_SCOPES }
        return ToolGate.effectiveTools(granted, listOfNotNull(client.toolPolicyJson)).size
    }

    private companion object {
        const val SESSION_TIMEOUT_MS = 5 * 60_000L
    }
}
