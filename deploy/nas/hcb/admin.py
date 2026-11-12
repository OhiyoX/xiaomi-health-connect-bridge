"""管理后台与账号自助页。

- ``/admin`` 提供共享 SPA（``hcb/admin_ui/``，JSON API 在 :mod:`hcb.admin_api`）；
  旧 SSR 页面 302 到 ``/admin#/...``，并在 ``/admin/legacy/...`` 保留一个版本。
- ``/account/*`` 仍是 SSR（无框架、无 JS 依赖）。
- 每个路由都先过门禁；普通用户被 302 到 ``/account``。

设计：``docs/superpowers/specs/2026-11-03-admin-ui-redesign.md`` §5。
- 所有 POST 都要求会话 + CSRF + 同源；表单上限 64 KiB（OAuth 仍是 16 KiB）。
- 页面不显示令牌/客户端密钥/密码；新建静态 key 与 OAuth 机密客户端时只显示
  一次原始值，随后库里只有哈希。
- 管理动作通过 :func:`record_admin_action` 上报；Task 7 的 ``hcb.audit`` 出现
  后自动接管，现在没有该模块时是安全的 no-op。
"""
from __future__ import annotations

import json
import os
import re
import secrets
import shutil
import sqlite3
import tempfile
import threading
import time
from email import policy as email_policy
from email.parser import BytesParser
from urllib.parse import parse_qs, quote, urlencode, urlsplit

from . import auth
from . import db as hdb
from . import oauth
from . import policy
from . import views
from . import web

ADMIN_FORM_LIMIT = 64 * 1024
MIN_PASSWORD = auth.ADMIN_MIN_PASSWORD
FETCH_MIN, FETCH_MAX = 300, 86400
BACKFILL_MIN, BACKFILL_MAX = 1, 365
MAX_CREDS_BYTES = 8 * 1024
TOTP_ISSUER = "hc-bridge"

LEGACY_PREFIX = "/admin/legacy"
"""旧 SSR 页面在 SPA 上线后只保留一个版本的入口（``/admin/legacy/...``）。"""

SPA_ROUTES = {
    "/admin/users": "/admin#/users",
    "/admin/keys": "/admin#/tokens",
    "/admin/tokens": "/admin#/tokens",
    "/admin/static-keys": "/admin#/tokens",
    "/admin/clients": "/admin#/oauth",
    "/admin/oauth": "/admin#/oauth",
    "/admin/oauth-clients": "/admin#/oauth",
    "/admin/sessions": "/admin#/sessions",
    "/admin/audit": "/admin#/audit",
    "/admin/security": "/admin#/security",
    "/admin/totp": "/admin#/security",
    # SPA 没有独立的设置页；旧的“设置”主要是抓取间隔与管理员密码。
    "/admin/settings": "/admin#/security",
}
"""旧 GET 路由 → 共享 SPA 的 hash 路由（Track N5 回退）。"""

SPA_DETAIL_ROUTES = (
    (re.compile(r"/admin/users/(\d+)"), "/admin#/users/"),
    (re.compile(r"/admin/clients/([A-Za-z0-9_-]{1,120})"), "/admin#/oauth/"),
)

_FLASH_LOCK = threading.Lock()
_FLASHES: dict[str, tuple[float, str, str]] = {}
_FLASH_TTL = 300
_FLASH_MAX = 512


def _process_start_time() -> float | None:
    """Linux ``/proc`` 里的进程启动时间；取不到时返回 ``None``。"""
    try:
        with open("/proc/self/stat", "r", encoding="ascii") as handle:
            fields = handle.read().split()
        start_ticks = int(fields[21])
        with open("/proc/stat", "r", encoding="ascii") as handle:
            btime = next(int(line.split()[1]) for line in handle
                         if line.startswith("btime "))
        return btime + start_ticks / float(os.sysconf("SC_CLK_TCK"))
    except Exception:  # noqa: BLE001 - 非 Linux 或格式变化时只省略该行
        return None


PROCESS_STARTED = _process_start_time()

_PARTITION_TABLES = (
    ("heart_rate", "ts"),
    ("spo2", "ts"),
    ("steps", "ts"),
    ("sleep", "bed"),
    ("calories", "ts"),
    ("workout", "start"),
    ("stress", "ts"),
    ("weight", "ts"),
)

_OK = {
    "user_created": "用户已创建。",
    "user_updated": "用户已保存。",
    "user_disabled": "用户已停用；其会话、静态密钥与 OAuth 令牌已撤销。",
    "user_enabled": "用户已启用。新密钥与新令牌需要重新创建。",
    "user_deleted": "用户已删除；其静态密钥、会话与令牌已撤销。",
    "password_reset": "用户密码已重置，旧会话已失效。",
    "key_revoked": "静态密钥已撤销。",
    "client_revoked": "OAuth 客户端及其令牌已撤销。",
    "client_tokens_revoked": "该客户端的全部令牌已撤销。",
    "session_revoked": "会话已吊销。",
    "sessions_revoked": "选定的会话已吊销。",
    "settings_saved": "设置已保存。",
    "password_changed": "密码已修改；当前会话保持登录。",
    "profile_saved": "显示名已保存。",
    "totp_prepared": "已生成新的 TOTP 密钥。请用身份验证器扫描/录入，然后输入一个动态码确认。",
    "totp_disabled": "两步验证已关闭；恢复码已清除。",
    "creds_saved": "小米凭据已保存（不回显）。",
    "sync_started": "已启动同步；完成后可刷新概览查看。",
    "backfill_started": "已启动回补。",
}

_ERR = {
    "err_generic": "操作失败，请检查输入后重试。",
    "err_csrf": "CSRF 校验失败，请刷新页面后重试。",
    "err_invalid": "输入无效，请检查后重试。",
    "err_username": "用户名需为 3–32 位小写字母、数字、点、下划线或连字符。",
    "err_duplicate": "名称或用户名已存在。",
    "err_role": "当前版本只有内置管理员一个 admin 账号，不能创建或改成管理员。",
    "err_scope": "至少选择一个 scope。",
    "err_data_mode": "数据模式只能是共享或独立。",
    "err_policy": "工具权限 JSON 无效：必须是字符串或 {tool, args} 对象的数组。",
    "err_window": "时间窗 JSON 无效：必须是对象（tz/start/end/days/from/until）。",
    "err_password": "密码不符合要求。",
    "err_short_password": f"密码至少 {MIN_PASSWORD} 位。",
    "err_password_mismatch": "两次输入的新密码不一致。",
    "err_current_password": "当前密码不正确。",
    "err_last_admin": "不能停用或删除当前管理员，否则会锁死后台。",
    "err_confirm": "确认文本不匹配，未执行删除。",
    "err_owner": "所有者不存在或不可用。",
    "err_redirect": "redirect URI 无效：只允许 https（本机回环可用 http）。",
    "err_interval": f"抓取间隔必须是 {FETCH_MIN}–{FETCH_MAX} 秒的整数。",
    "err_totp": "动态码或恢复码不正确。",
    "err_totp_enabled": "两步验证已启用；如需更换密钥，请先关闭。",
    "err_totp_disabled": "两步验证尚未启用。",
    "err_action": "未知操作。",
    "err_session": "会话不存在、已过期或不属于当前账号。",
    "err_current_session": "不能在这里吊销当前会话；请使用“退出登录”。",
    "err_not_found": "没有找到这个对象。",
    "err_archive": "独立分区归档失败，用户未删除。",
    "err_too_large": "表单超过 64 KiB，已拒绝。",
    "err_creds": "凭据 JSON 无效：必须是包含 userId 与 passToken 的对象。",
    "err_creds_empty": "没有收到凭据内容：请粘贴 JSON 或选择文件。",
    "err_creds_big": "凭据内容超过 8 KiB，已拒绝。",
    "err_creds_write": "凭据写入失败，未做任何修改。",
    "err_creds_missing": "该分区没有小米凭据，请先导入。",
    "err_shared": "共享用户使用 admin 分区；请切换到 admin 后操作。",
    "err_backfill_running": "该分区已有回补在运行。",
    "err_days": f"回补天数必须是 {BACKFILL_MIN}–{BACKFILL_MAX} 的整数。",
}


# ------------------------------------------------------------------ 小工具
def _remember_flash(kind: str, code: str) -> str:
    """一次性 flash：query 里只放随机 token，不能凭 ``?ok=user_created`` 伪造。"""
    token = secrets.token_urlsafe(18)
    now = time.time()
    with _FLASH_LOCK:
        for key, (expires, _kind, _code) in list(_FLASHES.items()):
            if expires <= now:
                _FLASHES.pop(key, None)
        while len(_FLASHES) >= _FLASH_MAX:
            _FLASHES.pop(next(iter(_FLASHES)), None)
        _FLASHES[token] = (now + _FLASH_TTL, kind, code)
    return token


def _consume_flash(kind: str, token: object) -> str | None:
    if not isinstance(token, str) or not token:
        return None
    with _FLASH_LOCK:
        item = _FLASHES.pop(token, None)
    if item is None:
        return None
    expires, stored_kind, code = item
    if stored_kind != kind or expires <= time.time():
        return None
    return code


def _flash(query: dict) -> views.Markup:
    parts = []
    for key, table, cls in (("ok", _OK, "ok"), ("err", _ERR, "err")):
        values = query.get(key) or []
        if values:
            code = _consume_flash(key, values[0])
            text = table.get(code) if code else None
            if text:
                parts.append(f'<p class="{cls}">{views.esc(text)}</p>')
    return views.raw("".join(parts))


def _back(handler, path: str, *, ok: str | None = None,
          err: str | None = None, cookies=()) -> None:
    path = _legacy_path(handler, path)
    params = {}
    if ok:
        params["ok"] = _remember_flash("ok", ok)
    if err:
        params["err"] = _remember_flash("err", err)
    sep = "&" if "?" in path else "?"
    location = path + ((sep + urlencode(params)) if params else "")
    web._redirect(handler, location, 303, cookies=cookies)


def _legacy_path(handler, path: str) -> str:
    """legacy 页面/表单的响应留在 ``/admin/legacy/...``，不回跳 SPA。"""
    if not getattr(handler, "_admin_legacy", False):
        return path
    if not isinstance(path, str) or not path.startswith("/admin"):
        return path
    if path == "/admin":
        return LEGACY_PREFIX
    if path.startswith(LEGACY_PREFIX + "/"):
        return path
    return LEGACY_PREFIX + path[len("/admin"):]


def _legacy_links(page: str) -> str:
    """把 legacy 页面里的 ``/admin/...`` 链接/表单指回 ``/admin/legacy/...``。"""
    if not isinstance(page, str):
        return page
    return re.sub(r'(href|action)="/admin(?!/legacy)(?=[/"])',
                  r'\1="/admin/legacy', page)


def _fmt_ts(value: object) -> str:
    try:
        if value is None or value == "":
            return "—"
        return time.strftime("%Y-%m-%d %H:%M", time.localtime(int(value)))
    except (TypeError, ValueError, OSError, OverflowError):
        return "—"


def _fmt_json(value: object, limit: int = 240) -> str:
    if value is None or value == "":
        return "—"
    text = str(value)
    return text if len(text) <= limit else text[:limit] + "…"


def _parse_multipart(content_type: str, raw: bytes) -> dict[str, list[str]]:
    """最小 multipart/form-data 解析；只取文本字段（凭据文件按文本读）。"""
    try:
        head = ("Content-Type: " + content_type +
                "\r\nMIME-Version: 1.0\r\n\r\n").encode("latin-1", "replace")
        message = BytesParser(policy=email_policy.default).parsebytes(head + raw)
    except Exception:  # noqa: BLE001 - 解析失败按空表单处理，随后 CSRF 拒绝
        return {}
    if not message.is_multipart():
        return {}
    out: dict[str, list[str]] = {}
    for part in message.iter_parts():
        if part.get_content_disposition() != "form-data":
            continue
        name = part.get_param("name", header="content-disposition")
        if not isinstance(name, str) or not name:
            continue
        payload = part.get_payload(decode=True) or b""
        charset = part.get_content_charset() or "utf-8"
        try:
            text = payload.decode(charset, "replace")
        except LookupError:
            text = payload.decode("utf-8", "replace")
        out.setdefault(name, []).append(text)
    return out


def _read_form(handler, limit: int = ADMIN_FORM_LIMIT):
    raw = web._read_body(handler, limit)
    content_type = handler.headers.get("Content-Type") or ""
    if content_type.split(";")[0].strip().lower() == "multipart/form-data":
        return _parse_multipart(content_type, raw)
    return parse_qs(raw.decode("utf-8", "replace"), keep_blank_values=True)


