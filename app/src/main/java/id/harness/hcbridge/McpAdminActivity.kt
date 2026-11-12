package id.harness.hcbridge

import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import id.harness.hcbridge.auth.AdminGate
import id.harness.hcbridge.auth.AdminThrottle
import id.harness.hcbridge.auth.ClientRow
import id.harness.hcbridge.auth.Crypto
import id.harness.hcbridge.auth.KeyRow
import id.harness.hcbridge.auth.McpConfig
import id.harness.hcbridge.auth.McpRouter
import id.harness.hcbridge.auth.McpStatus
import id.harness.hcbridge.auth.McpStore
import id.harness.hcbridge.auth.OAuth
import id.harness.hcbridge.auth.OAuthError
import id.harness.hcbridge.auth.TimeWindow
import id.harness.hcbridge.auth.TokenFormat
import id.harness.hcbridge.auth.TokenRow
import id.harness.hcbridge.auth.ToolGate
import id.harness.hcbridge.auth.ToolPolicy
import id.harness.hcbridge.auth.Totp
import id.harness.hcbridge.auth.TotpSecret
import id.harness.hcbridge.auth.Tunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * app 内的 MCP 管理页（计划 Task 6）。**只有这个 Activity 能改策略/发 key/批客户端** ——
 * 没有任何 HTTP 管理路由（`/admin` 一律 404，Task 7 的反例测试守着这一点）。
 *
 * 进入先过 [AdminGate]（首次设密码；之后密码 + 可选 TOTP，或用屏幕锁）。解锁后按段渲染：
 * 待确认请求 / 客户端 / 令牌与静态 key / 安全 / 审计。「状态」「公网」两段归 Task 7、Task 8。
 *
 * 不变式：
 * - 明文凭据（静态 key、TOTP secret）只在创建那一刻出现在一个对话框里，库里只有哈希/密文；
 * - 所有策略/时间窗输入先过 `canonical`，失败**不写库**（保持原值）；
 * - 每个动作写一条 `store.audit("admin", …)`，`detail` 只放标签/前缀，绝不放凭据；
 * - 窗口 `FLAG_SECURE`：管理页不允许截屏/录屏（key 与 secret 会短暂显示）；
 * - 门禁状态（试错计数/解锁会话/动态码占用）在 [AdminThrottle] 里，活得比本 Activity 久：
 *   返回键、旋转、切后台都不会把它们清零；解锁满 [SESSION_TIMEOUT_MS] 由前台定时器自动上锁，
 *   `onStop` 也直接上锁。
 */
