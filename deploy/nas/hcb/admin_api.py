"""共享管理 SPA 的 NAS 端：静态资源 + JSON API（`GET /admin/api/state`、`POST /admin/api/act`）。

契约：``admin-ui/API.md``；设计：``docs/superpowers/specs/2026-11-03-admin-ui-redesign.md`` §5。

- ``/admin`` 返回 ``hcb/admin_ui/index.html``；``/admin/assets/<file>`` 只放行构建产物
  里的 6 个文件（其余一律 404），带正确 MIME、``Cache-Control: no-store`` 与遍历防护。
- 状态是**只读**聚合：写动作全部走 :func:`hcb.auth` / :func:`hcb.oauth` / ``admin``
  里已有的函数，每个写动作写一条哈希链审计；审计里不放密码、令牌明文或 client_secret。
- 每个 API 请求独立校验管理员会话（401 JSON）、同源（403）与 ``X-CSRF-Token``（400）。
- GET 不写库；``hcb.stats`` 的 60 秒缓存让 1.2M 行的覆盖/指标查询不会随刷新重复全表扫描。
"""
from __future__ import annotations

import hmac
import json
import os
import re
import sqlite3
import threading
import time
from urllib.parse import unquote

from . import admin
from . import audit
from . import auth
from . import db as hdb
from . import oauth
from . import stats
from . import web

API_BODY_LIMIT = 64 * 1024
STATE_RATE = (240, 60)
"""状态接口每个 IP 每分钟上限（页面每次写动作后都会重拉一次）。"""
ACT_RATE = (120, 60)
"""写动作每个 IP 每分钟上限。"""
SECTIONS_CACHE_TTL = 60.0
AUDIT_ROW_LIMIT = 50
AUDIT_VERIFY_TTL = 60.0

ASSET_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "admin_ui")
INDEX_FILE = "index.html"
ASSETS = {
    "admin.css": "text/css; charset=utf-8",
    "sprite.svg": "image/svg+xml",
    "data.js": "text/javascript; charset=utf-8",
    "render.js": "text/javascript; charset=utf-8",
    "app.js": "text/javascript; charset=utf-8",
    "adapter.js": "text/javascript; charset=utf-8",
}
"""``admin_ui/`` 里允许通过 ``/admin/assets/`` 提供的文件（白名单，等于遍历防护）。"""

CSP = ("default-src 'none'; script-src 'self'; "
       "style-src 'self' 'unsafe-inline'; connect-src 'self'; "
       "img-src 'self' data:; base-uri 'none'; frame-ancestors 'none'")
"""SPA 页面的 CSP：脚本全部外链，无内联脚本、无外链资源。"""

PAGE_HEADERS = (
    ("Cache-Control", "no-store"),
    ("X-Frame-Options", "DENY"),
    ("X-Content-Type-Options", "nosniff"),
    ("Referrer-Policy", "no-referrer"),
    ("Content-Security-Policy", CSP),
)
ASSET_HEADERS = (
    ("Cache-Control", "no-store"),
    ("X-Frame-Options", "DENY"),
    ("X-Content-Type-Options", "nosniff"),
    ("Referrer-Policy", "no-referrer"),
)

ACTION_NAMES = (
    "user.create", "user.update", "user.toggle", "user.delete",
    "session.revoke", "session.revoke-others",
    "token.create", "token.revoke",
    "oauth.create", "oauth.toggle", "oauth.delete",
    "pending.decide",
    "coverage.backfill",
    "audit.verify",
    "security.change-password", "security.totp-begin",
    "security.totp-confirm", "security.totp-rebind", "security.totp-disable",
    "settings.update",
)
"""API.md §4 的 19 个动作；``GET /admin/api/state`` 只读。"""

_SID_RE = re.compile(r"^[0-9a-f]{64}$")
_FAMILY_RE = re.compile(r"^[A-Za-z0-9_-]{1,160}$")
_CLIENT_RE = re.compile(r"^[A-Za-z0-9_-]{1,120}$")
_CODE_RE = re.compile(r"^\d{6}$")
_SCOPE_SET = frozenset(oauth.SCOPES)

_MSG_INVALID = "输入无效，请检查后重试。"
_MSG_NOT_FOUND = "没有找到这个对象。"
_MSG_USERNAME = "用户名需为 3–32 位小写字母、数字、点、下划线或连字符。"
_MSG_DUPLICATE = "名称或用户名已存在。"
_MSG_SHORT_PASSWORD = f"密码至少 {admin.MIN_PASSWORD} 位。"
_MSG_SCOPE = "至少选择一个权限范围。"
_MSG_SESSION = "会话不存在、已过期或不属于当前账号。"
_MSG_CURRENT_SESSION = "不能在这里吊销当前会话；请使用“退出登录”。"
_MSG_LAST_ADMIN = "不能停用或删除当前管理员，否则会锁死后台。"
_MSG_CONFIRM = "确认文本不匹配，未执行删除。"
_MSG_REDIRECT = "回调地址无效：只允许 https（本机回环可用 http）。"
_MSG_TOTP = "动态码不正确。"
_MSG_TOTP_ENABLED = "两步验证已启用；如需更换密钥，请先关闭。"
_MSG_TOTP_DISABLED = "两步验证尚未启用。"
_MSG_SETTINGS = "NAS 的会话与锁定策略由环境变量与登录限速决定，页面上不可修改。"
_MSG_NO_CHANGE = "没有需要保存的修改。"
_MSG_PENDING = "没有待确认的授权请求。"


