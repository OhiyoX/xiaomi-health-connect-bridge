"""HTTP 路由：OAuth 发现/授权/令牌、登录与同意、后台门禁。

设计：``docs/superpowers/specs/2026-11-02-oauth-admin-design.md`` §6、§8.4–8.6、
§10、§11、§13。只用标准库。

约定
----
- ``handle(handler) -> bool``：认识这条路由（已写响应）返回 ``True``；否则
  ``False`` 交回 ``server.py``（``/health``、``/mcp``、``/ingest``）。
- 令牌、授权码、client secret、密码、Cookie 值不写日志，也不出现在页面。
- 所有 POST 都做同源检查；有会话的表单再校验 CSRF。同意页把 GET 时校验过的
  请求签名后放进表单，POST 只在该签名范围内收窄，不能借 POST 放宽 scope。
- ``PUBLIC_BASE_URL`` 优先；没有时只在私有 peer（nginx/回环）后按
  ``X-Forwarded-Proto`` + ``Host`` 推导；仍没有就用 ``https://health.example.com``。
  issuer/resource 只来自这个值，绝不信任客户端自带的 ``X-Forwarded-For``。
"""
from __future__ import annotations

import html
import ipaddress
import json
import os
import secrets
import sys
import time
from urllib.parse import parse_qs, quote, urlencode, urlsplit

from . import audit
from . import auth
from . import db as hdb
from . import oauth
from . import ratelimit

DEFAULT_BASE_URL = "https://health.example.com"
PROTECTED_RESOURCE_PATH = "/.well-known/oauth-protected-resource"
PROTECTED_RESOURCE_MCP_PATH = PROTECTED_RESOURCE_PATH + "/mcp"
AS_METADATA_PATH = "/.well-known/oauth-authorization-server"
FORM_LIMIT = 16 * 1024
LOGIN_COOKIE = "hcb_login"
LOGIN_CSRF_TTL = 10 * 60
LOGIN_LIMIT, LOGIN_WINDOW = 5, 300
"""登录/OTP 每个 IP 的尝试上限与窗口（秒）；管理页把这两个值展示为锁定策略。"""
CONSENT_TTL = 10 * 60
LOOPBACK_HOSTS = frozenset({"localhost", "127.0.0.1", "::1"})


def _audit_safe(*args, **kwargs) -> None:
    """只读/拒绝路径的最佳努力审计：失败只报类型，不影响响应。"""
    try:
        with hdb.db(0) as con:
            audit.append(con, *args, **kwargs)
    except Exception as exc:  # noqa: BLE001
        print(f"[audit] append failed: {type(exc).__name__}", flush=True)


def _audit_try(con, *args, **kwargs) -> None:
    """已有事务里的最佳努力审计；不把断链错误抛给拒绝路径。"""
    try:
        audit.append(con, *args, **kwargs)
    except Exception as exc:  # noqa: BLE001
        print(f"[audit] append failed: {type(exc).__name__}", flush=True)

CSP = ("default-src 'none'; style-src 'self' 'unsafe-inline'; img-src 'self'; "
       "form-action 'self'")
SECURITY_HEADERS = (
    ("Cache-Control", "no-store"),
    ("X-Frame-Options", "DENY"),
    ("X-Content-Type-Options", "nosniff"),
    ("Referrer-Policy", "no-referrer"),
    ("Content-Security-Policy", CSP),
)


class _TooLarge(Exception):
    pass


# ------------------------------------------------------------------ 基础配置
def _normalize_base(raw: object) -> str | None:
    if not isinstance(raw, str):
        return None
    text = raw.strip()
    if not text or any(c in text for c in "\r\n"):
        return None
    try:
        parts = urlsplit(text)
    except ValueError:
        return None
    if parts.scheme.lower() not in ("http", "https") or not parts.netloc:
        return None
    if parts.username or parts.password or parts.query or parts.fragment:
        return None
    if parts.path not in ("", "/"):
        return None
    return f"{parts.scheme.lower()}://{parts.netloc.lower()}"


def _trust_proxy_enabled() -> bool:
    return (os.environ.get("HCB_TRUST_PROXY", "0") or "0").strip().lower() in (
        "1", "true", "yes", "on")


def _trusted_proxy_networks() -> tuple:
    """``HCB_TRUSTED_PROXIES``：逗号分隔的 IP/CIDR；解析失败的项忽略。"""
    out = []
    for item in (os.environ.get("HCB_TRUSTED_PROXIES", "") or "").split(","):
        item = item.strip()
        if not item:
            continue
        try:
            out.append(ipaddress.ip_network(item, strict=False))
        except ValueError:
            continue
    return tuple(out)


def _peer_ip(handler) -> str:
    return str(getattr(handler, "client_address", ("", 0))[0] or "")


def _peer_trusted(handler) -> bool:
    """只有显式启用并用 CIDR 列出的反代 peer 才可信；未列 CIDR 时只信回环。"""
    if handler is None or not _trust_proxy_enabled():
        return False
    try:
        ip = ipaddress.ip_address(_peer_ip(handler))
    except ValueError:
        return False
    networks = _trusted_proxy_networks()
    if networks:
        return any(ip in network for network in networks)
    return ip.is_loopback


def _derive_base_url(handler) -> str | None:
    if handler is None or not _peer_trusted(handler):
        return None
    headers = handler.headers
    host = (headers.get("Host") or "").strip()
    if not host or any(c in host for c in "/\\\r\n") or any(c.isspace() for c in host):
        return None
    proto = (headers.get("X-Forwarded-Proto") or "").split(",")[0].strip().lower()
    if proto not in ("http", "https"):
        proto = "http"
    return f"{proto}://{host}"


def startup_warnings() -> list[str]:
    """启动时的配置提醒；只含变量名，不含任何秘密。"""
    out = []
    if not (os.environ.get("PUBLIC_BASE_URL") or "").strip():
        out.append("PUBLIC_BASE_URL 未设置：OAuth issuer 可能按请求推导；"
                   "生产请显式设置 https 地址")
    if _trust_proxy_enabled() and not _trusted_proxy_networks():
        out.append("HCB_TRUST_PROXY=1 但 HCB_TRUSTED_PROXIES 为空：只信任回环 peer")
    return out