def _error_page_nav(handler, status: int, title: str, message: str,
                    nav: str = "admin") -> None:
    target = "/admin" if nav == "admin" else "/account"
    label = "返回概览" if nav == "admin" else "返回我的账号"
    body = views.raw(f"<p>{views.esc(message)}</p>"
                     f'<p><a href="{target}">{label}</a></p>')
    page = views.page(title, body, nav=nav)
    if nav == "admin":
        page = _legacy_links(page)
    web._html_response(handler, status, page)


def _too_large(handler, nav: str = "admin") -> None:
    _error_page_nav(handler, 413, "请求过大", _ERR["err_too_large"], nav)


def _not_found(handler, nav: str = "admin") -> None:
    _error_page_nav(handler, 404, "没有这个页面", _ERR["err_not_found"], nav)


def _account_error(handler, status: int, message: str) -> None:
    _error_page_nav(handler, status, "出错了", message, "account")


def _page(handler, title: str, body, session, secret: str,
          *, nav: str = "admin", query: dict | None = None) -> None:
    identity = auth.ADMIN_SUBJECT if session.get("is_admin") else _username_for(
        session["user_id"])
    page = views.page(
        title, body, auth.csrf_token(secret, session["sid"]), nav,
        identity=identity, flash=_flash(query) if query is not None else None)
    if nav == "admin":
        page = _legacy_links(page)
    web._html_response(handler, 200, page)


def _username_for(user_id: object) -> str:
    try:
        uid = int(user_id)
    except (TypeError, ValueError):
        return "-"
    if uid <= 0:
        return auth.ADMIN_SUBJECT
    try:
        with hdb.db(0) as con:
            user = auth.get_user(con, uid)
    except Exception:  # noqa: BLE001 - 只用于显示
        return f"user:{uid}"
    return user["username"] if user else f"user:{uid}"


def _owner_label(owner: object, users: dict[int, dict]) -> str:
    try:
        uid = int(owner or 0)
    except (TypeError, ValueError):
        return str(owner)
    if uid <= 0:
        return "admin"
    user = users.get(uid)
    return f'{user["username"]} (id {uid})' if user else f"user:{uid}"


def _scope_set(value: object) -> list[str]:
    selected = set(web._values(value, "scope"))
    return [s for s in oauth.SCOPES if s in selected]


def _scope_inputs(selected) -> views.Markup:
    labels = {"health.read": "读取健康数据", "health.write": "触发同步/写入"}
    chosen = set(selected or ())
    items = [
        views.checkbox("scope", scope, f"{labels.get(scope, scope)} ({scope})",
                       checked=scope in chosen)
        for scope in oauth.SCOPES
    ]
    return views.raw("".join(str(item) for item in items))


def _parse_policy_field(form: dict) -> tuple[str | None, str | None]:
    """返回 ``(json_or_None, error_code)``；空 = 不额外限制。"""
    raw = (web._scalar(form, "tool_policy", "") or "").strip()
    if not raw:
        return None, None
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError:
        return None, "err_policy"
    value, corrupt = policy.parse_policy(parsed)
    if corrupt:
        return None, "err_policy"
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")), None


def _parse_window_field(form: dict) -> tuple[str | None, str | None]:
    raw = (web._scalar(form, "window", "") or "").strip()
    if not raw:
        return None, None
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError:
        return None, "err_window"
    value, corrupt = policy.parse_window(parsed)
    if corrupt:
        return None, "err_window"
    # 用规则引擎再验一遍形状（时区、边界、星期）；避免把坏窗口存进库。
    if value is not None and policy.window_description(value) is None:
        return None, "err_window"
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")), None


def _policy_summary(raw: object) -> str:
    parsed, corrupt = policy.parse_policy(raw)
    if corrupt:
        return "规则损坏（拒绝全部）"
    if parsed is None:
        return "不限制"
    if not parsed:
        return "禁止全部工具"
    parts = []
    for item in parsed:
        if isinstance(item, str):
            parts.append(item)
            continue
        name = str(item.get("tool") or "?")
        args = item.get("args")
        if isinstance(args, dict) and args:
            name += "(" + ", ".join(
                f"{key}={value!r}" for key, value in args.items()) + ")"
        parts.append(name)
    return ", ".join(parts)


def _window_summary(raw: object) -> str:
    parsed, corrupt = policy.parse_window(raw)
    if corrupt:
        return "窗口损坏（拒绝全部）"
    description = policy.window_description(parsed)
    return description or "不限时"


def _creds_path(owner: int) -> str:
    if not owner:
        return os.environ.get("HEALTH_CREDS", "/data/creds.json")
    return os.path.join(hdb.users_dir(), f"{int(owner)}.creds.json")


def _creds_state(owner: int) -> tuple[str, str]:
    path = _creds_path(owner)
    try:
        stat = os.stat(path)
    except OSError:
        return "未配置", "—"
    return "已配置", _fmt_ts(stat.st_mtime)


def _partition_state(owner: int, key: str, default=None):
    try:
        with hdb.db(owner) as con:
            row = con.execute(
                "SELECT value FROM sync_state WHERE key=?", (key,)).fetchone()
        return row["value"] if row is not None else default
    except Exception:  # noqa: BLE001 - 概览不应因分区故障而 500
        return default


def _partition_stats(owner: int) -> list[tuple[str, int, str, str]]:
    out = []
    try:
        with hdb.db(owner) as con:
            for table, column in _PARTITION_TABLES:
                try:
                    row = con.execute(
                        f"SELECT COUNT(*) AS n, MIN({column}) AS lo, MAX({column}) AS hi "
                        f"FROM {table}").fetchone()
                except sqlite3.OperationalError:
                    continue
                out.append((table, int(row["n"]), _fmt_ts(row["lo"]),
                            _fmt_ts(row["hi"])))
    except Exception:  # noqa: BLE001 - 概览不应因分区故障而 500
        return out
    return out


def _user_options(users: list[dict]) -> list[tuple[str, str]]:
    options = [("0", "admin（管理员分区）")]
    for user in users:
        options.append((str(user["id"]),
                        f'{user["username"]} (id {user["id"]})'))
    return options


# ------------------------------------------------------- 凭据 / 同步 / 回补
def _creds_ready(owner: int) -> bool:
    """凭据文件存在且含非空 userId/passToken；坏文件按未配置处理。"""
    try:
        path = _creds_path(int(owner))
        if not os.path.isfile(path):
            return False
        with open(path, encoding="utf-8") as handle:
            data = json.load(handle)
        if not isinstance(data, dict):
            return False
        return bool(str(data.get("userId") or "").strip()
                    and str(data.get("passToken") or "").strip())
    except Exception:  # noqa: BLE001 - 只用于判定是否可同步
        return False


def _parse_creds_form(form: dict) -> tuple[dict | None, str | None]:
    """从粘贴文本或上传文件读凭据；只返回 ``userId``/``passToken``。"""
    uploaded = (web._scalar(form, "creds_file", "")
                or web._scalar(form, "file", "")
                or web._scalar(form, "creds_upload", "") or "")
    pasted = (web._scalar(form, "creds", "")
              or web._scalar(form, "creds_json", "") or "")
    text = uploaded if uploaded.strip() else pasted
    if not text.strip():
        return None, "err_creds_empty"
    if len(text.encode("utf-8")) > MAX_CREDS_BYTES:
        return None, "err_creds_big"
    try:
        data = json.loads(text)
    except (json.JSONDecodeError, TypeError):
        return None, "err_creds"
    if not isinstance(data, dict):
        return None, "err_creds"
    user_id = data.get("userId")
    pass_token = data.get("passToken")
    if isinstance(user_id, int) and not isinstance(user_id, bool):
        user_id = str(user_id)
    if not isinstance(user_id, str) or not isinstance(pass_token, str):
        return None, "err_creds"
    user_id, pass_token = user_id.strip(), pass_token.strip()
    if not user_id or not pass_token:
        return None, "err_creds"
    if len(user_id) > 128 or len(pass_token) > 512:
        return None, "err_creds"
    if any(ord(c) < 32 or ord(c) == 127 for c in user_id + pass_token):
        return None, "err_creds"
    return {"userId": user_id, "passToken": pass_token}, None


def _write_creds(owner: int, data: dict) -> None:
    """原子写凭据文件，权限 0600；失败不留下半截文件。"""
    path = _creds_path(int(owner))
    directory = os.path.dirname(os.path.abspath(path))
    os.makedirs(directory, mode=0o700, exist_ok=True)
    try:
        os.chmod(directory, 0o700)
    except OSError:
        pass
    payload = json.dumps(data, ensure_ascii=False,
                         separators=(",", ":")).encode("utf-8")
    fd, tmp = tempfile.mkstemp(prefix=".creds-", suffix=".tmp", dir=directory)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "wb") as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp, path)
        os.chmod(path, 0o600)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


def _owner_for_form(form: dict) -> tuple[int | None, str | None]:
    raw = (web._scalar(form, "owner", "0") or "0").strip()
    try:
        owner = int(raw)
    except (TypeError, ValueError):
        return None, "err_invalid"
    if owner <= 0:
        return 0, None
    with hdb.db(0) as con:
        user = auth.get_user(con, owner)
    if user is None:
        return None, "err_not_found"
    if user["data_mode"] != "own":
        return None, "err_shared"
    return owner, None


def _audit_worker(action: str, *, result: str = "ok",
                  detail: str | None = None) -> None:
    """后台线程里的审计；失败只报类型，不能影响主请求。"""
    try:
        with hdb.db(0) as con:
            record_admin_action(con, action, result=result, detail=detail)
    except Exception as exc:  # noqa: BLE001
        print(f"[admin] audit {action} 失败: {type(exc).__name__}", flush=True)
        raise


def _sync_worker(owner: int) -> None:
    import server as server_mod
    try:
        stats = server_mod.sync_once(3, owner=int(owner))
    except Exception as exc:  # noqa: BLE001
        print(f"[admin] sync owner={owner} 失败: {type(exc).__name__}",
              flush=True)
        _audit_worker("admin.sync", result="error",
                      detail=f"owner={owner} error={type(exc).__name__}")
        return
    detail = f"owner={owner} stats=" + json.dumps(
        stats, ensure_ascii=False, separators=(",", ":"), default=str)[:300]
    _audit_worker("admin.sync", detail=detail)


def _save_creds_action(handler, form, *, owner: int, back: str) -> None:
    data, error = _parse_creds_form(form)
    if error:
        _back(handler, back, err=error)
        return
    try:
        _write_creds(owner, data)
    except OSError:
        _back(handler, back, err="err_creds_write")
        return
    with hdb.db(0) as con:
        record_admin_action(con, "admin.creds.update",
                            detail=f"owner={owner}")
    _back(handler, back, ok="creds_saved")


def _sync_now_action(handler, *, owner: int, back: str) -> None:
    if not _creds_ready(owner):
        _back(handler, back, err="err_creds_missing")
        return
    with hdb.db(0) as con:
        record_admin_action(con, "admin.sync.start", detail=f"owner={owner}")
    threading.Thread(target=_sync_worker, args=(owner,), daemon=True).start()
    _back(handler, back, ok="sync_started")


def _backfill_action(handler, form, *, owner: int, back: str) -> None:
    raw = (web._scalar(form, "days", "") or "").strip()
    try:
        days = int(raw)
    except (TypeError, ValueError):
        days = 0
    if not BACKFILL_MIN <= days <= BACKFILL_MAX:
        _back(handler, back, err="err_days")
        return
    if not _creds_ready(owner):
        _back(handler, back, err="err_creds_missing")
        return
    import server as server_mod
    try:
        started = server_mod.backfill_async(days, owner=owner)
    except Exception as exc:  # noqa: BLE001
        with hdb.db(0) as con:
            record_admin_action(
                con, "admin.backfill.start", result="error",
                detail=f"owner={owner} days={days} error={type(exc).__name__}")
        _back(handler, back, err="err_generic")
        return
    with hdb.db(0) as con:
        record_admin_action(
            con, "admin.backfill.start",
            result="ok" if started else "denied",
            detail=f"owner={owner} days={days}")
    if not started:
        _back(handler, back, err="err_backfill_running")
        return
    _back(handler, back, ok="backfill_started")


def _partition_action(handler, session, form, *, owner: int, back: str) -> None:
    action = web._scalar(form, "action", "") or ""
    if action == "creds":
        _save_creds_action(handler, form, owner=owner, back=back)
    elif action == "sync_now":
        _sync_now_action(handler, owner=owner, back=back)
    elif action == "backfill":
        _backfill_action(handler, form, owner=owner, back=back)
    else:
        _back(handler, back, err="err_action")


