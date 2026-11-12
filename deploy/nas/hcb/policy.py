"""四层工具权限、时间窗与数据分区。

设计：``docs/superpowers/specs/2026-11-02-oauth-admin-design.md`` §8.2、§8.3、§8.5。

四层上限取交集，任何一层拒绝就拒绝：

1. 用户 ``users.tool_policy_json`` / ``users.window_json``
2. OAuth 客户端 ``oauth_clients.*``
3. OAuth 令牌 ``oauth_tokens.*``
4. 静态 key ``agent_tokens.*``

``NULL``（Python ``None`` / SQL NULL）表示该层不额外限制；``[]`` 表示该层
不允许任何工具；损坏的 JSON 或规则形状一律拒绝（fail-closed）。时间窗同理，
``None`` 表示不限时，损坏的窗口拒绝。

本模块只做纯判断，不打开数据库。OAuth principal 由 ``hcb.oauth.verify_access``
提供已解析的三层；静态 key 的 owner 用户层由 ``server.resolve_principal`` 一并
放进 principal。数据分区只暴露 ``owner_for(principal)``，由调用方用
``hcb.context.set_owner`` / ``hcb.db.db(owner)`` 打开对应库。
"""
from __future__ import annotations

import enum
import json
import time
from datetime import datetime, timezone
from typing import Any, Iterable, Mapping
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from . import oauth

# 只读/写/tool 分类以 hcb.oauth 的显式清单为准；新工具默认算写工具。
READ_TOOLS = tuple(oauth.READ_TOOLS)
WRITE_TOOLS = tuple(oauth.WRITE_TOOLS)
INGEST_TOOLS = ("ingest",)

TOOL_SCOPES: dict[str, str | None] = {
    **{name: "health.read" for name in READ_TOOLS},
    **{name: "health.write" for name in WRITE_TOOLS},
    "ingest": None,
}
tool_scopes = TOOL_SCOPES  # 兼容 brief 中的小写别名

class Reason(str, enum.Enum):
    """审计可记录的拒绝原因枚举。"""

    ALLOW = "allow"
    UNKNOWN_TOOL = "unknown_tool"
    SCOPE_DENIED = "scope_denied"
    OAUTH_INGEST = "oauth_ingest"
    TOOL_DENIED = "tool_denied"
    ARGS_DENIED = "args_denied"
    POLICY_MALFORMED = "policy_malformed"
    WINDOW_CLOSED = "window_closed"
    WINDOW_MALFORMED = "window_malformed"
    PRINCIPAL_INVALID = "principal_invalid"

    def __str__(self) -> str:  # 便于日志与 JSON 直接使用
        return self.value


_MESSAGES = {
    Reason.ALLOW: "allowed",
    Reason.UNKNOWN_TOOL: "unknown tool",
    Reason.SCOPE_DENIED: "insufficient scope",
    Reason.OAUTH_INGEST: "ingest requires a static Bearer token",
    Reason.TOOL_DENIED: "tool not permitted by policy",
    Reason.ARGS_DENIED: "tool arguments not permitted by policy",
    Reason.POLICY_MALFORMED: "tool policy is malformed",
    Reason.WINDOW_MALFORMED: "time window is malformed",
    Reason.WINDOW_CLOSED: "outside the allowed time window",
    Reason.PRINCIPAL_INVALID: "invalid principal",
}


def reason_message(reason: Reason | str, detail: str | None = None) -> str:
    """给 JSON-RPC/页面用的稳定短消息；保留枚举值便于审计过滤。"""
    value = reason.value if isinstance(reason, Reason) else str(reason)
    try:
        parsed = Reason(value)
    except ValueError:
        parsed = Reason.PRINCIPAL_INVALID
    message = f"insufficient scope: {parsed.value}: {_MESSAGES[parsed]}"
    return f"{message} ({detail})" if detail else message


# ------------------------------------------------------------------ 工具分类
_SERVER_TOOL_NAMES: frozenset[str] | None = None