def base_url(handler=None) -> str | None:
    """规范化后的站点根：环境变量 → 受信代理推导 → 生产默认值。"""
    raw = os.environ.get("PUBLIC_BASE_URL")
    if raw is not None and raw.strip():
        return _normalize_base(raw)
    derived = _derive_base_url(handler)
    if derived:
        return _normalize_base(derived)
    return DEFAULT_BASE_URL


def canonical_base(handler=None) -> str | None:
    """issuer/resource 用的根；生产非 https（非回环）直接拒绝。"""
    base = base_url(handler)
    if not base:
        return None
    parts = urlsplit(base)
    if parts.scheme != "https" and (parts.hostname or "").lower() not in LOOPBACK_HOSTS:
        return None
    return base


def resource_url(handler=None) -> str | None:
    base = canonical_base(handler)
    return base + "/mcp" if base else None


def metadata_url(handler=None) -> str | None:
    """401 挑战里的资源元数据地址；配置无效时返回 ``None``，不偷换主机。"""
    base = canonical_base(handler) or base_url(handler)
    return (base + PROTECTED_RESOURCE_MCP_PATH) if base else None


def _issuer_for(canonical: str) -> str:
    text = canonical.rstrip("/")
    if text.endswith("/mcp"):
        text = text[:-4]
    return text.rstrip("/")


def metadata(canonical: str | None = None) -> dict:
    """RFC 9728 保护资源元数据；``canonical`` 缺省用当前 base 的 ``/mcp``。"""
    canonical = canonical or resource_url()
    if not canonical:
        raise ValueError("canonical resource is unavailable")
    return {
        "resource": canonical,
        "authorization_servers": [_issuer_for(canonical)],
        "scopes_supported": list(oauth.SCOPES),
        "resource_name": "hc-bridge Health MCP",
    }


def authorization_server_metadata(base: str | None = None) -> dict:
    base = base or canonical_base()
    if not base:
        raise ValueError("issuer is unavailable")
    return {
        "issuer": base,
        "authorization_endpoint": base + "/authorize",
        "token_endpoint": base + "/token",
        "registration_endpoint": base + "/register",
        "revocation_endpoint": base + "/revoke",
        "response_types_supported": ["code"],
        "grant_types_supported": ["authorization_code", "refresh_token"],
        "code_challenge_methods_supported": ["S256"],
        "token_endpoint_auth_methods_supported": ["none", "client_secret_post"],
        "scopes_supported": list(oauth.SCOPES),
    }


# ------------------------------------------------------------------ 请求/响应
def client_ip(handler) -> str:
    """客户端 IP：只信已列入 ``HCB_TRUSTED_PROXIES`` 的代理的 ``X-Real-IP``。"""
    peer = _peer_ip(handler)
    if _peer_trusted(handler):
        forwarded = (handler.headers.get("X-Real-IP") or "").strip()
        if forwarded:
            try:
                return str(ipaddress.ip_address(forwarded))
            except ValueError:
                pass
    return peer or "-"


def _same_origin(handler) -> bool:
    origin = handler.headers.get("Origin")
    if not origin:
        return True
    host = (handler.headers.get("Host") or "").strip()
    try:
        parts = urlsplit(origin)
    except ValueError:
        return False
    if parts.scheme not in ("http", "https"):
        return False
    return parts.netloc.lower() == host.lower()


def _cookie(handler, name: str) -> str | None:
    raw = handler.headers.get("Cookie") or ""
    for item in raw.split(";"):
        key, _, value = item.strip().partition("=")
        if key == name:
            value = value.strip().strip('"')
            return value or None
    return None


def _cookie_header(name: str, value: str, max_age: int, secure: bool) -> tuple[str, str]:
    parts = [f"{name}={value}", "Path=/", "HttpOnly", "SameSite=Lax",
             f"Max-Age={max_age}"]
    if secure:
        parts.append("Secure")
    return ("Set-Cookie", "; ".join(parts))


def _clear_cookie(name: str) -> tuple[str, str]:
    return ("Set-Cookie", f"{name}=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0")


def _discard_body(handler, limit: int = FORM_LIMIT) -> None:
    """响应前丢掉尚未读取的请求体；过大则直接关闭连接。"""
    if getattr(handler, "_body_read", False):
        return
    try:
        length = int(handler.headers.get("Content-Length", "0") or 0)
    except (TypeError, ValueError):
        length = 0
    if length > limit:
        handler.close_connection = True
        handler._body_read = True
        return
    if length > 0:
        try:
            handler.rfile.read(length)
        except Exception:  # noqa: BLE001
            handler.close_connection = True
    handler._body_read = True


def _send(handler, status: int, body: bytes, content_type: str,
          headers=()) -> None:
    _discard_body(handler)
    handler.send_response(status)
    handler.send_header("Content-Type", content_type)
    handler.send_header("Content-Length", str(len(body)))
    for key, value in headers:
        value = str(value)
        if "\r" in value or "\n" in value:
            continue
        handler.send_header(key, value)
    handler.end_headers()
    if getattr(handler, "command", "GET") != "HEAD":
        try:
            handler.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            handler.close_connection = True


def _json(handler, status: int, data, headers=()) -> None:
    body = json.dumps(data, ensure_ascii=False).encode("utf-8")
    base = list(headers)
    if not any(k.lower() == "cache-control" for k, _ in base):
        base.append(("Cache-Control", "no-store"))
    _send(handler, status, body, "application/json; charset=utf-8", base)


def _html_response(handler, status: int, page: str, headers=(), cookies=()) -> None:
    base = list(SECURITY_HEADERS)
    if headers:
        # 调用方给的 CSP 替换默认值：多条 CSP 会被浏览器取交集，页内策略就失效。
        if any(k.lower() == "content-security-policy" for k, _ in headers):
            base = [h for h in base if h[0].lower() != "content-security-policy"]
        base.extend(headers)
    _send(handler, status, page.encode("utf-8"),
          "text/html; charset=utf-8", list(cookies) + base)


def _redirect(handler, location: str, status: int = 302, cookies=()) -> None:
    if not isinstance(location, str) or any(c in location for c in "\r\n"):
        _error_page(handler, 400, "重定向地址无效")
        return
    _send(handler, status, b"", "text/plain; charset=utf-8",
          list(cookies) + [("Location", location), ("Cache-Control", "no-store")])


