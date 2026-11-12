"""OAuth 2.1 授权服务器核心：客户端注册、授权码 + PKCE、刷新与撤销。

设计：``docs/superpowers/specs/2026-11-02-oauth-admin-design.md`` §6、§7、§8.1–8.2、§10。

约定
----
- 所有函数接收调用方打开的 ``sqlite3.Connection``。客户端/授权码/令牌表只在
  owner 0，因此调用方必须用 ``with hcb.db.db(0) as con:``。
- 授权码、访问令牌、刷新令牌、client secret 都是不透明随机串；数据库只存
  ``SHA-256``（``hcb.auth.hash_token``）。明文只在签发函数返回给调用方一次。
- 资源标识（canonical resource）由调用方传入（生产为
  ``https://health.example.com/mcp``）；本模块不读环境变量。
- 失败一律抛 ``OAuthError``，只带 RFC 错误码与固定说明，不带内部细节。
- ``validate_authorize`` / ``issue_code`` 负责校验授权请求；同意页的签名请求
  由 Task 4 的 Web 层负责，本模块只校验并保存请求字段。
- ``oauth_codes.session_sid`` 存会话标识的 ``SHA-256``（传原始 sid 时自动哈希）。
- 令牌级 ``tool_policy_json`` / ``window_json`` 在签发时按 scope 生成；刷新时
  原样继承。损坏的 JSON（含用户/客户端层的 policy/window）一律 fail-closed。
- 访问令牌“过期/已撤销”只拒绝本次请求，**不**吊销 family；刷新令牌仍可继续用
  （否则 1 小时的访问令牌到期会连坐 30 天的刷新令牌）。只有绑定类故障才吊销。
- ``capabilities_for_scopes`` 返回 ``None`` 只表示 scope 层不额外限制；``ingest``
  永不进入 OAuth 白名单，Task 5 必须对 OAuth principal 显式拒绝 ``ingest``。
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import re
import secrets
import sqlite3
import time
from datetime import datetime
from typing import Any, Mapping
from urllib.parse import urlsplit

from . import auth

SCOPES = ("health.read", "health.write")

# 只读工具（设计 §8.1）。新工具默认算写工具；``ingest`` 永不进入 OAuth 白名单。
READ_TOOLS = (
    "health_status",
    "query_heart_rate",
    "query_spo2",
    "query_metric_series",
    "query_sleep",
    "get_daily_summary",
    "query_workouts",
    "query_weight",
    "query_stress",
    "query_raw",
    "query_body_composition",
    "sources",
    "get_data_coverage",
    "query_sleep_stages",
    "health_export_csv",
)
# 写工具：会改库或触发外部同步。``ingest`` 不在此列，只接受静态 Bearer。
WRITE_TOOLS = ("sync_now", "backfill", "sleep_audit")

CODE_TTL = 5 * 60
ACCESS_TTL = 60 * 60
REFRESH_TTL = 30 * 24 * 60 * 60
MAX_CLIENTS = 200
MAX_REDIRECT_URIS = 5
MAX_REDIRECT_URI_LENGTH = 2048
LAST_USED_UPDATE = 60

GRANT_TYPES = ("authorization_code", "refresh_token")
AUTH_METHODS = ("none", "client_secret_post")
LOOPBACK_HOSTS = frozenset({"localhost", "127.0.0.1", "::1"})
_PKCE_RE = re.compile(r"^[A-Za-z0-9\-._~]{43,128}$")
_CONTROL_RE = re.compile(r"[\x00-\x1f\x7f]")
_HASH_RE = re.compile(r"^[0-9a-f]{64}$")
_CLIENT_PREFIX = "hc_"
_SECRET_PREFIX = "cs_"
CODE_PREFIX = "oc_"
ACCESS_PREFIX = "oa_"
REFRESH_PREFIX = "or_"


class OAuthError(Exception):
    """带 RFC 错误码的 OAuth 错误；``redirect_uri`` / ``state`` 仅在可安全回跳时给出。"""

    def __init__(self, error: str, description: str = "", *, status: int = 400,
                 redirect_uri: str | None = None, state: str | None = None,
                 reason: str | None = None) -> None:
        super().__init__(description or error)
        self.error = error
        self.description = description
        self.status = status
        self.redirect_uri = redirect_uri
        self.state = state
        # 仅供审计的稳定原因码（不出现在响应里）。
        self.reason = reason

    def to_dict(self) -> dict[str, str]:
        out = {"error": self.error}
        if self.description:
            out["error_description"] = self.description
        return out


# ------------------------------------------------------------------ 小工具
def _touch(con, sql: str, params: tuple) -> None:
    try:
        con.execute(sql, params)
    except sqlite3.OperationalError:
        pass


def _now(value: object = None) -> int:
    if value is None:
        return int(time.time())
    if isinstance(value, datetime):
        return int(value.timestamp())
    if isinstance(value, bool):
        raise ValueError("now must be a timestamp")
    return int(value)  # type: ignore[arg-type]


def _as_int(value: object) -> int | None:
    """坏时间戳/ID 返回 ``None``，交给调用方 fail-closed，而不是抛 500。"""
    if isinstance(value, bool):
        return None
    try:
        return int(value)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return None


def _field(data: Mapping[str, Any] | None, name: str, *aliases: object) -> object:
    """从表单/JSON dict 取值；兼容 camelCase 别名与 parse_qs 的单值列表。"""
    if data is None:
        return None
    for key in (name, *aliases):
        try:
            value = data[key]  # type: ignore[index]
        except (KeyError, TypeError):
            continue
        if (isinstance(value, (list, tuple)) and len(value) == 1
                and key not in ("redirect_uris", "redirectUris")):
            value = value[0]
        return value
    return None


def _text(value: object) -> str | None:
    if not isinstance(value, str):
        return None
    text = value.strip()
    return text or None


def _random_token(prefix: str, nbytes: int = 32) -> str:
    return prefix + base64.urlsafe_b64encode(secrets.token_bytes(nbytes)).decode("ascii").rstrip("=")


def _compact(value: object) -> str:
    return json.dumps(value, separators=(",", ":"), ensure_ascii=False)


def _sid_key(value: object) -> str | None:
    """会话标识落库前统一为 SHA-256；已经是 64 位小写十六进制则原样使用。"""
    if not isinstance(value, str) or not value:
        return None
    if _HASH_RE.fullmatch(value):
        return value
    return auth.hash_token(value)


# ------------------------------------------------------------------ scope / PKCE
def normalize_scopes(scopes: object) -> list[str]:
    """空格分隔串或列表 → 按固定顺序去重；未知/非字符串 scope 抛 ``invalid_scope``。"""
    if scopes is None:
        return []
    if isinstance(scopes, str):
        parts: list[object] = scopes.split()
    elif isinstance(scopes, (list, tuple, set, frozenset)):
        parts = list(scopes)
    else:
        raise OAuthError("invalid_scope", "scope 无效")
    requested: set[str] = set()
    for raw in parts:
        if not isinstance(raw, str):
            raise OAuthError("invalid_scope", "scope 无效")
        for piece in raw.split():
            if piece not in SCOPES:
                raise OAuthError("invalid_scope", "不支持的 scope")
            requested.add(piece)
    return [s for s in SCOPES if s in requested]


def _scopes_from_json(value: object) -> list[str]:
    """数据库里的 scope JSON → 列表；损坏/未知一律返回空（调用方 fail-closed）。"""
    try:
        parsed = json.loads(value) if isinstance(value, str) else value
    except (json.JSONDecodeError, TypeError):
        return []
    if not isinstance(parsed, list):
        return []
    return [s for s in SCOPES if s in parsed]


def capabilities_for_scopes(scopes: object) -> list[str] | None:
    """scope → 工具白名单。两个 scope = 不额外限制（``None``）；``ingest`` 永不出现。"""
    granted = normalize_scopes(scopes)
    has_read = "health.read" in granted
    has_write = "health.write" in granted
    if not granted:
        raise OAuthError("invalid_scope", "至少需要一个 scope")
    if has_read and has_write:
        return None
    if has_read:
        return list(READ_TOOLS)
    return list(WRITE_TOOLS)


def pkce_challenge(verifier: object) -> str:
    """S256：``base64url(sha256(verifier))``，去掉 padding；供测试与调用方使用。"""
    if not isinstance(verifier, str):
        raise ValueError("code_verifier must be text")
    return base64.urlsafe_b64encode(
        hashlib.sha256(verifier.encode("utf-8")).digest()
    ).decode("ascii").rstrip("=")


def _valid_challenge(value: object) -> bool:
    return isinstance(value, str) and bool(_PKCE_RE.fullmatch(value))


def verify_pkce(verifier: object, challenge: object) -> bool:
    if not _valid_challenge(challenge) or not _valid_challenge(verifier):
        return False
    return hmac.compare_digest(pkce_challenge(verifier), challenge)  # type: ignore[arg-type]


# ------------------------------------------------------------------ JSON 列
def _parse_json(raw: object) -> tuple[object, bool]:
    if raw is None:
        return None, False
    if not isinstance(raw, str):
        return None, True
    try:
        return json.loads(raw), False
    except json.JSONDecodeError:
        return None, True


def _policy_value(raw: object) -> tuple[list[Any] | None, bool]:
    if raw is None:
        return None, False
    parsed, corrupt = _parse_json(raw)
    if corrupt or parsed is None:
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
        if not isinstance(item.get("tool"), str) or not item["tool"]:
            return None, True
        if "args" in item and item["args"] is not None and not isinstance(item["args"], dict):
            return None, True
    return parsed, False


def _window_value(raw: object) -> tuple[dict[str, Any] | None, bool]:
    if raw is None:
        return None, False
    parsed, corrupt = _parse_json(raw)
    if corrupt or parsed is None:
        return None, True
    if not isinstance(parsed, dict):
        return None, True
    return parsed, False


def _policy_window(policy_raw: object, window_raw: object
                   ) -> tuple[list[Any] | None, dict[str, Any] | None, bool]:
    """解析一层 policy/window；返回 ``(policy, window, corrupt)``。"""
    policy, policy_corrupt = _policy_value(policy_raw)
    window, window_corrupt = _window_value(window_raw)
    return policy, window, (policy_corrupt or window_corrupt)


def _policy_json(value: object, field: str, error: str) -> str | None:
    """注册/后台传入的 policy：列表或 JSON 串；``None`` 表示不限制。"""
    if value is None:
        return None
    parsed, corrupt = _policy_value(value)
    if corrupt:
        raise OAuthError(error, f"{field} 无效")
    return _compact(parsed)


def _window_json(value: object, field: str, error: str) -> str | None:
    if value is None:
        return None
    parsed, corrupt = _window_value(value)
    if corrupt:
        raise OAuthError(error, f"{field} 无效")
    return _compact(parsed)


# ------------------------------------------------------------------ 客户端
def _redirect_uris(raw: object) -> tuple[list[str], bool]:
    try:
        parsed = json.loads(raw) if isinstance(raw, str) else raw
    except (json.JSONDecodeError, TypeError):
        return [], True
    if not isinstance(parsed, list) or not all(isinstance(x, str) for x in parsed):
        return [], True
    return parsed, False


def _client_dict(row: sqlite3.Row) -> dict[str, Any]:
    uris, uris_corrupt = _redirect_uris(row["redirect_uris_json"])
    policy, policy_corrupt = _policy_value(row["tool_policy_json"])
    window, window_corrupt = _window_value(row["window_json"])
    return {
        "id": row["id"],
        "client_id": row["client_id"],
        "client_name": row["client_name"],
        "redirect_uris": uris,
        "redirect_uris_corrupt": uris_corrupt,
        "token_endpoint_auth_method": row["token_endpoint_auth_method"],
        "confidential": row["client_secret_hash"] is not None,
        "has_secret": row["client_secret_hash"] is not None,
        "tool_policy_json": row["tool_policy_json"],
        "window_json": row["window_json"],
        "tool_policy": policy,
        "window": window,
        "policy_corrupt": policy_corrupt,
        "window_corrupt": window_corrupt,
        "created_at": row["created_at"],
        "last_used_at": row["last_used_at"],
        "revoked_at": row["revoked_at"],
        "revoked": row["revoked_at"] is not None,
    }


def _client_row(con: sqlite3.Connection, client_id: object) -> sqlite3.Row | None:
    if not isinstance(client_id, str) or not client_id:
        return None
    return con.execute("SELECT * FROM oauth_clients WHERE client_id=?", (client_id,)).fetchone()


def _valid_redirect_uri(value: object) -> str:
    if not isinstance(value, str) or not value or len(value) > MAX_REDIRECT_URI_LENGTH:
        raise OAuthError("invalid_redirect_uri", "redirect_uri 无效")
    if "*" in value or "#" in value or _CONTROL_RE.search(value):
        raise OAuthError("invalid_redirect_uri", "redirect_uri 不允许通配符、fragment 或控制字符")
    try:
        url = urlsplit(value)
    except ValueError:
        raise OAuthError("invalid_redirect_uri", "redirect_uri 不是绝对地址") from None
    if not url.scheme or not url.netloc:
        raise OAuthError("invalid_redirect_uri", "redirect_uri 不是绝对地址")
    if url.username or url.password:
        raise OAuthError("invalid_redirect_uri", "redirect_uri 不允许用户信息")
    scheme = url.scheme.lower()
    host = (url.hostname or "").lower()
    if scheme == "https":
        return value
    if scheme == "http" and host in LOOPBACK_HOSTS:
        return value
    raise OAuthError("invalid_redirect_uri", "redirect_uri 只允许 https（本机回环可用 http）")


def _begin_immediate(con: sqlite3.Connection) -> bool:
    """需要“计数 + 插入”原子时开启写事务；已在事务中则沿用调用方事务。"""
    if con.in_transaction:
        return False
    con.execute("BEGIN IMMEDIATE")
    return True


def register_client(con: sqlite3.Connection, data: Mapping[str, Any] | None = None,
                    *, now: object = None, **kwargs: object) -> dict[str, Any]:
    """RFC 7591 动态注册；返回含一次性 ``client_secret`` 的 dict（机密客户端）。

    第二个参数是字段 dict，也可直接把字段作为关键字参数传入。
    """
    data = {**(data or {}), **kwargs}
    name = _field(data, "client_name", "clientName")
    if not isinstance(name, str):
        raise OAuthError("invalid_client_metadata", "client_name 无效")
    name = name.strip()
    if not name or len(name) > 80 or _CONTROL_RE.search(name):
        raise OAuthError("invalid_client_metadata", "client_name 需为 1-80 个字符")

    uris = _field(data, "redirect_uris", "redirectUris")
    if not isinstance(uris, (list, tuple)) or not uris:
        raise OAuthError("invalid_redirect_uri", "redirect_uris 不能为空")
    if len(uris) > MAX_REDIRECT_URIS:
        raise OAuthError("invalid_redirect_uri", f"redirect_uris 最多 {MAX_REDIRECT_URIS} 条")
    cleaned: list[str] = []
    for uri in uris:
        valid = _valid_redirect_uri(uri)
        if valid not in cleaned:
            cleaned.append(valid)

    method = _field(data, "token_endpoint_auth_method", "tokenEndpointAuthMethod") or "none"
    if method not in AUTH_METHODS:
        raise OAuthError("invalid_client_metadata", "不支持的 token_endpoint_auth_method")

    tool_policy = _policy_json(_field(data, "tool_policy_json", "toolPolicy"),
                               "tool_policy_json", "invalid_client_metadata")
    window = _window_json(_field(data, "window_json", "window"),
                          "window_json", "invalid_client_metadata")

    ts = _now(now)
    client_id = _random_token(_CLIENT_PREFIX, 16)
    secret = _random_token(_SECRET_PREFIX) if method == "client_secret_post" else None
    # “200 上限”的计数与插入必须在同一个写事务里，否则并发注册可越过上限。
    started = _begin_immediate(con)
    try:
        active = con.execute(
            "SELECT COUNT(*) FROM oauth_clients WHERE revoked_at IS NULL").fetchone()[0]
        if int(active) >= MAX_CLIENTS:
            raise OAuthError("invalid_client_metadata", "客户端数量已达上限", status=429)
        con.execute(
            "INSERT INTO oauth_clients(client_id, client_secret_hash, client_name, "
            "redirect_uris_json, token_endpoint_auth_method, tool_policy_json, window_json, "
            "created_at, last_used_at, revoked_at) VALUES(?,?,?,?,?,?,?,?,NULL,NULL)",
            (client_id, auth.hash_token(secret) if secret else None, name, _compact(cleaned),
             method, tool_policy, window, ts),
        )
        auth.mark_auth_configured(con)
    except BaseException:
        if started:
            con.rollback()
        raise
    response: dict[str, Any] = {
        "client_id": client_id,
        "client_id_issued_at": ts,
        "client_secret_expires_at": 0,
        "client_name": name,
        "redirect_uris": cleaned,
        "token_endpoint_auth_method": method,
        "grant_types": list(GRANT_TYPES),
        "confidential": secret is not None,
    }
    if secret is not None:
        response["client_secret"] = secret
    return response


def get_client(con: sqlite3.Connection, client_id: object) -> dict[str, Any] | None:
    """按 client_id 取客户端；永远不返回 client_secret 或其哈希。"""
    row = _client_row(con, client_id)
    return _client_dict(row) if row is not None else None


def list_clients(con: sqlite3.Connection, *, include_revoked: bool = False) -> list[dict[str, Any]]:
    sql = "SELECT * FROM oauth_clients"
    if not include_revoked:
        sql += " WHERE revoked_at IS NULL"
    sql += " ORDER BY id"
    return [_client_dict(row) for row in con.execute(sql).fetchall()]


def _authenticate_client(con: sqlite3.Connection, client_id: object,
                         client_secret: object = None) -> dict[str, Any]:
    row = _client_row(con, client_id)
    if row is None or row["revoked_at"] is not None:
        raise OAuthError("invalid_client", "客户端认证失败", status=401)
    method = row["token_endpoint_auth_method"]
    if method == "none":
        if client_secret not in (None, ""):
            raise OAuthError("invalid_client", "客户端认证失败", status=401)
    elif method == "client_secret_post":
        if not isinstance(client_secret, str) or not client_secret:
            raise OAuthError("invalid_client", "客户端认证失败", status=401)
        expected = row["client_secret_hash"]
        if not isinstance(expected, str) or not auth.constant_eq(
                auth.hash_token(client_secret), expected):
            raise OAuthError("invalid_client", "客户端认证失败", status=401)
    else:  # 数据库里出现未知认证方式：fail-closed
        raise OAuthError("invalid_client", "客户端认证失败", status=401)
    return _client_dict(row)


def authenticate_client(con: sqlite3.Connection, client_id: object,
                        client_secret: object = None) -> dict[str, Any]:
    """Token/撤销端点客户端认证；失败抛 ``invalid_client``。"""
    return _authenticate_client(con, client_id, client_secret)


def revoke_client(con: sqlite3.Connection, client_id: object, *, now: object = None) -> bool:
    """删除应用：吊销客户端、全部令牌与未兑换的授权码。"""
    row = _client_row(con, client_id)
    if row is None or row["revoked_at"] is not None:
        return False
    ts = _now(now)
    cur = con.execute(
        "UPDATE oauth_clients SET revoked_at=? WHERE client_id=? AND revoked_at IS NULL",
        (ts, row["client_id"]),
    )
    if cur.rowcount != 1:
        return False
    con.execute(
        "UPDATE oauth_tokens SET revoked_at=? WHERE client_id=? AND revoked_at IS NULL",
        (ts, row["client_id"]),
    )
    con.execute("DELETE FROM oauth_codes WHERE client_id=?", (row["client_id"],))
    return True


# ------------------------------------------------------------------ 授权请求
def _resolve_resource(given: object, canonical: object, error: str) -> str:
    text = _text(given)
    canon = _text(canonical)
    if canon is not None:
        if text is not None and text != canon:
            raise OAuthError(error, "resource 不匹配")
        return canon
    if text is not None:
        return text
    raise OAuthError(error, "缺少 resource")


def _read_param(params: object, key: str) -> str | None:
    """读 URL 参数：dict / parse_qs 列表 / 带 ``get`` 的对象。"""
    if params is None:
        return None
    getter = getattr(params, "get", None)
    if not callable(getter):
        return None
    try:
        value = getter(key)
    except (KeyError, TypeError):
        return None
    if isinstance(value, (list, tuple)):
        value = value[0] if value else None
    if value is None:
        return None
    return value if isinstance(value, str) else str(value)


def validate_authorize(con: sqlite3.Connection, params: object, canonical_resource: object,
                       *, allowed_scopes: object = None) -> dict[str, Any]:
    """校验 ``GET /authorize`` 参数；成功返回同意页/签发所需的规范化字段。

    ``allowed_scopes`` 是当前登录用户的 scope 上限；省略时按全部 scope。
    客户端/redirect 未通过时不带可回跳地址；之后出现的错误会带上
    ``redirect_uri`` 与 ``state``，Web 层可据此 ``302`` 回客户端。
    """
    client_id = _read_param(params, "client_id")
    client = get_client(con, client_id)
    if client is None or client["revoked"]:
        raise OAuthError("invalid_client", "未知或已停用的客户端", status=401)

    redirect_uri = _read_param(params, "redirect_uri")
    if (not isinstance(redirect_uri, str) or not redirect_uri
            or client["redirect_uris_corrupt"]
            or redirect_uri not in client["redirect_uris"]):
        raise OAuthError("invalid_request", "redirect_uri 未注册")

    state = _read_param(params, "state")

    def fail(error: str, description: str) -> OAuthError:
        return OAuthError(error, description, redirect_uri=redirect_uri, state=state)

    if _read_param(params, "response_type") != "code":
        raise fail("unsupported_response_type", "只支持 response_type=code")
    if _read_param(params, "code_challenge_method") != "S256":
        raise fail("invalid_request", "只支持 code_challenge_method=S256")
    challenge = _read_param(params, "code_challenge")
    if not _valid_challenge(challenge):
        raise fail("invalid_request", "code_challenge 无效")
    try:
        resource = _resolve_resource(_read_param(params, "resource"),
                                     canonical_resource, "invalid_request")
        ceiling = normalize_scopes(allowed_scopes) if allowed_scopes is not None else list(SCOPES)
        raw_scope = _read_param(params, "scope")
        scopes = normalize_scopes(raw_scope) if raw_scope and raw_scope.strip() else list(ceiling)
        if not scopes or any(s not in ceiling for s in scopes):
            raise fail("invalid_scope", "请求的 scope 超出用户上限")
    except OAuthError as e:
        if e.redirect_uri is None:
            raise fail(e.error, e.description) from None
        raise

    return {
        "client": client,
        "client_id": client["client_id"],
        "client_name": client["client_name"],
        "redirect_uri": redirect_uri,
        "scopes": scopes,
        "scope": " ".join(scopes),
        "resource": resource,
        "state": state,
        "code_challenge": challenge,
        "code_challenge_method": "S256",
        "response_type": "code",
    }


# ------------------------------------------------------------------ 授权码
def issue_code(con: sqlite3.Connection, data: Mapping[str, Any] | None = None,
               *, canonical_resource: object = None, now: object = None,
               **kwargs: object) -> str:
    """签发一次性授权码（5 分钟），返回明文 ``oc_...``；库里只有哈希。"""
    data = {**(data or {}), **kwargs}
    client_id = _field(data, "client_id", "clientId")
    client = get_client(con, client_id)
    if client is None or client["revoked"]:
        raise OAuthError("invalid_client", "未知或已停用的客户端", status=401)

    redirect_uri = _field(data, "redirect_uri", "redirectUri")
    if (not isinstance(redirect_uri, str) or not redirect_uri
            or client["redirect_uris_corrupt"]
            or redirect_uri not in client["redirect_uris"]):
        raise OAuthError("invalid_request", "redirect_uri 未注册")

    challenge = _field(data, "code_challenge", "codeChallenge")
    if not _valid_challenge(challenge):
        raise OAuthError("invalid_request", "code_challenge 无效")

    scopes = normalize_scopes(_field(data, "scopes", "scope"))
    if not scopes:
        raise OAuthError("invalid_scope", "至少需要授予一个 scope")
    resource = _resolve_resource(_field(data, "resource"), canonical_resource, "invalid_request")

    user_id = _field(data, "user_id", "userId")
    if isinstance(user_id, bool) or not isinstance(user_id, int) or user_id < 0:
        raise OAuthError("invalid_request", "缺少用户身份")
    if user_id == 0:
        if _field(data, "username") != auth.ADMIN_SUBJECT:
            raise OAuthError("invalid_request", "管理员身份无效")
        username, revision = auth.ADMIN_SUBJECT, 0
    else:
        user = auth.get_user(con, user_id)
        if user is None or not user["enabled"]:
            raise OAuthError("invalid_grant", "用户不存在或已停用")
        if any(s not in user["scopes"] for s in scopes):
            raise OAuthError("invalid_scope", "授予的 scope 超出用户上限")
        revision = _as_int(user["revision"])
        if revision is None:
            raise OAuthError("invalid_grant", "用户记录损坏")
        username = user["username"]

    sid_key = _sid_key(_field(data, "session_sid", "sessionSid", "sid"))
    if sid_key is None:
        raise OAuthError("invalid_request", "缺少网页会话")

    code = _random_token(CODE_PREFIX)
    ts = _now(now)
    con.execute(
        "INSERT INTO oauth_codes(code_hash, client_id, redirect_uri, code_challenge, "
        "scopes_json, resource, session_sid, user_id, username, user_revision, "
        "created_at, expires_at, consumed_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,NULL)",
        (auth.hash_token(code), client["client_id"], redirect_uri, challenge,
         _compact(scopes), resource, sid_key, user_id, username, revision,
         ts, ts + CODE_TTL),
    )
    return code


# ------------------------------------------------------------------ 令牌签发
def _current_ceiling(con: sqlite3.Connection, user_id: int,
                     revision: int) -> dict[str, Any] | None:
    if user_id == 0:
        return {"user_id": 0, "username": auth.ADMIN_SUBJECT,
                "revision": 0, "scopes": list(SCOPES), "user": None}
    user = auth.get_user(con, user_id)
    if user is None or not user["enabled"]:
        return None
    row_revision = _as_int(user["revision"])
    row_id = _as_int(user["id"])
    if row_revision is None or row_id is None or row_revision != revision:
        return None
    return {
        "user_id": row_id,
        "username": user["username"],
        "revision": row_revision,
        "scopes": list(user["scopes"]),
        "user": user,
    }


def _policy_for_scopes(scopes: list[str]) -> str | None:
    caps = capabilities_for_scopes(scopes)
    return None if caps is None else _compact(caps)


def _insert_token(con: sqlite3.Connection, raw: str, kind: str, *, client_id: str,
                  family_id: str, scopes: list[str], tool_policy_json: str | None,
                  window_json: str | None, user_id: int, username: str,
                  user_revision: int, resource: str, ts: int) -> None:
    con.execute(
        "INSERT INTO oauth_tokens(token_hash, prefix, kind, client_id, family_id, "
        "scopes_json, tool_policy_json, window_json, user_id, username, user_revision, "
        "resource, created_at, expires_at, revoked_at, replaced_by_hash, last_used_at) "
        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL,NULL,NULL)",
        (auth.hash_token(raw), raw[:12], kind, client_id, family_id, _compact(scopes),
         tool_policy_json, window_json, user_id, username, user_revision, resource,
         ts, ts + (ACCESS_TTL if kind == "access" else REFRESH_TTL)),
    )


def _issue_pair(con: sqlite3.Connection, *, client_id: str, family_id: str,
                scopes: list[str], tool_policy_json: str | None,
                window_json: str | None, user_id: int, username: str,
                user_revision: int, resource: str, ts: int) -> dict[str, Any]:
    access = _random_token(ACCESS_PREFIX)
    refresh = _random_token(REFRESH_PREFIX)
    common = dict(client_id=client_id, family_id=family_id, scopes=scopes,
                  tool_policy_json=tool_policy_json, window_json=window_json,
                  user_id=user_id, username=username, user_revision=user_revision,
                  resource=resource, ts=ts)
    _insert_token(con, access, "access", **common)
    _insert_token(con, refresh, "refresh", **common)
    return {
        "access_token": access,
        "token_type": "Bearer",
        "expires_in": ACCESS_TTL,
        "refresh_token": refresh,
        "scope": " ".join(scopes),
    }


def exchange_code(con: sqlite3.Connection, data: Mapping[str, Any] | None = None,
                  *, canonical_resource: object = None, now: object = None,
                  **kwargs: object) -> dict[str, Any]:
    """授权码 + PKCE → 访问令牌（1 小时）+ 刷新令牌（30 天）。"""
    data = {**(data or {}), **kwargs}
    client = _authenticate_client(con, _field(data, "client_id", "clientId"),
                                  _field(data, "client_secret", "clientSecret"))
    if client["policy_corrupt"] or client["window_corrupt"]:
        raise OAuthError("invalid_grant", "授权数据损坏")
    code = _field(data, "code")
    verifier = _field(data, "code_verifier", "verifier", "codeVerifier")
    redirect_uri = _field(data, "redirect_uri", "redirectUri")
    if (not isinstance(code, str) or not code
            or not isinstance(verifier, str) or not verifier
            or not isinstance(redirect_uri, str) or not redirect_uri):
        raise OAuthError("invalid_request", "缺少授权码、code_verifier 或 redirect_uri")

    ts = _now(now)
    code_hash = auth.hash_token(code)
    row = con.execute("SELECT * FROM oauth_codes WHERE code_hash=?", (code_hash,)).fetchone()
    if row is None or row["consumed_at"] is not None:
        raise OAuthError("invalid_grant", "授权码无效或已使用")
    expires_at = _as_int(row["expires_at"])
    if expires_at is None:
        raise OAuthError("invalid_grant", "授权码无效或已使用")
    if expires_at <= ts:
        raise OAuthError("invalid_grant", "授权码已过期")
    if row["client_id"] != client["client_id"]:
        raise OAuthError("invalid_grant", "授权码无效或已使用")
    if row["redirect_uri"] != redirect_uri:
        raise OAuthError("invalid_grant", "授权码无效或已使用")

    given_resource = _field(data, "resource")
    resolved = _resolve_resource(given_resource if _text(given_resource) else row["resource"],
                                 canonical_resource, "invalid_grant")
    if resolved != row["resource"]:
        raise OAuthError("invalid_grant", "resource 不匹配")

    if not verify_pkce(verifier, row["code_challenge"]):
        raise OAuthError("invalid_grant", "code_verifier 不匹配")

    scopes = _scopes_from_json(row["scopes_json"])
    if not scopes:
        raise OAuthError("invalid_grant", "授权码无效或已使用")
    user_id = _as_int(row["user_id"])
    user_revision = _as_int(row["user_revision"])
    if user_id is None or user_revision is None:
        raise OAuthError("invalid_grant", "授权码无效或已使用")
    ceiling = _current_ceiling(con, user_id, user_revision)
    if ceiling is None or any(s not in ceiling["scopes"] for s in scopes):
        raise OAuthError("invalid_grant", "授权已失效")
    user = ceiling["user"]
    _up, _uw, user_corrupt = _policy_window(
        user.get("tool_policy_json") if user is not None else None,
        user.get("window_json") if user is not None else None,
    )
    if user_corrupt:
        raise OAuthError("invalid_grant", "授权数据损坏")

    # 条件 UPDATE 是“一次性”的并发锁：只有一个调用方能消费。
    cur = con.execute(
        "UPDATE oauth_codes SET consumed_at=? WHERE code_hash=? AND consumed_at IS NULL "
        "AND expires_at > ?",
        (ts, code_hash, ts),
    )
    if cur.rowcount != 1:
        raise OAuthError("invalid_grant", "授权码无效或已使用")

    try:
        policy = _policy_for_scopes(scopes)
    except OAuthError:
        raise OAuthError("invalid_grant", "授权码无效或已使用") from None

    family_id = _random_token("fam_", 16)
    result = _issue_pair(
        con, client_id=client["client_id"], family_id=family_id, scopes=scopes,
        tool_policy_json=policy, window_json=None,
        user_id=user_id, username=ceiling["username"],
        user_revision=ceiling["revision"], resource=row["resource"], ts=ts,
    )
    con.execute("UPDATE oauth_clients SET last_used_at=? WHERE client_id=?",
                (ts, client["client_id"]))
    return result


def refresh(con: sqlite3.Connection, data: Mapping[str, Any] | None = None,
            *, canonical_resource: object = None, now: object = None,
            **kwargs: object) -> dict[str, Any]:
    """刷新令牌严格轮换；旧刷新令牌再次出现即视为重用并吊销整个 family。"""
    data = {**(data or {}), **kwargs}
    client = _authenticate_client(con, _field(data, "client_id", "clientId"),
                                  _field(data, "client_secret", "clientSecret"))
    raw = _field(data, "refresh_token", "refreshToken")
    if not isinstance(raw, str) or not raw.startswith(REFRESH_PREFIX):
        raise OAuthError("invalid_grant", "刷新令牌无效")

    ts = _now(now)
    token_hash = auth.hash_token(raw)
    row = con.execute("SELECT * FROM oauth_tokens WHERE token_hash=?", (token_hash,)).fetchone()
    if row is None or row["kind"] != "refresh":
        raise OAuthError("invalid_grant", "刷新令牌无效")
    if row["client_id"] != client["client_id"]:
        raise OAuthError("invalid_grant", "刷新令牌无效")
    if client["policy_corrupt"] or client["window_corrupt"]:
        _revoke_family(con, row["family_id"], ts)
        raise OAuthError("invalid_grant", "刷新令牌的授权数据损坏")

    if row["revoked_at"] is not None:
        _revoke_family(con, row["family_id"], ts)
        raise OAuthError("invalid_grant", "刷新令牌已使用，授权已吊销",
                         reason="refresh_reuse")

    _old_policy, _old_window, token_corrupt = _policy_window(
        row["tool_policy_json"], row["window_json"])
    if token_corrupt:
        _revoke_family(con, row["family_id"], ts)
        raise OAuthError("invalid_grant", "刷新令牌的授权数据损坏")
    expires_at = _as_int(row["expires_at"])
    if expires_at is None:
        _revoke_family(con, row["family_id"], ts)
        raise OAuthError("invalid_grant", "刷新令牌的授权数据损坏")
    if expires_at <= ts:
        raise OAuthError("invalid_grant", "刷新令牌已过期")

    given_resource = _field(data, "resource")
    resolved = _resolve_resource(given_resource if _text(given_resource) else row["resource"],
                                 canonical_resource, "invalid_grant")
    if resolved != row["resource"]:
        raise OAuthError("invalid_grant", "resource 不匹配")

    old_scopes = _scopes_from_json(row["scopes_json"])
    if not old_scopes:
        _revoke_family(con, row["family_id"], ts)
        raise OAuthError("invalid_grant", "刷新令牌的授权数据损坏")

    raw_scope = _field(data, "scope")
    scopes = old_scopes
    if raw_scope is not None and (not isinstance(raw_scope, str) or raw_scope.strip()):
        requested = normalize_scopes(raw_scope)
        if not requested or any(s not in old_scopes for s in requested):
            raise OAuthError("invalid_scope", "刷新只能收窄 scope")
        scopes = requested

    user_id = _as_int(row["user_id"])
    user_revision = _as_int(row["user_revision"])
    if user_id is None or user_revision is None:
        _revoke_family(con, row["family_id"], ts)
        raise OAuthError("invalid_grant", "刷新令牌的授权数据损坏")
    ceiling = _current_ceiling(con, user_id, user_revision)
    if ceiling is None or any(s not in ceiling["scopes"] for s in old_scopes):
        _revoke_family(con, row["family_id"], ts)
        raise OAuthError("invalid_grant", "授权已失效")
    user = ceiling["user"]
    _up, _uw, user_corrupt = _policy_window(
        user.get("tool_policy_json") if user is not None else None,
        user.get("window_json") if user is not None else None,
    )
    if user_corrupt:
        _revoke_family(con, row["family_id"], ts)
        raise OAuthError("invalid_grant", "刷新令牌的授权数据损坏")

    # 继承令牌级 policy/window（NULL 也原样传下去），刷新不能放宽。
    new_access = _random_token(ACCESS_PREFIX)
    new_refresh = _random_token(REFRESH_PREFIX)
    family_id = row["family_id"]
    common = dict(client_id=client["client_id"], family_id=family_id, scopes=scopes,
                  tool_policy_json=row["tool_policy_json"],
                  window_json=row["window_json"], user_id=user_id,
                  username=ceiling["username"], user_revision=ceiling["revision"],
                  resource=row["resource"], ts=ts)
    cur = con.execute(
        "UPDATE oauth_tokens SET revoked_at=?, replaced_by_hash=? "
        "WHERE token_hash=? AND revoked_at IS NULL",
        (ts, auth.hash_token(new_refresh), token_hash),
    )
    if cur.rowcount != 1:
        _revoke_family(con, family_id, ts)
        raise OAuthError("invalid_grant", "刷新令牌已使用，授权已吊销",
                         reason="refresh_reuse")

    _insert_token(con, new_access, "access", **common)
    _insert_token(con, new_refresh, "refresh", **common)
    con.execute("UPDATE oauth_clients SET last_used_at=? WHERE client_id=?",
                (ts, client["client_id"]))
    return {
        "access_token": new_access,
        "token_type": "Bearer",
        "expires_in": ACCESS_TTL,
        "refresh_token": new_refresh,
        "scope": " ".join(scopes),
    }


# ------------------------------------------------------------------ 资源服务器
def _revoke_family(con: sqlite3.Connection, family_id: object, ts: int) -> int:
    if not isinstance(family_id, str) or not family_id:
        return 0
    return con.execute(
        "UPDATE oauth_tokens SET revoked_at=? WHERE family_id=? AND revoked_at IS NULL",
        (ts, family_id),
    ).rowcount


def verify_access(con: sqlite3.Connection, raw: object, canonical_resource: object,
                  *, now: object = None) -> dict[str, Any] | None:
    """校验 MCP 请求的 OAuth 访问令牌；有效返回 principal，否则 ``None``。

    每次调用都重读用户：删除/停用/revision 变化/scope 上限收窄，任一项不满足都
    吊销 family 并返回 ``None``（设计 §6.1）。
    """
    canonical = _text(canonical_resource)
    if canonical is None:
        return None
    if not isinstance(raw, str) or not raw.startswith(ACCESS_PREFIX):
        return None

    ts = _now(now)
    token_hash = auth.hash_token(raw)
    row = con.execute("SELECT * FROM oauth_tokens WHERE token_hash=?", (token_hash,)).fetchone()
    if row is None or row["kind"] != "access":
        return None
    # 普通过期/已撤销只拒绝本次请求，不吊销 family：否则访问令牌 1 小时到期
    # 会连坐 30 天的刷新令牌。绑定类故障才吊销 family（见下）。
    if row["revoked_at"] is not None:
        return None
    expires_at = _as_int(row["expires_at"])
    if expires_at is None:
        _revoke_family(con, row["family_id"], ts)
        return None
    if expires_at <= ts:
        return None
    if row["resource"] != canonical:
        _revoke_family(con, row["family_id"], ts)
        return None

    client = _client_row(con, row["client_id"])
    if client is None or client["revoked_at"] is not None:
        _revoke_family(con, row["family_id"], ts)
        return None
    client_info = _client_dict(client)
    if client_info["policy_corrupt"] or client_info["window_corrupt"]:
        _revoke_family(con, row["family_id"], ts)
        return None

    token_policy, token_window, token_corrupt = _policy_window(
        row["tool_policy_json"], row["window_json"])
    scopes = _scopes_from_json(row["scopes_json"])
    if token_corrupt or not scopes:
        _revoke_family(con, row["family_id"], ts)
        return None

    user_id = _as_int(row["user_id"])
    user_revision = _as_int(row["user_revision"])
    if user_id is None or user_revision is None:
        _revoke_family(con, row["family_id"], ts)
        return None
    ceiling = _current_ceiling(con, user_id, user_revision)
    if ceiling is None or any(s not in ceiling["scopes"] for s in scopes):
        _revoke_family(con, row["family_id"], ts)
        return None
    user = ceiling["user"]
    user_policy, user_window, user_corrupt = _policy_window(
        user.get("tool_policy_json") if user is not None else None,
        user.get("window_json") if user is not None else None,
    )
    if user_corrupt:
        _revoke_family(con, row["family_id"], ts)
        return None

    last_used = _as_int(row["last_used_at"])
    if last_used is None or ts - last_used >= LAST_USED_UPDATE:
        _touch(con, "UPDATE oauth_tokens SET last_used_at=? WHERE id=?", (ts, row["id"]))
    client_last_used = _as_int(client["last_used_at"])
    if client_last_used is None or ts - client_last_used >= LAST_USED_UPDATE:
        _touch(con, "UPDATE oauth_clients SET last_used_at=? WHERE client_id=?",
               (ts, row["client_id"]))

    data_mode = user["data_mode"] if user is not None else "shared"
    if user is not None:
        user_info = dict(user)
        user_info.pop("tool_policy_json", None)
        user_info.pop("window_json", None)
        user_info["tool_policy"] = user_policy
        user_info["window"] = user_window
    else:
        user_info = None
    return {
        "kind": "oauth",
        "auth": "oauth",
        "user_id": user_id,
        "username": row["username"],
        "revision": user_revision,
        "scopes": scopes,
        "scope": " ".join(scopes),
        "resource": row["resource"],
        "client_id": row["client_id"],
        "client_name": client_info["client_name"],
        "token_id": row["id"],
        "family_id": row["family_id"],
        # 三层都已解析且校验过；Task 5 按“每层 AND”评估，None = 该层不额外限制。
        "tool_policy": token_policy,
        "window": token_window,
        "user_tool_policy": user_policy,
        "user_window": user_window,
        "client_tool_policy": client_info["tool_policy"],
        "client_window": client_info["window"],
        "tool_policies": [user_policy, client_info["tool_policy"], token_policy],
        "windows": [user_window, client_info["window"], token_window],
        "user": user_info,
        "data_mode": data_mode,
        "owner": user_id if (user is not None and data_mode == "own") else 0,
        "is_admin": user_id == 0,
        "subject": auth.ADMIN_SUBJECT if user_id == 0 else auth.subject_for_user(user_id),
    }


# ------------------------------------------------------------------ 撤销
def revoke_token(con: sqlite3.Connection, raw: object, *, client_id: object = None,
                 client_secret: object = None, now: object = None) -> bool:
    """RFC 7009；撤销刷新令牌会废掉整个 family，访问令牌只废自己。

    返回是否找到并处理了该令牌；Web 层无论如何都应回 200。
    """
    client = None
    if isinstance(client_id, str) and client_id:
        client = _authenticate_client(con, client_id, client_secret)
    if not isinstance(raw, str) or not raw:
        return False
    ts = _now(now)
    token_hash = auth.hash_token(raw)
    row = con.execute("SELECT * FROM oauth_tokens WHERE token_hash=?", (token_hash,)).fetchone()
    if row is None:
        return False
    if client is not None and row["client_id"] != client["client_id"]:
        return False
    if row["kind"] == "refresh":
        _revoke_family(con, row["family_id"], ts)
        return True
    if row["kind"] == "access":
        con.execute(
            "UPDATE oauth_tokens SET revoked_at=? WHERE token_hash=? AND revoked_at IS NULL",
            (ts, token_hash),
        )
        return True
    return False