def _partition_forms(action_path: str, owner: int, csrf: str, *,
                     sync: bool = True) -> views.Markup:
    creds = views.form(action_path, [
        views.hidden("action", "creds"),
        views.hidden("owner", str(owner)),
        views.field("粘贴凭据 JSON", views.textarea(
            "creds", "", rows=3,
            placeholder='{"userId": "...", "passToken": "..."}'),
            "不会回显；写入后文件权限 0600。"),
        views.field("或上传 JSON 文件", views.file_input(
            "creds_file", accept=".json,application/json")),
    ], csrf, submit="保存凭据", enctype="multipart/form-data")
    sync_form = views.form(action_path, [
        views.hidden("action", "sync_now"),
        views.hidden("owner", str(owner)),
    ], csrf, submit="立即同步")
    backfill = views.form(action_path, [
        views.hidden("action", "backfill"),
        views.hidden("owner", str(owner)),
        views.field("回补天数", views.text_input(
            "days", "30", inputmode="numeric"),
            f"{BACKFILL_MIN}–{BACKFILL_MAX} 天。"),
    ], csrf, submit="开始回补")
    out = views.raw("<h3>小米凭据</h3>") + creds
    if sync:
        out += views.raw("<h3>同步</h3>") + sync_form + backfill
    return out


# ------------------------------------------------------------------ 审计钩子
def record_admin_action(con, action: str, *, actor: str = auth.ADMIN_SUBJECT,
                        result: str = "ok", detail: str | None = None) -> None:
    """后台动作的统一审计入口。

    Task 7 会提供 ``hcb.audit``。存在时直接调用它的 ``audit_event`` /
    ``record_admin_action``；不存在时写一条最小 ``access_log`` 行，保证后台
    页面看得见动作。**不捕获** sink 的异常：审计失败必须让调用方失败，而不是
    静默丢记录。detail 只放 id/用户名/标签，绝不放密码、令牌或表单原文。
    """
    if detail is not None:
        detail = str(detail)[:500]
    # 先看 sys.modules：测试会替换它，且页面导入过真实模块后包属性会残留。
    import sys
    audit_mod = sys.modules.get("hcb.audit")
    if audit_mod is None:
        try:
            from . import audit as audit_mod
        except ImportError:
            audit_mod = None
    if audit_mod is not None:
        sink = (getattr(audit_mod, "audit_event", None)
                or getattr(audit_mod, "record_admin_action", None))
        if sink is not None:
            sink(con, action, actor=actor, result=result, detail=detail)
            return
    con.execute(
        "INSERT INTO access_log(actor, kind, action, tool, result, detail, "
        "prev_hash, hash, created_at) VALUES(?,?,?,?,?,?,?,?,?)",
        (actor, "admin", action, None, result, detail, None, None,
         int(time.time())),
    )


# ------------------------------------------------------------------ 门禁
def _admin_session(handler, path: str, query: dict):
    if not web._admin_configured():
        web._error_page(handler, 503,
                        "管理员密码未配置：请设置 HCB_ADMIN_PASSWORD 并重启服务。")
        return None, None
    session, secret = web._load_session(handler)
    if session is None:
        raw = urlsplit(handler.path).query
        target = path + (("?" + raw) if raw else "")
        web._redirect(handler, "/login?next=" + quote(target, safe=""))
        return None, None
    if not session["is_admin"]:
        web._redirect(handler, "/account")
        return None, None
    return session, secret


def _account_session(handler, path: str):
    session, secret = web._load_session(handler)
    if session is None:
        web._redirect(handler, "/login?next=" + quote(path, safe=""))
        return None, None
    return session, secret


def _guard_admin_change(con, session: dict, user_id: int) -> bool:
    """删除/停用前的最后管理员保护。

    内置管理员（subject ``admin``）不在 ``users`` 表里，所以任何 ``users.id``
    的删除都不会移除它；这里仍显式拒绝 id 0、拒绝操作当前会话主体，并在
    管理员密码不存在时 fail-closed（否则删掉最后一个普通账号也可能锁死管理）。
    """
    if user_id <= 0:
        return False
    if session.get("is_admin") and int(session.get("user_id") or 0) == user_id:
        return False
    if not auth.admin_has_password(con):
        row = con.execute(
            "SELECT COUNT(*) FROM users WHERE enabled=1 AND id<>?",
            (user_id,)).fetchone()
        return int(row[0]) > 0
    return True


# ------------------------------------------------------------------ 分发
def _owns(path: str) -> bool:
    return (path in ("/admin", "/account", "/account/password")
            or path.startswith("/admin/") or path.startswith("/account/"))


def handle(handler) -> bool:
    """认识 ``/admin*``、``/account*`` 时返回 ``True``。"""
    parts = urlsplit(handler.path)
    path = parts.path
    if not _owns(path):
        return False
    query = parse_qs(parts.query, keep_blank_values=True)
    try:
        if path == "/admin" or path.startswith("/admin/"):
            return _handle_admin(handler, path, query)
        return _handle_account(handler, path, query)
    except web._TooLarge:
        _too_large(handler, "account" if path.startswith("/account") else "admin")
        return True


def _handle_admin(handler, path: str, query: dict) -> bool:
    from . import admin_api
    # 静态资源不需要会话（只是构建产物，没有数据）；SPA/JSON 自带 401/403 语义。
    if path.startswith("/admin/assets/"):
        admin_api.serve_asset(handler, path)
        return True
    if path.startswith("/admin/api/"):
        return admin_api.handle(handler, path)
    session, secret = _admin_session(handler, path, query)
    if session is None:
        return True
    if handler.command == "POST":
        if not web._same_origin(handler):
            web._error_page(handler, 403, "跨站请求被拒绝")
            return True
        form = _read_form(handler, ADMIN_FORM_LIMIT)
        if not auth.csrf_verify(secret, session["sid"],
                                web._scalar(form, "_csrf")):
            web._error_page(handler, 403, "CSRF 校验失败")
            return True
        _admin_post(handler, path, query, session, secret, form)
        return True
    if handler.command not in ("GET", "HEAD"):
        web._method_not_allowed(handler, "GET, POST")
        return True
    _admin_get(handler, path, query, session, secret)
    return True


def _handle_account(handler, path: str, query: dict) -> bool:
    session, secret = _account_session(handler, path)
    if session is None:
        return True
    if handler.command == "POST":
        if not web._same_origin(handler):
            _account_error(handler, 403, "跨站请求被拒绝")
            return True
        form = _read_form(handler, web.FORM_LIMIT)
        if not auth.csrf_verify(secret, session["sid"],
                                web._scalar(form, "_csrf")):
            _account_error(handler, 403, "CSRF 校验失败")
            return True
        if path not in ("/account", "/account/password"):
            _not_found(handler, nav="account")
            return True
        _account_post(handler, path, session, form)
        return True
    if handler.command not in ("GET", "HEAD"):
        web._method_not_allowed(handler, "GET, POST")
        return True
    if path not in ("/account", "/account/password"):
        _not_found(handler, nav="account")
        return True
    _account_page(handler, path, query, session, secret)
    return True


# ------------------------------------------------------------------ GET 路由
def _admin_get(handler, path: str, query: dict, session: dict,
               secret: str) -> None:
    """``/admin`` 只走 SPA；旧 SSR 页面 302 到 hash 路由，``/admin/legacy/...`` 保留一个版本。"""
    from . import admin_api
    if path == "/admin":
        admin_api.serve_index(handler)
        return
    if path == LEGACY_PREFIX or path.startswith(LEGACY_PREFIX + "/"):
        _legacy_get(handler, path, query, session, secret)
        return
    target = SPA_ROUTES.get(path)
    if target is None:
        for pattern, prefix in SPA_DETAIL_ROUTES:
            match = pattern.fullmatch(path)
            if match:
                target = prefix + match.group(1)
                break
    if target is not None:
        web._redirect(handler, target)
        return
    _not_found(handler)


def _legacy_get(handler, path: str, query: dict, session: dict, secret: str) -> None:
    """``/admin/legacy/<old-path>``：一个版本的回退入口。"""
    if path == LEGACY_PREFIX + "/":
        web._redirect(handler, LEGACY_PREFIX, 301)
        return
    sub = "/admin" + path[len(LEGACY_PREFIX):]
    if not _is_legacy_page(sub):
        _not_found(handler)
        return
    handler._admin_legacy = True
    _admin_get_legacy(handler, sub, query, session, secret)


def _is_legacy_page(path: str) -> bool:
    if path in SPA_ROUTES or path == "/admin":
        return True
    return any(pattern.fullmatch(path) for pattern, _prefix in SPA_DETAIL_ROUTES)


def _admin_get_legacy(handler, path: str, query: dict, session: dict,
                      secret: str) -> None:
    if path == "/admin":
        _dashboard(handler, query, session, secret)
    elif path == "/admin/users":
        _users_page(handler, query, session, secret)
    elif re.fullmatch(r"/admin/users/\d+", path):
        _user_page(handler, int(path.rsplit("/", 1)[1]), query, session, secret)
    elif path in ("/admin/keys", "/admin/tokens", "/admin/static-keys"):
        _keys_page(handler, query, session, secret)
    elif path in ("/admin/clients", "/admin/oauth", "/admin/oauth-clients"):
        _clients_page(handler, query, session, secret)
    elif re.fullmatch(r"/admin/clients/[A-Za-z0-9_-]{1,120}", path):
        _client_page(handler, path.rsplit("/", 1)[1], query, session, secret)
    elif path == "/admin/sessions":
        _sessions_page(handler, query, session, secret)
    elif path == "/admin/audit":
        _audit_page(handler, query, session, secret)
    elif path in ("/admin/security", "/admin/totp"):
        _security_page(handler, query, session, secret)
    elif path == "/admin/settings":
        _settings_page(handler, query, session, secret)
    else:
        _not_found(handler)


def _dashboard(handler, query, session, secret) -> None:
    with hdb.db(0) as con:
        users = auth.list_users(con)
        keys = auth.static_token_list(con)
        clients = oauth.list_clients(con)
        sessions = auth.list_sessions(con)
        last_sync = auth.settings_get(con, "last_sync")
        last_ingest = auth.settings_get(con, "last_ingest")
        backfill = auth.settings_get(con, "backfill_state")
        interval = auth.settings_get(
            con, "fetch_interval_override",
            os.environ.get("FETCH_INTERVAL_SEC", "900"))
        now = int(time.time())
        active_sessions = [
            item for item in sessions
            if item["revoked_at"] is None
            and int(item["expires_at"]) > now
        ]
    users_by_id = {int(u["id"]): u for u in users}
    try:
        selected = int((query.get("owner") or ["0"])[0])
    except (TypeError, ValueError):
        selected = 0
    if selected not in users_by_id:
        selected = 0
    mode = users_by_id[selected]["data_mode"] if selected else "admin"
    effective_owner = selected if selected and mode == "own" else 0
    if effective_owner:
        last_sync = _partition_state(effective_owner, "last_sync", last_sync)
        last_ingest = _partition_state(
            effective_owner, "last_ingest", last_ingest)
        backfill = _partition_state(
            effective_owner, "backfill_state", backfill)
    creds_state, creds_mtime = _creds_state(effective_owner)
    if selected and mode == "shared":
        creds_label = f"共享（admin 凭据：{creds_state}，mtime {creds_mtime}）"
    else:
        creds_label = f"{creds_state}（mtime {creds_mtime}）"
    stats = _partition_stats(effective_owner)
    version = os.environ.get("HCB_VERSION", "1.0")
    if not selected:
        partition_label = "admin（owner 0）"
    elif mode == "shared":
        partition_label = (f'{users_by_id[selected]["username"]} '
                           f'(id {selected}) — 共享 admin 分区')
    else:
        partition_label = f'{users_by_id[selected]["username"]} (id {selected})'

    try:
        interval_seconds = int(interval)
    except (TypeError, ValueError):
        interval_seconds = 0
    next_sync = None
    try:
        if last_sync not in (None, "") and interval_seconds > 0:
            next_sync = int(last_sync) + interval_seconds
    except (TypeError, ValueError):
        next_sync = None
    service = views.table(("项目", "值"), [
        ["版本", version],
        ["进程启动", _fmt_ts(PROCESS_STARTED)],
        ["站点", web.base_url(handler) or "—"],
        ["当前分区", partition_label],
        ["数据库", hdb.db_path(effective_owner) if effective_owner
         else hdb.health_db_path()],
        ["抓取间隔", f"{interval} 秒"],
        ["最后同步", _fmt_ts(last_sync)],
        ["下次抓取（估算）", _fmt_ts(next_sync)],
        ["最后 ingest", _fmt_json(last_ingest, 120)],
        ["回补状态", _fmt_json(backfill, 200)],
        ["凭据", creds_label],
    ])
    counts = views.table(("项目", "数量"), [
        ["用户", f"{len(users)}（启用 {sum(1 for u in users if u['enabled'])}）"],
        ["有效静态密钥", str(len(keys))],
        ["有效 OAuth 客户端", str(len(clients))],
        ["有效会话", str(len(active_sessions))],
    ])
    data_rows = [[name, count, lo, hi] for name, count, lo, hi in stats]
    data = views.table(("表", "条数", "最早", "最新"), data_rows,
                       empty="admin 分区还没有业务数据。")
    csrf = auth.csrf_token(secret, session["sid"])
    if not selected or mode == "own":
        partition_section = views.raw("<h2>数据操作</h2>") \
            + _partition_forms("/admin", effective_owner, csrf)
    else:
        partition_section = views.raw(
            '<h2>数据操作</h2><p class="muted">共享用户使用 admin 分区；'
            "请切换到 admin 后执行凭据/同步操作。</p>")
    warning = views.raw("")
    if not keys and not clients:
        warning = views.raw(
            '<p class="warn">当前没有任何有效静态密钥或 OAuth 客户端；'
            'MCP 客户端将无法连接（旧环境变量令牌若仍配置则继续可用）。</p>')
    selector = views.raw(
        '<form method="get" action="/admin">'
        + str(views.field("查看分区", views.select(
            "owner", [("0", "admin（owner 0）")]
            + [(str(uid), f'{u["username"]} (id {uid})')
               for uid, u in sorted(users_by_id.items())], str(selected))))
        + '<p><button type="submit">切换分区</button></p></form>'
    )
    body = views.raw(
        "<p>服务状态与所选分区的数据覆盖。共享用户看同一份数据；"
        "独立用户有各自的分区。</p>"
    ) + selector + service + views.raw("<h2>账号与令牌</h2>") + counts \
        + partition_section + warning \
        + views.raw("<h2>数据覆盖</h2>") + data \
        + views.raw('<p class="muted">动作入口：'
                    '<a href="/admin/users">用户</a> · '
                    '<a href="/admin/keys">静态密钥</a> · '
                    '<a href="/admin/clients">OAuth 客户端</a> · '
                    '<a href="/admin/sessions">会话</a></p>')
    _page(handler, "概览", body, session, secret, query=query)