def _server_tool_names() -> frozenset[str]:
    """从 ``server.tool_list()`` 取当前工具名并缓存；导入失败时回退到静态清单。"""
    global _SERVER_TOOL_NAMES
    if _SERVER_TOOL_NAMES is None:
        try:
            import server  # 延迟导入，避免 server <-> policy 循环

            _SERVER_TOOL_NAMES = frozenset(
                tool["name"] for tool in server.tool_list())
        except Exception:  # noqa: BLE001 - 分类失败不应让进程启动失败
            return frozenset(TOOL_SCOPES)
    return _SERVER_TOOL_NAMES


def tool_kind(name: object) -> str:
    """``read`` / ``write`` / ``ingest``；未知工具返回 ``unknown``。"""
    if not isinstance(name, str) or not name:
        return "unknown"
    if name in INGEST_TOOLS:
        return "ingest"
    if name in READ_TOOLS:
        return "read"
    if name in WRITE_TOOLS:
        return "write"
    if name in _server_tool_names():
        return "write"  # 新工具默认按写权限处理
    return "unknown"


def read_tools() -> set[str]:
    return set(READ_TOOLS)


def write_tools() -> set[str]:
    return set(WRITE_TOOLS)


def ingest_tools() -> set[str]:
    return set(INGEST_TOOLS)


def _known_tool(name: str) -> bool:
    return name in _server_tool_names() or name in TOOL_SCOPES


# ------------------------------------------------------------------ 解析
def parse_policy(value: object) -> tuple[list[Any] | None, bool]:
    """JSON 列/已解析值 → ``(policy, corrupt)``。

    ``None`` = 不限制；``[]`` = 不允许任何工具；其它形状损坏。
    """
    if value is None:
        return None, False
    parsed = value
    if isinstance(value, str):
        try:
            parsed = json.loads(value)
        except (json.JSONDecodeError, TypeError):
            return None, True
    if not isinstance(parsed, list):
        return None, True
    for item in parsed:
        if isinstance(item, str):
            if not item:
                return None, True
            continue
        if not isinstance(item, dict):
            return None, True
        tool = item.get("tool")
        if not isinstance(tool, str) or not tool:
            return None, True
        if "args" in item and item["args"] is not None:
            if not isinstance(item["args"], dict):
                return None, True
            for key, expected in item["args"].items():
                if not isinstance(key, str) or not key:
                    return None, True
                if not isinstance(expected, (str, int, float, bool, list)):
                    return None, True
                if isinstance(expected, list):
                    if not all(isinstance(x, (str, int, float, bool)) for x in expected):
                        return None, True
    return parsed, False


def parse_window(value: object) -> tuple[dict[str, Any] | None, bool]:
    """JSON 列/已解析值 → ``(window, corrupt)``；``None`` = 不限时。"""
    if value is None:
        return None, False
    parsed = value
    if isinstance(value, str):
        try:
            parsed = json.loads(value)
        except (json.JSONDecodeError, TypeError):
            return None, True
    if not isinstance(parsed, dict):
        return None, True
    return parsed, False


def _timezone(name: object) -> ZoneInfo | None:
    # tz 必填：没有时区的窗口是歧义时间，fail-closed。
    if not isinstance(name, str) or not name:
        return None
    try:
        return ZoneInfo(name)
    except (ZoneInfoNotFoundError, ValueError):
        return None


def _minutes(value: object) -> int | None:
    if not isinstance(value, str):
        return None
    parts = value.split(":")
    if len(parts) != 2 or len(parts[0]) != 2 or len(parts[1]) != 2:
        return None
    if not (parts[0].isdigit() and parts[1].isdigit()):
        return None
    hour, minute = int(parts[0]), int(parts[1])
    if not (0 <= hour <= 23 and 0 <= minute <= 59):
        return None
    return hour * 60 + minute