class McpAdminActivity : AppCompatActivity() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main)

    private lateinit var store: McpStore
    private lateinit var config: McpConfig

    private var filterActor: String? = null
    private var filterAction: String? = null
    private var filterResult: String? = null

    /**
     * 「公网」段里 chips 的**草稿**：点了只改这里，按「保存公网设置」才落库。
     * 公网基址/token 一起保存 ⇒ 不会出现"mode 已换、地址还是旧的"的中间态。
     */
    private var draftTunnelMode: String = McpConfig.TUNNEL_OFF

    /** 最近一次公网探活（只活在进程里；不进 settings、不落审计）。 */
    private var lastProbe: TunnelProbe.Result? = null
    private var lastProbeBase: String? = null

    /** 前台定时上锁：解锁/每次交互都续期；`onStop`/`onDestroy` 取消。 */
    private val sessionTimer = AdminSessionTimer(SESSION_TIMEOUT_MS, onExpired = { showGate() })

    /** 打开着的对话框：Activity 走 onDestroy 时先收掉，避免窗口泄漏。 */
    private val dialogs = ArrayList<AlertDialog>()

    /** 失败解锁的审计节流（每 [FAIL_AUDIT_MIN_MS] 最多一条；锁定时不受限）。 */
    private var lastFailAuditAt = 0L

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
        // 管理页会短暂显示静态 key / TOTP secret：禁截屏
        if (!Prefs.adminAllowScreenshot(this)) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        setContentView(R.layout.activity_mcp_admin)
        store = Store.of(this)
        config = McpConfig(store)
        draftTunnelMode = config.tunnelMode
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        bindGate()
        bindAdmin()
        // 会话是进程级的：旋转/返回键回来还在有效期内就直接进，不要求重复验证
        if (AdminThrottle.isOpen(now())) openSession() else showGate()
    }

    /** 任何手/键交互都续期解锁会话（拦掉"页面放着不管就永远开着"）。 */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && adminVisible()) touchSession()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && adminVisible()) touchSession()
        return super.dispatchKeyEvent(event)
    }

    private fun adminVisible(): Boolean =
        findViewById<View>(R.id.adminScroll).visibility == View.VISIBLE

    override fun onResume() {
        super.onResume()
        // 解锁会话过期（或进程回来了但已过期）⇒ 重新上锁；还开着就重新起前台定时器
        if (adminVisible()) {
            if (AdminThrottle.isOpen(now())) sessionTimer.schedule() else showGate()
        }
    }

    override fun onStop() {
        super.onStop()
        // 切后台就上锁；旋转只是重建界面，不该把进程级会话清掉
        if (!isChangingConfigurations) lockNow()
    }

    override fun onDestroy() {
        sessionTimer.cancel()
        // 这里**不**清剪贴板："复制完整命令 → 返回 → 去 Termux 粘贴"是唯一用途，
        // 无条件清会把刚复制的内容吃掉。清理只靠下面的进程级定时器（best effort）。
        dismissDialogs()
        super.onDestroy()
        job.cancel()
    }

    // --------------------------------------------------------- 会话超时

    /** 解锁/交互后续期：会话时间戳 + 前台定时器都往后推 [SESSION_TIMEOUT_MS]。 */
    private fun touchSession() = sessionTimer.touch()

    private fun lockNow() {
        sessionTimer.cancel()
        AdminThrottle.lock()
        // 注意：**不**在这里清剪贴板 —— 切到 Termux 粘贴正是「复制完整命令」的用途。
        // 定时清理由进程级线程负责（Activity 销毁也不取消），另见 onDestroy。
        // 上锁就把还开着的对话框收掉：一次性明文/密钥不能留在锁屏后面
        dismissDialogs()
    }

    private fun dismissDialogs() {
        for (d in dialogs.toList()) runCatching { d.dismiss() }
        dialogs.clear()
    }

    // ------------------------------------------------------------- 管理员门

    private fun bindGate() {
        findViewById<MaterialButton>(R.id.btnGateUnlock).setOnClickListener { doUnlock() }
        findViewById<MaterialButton>(R.id.btnGateSetup).setOnClickListener { doSetup() }
        findViewById<MaterialButton>(R.id.btnGateDevice).setOnClickListener { requestDeviceCredential() }
    }

    private fun showGate() {
        lockNow()
        findViewById<View>(R.id.adminScroll).visibility = View.GONE
        findViewById<View>(R.id.gateBox).visibility = View.VISIBLE
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
            if (TotpSecret.enabled(this@McpAdminActivity)) {
                val secret = TotpSecret.get(this@McpAdminActivity)
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

    private fun openSession() {
        AdminThrottle.touch(now())
        sessionTimer.schedule()
        clearGateInputs()
        gateError(null)
        findViewById<View>(R.id.gateBox).visibility = View.GONE
        findViewById<View>(R.id.adminScroll).visibility = View.VISIBLE
        refreshAll()
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
    }

    // ------------------------------------------------------------- 管理内容

    private fun bindAdmin() {
        findViewById<MaterialButton>(R.id.btnStatusRefresh).setOnClickListener { renderStatus() }
        findViewById<MaterialButton>(R.id.btnPendingRefresh).setOnClickListener { renderPending() }
        findViewById<MaterialButton>(R.id.btnAuditRefresh).setOnClickListener { renderAudit() }
        findViewById<MaterialButton>(R.id.btnNewClient).setOnClickListener { createClientFromForm() }
        findViewById<MaterialButton>(R.id.btnNewKey).setOnClickListener { createKeyFromForm() }
        findViewById<MaterialButton>(R.id.btnChangePassword).setOnClickListener { changePassword() }
        findViewById<MaterialButton>(R.id.btnTotpEnable).setOnClickListener { beginTotpSetup() }
        findViewById<MaterialButton>(R.id.btnTotpDisable).setOnClickListener { confirmDisableTotp() }
        findViewById<MaterialButton>(R.id.btnRevokeAllTokens).setOnClickListener { confirmRevokeAllTokens() }
        findViewById<MaterialButton>(R.id.btnSavePublicSettings).setOnClickListener { savePublicSettings() }
        findViewById<MaterialButton>(R.id.btnCopyTunnelCommand).setOnClickListener { copyTunnelCommand(withToken = false) }
        findViewById<MaterialButton>(R.id.btnCopyTunnelCommandFull).setOnClickListener { copyTunnelCommand(withToken = true) }
        findViewById<MaterialButton>(R.id.btnTunnelProbe).setOnClickListener { probeTunnel() }
        findViewById<MaterialButton>(R.id.btnClearTunnelToken).setOnClickListener { confirmClearTunnelToken() }

        findViewById<MaterialSwitch>(R.id.switchConfirmNewClients).setOnCheckedChangeListener { _, on ->
            if (config.confirmNewClients == on) return@setOnCheckedChangeListener
            config.confirmNewClients = on
            audit("confirm_new_clients", "ok", if (on) "1" else "0")
            toast(if (on) "新客户端要管理员批准后才生效" else "新客户端注册后直接生效（不推荐）")
        }
        findViewById<MaterialSwitch>(R.id.switchRequireAuth).setOnCheckedChangeListener { _, on ->
            if (config.requireAuth == on) return@setOnCheckedChangeListener
            // 公网/tunnel 开着时不许关鉴权：先把公网关掉（决定 8，只往上拧）
            if (!on && Tunnel.publicEnabled(config)) {
                findViewById<MaterialSwitch>(R.id.switchRequireAuth).isChecked = true
                return@setOnCheckedChangeListener toast("公网/隧道开着：鉴权不能关（先关公网并保存）")
            }
            config.requireAuth = on
            audit("require_auth", "ok", if (on) "1" else "0")
            renderSecurity()
            toast(if (on) "已要求 Bearer 令牌" else "已关闭 Bearer 要求（见回环说明）")
        }
        findViewById<MaterialSwitch>(R.id.switchAllowLoopbackNoAuth).setOnCheckedChangeListener { _, on ->
            if (config.allowLoopbackNoAuth == on) return@setOnCheckedChangeListener
            config.allowLoopbackNoAuth = on
            audit("allow_loopback_no_auth", "ok", if (on) "1" else "0")
            renderSecurity()
            toast(if (on) "已允许回环免鉴权（还要关掉『要求 Bearer』才生效）" else "回环免鉴权已关闭")
        }
        for (mode in listOf(McpConfig.TUNNEL_OFF, McpConfig.TUNNEL_NAMED, McpConfig.TUNNEL_QUICK)) {
            findViewById<ChipGroup>(R.id.tunnelGroup).addView(Chip(this).apply {
                text = Tunnel.modeLabel(mode)
                tag = mode
                isCheckable = true
                setOnClickListener {
                    // 只改草稿，不落库：公网基址/token 与 mode 一起保存（一个事务 = 一个 issuer）
                    draftTunnelMode = mode
                    renderTunnelSection()   // 勾选状态始终照草稿重画，不信 ChipGroup 的残留
                }
            })
        }
        renderTunnelSection()
    }

    private fun refreshAll() {
        renderStatus()
        renderSecurity()
        renderPending()
        renderClients()
        renderKeys()
        renderTokens()
        renderAudit()
    }

    // ---------------------------------------------------------------- 状态

    private fun renderStatus() {
        val rows = store.auditRows(limit = 200)
        val counts = rows.groupingBy { it.result }.eachCount().entries.sortedBy { it.key }
        // 只读端点：不建 router（不占限流桶、不与服务里的实例分叉）
        val ep = runCatching { McpRouter.endpointsOf(config) }.getOrNull()
        val fallback = runCatching { McpRouter.endpointsFallbackOf(config) }.getOrDefault(false)
        val text = buildString {
            append(
                if (McpService.RUNNING) "服务：运行中"
                else "服务：未运行（在设置的「系统与权限」里打开「本机 MCP 服务器」）",
            )
            append("\n绑定 127.0.0.1:${McpService.PORT}（只监听回环）")
            append("\n鉴权：")
            append(if (config.requireAuth) "要求 Bearer（缺令牌 401）" else "不要求 Bearer")
            append(" · 回环免鉴权：").append(if (config.allowLoopbackNoAuth) "开" else "关")
            append("\nissuer：").append(ep?.issuer ?: "（读取失败）")
            if (fallback) append("（公网地址无效，已退回本机）")
            append("\n隧道：").append(Tunnel.modeLabel(config.tunnelMode))
            append(" · 公网基址：").append(config.publicBase ?: "无")
            val probe = lastProbe
            append("\n公网探活：")
            append(
                when {
                    probe == null -> "未检测（下面「公网」段有「连通检测」）"
                    probe.reachable -> "可达（${probe.ms} ms）· ${lastProbeBase ?: ""}"
                    else -> "不可达 · ${probe.note}"
                },
            )
            append("\n最近 ${rows.size} 条审计：")
            append(if (counts.isEmpty()) "无" else counts.joinToString(" · ") { "${it.key}=${it.value}" })
            append("\n\n最近日志：\n")
            append(AppLog.tail(this@McpAdminActivity, 10).joinToString("\n").ifBlank { "（无）" })
        }
        findViewById<TextView>(R.id.statusBody).text = text
    }

    // --------------------------------------------------------- 待确认请求

    private fun renderPending() {
        val box = findViewById<LinearLayout>(R.id.pendingBox)
        box.removeAllViews()
        val at = now()
        val rows = store.pendingList(at)
        findViewById<View>(R.id.pendingEmpty).visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        for (p in rows) {
            val client = store.client(p.clientId)
            box.addView(
                row(
                    title = client?.name?.takeIf { it.isNotBlank() } ?: p.clientId,
                    lines = listOf(
                        "回调 ${hostOf(p.redirectUri)}",
                        "scope ${p.scopes.joinToString(" ")} · 有效工具 ${effectiveToolCount(client, p.scopes)} 个",
                        "发起 ${fmt(p.createdAt)} · 还有 ${(p.expiresAt - at).coerceAtLeast(0) / 1000} 秒过期",
                    ),
                    actions = listOf(
                        "允许" to { decidePending(p.id, true) },
                        "拒绝" to { decidePending(p.id, false) },
                    ),
                ),
            )
        }
    }

    private fun decidePending(id: String, approve: Boolean) {
        val ok = OAuth.decidePending(store, id, approve, OAuth.ALLOWED_SCOPES, now())
        audit(if (approve) "pending_allow" else "pending_deny", if (ok) "ok" else "failed", TokenFormat.prefixOf(id))
        if (!ok) toast("请求已失效，或客户端已被吊销")
        renderPending()
    }

    // -------------------------------------------------------------- 客户端

    private fun renderClients() {
        val box = findViewById<LinearLayout>(R.id.clientBox)
        box.removeAllViews()
        findViewById<MaterialSwitch>(R.id.switchConfirmNewClients).isChecked = config.confirmNewClients
        val all = store.clients().sortedBy { it.createdAt }
        if (all.isEmpty()) box.addView(note("还没有客户端"))
        for (c in all.filter { it.status == McpStatus.CLIENT_PENDING }) {
            box.addView(
                row(
                    title = "${clientName(c)} · 待批准",
                    lines = clientLines(c),
                    actions = listOf(
                        "批准" to { approveClient(c) },
                        "拒绝" to { rejectClient(c) },
                    ),
                ),
            )
        }
        for (c in all.filter { it.status == McpStatus.CLIENT_ACTIVE }) {
            box.addView(
                row(
                    title = clientName(c),
                    lines = clientLines(c),
                    actions = listOf(
                        "工具策略" to { editClientPolicy(c) },
                        "时间窗" to { editClientWindow(c) },
                        "撤销全部令牌" to { revokeClientTokens(c) },
                        "吊销客户端" to { confirmRevokeClient(c) },
                    ),
                ),
            )
        }
        for (c in all.filter { it.status == McpStatus.CLIENT_REVOKED }) {
            box.addView(
                row(
                    title = "${clientName(c)} · 已吊销",
                    lines = clientLines(c),
                    actions = listOf("删除" to { confirmDeleteClient(c) }),
                ),
            )
        }
    }

    private fun clientLines(c: ClientRow): List<String> = listOf(
        "创建 ${fmt(c.createdAt)} · 最近使用 ${c.lastUsedAt?.let { fmt(it) } ?: "未用过"}",
        "工具策略 ${policyText(c.toolPolicyJson)} · 时间窗 ${TimeWindow.describe(c.windowJson)}",
        "回调 ${hostList(c.redirectUrisJson)}",
    )

    private fun approveClient(c: ClientRow) {
        store.approveClient(c.clientId, now())
        audit("client_approve", "ok", c.clientId)
        toast("已批准「${c.name}」")
        renderClients()
        renderPending()
    }

    private fun rejectClient(c: ClientRow) {
        store.rejectClient(c.clientId, now())
        audit("client_reject", "ok", c.clientId)
        toast("已拒绝「${c.name}」（可再删除）")
        renderClients()
    }

    private fun confirmRevokeClient(c: ClientRow) {
        AlertDialog.Builder(this)
            .setTitle("吊销客户端")
            .setMessage("「${c.name}」的所有访问/刷新令牌与未用授权码会立即失效，待确认请求一并拒绝。")
            .setNegativeButton("取消", null)
            .setPositiveButton("吊销") { _, _ ->
                store.revokeClient(c.clientId, now())
                audit("client_revoke", "ok", c.clientId)
                renderClients()
                renderTokens()
                renderPending()
            }
            .showTracked()
    }

    private fun confirmDeleteClient(c: ClientRow) {
        AlertDialog.Builder(this)
            .setTitle("删除客户端")
            .setMessage("只删除已吊销的「${c.name}」这一行；它的令牌已经吊销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                store.deleteClient(c.clientId)
                audit("client_delete", "ok", c.clientId)
                renderClients()
                renderTokens()
            }
            .showTracked()
    }

    private fun revokeClientTokens(c: ClientRow) {
        val families = store.tokens(c.clientId).map { it.familyId }.distinct()
        val at = now()
        for (f in families) store.revokeFamily(f, at)
        audit("client_tokens_revoke", "ok", "${c.clientId} families=${families.size}")
        toast(if (families.isEmpty()) "「${c.name}」没有有效令牌" else "已撤销 ${families.size} 组令牌")
        renderTokens()
    }

    private fun createClientFromForm() {
        val name = text(R.id.newClientName)
        val uris = text(R.id.newClientRedirects).lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (uris.isEmpty()) return formMsg(R.id.clientMsg, "至少填一个回调地址")
        val body = JSONObject()
            .put("client_name", name)
            .put("redirect_uris", JSONArray(uris))
            .put("token_endpoint_auth_method", "none")
        val out = try {
            // 管理员本人在管理页手工创建 ⇒ 直接 active（批准环节就是这次点击）
            OAuth.registerClient(store, body, confirmNewClients = false, now = now())
        } catch (e: OAuthError) {
            return formMsg(R.id.clientMsg, "注册失败：${e.desc}")
        }
        audit("client_create", "ok", out.optString("client_id"))
        findViewById<TextInputEditText>(R.id.newClientName).setText("")
        findViewById<TextInputEditText>(R.id.newClientRedirects).setText("")
        formMsg(R.id.clientMsg, null)
        renderClients()
        AlertDialog.Builder(this)
            .setTitle("客户端已注册")
            .setMessage("client_id：${out.optString("client_id")}\n回调：${out.optJSONArray("redirect_uris")}")
            .setPositiveButton("好", null)
            .showTracked()
    }

    // --------------------------------------------------- 策略与时间窗（可测）

    /** 规范化后写回 client 的工具策略；非法输入 ⇒ 不改库、返回 `false`。必须已解锁。 */
    internal fun applyClientPolicy(clientId: String, policy: String?): Boolean {
        if (!AdminThrottle.isOpen(now())) return false
        val canonical = try {
            ToolPolicy.canonical(policy?.takeIf { it.isNotBlank() })
        } catch (e: IllegalArgumentException) {
            return false
        }
        val row = store.client(clientId) ?: return false
        store.putClient(row.copy(toolPolicyJson = canonical))
        audit("policy_update", "ok", clientId + " " + (canonical ?: "null"))
        renderClients()
        return true
    }

    /** 规范化后写回 client 的时间窗；非法输入 ⇒ 不改库、返回 `false`。必须已解锁。 */
    internal fun applyClientWindow(clientId: String, window: String?): Boolean {
        if (!AdminThrottle.isOpen(now())) return false
        val canonical = try {
            TimeWindow.canonical(window?.takeIf { it.isNotBlank() })
        } catch (e: IllegalArgumentException) {
            return false
        }
        val row = store.client(clientId) ?: return false
        store.putClient(row.copy(windowJson = canonical))
        audit("window_update", "ok", clientId + " " + (canonical ?: "null"))
        renderClients()
        return true
    }

    private fun editClientPolicy(c: ClientRow) {
        editJson(
            title = "「${c.name}」工具策略",
            hint = "JSON：null/留空 = 不限；[] = 全禁；[\"hc_status\"] 白名单",
            initial = c.toolPolicyJson,
        ) { input -> applyClientPolicy(c.clientId, input) }
    }

    private fun editClientWindow(c: ClientRow) {
        editJson(
            title = "「${c.name}」时间窗",
            hint = "JSON：留空 = 不限；{\"days\":[1,2,3,4,5],\"start\":\"08:00\",\"end\":\"23:00\"}",
            initial = c.windowJson,
        ) { input -> applyClientWindow(c.clientId, input) }
    }

    private fun editKeyPolicy(k: KeyRow) {
        editJson(
            title = "「${k.label}」工具策略",
            hint = "JSON：留空 = 不限；[\"hc_status\"] 白名单",
            initial = k.toolPolicyJson,
        ) { input -> applyKeyPolicy(k.id, input) }
    }

    private fun editKeyWindow(k: KeyRow) {
        editJson(
            title = "「${k.label}」时间窗",
            hint = "JSON：留空 = 不限；{\"days\":[1,2,3,4,5],\"start\":\"08:00\",\"end\":\"23:00\"}",
            initial = k.windowJson,
        ) { input -> applyKeyWindow(k.id, input) }
    }

    /** 规范化后的策略写回静态 key（不改 hash）；非法输入 ⇒ 不改库、返回 `false`。必须已解锁。 */
    internal fun applyKeyPolicy(id: Long, policy: String?): Boolean {
        if (!AdminThrottle.isOpen(now())) return false
        val canonical = try {
            ToolPolicy.canonical(policy?.takeIf { it.isNotBlank() })
        } catch (e: IllegalArgumentException) {
            return false
        }
        val key = store.keys().firstOrNull { it.id == id } ?: return false
        if (!store.updateKey(id, canonical, key.windowJson, now())) return false
        audit("key_policy_update", "ok", "key:${id} " + (canonical ?: "null"))
        renderKeys()
        return true
    }

    /** 规范化后的时间窗写回静态 key；非法输入 ⇒ 不改库、返回 `false`。必须已解锁。 */
    internal fun applyKeyWindow(id: Long, window: String?): Boolean {
        if (!AdminThrottle.isOpen(now())) return false
        val canonical = try {
            TimeWindow.canonical(window?.takeIf { it.isNotBlank() })
        } catch (e: IllegalArgumentException) {
            return false
        }
        val key = store.keys().firstOrNull { it.id == id } ?: return false
        if (!store.updateKey(id, key.toolPolicyJson, canonical, now())) return false
        audit("key_window_update", "ok", "key:$id " + (canonical ?: "null"))
        renderKeys()
        return true
    }

    /** 一个"编辑 JSON"对话框：非法输入就地提示，**不关窗**也不写库。 */
    private fun editJson(title: String, hint: String, initial: String?, save: (String?) -> Boolean) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        box.addView(note(hint))
        val input = EditText(this).apply {
            setText(initial ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = android.view.Gravity.TOP
        }
        box.addView(input)
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (save(input.text.toString())) {
                    dialog.dismiss()
                } else {
                    input.error = "格式不对（未保存，库里保持原值）"
                }
            }
        }
        track(dialog)
    }

    // ------------------------------------------------- 令牌与静态 key

    private fun renderKeys() {
        val box = findViewById<LinearLayout>(R.id.keyBox)
        box.removeAllViews()
        val keys = store.keys().sortedBy { it.id }
        if (keys.isEmpty()) box.addView(note("还没有静态 key"))
        for (k in keys) {
            val actions = ArrayList<Pair<String, () -> Unit>>()
            if (k.revokedAt == null) {
                actions.add("工具策略" to { editKeyPolicy(k) })
                actions.add("时间窗" to { editKeyWindow(k) })
                actions.add("撤销" to { confirmRevokeKey(k) })
            }
            box.addView(
                row(
                    title = "${k.label}${if (k.revokedAt != null) " · 已撤销" else ""}".trim(),
                    lines = listOf(
                        "创建 ${fmt(k.createdAt)} · 最近使用 ${k.lastUsedAt?.let { fmt(it) } ?: "未用过"}",
                        "工具策略 ${policyText(k.toolPolicyJson)} · 时间窗 ${TimeWindow.describe(k.windowJson)}",
                    ),
                    actions = actions,
                ),
            )
        }
    }

    private fun confirmRevokeKey(k: KeyRow) {
        AlertDialog.Builder(this)
            .setTitle("撤销静态 key")
            .setMessage("「${k.label}」立刻失效（用它的脚本会开始收 401）。")
            .setNegativeButton("取消", null)
            .setPositiveButton("撤销") { _, _ ->
                store.revokeKey(k.id, now())
                audit("key_revoke", "ok", k.label)
                renderKeys()
            }
            .showTracked()
    }

    private fun createKeyFromForm() {
        val label = text(R.id.keyLabel)
        if (label.isEmpty()) return formMsg(R.id.keyMsg, "先给 key 起个标签")
        val raw = try {
            createStaticKey(
                label,
                text(R.id.keyPolicy).takeIf { it.isNotEmpty() },
                text(R.id.keyWindow).takeIf { it.isNotEmpty() },
            ) ?: return formMsg(R.id.keyMsg, "会话已锁定，请重新解锁后再创建")
        } catch (e: IllegalArgumentException) {
            return formMsg(R.id.keyMsg, "策略/时间窗格式不对：没有创建（库里保持原值）")
        }
        findViewById<TextInputEditText>(R.id.keyLabel).setText("")
        findViewById<TextInputEditText>(R.id.keyPolicy).setText("")
        findViewById<TextInputEditText>(R.id.keyWindow).setText("")
        formMsg(R.id.keyMsg, null)
        renderKeys()
        showSecretOnce("静态 key「$label」", raw, "只显示这一次；库里只有 SHA-256，丢了只能重建。")
    }

    /**
     * 创建静态 key：策略/时间窗先 `canonical`（非法 ⇒ 抛 [IllegalArgumentException]，什么都不写），
     * 返回**一次性明文**；会话已锁定 ⇒ `null`（不抛异常，调用方给提示）。
     * UI 只把 [TokenFormat.prefixOf] 之后的形态留在列表里。
     */
    internal fun createStaticKey(label: String, policy: String?, window: String?): String? {
        if (!AdminThrottle.isOpen(now())) return null
        val canonicalPolicy = ToolPolicy.canonical(policy?.takeIf { it.isNotBlank() })
        val canonicalWindow = TimeWindow.canonical(window?.takeIf { it.isNotBlank() })
        val raw = TokenFormat.newStaticKey()
        store.putKey(label.trim(), Crypto.sha256Hex(raw), canonicalPolicy, canonicalWindow, now())
        audit("key_create", "ok", label.trim())
        return raw
    }

    private fun renderTokens() {
        val box = findViewById<LinearLayout>(R.id.tokenBox)
        box.removeAllViews()
        val at = now()
        val live = store.tokens()
            .filter { it.revokedAt == null && it.expiresAt > at }
            .sortedByDescending { it.createdAt }
        if (live.isEmpty()) box.addView(note("没有有效令牌"))
        for (t in live.take(MAX_TOKEN_ROWS)) {
            box.addView(
                row(
                    title = "${t.prefix}… · ${if (t.kind == McpStatus.KIND_REFRESH) "刷新" else "访问"}",
                    lines = listOf(
                        "客户端 ${store.client(t.clientId)?.name ?: t.clientId}",
                        "过期 ${fmt(t.expiresAt)} · 最近使用 ${t.lastUsedAt?.let { fmt(it) } ?: "未用过"}",
                    ),
                    actions = listOf("撤销" to { revokeToken(t) }),
                ),
            )
        }
        if (live.size > MAX_TOKEN_ROWS) box.addView(note("只显示最近 $MAX_TOKEN_ROWS 个（共 ${live.size} 个）"))
    }

    private fun revokeToken(t: TokenRow) {
        store.revokeFamily(t.familyId, now())
        audit("token_revoke", "ok", t.prefix)
        renderTokens()
    }

    private fun confirmRevokeAllTokens() {
        if (!requireUnlocked()) return
        AlertDialog.Builder(this)
            .setTitle("撤销全部令牌")
            .setMessage("所有客户端的访问/刷新令牌立即失效；它们要重新授权。静态 key 不受影响。")
            .setNegativeButton("取消", null)
            .setPositiveButton("全部撤销") { _, _ ->
                val at = now()
                val families = store.tokens().filter { it.revokedAt == null }.map { it.familyId }.distinct()
                for (f in families) store.revokeFamily(f, at)
                audit("tokens_revoke_all", "ok", "families=${families.size}")
                renderTokens()
                toast("已撤销 ${families.size} 组令牌")
            }
            .showTracked()
    }

    // ---------------------------------------------------------------- 安全

    private fun renderSecurity() {
        findViewById<MaterialSwitch>(R.id.switchRequireAuth).isChecked = config.requireAuth
        findViewById<MaterialSwitch>(R.id.switchAllowLoopbackNoAuth).isChecked = config.allowLoopbackNoAuth
        findViewById<MaterialSwitch>(R.id.switchConfirmNewClients).isChecked = config.confirmNewClients

        val hint = findViewById<TextView>(R.id.loopbackHint)
        val base = "仅回环免鉴权：还要把上面的『要求 Bearer』关掉才生效；公网/tunnel 与带转发头的请求永远 401。"
        val blockedPublic = Tunnel.publicEnabled(config)
        hint.text = buildString {
            append(base)
            if (blockedPublic) append("\n现在无效：公网/隧道开着（鉴权被强制开启）。")
            else if (config.allowLoopbackNoAuth && config.requireAuth) append("\n现在无效：requireAuth 仍为开。")
        }
        hint.setTextColor(
            if (blockedPublic || (config.allowLoopbackNoAuth && config.requireAuth)) hint.m3("colorError")
            else hint.m3("colorOnSurfaceVariant"),
        )

        // 只在「没输入」或「输入的就是已保存值」时回填：不覆盖正在编辑的公网基址草稿
        val baseField = findViewById<TextInputEditText>(R.id.publicBase)
        val typed = baseField.text?.toString().orEmpty()
        val unchanged = typed.isBlank() || typed == config.publicBase ||
            (typed.isNotBlank() && Tunnel.normalizePublicBase(typed) == config.publicBase)
        if (!baseField.isFocused && unchanged) baseField.setText(config.publicBase ?: "")
        renderTunnelSection()   // 草稿 mode 不动：切换鉴权开关不该把用户选的隧道模式改回去

        val totpOn = TotpSecret.enabled(this)
        findViewById<TextView>(R.id.totpStatus).text = if (totpOn) {
            "TOTP：已启用。解锁要先验证动态码（RFC 6238，30 秒 6 位）。恢复路径是这台设备的屏幕锁。"
        } else {
            "TOTP：未启用。启用后解锁除密码外还要动态码（自写 HMAC-SHA1，无第三方依赖）。"
        }
        findViewById<MaterialButton>(R.id.btnTotpEnable).visibility = if (totpOn) View.GONE else View.VISIBLE
        findViewById<MaterialButton>(R.id.btnTotpDisable).visibility = if (totpOn) View.VISIBLE else View.GONE
    }

    /** chips 的勾选状态**始终**由草稿 [draftTunnelMode] 决定（唯一真相），不信 ChipGroup 的残留状态。 */
    private fun renderTunnelChips() {
        val group = findViewById<ChipGroup>(R.id.tunnelGroup)
        for (i in 0 until group.childCount) {
            (group.getChildAt(i) as? Chip)?.let { it.isChecked = it.tag == draftTunnelMode }
        }
    }

    /**
     * 「公网」段渲染：提示 + token 状态 + 命令预览。
     *
     * **界面永不回显 token**：只有「已保存（不可读）／未保存」和占位符版命令；
     * 含 token 的命令只在用户在「复制完整命令」里主动要时进剪贴板，且 60 秒后清掉。
     */
    private fun renderTunnelSection() {
        renderTunnelChips()
        val public = config.publicBase != null
        val on = Tunnel.publicEnabled(config)
        val mode = config.tunnelMode
        val notice = findViewById<TextView>(R.id.publicNotice)
        val text = when {
            public && mode == McpConfig.TUNNEL_OFF -> buildString {
                append("公网基址已设置（隧道模式关闭）：issuer = ")
                append(McpRouter.endpointsOf(config).issuer)
                append("。旧令牌全部失效，客户端要重新授权；鉴权已强制开启，空闲不自动停服。")
                append("\n不用 cloudflared 时，请自行把该地址转发到 127.0.0.1:")
                append(McpService.PORT).append("。")
            }
            public -> buildString {
                append("公网已开启：issuer = ").append(McpRouter.endpointsOf(config).issuer)
                append("。旧令牌全部失效，客户端要重新授权；鉴权已强制开启，空闲不自动停服。")
                append("\n管理页只在 app 内：HTTP 上没有 /admin 路由，隧道只转发 /mcp 与 OAuth/发现端点。")
            }
            on -> buildString {
                append("隧道 ").append(Tunnel.modeLabel(mode))
                append(" 已保存，但公网基址为空：issuer 仍是本机。把 cloudflared 给的 https 地址填上再保存；")
                append("鉴权已强制开启。")
            }
            draftTunnelMode != mode -> "还没保存：保存公网设置后，公网/隧道会强制开启鉴权。"
            else -> ""
        }
        notice.text = text
        notice.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        notice.setTextColor(if (on) notice.m3("colorPrimary") else notice.m3("colorOnSurfaceVariant"))

        val tokenSaved = !Secure.getString(this, Tunnel.SECURE_KEY).isNullOrBlank()
        findViewById<TextView>(R.id.tunnelTokenStatus).text = when {
            tokenSaved -> "tunnel token：已保存（密文，界面不回显）。要换就粘一个新的再保存。"
            draftTunnelMode == McpConfig.TUNNEL_NAMED -> "tunnel token：未保存 —— named tunnel 必须有它。"
            else -> "tunnel token：未保存（quick tunnel 不需要）。"
        }
        findViewById<MaterialButton>(R.id.btnClearTunnelToken).visibility =
            if (tokenSaved) View.VISIBLE else View.GONE

        findViewById<TextView>(R.id.tunnelCommand).text = tunnelPreview(draftTunnelMode, tokenSaved)
        val usable = draftTunnelMode != McpConfig.TUNNEL_OFF
        findViewById<MaterialButton>(R.id.btnCopyTunnelCommand).isEnabled = usable
        findViewById<MaterialButton>(R.id.btnCopyTunnelCommandFull).isEnabled = usable
    }

    /** 命令预览：**只有占位符版**（token 从不进这个 TextView，也不进日志）。 */
    private fun tunnelPreview(mode: String, tokenSaved: Boolean): String = when (mode) {
        McpConfig.TUNNEL_OFF -> "隧道关闭：不需要 cloudflared。"
        McpConfig.TUNNEL_NAMED -> buildString {
            append("named tunnel（隧道名只写在 Cloudflare 侧，不作位置参数）：\n")
            append(Tunnel.commandFor(mode, null))
            append("\n")
            append(
                if (tokenSaved) "token 已保存：要用真 token 就按「复制完整命令」。"
                else "先把 tunnel token 粘进来并保存。",
            )
        }
        else -> buildString {
            append("quick tunnel（临时 trycloudflare.com 域名，cloudflared 重启就换）：\n")
            append(Tunnel.commandFor(mode, null))
        }
    }

    private fun changePassword() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun field(hint: String) = EditText(this).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val first = field("新密码（至少 $MIN_PASSWORD 位）")
        val second = field("再输一次")
        box.addView(first)
        box.addView(second)
        val dialog = AlertDialog.Builder(this)
            .setTitle("修改管理员密码")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val a = first.text.toString()
                val b = second.text.toString()
                when {
                    a.length < MIN_PASSWORD -> first.error = "至少 $MIN_PASSWORD 位"
                    a != b -> second.error = "两次输入不一致"
                    else -> {
                        dialog.dismiss()
                        setAdminPassword(a)
                    }
                }
            }
        }
        track(dialog)
    }

    private fun setAdminPassword(pw: String) {
        scope.launch {
            val hash = withContext(Dispatchers.Default) { AdminGate.hash(pw) }
            config.adminPasswordHash = hash
            audit("admin_password_set", "ok", "change")
            toast("管理员密码已更新")
        }
    }

    private fun beginTotpSetup() {
        if (TotpSecret.enabled(this)) return toast("TOTP 已经启用了")
        val secret = Totp.secret()
        val uri = Totp.uri(secret, "HC Bridge", "MCP 管理")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        box.addView(note("1) 把这个密钥加进验证器（手动输入或用它生成 URI）。\n2) 输入验证器当前的 6 位码；验证通过才真正启用。"))
        box.addView(
            TextView(this).apply {
                text = secret
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            },
        )
        box.addView(
            TextView(this).apply {
                text = uri
                setTextIsSelectable(true)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                setTextColor(m3("colorOnSurfaceVariant"))
            },
        )
        val code = EditText(this).apply {
            hint = "6 位动态码"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        box.addView(code)
        val dialog = AlertDialog.Builder(this)
            .setTitle("启用 TOTP")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认启用", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!AdminThrottle.claimTotp(secret, code.text.toString(), now())) {
                    code.error = "码不对或已用过（检查手机与验证器的时钟）"
                    return@setOnClickListener
                }
                TotpSecret.set(this, secret)
                audit("totp_enable", "ok", null)
                dialog.dismiss()
                renderSecurity()
                toast("TOTP 已启用：下次解锁要动态码")
            }
        }
        track(dialog)
    }

    private fun confirmDisableTotp() {
        AlertDialog.Builder(this)
            .setTitle("关闭 TOTP")
            .setMessage("关闭后只用管理员密码（或屏幕锁）解锁管理页。")
            .setNegativeButton("取消", null)
            .setPositiveButton("关闭") { _, _ ->
                TotpSecret.set(this, null)
                audit("totp_disable", "ok", null)
                renderSecurity()
                toast("TOTP 已关闭")
            }
            .showTracked()
    }

    /** 写操作前确认还解锁着；已锁定 ⇒ 回到门前（不抛异常，也不写库）。 */
    private fun requireUnlocked(): Boolean {
        if (AdminThrottle.isOpen(now())) return true
        showGate()
        return false
    }

    /**
     * 保存「公网」段：隧道模式 + 公网基址 + tunnel token **一次写完**，随后跑 [Tunnel.applyGuards]。
     *
     * 任何一项校验失败就**什么都不写**（连模式也不写）：半套配置只会让 issuer 与客户端预期对不上。
     * token 只写 `Secure`（密文），审计里只有 "set/keep"，没有值。
     */
    private fun savePublicSettings() {
        if (!requireUnlocked()) return
        val raw = text(R.id.publicBase)
        if (!Tunnel.validatePublicBase(raw)) {
            return toast("公网基址必须是 https://host（不带路径、查询或尾部斜杠）")
        }
        val mode = draftTunnelMode
        // 存**归一化**值：`Endpoints.from` 会把 host 折小写、去掉 `:443`；
        // 存原串会让探活的 resource 比对永远不等（假"不可达"），也让 issuer 有两个写法。
        val base = Tunnel.normalizePublicBase(raw)
        val changedBase = base != config.publicBase
        val token = text(R.id.tunnelToken)
        if (mode == McpConfig.TUNNEL_NAMED && token.isEmpty() &&
            Secure.getString(this, Tunnel.SECURE_KEY).isNullOrBlank()
        ) {
            return toast("named tunnel 需要 tunnel token：先粘进来再保存")
        }
        val before = McpRouter.endpointsOf(config).issuer
        config.publicBase = base
        if (config.tunnelMode != mode) config.tunnelMode = mode
        if (token.isNotEmpty()) Secure.putString(this, Tunnel.SECURE_KEY, token)
        Tunnel.applyGuards(config)
        audit("public_settings", "ok", "mode=$mode base=${base ?: "null"} token=${if (token.isNotEmpty()) "set" else "keep"}")
        if (changedBase) {
            // 基址变了：上一次探活结果作废，别让「状态」段拿旧地址的结论骗人
            lastProbe = null
            lastProbeBase = null
        }
        // 输入框立刻清空：token 不留在界面里（截图/切后台/输入法缓存都不该有它）
        findViewById<TextInputEditText>(R.id.tunnelToken).setText("")
        findViewById<TextInputEditText>(R.id.publicBase).setText(base ?: "")
        renderSecurity()
        renderStatus()
        val after = McpRouter.endpointsOf(config).issuer
        toast(
            when {
                base != null && after != before -> "公网已开启：issuer → $after，旧令牌需重新授权"
                base != null -> "已保存：issuer 仍是 $after"
                Tunnel.publicEnabled(config) -> "已保存：${Tunnel.modeLabel(config.tunnelMode)}，issuer 仍是 $after"
                else -> "已恢复只走本机（鉴权不会自动关）"
            },
        )
    }

    /**
     * 「复制 Termux 命令」。占位版随时可复制；完整版要已存 token，且
     * **60 秒后清剪贴板**（`ClipData` 在 Android 13+ 已标 `IS_SENSITIVE`）。
     */
    private fun copyTunnelCommand(withToken: Boolean) {
        if (!requireUnlocked()) return
        val mode = draftTunnelMode
        if (mode == McpConfig.TUNNEL_OFF) return toast("隧道是关闭状态：不需要 cloudflared 命令")
        val saved = Secure.getString(this, Tunnel.SECURE_KEY)
        if (!withToken || mode != McpConfig.TUNNEL_NAMED || saved.isNullOrBlank()) {
            copy(Tunnel.commandFor(mode, null))
            audit("tunnel_command", "ok", "placeholder")
            if (withToken && mode == McpConfig.TUNNEL_NAMED) toast("还没存 tunnel token：先粘进来并保存")
            return
        }
        val cmd = Tunnel.commandFor(mode, saved)
        copy(cmd)                        // 剪贴板；绝不写日志/审计/AppLog
        audit("tunnel_command", "ok", "full")   // 只记"复制了完整版"，不含 token
        scheduleSecretClipboardClear(cmd)
    }

    /**
     * 进程级定时清剪贴板（**best effort**）：不用 Activity 的 `CoroutineScope`
     * （`onDestroy` 会 cancel，token 就可能一直留着）。线程只持有 `applicationContext`，
     * Activity 是否还在无关。
     *
     * 注意这是"尝试清除"：Android 从后台读剪贴板可能被系统拒绝，**以系统行为为准**；
     * 真正可靠的边界是"剪贴板里本来就不该长期放凭据"。
     */
    private fun scheduleSecretClipboardClear(value: String) {
        val app = applicationContext
        Thread({
            try {
                Thread.sleep(CLIP_CLEAR_MS)
            } catch (t: InterruptedException) {
                return@Thread
            }
            clearClipboardIfOurs(app, value)
        }, "hcb-clip-clear").apply { isDaemon = true }.start()
    }

    private fun confirmClearTunnelToken() {
        if (!requireUnlocked()) return
        AlertDialog.Builder(this)
            .setTitle("清除 tunnel token")
            .setMessage("清除后「复制完整命令」只能给出占位符，named tunnel 也起不来，直到重新粘一个 token。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清除") { _, _ ->
                Secure.putString(this, Tunnel.SECURE_KEY, null)
                audit("tunnel_token", "ok", "cleared")
                renderTunnelSection()
                toast("已清除 tunnel token")
            }
            .showTracked()
    }

    /**
     * 连通检测：经公网地址回打本机（`/health` + 元数据 `resource` 校验）。
     *
     * **网络只在 IO 线程**：主线程发网络请求会 `NetworkOnMainThreadException`，也会卡住界面。
     */
    private fun probeTunnel() {
        if (!requireUnlocked()) return
        val typed = text(R.id.publicBase).ifEmpty { config.publicBase ?: "" }
        if (!Tunnel.validatePublicBase(typed) || typed.isBlank()) {
            return toast("先填一个合法的公网基址（https://host，不带路径）")
        }
        // 比较**归一化**后的值：输入框里可能还是大写/`:443`，与库里的写法不同但其实是同一个地址
        val raw = Tunnel.normalizePublicBase(typed) ?: return toast("公网基址不合法")
        // 元数据里的 resource 由**已保存**的 publicBase 决定：没保存就探，必然"地址不一致"
        if (raw != config.publicBase) {
            return toast("先保存这个公网基址再检测（issuer 还没切过去）")
        }
        val out = findViewById<TextView>(R.id.tunnelProbeResult)
        val button = findViewById<MaterialButton>(R.id.btnTunnelProbe)
        out.setTextColor(out.m3("colorOnSurfaceVariant"))
        out.text = "检测中：GET $raw/health …"
        button.isEnabled = false
        scope.launch {
            val r = withContext(Dispatchers.IO) { TunnelProbe().probe(raw) }
            out.text = if (r.reachable) "可达（${r.ms} ms）：${r.note}" else "不可达（${r.ms} ms）：${r.note}"
            out.setTextColor(if (r.reachable) out.m3("colorPrimary") else out.m3("colorError"))
            button.isEnabled = true
            lastProbe = r
            lastProbeBase = raw
            audit("tunnel_probe", if (r.reachable) "ok" else "failed", "ms=${r.ms}")
            renderStatus()
        }
    }

    // ---------------------------------------------------------------- 审计

    private fun renderAudit() {
        val all = store.auditRows(200)
        val actor = filterActor?.takeIf { v -> all.any { it.actor == v } }
        val action = filterAction?.takeIf { v -> all.any { it.action == v } }
        val result = filterResult?.takeIf { v -> all.any { it.result == v } }
        filterActor = actor
        filterAction = action
        filterResult = result

        fillChips(R.id.auditActorGroup, all.map { it.actor }.distinct(), actor) { v ->
            filterActor = v
            renderAudit()
        }
        fillChips(R.id.auditActionGroup, all.map { it.action }.distinct(), action) { v ->
            filterAction = v
            renderAudit()
        }
        fillChips(R.id.auditResultGroup, all.map { it.result }.distinct(), result) { v ->
            filterResult = v
            renderAudit()
        }

        val rows = store.auditRows(200, actor = actor, action = action, result = result)
        findViewById<TextView>(R.id.auditChainStatus).text = if (store.auditChainOk()) {
            "审计链：完好（哈希链自洽）· 显示 ${rows.size}/${all.size} 条"
        } else {
            "审计链：校验失败 —— 有人直改或删过 audit_log 行"
        }

        val box = findViewById<LinearLayout>(R.id.auditBox)
        box.removeAllViews()
        if (rows.isEmpty()) box.addView(note("没有匹配的记录"))
        for (r in rows) {
            box.addView(
                row(
                    title = "${fmt(r.createdAt)} · ${r.action}${r.tool?.let { " · $it" } ?: ""}",
                    lines = listOf("${r.actor} · ${r.result}${r.detail?.let { " · $it" } ?: ""}"),
                ),
            )
        }
    }

    private fun fillChips(groupId: Int, values: List<String>, selected: String?, pick: (String?) -> Unit) {
        val group = findViewById<ChipGroup>(groupId)
        group.removeAllViews()
        val options = ArrayList<String?>(values.size + 1)
        options.add(null)
        options.addAll(values.take(MAX_FILTER_CHIPS))
        for (v in options) {
            group.addView(
                Chip(this).apply {
                    text = v?.take(24) ?: "全部"
                    isCheckable = true
                    isChecked = v == selected
                    setOnClickListener { pick(v) }
                },
            )
        }
    }

    // ------------------------------------------------------------- 小工具

    private fun audit(action: String, result: String, detail: String?) =
        store.audit("admin", action, null, result, detail, now())

    private fun now(): Long = System.currentTimeMillis()

    private fun text(id: Int): String =
        findViewById<TextInputEditText>(id).text?.toString()?.trim().orEmpty()

    private fun fmt(at: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(at))

    private fun hostOf(uri: String): String =
        runCatching { URI(uri).host }.getOrNull() ?: "（无法解析）"

    private fun hostList(json: String): String {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return "（无法解析）"
        val hosts = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) hosts.add(hostOf(arr.optString(i, "")))
        return hosts.joinToString(", ")
    }

    private fun policyText(json: String?): String {
        if (json == null) return "不限"
        val names = runCatching { ToolPolicy.names(json) }.getOrNull() ?: return "损坏（全部拒绝）"
        if (names == null) return "不限"
        if (names.isEmpty()) return "全禁"
        val head = names.take(3).joinToString(",")
        return if (names.size > 3) "$head…（${names.size} 个）" else head
    }

    private fun effectiveToolCount(client: ClientRow?, scopes: List<String>): Int {
        val granted = scopes.filterTo(LinkedHashSet()) { it in OAuth.ALLOWED_SCOPES }
        return ToolGate.effectiveTools(granted, listOfNotNull(client?.toolPolicyJson)).size
    }

    private fun clientName(c: ClientRow): String = c.name.takeIf { it.isNotBlank() } ?: c.clientId

    private fun note(s: String): TextView = TextView(this).apply {
        text = s
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        setTextColor(m3("colorOnSurfaceVariant"))
    }

    /** 一行：标题 + 若干行说明 + 动作 chips（列表行全部这样生成）。 */
    private fun row(title: String, lines: List<String>, actions: List<Pair<String, () -> Unit>> = emptyList()): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(0, dp(8), 0, 0)
        }
        box.addView(
            TextView(this).apply {
                text = title
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            },
        )
        if (lines.isNotEmpty()) {
            box.addView(
                TextView(this).apply {
                    text = lines.joinToString("\n")
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                    setTextColor(m3("colorOnSurfaceVariant"))
                },
            )
        }
        if (actions.isNotEmpty()) {
            val group = ChipGroup(this)
            for ((label, onClick) in actions) {
                group.addView(
                    Chip(this).apply {
                        text = label
                        isCheckable = false
                        setOnClickListener { onClick() }
                    },
                )
            }
            box.addView(group)
        }
        return box
    }

    private fun formMsg(id: Int, msg: String?) {
        val v = findViewById<TextView>(id)
        v.text = msg.orEmpty()
        v.visibility = if (msg == null) View.GONE else View.VISIBLE
    }

    /** 一次性明文：只在这个对话框里出现；「复制」进剪贴板（系统剪贴板对普通 app 可见，提示用户）。 */
    private fun showSecretOnce(title: String, secret: String, note: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        box.addView(note(note))
        box.addView(
            TextView(this).apply {
                text = secret
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            },
        )
        track(
            AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setNeutralButton("复制") { _, _ -> copy(secret) }
                .setPositiveButton("好", null)
                .create(),
            onDismiss = { clearClipboardIfOurs(secret) },
        )
    }

    /** 对话框统一登记：`onDestroy` 会收掉还没关的窗口（旋转/返回都不留悬挂窗口）。 */
    private fun track(dialog: AlertDialog, onDismiss: (() -> Unit)? = null): AlertDialog {
        dialogs.add(dialog)
        dialog.setOnDismissListener {
            dialogs.remove(dialog)
            onDismiss?.invoke()
        }
        dialog.show()
        return dialog
    }

    private fun AlertDialog.Builder.showTracked(): AlertDialog = track(create())

    /**
     * 复制到剪贴板：Android 13+ 打上 `IS_SENSITIVE`（系统不预览、不进剪贴板历史）；
     * 关掉对话框时若剪贴板里**还是**我们放的那份就清掉（不覆盖用户后来复制的内容）。
     */
    private fun copy(value: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(CLIP_LABEL, value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        cm.setPrimaryClip(clip)
        toast("已复制")
    }

    private fun clearClipboardIfOurs(value: String) = clearClipboardIfOurs(this, value)

    /**
     * 剪贴板清理的**静态**版本（可从后台线程 + `applicationContext` 调用）：
     * 只清"还是我们放的那份"，不覆盖用户后来复制的内容。
     */
    private fun clearClipboardIfOurs(ctx: Context, value: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val current = cm.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(ctx)?.toString()
        if (current == value) runCatching { cm.clearPrimaryClip() }
    }

    private companion object {
        const val MIN_PASSWORD = 8
        const val MAX_TOKEN_ROWS = 30
        const val MAX_FILTER_CHIPS = 8

        /** 解锁会话在前台的有效期（无交互就自动上锁）。 */
        const val SESSION_TIMEOUT_MS = 5 * 60_000L

        /** 失败解锁的审计最小间隔（锁定那一笔不受限）。 */
        const val FAIL_AUDIT_MIN_MS = 30_000L

        /** 剪贴板标签（只用于识别"是不是我们放的那份"，不含凭据本身）。 */
        const val CLIP_LABEL = "MCP"

        /** 含 token 的剪贴板内容保留多久（到期自动清，前提是还被我们占着）。 */
        const val CLIP_CLEAR_MS = 60_000L
    }
}