def _users_page(handler, query, session, secret) -> None:
    with hdb.db(0) as con:
        users = auth.list_users(con)
        key_counts = {}
        for row in con.execute(
                "SELECT owner_user_id AS uid, COUNT(*) AS n FROM agent_tokens "
                "WHERE revoked_at IS NULL GROUP BY owner_user_id").fetchall():
            key_counts[int(row["uid"])] = int(row["n"])
    rows = []
    for user in users:
        rows.append([
            user["id"],
            views.link(f"/admin/users/{user['id']}", user["username"]),
            user["display_name"],
            "user",
            ", ".join(user["scopes"]) or "—",
            user["data_mode"],
            "启用" if user["enabled"] else "停用",
            key_counts.get(int(user["id"]), 0),
            _fmt_ts(user["created_at"]),
        ])
    listing = views.table(
        ("ID", "用户名", "显示名", "角色", "Scope 上限", "数据模式",
         "状态", "有效密钥", "创建"),
        rows, empty="还没有普通用户。")
    create = views.form("/admin/users", [
        views.hidden("action", "create"),
        views.field("用户名", views.text_input(
            "username", required=True, autocomplete="off",
            placeholder="3-32 位 a-z0-9._-")),
        views.field("显示名", views.text_input("display_name")),
        views.field("角色", views.select("role", [("user", "普通用户")], "user"),
                    "当前版本只有内置管理员一个 admin 账号。"),
        views.field("初始密码", views.password_input(
            "password", autocomplete="new-password"),
            f"至少 {MIN_PASSWORD} 位。"),
        views.hidden("scope_present", "1"),
        views.group("Scope 上限", _scope_inputs(["health.read"]),
                    "至少选择一个；默认只读。health.write 允许触发同步。"),
        views.field("数据模式", views.select("data_mode", [
            ("shared", "共享（管理员的数据与凭据）"),
            ("own", "独立（自己的分区与凭据）"),
        ], "shared")),
        views.hidden("enabled_present", "1"),
        views.checkbox("enabled", "1", "创建后立即启用", checked=True),
        views.field("工具权限 JSON（留空 = 不限制）", views.textarea(
            "tool_policy", "", rows=3,
            placeholder='["query_heart_rate", {"tool": "query_metric_series", "args": {"metric": "steps"}}]')),
        views.field("时间窗 JSON（留空 = 不限时）", views.textarea(
            "window", "", rows=3,
            placeholder='{"tz":"Asia/Shanghai","start":"08:00","end":"22:00"}')),
    ], auth.csrf_token(secret, session["sid"]), submit="创建用户")
    body = listing + views.raw("<h2>新建用户</h2>") + create
    _page(handler, "用户", body, session, secret, query=query)


def _user_page(handler, user_id: int, query, session, secret) -> None:
    with hdb.db(0) as con:
        user = auth.get_user(con, user_id)
        if user is None:
            _not_found(handler)
            return
        user = dict(user)
        keys = [t for t in auth.static_token_list(con, include_revoked=True)
                if int(t["owner_user_id"]) == user_id]
        sessions = auth.list_sessions(con, subject=auth.subject_for_user(user_id))
    if user["data_mode"] == "own":
        creds_state, creds_mtime = _creds_state(user_id)
        creds_label = f"{creds_state}（mtime {creds_mtime}）"
    else:
        creds_state, creds_mtime = _creds_state(0)
        creds_label = f"共享（admin 凭据：{creds_state}，mtime {creds_mtime}）"
    info = views.table(("项目", "值"), [
        ["ID", user["id"]],
        ["用户名", user["username"]],
        ["显示名", user["display_name"]],
        ["角色", "user"],
        ["Scope 上限", ", ".join(user["scopes"]) or "—"],
        ["数据模式", user["data_mode"]],
        ["状态", "启用" if user["enabled"] else "停用"],
        ["revision", user["revision"]],
        ["工具权限", _policy_summary(user.get("tool_policy_json"))],
        ["时间窗", _window_summary(user.get("window_json"))],
        ["凭据", creds_label],
        ["创建", _fmt_ts(user["created_at"])],
        ["更新", _fmt_ts(user["updated_at"])],
    ])
    csrf = auth.csrf_token(secret, session["sid"])
    if user["data_mode"] == "own":
        partition_section = views.raw("<h2>数据操作</h2>") \
            + _partition_forms(f"/admin/users/{user_id}", user_id, csrf)
    else:
        partition_section = views.raw(
            '<h2>数据操作</h2><p class="muted">共享用户使用 admin 分区。'
            "可先导入自己的凭据，再在编辑区切换到独立；同步/回补在切到独立后可用。</p>") \
            + _partition_forms(f"/admin/users/{user_id}", user_id, csrf,
                               sync=False)
    edit = views.form(f"/admin/users/{user_id}", [
        views.hidden("action", "update"),
        views.field("显示名", views.text_input(
            "display_name", user["display_name"])),
        views.group("Scope 上限", _scope_inputs(user["scopes"])),
        views.field("数据模式", views.select("data_mode", [
            ("shared", "共享（管理员的数据）"),
            ("own", "独立（自己的分区）"),
        ], user["data_mode"]),
            "切到独立前请确认该用户已配置自己的小米凭据；共享会看到管理员的数据。"),
        views.hidden("scope_present", "1"),
        views.hidden("enabled_present", "1"),
        views.checkbox("enabled", "1", "启用", checked=user["enabled"]),
        views.field("工具权限 JSON（留空 = 不限制）", views.textarea(
            "tool_policy", user.get("tool_policy_json") or "", rows=4)),
        views.field("时间窗 JSON（留空 = 不限时）", views.textarea(
            "window", user.get("window_json") or "", rows=4)),
    ], csrf, submit="保存修改")
    reset = views.form(f"/admin/users/{user_id}", [
        views.hidden("action", "reset_password"),
        views.field("新密码", views.password_input(
            "password", autocomplete="new-password"),
            f"至少 {MIN_PASSWORD} 位；旧会话会立即失效。"),
    ], csrf, submit="重置密码")
    toggle_action = "disable" if user["enabled"] else "enable"
    toggle_label = "停用用户" if user["enabled"] else "启用用户"
    toggle = views.form(f"/admin/users/{user_id}", [
        views.hidden("action", toggle_action),
    ], csrf, submit=toggle_label)
    delete = views.form(f"/admin/users/{user_id}", [
        views.hidden("action", "delete"),
        views.field("输入用户名确认", views.text_input(
            "confirm", required=True, autocomplete="off",
            placeholder=user["username"])),
    ], csrf, submit="永久删除用户", cls="danger")
    key_rows = []
    for token in keys:
        key_rows.append([
            token["label"],
            "启用" if token["revoked_at"] is None else "已撤销",
            _policy_summary(token["tool_policy_json"]),
            _window_summary(token["window_json"]),
            _fmt_ts(token["created_at"]),
            _fmt_ts(token["last_used_at"]),
        ])
    keys_table = views.table(("标签", "状态", "工具权限", "时间窗", "创建", "最近使用"),
                             key_rows, empty="该用户没有静态密钥。")
    partition_owner = user_id if user["data_mode"] == "own" else 0
    data_rows = [[name, count, lo, hi] for name, count, lo, hi in
                 _partition_stats(partition_owner)]
    data_note = ("该用户独立分区。" if user["data_mode"] == "own"
                 else "共享用户使用 admin 分区（owner 0）。")
    data = views.raw(f'<p class="muted">{views.esc(data_note)}</p>') \
        + views.table(("表", "条数", "最早", "最新"), data_rows,
                      empty="该分区还没有数据。")
    body = info + partition_section + views.raw("<h2>编辑</h2>") + edit \
        + views.raw("<h2>重置密码</h2>") + reset \
        + views.raw("<h2>状态与删除</h2>") + toggle + delete \
        + views.raw("<h2>静态密钥</h2>") + keys_table \
        + views.raw("<h2>该用户的活动会话</h2>") + views.table(
            ("创建", "最近活动", "过期", "状态", "UA"),
            [[_fmt_ts(s["created_at"]), _fmt_ts(s["last_seen_at"]),
              _fmt_ts(s["expires_at"]),
              ("已过期" if s["revoked_at"] is None
               and int(s["expires_at"]) <= int(time.time()) else
               "有效" if s["revoked_at"] is None else "已吊销"),
              (s["user_agent"] or "")[:60]] for s in sessions],
            empty="没有活动会话。") \
        + views.raw("<h2>该用户的分区</h2>") + data \
        + views.raw('<p><a href="/admin/users">返回用户列表</a></p>')
    _page(handler, f"用户 {user['username']}", body, session, secret, query=query)


def _keys_page(handler, query, session, secret) -> None:
    with hdb.db(0) as con:
        users = auth.list_users(con)
        tokens = auth.static_token_list(con, include_revoked=True)
    users_by_id = {int(u["id"]): u for u in users}
    csrf = auth.csrf_token(secret, session["sid"])
    rows = []
    for token in tokens:
        revoke = views.raw("")
        if token["revoked_at"] is None:
            revoke = views.form("/admin/keys", [
                views.hidden("action", "revoke"),
                views.hidden("token_id", token["id"]),
            ], csrf, submit="撤销")
        rows.append([
            token["label"],
            _owner_label(token["owner_user_id"], users_by_id),
            token["origin"],
            _fmt_ts(token["created_at"]),
            _fmt_ts(token["last_used_at"]),
            "有效" if token["revoked_at"] is None else "已撤销",
            _policy_summary(token["tool_policy_json"]),
            _window_summary(token["window_json"]),
            revoke,
        ])
    listing = views.table(
        ("标签", "所有者", "来源", "创建", "最近使用", "状态",
         "工具权限", "时间窗", "操作"),
        rows, empty="还没有静态密钥。")
    create = views.form("/admin/keys", [
        views.hidden("action", "create"),
        views.field("标签", views.text_input(
            "label", required=True, autocomplete="off",
            placeholder="phone / termux / mcp-client")),
        views.field("所有者", views.select(
            "owner_user_id", _user_options(users), "0"),
            "静态密钥按所有者分区读写；共享用户仍写 admin 分区。"),
        views.field("工具权限 JSON（留空 = 不限制）", views.textarea(
            "tool_policy", "", rows=3)),
        views.field("时间窗 JSON（留空 = 不限时）", views.textarea(
            "window", "", rows=3)),
    ], csrf, submit="创建静态密钥")
    body = views.raw(
        "<p>完整令牌只在创建成功时显示一次；数据库只保存 SHA-256。"
        "停用或删除所有者会撤销其全部静态密钥。</p>"
    ) + listing + views.raw("<h2>新建静态密钥</h2>") + create
    _page(handler, "静态密钥", body, session, secret, query=query)