class ApiError(Exception):
    """动作的业务失败；消息会原样显示给管理员，不能带内部细节。"""


# ------------------------------------------------------------------ 静态资源
def _read(path: str) -> bytes | None:
    try:
        with open(path, "rb") as handle:
            return handle.read()
    except OSError:
        return None


def serve_index(handler) -> None:
    """``/admin``：返回构建出的 SPA 壳（已登录后才到这里）。"""
    body = _read(os.path.join(ASSET_DIR, INDEX_FILE))
    if body is None:
        web._error_page(handler, 503, "管理界面资源缺失：请重新部署 hcb/admin_ui/。")
        return
    web._send(handler, 200, body, "text/html; charset=utf-8", PAGE_HEADERS)


def serve_asset(handler, path: str) -> None:
    """``/admin/assets/<file>``：只认白名单里的构建产物。"""
    if handler.command not in ("GET", "HEAD"):
        web._method_not_allowed(handler, "GET, HEAD")
        return
    prefix = "/admin/assets/"
    name = unquote(path[len(prefix):]) if path.startswith(prefix) else ""
    if (not name or name not in ASSETS or "/" in name or "\\" in name
            or name.startswith(".") or ".." in name):
        web._error_page(handler, 404, "没有这个文件。")
        return
    body = _read(os.path.join(ASSET_DIR, name))
    if body is None:
        web._error_page(handler, 404, "没有这个文件。")
        return
    web._send(handler, 200, body, ASSETS[name], ASSET_HEADERS)


# ------------------------------------------------------------------ 路由
def handle(handler, path: str) -> bool:
    """``/admin/api/*`` 的分发；调用方已确认前缀。"""
    if path == "/admin/api/state":
        if handler.command not in ("GET", "HEAD"):
            web._method_not_allowed(handler, "GET")
            return True
        _state_response(handler)
        return True
    if path == "/admin/api/act":
        if handler.command != "POST":
            web._method_not_allowed(handler, "POST")
            return True
        _act_response(handler)
        return True
    web._json(handler, 404, {"ok": False, "error": "没有这个接口。"})
    return True


def _session_for_api(handler):
    """API 的会话门禁：未登录/非管理员返回 JSON，不做 302。"""
    if not web._admin_configured():
        web._json(handler, 503, {"ok": False,
                                 "error": "管理员密码未配置：请设置 HCB_ADMIN_PASSWORD。"})
        return None, None
    session, secret = web._load_session(handler)
    if session is None:
        web._json(handler, 401, {"ok": False, "error": "未登录或会话已过期"})
        return None, None
    if not session.get("is_admin"):
        web._json(handler, 403, {"ok": False, "error": "需要管理员权限"})
        return None, None
    return session, secret


def _state_response(handler) -> None:
    session, secret = _session_for_api(handler)
    if session is None:
        return
    if not web._rate_ok(handler, "admin_api_state", *STATE_RATE):
        web._too_many(handler, STATE_RATE[1])
        return
    try:
        with hdb.db(0) as con:
            payload = build_state(con, session, secret)
    except sqlite3.Error:
        web._json(handler, 503, {"ok": False, "error": "数据库暂不可用"})
        return
    web._json(handler, 200, payload)


def _act_response(handler) -> None:
    session, secret = _session_for_api(handler)
    if session is None:
        return
    if not web._same_origin(handler):
        web._json(handler, 403, {"ok": False, "error": "跨站请求被拒绝"})
        return
    expected = auth.csrf_token(secret, session["sid"])
    supplied = handler.headers.get("X-CSRF-Token") or ""
    if not supplied or not hmac.compare_digest(expected, supplied):
        web._json(handler, 400, {"ok": False,
                                 "error": "CSRF 校验失败，请刷新页面后重试。"})
        return
    if not web._rate_ok(handler, "admin_api_act", *ACT_RATE):
        web._too_many(handler, ACT_RATE[1])
        return
    try:
        raw = web._read_body(handler, API_BODY_LIMIT)
    except web._TooLarge:
        web._json(handler, 413, {"ok": False, "error": "请求过大。"})
        return
    try:
        data = json.loads(raw.decode("utf-8") or "{}")
    except (UnicodeDecodeError, json.JSONDecodeError):
        web._json(handler, 400, {"ok": False, "error": "请求不是合法的 JSON。"})
        return
    if not isinstance(data, dict):
        web._json(handler, 400, {"ok": False, "error": "请求不是合法的 JSON 对象。"})
        return
    name = data.get("name")
    payload = data.get("payload", {})
    if not isinstance(name, str) or not name or not isinstance(payload, dict):
        web._json(handler, 400, {"ok": False, "error": "动作请求格式无效。"})
        return
    action = _ACTIONS.get(name)
    if action is None:
        web._json(handler, 200, {"ok": False, "error": "未知操作。"})
        return
    headers: list[tuple[str, str]] = []
    result = _run_action(action, payload, session, handler, headers)
    web._json(handler, 200, result, headers=headers)


