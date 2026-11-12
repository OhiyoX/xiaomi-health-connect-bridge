package id.harness.hcbridge.auth

/**
 * 令牌、授权码、client secret、静态 key、待确认请求 id 的**格式**。
 *
 * 约定：`<prefix><base64url(32 随机字节，无填充)>`；库里只存 [Crypto.sha256Hex] 的结果，
 * 明文只在生成那一刻返回一次。前缀的作用是让日志/审计能在**不泄露**令牌的前提下
 * 用 [prefixOf] 区分“哪一类凭据”（`raw.take(12)`）。
 *
 * 本文件**不得** import 任何 `android.*`。
 */
object TokenFormat {

    /** 访问令牌 `hcb_at_…`（TTL 1 小时）。 */
    const val ACCESS_PREFIX = "hcb_at_"

    /** 刷新令牌 `hcb_rt_…`（TTL 30 天，严格轮换）。 */
    const val REFRESH_PREFIX = "hcb_rt_"

    /** 授权码 `hcb_oc_…`（TTL 5 分钟，一次性）。 */
    const val CODE_PREFIX = "hcb_oc_"

    /** 静态 key `hcb_st_…`。 */
    const val KEY_PREFIX = "hcb_st_"

    /** OAuth 客户端密钥 `hcb_cs_…`。 */
    const val CLIENT_SECRET_PREFIX = "hcb_cs_"

    /** 待确认请求 id `hcb_rq_…`（`/authorize?req=<id>`）。 */
    const val REQUEST_PREFIX = "hcb_rq_"

    const val ACCESS_TTL_MS = 3_600_000L       // 1h
    const val REFRESH_TTL_MS = 2_592_000_000L  // 30d
    const val CODE_TTL_MS = 300_000L           // 5min
    const val PENDING_TTL_MS = 300_000L        // 5min

    /** 令牌体随机字节数。 */
    const val TOKEN_BYTES = 32

    /** 待确认请求 id 的随机字节数（比令牌短；它不是凭据，只是不可猜的句柄）。 */
    const val REQUEST_BYTES = 24

    /** 日志/审计里可安全记录的前缀长度（含 `hcb_xx_` 7 个字符）。 */
    private const val PREFIX_LEN = 12

    fun newAccessToken(): String = ACCESS_PREFIX + Crypto.randomToken(TOKEN_BYTES)

    fun newRefreshToken(): String = REFRESH_PREFIX + Crypto.randomToken(TOKEN_BYTES)

    fun newCode(): String = CODE_PREFIX + Crypto.randomToken(TOKEN_BYTES)

    fun newStaticKey(): String = KEY_PREFIX + Crypto.randomToken(TOKEN_BYTES)

    fun newClientSecret(): String = CLIENT_SECRET_PREFIX + Crypto.randomToken(TOKEN_BYTES)

    fun newRequestId(bytes: Int = REQUEST_BYTES): String = REQUEST_PREFIX + Crypto.randomToken(bytes)

    /** 前 12 个字符；比输入短就返回全部（不抛异常，日志路径不该失败）。 */
    fun prefixOf(raw: String): String = raw.take(PREFIX_LEN)
}