def _timestamp(value: object) -> int | None:
    if value is None:
        return int(time.time())
    if isinstance(value, bool):
        return None
    if isinstance(value, datetime):
        if value.tzinfo is None:
            value = value.replace(tzinfo=timezone.utc)
        return int(value.timestamp())
    if isinstance(value, (int, float)):
        return int(value)
    return None


def _datetime_bound(value: object, tz: ZoneInfo) -> int | None:
    if value is None:
        return None
    if not isinstance(value, str) or not value.strip():
        return None
    text = value.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=tz)
    return int(parsed.timestamp())


def _window(value: object) -> tuple[dict[str, Any] | None, bool]:
    """校验并规范化窗口；返回 ``(normalized, valid)``。"""
    parsed, corrupt = parse_window(value)
    if corrupt:
        return None, False
    if parsed is None:
        return None, True
    tz = _timezone(parsed.get("tz"))
    if tz is None:
        return None, False

    days_raw = parsed.get("days")
    days: set[int] | None = None
    if days_raw is not None:
        if not isinstance(days_raw, list) or not days_raw:
            return None, False
        days = set()
        for day in days_raw:
            if isinstance(day, bool) or not isinstance(day, int) or not 1 <= day <= 7:
                return None, False
            days.add(day)

    start_raw, end_raw = parsed.get("start"), parsed.get("end")
    start = end = None
    if start_raw is not None or end_raw is not None:
        start, end = _minutes(start_raw), _minutes(end_raw)
        if start is None or end is None:
            return None, False
        # 只有 00:00–00:00 可表示“全天”；其它相同起止时间是歧义窗口，拒绝。
        if start == end and start != 0:
            return None, False

    from_raw, until_raw = parsed.get("from"), parsed.get("until")
    from_ts = _datetime_bound(from_raw, tz)
    until_ts = _datetime_bound(until_raw, tz)
    if from_raw is not None and from_ts is None:
        return None, False
    if until_raw is not None and until_ts is None:
        return None, False

    return {
        "tz": tz,
        "days": days,
        "start": start,
        "end": end,
        "from": from_ts,
        "until": until_ts,
    }, True


def _in_window(window: Mapping[str, Any], now: int) -> bool:
    if window.get("from") is not None and now < window["from"]:
        return False
    if window.get("until") is not None and now > window["until"]:
        return False

    local = datetime.fromtimestamp(now, tz=window["tz"])
    minutes = local.hour * 60 + local.minute
    start, end = window.get("start"), window.get("end")
    days = window.get("days")

    if start is None or end is None or start == end:
        day = local.isoweekday()
        return days is None or day in days

    if start < end:
        if not start <= minutes < end:
            return False
        day = local.isoweekday()
    else:  # 跨午夜：end 之前属于前一天开始的窗口
        if start <= minutes:
            day = local.isoweekday()
        elif minutes < end:
            day = 7 if local.isoweekday() == 1 else local.isoweekday() - 1
        else:
            return False
    return days is None or day in days


def window_description(window: object) -> str | None:
    """窗口的人读摘要（起止、星期、绝对范围、时区）；损坏时返回 ``None``。"""
    normalized, valid = _window(window)
    if not valid or normalized is None:
        return None
    parts: list[str] = []
    start, end = normalized.get("start"), normalized.get("end")
    if start is not None and end is not None:
        parts.append(f"{start // 60:02d}:{start % 60:02d}-"
                     f"{end // 60:02d}:{end % 60:02d}")
    days = normalized.get("days")
    if days:
        parts.append("days " + ",".join(str(day) for day in sorted(days)))
    from_ts, until_ts = normalized.get("from"), normalized.get("until")
    if from_ts is not None:
        parts.append("from " + datetime.fromtimestamp(
            from_ts, tz=normalized["tz"]).isoformat())
    if until_ts is not None:
        parts.append("until " + datetime.fromtimestamp(
            until_ts, tz=normalized["tz"]).isoformat())
    parts.append(str(normalized["tz"]))
    return " ".join(parts)


