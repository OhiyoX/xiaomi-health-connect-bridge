package id.harness.hcbridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import id.harness.hcbridge.auth.Endpoints
import id.harness.hcbridge.auth.HttpConn
import id.harness.hcbridge.auth.HttpResp
import id.harness.hcbridge.auth.Inbound
import id.harness.hcbridge.auth.McpConfig
import id.harness.hcbridge.auth.McpRouter
import id.harness.hcbridge.auth.McpRpc
import id.harness.hcbridge.auth.McpStore
import id.harness.hcbridge.auth.Principal
import id.harness.hcbridge.auth.Route
import id.harness.hcbridge.auth.Tunnel
import id.harness.hcbridge.auth.respond
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * app 自带的 MCP 服务器：只监听 **127.0.0.1:8765**（公网由 tunnel 转发进来，见 Task 8）。
 *
 * 这一层只干三件事（计划 Task 7）：
 * 1. **socket 拆包**：`BodyReader` 按字节读头/体（头 ≤16 KB、体 ≤256 KB，超限 413，不预分配）；
 * 2. **[McpRouter]**：路由 + 鉴权门（401/`WWW-Authenticate`）+ 限流 + OAuth 端点，**全程一个实例**
 *    （按请求新建会把 `RateLimiter` 的桶清零 = 限流失效）；
 * 3. **执行 + 审计**：`Route.Mcp` → [McpRpc]（工具过滤/策略/时间窗判定都在那里），
 *    这里只提供「跑工具 + 写审计」的回调。
 *
 * 不变式：任何未预料异常只记**类名**（`error=<Class>`），message/参数/令牌都不进日志；
 * 响应体里也不出现参数值。`RUNNING` 给管理页「状态」段用。
 */
class McpService : Service() {

    companion object {
        private const val TAG = "HCBridgeMcp"
        /** 与 `Endpoints.PORT` 同源（同一份数值，不另写一个字面量）。 */
        const val PORT = Endpoints.PORT
        const val CHANNEL = "hc-mcp"
        const val NOTIF_ID = 4713
        const val ACTION_START = "id.harness.hcbridge.MCP_START"
        const val ACTION_STOP = "id.harness.hcbridge.MCP_STOP"

        /** 服务是否在跑（管理页「状态」段只读它，不自己探端口）。 */
        @Volatile
        var RUNNING: Boolean = false
            private set
        /** 无请求这么久就自动停（省电/省内存；pi 下次要用时打开面板或再发广播即可） */
        private const val IDLE_STOP_MS = 10 * 60 * 1000L

        /** 单条连接的读超时：半开连接最多占一个线程 15 秒，超时回 408。 */
        private const val READ_TIMEOUT_MS = 15_000
    }

    private var server: ServerSocket? = null
    private val running = AtomicBoolean(false)
    @Volatile private var lastRequestAt = System.currentTimeMillis()

    private lateinit var store: McpStore
    private lateinit var config: McpConfig

    /** **唯一**的 router：限流桶、端点缓存都挂在它上面，不能按请求新建。 */
    private val router by lazy { McpRouter(store, config) }

