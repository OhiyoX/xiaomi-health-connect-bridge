package id.harness.hcbridge

import id.harness.hcbridge.auth.AdminGate
import id.harness.hcbridge.auth.AdminThrottle
import id.harness.hcbridge.auth.Crypto
import id.harness.hcbridge.auth.McpConfig
import id.harness.hcbridge.auth.McpStatus
import id.harness.hcbridge.auth.McpStore
import id.harness.hcbridge.auth.OAuth
import id.harness.hcbridge.auth.OAuthError
import id.harness.hcbridge.auth.TimeWindow
import id.harness.hcbridge.auth.TokenFormat
import id.harness.hcbridge.auth.ToolPolicy
import id.harness.hcbridge.auth.Totp
import id.harness.hcbridge.auth.Tunnel
import org.json.JSONArray
import org.json.JSONObject

/**
 * 共享前端的**全部写动作**在 App 端的实现（契约：`admin-ui/API.md` §4）。
 *
 * 设计约束（改之前先读）：
 * - **每个动作独立校验** [AdminThrottle]：页面上看到的解锁状态不算数，锁了就一律 `ok:false`；
 * - 所有动作写一条审计；`detail` 只放标签/前缀/id，**绝不放**密码、TOTP secret、令牌明文、client_secret；
 * - 一次性明文（静态 key、client_secret、TOTP secret）只出现在本次返回值里，库里只存哈希/密文；
 * - 本机一个管理员、一个解锁会话（`caps` 声明 `single`）：`user.*` 一律 `ok:false`，
 *   `session.revoke` 等价「上锁」，`session.revoke-others` 返回 `{revoked:0}`；
 * - 策略项（`settings.update`）在本机是常量（`AdminGate`），**不假装能改**。
 *
 * 本文件**不得** import 任何 `android.*`：JVM 单测用 `InMemoryStore` + 假 [AdminEnv] 覆盖全部动作。
 */