def window_denial_detail(principal: object) -> str | None:
    """拒绝消息里可带的第一层窗口摘要；用于页面/审计排查。"""
    if not isinstance(principal, Mapping):
        return None
    layers, corrupt = _window_layers(principal)
    if corrupt:
        return None
    for value in layers:
        if value is None:
            continue
        description = window_description(value)
        if description:
            return description
    return None


def window_allow(window: object, now: object = None) -> bool:
    """单个窗口是否允许；``None`` 允许，损坏/窗外拒绝。"""
    ts = _timestamp(now)
    if ts is None:
        return False
    normalized, valid = _window(window)
    if not valid:
        return False
    if normalized is None:
        return True
    return _in_window(normalized, ts)


def window_allow_validated(window: object, now: object = None) -> bool:
    """``window_allow`` 的显式别名，便于调用方表达“已校验”。"""
    return window_allow(window, now)


def windows_allow(windows: object, now: object = None) -> bool:
    """多层窗口取交集；``None``/空列表允许，任何一层损坏或窗外拒绝。"""
    ts = _timestamp(now)
    if ts is None:
        return False
    if windows is None:
        return True
    if not isinstance(windows, (list, tuple)):
        return False
    for value in windows:
        if not window_allow(value, ts):
            return False
    return True


# ------------------------------------------------------------------ principal 层
def _layer_value(principal: Mapping[str, Any], *keys: str) -> object:
    for key in keys:
        if key in principal:
            return principal.get(key)
    return None


def _is_admin(principal: Mapping[str, Any]) -> bool:
    """时间窗豁免只给人类管理员，不给 ownerless 静态 key。"""
    if principal.get("kind") == "static":
        return False
    if "user_id" not in principal:
        return False
    user_id = principal.get("user_id")
    if not (isinstance(user_id, int) and not isinstance(user_id, bool) and user_id == 0):
        return False
    return principal.get("is_admin") is True or principal.get("role") == "admin"


def _oauth_policies(principal: Mapping[str, Any]) -> tuple[list[object], bool]:
    # verify_access 同时给出逐层字段和 tool_policies 列表；两者都评估（取交集）
    # 才能在旧列表与逐层字段不一致时仍然 fail-closed。
    layers: list[object] = [
        _layer_value(principal, "user_tool_policy", "user_tool_policy_json"),
        _layer_value(principal, "client_tool_policy", "client_tool_policy_json"),
        _layer_value(principal, "tool_policy", "tool_policy_json"),
    ]
    raw = principal.get("tool_policies") if "tool_policies" in principal else None
    if raw is not None:
        if not isinstance(raw, (list, tuple)):
            return layers, True
        layers.extend(raw)
    return layers, False


def _static_policies(principal: Mapping[str, Any]) -> tuple[list[object], bool]:
    layers: list[object] = [
        _layer_value(principal, "user_tool_policy", "user_tool_policy_json")]
    # 兼容已解析的 capabilities 与原始 JSON 列；两者都存在时都评估（取交集），
    # 这样任何一列损坏都不会被另一列“绕过”。
    if "capabilities" in principal:
        layers.append(principal.get("capabilities"))
    if "capabilities_json" in principal:
        layers.append(principal.get("capabilities_json"))
    layers.append(_layer_value(principal, "tool_policy_json", "tool_policy"))
    return layers, False


def _tool_policy_layers(principal: Mapping[str, Any]) -> tuple[list[object], bool]:
    kind = principal.get("kind")
    if kind == "oauth":
        return _oauth_policies(principal)
    if kind == "static":
        return _static_policies(principal)
    return [], True


def _window_layers(principal: Mapping[str, Any]) -> tuple[list[object], bool]:
    kind = principal.get("kind")
    if kind == "oauth":
        layers: list[object] = [
            _layer_value(principal, "user_window", "user_window_json"),
            _layer_value(principal, "client_window", "client_window_json"),
            _layer_value(principal, "window", "window_json"),
        ]
        raw = principal.get("windows") if "windows" in principal else None
        if raw is not None:
            if not isinstance(raw, (list, tuple)):
                return layers, True
            layers.extend(raw)
        return layers, False
    if kind == "static":
        return [
            _layer_value(principal, "user_window", "user_window_json"),
            _layer_value(principal, "window_json", "window"),
        ], False
    return [], True