def _too_many(handler, window: int) -> None:
    _json(handler, 429, {"error": "rate_limited"},
          headers=[("Retry-After", str(int(window)))])


def _rate_ok(handler, bucket: str, limit: int, window: int) -> bool:
    return ratelimit.take(f"{bucket}:{client_ip(handler)}", limit, window)


def _read_body(handler, limit: int = FORM_LIMIT) -> bytes:
    try:
        length = int(handler.headers.get("Content-Length", "0") or 0)
    except (TypeError, ValueError):
        length = 0
    if length < 0:
        length = 0
    if length > limit:
        handler.close_connection = True
        handler._body_read = True
        raise _TooLarge
    try:
        return handler.rfile.read(length) if length else b""
    finally:
        handler._body_read = True


def _form(handler) -> dict[str, list[str]]:
    raw = _read_body(handler)
    return parse_qs(raw.decode("utf-8", "replace"), keep_blank_values=True)


def _json_payload(handler) -> dict:
    raw = _read_body(handler)
    try:
        data = json.loads(raw.decode("utf-8") or "{}")
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise ValueError("invalid json") from None
    if not isinstance(data, dict):
        raise ValueError("json object required")
    return data


def _scalar(data: dict, key: str, default=None):
    value = data.get(key)
    if isinstance(value, (list, tuple)):
        return value[0] if value else default
    return default if value is None else value


def _values(data: dict, key: str) -> list[str]:
    value = data.get(key)
    if isinstance(value, list):
        return [v for v in value if isinstance(v, str)]
    if isinstance(value, str):
        return [value]
    return []


def _add_query(url: str, params: dict) -> str:
    if not params:
        return url
    sep = "&" if urlsplit(url).query else "?"
    return url + sep + urlencode(params, doseq=True)


def _error_redirect(handler, redirect_uri: str, error: str,
                    description: str = "", state: str | None = None) -> None:
    params: dict[str, str] = {"error": error}
    if description:
        params["error_description"] = description
    if isinstance(state, str) and state:
        params["state"] = state
    _redirect(handler, _add_query(redirect_uri, params))


def _safe_next(value: object) -> str | None:
    if not isinstance(value, str) or not value or len(value) > 2048:
        return None
    if any(c in value for c in "\\\r\n"):
        return None
    if not value.startswith("/") or value.startswith("//"):
        return None
    try:
        parts = urlsplit(value)
    except ValueError:
        return None
    if parts.scheme or parts.netloc:
        return None
    path = parts.path
    if path in ("/admin", "/account", "/account/password", "/login/otp"):
        return value
    if path.startswith("/admin/") or path.startswith("/account") or path == "/authorize":
        return value
    return None


def _page(title: str, body: str) -> str:
    """独立页外壳（同意页、错误页）：与登录页共用设计令牌；``body`` 自带 ``.box``。"""
    return _auth_page(title, f'<div class="login">{body}</div>')


def _auth_page(title: str, body: str, *, status: int = 200) -> str:
    """Standalone login/OTP shell using the shared admin design tokens."""
    return (
        "<!doctype html><html lang=\"zh-CN\" data-theme=\"dark\"><head>"
        "<meta charset=\"utf-8\">"
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
        "<meta name=\"color-scheme\" content=\"dark light\">"
        f"<title>{html.escape(title)}</title>"
        "<link rel=\"stylesheet\" href=\"/admin/assets/admin.css\"></head>"
        f"<body>{body}</body></html>"
    )


def _login_page(token: str, nxt: str, *, error: str = "", status: int = 200) -> str:
    banner = (f'<div class="banner danger"><div>{html.escape(error)}</div></div>'
              if error else "")
    body = (
        '<div class="login"><form class="box" method="post" action="/login">'
        '<h1><svg class="logo" style="width:28px;height:28px" aria-hidden="true">'
        '<use href="/admin/assets/sprite.svg#logo"/></svg>HC Health</h1>'
        '<p class="lede" style="margin:0">管理后台登录。输入管理员用户名和密码。</p>'
        f'{banner}'
        f'<input type="hidden" name="_csrf" value="{html.escape(token)}">'
        f'<input type="hidden" name="next" value="{html.escape(nxt)}">'
        '<div class="fld"><label>用户名</label>'
        '<input class="inp" name="username" autocomplete="username" required autofocus></div>'
        '<div class="fld"><label>密码</label>'
        '<input class="inp" name="password" type="password" autocomplete="current-password" required></div>'
        '<button class="btn primary" type="submit">继续</button>'
        '</form></div>'
    )
    return _auth_page("登录", body, status=status)


def _otp_page(token: str, nxt: str, *, error: str = "", status: int = 200) -> str:
    banner = (f'<div class="banner danger"><div>{html.escape(error)}</div></div>'
              if error else "")
    body = (
        '<div class="login"><form class="box" method="post" action="/login/otp">'
        '<h1><svg class="logo" style="width:28px;height:28px" aria-hidden="true">'
        '<use href="/admin/assets/sprite.svg#logo"/></svg>双因素验证</h1>'
        '<p class="lede" style="margin:0">输入身份验证器中的 6 位验证码，或一个恢复码。</p>'
        f'{banner}'
        f'<input type="hidden" name="_csrf" value="{html.escape(token)}">'
        f'<input type="hidden" name="next" value="{html.escape(nxt)}">'
        '<div class="fld"><label>动态码</label>'
        '<input class="inp" name="code" inputmode="numeric" autocomplete="one-time-code" '
        'required autofocus style="height:48px;text-align:center;font:600 20px/28px var(--mono);letter-spacing:.35em"></div>'
        '<button class="btn primary" type="submit">验证并进入</button>'
        '</form></div>'
    )
    return _auth_page("两步验证", body, status=status)


def _error_page(handler, status: int, message: str) -> None:
    _html_response(handler, status, _page(
        "出错了",
        f'<div class="box"><h1>出错了</h1>'
        f'<p class="lede">{html.escape(message)}</p></div>'))


def _method_not_allowed(handler, allow: str) -> None:
    _json(handler, 405, {"error": "method_not_allowed"},
          headers=[("Allow", allow)])


# ------------------------------------------------------------------ 会话/CSRF
def _signing_secret() -> str:
    with hdb.db(0) as con:
        return auth.session_secret(con)