def _run_action(action, payload, session, handler, headers) -> dict:
    """在同一个事务里执行动作 + 审计；失败整体回滚。"""
    try:
        with hdb.db(0) as con:
            data = action(con, payload, session, handler, headers)
        # 写成功：链校验/安全段的小缓存立刻过期，下一次 state 必须看到新事实。
        clear_cache()
    except ApiError as exc:
        return {"ok": False, "error": str(exc)}
    except oauth.OAuthError as exc:
        return {"ok": False, "error": _oauth_message(exc)}
    except ValueError as exc:
        return {"ok": False, "error": _value_message(exc)}
    except sqlite3.Error:
        return {"ok": False, "error": "数据库操作失败，请稍后重试。"}
    except Exception as exc:  # noqa: BLE001 - 审计/意外错误：事务已回滚，绝不返回成功
        print(f"[admin-api] action {getattr(action, '__name__', '?')} failed: "
              f"{type(exc).__name__}", flush=True)
        return {"ok": False, "error": "操作失败，请稍后重试。"}
    return {"ok": True, "data": data if isinstance(data, dict) else {}}


def _oauth_message(exc: oauth.OAuthError) -> str:
    text = f"{exc.error} {exc.description or ''}"
    if "上限" in text or "429" in text:
        return "客户端数量已达上限。"
    if "redirect" in text:
        return _MSG_REDIRECT
    if "already" in text:
        return _MSG_DUPLICATE
    return "OAuth 客户端参数无效。"


def _value_message(exc: ValueError) -> str:
    text = str(exc)
    if "already exists" in text:
        return _MSG_DUPLICATE
    if "username" in text:
        return _MSG_USERNAME
    if "password" in text:
        return "密码不符合要求。"
    if "display" in text:
        return "显示名不能为空。"
    return _MSG_INVALID


# ------------------------------------------------------------------ 状态
def build_state(con: sqlite3.Connection, session: dict, secret: str, *,
                now: int | None = None) -> dict:
    """``admin-ui/API.md`` §3 的整包状态；任何一段失败只让该段为空。"""
    ts = int(time.time()) if now is None else int(now)
    summary = _safe(lambda: stats.sync_summary(con), dict)
    sessions = _safe(lambda: _sessions(con, session), list)
    users = _safe(lambda: _users(con, sessions), list)
    coverage = _safe(lambda: _coverage(con), list)
    return {
        "version": os.environ.get("HCB_VERSION", "1.0"),
        "now": ts * 1000,
        "me": {"id": "0", "username": auth.ADMIN_SUBJECT,
               "display": auth.ADMIN_SUBJECT, "role": "admin"},
        "overview": {
            "sync": _sync(summary),
            "backfill": _backfill(summary),
            "kpis": _safe(lambda: _kpis(con, coverage, sessions, users), dict),
        },
        "users": users,
        "sessions": sessions,
        "tokens": _safe(lambda: _tokens(con), list),
        "oauth": _safe(lambda: _oauth(con), list),
        "pending": [],
        "coverage": coverage,
        "sleep": _safe(lambda: _sleep(con), list),
        "metrics": _safe(lambda: _metrics(con), _empty_metrics),
        "audit": _safe(lambda: _audit(con), _empty_audit),
        "security": _safe(lambda: _security(con), _empty_security),
        "caps": {"users": "full", "sessions": "full", "settings": False},
        "csrf": auth.csrf_token(secret, session["sid"]),
    }


def _safe(builder, default_factory):
    try:
        return builder()
    except Exception as exc:  # noqa: BLE001 - 单段取不到数据不能让整个 state 失败
        print(f"[admin-api] state section failed: {type(exc).__name__}", flush=True)
        return default_factory()


def _sync(summary: dict) -> dict:
    last_sync = summary.get("last_sync")
    healthy = summary.get("healthy")
    stale = summary.get("stale")
    if healthy:
        pipeline = "ok"
    elif last_sync is None or stale is None:
        pipeline = "unknown"
    else:
        pipeline = "warn"
    return {
        "lastSyncAt": last_sync,
        "lastSyncSource": summary.get("last_source"),
        "lastSyncRows": summary.get("last_rows") or 0,
        "lagMin": summary.get("lag_min"),
        "pipeline": pipeline,
    }


def _backfill(summary: dict) -> dict:
    item = summary.get("backfill") or {}
    if item.get("running"):
        status = "running"
    elif item.get("ok") is False or item.get("error"):
        status = "error"
    else:
        status = "idle"
    note = item.get("note") or item.get("error") or ""
    return {
        "status": status,
        "progress": item.get("progress"),
        "rangeFrom": item.get("range_from") or "",
        "rangeTo": item.get("range_to") or "",
        "lastRun": item.get("done_at") or item.get("started_at"),
        "note": str(note)[:200],
    }


def _kpis(con: sqlite3.Connection, coverage: list[dict], sessions: list[dict],
          users: list[dict]) -> dict:
    heart = next((row for row in coverage if row["type"] == "heart_rate"), None)
    return {
        "heartRows": heart["rows"] if heart else None,
        "coverageTypes": len(coverage),
        "lowCoverage": sum(1 for row in coverage
                           if row.get("pct") is not None and row["pct"] < 0.9),
        "activeSessions": len(sessions),
        "activeUsers": sum(1 for user in users if user["status"] == "active"),
        "auditFails24h": _safe(lambda: stats.audit_fail_count(con, 24), int),
    }