def _client_rows(con) -> tuple[dict, dict]:
    tokens = {}
    scopes = {}
    now = int(time.time())
    for row in con.execute(
            "SELECT client_id, scopes_json FROM oauth_tokens "
            "WHERE revoked_at IS NULL AND kind='access' AND expires_at > ?",
            (now,)).fetchall():
        cid = row["client_id"]
        tokens[cid] = tokens.get(cid, 0) + 1
        try:
            parsed = json.loads(row["scopes_json"])
        except (json.JSONDecodeError, TypeError):
            parsed = []
        bucket = scopes.setdefault(cid, set())
        if isinstance(parsed, list):
            bucket.update(str(s) for s in parsed)
    return tokens, scopes


def _clients_page(handler, query, session, secret) -> None:
    with hdb.db(0) as con:
        clients = oauth.list_clients(con, include_revoked=True)
        token_counts, token_scopes = _client_rows(con)
    csrf = auth.csrf_token(secret, session["sid"])
    rows = []
    for client in clients:
        cid = client["client_id"]
        revoke = views.raw("")
        if not client["revoked"]:
            revoke = views.form("/admin/clients", [
                views.hidden("action", "revoke"),
                views.hidden("client_id", cid),
                views.text_input("confirm", "", required=True,
                                 placeholder=client["client_name"]),
            ], csrf, submit="撤销应用")
        redirects = ", ".join(client["redirect_uris"]) or "—"
        rows.append([
            views.link(f"/admin/clients/{cid}", client["client_name"]),
            views.code(cid),
            "机密" if client["confidential"] else "公开",
            redirects[:120],
            ", ".join(sorted(token_scopes.get(cid, ()))) or "—",
            token_counts.get(cid, 0),
            _fmt_ts(client["created_at"]),
            _fmt_ts(client["last_used_at"]),
            "有效" if not client["revoked"] else "已撤销",
            revoke,
        ])
    listing = views.table(
        ("名称", "client_id", "类型", "redirect URI", "有效令牌 scope",
         "令牌数", "创建", "最近使用", "状态", "操作"),
        rows, empty="还没有 OAuth 客户端。")
    create = views.form("/admin/clients", [
        views.hidden("action", "create"),
        views.field("名称", views.text_input(
            "client_name", required=True, autocomplete="off")),
        views.field("Redirect URI（每行一个）", views.textarea(
            "redirect_uris", "", rows=3,
            placeholder="https://client.example/callback")),
        views.checkbox("confidential", "1",
                       "机密客户端（client_secret_post）"),
        views.field("工具权限 JSON（留空 = 不限制）", views.textarea(
            "tool_policy", "", rows=3)),
        views.field("时间窗 JSON（留空 = 不限时）", views.textarea(
            "window", "", rows=3)),
    ], csrf, submit="创建客户端")
    body = views.raw(
        "<p>动态注册的应用也会出现在这里。机密客户端的 client_secret "
        "只在创建成功时显示一次；之后无法再次查看。</p>"
    ) + listing + views.raw("<h2>新建 OAuth 客户端</h2>") + create
    _page(handler, "OAuth 客户端", body, session, secret, query=query)


def _client_page(handler, client_id: str, query, session, secret) -> None:
    with hdb.db(0) as con:
        client = oauth.get_client(con, client_id)
        if client is None:
            _not_found(handler)
            return
        tokens = [dict(row) for row in con.execute(
            "SELECT id, prefix, kind, user_id, username, scopes_json, created_at, "
            "expires_at, last_used_at, revoked_at FROM oauth_tokens "
            "WHERE client_id=? ORDER BY id DESC LIMIT 100",
            (client_id,)).fetchall()]
        users = auth.list_users(con)
    users_by_id = {int(u["id"]): u for u in users}
    csrf = auth.csrf_token(secret, session["sid"])
    info = views.table(("项目", "值"), [
        ["名称", client["client_name"]],
        ["client_id", client["client_id"]],
        ["类型", "机密（client_secret_post）" if client["confidential"] else "公开"],
        ["redirect URI", ", ".join(client["redirect_uris"]) or "—"],
        ["工具权限上限", _policy_summary(client["tool_policy_json"])],
        ["时间窗上限", _window_summary(client["window_json"])],
        ["创建", _fmt_ts(client["created_at"])],
        ["最近使用", _fmt_ts(client["last_used_at"])],
        ["状态", "有效" if not client["revoked"] else "已撤销"],
    ])
    token_rows = []
    now = int(time.time())
    for token in tokens:
        try:
            scopes = ", ".join(str(s) for s in json.loads(token["scopes_json"]))
        except (json.JSONDecodeError, TypeError):
            scopes = "—"
        expired = (token["revoked_at"] is None
                   and int(token["expires_at"]) <= now)
        status = ("已过期" if expired else
                  "有效" if token["revoked_at"] is None else "已撤销")
        token_rows.append([
            views.code(token["prefix"]),
            token["kind"],
            _owner_label(token["user_id"], users_by_id),
            scopes or "—",
            _fmt_ts(token["expires_at"]),
            _fmt_ts(token["last_used_at"]),
            status,
        ])
    tokens_table = views.table(
        ("前缀", "类型", "主体", "scope", "过期", "最近使用", "状态"),
        token_rows, empty="该客户端还没有令牌。")
    actions = []
    if not client["revoked"]:
        actions.append(views.form("/admin/clients", [
            views.hidden("action", "revoke_tokens"),
            views.hidden("client_id", client["client_id"]),
        ], csrf, submit="撤销全部令牌"))
        actions.append(views.form("/admin/clients", [
            views.hidden("action", "revoke"),
            views.hidden("client_id", client["client_id"]),
            views.field("输入应用名称确认", views.text_input(
                "confirm", "", required=True,
                placeholder=client["client_name"])),
        ], csrf, submit="删除应用", cls="danger"))
    body = info + views.raw("<h2>令牌</h2>") + tokens_table \
        + views.raw("<h2>操作</h2>") + views.raw("".join(str(a) for a in actions)) \
        + views.raw('<p><a href="/admin/clients">返回客户端列表</a></p>')
    _page(handler, f"客户端 {client['client_name']}", body, session, secret, query=query)


def _subject_label(subject: object, users_by_id: dict[int, dict]) -> str:
    if subject == auth.ADMIN_SUBJECT:
        return "admin（管理员）"
    uid = auth.user_id_for_subject(subject)
    if uid is None:
        return str(subject)
    user = users_by_id.get(uid)
    return f'{user["username"]} (id {uid})' if user else f"user:{uid}"


def _sessions_page(handler, query, session, secret) -> None:
    with hdb.db(0) as con:
        sessions = auth.list_sessions(con, include_revoked=True)
        users = auth.list_users(con)
    users_by_id = {int(u["id"]): u for u in users}
    csrf = auth.csrf_token(secret, session["sid"])
    current = auth.hash_token(session["sid"])
    now = int(time.time())
    rows = []
    for item in sessions:
        expired = (item["revoked_at"] is None
                   and int(item["expires_at"]) <= now)
        active = item["revoked_at"] is None and not expired
        revoke = views.raw("")
        if active and item["sid_hash"] != current:
            revoke = views.form("/admin/sessions", [
                views.hidden("action", "revoke"),
                views.hidden("sid_hash", item["sid_hash"]),
            ], csrf, submit="吊销")
        status = ("已过期" if expired else
                  "有效" if item["revoked_at"] is None else "已吊销")
        rows.append([
            _subject_label(item["subject"], users_by_id),
            "管理员" if item["subject"] == auth.ADMIN_SUBJECT else "用户",
            _fmt_ts(item["created_at"]),
            _fmt_ts(item["last_seen_at"]),
            _fmt_ts(item["expires_at"]),
            status,
            (item["user_agent"] or "")[:60],
            revoke,
        ])
    listing = views.table(
        ("主体", "类型", "创建", "最近活动", "过期", "状态", "UA", "操作"),
        rows, empty="没有会话。")
    subjects = sorted({item["subject"] for item in sessions
                       if item["revoked_at"] is None
                       and int(item["expires_at"]) > now})
    subject_options = [("", "选择主体")] + [
        (subject, _subject_label(subject, users_by_id))
        for subject in subjects
    ]
    revoke_subject = views.form("/admin/sessions", [
        views.hidden("action", "revoke_subject"),
        views.field("主体", views.select("subject", subject_options, "")),
    ], csrf, submit="吊销该主体全部会话")
    revoke_others = views.form("/admin/sessions", [
        views.hidden("action", "revoke_others"),
    ], csrf, submit="吊销除当前外全部会话")
    body = views.raw(
        "<p>管理员会话 TTL 4 小时，普通用户 12 小时；每次请求都会校验"
        "未吊销、未过期。</p>"
    ) + listing + views.raw("<h2>批量吊销</h2>") + revoke_subject + revoke_others
    _page(handler, "会话", body, session, secret, query=query)


def _audit_page(handler, query, session, secret) -> None:
    actor = (query.get("actor") or [""])[0][:80]
    action = (query.get("action") or [""])[0][:80]
    result = (query.get("result") or [""])[0][:40]
    rows = []
    status = None
    error = None
    try:
        from . import audit as audit_mod
        with hdb.db(0) as con:
            rows = audit_mod.list_recent(
                con, limit=200, actor=actor or None, action=action or None,
                result=result or None)
            status = audit_mod.verify(con)
    except (sqlite3.OperationalError, ImportError) as exc:
        error = type(exc).__name__
    table_rows = []
    for row in rows:
        table_rows.append([
            row["id"], _fmt_ts(row["created_at"]), row["actor"], row["kind"],
            row["action"], row["tool"] or "—", row["result"] or "—",
        ])
    filters = views.raw(
        '<form method="get" action="/admin/audit" class="filters">'
        + str(views.field("Actor", views.text_input("actor", actor)))
        + str(views.field("Action", views.text_input("action", action)))
        + str(views.field("Result", views.text_input("result", result)))
        + '<p><button type="submit">过滤</button></form>'
    )
    body = views.raw(
        "<p>最近 200 条访问/管理记录，只读；只显示元数据，不显示工具参数、"
        "令牌或健康数据。</p>"
    )
    if status is not None:
        if status["ok"]:
            body += views.raw(
                f'<p class="ok">哈希链校验通过：{status["rows"]} 行，'
                '链头与锚点一致。</p>')
        else:
            bad = "" if status.get("first_bad_id") is None else \
                f'（首个异常 id={status["first_bad_id"]}）'
            body += views.raw(
                '<p class="err">哈希链校验失败'
                f'{views.esc(bad)}：{views.esc(str(status.get("error")))}</p>')
    body += filters
    if error:
        body += views.raw(f'<p class="err">审计表暂不可用（{views.esc(error)}）。</p>')
    else:
        body += views.table(
            ("ID", "时间", "Actor", "Kind", "Action", "Tool", "Result"),
            table_rows, empty="没有匹配的审计记录。")
    _page(handler, "审计", body, session, secret, query=query)


def _otp_uri(secret: str) -> str:
    label = quote(f"{TOTP_ISSUER}:admin", safe="")
    return "otpauth://totp/" + label + "?" + urlencode({
        "secret": secret,
        "issuer": TOTP_ISSUER,
        "algorithm": "SHA1",
        "digits": 6,
        "period": 30,
    })


def _password_fields() -> list:
    return [
        views.field("当前密码", views.password_input("current_password")),
        views.field("新密码", views.password_input(
            "new_password", autocomplete="new-password"),
            f"至少 {MIN_PASSWORD} 位。"),
        views.field("确认新密码", views.password_input(
            "confirm_password", autocomplete="new-password")),
    ]