def _load_session(handler):
    """返回 ``(session|None, secret)``；secret 用于 CSRF/签名。"""
    raw = _cookie(handler, auth.SESSION_COOKIE)
    with hdb.db(0) as con:
        secret = auth.session_secret(con)
        if not raw:
            return None, secret
        return auth.read_session(con, raw, secret), secret


def _session_cookie(handler, cookie: str, ttl: int):
    secure = (base_url(handler) or "").startswith("https://")
    return _cookie_header(auth.SESSION_COOKIE, cookie, ttl, secure)


def _login_csrf(secret: str) -> str:
    return auth.sign_payload(secret, {
        "purpose": "login",
        "nonce": secrets.token_urlsafe(16),
        "exp": int(time.time()) + LOGIN_CSRF_TTL,
    })


def _login_csrf_ok(handler, form: dict, secret: str) -> bool:
    """登录/OTP 的 CSRF：必须先 GET 表单，Cookie 与表单 token 必须一致。

    fail-closed：缺 Cookie、缺 token、签名错或过期都拒绝。脚本登录也必须先
    GET ``/login`` 并带上返回的 Cookie 与 ``_csrf``。
    """
    cookie = _cookie(handler, LOGIN_COOKIE)
    token = _scalar(form, "_csrf")
    if not cookie or not isinstance(token, str) or not token:
        return False
    if not auth.constant_eq(cookie, token):
        return False
    payload = auth.verify_payload(secret, cookie)
    if not payload or payload.get("purpose") != "login":
        return False
    exp = payload.get("exp")
    return isinstance(exp, int) and not isinstance(exp, bool) and exp > int(time.time())


def _admin_configured() -> bool:
    with hdb.db(0) as con:
        return auth.admin_has_password(con)


def _identity(con, session: dict) -> dict | None:
    """从会话行（不是表单）推导同意身份。"""
    if session["is_admin"]:
        return {
            "user_id": 0,
            "username": auth.ADMIN_SUBJECT,
            "revision": 0,
            "scopes": list(oauth.SCOPES),
        }
    user = auth.get_user(con, session["user_id"])
    if user is None or not user["enabled"]:
        return None
    return {
        "user_id": int(user["id"]),
        "username": user["username"],
        "revision": int(user["revision"]),
        "scopes": list(user["scopes"]),
    }


# ------------------------------------------------------------------ 发现
def _well_known(handler, path: str) -> bool:
    if handler.command not in ("GET", "HEAD"):
        _method_not_allowed(handler, "GET")
        return True
    base = canonical_base(handler)
    if base is None:
        _json(handler, 503, {"error": "oauth_unavailable",
                             "error_description": "public base URL must be https"})
        return True
    cache = (("Cache-Control", "public, max-age=300"),)
    if path in (PROTECTED_RESOURCE_PATH, PROTECTED_RESOURCE_MCP_PATH):
        _json(handler, 200, metadata(base + "/mcp"), headers=cache)
        return True
    if path == AS_METADATA_PATH:
        _json(handler, 200, authorization_server_metadata(base), headers=cache)
        return True
    _json(handler, 404, {"error": "not_found"})
    return True


# ------------------------------------------------------------------ 注册/令牌/撤销
def _register(handler) -> bool:
    if handler.command != "POST":
        _method_not_allowed(handler, "POST")
        return True
    if not _rate_ok(handler, "register", 10, 3600):
        _too_many(handler, 3600)
        return True
    try:
        data = _json_payload(handler)
    except ValueError as exc:
        _json(handler, 400, {"error": "invalid_client_metadata",
                             "error_description": str(exc)})
        return True
    try:
        with hdb.db(0) as con:
            out = oauth.register_client(con, data)
            audit.append(con, "oauth", "oauth-client", "oauth.client.register", {
                "client_id": out["client_id"],
                "client_name": out["client_name"],
                "auth_method": out.get("token_endpoint_auth_method"),
            }, "ok")
    except oauth.OAuthError as exc:
        _json(handler, exc.status, exc.to_dict())
        return True
    _json(handler, 201, out)
    return True


def _parse_token_body(handler) -> dict[str, list[str]]:
    raw = _read_body(handler)
    content_type = (handler.headers.get("Content-Type") or "").split(";")[0].strip().lower()
    if content_type == "application/json":
        try:
            data = json.loads(raw.decode("utf-8") or "{}")
        except (UnicodeDecodeError, json.JSONDecodeError):
            raise oauth.OAuthError("invalid_request", "请求体不是合法 JSON")
        if not isinstance(data, dict):
            raise oauth.OAuthError("invalid_request", "请求体必须是 JSON 对象")
        return {k: [v] if not isinstance(v, list) else [str(x) for x in v]
                for k, v in data.items() if v is not None}
    return parse_qs(raw.decode("utf-8", "replace"), keep_blank_values=True)


def _token(handler) -> bool:
    if handler.command != "POST":
        _method_not_allowed(handler, "POST")
        return True
    if not _rate_ok(handler, "token", 60, 60):
        _too_many(handler, 60)
        return True
    base = canonical_base(handler)
    if base is None:
        _json(handler, 503, {"error": "oauth_unavailable"})
        return True
    error = None
    out = None
    data: dict = {}
    grant = ""
    try:
        data = _parse_token_body(handler)
        grant = _scalar(data, "grant_type") or ""
        with hdb.db(0) as con:
            try:
                if grant == "authorization_code":
                    out = oauth.exchange_code(con, data, canonical_resource=base + "/mcp")
                elif grant == "refresh_token":
                    out = oauth.refresh(con, data, canonical_resource=base + "/mcp")
                else:
                    raise oauth.OAuthError("unsupported_grant_type", "不支持的 grant_type")
            except oauth.OAuthError as exc:
                # 在 with 内捕获：刷新令牌重用会吊销整族，必须让事务提交而不是回滚。
                description = exc.description or ""
                reuse = (grant == "refresh_token"
                         and getattr(exc, "reason", None) == "refresh_reuse")
                action = ("oauth.token.reuse" if reuse else
                          "oauth.token.refresh" if grant == "refresh_token"
                          else "oauth.token.issue")
                _audit_try(con, "oauth", _scalar(data, "client_id") or "oauth",
                           action, {"grant": grant, "error": exc.error,
                                    "reason": description}, "denied")
                error = exc
            else:
                row = con.execute(
                    "SELECT username, client_id, family_id, scopes_json "
                    "FROM oauth_tokens WHERE token_hash=?",
                    (auth.hash_token(out["access_token"]),)).fetchone()
                action = ("oauth.token.refresh" if grant == "refresh_token"
                          else "oauth.token.issue")
                audit.append(con, "oauth",
                             row["username"] if row else "oauth", action, {
                                 "client_id": row["client_id"] if row else
                                 _scalar(data, "client_id"),
                                 "grant": grant,
                                 "family_id": row["family_id"] if row else None,
                                 "scopes": json.loads(row["scopes_json"])
                                 if row else None,
                             }, "ok")
    except oauth.OAuthError as exc:
        error = exc
    if error is not None:
        _json(handler, error.status, error.to_dict())
        return True
    _json(handler, 200, out)
    return True