def _users(con: sqlite3.Connection, sessions: list[dict]) -> list[dict]:
    totp = auth.admin_totp_state(con)
    last_seen: dict[str, int] = {}
    counts: dict[str, int] = {}
    for item in sessions:
        subject = auth.ADMIN_SUBJECT if item["userId"] == "0" \
            else auth.subject_for_user(int(item["userId"]))
        counts[subject] = counts.get(subject, 0) + 1
        stamp = item["lastSeen"] or 0
        if stamp > last_seen.get(subject, 0):
            last_seen[subject] = stamp
    out = [{
        "id": "0",
        "username": auth.ADMIN_SUBJECT,
        "display": auth.ADMIN_SUBJECT,
        "role": "admin",
        "status": "active",
        "totp": bool(totp["enabled"]),
        "lastLogin": last_seen.get(auth.ADMIN_SUBJECT) or None,
        "createdAt": None,
        "sessions": counts.get(auth.ADMIN_SUBJECT, 0),
        "note": "",
        "scopes": ["admin", *oauth.SCOPES],
    }]
    for user in auth.list_users(con):
        scopes = list(user["scopes"])
        subject = auth.subject_for_user(int(user["id"]))
        out.append({
            "id": str(user["id"]),
            "username": user["username"],
            "display": user["display_name"] or user["username"],
            "role": "service" if "health.write" in scopes else "viewer",
            "status": "active" if user["enabled"] else "disabled",
            "totp": False,
            "lastLogin": last_seen.get(subject) or None,
            "createdAt": user["created_at"],
            "sessions": counts.get(subject, 0),
            "note": "",
            "scopes": scopes,
        })
    return out


_DEVICE_OS = (
    ("android", "Android"), ("iphone", "iOS"), ("ipad", "iOS"),
    ("windows", "Windows"), ("macintosh", "macOS"), ("mac os x", "macOS"),
    ("cros", "ChromeOS"), ("linux", "Linux"),
)
_DEVICE_APP = (
    ("edg/", "Edge"), ("opr/", "Opera"), ("samsungbrowser", "Samsung"),
    ("chrome/", "Chrome"), ("firefox/", "Firefox"), ("safari/", "Safari"),
    ("okhttp", "OkHttp"), ("curl/", "curl"), ("python-urllib", "Python"),
)


def _device_label(user_agent: object) -> str:
    """`Chrome · macOS` 这类短标签；UA 为空时给 ``未知设备``。"""
    raw = str(user_agent or "").strip()
    if not raw:
        return "未知设备"
    lowered = raw.lower()
    browser = next((name for key, name in _DEVICE_APP if key in lowered), "")
    system = next((name for key, name in _DEVICE_OS if key in lowered), "")
    if browser and system:
        return f"{browser} · {system}"
    if browser or system:
        return browser or system
    return raw[:40]


def _sessions(con: sqlite3.Connection, session: dict) -> list[dict]:
    now = int(time.time())
    current = session.get("sid_hash") or auth.hash_token(session["sid"])
    out = []
    for item in auth.list_sessions(con):
        if int(item["expires_at"]) <= now:
            continue
        subject = item["subject"]
        user_id = "0" if subject == auth.ADMIN_SUBJECT else \
            str(auth.user_id_for_subject(subject) or "")
        out.append({
            "id": item["sid_hash"],
            "userId": user_id,
            "device": _device_label(item.get("user_agent")),
            "ip": "—",
            "loc": "—",
            "createdAt": item["created_at"],
            "lastSeen": item["last_seen_at"],
            "current": item["sid_hash"] == current,
            "expiresAt": item["expires_at"],
        })
    return out


def _tokens(con: sqlite3.Connection) -> list[dict]:
    # 静态 key 没有 scope 列：工具权限在 tool_policy_json 里，这里给只读+写入上限。
    return [{
        "id": str(row["id"]),
        "name": row["label"],
        "prefix": f"key_{row['id']}",
        "scopes": list(oauth.SCOPES),
        "status": "revoked" if row["revoked_at"] else "active",
        "userId": str(row["owner_user_id"] or 0),
        "createdAt": row["created_at"],
        "lastUsed": row["last_used_at"],
        "expires": None,
        "kind": "static",
        "family": "",
    } for row in auth.static_token_list(con, include_revoked=True)]


def _oauth(con: sqlite3.Connection) -> list[dict]:
    return [{
        "id": client["client_id"],
        "name": client["client_name"],
        "clientId": client["client_id"],
        "kind": "confidential" if client["confidential"] else "public",
        "redirects": list(client["redirect_uris"]),
        "status": "revoked" if client["revoked"] else "active",
        "createdAt": client["created_at"],
        "lastAuth": client["last_used_at"],
    } for client in oauth.list_clients(con, include_revoked=True)]