def _security_page(handler, query, session, secret) -> None:
    with hdb.db(0) as con:
        state = auth.admin_totp_state(con)
        remaining = auth.admin_recovery_remaining(con)
    csrf = auth.csrf_token(secret, session["sid"])
    info = views.table(("项目", "状态"), [
        ["两步验证", "已启用" if state["enabled"] else "未启用"],
        ["恢复码剩余", str(remaining) if state["enabled"] else "—"],
    ])
    body = info
    if state["enabled"]:
        body += views.raw("<h2>关闭两步验证</h2>"
                          "<p>需要当前密码与一个动态码（或恢复码）。</p>") \
            + views.form("/admin/security", [
                views.hidden("action", "totp_disable"),
                views.field("当前密码", views.password_input("current_password")),
                views.field("动态码或恢复码", views.text_input(
                    "code", required=True, inputmode="numeric",
                    autocomplete="one-time-code")),
            ], csrf, submit="关闭两步验证")
        body += views.raw("<h2>重新生成恢复码</h2>"
                          "<p>需要当前密码与一个动态码（或恢复码）。</p>") \
            + views.form("/admin/security", [
                views.hidden("action", "recovery_generate"),
                views.field("当前密码", views.password_input("current_password")),
                views.field("动态码或恢复码", views.text_input(
                    "code", required=True, inputmode="numeric",
                    autocomplete="one-time-code")),
            ], csrf, submit="生成新恢复码")
    elif state["secret"]:
        body += views.raw(
            "<h2>确认新密钥</h2>"
            '<p class="warn">下面的密钥只显示在本页。请现在录入身份验证器，'
            "然后输入当前密码与一个 6 位动态码。确认成功后会生成 10 个恢复码。</p>"
        ) + views.pre(state["secret"]) \
            + views.raw("<p>otpauth URI：</p>") + views.pre(_otp_uri(state["secret"])) \
            + views.form("/admin/security", [
                views.hidden("action", "totp_confirm"),
                views.field("当前密码", views.password_input("current_password")),
                views.field("动态码", views.text_input(
                    "code", required=True, inputmode="numeric",
                    autocomplete="one-time-code")),
            ], csrf, submit="确认并启用") \
            + views.raw("<h2>重新生成待确认密钥</h2>"
                        "<p>丢弃当前未确认的密钥，生成新的密钥。</p>") \
            + views.form("/admin/security", [
                views.hidden("action", "totp_prepare"),
                views.field("当前密码", views.password_input("current_password")),
            ], csrf, submit="重新生成密钥")
    else:
        body += views.raw(
            "<h2>启用两步验证</h2>"
            "<p>需要当前密码。生成密钥后，用身份验证器录入；"
            "确认一个动态码后才算启用。</p>"
        ) + views.form("/admin/security", [
            views.hidden("action", "totp_prepare"),
            views.field("当前密码", views.password_input("current_password")),
        ], csrf, submit="生成 TOTP 密钥")
    body += views.raw("<h2>修改管理员密码</h2>") \
        + views.form("/admin/security", [
            views.hidden("action", "change_password"),
            *_password_fields(),
        ], csrf, submit="修改密码")
    _page(handler, "安全", body, session, secret, query=query)


def _settings_page(handler, query, session, secret) -> None:
    with hdb.db(0) as con:
        interval = auth.settings_get(con, "fetch_interval_override") or \
            os.environ.get("FETCH_INTERVAL_SEC", "900")
    csrf = auth.csrf_token(secret, session["sid"])
    interval_form = views.form("/admin/settings", [
        views.hidden("action", "set_interval"),
        views.field("抓取间隔（秒）", views.text_input(
            "fetch_interval", interval, inputmode="numeric"),
            f"允许范围 {FETCH_MIN}–{FETCH_MAX}；后台抓取循环下一轮读取。"),
    ], csrf, submit="保存抓取间隔")
    password_form = views.form("/admin/settings", [
        views.hidden("action", "change_password"),
        *_password_fields(),
    ], csrf, submit="修改管理员密码")
    info = views.table(("项目", "值"), [
        ["PUBLIC_BASE_URL", os.environ.get("PUBLIC_BASE_URL") or "（未设置，按请求推导）"],
        ["数据库", hdb.health_db_path()],
        ["版本", os.environ.get("HCB_VERSION", "1.0")],
        ["管理员密码", "已配置" if web._admin_configured() else "未配置"],
    ])
    body = views.raw("<h2>同步</h2>") + interval_form \
        + views.raw("<h2>管理员密码</h2>") + password_form \
        + views.raw("<h2>只读信息</h2>") + info
    _page(handler, "设置", body, session, secret, query=query)


def _account_page(handler, path: str, query, session, secret) -> None:
    with hdb.db(0) as con:
        if session["is_admin"]:
            user = None
            totp_state = auth.admin_totp_state(con)
        else:
            user = auth.get_user(con, session["user_id"])
            if user is None:
                web._redirect(handler, "/login")
                return
            user = dict(user)
            totp_state = None
        sessions = auth.list_sessions(con, subject=session["subject"])
    csrf = auth.csrf_token(secret, session["sid"])
    username = auth.ADMIN_SUBJECT if user is None else user["username"]
    info_rows = [
        ["用户名", username],
        ["角色", "管理员" if user is None else "普通用户"],
    ]
    if user is not None:
        info_rows.extend([
            ["显示名", user["display_name"]],
            ["Scope 上限", ", ".join(user["scopes"]) or "—"],
            ["数据模式", "共享（管理员的数据）" if user["data_mode"] == "shared"
             else "独立（自己的分区）"],
            ["状态", "启用" if user["enabled"] else "停用"],
            ["创建", _fmt_ts(user["created_at"])],
        ])
    else:
        info_rows.append(
            ["两步验证", "已启用" if totp_state["enabled"] else "未启用"])
    body = views.table(("项目", "值"), info_rows)
    if user is not None:
        body += views.raw("<h2>显示名</h2>") + views.form("/account", [
            views.hidden("action", "update_profile"),
            views.field("显示名", views.text_input(
                "display_name", user["display_name"])),
        ], csrf, submit="保存显示名")
    else:
        body += views.raw('<p>管理员设置（TOTP、管理密码）在'
                          '<a href="/admin/security">安全页</a>。</p>')
    body += views.raw("<h2>修改密码</h2>") + views.form("/account/password", [
        views.hidden("action", "change_password"),
        *_password_fields(),
    ], csrf, submit="修改密码")
    current = auth.hash_token(session["sid"])
    session_rows = []
    for item in sessions:
        revoke = views.raw("")
        if item["revoked_at"] is None and item["sid_hash"] != current:
            revoke = views.form("/account/password", [
                views.hidden("action", "revoke_session"),
                views.hidden("sid_hash", item["sid_hash"]),
            ], csrf, submit="吊销")
        session_rows.append([
            _fmt_ts(item["created_at"]),
            _fmt_ts(item["last_seen_at"]),
            _fmt_ts(item["expires_at"]),
            "有效" if item["revoked_at"] is None else "已吊销",
            (item["user_agent"] or "")[:60],
            revoke,
        ])
    body += views.raw("<h2>我的会话</h2>") + views.table(
        ("创建", "最近活动", "过期", "状态", "UA", "操作"),
        session_rows, empty="没有活动会话。")
    if user is None:
        body += views.raw('<p><a href="/admin">进入管理后台</a></p>')
    title = "修改密码" if path == "/account/password" else "我的账号"
    _page(handler, title, body, session, secret, nav="account", query=query)


# ------------------------------------------------------------------ POST 路由
def _admin_post(handler, path: str, query: dict, session: dict, secret: str,
                form: dict) -> None:
    """旧 SSR 表单端点：仍接受 POST（一个版本），响应留在 legacy 页面。"""
    handler._admin_legacy = True
    if path == LEGACY_PREFIX or path.startswith(LEGACY_PREFIX + "/"):
        path = "/admin" + path[len(LEGACY_PREFIX):]
    _admin_post_legacy(handler, path, query, session, secret, form)


def _admin_post_legacy(handler, path: str, query: dict, session: dict,
                       secret: str, form: dict) -> None:
    match = re.fullmatch(
        r"/admin/users/(\d+)/(delete|disable|enable|reset|reset_password)", path)
    if match:
        fields = dict(form)
        fields["action"] = match.group(2)
        _users_post(handler, session, fields, form_path=int(match.group(1)))
        return
    match = re.fullmatch(
        r"/admin/users/(\d+)/(creds|sync_now|backfill)", path)
    if match:
        fields = dict(form)
        fields["action"] = match.group(2)
        fields.setdefault("owner", match.group(1))
        _user_partition_action(
            handler, session, fields, int(match.group(1)))
        return
    match = re.fullmatch(r"/admin/keys/(\d+)/revoke", path)
    if match:
        fields = dict(form)
        fields["action"] = "revoke"
        fields["token_id"] = match.group(1)
        _keys_post(handler, session, fields)
        return
    match = re.fullmatch(r"/admin/clients/([A-Za-z0-9_-]{1,120})/revoke", path)
    if match:
        fields = dict(form)
        fields["action"] = "revoke"
        fields["client_id"] = match.group(1)
        _clients_post(handler, session, fields)
        return
    if path == "/admin":
        _dashboard_post(handler, session, form)
        return
    if path == "/admin/users":
        _users_post(handler, session, form, form_path=None)
    elif re.fullmatch(r"/admin/users/\d+", path):
        _users_post(handler, session, form,
                    form_path=int(path.rsplit("/", 1)[1]))
    elif path in ("/admin/keys", "/admin/tokens", "/admin/static-keys"):
        _keys_post(handler, session, form)
    elif path in ("/admin/clients", "/admin/oauth", "/admin/oauth-clients"):
        _clients_post(handler, session, form)
    elif path == "/admin/sessions":
        _sessions_post(handler, session, form)
    elif path == "/admin/security":
        _security_post(handler, session, form)
    elif path == "/admin/settings":
        _settings_post(handler, session, form)
    elif path == "/admin/totp":
        _security_post(handler, session, form)
    else:
        _not_found(handler)


def _users_post(handler, session, form, form_path: int | None) -> None:
    action = web._scalar(form, "action", "") or ""
    if form_path is None:
        if action != "create":
            _back(handler, "/admin/users", err="err_action")
            return
        _users_create(handler, session, form)
        return
    user_id = form_path
    if action == "update":
        _users_update(handler, session, form, user_id)
    elif action in ("reset_password", "reset"):
        _users_reset(handler, session, form, user_id)
    elif action in ("disable", "enable"):
        _users_toggle(handler, session, user_id, action == "enable")
    elif action == "delete":
        _users_delete(handler, session, form, user_id)
    elif action in ("creds", "sync_now", "backfill"):
        _user_partition_action(handler, session, form, user_id)
    else:
        _back(handler, f"/admin/users/{user_id}", err="err_action")


def _dashboard_post(handler, session, form) -> None:
    action = web._scalar(form, "action", "") or ""
    owner, error = _owner_for_form(form)
    if error:
        _back(handler, "/admin", err=error)
        return
    _partition_action(handler, session, form, owner=owner,
                      back=f"/admin?owner={owner}")


def _user_partition_action(handler, session, form, user_id: int) -> None:
    action = web._scalar(form, "action", "") or ""
    with hdb.db(0) as con:
        user = auth.get_user(con, user_id)
    if user is None:
        _back(handler, "/admin/users", err="err_not_found")
        return
    if action != "creds" and user["data_mode"] != "own":
        _back(handler, f"/admin/users/{user_id}", err="err_shared")
        return
    _partition_action(handler, session, form, owner=user_id,
                      back=f"/admin/users/{user_id}")


def _users_create(handler, session, form) -> None:
    username = (web._scalar(form, "username", "") or "").strip()
    display = (web._scalar(form, "display_name", "") or "").strip()
    role = (web._scalar(form, "role", "user") or "user").strip()
    password = web._scalar(form, "password", "") or ""
    scopes = _scope_set(form)
    data_mode = web._scalar(form, "data_mode", "shared") or "shared"
    enabled = (bool(form.get("enabled"))
               if "enabled_present" in form else True)
    policy_json, policy_err = _parse_policy_field(form)
    window_json, window_err = _parse_window_field(form)
    if role != "user":
        _back(handler, "/admin/users", err="err_role")
        return
    if not scopes:
        _back(handler, "/admin/users", err="err_scope")
        return
    if len(password) < MIN_PASSWORD:
        _back(handler, "/admin/users", err="err_short_password")
        return
    if policy_err or window_err:
        _back(handler, "/admin/users", err=policy_err or window_err)
        return
    if data_mode not in ("shared", "own"):
        _back(handler, "/admin/users", err="err_data_mode")
        return
    try:
        with hdb.db(0) as con:
            user = auth.create_user(
                con, username, password, display or None, scopes,
                tool_policy_json=policy_json, window_json=window_json,
                data_mode=data_mode, enabled=enabled)
            record_admin_action(
                con, "admin.user.create",
                detail=f'id={user["id"]} user={user["username"]}')
    except ValueError as exc:
        message = str(exc)
        if "already exists" in message:
            code = "err_duplicate"
        elif "username" in message:
            code = "err_username"
        elif "password" in message or "display" in message:
            code = "err_invalid"
        else:
            code = "err_generic"
        _back(handler, "/admin/users", err=code)
        return
    _back(handler, "/admin/users", ok="user_created")