def _revoke(handler) -> bool:
    if handler.command != "POST":
        _method_not_allowed(handler, "POST")
        return True
    if not _rate_ok(handler, "revoke", 60, 60):
        _too_many(handler, 60)
        return True
    try:
        data = _parse_token_body(handler)
    except oauth.OAuthError as exc:
        _json(handler, exc.status, exc.to_dict())
        return True
    try:
        with hdb.db(0) as con:
            client_id = _scalar(data, "client_id")
            found = oauth.revoke_token(
                con, _scalar(data, "token"),
                client_id=client_id,
                client_secret=_scalar(data, "client_secret"),
            )
            audit.append(con, "oauth", client_id or "oauth",
                         "oauth.token.revoke",
                         {"client_id": client_id, "found": bool(found)},
                         "ok" if found else "not_found")
    except oauth.OAuthError as exc:
        _json(handler, exc.status, exc.to_dict())
        return True
    # RFC 7009：无论是否找到都回 200。
    _json(handler, 200, {})
    return True


# ------------------------------------------------------------------ 授权与同意
def consent_page(username: str, client_name: str, redirect_uri: str,
                 resource: str, scopes: list[str], csrf: str, request: str) -> str:
    host = urlsplit(redirect_uri).netloc or redirect_uri
    scope_items = []
    for scope in scopes:
        label = {"health.read": "读取健康数据", "health.write": "触发同步/写入"}.get(
            scope, scope)
        scope_items.append(
            '<label class="chk"><input type="checkbox" name="scope" '
            f'value="{html.escape(scope)}" checked><span>{html.escape(label)}'
            f'<span class="d">{html.escape(scope)}</span></span></label>')
    body = (
        '<form class="box" method="post" action="/authorize">'
        '<h1><svg style="width:28px;height:28px" aria-hidden="true">'
        '<use href="/admin/assets/sprite.svg#logo"/></svg>授权确认</h1>'
        f'<p class="lede">以 <strong>{html.escape(username)}</strong> 的身份，'
        '允许这个应用访问你的健康数据。</p>'
        f'<div class="fld"><label>应用</label><div>{html.escape(client_name)}</div></div>'
        f'<div class="fld"><label>回跳主机</label><div class="mono">{html.escape(host)}</div></div>'
        f'<div class="fld"><label>资源</label><div class="mono">{html.escape(resource)}</div></div>'
        f'<input type="hidden" name="_csrf" value="{html.escape(csrf)}">'
        f'<input type="hidden" name="request" value="{html.escape(request)}">'
        '<div class="fld"><label>授予的权限</label>'
        + "".join(scope_items)
        + '</div>'
        '<button class="btn primary" type="submit" name="decision" value="allow">允许</button>'
        '<button class="btn ghost" type="submit" name="decision" value="deny" '
        'style="width:100%;height:40px">拒绝</button>'
        '</form>'
    )
    return _page("授权确认", body)


def _authorize_precheck(handler, query: dict) -> bool:
    """客户端/redirect 无效时直接 400 HTML，不先跳登录、也不回跳。"""
    with hdb.db(0) as con:
        client = oauth.get_client(con, _scalar(query, "client_id"))
    redirect_uri = _scalar(query, "redirect_uri")
    if (client is None or client["revoked"] or client["redirect_uris_corrupt"]
            or not isinstance(redirect_uri, str) or not redirect_uri
            or redirect_uri not in client["redirect_uris"]):
        _error_page(handler, 400, "客户端或 redirect_uri 无效")
        return False
    return True


def _authorize_get(handler, query: dict) -> bool:
    if not _rate_ok(handler, "authorize", 30, 60):
        _too_many(handler, 60)
        return True
    if not _authorize_precheck(handler, query):
        return True
    raw_qs = urlsplit(handler.path).query
    next_path = "/authorize" + (("?" + raw_qs) if raw_qs else "")
    session, secret = _load_session(handler)
    if session is None:
        _redirect(handler, "/login?next=" + quote(next_path, safe=""))
        return True
    base = canonical_base(handler)
    if base is None:
        _json(handler, 503, {"error": "oauth_unavailable"})
        return True
    resource = base + "/mcp"
    with hdb.db(0) as con:
        identity = _identity(con, session)
        if identity is None:
            _redirect(handler, "/login?next=" + quote(next_path, safe=""))
            return True
        try:
            req = oauth.validate_authorize(
                con, query, resource, allowed_scopes=identity["scopes"])
        except oauth.OAuthError as exc:
            if exc.redirect_uri:
                _error_redirect(handler, exc.redirect_uri, exc.error,
                                exc.description, exc.state)
            else:
                _error_page(handler, exc.status or 400,
                            exc.description or exc.error)
            return True
        signed = auth.sign_payload(secret, {
            "purpose": "authorize",
            "sid_hash": auth.hash_token(session["sid"]),
            "client_id": req["client_id"],
            "redirect_uri": req["redirect_uri"],
            "code_challenge": req["code_challenge"],
            "code_challenge_method": "S256",
            "resource": req["resource"],
            "scopes": req["scopes"],
            "state": req["state"],
            "user_revision": identity["revision"],
            "exp": int(time.time()) + CONSENT_TTL,
        })
    page = consent_page(
        username=identity["username"],
        client_name=req["client_name"],
        redirect_uri=req["redirect_uri"],
        resource=req["resource"],
        scopes=req["scopes"],
        csrf=auth.csrf_token(secret, session["sid"]),
        request=signed,
    )
    # 同意表单提交后要跳到客户端的 redirect_uri（跨域），form-action 'self' 会
    # 静默拦掉这次跳转（页面看起来“点了没反应”），所以只对该来源放开。
    origin = "{0.scheme}://{0.netloc}".format(urlsplit(req["redirect_uri"]))
    if any(c in origin for c in " ;,"):
        _error_page(handler, 400, "redirect_uri 无效")
        return True
    _html_response(handler, 200, page, headers=[
        ("Content-Security-Policy", CSP.replace("form-action 'self'",
                                                f"form-action 'self' {origin}"))])
    return True


