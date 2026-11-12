package id.harness.hcbridge

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.webkit.JavascriptInterface
import id.harness.hcbridge.auth.Tunnel
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * `window.HcbAdmin`：共享前端（`file:///android_asset/admin/`）与原生 store 之间的**唯一**通道。
 *
 * 契约（`admin-ui/API.md` §6 / `admin-ui/adapters/app.js`）：
 * ```
 * HcbAdmin.state()                 -> 状态 JSON 字符串
 * HcbAdmin.act(name, payloadJson)  -> {"ok":true,...} / {"ok":false,"error":"…"}
 * HcbAdmin.clipboard(text)         -> {"ok":true} / {"ok":false,"error":"…"}
 * HcbAdmin.close()                 -> 关闭管理页
 * HcbAdmin.ready()                 -> 首屏就绪（收起原生加载指示）
 * ```
 *
 * 三条硬约束：
 * 1. **绝不抛异常**：JS 侧 `try/catch` 拿不到原生栈，抛出去只会变成"页面永远转圈"；
 *    每个方法都折成 JSON（`{"error":"…"}` / `{"ok":false,…}`）。
 * 2. 方法跑在 **WebView 的 JS 桥线程**，不是主线程：实现里不许直接动控件（宿主自己 `runOnUiThread`）。
 * 3. 实例只在**解锁后**挂上（`addJavascriptInterface`），上锁/`onStop`/销毁时移除（见 `McpWebAdminActivity`）。
 *
 * 一次性明文（静态 key / client secret / TOTP secret）由宿主放在 `act` 结果的**顶层**，
 * adapter 会把它收进 `data`（见 `admin-ui/adapters/app.js`）；这里不做任何形状转换。
 */
class AdminBridge(private val host: Host) {

    /** 宿主（`McpWebAdminActivity` 实现）：所有真正的工作在那边，桥只做"永不抛"的包装。 */
    interface Host {
        /** 拼状态 JSON；失败时返回 `{error:"…"}`（页面显示错误横幅 + 重试）。 */
        fun stateJson(): JSONObject

        /** 执行动作；`payloadJson` 可能是空串/`null`/坏 JSON。 */
        fun actJson(name: String, payloadJson: String?): JSONObject

        /** 写剪贴板；返回 `{ok:true}` / `{ok:false,error:"…"}`。 */
        fun copyToClipboard(text: String): JSONObject

        /** 页面的「退出登录」：关闭管理页。 */
        fun closeFromPage()

        /** 首屏渲染完成：收起原生加载指示。 */
        fun pageReady()

        /** 宿主当前主题：`dark` / `light`（App 跟随 DayNight）。 */
        fun theme(): String
    }

    @JavascriptInterface
    fun state(): String = wrap { host.stateJson() }

    @JavascriptInterface
    fun act(name: String?, payloadJson: String?): String = wrap {
        host.actJson(name.orEmpty(), payloadJson)
    }

    @JavascriptInterface
    fun theme(): String = try {
        host.theme()
    } catch (t: Throwable) {
        "dark"
    }

    @JavascriptInterface
    fun clipboard(text: String?): String = wrap {
        host.copyToClipboard(text.orEmpty())
    }

    @JavascriptInterface
    fun close() {
        try {
            host.closeFromPage()
        } catch (t: Throwable) {
            // 关闭失败也没有可回报的对象：吞掉，不让桥把异常抛回 JS
        }
    }

    @JavascriptInterface
    fun ready() {
        try {
            host.pageReady()
        } catch (t: Throwable) {
            // 就绪信号失败不影响功能
        }
    }

    private fun wrap(block: () -> JSONObject): String = try {
        block().toString()
    } catch (t: Throwable) {
        // 不把异常类/堆栈回给页面：它会被原样 toast 出来
        JSONObject().put("error", "本机管理接口出错").toString()
    }
}

/**
 * 剪贴板里的一次性明文（静态 key / TOTP secret / client secret）**60 秒后尝试清掉**
 * （只在"还是我们放的那份"时清，不覆盖用户后来复制的内容）。
 *
 * 与原生管理页同一策略、同一限制：Android 从后台读剪贴板可能被拒，**以系统行为为准**；
 * 真正可靠的边界是"剪贴板里本来就不该长期放凭据"。
 */
object SecretClipboard {

    /** 含 token 的剪贴板内容保留多久。 */
    const val CLEAR_MS = 60_000L

    /** 只对**看起来像凭据**的内容排队清理；复制 client_id 这种标识不清。 */
    fun looksSecret(value: String): Boolean {
        if (value.startsWith("hcb_")) return true
        if (value.startsWith("otpauth://")) return true
        // 含真 token 的 cloudflared 命令（占位符版用的是 `$TUNNEL_TOKEN`，不算）
        if (value.contains("cloudflared") && value.contains("--token ") &&
            !value.contains("$" + Tunnel.ENV_TOKEN)
        ) {
            return true
        }
        // TOTP secret：32 个 base32 字符（A-Z2-7）
        return value.length in 16..64 && value.all { it in 'A'..'Z' || it in '2'..'7' }
    }

    /** 进程级定时清理（不绑 Activity 的协程作用域：Activity 走了也要清）。 */
    fun scheduleClear(ctx: Context, value: String) {
        if (!looksSecret(value)) return
        val app = ctx.applicationContext
        Thread({
            try {
                Thread.sleep(CLEAR_MS)
            } catch (t: InterruptedException) {
                return@Thread
            }
            clearIfOurs(app, value)
        }, "hcb-admin-clip").apply { isDaemon = true }.start()
    }

    fun clearIfOurs(ctx: Context, value: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val current = cm.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(ctx)?.toString()
        if (current == value) runCatching { cm.clearPrimaryClip() }
    }

    /** 复制（Android 13+ 标记 `IS_SENSITIVE`：系统不预览、不进剪贴板历史）。 */
    fun copy(ctx: Context, value: String, label: String): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        cm.setPrimaryClip(clip)
        scheduleClear(ctx, value)
        true
    }.getOrDefault(false)

    /** 主线程同步等待的复制包装（桥线程 → 主线程）；失败返回 `{ok:false}`。 */
    fun copyBlocking(ctx: Context, value: String, label: String): JSONObject {
        if (value.isEmpty()) return JSONObject().put("ok", false).put("error", "没有可复制的内容")
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return if (copy(ctx, value, label)) JSONObject().put("ok", true)
            else JSONObject().put("ok", false).put("error", "剪贴板不可用")
        }
        var out = JSONObject().put("ok", false).put("error", "剪贴板不可用")
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            out = if (copy(ctx, value, label)) JSONObject().put("ok", true)
            else JSONObject().put("ok", false).put("error", "剪贴板不可用")
            latch.countDown()
        }
        runCatching { latch.await(3, TimeUnit.SECONDS) }
        return out
    }
}