def _same_value(actual: object, expected: object) -> bool:
    if isinstance(actual, bool) != isinstance(expected, bool):
        return False
    if isinstance(actual, (int, float)) and isinstance(expected, (int, float)):
        return actual == expected
    return type(actual) is type(expected) and actual == expected


def _arg_matches(actual: object, expected: object) -> bool:
    if isinstance(expected, list):
        return any(_arg_matches(actual, item) for item in expected)
    return _same_value(actual, expected)


def _policy_reason(value: object, tool: str, args: Mapping[str, Any] | None
                   ) -> Reason | None:
    parsed, corrupt = parse_policy(value)
    if corrupt:
        return Reason.POLICY_MALFORMED
    if parsed is None:
        return None
    if not parsed:
        return Reason.TOOL_DENIED
    matched = False
    args_failed = False
    for item in parsed:
        if isinstance(item, str):
            if item == tool:
                matched = True
            continue
        if item["tool"] != tool:
            continue
        constraints = item.get("args") or {}
        actual = args if args is not None else {}
        if all(key in actual and _arg_matches(actual[key], expected)
               for key, expected in constraints.items()):
            matched = True
        elif constraints:
            args_failed = True
    if matched:
        return None
    return Reason.ARGS_DENIED if args_failed else Reason.TOOL_DENIED


def _scope_reason(principal: Mapping[str, Any], tool: str) -> Reason | None:
    if principal.get("kind") != "oauth":
        return None
    scopes = principal.get("scopes")
    if isinstance(scopes, str):
        scopes = scopes.split()
    if not isinstance(scopes, (list, tuple, set, frozenset)):
        return Reason.SCOPE_DENIED
    has_read = "health.read" in scopes
    has_write = "health.write" in scopes
    if has_read and has_write:
        return None
    kind = tool_kind(tool)
    if has_read and kind == "read":
        return None
    if has_write and kind == "write":
        return None
    return Reason.SCOPE_DENIED


def _window_reason(principal: Mapping[str, Any], now: int) -> Reason | None:
    layers, corrupt = _window_layers(principal)
    if corrupt:
        return Reason.WINDOW_MALFORMED
    for value in layers:
        normalized, valid = _window(value)
        if not valid:
            return Reason.WINDOW_MALFORMED
        if normalized is not None and not _in_window(normalized, now):
            return Reason.WINDOW_CLOSED
    return None


def _authorize(principal: Mapping[str, Any] | None, tool: str,
               now: int, args: Mapping[str, Any] | None,
               *, check_windows: bool, list_mode: bool = False) -> tuple[bool, Reason]:
    if not isinstance(tool, str) or not tool or not _known_tool(tool):
        return False, Reason.UNKNOWN_TOOL
    if principal is None or not isinstance(principal, Mapping):
        return False, Reason.PRINCIPAL_INVALID
    kind = principal.get("kind")
    # 只接受真实身份类型；open mode 的显式 anonymous 是唯一的历史例外。
    if kind not in ("oauth", "static", "anonymous"):
        return False, Reason.PRINCIPAL_INVALID

    if kind == "anonymous":
        # 仅用于“从未配置过任何认证”的旧版开放模式；/ingest 在 HTTP 层拒绝它。
        return True, Reason.ALLOW

    # OAuth 永远不能使用手机/机器写入入口，即使 scope 层没有额外限制。
    if kind == "oauth" and tool in INGEST_TOOLS:
        return False, Reason.OAUTH_INGEST

    reason = _scope_reason(principal, tool)
    if reason is not None:
        return False, reason

    layers, corrupt = _tool_policy_layers(principal)
    if corrupt:
        return False, Reason.POLICY_MALFORMED
    for value in layers:
        reason = _policy_reason(value, tool, args)
        # 列表阶段不评估参数：参数规则未命中不算拒绝，但其它层的
        # TOOL_DENIED / POLICY_MALFORMED 仍然必须生效。
        if reason is Reason.ARGS_DENIED and list_mode:
            continue
        if reason is not None:
            return False, reason

    # admin 不受时间窗限制，但仍受上面 scope/policy 约束。
    if check_windows and not _is_admin(principal):
        reason = _window_reason(principal, now)
        if reason is not None:
            return False, reason

    return True, Reason.ALLOW