def _authorize_post(handler) -> bool:
    if not _rate_ok(handler, "authorize", 30, 60):
        _too_many(handler, 60)
        return True
    if not _same_origin(handler):
        _error_page(handler, 403, "跨站请求被拒绝")
        return True
    session, secret = _load_session(handler)
    if session is None:
        _error_page(handler, 401, "请先登录")
        return True
    form = _form(handler)
    if not auth.csrf_verify(secret, session["sid"], _scalar(form, "_csrf")):
        _error_page(handler, 403, "CSRF 校验失败")
        return True
    payload = auth.verify_payload(secret, _scalar(form, "request"))
    now = int(time.time())
    if (not payload or payload.get("purpose") != "authorize"
            or payload.get("sid_hash") != auth.hash_token(session["sid"])):
        _error_page(handler, 403, "授权请求无效，请重新打开同意页")
        return True
    exp = payload.get("exp")
    if not isinstance(exp, int) or isinstance(exp, bool) or exp <= now:
        _error_page(handler, 403, "授权请求已过期，请重新打开同意页")
        return True
    redirect_uri = payload.get("redirect_uri")
    state = payload.get("state")
    if not isinstance(redirect_uri, str) or not redirect_uri:
        _error_page(handler, 403, "授权请求无效")
        return True
    base = canonical_base(handler)
    if base is None:
        _json(handler, 503, {"error": "oauth_unavailable"})
        return True
    resource = base + "/mcp"
    if payload.get("resource") != resource:
        _error_page(handler, 403, "资源不匹配")
        return True

    # 客户端/redirect 可能在 GET 之后被撤销或改掉；回跳前再确认一次。
    if not _authorize_precheck(
            handler, {"client_id": payload.get("client_id"),
                      "redirect_uri": redirect_uri}):
        return True

    if _scalar(form, "decision") != "allow":
        _audit_safe("oauth",
                    auth.ADMIN_SUBJECT if session["is_admin"]
                    else session["subject"], "oauth.consent", {
                        "client_id": payload.get("client_id"),
                        "redirect_uri": redirect_uri,
                    }, "denied")
        _error_redirect(handler, redirect_uri, "access_denied", "用户拒绝授权", state)
        return True

    signed_scopes = payload.get("scopes")
    if not isinstance(signed_scopes, list) or not signed_scopes:
        _error_page(handler, 403, "授权请求无效")
        return True
    params = {
        "response_type": "code",
        "client_id": payload.get("client_id"),
        "redirect_uri": redirect_uri,
        "code_challenge": payload.get("code_challenge"),
        "code_challenge_method": "S256",
        "scope": " ".join(str(s) for s in signed_scopes),
        "state": state,
        "resource": resource,
    }
    with hdb.db(0) as con:
        identity = _identity(con, session)
        if identity is None:
            return _login_again(handler)
        try:
            validated = oauth.validate_authorize(
                con, params, resource, allowed_scopes=identity["scopes"])
        except oauth.OAuthError as exc:
            if exc.redirect_uri:
                _error_redirect(handler, exc.redirect_uri, exc.error,
                                exc.description, state)
            else:
                _error_page(handler, exc.status or 400,
                            exc.description or exc.error)
            return True
        selected = set(_values(form, "scope"))
        scopes = [s for s in validated["scopes"] if s in selected]
        if not scopes:
            _error_redirect(handler, redirect_uri, "invalid_scope",
                            "至少选择一个 scope", state)
            return True
        try:
            code = oauth.issue_code(con, {
                "client_id": validated["client_id"],
                "redirect_uri": validated["redirect_uri"],
                "code_challenge": validated["code_challenge"],
                "scopes": scopes,
                "resource": validated["resource"],
                "session_sid": session["sid"],
                "user_id": identity["user_id"],
                "username": identity["username"],
                "user_revision": identity["revision"],
            }, canonical_resource=resource)
            audit.append(con, "oauth", identity["username"], "oauth.consent", {
                "client_id": validated["client_id"],
                "redirect_uri": validated["redirect_uri"],
                "scopes": scopes,
            }, "approved")
        except oauth.OAuthError as exc:
            if exc.redirect_uri:
                _error_redirect(handler, exc.redirect_uri, exc.error,
                                exc.description, state)
            else:
                _error_page(handler, exc.status or 400,
                            exc.description or exc.error)
            return True
    reply = {"code": code}
    if isinstance(state, str) and state:
        reply["state"] = state
    _redirect(handler, _add_query(redirect_uri, reply))
    return True


def _login_again(handler) -> bool:
    _error_page(handler, 401, "会话对应账号已失效，请重新登录")
    return True


# ------------------------------------------------------------------ 登录/登出
def _login_next(form_or_query, default: str) -> str:
    return _safe_next(_scalar(form_or_query, "next")) or default


def _login_get(handler, query: dict) -> bool:
    if not _admin_configured():
        _error_page(handler, 503,
                    "管理员密码未配置：请设置 HCB_ADMIN_PASSWORD 并重启服务。")
        return True
    secret = _signing_secret()
    token = _login_csrf(secret)
    nxt = _safe_next(_scalar(query, "next")) or ""
    secure = (base_url(handler) or "").startswith("https://")
    _html_response(handler, 200, _login_page(token, nxt), cookies=[
        _cookie_header(LOGIN_COOKIE, token, LOGIN_CSRF_TTL, secure),
    ])
    return True