def _coverage(con: sqlite3.Connection) -> list[dict]:
    return [{
        "type": item["type"],
        "label": item["label"],
        "rows": item["rows"],
        "sources": item["sources"],
        "days30": item["days30"],
        "pct": item["pct"],
        "lastAt": item["last"],
    } for item in stats.coverage(con, 30)]


def _sleep(con: sqlite3.Connection) -> list[dict]:
    return [{
        "date": item["date"],
        "bed": item["bed"],
        "wake": item["wake"],
        "hours": item["hours"],
        "score": item["score"],
        "source": item["source"],
        "flags": item["flags"],
        "note": item["note"],
    } for item in stats.sleep_days(con, 30)]


_METRIC_DECIMALS = {"steps": 0, "heart_rate": 0, "sleep": 1}


def _metrics(con: sqlite3.Connection) -> dict:
    # 90 日序列一次取回，页面本地切 14/30/90，不再为每次切换发请求。
    days = 90
    out: dict = {"days": days}
    for key in ("steps", "heart_rate", "sleep"):
        data = stats.series(con, key, days)
        out[key] = {
            "label": data["label"],
            "unit": data["unit"],
            "decimals": _METRIC_DECIMALS[key],
            "latest": data["latest"],
            "avg": data["avg"],
            "series": list(data["values"]),
        }
    return out


def _empty_metrics() -> dict:
    return {"days": 14}


def _audit(con: sqlite3.Connection) -> dict:
    rows = []
    for row in audit.list_recent(con, AUDIT_ROW_LIMIT):
        detail = _audit_detail(row["detail"])
        rows.append({
            "id": str(row["id"]),
            "at": row["created_at"],
            "actor": row["actor"],
            "action": row["action"],
            "target": _audit_target(detail),
            "outcome": "ok" if (row["result"] or "") in ("ok", "approved", "success")
            else "fail",
            "detail": detail,
        })
    return {"rows": rows, "chainOk": _chain_ok(con)}


def _empty_audit() -> dict:
    return {"rows": [], "chainOk": None}


def _audit_detail(raw: object) -> str:
    """审计的 ``detail`` 列是规范化 JSON；只取人可读的 ``detail``/``value``。"""
    if not raw:
        return ""
    try:
        data = json.loads(str(raw))
    except (TypeError, ValueError):
        return str(raw)[:200]
    if isinstance(data, dict):
        for key in ("detail", "value"):
            value = data.get(key)
            if isinstance(value, str) and value:
                return value[:300]
        return ""
    return str(data)[:200]


def _audit_target(detail: str) -> str:
    token = (detail or "").split(" ", 1)[0].strip()
    return token[:80] if "=" in token else ""


_CACHE_LOCK = threading.Lock()
_CACHE: dict[tuple, tuple[float, object]] = {}


def _cached(name: str, builder):
    key = (hdb.health_db_path(), name)
    now = time.monotonic()
    with _CACHE_LOCK:
        hit = _CACHE.get(key)
        if hit is not None and hit[0] > now:
            return hit[1]
    value = builder()
    with _CACHE_LOCK:
        _CACHE[key] = (now + SECTIONS_CACHE_TTL, value)
    return value


def clear_cache() -> None:
    """测试用：清掉链校验/安全段的小缓存。"""
    with _CACHE_LOCK:
        _CACHE.clear()


def _chain_ok(con: sqlite3.Connection) -> bool:
    """状态页的链校验结果缓存 60 秒；``audit.verify`` 动作总是现算。"""
    return bool(_cached("chain", lambda: audit.verify(con)["ok"]))


def _last_audit_at(con: sqlite3.Connection, action: str) -> int | None:
    def builder():
        try:
            rows = audit.list_recent(con, 1, action=action, result="ok")
        except sqlite3.Error:
            return None
        return rows[0]["created_at"] if rows else None
    return _cached(f"audit:{action}", builder)


