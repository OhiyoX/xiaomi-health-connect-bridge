package id.harness.hcbridge.logic

/**
 * WebView 登录成功后，从 `CookieManager` 返回的 cookie 串里取小米凭据。
 *
 * 只认精确的 `userId` / `passToken` 两个键（`cUserId`、`serviceToken` 之类不能混用）；
 * 值可能带引号或空格，这里一并归一化。
 */
object LoginCookie {

    fun parse(cookie: String?): Pair<String, String>? {
        if (cookie.isNullOrBlank()) return null
        var userId: String? = null
        var passToken: String? = null
        for (raw in cookie.split(';')) {
            val part = raw.trim()
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val value = part.substring(eq + 1).trim().trim('"')
            if (value.isEmpty()) continue
            when (part.substring(0, eq).trim()) {
                "userId" -> userId = value
                "passToken" -> passToken = value
            }
        }
        return if (userId != null && passToken != null) userId to passToken else null
    }
}