def _login_post(handler) -> bool:
    if not _rate_ok(handler, "login", LOGIN_LIMIT, LOGIN_WINDOW):
        _too_many(handler, LOGIN_WINDOW)
        return True
    if not _admin_configured():
        _error_page(handler, 503,
                    "管理员密码未配置：请设置 HCB_ADMIN_PASSWORD 并重启服务。")
        return True
    if not _same_origin(handler):
        _error_page(handler, 403, "跨站请求被拒绝")
        return True
    form = _form(handler)
    secret = _signing_secret()
    if not _login_csrf_ok(handler, form, secret):
        _error_page(handler, 403, "CSRF 校验失败")
        return True
    username = _scalar(form, "username", "") or ""
    password = _scalar(form, "password", "") or ""
    nxt = _login_next(form, "/account")
    reply = None
    with hdb.db(0) as con:
        if username.strip().lower() == auth.ADMIN_SUBJECT:
            if auth.admin_check_password(con, password):
                subject, revision = auth.ADMIN_SUBJECT, 0
            else:
                subject = revision = None
        else:
            user = auth.check_user_password(con, username, password)
            subject = auth.subject_for_user(user["id"]) if user is not None else None
            revision = int(user["revision"]) if user is not None else None
        if subject is None:
            _audit_try(con, "auth", username or "anonymous", "auth.login", {
                "username": username, "method": "password"}, "denied")
        else:
            nxt = _login_next(form, "/admin" if subject == auth.ADMIN_SUBJECT
                              else "/account")
            if subject == auth.ADMIN_SUBJECT and auth.admin_totp_state(con)["enabled"]:
                nonce, _ = auth.preauth_create(con, auth.ADMIN_SUBJECT)
                target = "/login/otp"
                if _safe_next(_scalar(form, "next")):
                    target += "?next=" + quote(str(_scalar(form, "next")), safe="")
                reply = ("preauth", target, nonce)
            else:
                _sid, cookie = auth.create_session(
                    con, subject, revision, ua=handler.headers.get("User-Agent", ""))
                audit.append(con, "auth", subject, "auth.login", {
                    "username": username, "method": "password"}, "ok")
                ttl = (auth.ADMIN_SESSION_TTL if subject == auth.ADMIN_SUBJECT
                       else auth.USER_SESSION_TTL)
                reply = ("session", nxt, cookie, ttl)
    if reply is None:
        token = _cookie(handler, LOGIN_COOKIE)
        cookies = []
        if not token:
            token = _login_csrf(secret)
            secure = (base_url(handler) or "").startswith("https://")
            cookies = [_cookie_header(LOGIN_COOKIE, token, LOGIN_CSRF_TTL, secure)]
        _html_response(handler, 401,
                       _login_page(token, nxt, error="用户名或密码错误", status=401),
                       cookies=cookies)
        return True
    ratelimit.reset(f"login:{client_ip(handler)}")
    secure = (base_url(handler) or "").startswith("https://")
    if reply[0] == "preauth":
        _redirect(handler, reply[1], 303, cookies=[
            _cookie_header(auth.PREAUTH_COOKIE, reply[2], auth.PREAUTH_TTL, secure),
            _clear_cookie(LOGIN_COOKIE),
        ])
    else:
        _redirect(handler, reply[1], 303, cookies=[
            _cookie_header(auth.SESSION_COOKIE, reply[2], reply[3], secure),
            _clear_cookie(auth.PREAUTH_COOKIE),
            _clear_cookie(LOGIN_COOKIE),
        ])
    return True


def _otp_get(handler, query: dict) -> bool:
    if not _admin_configured():
        _error_page(handler, 503, "管理员密码未配置")
        return True
    if not _cookie(handler, auth.PREAUTH_COOKIE):
        _redirect(handler, "/login")
        return True
    secret = _signing_secret()
    token = _login_csrf(secret)
    nxt = _safe_next(_scalar(query, "next")) or ""
    secure = (base_url(handler) or "").startswith("https://")
    _html_response(handler, 200, _otp_page(token, nxt), cookies=[
        _cookie_header(LOGIN_COOKIE, token, LOGIN_CSRF_TTL, secure),
    ])
    return True


def _otp_post(handler) -> bool:
    if not _rate_ok(handler, "login", LOGIN_LIMIT, LOGIN_WINDOW):
        _too_many(handler, LOGIN_WINDOW)
        return True
    if not _same_origin(handler):
        _error_page(handler, 403, "跨站请求被拒绝")
        return True
    form = _form(handler)
    secret = _signing_secret()
    if not _login_csrf_ok(handler, form, secret):
        _error_page(handler, 403, "CSRF 校验失败")
        return True
    nonce = _cookie(handler, auth.PREAUTH_COOKIE)
    if not nonce:
        _error_page(handler, 401, "登录已过期，请重新登录")
        return True
    code_value = _scalar(form, "code", "") or ""
    error = None
    cookie = None
    with hdb.db(0) as con:
        subject = auth.preauth_consume(con, nonce)
        if subject != auth.ADMIN_SUBJECT:
            error = "expired"
            _audit_try(con, "auth", auth.ADMIN_SUBJECT, "auth.totp",
                       {"method": "totp"}, "expired")
        else:
            ok = (auth.admin_totp_verify(con, code_value)
                  or auth.admin_recovery_consume(con, code_value))
            if not ok:
                error = "bad-code"
                _audit_try(con, "auth", auth.ADMIN_SUBJECT, "auth.totp",
                           {"method": "totp"}, "denied")
            else:
                _sid, cookie = auth.create_session(
                    con, subject, 0, ua=handler.headers.get("User-Agent", ""))
                audit.append(con, "auth", subject, "auth.login", {
                    "username": subject, "method": "totp"}, "ok")
    if error == "expired":
        _error_page(handler, 401, "登录已过期，请重新登录")
        return True
    if error is not None:
        token = _login_csrf(secret)
        secure = (base_url(handler) or "").startswith("https://")
        _html_response(handler, 401,
                       _login_page(token, "", error="动态码或恢复码不正确，请重新登录",
                                   status=401),
                       cookies=[_cookie_header(LOGIN_COOKIE, token,
                                               LOGIN_CSRF_TTL, secure),
                                _clear_cookie(auth.PREAUTH_COOKIE)])
        return True
    ratelimit.reset(f"login:{client_ip(handler)}")
    secure = (base_url(handler) or "").startswith("https://")
    nxt = _login_next(form, "/admin")
    _redirect(handler, nxt, 303, cookies=[
        _cookie_header(auth.SESSION_COOKIE, cookie, auth.ADMIN_SESSION_TTL, secure),
        _clear_cookie(auth.PREAUTH_COOKIE),
        _clear_cookie(LOGIN_COOKIE),
    ])
    return True


