package id.harness.hcbridge.auth

import android.content.Context
import id.harness.hcbridge.Secure

/**
 * TOTP secret 的**唯一**存取口（计划 Task 6 / 设计决定 7）。
 *
 * secret 落 `Secure`（EncryptedSharedPreferences，主密钥在 Android Keystore；键 `totpSecret`），
 * **不进** `settings` 表、不进 `McpConfig`、不进日志、不进审计。`McpConfig` 刻意不碰 `Secure`
 * （纯 JVM 可测），所以需要 `Context` 的这一小块单独放这里。
 */
object TotpSecret {

    /** `Secure` 里的键名（测试也照它断言 settings 表里没有同名键）。 */
    const val KEY = "totpSecret"

    /** 当前 secret（base32）；没启用/被清空 ⇒ `null`。 */
    fun get(c: Context): String? =
        Secure.getString(c, KEY)?.filterNot { it.isWhitespace() }?.takeIf { it.isNotEmpty() }

    /** `null`/空白 = 关闭 TOTP（[Secure] 会删掉这个键）。 */
    fun set(c: Context, v: String?) {
        val clean = v?.filterNot { it.isWhitespace() }?.takeIf { it.isNotEmpty() }
        Secure.putString(c, KEY, clean)
    }

    fun enabled(c: Context): Boolean = get(c) != null
}