def _users_update(handler, session, form, user_id: int) -> None:
    policy_json = window_json = None
    policy_err = window_err = None
    with hdb.db(0) as con:
        user = auth.get_user(con, user_id)
        if user is None:
            _back(handler, "/admin/users", err="err_not_found")
            return
        scopes = _scope_set(form)
        if "scope_present" in form and not scopes:
            _back(handler, f"/admin/users/{user_id}", err="err_scope")
            return
        if not scopes:
            scopes = user["scopes"]
        data_mode = (web._scalar(form, "data_mode", "") or user["data_mode"])
        enabled = (bool(form.get("enabled"))
                   if "enabled_present" in form else bool(user["enabled"]))
        if "tool_policy" in form:
            policy_json, policy_err = _parse_policy_field(form)
        if "window" in form:
            window_json, window_err = _parse_window_field(form)
        display = (web._scalar(form, "display_name", "") or user["display_name"])
        if policy_err or window_err:
            _back(handler, f"/admin/users/{user_id}", err=policy_err or window_err)
            return
        if data_mode not in ("shared", "own"):
            _back(handler, f"/admin/users/{user_id}", err="err_data_mode")
            return
        if (data_mode == "own" and user["data_mode"] != "own"
                and not _creds_ready(user_id)):
            _back(handler, f"/admin/users/{user_id}", err="err_creds_missing")
            return
        if not enabled and not _guard_admin_change(con, session, user_id):
            _back(handler, f"/admin/users/{user_id}", err="err_last_admin")
            return
        kwargs = {
            "display_name": display,
            "scopes": scopes,
            "data_mode": data_mode,
            "enabled": enabled,
        }
        if "tool_policy" in form:
            kwargs["tool_policy_json"] = policy_json
        if "window" in form:
            kwargs["window_json"] = window_json
        try:
            updated = auth.update_user(con, user_id, **kwargs)
        except ValueError:
            _back(handler, f"/admin/users/{user_id}", err="err_invalid")
            return
        if updated is None:
            _back(handler, "/admin/users", err="err_not_found")
            return
        if not enabled and user["enabled"]:
            _revoke_user_credentials(con, user_id)
        record_admin_action(
            con, "admin.user.update", detail=f"id={user_id} user={user['username']}")
    _back(handler, f"/admin/users/{user_id}", ok="user_updated")


def _users_reset(handler, session, form, user_id: int) -> None:
    password = web._scalar(form, "password", "") or ""
    if len(password) < MIN_PASSWORD:
        _back(handler, f"/admin/users/{user_id}", err="err_short_password")
        return
    with hdb.db(0) as con:
        user = auth.get_user(con, user_id)
        if user is None:
            _back(handler, "/admin/users", err="err_not_found")
            return
        try:
            auth.update_user(con, user_id, password=password)
            auth.revoke_subject(con, auth.subject_for_user(user_id))
        except ValueError:
            _back(handler, f"/admin/users/{user_id}", err="err_invalid")
            return
        record_admin_action(
            con, "admin.user.password_reset",
            detail=f"id={user_id} user={user['username']}")
    _back(handler, f"/admin/users/{user_id}", ok="password_reset")


def _users_toggle(handler, session, user_id: int, enable: bool) -> None:
    with hdb.db(0) as con:
        user = auth.get_user(con, user_id)
        if user is None:
            _back(handler, "/admin/users", err="err_not_found")
            return
        if not enable and not _guard_admin_change(con, session, user_id):
            _back(handler, f"/admin/users/{user_id}", err="err_last_admin")
            return
        auth.update_user(con, user_id, enabled=enable)
        if not enable:
            _revoke_user_credentials(con, user_id)
        record_admin_action(
            con, "admin.user.enable" if enable else "admin.user.disable",
            detail=f"id={user_id} user={user['username']}")
    _back(handler, f"/admin/users/{user_id}",
          ok="user_enabled" if enable else "user_disabled")


def _archive_user_partition(user_id: int) -> bool:
    """own 分区与凭据先完整暂存，再原子改名为归档目录。

    任何一步失败都把已移动的文件放回原处；只有归档成功后调用方才删除用户行。
    包含 SQLite 的 ``-wal`` / ``-shm`` / ``-journal`` 旁文件。
    """
    source_db = hdb.db_path(user_id)
    source_creds = _creds_path(user_id)
    files: list[tuple[str, str]] = []
    for path, name in ((source_db, "health.db"), (source_creds, "creds.json")):
        if os.path.exists(path):
            files.append((path, name))
    for suffix, name in (("-wal", "health.db-wal"),
                         ("-shm", "health.db-shm"),
                         ("-journal", "health.db-journal")):
        if os.path.exists(source_db + suffix):
            files.append((source_db + suffix, name))
    if not files:
        return True

    archive_root = os.path.join(hdb.data_dir(), "archive")
    try:
        os.makedirs(archive_root, mode=0o700, exist_ok=True)
        staging = tempfile.mkdtemp(prefix=f".{user_id}-", dir=archive_root)
    except OSError:
        return False
    try:
        os.chmod(staging, 0o700)
    except OSError:
        pass
    moved: list[tuple[str, str]] = []
    stamp = time.strftime("%Y%m%d-%H%M%S")
    final = os.path.join(
        archive_root, f"{user_id}-{stamp}-{os.getpid()}-{time.time_ns() % 1000000:06d}")
    try:
        for path, name in files:
            dest = os.path.join(staging, name)
            shutil.move(path, dest)
            moved.append((path, dest))
        os.replace(staging, final)
        return True
    except OSError:
        for source, dest in reversed(moved):
            try:
                if os.path.exists(dest) and not os.path.exists(source):
                    shutil.move(dest, source)
            except OSError:
                pass
        shutil.rmtree(staging, ignore_errors=True)
        return False


def _revoke_user_credentials(con, user_id: int) -> None:
    """停用/删除用户时撤销会话、静态密钥、OAuth 令牌与未兑换的码。"""
    ts = int(time.time())
    try:
        auth.revoke_subject(con, auth.subject_for_user(user_id), now=ts)
    except ValueError:
        pass
    con.execute(
        "UPDATE agent_tokens SET revoked_at=? "
        "WHERE owner_user_id=? AND revoked_at IS NULL", (ts, user_id))
    con.execute(
        "UPDATE oauth_tokens SET revoked_at=? "
        "WHERE user_id=? AND revoked_at IS NULL", (ts, user_id))
    con.execute("DELETE FROM oauth_codes WHERE user_id=?", (user_id,))


def _users_delete(handler, session, form, user_id: int) -> None:
    confirm = (web._scalar(form, "confirm", "") or "").strip()
    with hdb.db(0) as con:
        user = auth.get_user(con, user_id)
        if user is None:
            _back(handler, "/admin/users", err="err_not_found")
            return
        if not _guard_admin_change(con, session, user_id):
            _back(handler, f"/admin/users/{user_id}", err="err_last_admin")
            return
        if confirm != user["username"]:
            _back(handler, f"/admin/users/{user_id}", err="err_confirm")
            return
        if not _archive_user_partition(user_id):
            _back(handler, f"/admin/users/{user_id}", err="err_archive")
            return
        _revoke_user_credentials(con, user_id)
        con.execute("DELETE FROM users WHERE id=?", (user_id,))
        record_admin_action(
            con, "admin.user.delete",
            detail=f"id={user_id} user={user['username']}")
    _back(handler, "/admin/users", ok="user_deleted")


def _keys_post(handler, session, form) -> None:
    action = web._scalar(form, "action", "") or ""
    if action == "create":
        _keys_create(handler, session, form)
    elif action == "revoke":
        raw_id = (web._scalar(form, "token_id", "") or "").strip()
        label = (web._scalar(form, "label", "") or "").strip()
        try:
            ident = int(raw_id) if raw_id else label
        except (TypeError, ValueError):
            ident = label or 0
        if not ident:
            _back(handler, "/admin/keys", err="err_invalid")
            return
        with hdb.db(0) as con:
            ok = auth.static_token_revoke(con, ident)
            if ok:
                record_admin_action(con, "admin.key.revoke",
                                    detail=f"id={ident}")
        _back(handler, "/admin/keys",
              ok="key_revoked" if ok else None,
              err=None if ok else "err_not_found")
    else:
        _back(handler, "/admin/keys", err="err_action")


def _keys_create(handler, session, form) -> None:
    label = (web._scalar(form, "label", "") or "").strip()
    try:
        owner = int(web._scalar(form, "owner_user_id", "0") or 0)
    except (TypeError, ValueError):
        owner = -1
    policy_json, policy_err = _parse_policy_field(form)
    window_json, window_err = _parse_window_field(form)
    if policy_err or window_err:
        _back(handler, "/admin/keys", err=policy_err or window_err)
        return
    if owner < 0:
        _back(handler, "/admin/keys", err="err_owner")
        return
    try:
        with hdb.db(0) as con:
            data = auth.static_token_create(
                con, label, owner_user_id=owner,
                tool_policy_json=policy_json, window_json=window_json)
            record_admin_action(
                con, "admin.key.create",
                detail=f'id={data["id"]} label={data["label"]} owner={owner}')
    except ValueError as exc:
        message = str(exc)
        if "already exists" in message:
            _back(handler, "/admin/keys", err="err_duplicate")
        elif "owner" in message:
            _back(handler, "/admin/keys", err="err_owner")
        else:
            _back(handler, "/admin/keys", err="err_invalid")
        return
    body = views.raw(
        '<p class="warn">这是完整令牌的唯一一次显示。请立即复制到客户端；'
        "离开本页后无法再次查看（只显示一次）。</p>"
    ) + views.pre(data["token"]) \
        + views.table(("标签", "所有者", "创建"), [[
            data["label"], str(owner), _fmt_ts(data["created_at"])]]) \
        + views.raw('<p><a href="/admin/keys">返回静态密钥列表</a></p>')
    _page(handler, "静态密钥已创建", body, session, _secret_for())


def _secret_for() -> str:
    """从数据库再取签名密钥（只用于同一请求内生成 CSRF）。"""
    with hdb.db(0) as con:
        return auth.session_secret(con)


def _clients_post(handler, session, form) -> None:
    action = web._scalar(form, "action", "") or ""
    if action == "create":
        _clients_create(handler, session, form)
        return
    client_id = (web._scalar(form, "client_id", "") or "").strip()
    if not re.fullmatch(r"hc_[A-Za-z0-9_-]{1,120}", client_id):
        _back(handler, "/admin/clients", err="err_invalid")
        return
    if action not in ("revoke", "revoke_tokens"):
        _back(handler, "/admin/clients", err="err_action")
        return
    confirm = (web._scalar(form, "confirm", "") or "").strip()
    ok = False
    error = None
    with hdb.db(0) as con:
        client = oauth.get_client(con, client_id)
        if client is None:
            error = "err_not_found"
        elif action == "revoke" and confirm != client["client_name"]:
            error = "err_confirm"
        elif action == "revoke":
            ok = oauth.revoke_client(con, client_id)
            if ok:
                record_admin_action(con, "admin.client.revoke",
                                    detail=f"client_id={client_id}")
        else:
            ts = int(time.time())
            con.execute(
                "UPDATE oauth_tokens SET revoked_at=? "
                "WHERE client_id=? AND revoked_at IS NULL", (ts, client_id))
            record_admin_action(con, "admin.client.tokens_revoke",
                                detail=f"client_id={client_id}")
            ok = True
    # 响应必须在事务提交之后发送，否则客户端可能先看到成功、库里还没提交。
    if error:
        _back(handler, "/admin/clients", err=error)
    elif action == "revoke":
        _back(handler, "/admin/clients", ok="client_revoked" if ok else None,
              err=None if ok else "err_not_found")
    else:
        _back(handler, f"/admin/clients/{client_id}",
              ok="client_tokens_revoked")


def _clients_create(handler, session, form) -> None:
    name = (web._scalar(form, "client_name", "") or "").strip()
    raw_uris = (web._scalar(form, "redirect_uris", "") or "").strip()
    uris = [line.strip() for line in raw_uris.splitlines() if line.strip()]
    confidential = bool(form.get("confidential"))
    policy_json, policy_err = _parse_policy_field(form)
    window_json, window_err = _parse_window_field(form)
    if policy_err or window_err:
        _back(handler, "/admin/clients", err=policy_err or window_err)
        return
    if not name or not uris:
        _back(handler, "/admin/clients", err="err_invalid")
        return
    payload = {
        "client_name": name,
        "redirect_uris": uris,
        "token_endpoint_auth_method":
            "client_secret_post" if confidential else "none",
        "tool_policy_json": policy_json,
        "window_json": window_json,
    }
    try:
        with hdb.db(0) as con:
            data = oauth.register_client(con, payload)
            record_admin_action(
                con, "admin.client.create",
                detail=f'client_id={data["client_id"]} name={name}')
    except oauth.OAuthError as exc:
        code = ("err_duplicate" if "上限" in (exc.description or "")
                else "err_redirect" if exc.error == "invalid_redirect_uri"
                else "err_invalid")
        _back(handler, "/admin/clients", err=code)
        return
    body = views.raw(
        "<p>客户端已创建。下面的信息只显示一次；库中只保存 secret 的哈希。</p>"
    ) + views.table(("项目", "值"), [
        ["名称", data["client_name"]],
        ["client_id", data["client_id"]],
        ["类型", "机密" if data.get("confidential") else "公开"],
        ["认证方式", data["token_endpoint_auth_method"]],
        ["redirect URI", ", ".join(data["redirect_uris"])],
    ])
    if data.get("client_secret"):
        body += views.raw('<p class="warn">client_secret（只显示一次）：</p>') \
            + views.pre(data["client_secret"])
    body += views.raw('<p><a href="/admin/clients">返回客户端列表</a></p>')
    _page(handler, "OAuth 客户端已创建", body, session, _secret_for())