def _logout(handler) -> bool:
    """GET 保留兼容；POST 需要同源 + CSRF（页面用 POST 表单）。"""
    session, secret = _load_session(handler)
    if handler.command == "POST":
        if not _same_origin(handler):
            _error_page(handler, 403, "跨站请求被拒绝")
            return True
        form = _form(handler)
        if session is not None and not auth.csrf_verify(
                secret, session["sid"], _scalar(form, "_csrf")):
            _error_page(handler, 403, "CSRF 校验失败")
            return True
    if session is not None:
        with hdb.db(0) as con:
            auth.revoke_session(con, session["sid"])
    _redirect(handler, "/login", 302, cookies=[_clear_cookie(auth.SESSION_COOKIE)])
    return True


# ------------------------------------------------------------------ 静态资源
def _admin_css(handler) -> bool:
    if handler.command not in ("GET", "HEAD"):
        _method_not_allowed(handler, "GET")
        return True
    css = (
        ":root{color-scheme:light dark;font-family:system-ui,sans-serif}"
        "body{margin:0;background:#f7f7f8;color:#1c1c1e}"
        "header,main{max-width:56rem;margin:0 auto;padding:0 1rem}"
        "header{padding-top:1rem}h1{font-size:1.35rem;margin:.2rem 0 .6rem}"
        "header form{display:inline}nav{line-height:2.2;margin-bottom:.5rem}"
        "nav a{display:inline-block;margin:0 .5rem .2rem 0}label,input,button,select,textarea{font:inherit}"
        "input,select,textarea{width:100%;padding:.5rem;margin:.25rem 0 .6rem;box-sizing:border-box}"
        "input[type=checkbox]{width:auto;margin:0 .3rem 0 0}"
        "button{padding:.55rem 1rem;margin-right:.5rem;margin-top:.2rem}"
        "label.check{display:inline-block;margin:.2rem 1rem .2rem 0}"
        ".field{margin:.2rem 0 .5rem}.field .label{display:block}"
        ".hint{display:block;color:#666;font-size:.85rem}"
        ".ok,.err,.warn{padding:.6rem .8rem;border-radius:.4rem;border:1px solid}"
        ".ok{border-color:#2e7d32}.err{border-color:#b3261e}.warn{border-color:#a15c00}"
        ".muted{color:#666}.identity{color:#555}code{font-size:.9em;word-break:break-all}"
        "pre.secret{overflow-x:auto;padding:.7rem;border:1px dashed #888;border-radius:.4rem}"
        ".scroll{overflow-x:auto}table{border-collapse:collapse;width:100%;margin:.5rem 0 1rem}"
        "th,td{text-align:left;vertical-align:top;padding:.4rem .5rem;border-bottom:1px solid #ddd}"
        "th{font-weight:600;white-space:nowrap}td form{margin:0}td button{padding:.25rem .6rem}"
        ".badge{display:inline-block;padding:.1rem .4rem;border:1px solid #999;border-radius:1rem;font-size:.8em}"
        "form.danger button{border-color:#b3261e;color:#b3261e}"
        "fieldset{border:1px solid #ccc;border-radius:.5rem}.filters{display:flex;flex-wrap:wrap;gap:.5rem}"
        ".filters .field{flex:1 1 10rem}"
        "@media(prefers-color-scheme:dark){body{background:#111;color:#eee}"
        ".hint,.muted,.identity{color:#aaa}th,td{border-color:#333}a{color:#8ab4f8}}"
    ).encode("utf-8")
    _send(handler, 200, css, "text/css; charset=utf-8",
          [("Cache-Control", "public, max-age=300")])
    return True


# ------------------------------------------------------------------ 分发
def handle(handler) -> bool:
    """处理本模块负责的路由；不认识返回 ``False``。"""
    try:
        parts = urlsplit(handler.path)
        path = parts.path
        method = handler.command
        if path == "/admin.css":
            return _admin_css(handler)
        if path in ("/admin/", "/account/"):
            target = path.rstrip("/")
            if parts.query:
                target += "?" + parts.query
            _redirect(handler, target, 301)
            return True
        if path.startswith("/.well-known/"):
            return _well_known(handler, path)
        if path == "/register":
            return _register(handler)
        if path == "/token":
            return _token(handler)
        if path == "/revoke":
            return _revoke(handler)
        if path == "/authorize":
            if method in ("GET", "HEAD"):
                return _authorize_get(handler,
                                      parse_qs(parts.query, keep_blank_values=True))
            if method == "POST":
                return _authorize_post(handler)
            _method_not_allowed(handler, "GET, POST")
            return True
        if path == "/login":
            if method in ("GET", "HEAD"):
                return _login_get(handler,
                                  parse_qs(parts.query, keep_blank_values=True))
            if method == "POST":
                return _login_post(handler)
            _method_not_allowed(handler, "GET, POST")
            return True
        if path == "/login/otp":
            if method in ("GET", "HEAD"):
                return _otp_get(handler,
                                parse_qs(parts.query, keep_blank_values=True))
            if method == "POST":
                return _otp_post(handler)
            _method_not_allowed(handler, "GET, POST")
            return True
        if path == "/logout":
            if method in ("GET", "HEAD", "POST"):
                return _logout(handler)
            _method_not_allowed(handler, "GET, POST")
            return True
        if path == "/admin" or path.startswith("/admin/") \
                or path == "/account" or path.startswith("/account/"):
            from . import admin as admin_ui
            return admin_ui.handle(handler)
        return False
    except _TooLarge:
        _json(handler, 413, {"error": "payload_too_large"})
        return True
    except Exception as exc:  # noqa: BLE001
        # 只记录异常类型与路由，绝不打印请求体/令牌/Cookie 或 traceback 文本。
        sys.stderr.write(f"[web] {handler.command} {urlsplit(handler.path).path}: "
                         f"{type(exc).__name__}\n")
        _error_page(handler, 500, "服务器内部错误")
        return True