def _security(con: sqlite3.Connection) -> dict:
    totp = auth.admin_totp_state(con)
    return {
        "totpEnabled": bool(totp["enabled"]),
        "totpEnrolledAt": _last_audit_at(con, "admin.totp.confirm"),
        "passwordChangedAt": _last_audit_at(con, "admin.password.change"),
        "passwordSet": auth.admin_has_password(con),
        "sessionTtlHours": max(1, int(auth.ADMIN_SESSION_TTL) // 3600),
        "allowPasswordLogin": True,
        "requireTotp": False,
        "maxFailedLogins": web.LOGIN_LIMIT,
        "lockoutMinutes": max(1, web.LOGIN_WINDOW // 60),
        "settingsEditable": False,
    }


def _empty_security() -> dict:
    return {"totpEnabled": False, "passwordSet": False, "settingsEditable": False}


# ------------------------------------------------------------------ 动作辅助
def _text(payload: dict, key: str, *aliases: str) -> str:
    value = payload.get(key)
    for alias in aliases:
        if value is None:
            value = payload.get(alias)
    if value is None:
        return ""
    if not isinstance(value, str):
        raise ApiError(_MSG_INVALID)
    return value.strip()


def _int(payload: dict, key: str) -> int:
    value = payload.get(key)
    if isinstance(value, bool):
        raise ApiError(_MSG_INVALID)
    if isinstance(value, int):
        return value
    if isinstance(value, str) and value.strip().isdigit():
        return int(value.strip())
    raise ApiError(_MSG_INVALID)


def _bool(payload: dict, key: str) -> bool:
    value = payload.get(key)
    if not isinstance(value, bool):
        raise ApiError(_MSG_INVALID)
    return value


def _scopes(payload: dict, *, required: bool = True) -> list[str]:
    raw = payload.get("scopes")
    if raw is None:
        if required:
            raise ApiError(_MSG_SCOPE)
        return []
    if not isinstance(raw, list):
        raise ApiError(_MSG_SCOPE)
    out: list[str] = []
    for item in raw:
        if not isinstance(item, str) or item not in _SCOPE_SET:
            raise ApiError(_MSG_SCOPE)
        if item not in out:
            out.append(item)
    if required and not out:
        raise ApiError(_MSG_SCOPE)
    return out


def _user(con: sqlite3.Connection, payload: dict) -> dict:
    user = auth.get_user(con, _int(payload, "id"))
    if user is None:
        raise ApiError("没有找到这个用户。")
    return user


# ------------------------------------------------------------------ 动作：用户
def _user_create(con, payload, session, handler, headers) -> dict:
    username = _text(payload, "username")
    display = _text(payload, "display", "display_name")
    password = payload.get("password")
    scopes = _scopes(payload)
    if not isinstance(password, str) or len(password) < admin.MIN_PASSWORD:
        raise ApiError(_MSG_SHORT_PASSWORD)
    user = auth.create_user(con, username, password, display or None, scopes)
    admin.record_admin_action(
        con, "admin.user.create",
        detail=f'id={user["id"]} user={user["username"]}')
    return {"id": str(user["id"])}


def _user_update(con, payload, session, handler, headers) -> dict:
    user = _user(con, payload)
    kwargs: dict = {}
    if "display" in payload or "display_name" in payload:
        display = _text(payload, "display", "display_name")
        if not display:
            raise ApiError("显示名不能为空。")
        kwargs["display_name"] = display
    if "scopes" in payload:
        kwargs["scopes"] = _scopes(payload)
    password = payload.get("password")
    if password not in (None, ""):
        if not isinstance(password, str) or len(password) < admin.MIN_PASSWORD:
            raise ApiError(_MSG_SHORT_PASSWORD)
        kwargs["password"] = password
    if not kwargs:
        raise ApiError(_MSG_NO_CHANGE)
    updated = auth.update_user(con, int(user["id"]), **kwargs)
    if updated is None:
        raise ApiError("没有找到这个用户。")
    suffix = ""
    if "password" in kwargs:
        auth.revoke_subject(con, auth.subject_for_user(int(user["id"])))
        suffix = " password=1"
    admin.record_admin_action(
        con, "admin.user.update",
        detail=f'id={user["id"]} user={user["username"]}{suffix}')
    return {}


def _user_toggle(con, payload, session, handler, headers) -> dict:
    user = _user(con, payload)
    enabled = _bool(payload, "enabled")
    user_id = int(user["id"])
    if not enabled and not admin._guard_admin_change(con, session, user_id):
        raise ApiError(_MSG_LAST_ADMIN)
    auth.update_user(con, user_id, enabled=enabled)
    if not enabled:
        admin._revoke_user_credentials(con, user_id)
    admin.record_admin_action(
        con, "admin.user.enable" if enabled else "admin.user.disable",
        detail=f'id={user_id} user={user["username"]}')
    return {}


def _user_delete(con, payload, session, handler, headers) -> dict:
    user = _user(con, payload)
    user_id = int(user["id"])
    if not admin._guard_admin_change(con, session, user_id):
        raise ApiError(_MSG_LAST_ADMIN)
    if _text(payload, "username") != user["username"]:
        raise ApiError(_MSG_CONFIRM)
    if not admin._archive_user_partition(user_id):
        raise ApiError("独立分区归档失败，用户未删除。")
    admin._revoke_user_credentials(con, user_id)
    con.execute("DELETE FROM users WHERE id=?", (user_id,))
    admin.record_admin_action(
        con, "admin.user.delete",
        detail=f'id={user_id} user={user["username"]}')
    return {}


# ------------------------------------------------------------------ 动作：会话
def _session_revoke(con, payload, session, handler, headers) -> dict:
    sid_hash = _text(payload, "id").lower()
    if not _SID_RE.fullmatch(sid_hash):
        raise ApiError(_MSG_INVALID)
    if sid_hash == (session.get("sid_hash") or auth.hash_token(session["sid"])):
        raise ApiError(_MSG_CURRENT_SESSION)
    if not auth.revoke_session(con, sid_hash):
        raise ApiError(_MSG_SESSION)
    admin.record_admin_action(
        con, "admin.session.revoke", detail=f"sid_hash={sid_hash[:12]}…")
    return {}


def _session_revoke_others(con, payload, session, handler, headers) -> dict:
    subtotal = 0
    for subject in {row["subject"] for row in auth.list_sessions(con)}:
        subtotal += auth.revoke_subject(con, subject, keep_sid=session["sid"])
    admin.record_admin_action(
        con, "admin.session.revoke_others", detail=f"count={subtotal}")
    return {"revoked": subtotal}


# ------------------------------------------------------------------ 动作：令牌
def _token_create(con, payload, session, handler, headers) -> dict:
    name = _text(payload, "name")
    _scopes(payload)
    if not name:
        raise ApiError(_MSG_INVALID)
    data = auth.static_token_create(con, name, owner_user_id=0)
    admin.record_admin_action(
        con, "admin.key.create",
        detail=f'id={data["id"]} label={data["label"]} owner=0')
    return {"id": str(data["id"]), "secret": data["token"],
            "prefix": f'key_{data["id"]}'}


def _token_revoke(con, payload, session, handler, headers) -> dict:
    family = payload.get("family")
    if family not in (None, ""):
        if not isinstance(family, str) or not _FAMILY_RE.fullmatch(family):
            raise ApiError(_MSG_INVALID)
        cur = con.execute(
            "UPDATE oauth_tokens SET revoked_at=? "
            "WHERE family_id=? AND revoked_at IS NULL",
            (int(time.time()), family))
        if cur.rowcount <= 0:
            raise ApiError("令牌不存在或已撤销。")
        admin.record_admin_action(
            con, "admin.token.revoke",
            detail=f"family={family[:12]}… count={cur.rowcount}")
        return {}
    token_id = _int(payload, "id")
    if not auth.static_token_revoke(con, token_id):
        raise ApiError("令牌不存在或已撤销。")
    admin.record_admin_action(con, "admin.key.revoke", detail=f"id={token_id}")
    return {}


# ------------------------------------------------------------------ 动作：OAuth
def _client_id(payload: dict) -> str:
    client_id = _text(payload, "id", "client_id")
    if not _CLIENT_RE.fullmatch(client_id):
        raise ApiError(_MSG_INVALID)
    return client_id


def _oauth_create(con, payload, session, handler, headers) -> dict:
    name = _text(payload, "name")
    kind = _text(payload, "kind") or "public"
    raw_uris = payload.get("redirects")
    if kind not in ("public", "confidential"):
        raise ApiError(_MSG_INVALID)
    if not isinstance(raw_uris, list) or not raw_uris:
        raise ApiError(_MSG_REDIRECT)
    uris = []
    for item in raw_uris:
        if not isinstance(item, str) or not item.strip():
            raise ApiError(_MSG_REDIRECT)
        uris.append(item.strip())
    data = oauth.register_client(con, {
        "client_name": name,
        "redirect_uris": uris,
        "token_endpoint_auth_method":
            "client_secret_post" if kind == "confidential" else "none",
    })
    admin.record_admin_action(
        con, "admin.client.create",
        detail=f'client_id={data["client_id"]} name={data["client_name"]}')
    out = {"clientId": data["client_id"]}
    if data.get("client_secret"):
        out["secret"] = data["client_secret"]
    return out


def _oauth_toggle(con, payload, session, handler, headers) -> dict:
    client_id = _client_id(payload)
    enabled = _bool(payload, "enabled")
    client = oauth.get_client(con, client_id)
    if client is None:
        raise ApiError("没有找到这个客户端。")
    if enabled:
        if client["revoked"]:
            con.execute(
                "UPDATE oauth_clients SET revoked_at=NULL WHERE client_id=?",
                (client_id,))
        admin.record_admin_action(
            con, "admin.client.enable", detail=f"client_id={client_id}")
    else:
        if not client["revoked"] and not oauth.revoke_client(con, client_id):
            raise ApiError("没有找到这个客户端。")
        admin.record_admin_action(
            con, "admin.client.revoke", detail=f"client_id={client_id}")
    return {}


def _oauth_delete(con, payload, session, handler, headers) -> dict:
    client_id = _client_id(payload)
    client = oauth.get_client(con, client_id)
    if client is None:
        raise ApiError("没有找到这个客户端。")
    if not client["revoked"]:
        raise ApiError("只能删除已停用的客户端；请先停用。")
    con.execute("DELETE FROM oauth_tokens WHERE client_id=?", (client_id,))
    con.execute("DELETE FROM oauth_codes WHERE client_id=?", (client_id,))
    con.execute("DELETE FROM oauth_clients WHERE client_id=?", (client_id,))
    admin.record_admin_action(
        con, "admin.client.delete", detail=f"client_id={client_id}")
    return {}


def _pending_decide(con, payload, session, handler, headers) -> dict:
    # NAS 的 /authorize 是同步同意页，没有排队请求；这里只做显式拒绝。
    raise ApiError(_MSG_PENDING)


# ------------------------------------------------------------------ 动作：数据
def _coverage_backfill(con, payload, session, handler, headers) -> dict:
    metric = payload.get("type")
    if metric not in (None, ""):
        if not isinstance(metric, str) or metric not in stats.METRIC_LABELS:
            raise ApiError("未知的数据类型。")
    if not admin._creds_ready(0):
        raise ApiError("该分区没有小米凭据，请先导入。")
    import server as server_mod
    days = 30
    try:
        started = server_mod.backfill_async(days, owner=0)
    except Exception:  # noqa: BLE001 - 后台启动失败不该给出 traceback
        raise ApiError("回补启动失败，请查看服务日志。") from None
    admin.record_admin_action(
        con, "admin.backfill.start",
        result="ok" if started else "denied",
        detail=f"owner=0 days={days} type={metric or 'all'}")
    if not started:
        raise ApiError("该分区已有回补在运行。")
    return {"queued": True}


def _audit_verify(con, payload, session, handler, headers) -> dict:
    status = audit.verify(con)
    return {"chainOk": bool(status.get("ok")),
            "checked": int(status.get("rows") or 0)}


# ------------------------------------------------------------------ 动作：安全
def _admin_password(con, payload, session, handler, headers) -> dict:
    form = {
        "current_password": payload.get("current", ""),
        "new_password": payload.get("password", ""),
        "confirm_password": payload.get("password2", ""),
    }
    for value in form.values():
        if not isinstance(value, str):
            raise ApiError(_MSG_INVALID)
    error, cookie = admin._admin_password_change(con, handler, form)
    if error:
        raise ApiError(admin._ERR.get(error, "密码不符合要求。"))
    if cookie:
        headers.append(cookie)
    return {}


def _totp_begin(con, payload, session, handler, headers) -> dict:
    if auth.admin_totp_state(con)["enabled"]:
        raise ApiError(_MSG_TOTP_ENABLED)
    secret = auth.admin_totp_enable(con)
    admin.record_admin_action(con, "admin.totp.prepare")
    return {"secret": secret, "uri": admin._otp_uri(secret)}


def _totp_confirm(con, payload, session, handler, headers) -> dict:
    code = _text(payload, "code")
    if not _CODE_RE.fullmatch(code):
        raise ApiError("请输入 6 位动态码。")
    if auth.admin_totp_state(con)["enabled"]:
        raise ApiError(_MSG_TOTP_ENABLED)
    if not auth.admin_totp_confirm(con, code):
        raise ApiError(_MSG_TOTP)
    codes = auth.admin_recovery_generate(con, 10)
    admin.record_admin_action(
        con, "admin.totp.confirm", detail="recovery_codes=10")
    # 一次性明文：只在这里出现；SPA 目前不展示，legacy 安全页可重新生成。
    return {"recoveryCodes": codes}


def _totp_disable(con, payload, session, handler, headers) -> dict:
    if not auth.admin_totp_state(con)["enabled"]:
        raise ApiError(_MSG_TOTP_DISABLED)
    code = _text(payload, "code")
    if not _CODE_RE.fullmatch(code) or not auth.admin_totp_verify(con, code):
        raise ApiError(_MSG_TOTP)
    auth.admin_totp_disable(con)
    admin.record_admin_action(con, "admin.totp.disable")
    return {}


def _totp_rebind(con, payload, session, handler, headers) -> dict:
    """Verify the current factor, then replace the secret in one transaction."""
    if not auth.admin_totp_state(con)["enabled"]:
        raise ApiError(_MSG_TOTP_DISABLED)
    code = _text(payload, "code")
    if not _CODE_RE.fullmatch(code) or not auth.admin_totp_verify(con, code):
        raise ApiError(_MSG_TOTP)
    auth.admin_totp_disable(con)
    secret = auth.admin_totp_enable(con)
    admin.record_admin_action(con, "admin.totp.rebind")
    return {"secret": secret, "uri": admin._otp_uri(secret)}


def _settings_update(con, payload, session, handler, headers) -> dict:
    policy = ("sessionTtlHours", "maxFailedLogins", "lockoutMinutes",
              "requireTotp", "allowPasswordLogin")
    if any(key in payload for key in policy):
        raise ApiError(_MSG_SETTINGS)
    interval = payload.get("fetch_interval", payload.get("fetchInterval"))
    if interval in (None, ""):
        raise ApiError(_MSG_NO_CHANGE)
    if isinstance(interval, bool) or not isinstance(interval, (int, str)):
        raise ApiError(admin._ERR["err_interval"])
    try:
        value = int(str(interval).strip())
    except (TypeError, ValueError):
        raise ApiError(admin._ERR["err_interval"]) from None
    if not admin.FETCH_MIN <= value <= admin.FETCH_MAX:
        raise ApiError(admin._ERR["err_interval"])
    auth.settings_set(con, "fetch_interval_override", str(value))
    admin.record_admin_action(
        con, "admin.settings.update", detail=f"fetch_interval={value}")
    return {"changed": 1}


_ACTIONS = {
    "user.create": _user_create,
    "user.update": _user_update,
    "user.toggle": _user_toggle,
    "user.delete": _user_delete,
    "session.revoke": _session_revoke,
    "session.revoke-others": _session_revoke_others,
    "token.create": _token_create,
    "token.revoke": _token_revoke,
    "oauth.create": _oauth_create,
    "oauth.toggle": _oauth_toggle,
    "oauth.delete": _oauth_delete,
    "pending.decide": _pending_decide,
    "coverage.backfill": _coverage_backfill,
    "audit.verify": _audit_verify,
    "security.change-password": _admin_password,
    "security.totp-begin": _totp_begin,
    "security.totp-confirm": _totp_confirm,
    "security.totp-rebind": _totp_rebind,
    "security.totp-disable": _totp_disable,
    "settings.update": _settings_update,
}
assert tuple(_ACTIONS) == ACTION_NAMES, "动作表与 API.md 不一致"
