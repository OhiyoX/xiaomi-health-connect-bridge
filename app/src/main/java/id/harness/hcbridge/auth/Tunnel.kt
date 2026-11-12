package id.harness.hcbridge.auth

import java.net.URI

/**
 * Cloudflare Tunnel 的**纯文本**部分（计划 Task 8 / 决定 8）。
 *
 * app 只做三件事：**存配置**、**拼命令**、**探活**。它
 * - 不打包 `cloudflared`（不把二进制塞进 APK）；
 * - 不启动、不杀 tunnel（Termux 里由人手动跑；`RUN_COMMAND` 不实现）；
 * - 不把 tunnel token 写进日志、审计、通知或界面（存 `Secure`，见 [SECURE_KEY]）。
 *
 * 命令形态（两种都验证过要这样拼）：
 * - `named`：`cloudflared tunnel run --token <TOKEN>` —— **只认 token**。
 *   写成 `cloudflared tunnel run <隧道名>` 会去读本机 `cert.pem`（手机上根本没有）并直接失败，
 *   所以隧道名**永远不作位置参数**；它只出现在 Cloudflare 侧的配置里（文档说明用）。
 * - `quick`：`cloudflared tunnel --url http://127.0.0.1:8765 --no-autoupdate` —— 端口钉死回环，
 *   关掉自动更新（app 不替它管版本）。
 *
 * 这个 object 不 import `android.*`：命令就是字符串，`TunnelTest` 在 JVM 上覆盖。
 */
object Tunnel {

    /** `Secure` 里的键：tunnel token 只以密文存这儿。 */
    const val SECURE_KEY = "tunnelToken"

    /** 占位命令里用的环境变量名（token 不进聊天/文档）。 */
    const val ENV_TOKEN = "TUNNEL_TOKEN"

    /**
     * cloudflared 允许转发的路径前缀（named tunnel 的 ingress 规则照这个写）。
     *
     * **管理面不在里面**：HTTP 上根本没有 `/admin*` 路由（`McpRouter` 一律 404），这里也不放行 ——
     * 管理只走 app 内的 [id.harness.hcbridge.McpAdminActivity]。
     */
    val PUBLIC_PATHS: List<String> = listOf(
        "/mcp",
        "/.well-known/",
        "/authorize",
        "/token",
        "/register",
        "/revoke",
        "/health",
    )

    private val SHELL_SAFE = Regex("^[A-Za-z0-9._~+=:/-]+$")

    /** Termux 里要跑的命令；[McpConfig.TUNNEL_OFF] ⇒ 空串（不需要 cloudflared）。 */
    fun commandFor(mode: String, token: String?, hostname: String? = null): String = when (mode) {
        McpConfig.TUNNEL_OFF -> ""
        McpConfig.TUNNEL_NAMED -> {
            // hostname 只用于文档/界面说明，**不进命令**（见类注释）
            val t = token?.trim()?.takeIf { it.isNotEmpty() }
            if (t == null) "cloudflared tunnel run --token \"\$$ENV_TOKEN\""
            else "cloudflared tunnel run --token ${shellArg(t)}"
        }
        // 端口来自 Endpoints.PORT（= McpService.PORT）：命令里的地址不可能与服务绑定的端口漂开
        McpConfig.TUNNEL_QUICK -> "cloudflared tunnel --url http://127.0.0.1:${Endpoints.PORT} --no-autoupdate"
        else -> throw IllegalArgumentException("bad tunnel mode")
    }

    /**
     * 公网基址校验：**空白 = 合法**（只走本机）；否则必须是裸 `https://host[:port]`。
     *
     * 比 `Endpoints.from` 更严：**连尾斜杠都不收**。理由：这里是人手粘贴的输入框，
     * `https://h/` 与 `https://h` 同时存在只会制造两个 issuer；宁可当场提示改。
     */
    fun validatePublicBase(v: String?): Boolean {
        val raw = v?.trim()?.takeIf { it.isNotEmpty() } ?: return true
        if (raw.any { it.isWhitespace() }) return false
        val uri = try {
            URI(raw)
        } catch (e: Exception) {
            return false
        }
        if (uri.isOpaque) return false
        if (uri.scheme?.lowercase() != "https") return false
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return false
        if (uri.host.isNullOrEmpty()) return false
        if (!uri.path.isNullOrEmpty()) return false // "/" 也不收
        val port = uri.port
        return port == -1 || port in 1..65535
    }

    /**
     * 校验 + **归一化**公网基址。
     *
     * 空白 ⇒ `null`（只走本机）；非法 ⇒ `null`；合法 ⇒ `Endpoints.from(v).issuer`
     * （host 折小写、去掉 `:443`、去掉尾斜杠）。
     *
     * 存归一化值是必须的：`Endpoints.from("https://Host.example.com:443").resource`
     * 是 `https://host.example.com/mcp`，存原串会让探活的 `resource` 比对永远不等 ⇒ 假"不可达"。
     */
    fun normalizePublicBase(v: String?): String? {
        if (!validatePublicBase(v)) return null
        val raw = v?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Endpoints.from(raw).issuer
    }

    fun modeLabel(mode: String): String = when (mode) {
        McpConfig.TUNNEL_OFF -> "关闭（只走本机）"
        McpConfig.TUNNEL_NAMED -> "named tunnel（固定域名）"
        McpConfig.TUNNEL_QUICK -> "quick tunnel（临时域名）"
        else -> mode // 未知值原样回显，不抛：界面不该因为一个脏字符串崩掉
    }

    /** 公网入口是否开着（`publicBase` 或非 off 的 tunnelMode 任一成立）。 */
    fun publicEnabled(config: McpConfig): Boolean =
        config.tunnelMode != McpConfig.TUNNEL_OFF || config.publicBase != null

    /**
     * 强制守卫（计划决定 8）：只要开了公网入口，`requireAuth` 就必须是 `true`。
     *
     * **只往上拧，不自动松开**：关掉 tunnel/公网基址后仍要管理员自己决定是否重新关闭鉴权
     * （`requireAuth` 保持原值，管理页给提示）。
     */
    fun applyGuards(config: McpConfig) {
        if (!publicEnabled(config)) return
        if (!config.requireAuth) config.requireAuth = true
    }

    /** 这个路径能不能经公网转发（`/mcp` 与其子路径、OAuth 端点、`/health`）。 */
    fun isPublicPath(path: String): Boolean = PUBLIC_PATHS.any { p ->
        path == p || path.startsWith(if (p.endsWith("/")) p else "$p/")
    }

    /** 管理面路径：HTTP 上不存在（404），隧道也不放行。 */
    fun isAdminPath(path: String): Boolean = path == "/admin" || path.startsWith("/admin/")

    /** 只给 shell 安全的 token 原样输出；含空格/引号/分号等一律单引号包起来。 */
    private fun shellArg(v: String): String =
        if (SHELL_SAFE.matches(v)) v else "'" + v.replace("'", "'\\''") + "'"
}