def authorize(principal: Mapping[str, Any] | None, tool_name: str,
              now: object = None, args: Mapping[str, Any] | None = None
              ) -> tuple[bool, Reason]:
    """判定一次 ``tools/call``。

    返回 ``(allowed, reason)``；``reason`` 是 ``Reason`` 枚举，供审计记录。
    四层 policy 取交集；OAuth 的 ``ingest`` 一律拒绝；admin 只豁免时间窗。
    """
    ts = _timestamp(now)
    if ts is None:
        return False, Reason.WINDOW_MALFORMED
    if args is not None and not isinstance(args, Mapping):
        return False, Reason.ARGS_DENIED
    return _authorize(principal, tool_name, ts, args, check_windows=True)


def policy_allow(principal: Mapping[str, Any] | None, tool: str,
                 args: Mapping[str, Any] | None = None,
                 now: object = None) -> tuple[bool, Reason]:
    """``authorize`` 的兼容签名（brief 的 ``policy_allow(principal, tool, args, now)``）。"""
    return authorize(principal, tool, now=now, args=args)


def allowed_tools(principal: Mapping[str, Any] | None,
                  tools: Iterable[object] | None = None) -> list[dict[str, Any]]:
    """``tools/list`` 过滤：按 scope + 四层 policy，不按时间窗。"""
    if tools is None:
        try:
            import server  # 延迟导入

            candidates = list(server.tool_list())
        except Exception:  # noqa: BLE001 - 调用方应能拿到空列表而不是 500
            candidates = []
    else:
        candidates = list(tools)
    out: list[dict[str, Any]] = []
    ts = int(time.time())
    for item in candidates:
        if isinstance(item, str):
            name = item
        elif isinstance(item, Mapping):
            name = item.get("name")
        else:
            continue
        if not isinstance(name, str):
            continue
        allowed, _reason = _authorize(
            principal, name, ts, None, check_windows=False, list_mode=True)
        if allowed:
            out.append(item if isinstance(item, Mapping) else {"name": name})
    return out


# ------------------------------------------------------------------ 分区
def owner_for(principal: Mapping[str, Any] | None) -> int:
    """principal 的数据分区：shared/admin → 0，own → 用户 id。

    非法值 fail-closed 到 0（admin/共享分区），绝不从坏 principal 猜一个用户分区。
    """
    if not isinstance(principal, Mapping):
        return 0
    owner = principal.get("owner")
    if isinstance(owner, int) and not isinstance(owner, bool) and owner >= 0:
        return owner
    user_id = principal.get("user_id")
    if (isinstance(user_id, int) and not isinstance(user_id, bool) and user_id > 0
            and principal.get("data_mode") == "own"):
        return user_id
    return 0


def set_owner(principal: Mapping[str, Any] | None):
    """把当前请求上下文切到 principal 的分区；返回 ``context.reset_owner`` 的 token。"""
    from . import context

    return context.set_owner(owner_for(principal))


__all__ = [
    "READ_TOOLS", "WRITE_TOOLS", "INGEST_TOOLS", "TOOL_SCOPES",
    "Reason", "reason_message", "tool_kind", "read_tools", "write_tools",
    "ingest_tools", "parse_policy", "parse_window", "window_description",
    "window_denial_detail", "window_allow",
    "window_allow_validated", "windows_allow", "authorize", "policy_allow",
    "allowed_tools", "owner_for", "set_owner",
]