    /** `McpTools` 的定义（名字 → 完整 JSON），只建一次。 */
    private val toolDefs: Map<String, JSONObject> by lazy {
        val arr = McpTools.json()
        val out = LinkedHashMap<String, JSONObject>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { out[it.optString("name")] = it }
        }
        out
    }

    override fun onCreate() {
        super.onCreate()
        store = Store.of(this)
        config = McpConfig(store)
        applyConfigGuards()
        startForeground(NOTIF_ID, buildNotification())
        RUNNING = true
        running.set(true)
        Thread({ acceptLoop() }, "hc-mcp-accept").start()
        Thread({ idleWatch() }, "hc-mcp-idle").start()
        Log.i(TAG, "MCP 监听 127.0.0.1:$PORT")
        AppLog.log(
            this, "mcp",
            "服务启动，监听 127.0.0.1:$PORT（requireAuth=${config.requireAuth}，tunnel=${config.tunnelMode}）",
        )
    }

    /**
     * 启动时的配置守卫（计划 Task 8 决定 8）：
     * 1. `publicBase` 写坏（不是裸 https origin）⇒ 记一行「已退回本机」并强制鉴权：路由层
     *    （`McpRouter.endpointsOf`）本来就会静默退回本机 issuer，但坏值不能顺带把鉴权放松；
     * 2. `tunnelMode != "off"` 或 `publicBase != null` ⇒ `requireAuth` 强制 `true`
     *    （只往上拧；管理员之后仍然要显式关公网才能关鉴权）。
     *
     * 幂等：只在真的需要改的时候写 settings（服务每次启动都会走这里）。
     */
    private fun applyConfigGuards() {
        val base = config.publicBase
        if (base != null && !Tunnel.validatePublicBase(base)) {
            AppLog.log(this, "mcp", "公网基址无效（必须是裸 https origin），issuer 已退回本机")
            if (!config.requireAuth) config.requireAuth = true
        } else if (Tunnel.publicEnabled(config) && !config.requireAuth) {
            AppLog.log(this, "mcp", "公网/隧道已开启：requireAuth 强制为 true")
        }
        Tunnel.applyGuards(config)   // 规则只有一处（auth/Tunnel.kt）
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf(); return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        RUNNING = false
        running.set(false)
        runCatching { server?.close() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): android.app.Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "HC Bridge MCP", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val issuer = if (::store.isInitialized) router.endpoints().issuer else "http://127.0.0.1:$PORT"
        val auth = if (::config.isInitialized && !config.requireAuth) "免鉴权" else "要求 Bearer"
        val fallback = if (::store.isInitialized && router.endpointsFallback()) " · 公网地址无效，已退回本机" else ""
        return android.app.Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("HC Bridge MCP 运行中")
            .setContentText("$issuer/mcp · $auth$fallback")
            .setOngoing(true)
            .build()
    }

    // ------------------------------------------------------------- 监听

    private fun acceptLoop() {
        try {
            val ss = ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"))
            server = ss
            while (running.get()) {
                val s = try {
                    ss.accept()
                } catch (t: Throwable) {
                    break
                }
                Thread({ handle(s) }, "hc-mcp-conn").start()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "监听失败", t)
        }
    }

    /**
     * 空闲超过 IDLE_STOP_MS 就自己停：不占后台进程、不留常驻通知。
     *
     * 公网/tunnel 时必须常驻，所以每轮都重读配置（不能在进循环前判一次就 return：
     * 服务跑着的时候打开 tunnel，就再也不会停）。
     */
    private fun idleWatch() {
        while (running.get()) {
            Thread.sleep(60_000)
            if (config.tunnelMode != McpConfig.TUNNEL_OFF || config.publicBase != null) continue
            val idle = System.currentTimeMillis() - lastRequestAt
            if (idle > IDLE_STOP_MS) {
                Log.i(TAG, "空闲 ${idle / 1000}s，自动停止 MCP 服务")
                AppLog.log(this, "mcp", "空闲 ${idle / 60000} 分钟，自动停止（省电）")
                stopSelf()
                return
            }
        }
    }

    /**
     * 一条连接 = 一个请求 = 一个响应（`Connection: close`）。
     *
     * 拆包全在 [HttpConn.read]（纯 JVM、有单测）：读头 ≤16 KB、定长体 ≤256 KB、
     * **没有 `Content-Length` 就是空体**（GET 不会因为等不到 EOF 而挂住）、chunked ⇒ 411。
     * 这里只管：套 `BufferedInputStream`、`soTimeout`、把拒绝写回去、把请求交给 [router]。
     *
     * 任何未预料异常 ⇒ 500 固定文案 + 只记类名；读超时 ⇒ 408。
     */
    private fun handle(sock: Socket) {
        sock.use { s ->
            val os = s.getOutputStream()
            try {
                s.soTimeout = READ_TIMEOUT_MS
                val inp = BufferedInputStream(s.getInputStream())
                val inbound = HttpConn.read(inp, peerLoopback = s.inetAddress?.isLoopbackAddress ?: false)
                val req = when (inbound) {
                    is Inbound.Reject -> {
                        respond(os, HttpResp.text(inbound.status, inbound.message))
                        return
                    }
                    is Inbound.Ok -> inbound.req
                }
                lastRequestAt = System.currentTimeMillis()
                when (val route = router.route(req)) {
                    is Route.Respond -> respond(os, route.resp)
                    is Route.Mcp -> respond(os, handleMcp(route.principal, route.body))
                }
            } catch (t: Throwable) {
                if (t !is SocketTimeoutException) {
                    // 只记类名：message 可能带路径/参数值/令牌片段
                    AppLog.log(this, "mcp", "error=" + t.javaClass.simpleName)
                }
                val status = if (t is SocketTimeoutException) 408 else 500
                runCatching {
                    respond(os, HttpResp.text(status, if (status == 408) "request timeout" else "internal error"))
                }
            }
        }
    }

    /**
     * `/mcp` 的 JSON-RPC：判定（scope/策略/时间窗）在 [McpRpc]，这里只执行与审计。
     *
     * 审计：放行并执行完 ⇒ `ok`；执行抛异常 ⇒ `error`（detail = 异常类名）；被门拒绝 ⇒ `denied`
     * （detail = 稳定原因串）。detail 永远不含参数值。
     */
    private fun handleMcp(principal: Principal, body: String): HttpResp = McpRpc.handle(
        principal = principal,
        body = body,
        now = System.currentTimeMillis(),
        defs = { name -> toolDefs[name] },
        call = { name, args ->
            val at = System.currentTimeMillis()
            try {
                val result = runTool(name, args)
                store.audit(principal.label, "tool_call", name, "ok", null, at)
                result
            } catch (t: Throwable) {
                store.audit(principal.label, "tool_call", name, "error", t.javaClass.simpleName, at)
                throw t
            }
        },
        onDenied = { name, reason ->
            store.audit(principal.label, "tool_call", name, "denied", reason, System.currentTimeMillis())
        },
    )

    /** 工具执行：MCP 的 `result` 形态（`content: [{type:text, text:…}]`）。 */
    private fun runTool(name: String, args: JSONObject): JSONObject {
        AppLog.log(this, "mcp", "tool=$name")
        val text = callTool(name, args)
        return JSONObject().put(
            "content",
            JSONArray().put(JSONObject().put("type", "text").put("text", text)),
        )
    }

    private fun callTool(name: String, args: JSONObject): String = when (name) {
        "hc_status" -> statusJson()
        "hc_dump" -> dumpJson()
        "hc_sync_now" -> {
            SyncWorker.runNow(this, force = true)
            """{"ok":true,"queued":"sync"}"""
        }
        "hc_backfill" -> {
            val days = args.optInt("days", 365).coerceIn(1, 1825)
            SyncWorker.runBackfill(this, days)
            """{"ok":true,"queued":"backfill","days":$days}"""
        }
        "get_connection_status" -> statusJson()
        "get_profile" -> profileJson()
        "get_daily_summary" -> {
            val client = HealthConnectClient.getOrCreate(this)
            runBlocking { HcQuery.dailySummary(client, args.optString("date").ifBlank { null }) }.toString(2)
        }
        "query_metric_series" -> {
            val client = HealthConnectClient.getOrCreate(this)
            val metric = args.optString("metric", "steps")
            val g = args.optString("granularity", "day")
            runBlocking {
                HcQuery.metricSeries(
                    client, metric,
                    args.optString("start_date").ifBlank { null },
                    args.optString("end_date").ifBlank { null },
                    g
                )
            }.toString(2)
        }
        "query_heart_rate" -> {
            val client = HealthConnectClient.getOrCreate(this)
            runBlocking {
                HcQuery.heartRate(
                    client,
                    args.optString("start_date").ifBlank { null },
                    args.optString("end_date").ifBlank { null },
                    args.optInt("limit", 500)
                )
            }.toString(2)
        }
        "query_sleep" -> {
            val client = HealthConnectClient.getOrCreate(this)
            runBlocking {
                HcQuery.sleep(
                    client,
                    args.optString("start_date").ifBlank { null },
                    args.optString("end_date").ifBlank { null },
                    args.optBoolean("include_naps", true),
                    args.optBoolean("dedupe", true)
                )
            }.toString(2)
        }
        "query_workouts" -> {
            val client = HealthConnectClient.getOrCreate(this)
            runBlocking {
                HcQuery.workouts(
                    client,
                    args.optString("start_date").ifBlank { null },
                    args.optString("end_date").ifBlank { null },
                    args.optInt("min_duration", 0)
                )
            }.toString(2)
        }
        "hc_sleep_extras" -> {
            val days = args.optInt("days", 365).coerceIn(1, 1825)
            SyncWorker.runSleepExtrasImport(this, days)
            """{"ok":true,"queued":"sleep-extras","days":$days}"""
        }
        "hc_sleep_shift" -> {
            val client = HealthConnectClient.getOrCreate(this)
            val zone = java.time.ZoneId.systemDefault()
            val start = args.optString("start_date").ifBlank { null }
            val end = args.optString("end_date").ifBlank { null }
            val shift = args.optInt("seconds", 1).toLong()
            val today = java.time.LocalDate.now(zone)
            fun day(s: String?): java.time.LocalDate =
                if (s.isNullOrBlank()) today else java.time.LocalDate.parse(s)
            val from = day(start).minusDays(1).atStartOfDay(zone).toEpochSecond()
            val to = day(end).plusDays(1).atStartOfDay(zone).toEpochSecond()
            val (ins, del) = runBlocking {
                HcWriter.shiftOwnSleep(client, from, to, shift, packageName, zone)
            }
            JSONObject().put("ok", true).put("inserted", ins).put("deleted", del)
                .put("shift_seconds", shift).toString(2)
        }
        "hc_pull_from_server" -> {
            val zone = java.time.ZoneId.systemDefault()
            val today = java.time.LocalDate.now(zone)
            fun day(s: String?): java.time.LocalDate =
                if (s.isNullOrBlank()) today else java.time.LocalDate.parse(s)
            val from = day(args.optString("start_date")).atStartOfDay(zone).toEpochSecond()
            val to = day(args.optString("end_date")).plusDays(1).atStartOfDay(zone).toEpochSecond()
            val (n, note) = runBlocking { SecondarySync.pullWindow(this@McpService, from, to) }
            JSONObject().put("ok", true).put("written", n).put("notes", note).toString(2)
        }
        "hc_sleep_prune" -> {
            val client = HealthConnectClient.getOrCreate(this)
            val zone = java.time.ZoneId.systemDefault()
            val today = java.time.LocalDate.now(zone)
            fun day(s: String?): java.time.LocalDate =
                if (s.isNullOrBlank()) today else java.time.LocalDate.parse(s)
            val from = day(args.optString("start_date")).minusDays(1).atStartOfDay(zone).toEpochSecond()
            val to = day(args.optString("end_date")).plusDays(1).atStartOfDay(zone).toEpochSecond()
            val n = runBlocking { HcWriter.pruneOwnSleepDuplicates(client, from, to, packageName) }
            JSONObject().put("ok", true).put("deleted", n).toString(2)
        }
        "hc_sleep_force_import" -> {
            val creds = Prefs.creds(this) ?: throw RuntimeException("未配置小米凭据")
            val zone = java.time.ZoneId.systemDefault()
            val today = java.time.LocalDate.now(zone)
            fun day(s: String?): java.time.LocalDate =
                if (s.isNullOrBlank()) today else java.time.LocalDate.parse(s)
            val from = day(args.optString("start_date")).minusDays(1).atStartOfDay(zone).toEpochSecond()
            val to = day(args.optString("end_date")).plusDays(1).atStartOfDay(zone).toEpochSecond()
            val client = HealthConnectClient.getOrCreate(this)
            val (raw, inserted) = runBlocking {
                val s = CloudApi.login(creds.first, creds.second)
                val rows = CloudApi.fetchValues(s, "sleep", from, to)
                val sessions = rows.mapNotNull { (_, v) ->
                    val bed = v.optLong("bedtime", 0)
                    val wake = v.optLong("device_wake_up_time", 0).takeIf { it > 0 }
                        ?: (bed + v.optLong("sleep_duration", 0) * 60)
                    if (bed > 0 && wake > bed) {
                        id.harness.hcbridge.logic.SleepMerge.Session(
                            bed, wake, title = "小米睡眠",
                            stages = id.harness.hcbridge.logic.SleepStages.fromXiaomiItems(
                                v.optJSONArray("items"), bed, wake
                            )
                        )
                    } else null
                }
                rows.size to HcWriter.insertSleepDirect(client, sessions, zone)
            }
            JSONObject().put("ok", true).put("cloud_rows", raw).put("inserted", inserted).toString(2)
        }
        "hc_sleep_raw" -> {            val client = HealthConnectClient.getOrCreate(this)
            runBlocking {
                HcQuery.sleepRaw(
                    client,
                    args.optString("start_date").ifBlank { null },
                    args.optString("end_date").ifBlank { null }
                )
            }.toString(2)
        }
        "query_sleep_stages" -> {
            val client = HealthConnectClient.getOrCreate(this)
            runBlocking {
                HcQuery.sleepStages(
                    client,
                    args.optString("start_date").ifBlank { null },
                    args.optString("end_date").ifBlank { null }
                )
            }.toString(2)
        }
        "query_nightly_recovery" -> {
            val client = HealthConnectClient.getOrCreate(this)
            runBlocking { HcQuery.nightlyRecovery(client, args.optInt("days", 7).coerceIn(1, 365)) }.toString(2)
        }
        "query_body_composition" -> {
            val client = HealthConnectClient.getOrCreate(this)
            val hM = Prefs.heightM(this)
            runBlocking {
                HcQuery.bodyComposition(
                    client,
                    args.optString("start_date").ifBlank { null },
                    args.optString("end_date").ifBlank { null },
                    args.optString("source").ifBlank { null },
                    hM
                )
            }.toString(2)
        }
        "query_body_measurements" -> {
            val client = HealthConnectClient.getOrCreate(this)
            runBlocking {
                HcQuery.bodyMeasurements(
                    client,
                    args.optString("start_date").ifBlank { null },
                    args.optString("end_date").ifBlank { null }
                )
            }.toString(2)
        }
        "query_spo2" -> {
            val client = HealthConnectClient.getOrCreate(this)
            runBlocking {
                HcQuery.spo2(
                    client,
                    args.optString("start_date").ifBlank { null },
                    args.optString("end_date").ifBlank { null },
                    args.optInt("limit", 200)
                )
            }.toString(2)
        }
        "hc_steps_breakdown" -> {
            val client = HealthConnectClient.getOrCreate(this)
            runBlocking {
                val d = args.optString("date").ifBlank { null }
                val day = java.time.LocalDate.parse(d ?: java.time.LocalDate.now().toString())
                val from = day.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()
                val to = day.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()
                val recs = HcQuery.readAll<androidx.health.connect.client.records.StepsRecord>(
                    client, from, to, cap = 20000
                )
                HcQuery.stepsByOrigin(client, d, recs)
            }.toString(2)
        }
        "get_data_coverage" -> {
            val client = HealthConnectClient.getOrCreate(this)
            runBlocking { HcQuery.coverage(client) }.toString(2)
        }
        "sync_data" -> {
            val days = args.optInt("days", 0)
            if (days > 0) SyncWorker.runBackfill(this, days) else SyncWorker.runNow(this)
            """{"ok":true,"queued":"${if (days > 0) "backfill $days" else "sync"}"}"""
        }
        "get_sync_history" -> SyncHistory.asText(this)
        "hc_export_csv" -> {
            val client = HealthConnectClient.getOrCreate(this)
            val typesArr = args.optJSONArray("types")
            val types = if (typesArr == null) listOf("heart_rate", "steps", "sleep")
            else (0 until typesArr.length()).map { typesArr.optString(it) }.filter { it.isNotBlank() }
            val (suffix, csv, counts) = runBlocking {
                HcQuery.exportCsv(
                    client, types,
                    args.optString("start_date").ifBlank { null },
                    args.optString("end_date").ifBlank { null }
                )
            }
            val name = "export-" + types.joinToString("-") + "-" + suffix + ".csv"
            val ok = DumpWorker.write(this, name, csv, "text/csv")
            if (!ok) throw RuntimeException("写文件失败")
            JSONObject()
                .put("ok", true)
                .put("path", "/sdcard/Download/hc-bridge/$name")
                .put("bytes", csv.length)
                .put("rows", JSONObject(counts as Map<*, *>))
                .toString(2)
        }
        "export_logs" -> {
            val name = DumpWorker.exportLogs(this)
            if (name != null) """{"ok":true,"path":"/sdcard/Download/hc-bridge/$name"}"""
            else """{"ok":false,"error":"写文件失败"}"""
        }
        "hc_logs" -> {
            val n = args.optInt("lines", 80).coerceIn(1, 300)
            AppLog.tail(this, n).joinToString("\n")
        }
        "hc_set_interval" -> {
            val min = args.optInt("minutes", 15).coerceIn(15, 120)
            Prefs.setInterval(this, min)
            SyncWorker.schedule(this)
            """{"ok":true,"interval_minutes":$min}"""
        }
        "hc_weight_import" -> {
            val days = args.optInt("days", 1825).coerceIn(1, 1825)
            SyncWorker.runWeightImport(this, days)
            """{"ok":true,"queued":"weight-import","days":$days}"""
        }
        "hc_activity_cleanup" -> {
            val days = args.optInt("days", 365).coerceIn(1, 1825)
            SyncWorker.runActivityCleanup(this, days)
            """{"ok":true,"queued":"activity-cleanup","days":$days}"""
        }
        "hc_sleep_gap_check" -> {
            val days = args.optInt("days", 30).coerceIn(1, 90)
            SyncWorker.runSleepGapCheck(this, days)
            """{"ok":true,"queued":"sleep-gap-check","days":$days}"""
        }
        "hc_sleep_cleanup" -> {
            val days = args.optInt("days", 365).coerceIn(1, 1095)
            SyncWorker.runSleepCleanup(this, days)
            """{"ok":true,"queued":"sleep-cleanup","days":$days}"""
        }
        "hc_upstream" -> {
            val raw = Prefs.upstreamJson(this)
            val out = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
            out.put("kicks_today", Prefs.kicksToday(this))
            out.put("last_kick_ts", Prefs.lastKickTs(this))
            out.put("last_kick_note", Prefs.lastKickNote(this))
            out.put("mi_installed", UpstreamKick.miLaunchIntent(this) != null)
            out.toString(2)
        }
        "hc_kick" -> {
            SyncWorker.runKick(this)
            """{"ok":true,"queued":"upstream-kick"}"""
        }
        else -> throw IllegalArgumentException("unknown tool $name")
    }

    private fun statusJson(): String {
        val out = JSONObject()
        out.put("hc_sdk_available", HealthConnectClient.getSdkStatus(this) == HealthConnectClient.SDK_AVAILABLE)
        out.put("has_credentials", Prefs.hasCreds(this))
        out.put("last_written_ts", Prefs.lastTs(this))
        out.put("interval_minutes", Prefs.interval(this))
        out.put("fails", Prefs.fails(this))
        out.put("last_status", Prefs.status(this))
        out.put(
            "upstream",
            runCatching { JSONObject(Prefs.upstreamJson(this)) }.getOrNull() ?: JSONObject()
        )
        val client = runCatching { HealthConnectClient.getOrCreate(this) }.getOrNull()
        val granted = client?.let { runCatching { runBlocking { it.permissionController.getGrantedPermissions() } }.getOrNull() }
            ?: emptySet()
        out.put("permissions_granted", JSONArray(granted.toList()))
        return out.toString(2)
    }

    private fun profileJson(): String {
        val out = JSONObject()
        out.put("platform", "Android/" + android.os.Build.VERSION.RELEASE)
        out.put("device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
        out.put("xiaomi_user_id", Prefs.creds(this)?.first ?: "")
        out.put("hc_available", HealthConnectClient.getSdkStatus(this) == HealthConnectClient.SDK_AVAILABLE)
        val client = runCatching { HealthConnectClient.getOrCreate(this) }.getOrNull()
        if (client != null) {
            runCatching {
                val res = runBlocking {
                    HcQuery.metricSeries(client, "steps", null, null, "day")
                }
                out.put("recent_steps_series_len", res.optJSONArray("series")?.length() ?: 0)
            }
        }
        return out.toString(2)
    }

    private fun dumpJson(): String {
        val client = HealthConnectClient.getOrCreate(this)
        val dump = runBlocking { HcReader.dump(client) }
        return dump.toString(2)
    }
}