class AdminActions(
    private val store: McpStore,
    private val config: McpConfig,
    private val env: AdminEnv,
) {

    /** `totp-begin` → `totp-confirm` 之间的待确认 secret：只在内存里，确认前不落库。 */
    @Volatile
    private var pendingTotp: String? = null

    /**
     * 执行一个动作。[payload] 为 `null` 时按空对象处理（页面偶尔会传 `null`）。
     *
     * **不抛异常**：所有失败都变成 `{ok:false, error:"给人看的中文短句"}`。
     */
    fun act(name: String, payload: JSONObject?): JSONObject {
        val p = payload ?: JSONObject()
        if (!AdminThrottle.isOpen(env.now())) return err(LOCKED)
        return try {
            when (name) {
                // 本机只有一个管理员账号：多用户动作不支持，也不伪造成功
                "user.create", "user.update", "user.toggle", "user.delete" -> singleAdmin(name, p)
                "session.revoke" -> revokeSession(p)
                "session.revoke-others" -> okData(JSONObject().put("revoked", 0))
                "token.create" -> createToken(p)
                "token.revoke" -> revokeToken(p)
                "oauth.create" -> createClient(p)
                "oauth.toggle" -> toggleClient(p)
                "oauth.delete" -> deleteClient(p)
                "pending.decide" -> decidePending(p)
                "coverage.backfill" -> backfill(p)
                "audit.verify" -> verifyAudit()
                "security.change-password" -> changePassword(p)
                "security.totp-begin" -> beginTotp()
                "security.totp-confirm" -> confirmTotp(p)
                "security.totp-rebind" -> rebindTotp(p)
                "security.totp-disable" -> disableTotp(p)
                "settings.update" -> rejectSettings()
                // 本机访问与工具策略（0.9.x 扩展，App 专属；`caps.localAccess` / `caps.policies`）
                "token.policy" -> tokenLayer(p, policy = true)
                "token.window" -> tokenLayer(p, policy = false)
                "oauth.policy" -> clientLayer(p, policy = true)
                "oauth.window" -> clientLayer(p, policy = false)
                "local.require-auth" -> setRequireAuth(p)
                "local.loopback" -> setLoopback(p)
                "local.confirm-clients" -> setConfirmClients(p)
                "local.save-public" -> savePublicSettings(p)
                "local.clear-token" -> clearTunnelToken()
                "local.probe" -> probePublic()
                "local.command" -> tunnelCommand(p)
                else -> err("不支持的操作")
            }
        } catch (e: OAuthError) {
            // 只回固定文案：错误会原样 toast，不能带内部细节
            err(oauthMessage(e))
        } catch (e: IllegalArgumentException) {
            err("参数不合法")
        } catch (t: Throwable) {
            env.log("admin-act", "动作失败（${t.javaClass.simpleName}）")
            err("操作失败，请稍后重试")
        }
    }

    // ------------------------------------------------------------------ 用户 / 会话

    private fun singleAdmin(name: String, p: JSONObject): JSONObject {
        audit(name, "failed", "app-single-admin")
        return err("本机只有一个管理员账号，不支持多用户")
    }

    /**
     * 本机只有一个解锁会话，撤销它 = **立即上锁**（隐藏 WebView、移除 JS 桥、回到管理员门）。
     *
     * 先写审计再上锁：上锁后宿主会立刻卸掉页面，动作的返回值通常已经没人接收。
     */
    private fun revokeSession(p: JSONObject): JSONObject {
        val id = p.optString("id").trim()
        if (id.isNotEmpty() && id != AdminStateJson.UNLOCK_SESSION_ID) {
            return err("本机只有一个解锁会话，不能撤销其它会话")
        }
        AdminThrottle.lock()
        audit("admin_lock", "ok", "session.revoke")
        env.lockSession()
        return okData(null)
    }

    // ------------------------------------------------------------------ 令牌

    /**
     * `token.create` → 静态 key：库里只存 SHA-256，明文只在这里返回一次。
     * 页面传的 `scopes` 被忽略（静态 key 的工具范围由「工具策略」决定，默认不限）。
     */
    private fun createToken(p: JSONObject): JSONObject {
        val name = p.optString("name").trim()
        if (name.isEmpty()) return err("请填令牌名称")
        if (name.length > NAME_MAX) return err("名称太长（最多 $NAME_MAX 字）")
        val raw = TokenFormat.newStaticKey()
        val id = store.putKey(name, Crypto.sha256Hex(raw), null, null, env.now())
        audit("key_create", "ok", name)
        // 一次性明文字段放在**顶层**（app adapter 会把它收进 data，见 admin-ui/adapters/app.js）
        return JSONObject()
            .put("ok", true)
            .put("id", "${AdminStateJson.KEY_ID_PREFIX}$id")
            .put("secret", raw)
            .put("prefix", TokenFormat.prefixOf(raw))
    }

    /** `token.revoke`：`key:<id>` → 撤销静态 key；`tok:<family>:…` → 撤销整个令牌族。 */
    private fun revokeToken(p: JSONObject): JSONObject {
        val id = p.optString("id").trim()
        if (id.startsWith(AdminStateJson.KEY_ID_PREFIX)) {
            val keyId = id.removePrefix(AdminStateJson.KEY_ID_PREFIX).toLongOrNull()
                ?: return err("令牌不存在")
            val key = runCatching { store.keys() }.getOrDefault(emptyList()).firstOrNull { it.id == keyId }
                ?: return err("令牌不存在")
            if (key.revokedAt == null) {
                store.revokeKey(keyId, env.now())
                audit("key_revoke", "ok", key.label)
            }
            return okData(null)
        }
        if (id.startsWith(AdminStateJson.TOKEN_ID_PREFIX)) {
            val parts = id.removePrefix(AdminStateJson.TOKEN_ID_PREFIX).split(':')
            val family = p.optString("family").trim().ifEmpty { parts.getOrNull(0).orEmpty() }
            if (family.isEmpty()) return err("令牌不存在")
            store.revokeFamily(family, env.now())
            // 前缀已在状态里展示，不是凭据；family 本身不进审计
            audit("token_revoke", "ok", parts.getOrNull(2)?.takeIf { it.isNotEmpty() })
            return okData(null)
        }
        return err("令牌不存在")
    }

    // ------------------------------------------------------------------ 客户端

    /** `oauth.create`：管理员手工创建 ⇒ 直接 `active`（这次点击就是批准），没有待批准环节。 */
    private fun createClient(p: JSONObject): JSONObject {
        val name = p.optString("name").trim()
        if (name.isEmpty()) return err("请填客户端名称")
        val kind = p.optString("kind", "public").trim().ifEmpty { "public" }
        if (kind != KIND_PUBLIC && kind != KIND_CONFIDENTIAL) return err("客户端类型不正确")
        val redirects = p.optJSONArray("redirects") ?: JSONArray()
        if (redirects.length() == 0) return err("至少填一个回调地址")
        val body = JSONObject()
            .put("client_name", name)
            .put("redirect_uris", redirects)
            .put("token_endpoint_auth_method", if (kind == KIND_CONFIDENTIAL) "client_secret_post" else "none")
        val out = OAuth.registerClient(store, body, confirmNewClients = false, now = env.now())
        val clientId = out.optString("client_id")
        audit("client_create", "ok", clientId)
        val res = JSONObject().put("ok", true).put("clientId", clientId)
        // 机密客户端的 secret 只在此次返回；明文放顶层，先不进 data
        out.optString("client_secret", "").takeIf { it.isNotEmpty() }?.let { res.put("secret", it) }
        return res
    }

    /** `oauth.toggle`：启用 = 批准（pending→active）；停用 = 吊销（级联令牌与待确认请求）。 */
    private fun toggleClient(p: JSONObject): JSONObject {
        val id = p.optString("id").trim()
        if (id.isEmpty()) return err("客户端不存在")
        val c = store.client(id) ?: return err("客户端不存在")
        val enable = p.optBoolean("enabled", false)
        if (enable) {
            when (c.status) {
                McpStatus.CLIENT_ACTIVE -> return okData(null)
                McpStatus.CLIENT_PENDING -> {
                    store.approveClient(id, env.now())
                    audit("client_approve", "ok", id)
                    return okData(null)
                }
                else -> return err("已吊销的客户端不能重新启用，请重新注册")
            }
        }
        when (c.status) {
            McpStatus.CLIENT_PENDING -> {
                store.rejectClient(id, env.now())
                audit("client_reject", "ok", id)
            }
            McpStatus.CLIENT_ACTIVE -> {
                store.revokeClient(id, env.now())
                audit("client_revoke", "ok", id)
            }
            else -> Unit          // 已吊销：幂等，不重复写审计
        }
        return okData(null)
    }

    /** `oauth.delete`：只删**已吊销**的行（活跃行不删，避免悬空令牌）。 */
    private fun deleteClient(p: JSONObject): JSONObject {
        val id = p.optString("id").trim()
        if (id.isEmpty()) return err("客户端不存在")
        val c = store.client(id) ?: return err("客户端不存在")
        if (c.revokedAt == null) return err("只能删除已吊销的客户端")
        store.deleteClient(id)
        audit("client_delete", "ok", id)
        return okData(null)
    }

    /** `pending.decide`：只能动仍 `pending`（未过期、客户端仍 active）的请求。 */
    private fun decidePending(p: JSONObject): JSONObject {
        val id = p.optString("id").trim()
        if (id.isEmpty()) return err("请求不存在")
        val approve = p.optBoolean("approve", false)
        val done = OAuth.decidePending(store, id, approve, OAuth.ALLOWED_SCOPES, env.now())
        audit(
            if (approve) "pending_allow" else "pending_deny",
            if (done) "ok" else "failed",
            TokenFormat.prefixOf(id),
        )
        if (!done) return err("请求已失效，或客户端已被吊销")
        return okData(null)
    }

    // ------------------------------------------------------------------ 健康数据

    /** `coverage.backfill`：排队一次 `SyncWorker` 回填（`type` 本机不支持细分，忽略）。 */
    private fun backfill(p: JSONObject): JSONObject {
        val days = if (p.has("days")) p.optInt("days", DEFAULT_BACKFILL_DAYS) else DEFAULT_BACKFILL_DAYS
        val range = days.coerceIn(1, MAX_BACKFILL_DAYS)
        val queued = runCatching { env.enqueueBackfill(range) }.getOrDefault(false)
        audit("coverage_backfill", if (queued) "ok" else "failed", "${range}d")
        if (!queued) return err("无法排队回填任务")
        return okData(JSONObject().put("queued", true).put("days", range))
    }

    private fun verifyAudit(): JSONObject {
        val chainOk = runCatching { store.auditChainOk() }.getOrDefault(false)
        val checked = runCatching { store.auditRows(limit = AdminStateJson.AUDIT_ROWS).size }.getOrDefault(0)
        audit("audit_verify", if (chainOk) "ok" else "failed", "checked=$checked")
        return okData(JSONObject().put("chainOk", chainOk).put("checked", checked))
    }

    // ------------------------------------------------------------------ 安全

    /**
     * 改管理员密码。`current` 被忽略：能调用这个动作就已经在解锁会话里（原生页同口径）。
     * 密码只经 `AdminGate.hash` 流动，审计里只有 "web"。
     */
    private fun changePassword(p: JSONObject): JSONObject {
        val password = p.optString("password")
        val second = p.optString("password2", password)
        if (password.length < MIN_PASSWORD) return err("密码至少 $MIN_PASSWORD 位")
        if (second.isNotEmpty() && password != second) return err("两次输入不一致")
        val hash = AdminGate.hash(password)
        config.adminPasswordHash = hash
        audit("admin_password_set", "ok", "web")
        return okData(null)
    }

    /** 生成新的 TOTP secret：**只返回一次**，确认码通过后才落 `Secure`。 */
    private fun beginTotp(): JSONObject {
        val secret = Totp.secret()
        pendingTotp = secret
        audit("totp_begin", "ok", null)
        return JSONObject()
            .put("ok", true)
            .put("secret", secret)
            .put("uri", Totp.uri(secret, "HC Bridge", "MCP 管理"))
    }

    /**
     * 确认绑定：校验新密钥的当前动态码后才落 `Secure`。
     *
     * 允许"刚 `totp-rebind` 过"的情况：那时 `pendingTotp` 与已存 secret 是同一个（新密钥），
     * 于是这次确认就是对它再做一次校验；不额外产生恢复码（App 的恢复路径是设备屏幕锁）。
     */
    private fun confirmTotp(p: JSONObject): JSONObject {
        val secret = pendingTotp ?: env.totpSecret() ?: return err("先开始绑定 TOTP")
        val code = p.optString("code").filter { it.isDigit() }
        if (code.length != Totp.DIGITS) return err("请输入 ${Totp.DIGITS} 位数字验证码")
        // 与原生页同一把重放闸：同一个动态码只能用一次
        if (!AdminThrottle.claimTotp(secret, code, env.now())) {
            return err("码不对或已用过（检查手机与验证器的时钟）")
        }
        env.setTotpSecret(secret)
        pendingTotp = null
        audit("totp_enable", "ok", null)
        // App 不生成恢复码：契约里 `recoveryCodes` 是可选的，页面拿不到就只提示"TOTP 已启用"
        return okData(null)
    }

    /**
     * 停用 TOTP：必须带**当前**动态码（与门禁同一把校验：`AdminThrottle.claimTotp` 带防重放）。
     *
     * 解锁会话本身已经证明过身份，这里再要一次动态码是为了拦住"手机被顺手拿到、页面还开着"的场景。
     */
    private fun disableTotp(p: JSONObject): JSONObject {
        val secret = env.totpSecret() ?: return err("TOTP 未启用")
        val code = totpCodeOf(p) ?: return err("请输入 ${Totp.DIGITS} 位数字验证码")
        if (!AdminThrottle.claimTotp(secret, code, env.now())) {
            return err("动态码不正确或已用过（请等下一个 30 秒）")
        }
        env.setTotpSecret(null)
        pendingTotp = null
        audit("totp_disable", "ok", null)
        return okData(null)
    }

    /**
     * 重新绑定：先校验**当前**动态码，再一次性换上新密钥（旧验证器立刻失效）。
     *
     * 返回新密钥的 `{secret, uri}`（只此一次，页面据此显示绑定信息）；`pendingTotp` 一并置成新密钥，
     * 页面随后的 `security.totp-confirm` 就是对新密钥做一次确认。
     * 恢复路径：这台设备的屏幕锁（App 不发恢复码）。
     */
    private fun rebindTotp(p: JSONObject): JSONObject {
        val current = env.totpSecret() ?: return err("TOTP 未启用")
        val code = totpCodeOf(p) ?: return err("请输入 ${Totp.DIGITS} 位数字验证码")
        if (!AdminThrottle.claimTotp(current, code, env.now())) {
            return err("动态码不正确或已用过（请等下一个 30 秒）")
        }
        val secret = Totp.secret()
        // 一次写入 = 停用旧密钥 + 启用新密钥（旧验证器的码立刻作废）
        env.setTotpSecret(secret)
        pendingTotp = secret
        audit("totp_rebind", "ok", null)
        return JSONObject()
            .put("ok", true)
            .put("secret", secret)
            .put("uri", Totp.uri(secret, "HC Bridge", "MCP 管理"))
    }

    /** 6 位数字动态码；位数不对 ⇒ `null`（不把用户输入原样回显或写审计）。 */
    private fun totpCodeOf(p: JSONObject): String? =
        p.optString("code").filter { it.isDigit() }.takeIf { it.length == Totp.DIGITS }

    /** 本机策略是常量（`AdminGate` 的 5 分钟 / 5 次 / 60 秒），页面不提供编辑表单。 */
    private fun rejectSettings(): JSONObject {
        audit("settings_update", "failed", "app-fixed-policy")
        return err("本机策略固定（解锁超时与锁定阈值），不能从页面修改")
    }

    // ------------------------------------------------------- 工具策略 / 时间窗

    /**
     * `token.policy` / `token.window`：静态 key 改 key 那一层，OAuth 令牌改**整个令牌族**的
     * 令牌层（访问 + 刷新都改，刷新轮换时才会把收窄继承下去）。
     *
     * 与原生 `applyKeyPolicy` 同口径：先 `canonical`（非法 ⇒ 不写库），再写；已吊销/不存在 ⇒ `ok:false`。
     */
    private fun tokenLayer(p: JSONObject, policy: Boolean): JSONObject {
        val id = p.optString("id").trim()
        if (id.isEmpty()) return err("令牌不存在")
        val raw = jsonField(p, if (policy) "policy" else "window")
        val canonical = try {
            if (policy) ToolPolicy.canonical(raw) else TimeWindow.canonical(raw)
        } catch (e: IllegalArgumentException) {
            return err(if (policy) "策略格式不对：只接受 null 或数组" else "时间窗格式不对：只接受 null 或对象")
        }

        if (id.startsWith(AdminStateJson.KEY_ID_PREFIX)) {
            val keyId = id.removePrefix(AdminStateJson.KEY_ID_PREFIX).toLongOrNull()
                ?: return err("令牌不存在")
            val key = runCatching { store.keys() }.getOrDefault(emptyList()).firstOrNull { it.id == keyId }
                ?: return err("令牌不存在")
            val ok = if (policy) {
                store.updateKey(keyId, canonical, key.windowJson, env.now())
            } else {
                store.updateKey(keyId, key.toolPolicyJson, canonical, env.now())
            }
            if (!ok) return err("令牌不存在或已撤销")
            audit(
                if (policy) "key_policy_update" else "key_window_update",
                "ok",
                "key:$keyId " + (canonical ?: "null"),
            )
            return okData(null)
        }

        if (id.startsWith(AdminStateJson.TOKEN_ID_PREFIX)) {
            val parts = id.removePrefix(AdminStateJson.TOKEN_ID_PREFIX).split(':')
            val family = p.optString("family").trim().ifEmpty { parts.getOrNull(0).orEmpty() }
            if (family.isEmpty()) return err("令牌不存在")
            val rows = runCatching { store.tokens() }.getOrDefault(emptyList())
                .filter { it.familyId == family && it.revokedAt == null }
            if (rows.isEmpty()) return err("令牌不存在或已撤销")
            for (row in rows) {
                store.putToken(
                    if (policy) row.copy(toolPolicyJson = canonical) else row.copy(windowJson = canonical),
                )
            }
            audit(
                if (policy) "token_policy_update" else "token_window_update",
                "ok",
                "family rows=${rows.size} " + (canonical ?: "null"),
            )
            return okData(null)
        }
        return err("令牌不存在")
    }

    /** `oauth.policy` / `oauth.window`：客户端层（与原生 `applyClientPolicy` / `applyClientWindow` 同口径）。 */
    private fun clientLayer(p: JSONObject, policy: Boolean): JSONObject {
        val id = p.optString("id").trim()
        val c = store.client(id) ?: return err("客户端不存在")
        val raw = jsonField(p, if (policy) "policy" else "window")
        val canonical = try {
            if (policy) ToolPolicy.canonical(raw) else TimeWindow.canonical(raw)
        } catch (e: IllegalArgumentException) {
            return err(if (policy) "策略格式不对：只接受 null 或数组" else "时间窗格式不对：只接受 null 或对象")
        }
        store.putClient(
            if (policy) c.copy(toolPolicyJson = canonical) else c.copy(windowJson = canonical),
        )
        audit(
            if (policy) "policy_update" else "window_update",
            "ok",
            id + " " + (canonical ?: "null"),
        )
        return okData(null)
    }

    // ---------------------------------------------------------------- 本机访问

    /**
     * `local.require-auth`：公网/隧道开着时**只往上拧**（拒绝关闭），与原生页同一道守卫。
     * 关闭不会顺手打开回环豁免 —— 两个开关是独立的两步。
     */
    private fun setRequireAuth(p: JSONObject): JSONObject {
        val enabled = p.optBoolean("enabled", false)
        if (!enabled && Tunnel.publicEnabled(config)) {
            return err("公网/隧道开着：鉴权不能关（先关公网并保存）")
        }
        if (config.requireAuth != enabled) {
            config.requireAuth = enabled
            audit("require_auth", "ok", if (enabled) "1" else "0")
        }
        return okData(null)
    }

    private fun setLoopback(p: JSONObject): JSONObject {
        val enabled = p.optBoolean("enabled", false)
        if (config.allowLoopbackNoAuth != enabled) {
            config.allowLoopbackNoAuth = enabled
            audit("allow_loopback_no_auth", "ok", if (enabled) "1" else "0")
        }
        return okData(null)
    }

    private fun setConfirmClients(p: JSONObject): JSONObject {
        val enabled = p.optBoolean("enabled", false)
        if (config.confirmNewClients != enabled) {
            config.confirmNewClients = enabled
            audit("confirm_new_clients", "ok", if (enabled) "1" else "0")
        }
        return okData(null)
    }

    /**
     * `local.save-public`：模式 / 公网基址 / tunnel token **一次写完**（与原生 `savePublicSettings` 同口径）。
     *
     * 任何一项校验失败就**什么都不写**（连模式也不写）：半套配置会让 issuer 与客户端预期对不上。
     * `token` 空串 = 保留已存的那个（界面从不回显 token）；审计只记 `token=set|keep`，没有值。
     */
    private fun savePublicSettings(p: JSONObject): JSONObject {
        val mode = p.optString("mode", McpConfig.TUNNEL_OFF).trim().ifEmpty { McpConfig.TUNNEL_OFF }
        if (mode !in McpConfig.TUNNEL_MODES) return err("隧道模式不合法")
        val rawBase = if (p.has("publicBase") && !p.isNull("publicBase")) p.optString("publicBase", "") else ""
        if (!Tunnel.validatePublicBase(rawBase)) {
            return err("公网基址必须是 https://host（不带路径、查询或尾部斜杠）")
        }
        // 存归一化值：存原串会让探活的 resource 比对永远不等（假"不可达"）
        val base = Tunnel.normalizePublicBase(rawBase)
        val newToken = p.optString("token", "").trim()
        if (mode == McpConfig.TUNNEL_NAMED && newToken.isEmpty() && !env.hasTunnelToken()) {
            return err("named tunnel 需要 tunnel token：先粘进来再保存")
        }
        val before = config.publicBase
        config.publicBase = base
        if (config.tunnelMode != mode) config.tunnelMode = mode
        if (newToken.isNotEmpty()) env.setTunnelToken(newToken)
        Tunnel.applyGuards(config)
        if (before != base) env.setLastProbe(null)   // 基址变了：上一次探活结果作废
        audit(
            "public_settings",
            "ok",
            "mode=$mode base=${base ?: "null"} token=${if (newToken.isNotEmpty()) "set" else "keep"}",
        )
        return okData(null)
    }

    private fun clearTunnelToken(): JSONObject {
        env.setTunnelToken(null)
        audit("tunnel_token", "ok", "cleared")
        return okData(null)
    }

    /**
     * `local.command`：按**已保存**的模式拼 Termux 命令。
     *
     * `full:true` 且是 named + 已存 token ⇒ 命令里带真 token（只出现在本动作返回值里，
     * 页面只把它放进剪贴板）；其余情况给占位符版。审计只记 `full|placeholder`，**没有命令、没有 token**。
     */
    private fun tunnelCommand(p: JSONObject): JSONObject {
        val full = p.optBoolean("full", false)
        val mode = config.tunnelMode
        if (mode == McpConfig.TUNNEL_OFF) {
            return okData(JSONObject().put("command", ""))
        }
        val token = if (full && mode == McpConfig.TUNNEL_NAMED) env.tunnelToken() else null
        val command = runCatching { Tunnel.commandFor(mode, token) }.getOrElse { "" }
        audit("tunnel_command", "ok", if (token != null) "full" else "placeholder")
        return okData(JSONObject().put("command", command))
    }

    /**
     * `local.probe`：只探**已保存**的公网基址（与原生一致：issuer 还没切过去就先保存）。
     *
     * 阻塞调用（最长约 2 × 8 秒）：本动作跑在 JS 桥线程，不在主线程。结果写进进程内状态，
     * 页面的"最近检测"直接从下一次 `state()` 读；审计只记 `ms=`。
     */
    private fun probePublic(): JSONObject {
        val base = config.publicBase ?: return err("先保存公网基址再检测（issuer 还没切到公网）")
        val result = runCatching { env.probe(base) }
            .getOrElse { ProbeEntry(false, env.now(), "探活失败（${it.javaClass.simpleName}）") }
        env.setLastProbe(result)
        audit("tunnel_probe", if (result.ok) "ok" else "failed", "ms=${result.ms}")
        return okData(
            JSONObject()
                .put("ok", result.ok)
                .put("at", result.at)
                .put("detail", result.detail),
        )
    }

    /** payload 里的 JSON 字段：`null` ⇒ `null`；字符串按原文（空串 = null）；对象/数组序列化后返回。 */
    private fun jsonField(p: JSONObject, key: String): String? {
        if (!p.has(key) || p.isNull(key)) return null
        return when (val v = p.opt(key)) {
            null -> null
            is String -> v.trim().ifEmpty { null }
            else -> v.toString()
        }
    }

    // ------------------------------------------------------------------ 小工具

    private fun oauthMessage(e: OAuthError): String = when (e.code) {
        "invalid_redirect_uri" -> "回调地址不合规：必须是 https 或回环 http，且不能带 #"
        "invalid_client_metadata" -> "客户端数量已达上限或类型不支持"
        else -> "客户端注册失败"
    }

    private fun audit(action: String, result: String, detail: String?) =
        runCatching { store.audit("admin", action, null, result, detail, env.now()) }

    private fun okData(data: JSONObject?): JSONObject =
        JSONObject().put("ok", true).apply { if (data != null) put("data", data) }

    private fun err(message: String): JSONObject = JSONObject().put("ok", false).put("error", message)

    private companion object {
        /** 与原生管理页同一个下限（`McpAdminActivity.MIN_PASSWORD`）。 */
        const val MIN_PASSWORD = 8

        const val NAME_MAX = 100

        /** `coverage.backfill` 缺省回补窗口（天）：与设置页「回补 365 天」一致。 */
        const val DEFAULT_BACKFILL_DAYS = 365

        /** `SyncWorker.runBackfill` 的实际上限（`hc_backfill` 也是 730）。 */
        const val MAX_BACKFILL_DAYS = 730

        const val KIND_PUBLIC = "public"
        const val KIND_CONFIDENTIAL = "confidential"

        const val LOCKED = "管理会话已锁定，请重新解锁"
    }
}
