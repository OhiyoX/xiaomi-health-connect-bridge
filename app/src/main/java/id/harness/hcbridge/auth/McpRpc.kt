package id.harness.hcbridge.auth

import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

/**
 * `/mcp` 的 JSON-RPC 2.0 处理（计划 Task 5/7 共用的纯 JVM 部分）。
 *
 * 放在 `auth/` 而不是 `McpService` 里，是为了让"工具过滤 / 工具判定"能在 JVM 单测里直测，
 * 不需要模拟器：`McpService` 只负责 socket 拆包、把 [Route.Mcp] 交进来、把 [HttpResp] 写回去。
 *
 * 支持的方法（MCP 2025-06-18 的最小面）：
 * - `initialize` → 协议版本（客户端给的支持就回它，否则回 [PROTOCOL_VERSION]）、`tools` 能力、serverInfo；
 * - `ping` → `{}`；
 * - `tools/list` → [ToolGate.visible] 过滤后的工具（**只给名字**，描述由拆包层用 `McpTools` 注入）；
 * - `tools/call` → 先过 [ToolGate.check]，拒绝回 `-32001`（不带参数值），放行才调执行回调。
 *
 * 通知（没有 `id`）一律 `202 Accepted` + 空体；解析失败 `400` + `-32700`；
 * 批量数组不支持（MCP 已移除）⇒ `-32600`。
 *
 * 本文件**不得** import 任何 `android.*`。
 */
object McpRpc {

    const val PROTOCOL_VERSION = "2025-06-18"

    /** 认识的协议版本；客户端给别的就回 [PROTOCOL_VERSION]（MCP 的协商规则）。 */
    val SUPPORTED_PROTOCOL_VERSIONS: Set<String> = linkedSetOf("2025-06-18", "2025-03-26", "2024-11-05")

    private const val SERVER_NAME = "hc-bridge"

    /** JSON-RPC 错误码（MCP 约定：`-32001` = 工具不被允许，`-32000` = 执行失败）。 */
    const val ERR_PARSE = -32700
    const val ERR_INVALID_REQUEST = -32600
    const val ERR_METHOD_NOT_FOUND = -32601
    const val ERR_INVALID_PARAMS = -32602
    const val ERR_SERVER = -32000
    const val ERR_TOOL_NOT_PERMITTED = -32001

    /**
     * @param defs 工具定义注入（拆包层给 `McpTools` 的描述；缺省只有 `name` 与空 `inputSchema`）。
     * @param call 放行后的执行回调；返回值就是 JSON-RPC 的 `result`。抛异常 ⇒ `-32000`。
     * @param onDenied 被 [ToolGate] 挡下的调用（工具名 + 稳定原因串）：拆包层用它写审计。
     *   `tools/call` 的判定仍然只有这里一处，回调只观察、不参与放行。
     */
    fun handle(
        principal: Principal,
        body: String,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        defs: (String) -> JSONObject? = { defaultToolDef(it) },
        call: (String, JSONObject) -> JSONObject = { name, _ ->
            throw UnsupportedOperationException("tool execution is not wired: $name")
        },
        onDenied: (String, String) -> Unit = { _, _ -> },
    ): HttpResp {
        val text = body.trim()
        if (text.isEmpty()) return error(400, null, ERR_INVALID_REQUEST, "empty request body")

        val parsed = try {
            JsonCanon.parse(text)
        } catch (e: Exception) {
            return error(400, null, ERR_PARSE, "parse error")
        }
        if (parsed !is JSONObject) {
            return error(400, null, ERR_INVALID_REQUEST, "batch requests are not supported")
        }

        val id = if (parsed.has("id")) parsed.opt("id") else null

        // 先验信封，再决定“是通知还是请求”：带着坏信封的通知不能拿到 202（那是“已收下”的意思）。
        val version = parsed.optString("jsonrpc", "")
        if (version != "2.0") return error(400, id, ERR_INVALID_REQUEST, "jsonrpc must be 2.0")
        val method = parsed.optString("method", "").trim()
        if (method.isEmpty()) return error(400, id, ERR_INVALID_REQUEST, "method is required")

        // 通知（没有 id）：不产生响应体，HTTP 202 是 MCP 的约定回法。
        if (!parsed.has("id")) return HttpResp(202, mapOf("Cache-Control" to "no-store"), "")

        val params = parsed.optJSONObject("params") ?: JSONObject()

        return when (method) {
            "initialize" -> ok(id, initializeResult(params))
            "ping" -> ok(id, JSONObject())
            "tools/list" -> ok(id, JSONObject().put("tools", toolList(principal, defs)))
            "tools/call" -> toolCall(principal, id, params, now, zone, call, onDenied)
            else -> error(200, id, ERR_METHOD_NOT_FOUND, "method not found: $method")
        }
    }

    // ------------------------------------------------------------------ 方法

    private fun initializeResult(params: JSONObject): JSONObject {
        val asked = params.optString("protocolVersion", "").trim()
        val version = if (asked in SUPPORTED_PROTOCOL_VERSIONS) asked else PROTOCOL_VERSION
        return JSONObject()
            .put("protocolVersion", version)
            .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
            .put("serverInfo", JSONObject().put("name", SERVER_NAME).put("version", "1"))
    }

    private fun toolList(principal: Principal, defs: (String) -> JSONObject?): JSONArray {
        val visible = ToolGate.visible(ToolCatalog.ALL_TOOLS, principal.allowedTools)
        val arr = JSONArray()
        for (name in visible) arr.put(defs(name) ?: defaultToolDef(name))
        return arr
    }

    private fun toolCall(
        principal: Principal,
        id: Any?,
        params: JSONObject,
        now: Long,
        zone: ZoneId,
        call: (String, JSONObject) -> JSONObject,
        onDenied: (String, String) -> Unit,
    ): HttpResp {
        val name = params.optString("name", "").trim()
        if (name.isEmpty()) return error(200, id, ERR_INVALID_PARAMS, "params.name is required")
        val args = params.optJSONObject("arguments") ?: JSONObject()

        val reason = ToolGate.check(principal.scopes, principal.policies, principal.windows, name, args, now, zone)
        if (reason != null) {
            // 只回工具名与稳定原因串；**不回**参数值（可能是健康数据）。
            onDenied(name, reason)
            return error(200, id, ERR_TOOL_NOT_PERMITTED, "tool not permitted: $name ($reason)")
        }
        return try {
            ok(id, call(name, args))
        } catch (e: Exception) {
            error(200, id, ERR_SERVER, "tool failed: ${e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------ 响应

    private fun ok(id: Any?, result: JSONObject): HttpResp = HttpResp.json(
        200,
        JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL).put("result", result),
    )

    private fun error(status: Int, id: Any?, code: Int, message: String): HttpResp = HttpResp.json(
        status,
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message)),
    )

    private fun defaultToolDef(name: String): JSONObject = JSONObject()
        .put("name", name)
        .put("description", "")
        .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()))
}
