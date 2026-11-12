package id.harness.hcbridge

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 密钥类数据（远端 token）落在 EncryptedSharedPreferences：
 * 主密钥在 Android Keystore（AES256-GCM，不可导出），文件里只有密文。
 * Keystore 不可用时降级为普通 prefs，但只在应用私有目录（不入备份、不可被其它 app 读）。
 */
object Secure {

    private const val FILE = "secure_prefs"
    @Volatile private var cached: SharedPreferences? = null

    private fun sp(c: Context): SharedPreferences? = cached ?: runCatching {
        val key = MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            c, FILE, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        android.util.Log.w("HCBridge", "EncryptedSharedPreferences 不可用，降级普通 prefs：${it.message}")
        c.getSharedPreferences("secure_fallback", Context.MODE_PRIVATE)
    }.also { cached = it }

    fun getString(c: Context, key: String): String? = runCatching { sp(c)?.getString(key, null) }.getOrNull()

    fun putString(c: Context, key: String, value: String?) {
        runCatching {
            sp(c)?.edit()?.apply {
                if (value.isNullOrBlank()) remove(key) else putString(key, value)
            }?.apply()
        }
    }
}