def _sessions_post(handler, session, form) -> None:
    action = web._scalar(form, "action", "") or ""
    if action == "revoke":
        sid_hash = (web._scalar(form, "sid_hash", "") or "").strip().lower()
        if not re.fullmatch(r"[0-9a-f]{64}", sid_hash):
            _back(handler, "/admin/sessions", err="err_invalid")
            return
        if sid_hash == auth.hash_token(session["sid"]):
            _back(handler, "/admin/sessions", err="err_current_session")
            return
        with hdb.db(0) as con:
            ok = auth.revoke_session(con, sid_hash)
            if ok:
                record_admin_action(con, "admin.session.revoke",
                                    detail=f"sid_hash={sid_hash[:12]}…")
        _back(handler, "/admin/sessions",
              ok="session_revoked" if ok else None,
              err=None if ok else "err_session")
    elif action == "revoke_subject":
        subject = (web._scalar(form, "subject", "") or "").strip()
        if subject != auth.ADMIN_SUBJECT and \
                not re.fullmatch(r"user:\d+", subject):
            _back(handler, "/admin/sessions", err="err_invalid")
            return
        with hdb.db(0) as con:
            # 吊销当前主体时保留正在用的这把会话，避免点一下就把自己踢出后台。
            keep = session["sid"] if subject == session["subject"] else None
            count = auth.revoke_subject(con, subject, keep_sid=keep)
            record_admin_action(
                con, "admin.session.revoke_subject",
                detail=f"subject={subject} count={count}")
        _back(handler, "/admin/sessions", ok="sessions_revoked")
    elif action == "revoke_others":
        with hdb.db(0) as con:
            subjects = {row["subject"] for row in
                        auth.list_sessions(con, include_revoked=False)}
            count = 0
            for subject in subjects:
                count += auth.revoke_subject(
                    con, subject, keep_sid=session["sid"])
            record_admin_action(
                con, "admin.session.revoke_others", detail=f"count={count}")
        _back(handler, "/admin/sessions", ok="sessions_revoked")
    else:
        _back(handler, "/admin/sessions", err="err_action")


def _security_post(handler, session, form) -> None:
    action = web._scalar(form, "action", "") or ""
    if action in ("totp_enable", "enable_totp"):
        action = "totp_prepare"
    elif action in ("totp_verify", "confirm_totp"):
        action = "totp_confirm"
    elif action in ("totp_off", "disable_totp"):
        action = "totp_disable"
    elif action in ("recovery_codes", "regenerate_recovery"):
        action = "recovery_generate"
    if action == "totp_prepare":
        password = web._scalar(form, "current_password", "") or ""
        error = None
        with hdb.db(0) as con:
            if not auth.admin_check_password(con, password):
                record_admin_action(con, "admin.totp.prepare",
                                    result="denied", detail="bad_password")
                error = "err_current_password"
            elif auth.admin_totp_state(con)["enabled"]:
                record_admin_action(con, "admin.totp.prepare",
                                    result="denied", detail="already_enabled")
                error = "err_totp_enabled"
            else:
                auth.admin_totp_enable(con)
                record_admin_action(con, "admin.totp.prepare")
        if error:
            _back(handler, "/admin/security", err=error)
        else:
            _back(handler, "/admin/security", ok="totp_prepared")
    elif action == "totp_confirm":
        password = web._scalar(form, "current_password", "") or ""
        code_value = (web._scalar(form, "code", "") or "").strip()
        error = None
        codes = None
        with hdb.db(0) as con:
            if not auth.admin_check_password(con, password):
                record_admin_action(con, "admin.totp.confirm",
                                    result="denied", detail="bad_password")
                error = "err_current_password"
            elif auth.admin_totp_state(con)["enabled"]:
                record_admin_action(con, "admin.totp.confirm",
                                    result="denied", detail="already_enabled")
                error = "err_totp_enabled"
            elif not auth.admin_totp_confirm(con, code_value):
                record_admin_action(con, "admin.totp.confirm",
                                    result="denied", detail="bad_code")
                error = "err_totp"
            else:
                codes = auth.admin_recovery_generate(con, 10)
                record_admin_action(con, "admin.totp.confirm")
        if error:
            _back(handler, "/admin/security", err=error)
            return
        body = views.raw(
            '<p class="ok">两步验证已启用。下面的 10 个恢复码只显示这一次；'
            "请离线保存。每个码只能使用一次。</p>"
        ) + views.pre("\n".join(codes)) \
            + views.raw('<p><a href="/admin/security">返回安全页</a></p>')
        _page(handler, "两步验证已启用", body, session, _secret_for())
    elif action == "totp_disable":
        password = web._scalar(form, "current_password", "") or ""
        code_value = (web._scalar(form, "code", "") or "").strip()
        error = None
        with hdb.db(0) as con:
            if not auth.admin_check_password(con, password):
                record_admin_action(con, "admin.totp.disable",
                                    result="denied", detail="bad_password")
                error = "err_current_password"
            elif not (auth.admin_totp_verify(con, code_value)
                      or auth.admin_recovery_consume(con, code_value)):
                record_admin_action(con, "admin.totp.disable",
                                    result="denied", detail="bad_code")
                error = "err_totp"
            else:
                auth.admin_totp_disable(con)
                record_admin_action(con, "admin.totp.disable")
        if error:
            _back(handler, "/admin/security", err=error)
        else:
            _back(handler, "/admin/security", ok="totp_disabled")
    elif action == "recovery_generate":
        password = web._scalar(form, "current_password", "") or ""
        code_value = (web._scalar(form, "code", "") or "").strip()
        error = None
        codes = None
        with hdb.db(0) as con:
            if not auth.admin_check_password(con, password):
                record_admin_action(con, "admin.totp.recovery_generate",
                                    result="denied", detail="bad_password")
                error = "err_current_password"
            elif not auth.admin_totp_state(con)["enabled"]:
                record_admin_action(con, "admin.totp.recovery_generate",
                                    result="denied", detail="not_enabled")
                error = "err_totp_disabled"
            elif not (auth.admin_totp_verify(con, code_value)
                      or auth.admin_recovery_consume(con, code_value)):
                record_admin_action(con, "admin.totp.recovery_generate",
                                    result="denied", detail="bad_code")
                error = "err_totp"
            else:
                codes = auth.admin_recovery_generate(con, 10)
                record_admin_action(con, "admin.totp.recovery_generate")
        if error:
            _back(handler, "/admin/security", err=error)
            return
        body = views.raw(
            '<p class="warn">新的恢复码只显示这一次；旧恢复码已全部失效。</p>'
        ) + views.pre("\n".join(codes)) \
            + views.raw('<p><a href="/admin/security">返回安全页</a></p>')
        _page(handler, "恢复码", body, session, _secret_for())
    elif action == "change_password":
        _change_admin_password(handler, session, form, back="/admin/security")
    else:
        _back(handler, "/admin/security", err="err_action")


def _settings_post(handler, session, form) -> None:
    action = web._scalar(form, "action", "") or ""
    if action == "set_interval":
        raw = (web._scalar(form, "fetch_interval", "") or "").strip()
        try:
            interval = int(raw)
        except (TypeError, ValueError):
            interval = 0
        if not FETCH_MIN <= interval <= FETCH_MAX:
            _back(handler, "/admin/settings", err="err_interval")
            return
        with hdb.db(0) as con:
            auth.settings_set(con, "fetch_interval_override", str(interval))
            record_admin_action(con, "admin.settings.update",
                                detail=f"fetch_interval={interval}")
        _back(handler, "/admin/settings", ok="settings_saved")
    elif action == "change_password":
        _change_admin_password(handler, session, form, back="/admin/settings")
    else:
        _back(handler, "/admin/settings", err="err_action")


def _check_new_password(form) -> str | None:
    new = web._scalar(form, "new_password", "") or ""
    confirm = web._scalar(form, "confirm_password", "") or ""
    if len(new) < MIN_PASSWORD:
        return "err_short_password"
    if new != confirm:
        return "err_password_mismatch"
    return None


def _admin_password_change(con, handler, form, *,
                           audit_action: str = "admin.password.change"):
    """改管理员密码并换一把新会话；返回 ``(error, Set-Cookie tuple|None)``。"""
    current = web._scalar(form, "current_password", "") or ""
    error = _check_new_password(form)
    if error:
        return error, None
    if not auth.admin_check_password(con, current):
        return "err_current_password", None
    new = web._scalar(form, "new_password", "") or ""
    try:
        # 不 keep 任何旧 sid：改密后旧 Cookie 必须立即失效。
        auth.admin_set_password(con, new)
    except ValueError:
        return "err_password", None
    _sid, cookie = auth.create_session(
        con, auth.ADMIN_SUBJECT, 0, ttl=auth.ADMIN_SESSION_TTL,
        ua=handler.headers.get("User-Agent", ""))
    record_admin_action(con, audit_action)
    return None, web._session_cookie(
        handler, cookie, auth.ADMIN_SESSION_TTL)


def _change_admin_password(handler, session, form, *, back: str) -> None:
    error = None
    cookie = None
    with hdb.db(0) as con:
        error, cookie = _admin_password_change(con, handler, form)
    if error:
        _back(handler, back, err=error)
        return
    _back(handler, back, ok="password_changed",
          cookies=(cookie,) if cookie else ())


# ------------------------------------------------------------------ 账号 POST
def _account_post(handler, path: str, session, form) -> None:
    action = web._scalar(form, "action", "") or ""
    if action == "change_password":
        _change_own_password(handler, session, form)
    elif action == "update_profile":
        _update_own_profile(handler, session, form)
    elif action == "revoke_session":
        _revoke_own_session(handler, session, form)
    else:
        _back(handler, "/account", err="err_action")


def _change_own_password(handler, session, form) -> None:
    current = web._scalar(form, "current_password", "") or ""
    error = _check_new_password(form)
    if error:
        _back(handler, "/account", err=error)
        return
    new = web._scalar(form, "new_password", "") or ""
    cookie_header = None
    error = None
    with hdb.db(0) as con:
        if session["is_admin"]:
            error, cookie_header = _admin_password_change(
                con, handler, form,
                audit_action="account.password.change")
        else:
            user = auth.get_user(con, session["user_id"])
            if user is None or auth.check_user_password(
                    con, user["username"], current) is None:
                error = "err_current_password"
            else:
                try:
                    _sid, cookie = auth.change_password(
                        con, user["id"], new, current_sid=session["sid"],
                        ttl=auth.USER_SESSION_TTL)
                except ValueError:
                    error = "err_session"
                else:
                    record_admin_action(
                        con, "account.password.change",
                        actor=user["username"])
                    cookie_header = web._session_cookie(
                        handler, cookie, auth.USER_SESSION_TTL)
    if error:
        _back(handler, "/account", err=error)
        return
    _back(handler, "/account", ok="password_changed",
          cookies=(cookie_header,) if cookie_header else ())


def _update_own_profile(handler, session, form) -> None:
    if session["is_admin"]:
        _back(handler, "/account", err="err_action")
        return
    display = (web._scalar(form, "display_name", "") or "").strip()
    with hdb.db(0) as con:
        user = auth.get_user(con, session["user_id"])
        if user is None:
            _back(handler, "/login", err="err_session")
            return
        try:
            auth.update_user(con, user["id"], display_name=display)
        except ValueError:
            _back(handler, "/account", err="err_invalid")
            return
        record_admin_action(con, "account.profile.update",
                            actor=user["username"])
    _back(handler, "/account", ok="profile_saved")


def _revoke_own_session(handler, session, form) -> None:
    sid_hash = (web._scalar(form, "sid_hash", "") or "").strip().lower()
    if not re.fullmatch(r"[0-9a-f]{64}", sid_hash):
        _back(handler, "/account", err="err_invalid")
        return
    if sid_hash == auth.hash_token(session["sid"]):
        _back(handler, "/account", err="err_current_session")
        return
    with hdb.db(0) as con:
        own = {row["sid_hash"] for row in auth.list_sessions(
            con, subject=session["subject"], include_revoked=False)}
        if sid_hash not in own:
            _back(handler, "/account", err="err_session")
            return
        auth.revoke_session(con, sid_hash)
        record_admin_action(con, "account.session.revoke",
                            actor="admin" if session["is_admin"]
                            else _username_for(session["user_id"]))
    _back(handler, "/account", ok="session_revoked")
