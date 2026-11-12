package id.harness.hcbridge.auth

/**
 * MCP 会话配置（计划 Task 3 / 决定 8、9、13）。
 *
 * 全部落在 `settings` 表里（键名见 `companion object`），所以：
 * - app 重启/进程被杀后设置还在；
 * - 没有单独的 preferences 文件要同步，也不进备份之外的别处。
 *
 * **本类不碰 `Secure`、不存 TOTP secret**：TOTP 走 Task 6 的 `TotpSecret`（要 `Context`），
 * 静态 key / client secret 明文从不经过这里。
 *
 * 值语义（缺省即"最保守"）：
 * - [requireAuth] 缺省 `true`；[allowLoopbackNoAuth] 缺省 `false` —— 两个都关掉才可能有回环豁免，
 *   而且还要 `publicBase == null` 且 `tunnelMode == "off"`（判定在 Task 5 的 `trustedLoopback`）。
 * - [confirmNewClients] 缺省 `true`：DCR 注册的客户端落 `status='pending'`，等管理员批准。
 * - [tunnelMode] 只有 `"off" | "named" | "quick"`；写别的值抛 [IllegalArgumentException]。
 */
class McpConfig(private val store: McpStore) {

    var requireAuth: Boolean
        get() = bool(KEY_REQUIRE_AUTH, true)
        set(v) = put(KEY_REQUIRE_AUTH, if (v) "1" else "0")

    var allowLoopbackNoAuth: Boolean
        get() = bool(KEY_ALLOW_LOOPBACK_NO_AUTH, false)
        set(v) = put(KEY_ALLOW_LOOPBACK_NO_AUTH, if (v) "1" else "0")

    /** 公网基址（`https://…`，无路径/尾斜杠）；`null` = 只走本机。 */
    var publicBase: String?
        get() = store.setting(KEY_PUBLIC_BASE)?.trim()?.takeIf { it.isNotEmpty() }
        set(v) = put(KEY_PUBLIC_BASE, v?.trim()?.takeIf { it.isNotEmpty() })

    var tunnelMode: String
        get() = store.setting(KEY_TUNNEL_MODE)?.takeIf { it in TUNNEL_MODES } ?: TUNNEL_OFF
        set(v) {
            require(v in TUNNEL_MODES) { "bad tunnel mode" }
            put(KEY_TUNNEL_MODE, v)
        }

    /** 管理员密码哈希（`pbkdf2$sha256$<iter>$<salt>$<hash>`）；`null` = 还没设置。 */
    var adminPasswordHash: String?
        get() = store.setting(KEY_ADMIN_PASSWORD_HASH)?.takeIf { it.isNotEmpty() }
        set(v) = put(KEY_ADMIN_PASSWORD_HASH, v?.takeIf { it.isNotEmpty() })

    var confirmNewClients: Boolean
        get() = bool(KEY_CONFIRM_NEW_CLIENTS, true)
        set(v) = put(KEY_CONFIRM_NEW_CLIENTS, if (v) "1" else "0")

    var migrationNoticeSeen: Boolean
        get() = bool(KEY_MIGRATION_NOTICE_SEEN, false)
        set(v) = put(KEY_MIGRATION_NOTICE_SEEN, if (v) "1" else "0")

    /**
     * 管理页形态（0.9.0）：`web` = 内置共享 SPA（默认），`native` = 旧原生页（隐藏回退）。
     *
     * **没有设置界面**：只有 `settings` 表里这一行能改（出问题时手工回退）。写别的值抛
     * [IllegalArgumentException]；读到脏值按缺省 [ADMIN_UI_WEB] 处理，不因为一个坏字符串把入口弄丢。
     */
    var adminUiMode: String
        get() = store.setting(KEY_ADMIN_UI_MODE)?.takeIf { it in ADMIN_UI_MODES } ?: ADMIN_UI_WEB
        set(v) {
            require(v in ADMIN_UI_MODES) { "bad admin ui mode" }
            put(KEY_ADMIN_UI_MODE, v)
        }

    /**
     * 迁移提示是否要显示（计划 Task 9）：**只提示一次**，且只提示"还没完全显式豁免"的装机。
     *
     * `requireAuth || !allowLoopbackNoAuth` = 只要不是「两个开关都关掉」的完整豁免配置，
     * 就认为管理员还可能被新默认值挡住（老装机升级后本机会 401）。
     * 本方法**只读**：不打开任何豁免、不写任何配置。
     */
    fun shouldShowMigrationNotice(): Boolean =
        !migrationNoticeSeen && (requireAuth || !allowLoopbackNoAuth)

    private fun bool(key: String, def: Boolean): Boolean = when (store.setting(key)) {
        null -> def
        "1", "true", "TRUE", "True" -> true
        "0", "false", "FALSE", "False" -> false
        else -> def // 坏值退回缺省，不因为一个脏字符串把鉴权关掉
    }

    private fun put(key: String, value: String?) = store.setSetting(key, value)

    companion object {
        const val TUNNEL_OFF = "off"
        const val TUNNEL_NAMED = "named"
        const val TUNNEL_QUICK = "quick"

        val TUNNEL_MODES = setOf(TUNNEL_OFF, TUNNEL_NAMED, TUNNEL_QUICK)

        /** 默认管理页：内置共享 SPA（WebView）。 */
        const val ADMIN_UI_WEB = "web"

        /** 隐藏回退：旧的原生管理页（`McpAdminActivity`）。 */
        const val ADMIN_UI_NATIVE = "native"

        val ADMIN_UI_MODES = setOf(ADMIN_UI_WEB, ADMIN_UI_NATIVE)

        const val KEY_REQUIRE_AUTH = "requireAuth"
        const val KEY_ALLOW_LOOPBACK_NO_AUTH = "allowLoopbackNoAuth"
        const val KEY_PUBLIC_BASE = "publicBase"
        const val KEY_TUNNEL_MODE = "tunnelMode"
        const val KEY_ADMIN_PASSWORD_HASH = "adminPasswordHash"
        const val KEY_CONFIRM_NEW_CLIENTS = "confirmNewClients"
        const val KEY_MIGRATION_NOTICE_SEEN = "migrationNoticeSeen"
        const val KEY_ADMIN_UI_MODE = "adminUiMode"
    }
}
