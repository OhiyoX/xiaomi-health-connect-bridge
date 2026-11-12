"""身份、会话、静态 key、管理员引导与 TOTP 密钥存储。

全部函数都接收调用方打开的 ``sqlite3.Connection``；账号/会话/令牌表只在 owner 0，
因此调用方必须用 ``with hcb.db.db(0) as con:``（不要依赖 ``hcb.context.owner``）。

安全约定：

- 密码：``scrypt$N$r$p$salt_b64$hash_b64``，16 字节随机盐，比较用
  ``hmac.compare_digest``。
- 会话：Cookie 值 ``base64url(payload).base64url(hmac_sha256(secret, payload))``；
  数据库只存 ``SHA-256(sid)``。
- CSRF：``HMAC-SHA256(secret, "csrf:" + sid)``，与会话 sid 绑定。
- 令牌：数据库只存 SHA-256；明文只在创建/导入时返回给调用方一次。
- 日志不打印密码、令牌、Cookie、TOTP 密钥。
"""
from __future__ import annotations

import base64
import binascii
import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
import time
from dataclasses import dataclass
from datetime import datetime
from typing import Any, Mapping

from . import totp

SCOPES = ("health.read", "health.write")
ADMIN_SUBJECT = "admin"
ADMIN_MIN_PASSWORD = 12
SESSION_COOKIE = "hcb_session"
PREAUTH_COOKIE = "hcb_preauth"
AUTH_CONFIGURED_KEY = "auth_configured"


def _touch(con: sqlite3.Connection, sql: str, params: tuple) -> None:
    """Best-effort metadata update; a locked DB must not change auth results."""
    try:
        con.execute(sql, params)
    except sqlite3.OperationalError:
        pass


def _env_int(name: str, default: int, *, minimum: int = 1) -> int:
    """读非负整数环境变量；缺失/非法/越界时用默认值（不改调用方行为）。"""
    raw = os.environ.get(name)
    if raw is None or not str(raw).strip():
        return default
    try:
        value = int(str(raw).strip())
    except (TypeError, ValueError):
        return default
    return value if value >= minimum else default


ADMIN_SESSION_TTL = _env_int("HCB_ADMIN_SESSION_TTL", 4 * 3600)
USER_SESSION_TTL = _env_int("HCB_USER_SESSION_TTL", 12 * 3600)
SESSION_TTL = USER_SESSION_TTL  # 兼容旧引用；新代码应按 subject 用上面两个常量
PREAUTH_TTL = _env_int("HCB_PREAUTH_TTL", 300)
TOTP_WINDOW = _env_int("HCB_TOTP_WINDOW", 1, minimum=0)
UA_MAX = 120
TOKEN_HASH_LEN = 64

_SCRYPT_N = 16384
_SCRYPT_R = 8
_SCRYPT_P = 1
_SCRYPT_MAXMEM = 128 * 1024 * 1024
_USERNAME_RE = re.compile(r"^[a-z0-9._-]{3,32}$")
_UNSET = object()

_DUMMY_HASH: str | None = None


# ------------------------------------------------------------------ 小工具
def _now(value: object = None) -> int:
    if value is None:
        return int(time.time())
    if isinstance(value, datetime):
        return int(value.timestamp())
    if isinstance(value, bool):
        raise ValueError("now must be a timestamp")
    return int(value)  # type: ignore[arg-type]


def _b64e(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def _b64d(text: str) -> bytes:
    if not isinstance(text, str):
        raise ValueError("base64 value must be text")
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def _key_bytes(secret: object) -> bytes:
    if isinstance(secret, str):
        data = secret.encode("utf-8")
    elif isinstance(secret, (bytes, bytearray)):
        data = bytes(secret)
    else:
        raise ValueError("secret must be text or bytes")
    if not data:
        raise ValueError("secret must not be empty")
    return data


def constant_eq(a: object, b: object) -> bool:
    """常量时间比较 str/bytes；类型不支持时返回 ``False``。"""
    if isinstance(a, str):
        a = a.encode("utf-8")
    if isinstance(b, str):
        b = b.encode("utf-8")
    if not isinstance(a, (bytes, bytearray)) or not isinstance(b, (bytes, bytearray)):
        return False
    return hmac.compare_digest(bytes(a), bytes(b))


def hash_token(raw: object) -> str:
    """令牌/授权码/nonce 的 SHA-256（小写十六进制）。"""
    if isinstance(raw, str):
        data = raw.encode("utf-8")
    elif isinstance(raw, (bytes, bytearray)):
        data = bytes(raw)
    else:
        raise TypeError("token must be text or bytes")
    return hashlib.sha256(data).hexdigest()


def _looks_like_hash(value: str) -> bool:
    return len(value) == TOKEN_HASH_LEN and all(c in "0123456789abcdef" for c in value.lower())


# ------------------------------------------------------------------ 密码
def hash_password(password: object) -> str:
    """生成 ``scrypt$N$r$p$salt_b64$hash_b64``。空密码拒绝。"""
    if not isinstance(password, str) or not password:
        raise ValueError("password must be a non-empty string")
    salt = secrets.token_bytes(16)
    digest = hashlib.scrypt(
        password.encode("utf-8"), salt=salt, n=_SCRYPT_N, r=_SCRYPT_R, p=_SCRYPT_P,
        dklen=32, maxmem=_SCRYPT_MAXMEM,
    )
    return "scrypt${}${}${}${}${}".format(
        _SCRYPT_N, _SCRYPT_R, _SCRYPT_P,
        base64.b64encode(salt).decode("ascii"),
        base64.b64encode(digest).decode("ascii"),
    )


def _parse_password_hash(stored: str) -> tuple[int, int, int, bytes, bytes] | None:
    try:
        algo, n_s, r_s, p_s, salt_s, hash_s = stored.split("$")
        if algo != "scrypt":
            return None
        n, r, p = int(n_s), int(r_s), int(p_s)
        # 防止数据库里的坏/恶意参数把进程拖死；正常只写 N=16384,r=8,p=1。
        if not (1 <= n <= 1 << 20 and 1 <= r <= 32 and 1 <= p <= 16):
            return None
        salt = base64.b64decode(salt_s, validate=True)
        digest = base64.b64decode(hash_s, validate=True)
        if len(salt) < 8 or len(digest) < 16:
            return None
        return n, r, p, salt, digest
    except (ValueError, TypeError, binascii.Error):
        return None


def verify_password(password: object, stored: object) -> bool:
    """校验密码；任何格式/类型问题都返回 ``False``，不抛异常。"""
    if not isinstance(password, str) or not password or not isinstance(stored, str):
        return False
    parsed = _parse_password_hash(stored)
    if parsed is None:
        return False
    n, r, p, salt, expected = parsed
    try:
        actual = hashlib.scrypt(
            password.encode("utf-8"), salt=salt, n=n, r=r, p=p,
            dklen=len(expected), maxmem=_SCRYPT_MAXMEM,
        )
    except (ValueError, MemoryError):
        return False
    return hmac.compare_digest(actual, expected)


def _dummy_hash() -> str:
    """用户不存在时也做一次 scrypt，减少按响应时间枚举用户。"""
    global _DUMMY_HASH
    if _DUMMY_HASH is None:
        _DUMMY_HASH = hash_password("hcb-dummy-password-for-timing-only")
    return _DUMMY_HASH


# ------------------------------------------------------------------ JSON 列
def _parse_json_value(value: object, field: str) -> object:
    if isinstance(value, str):
        try:
            return json.loads(value)
        except json.JSONDecodeError as e:
            raise ValueError(f"{field} must be valid JSON") from e
    return value


def _json_list(value: object, field: str) -> str | None:
    if value is None:
        return None
    parsed = _parse_json_value(value, field)
    if not isinstance(parsed, list):
        raise ValueError(f"{field} must be a JSON array")
    return json.dumps(parsed, separators=(",", ":"), ensure_ascii=False)


def _json_dict(value: object, field: str) -> str | None:
    if value is None:
        return None
    parsed = _parse_json_value(value, field)
    if not isinstance(parsed, dict):
        raise ValueError(f"{field} must be a JSON object")
    return json.dumps(parsed, separators=(",", ":"), ensure_ascii=False)


def _normalize_scopes(scopes: object) -> list[str]:
    if isinstance(scopes, str):
        parsed = _parse_json_value(scopes, "scopes")
    else:
        parsed = scopes
    if not isinstance(parsed, (list, tuple, set, frozenset)):
        raise ValueError("scopes must be a list")
    try:
        requested = set(parsed)
    except TypeError:
        raise ValueError("scopes must contain strings") from None
    unknown = requested - set(SCOPES)
    if unknown:
        raise ValueError(f"unknown scope: {sorted(str(s) for s in unknown)[0]}")
    return [s for s in SCOPES if s in requested]


def _scopes_from_json(value: object) -> list[str]:
    try:
        parsed = json.loads(value) if isinstance(value, str) else value
    except (json.JSONDecodeError, TypeError):
        return []
    if not isinstance(parsed, list):
        return []
    return [s for s in SCOPES if s in parsed]


def _bool_value(value: object) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, int) and value in (0, 1):
        return bool(value)
    raise ValueError("enabled must be a boolean")


def _normalize_username(username: object) -> str:
    if not isinstance(username, str):
        raise ValueError("username must be text")
    name = username.strip().lower()
    if not _USERNAME_RE.fullmatch(name):
        raise ValueError("username must be 3-32 chars of [a-z0-9._-]")
    if name == ADMIN_SUBJECT:
        raise ValueError("username 'admin' is reserved")
    return name


def _clean_ua(ua: object) -> str:
    if ua is None:
        return ""
    cleaned = str(ua).replace("\r", " ").replace("\n", " ").strip()
    return cleaned[:UA_MAX]


# ------------------------------------------------------------------ 用户
def _user_dict(row: sqlite3.Row) -> dict[str, Any]:
    """给认证/后台用的用户 dict；不返回 ``password_hash``，避免误渲染或误记录。"""
    data = dict(row)
    data.pop("password_hash", None)
    data["scopes"] = _scopes_from_json(data.get("scopes_json"))
    data["enabled"] = bool(data.get("enabled"))
    return data


def create_user(con: sqlite3.Connection, username: object, password: object,
                display_name: object = None, scopes: object = None,
                tool_policy_json: object = None, window_json: object = None,
                data_mode: str = "shared", enabled: object = True,
                now: object = None) -> dict[str, Any]:
    """创建普通用户；用户名大小写不敏感，``admin`` 保留。"""
    name = _normalize_username(username)
    if not isinstance(password, str) or not password:
        raise ValueError("password must be a non-empty string")
    if scopes is None:
        scopes = ["health.read"]
    scope_list = _normalize_scopes(scopes)
    if data_mode not in ("shared", "own"):
        raise ValueError("data_mode must be 'shared' or 'own'")
    enabled_bool = _bool_value(enabled)
    display = name if display_name is None else str(display_name).strip()
    if not display:
        raise ValueError("display_name must not be empty")
    if len(display) > 120:
        raise ValueError("display_name must be at most 120 chars")
    ts = _now(now)
    try:
        cur = con.execute(
            "INSERT INTO users(username, display_name, password_hash, scopes_json, "
            "tool_policy_json, window_json, data_mode, enabled, revision, created_at, updated_at) "
            "VALUES(?,?,?,?,?,?,?,?,1,?,?)",
            (name, display, hash_password(password), json.dumps(scope_list, separators=(",", ":")),
             _json_list(tool_policy_json, "tool_policy_json"),
             _json_dict(window_json, "window_json"), data_mode, 1 if enabled_bool else 0, ts, ts),
        )
    except sqlite3.IntegrityError:
        raise ValueError(f"username already exists: {name}") from None
    mark_auth_configured(con)
    row = con.execute("SELECT * FROM users WHERE id=?", (cur.lastrowid,)).fetchone()
    if row is None:  # pragma: no cover - 插入后立即读
        raise RuntimeError("user row disappeared after insert")
    return _user_dict(row)


def by_username(con: sqlite3.Connection, username: object) -> dict[str, Any] | None:
    if not isinstance(username, str) or not username.strip():
        return None
    row = con.execute("SELECT * FROM users WHERE username = ? COLLATE NOCASE",
                      (username.strip(),)).fetchone()
    return _user_dict(row) if row is not None else None


def get_user(con: sqlite3.Connection, user_id: object) -> dict[str, Any] | None:
    try:
        uid = int(user_id)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return None
    if uid <= 0:
        return None
    row = con.execute("SELECT * FROM users WHERE id=?", (uid,)).fetchone()
    return _user_dict(row) if row is not None else None


def list_users(con: sqlite3.Connection) -> list[dict[str, Any]]:
    rows = con.execute("SELECT * FROM users ORDER BY id").fetchall()
    return [_user_dict(row) for row in rows]


def update_user(con: sqlite3.Connection, user_id: object, *,
                display_name: object = _UNSET, password: object = _UNSET,
                scopes: object = _UNSET, tool_policy_json: object = _UNSET,
                window_json: object = _UNSET, data_mode: object = _UNSET,
                enabled: object = _UNSET, now: object = None) -> dict[str, Any] | None:
    """改用户；密码、scope 上限、启用状态变化时 revision +1。

    ``tool_policy_json=None`` / ``window_json=None`` 表示清空该列；
    省略参数表示不改。
    """
    try:
        uid = int(user_id)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return None
    row = con.execute("SELECT * FROM users WHERE id=?", (uid,)).fetchone()
    if row is None:
        return None

    sets: list[str] = []
    params: list[object] = []
    bump = False

    if display_name is not _UNSET:
        display = str(display_name).strip()
        if not display:
            raise ValueError("display_name must not be empty")
        if len(display) > 120:
            raise ValueError("display_name must be at most 120 chars")
        sets.append("display_name=?")
        params.append(display)

    if password is not _UNSET:
        if not isinstance(password, str) or not password:
            raise ValueError("password must be a non-empty string")
        sets.append("password_hash=?")
        params.append(hash_password(password))
        bump = True

    if scopes is not _UNSET:
        scope_list = _normalize_scopes(scopes)
        scope_json = json.dumps(scope_list, separators=(",", ":"))
        if scope_json != row["scopes_json"]:
            sets.append("scopes_json=?")
            params.append(scope_json)
            bump = True

    if tool_policy_json is not _UNSET:
        sets.append("tool_policy_json=?")
        params.append(_json_list(tool_policy_json, "tool_policy_json"))

    if window_json is not _UNSET:
        sets.append("window_json=?")
        params.append(_json_dict(window_json, "window_json"))

    if data_mode is not _UNSET:
        if data_mode not in ("shared", "own"):
            raise ValueError("data_mode must be 'shared' or 'own'")
        sets.append("data_mode=?")
        params.append(data_mode)

    if enabled is not _UNSET:
        enabled_bool = _bool_value(enabled)
        if enabled_bool != bool(row["enabled"]):
            sets.append("enabled=?")
            params.append(1 if enabled_bool else 0)
            bump = True

    if not sets:
        return _user_dict(row)

    if bump:
        sets.append("revision = revision + 1")
    sets.append("updated_at=?")
    params.append(_now(now))
    params.append(uid)
    con.execute(f"UPDATE users SET {', '.join(sets)} WHERE id=?", params)
    row = con.execute("SELECT * FROM users WHERE id=?", (uid,)).fetchone()
    return _user_dict(row) if row is not None else None


def check_user_password(con: sqlite3.Connection, username: object,
                        password: object) -> dict[str, Any] | None:
    """用户名 + 密码 → 用户；禁用/不存在/密码错都返回 ``None``。

    返回的 dict 不含 ``password_hash``（与 ``by_username`` 一致）。
    """
    if not isinstance(username, str) or not username.strip():
        verify_password(password, _dummy_hash())
        return None
    row = con.execute("SELECT * FROM users WHERE username = ? COLLATE NOCASE",
                      (username.strip(),)).fetchone()
    if row is None:
        # 仍做一次 scrypt，避免“用户不存在”明显更快。
        verify_password(password, _dummy_hash())
        return None
    if not verify_password(password, row["password_hash"]):
        return None
    user = _user_dict(row)
    return user if user["enabled"] else None


# ------------------------------------------------------------------ subject
def subject_for_user(user_id: object) -> str:
    try:
        uid = int(user_id)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        raise ValueError("user_id must be an integer") from None
    if isinstance(user_id, bool) or uid <= 0:
        raise ValueError("user_id must be a positive integer")
    return f"user:{uid}"


def user_id_for_subject(subject: object) -> int | None:
    """``admin`` → 0；``user:<id>`` → id；其它 → ``None``。"""
    if subject == ADMIN_SUBJECT:
        return 0
    if not isinstance(subject, str) or not subject.startswith("user:"):
        return None
    tail = subject[5:]
    if not tail.isdigit():
        return None
    uid = int(tail)
    if uid <= 0 or str(uid) != tail:
        return None
    return uid


def _validate_session_subject(con: sqlite3.Connection, subject: object) -> int:
    uid = user_id_for_subject(subject)
    if uid is None:
        raise ValueError("invalid session subject")
    if uid:
        user = get_user(con, uid)
        if user is None:
            raise ValueError("unknown user")
    return uid


# ------------------------------------------------------------------ 签名 / 会话
def sign_payload(secret: object, payload: object) -> str:
    """签名一个 JSON payload：``base64url(json).base64url(hmac)``。"""
    raw = json.dumps(payload, separators=(",", ":"), sort_keys=True,
                     ensure_ascii=False).encode("utf-8")
    body = _b64e(raw)
    sig = hmac.new(_key_bytes(secret), body.encode("ascii"), hashlib.sha256).digest()
    return body + "." + _b64e(sig)


def verify_payload(secret: object, value: object) -> dict[str, Any] | None:
    """校验签名并返回 dict；签名错误、格式错误、非对象都返回 ``None``。"""
    if not isinstance(value, str) or not value:
        return None
    try:
        body, sig = value.split(".", 1)
        expected = hmac.new(_key_bytes(secret), body.encode("ascii"), hashlib.sha256).digest()
        if not hmac.compare_digest(expected, _b64d(sig)):
            return None
        decoded = json.loads(_b64d(body))
    except (ValueError, TypeError, json.JSONDecodeError, binascii.Error, UnicodeDecodeError):
        return None
    return decoded if isinstance(decoded, dict) else None


def _service_secret(con: sqlite3.Connection, name: str) -> str:
    row = con.execute("SELECT value FROM service_secrets WHERE key=?", (name,)).fetchone()
    if row is not None:
        return row["value"]
    value = _b64e(secrets.token_bytes(32))
    con.execute("INSERT OR IGNORE INTO service_secrets(key, value) VALUES(?,?)", (name, value))
    row = con.execute("SELECT value FROM service_secrets WHERE key=?", (name,)).fetchone()
    if row is None:  # pragma: no cover - 插入后立即读
        raise RuntimeError("service secret row disappeared after insert")
    return row["value"]


def session_secret(con: sqlite3.Connection) -> str:
    """会话/CSRF 签名密钥；首次调用生成并持久化到 ``service_secrets``。"""
    return _service_secret(con, "session_secret")


def _cookie_value(value: object) -> str:
    if not isinstance(value, str):
        return ""
    text = value.strip()
    prefix = SESSION_COOKIE + "="
    if text.startswith(prefix):
        text = text[len(prefix):]
    return text.strip().strip('"').strip()


def create_session(con: sqlite3.Connection, subject: str, revision: int,
                   ttl: int | None = None, ua: object = "",
                   *, now: object = None, secret: object = None) -> tuple[str, str]:
    """建会话并返回 ``(sid, cookie_value)``；库中只写 ``SHA-256(sid)``。

    ``ttl=None`` 时按主体取设计值：管理员 4 小时、普通用户 12 小时。
    """
    if not isinstance(subject, str):
        raise ValueError("subject must be text")
    if not isinstance(revision, int) or isinstance(revision, bool) or revision < 0:
        raise ValueError("revision must be a non-negative integer")
    if ttl is None:
        ttl = ADMIN_SESSION_TTL if subject == ADMIN_SUBJECT else USER_SESSION_TTL
    if not isinstance(ttl, int) or isinstance(ttl, bool) or ttl <= 0:
        raise ValueError("ttl must be a positive integer")
    signing_secret = secret if secret is not None else session_secret(con)
    _key_bytes(signing_secret)  # 先验证密钥，避免插入行后签名失败
    ts = _now(now)
    uid = _validate_session_subject(con, subject)
    if uid:
        user = get_user(con, uid)
        if user is None:
            raise ValueError("unknown user")
        if not user["enabled"]:
            raise ValueError("user is disabled")
        if user["revision"] != revision:
            raise ValueError("stale user revision")

    sid = secrets.token_urlsafe(32)
    sid_hash = hash_token(sid)
    expires_at = ts + ttl
    con.execute(
        "INSERT INTO sessions(sid_hash, subject, revision, user_agent, created_at, "
        "last_seen_at, expires_at, revoked_at) VALUES(?,?,?,?,?,?,?,NULL)",
        (sid_hash, subject, revision, _clean_ua(ua), ts, ts, expires_at),
    )
    payload = {"sub": subject, "rev": revision, "sid": sid, "iat": ts, "exp": expires_at}
    cookie = sign_payload(signing_secret, payload)
    return sid, cookie


def read_session(con: sqlite3.Connection, cookie_value: object, secret: object,
                 *, now: object = None) -> dict[str, Any] | None:
    """校验 Cookie 与数据库行；有效返回会话 dict，否则 ``None``。

    返回字段：``sid``、``sid_hash``、``subject``、``revision``、``user_id``、
    ``is_admin``、``iat``、``created_at``、``last_seen_at``、``expires_at``、
    ``user_agent``。
    """
    ts = _now(now)
    payload = verify_payload(secret, _cookie_value(cookie_value))
    if not payload:
        return None
    sub = payload.get("sub")
    sid = payload.get("sid")
    exp = payload.get("exp")
    rev = payload.get("rev")
    iat = payload.get("iat")
    if (not isinstance(sub, str) or not isinstance(sid, str) or not sid
            or not isinstance(exp, int) or isinstance(exp, bool)
            or not isinstance(rev, int) or isinstance(rev, bool)
            or not isinstance(iat, int) or isinstance(iat, bool)):
        return None
    if exp <= ts:
        return None

    sid_hash = hash_token(sid)
    row = con.execute("SELECT * FROM sessions WHERE sid_hash=?", (sid_hash,)).fetchone()
    if row is None or not constant_eq(row["sid_hash"], sid_hash):
        return None
    if row["revoked_at"] is not None:
        return None
    if int(row["expires_at"]) <= ts:
        return None
    if row["subject"] != sub or int(row["revision"]) != rev:
        return None

    uid = user_id_for_subject(sub)
    if uid is None:
        return None
    if uid:
        user = get_user(con, uid)
        if user is None or not user["enabled"] or int(user["revision"]) != rev:
            return None

    last_seen = int(row["last_seen_at"])
    if ts - last_seen >= 60:
        _touch(con, "UPDATE sessions SET last_seen_at=? WHERE sid_hash=?", (ts, sid_hash))
        last_seen = ts

    return {
        "sid": sid,
        "sid_hash": sid_hash,
        "subject": sub,
        "revision": rev,
        "user_id": uid,
        "is_admin": sub == ADMIN_SUBJECT,
        "iat": iat,
        "created_at": int(row["created_at"]),
        "last_seen_at": last_seen,
        "expires_at": int(row["expires_at"]),
        "user_agent": row["user_agent"],
    }


def change_password(con: sqlite3.Connection, user_id: object, new_password: object, *,
                    current_sid: object, ua: object = None, ttl: int | None = None,
                    now: object = None) -> tuple[str, str]:
    """改用户密码并续上当前会话。

    - ``revision`` +1（旧令牌/旧会话立即失效）；
    - 吊销该用户全部会话（包括当前会话）；
    - 用新 revision 建一个替代会话并返回 ``(sid, cookie_value)``。

    当前会话必须存在、未吊销、未过期、属于该用户且 revision 未过期，否则抛
    ``ValueError``。管理员密码走 ``admin_set_password``（无 revision 概念）。
    """
    try:
        uid = int(user_id)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        raise ValueError("user_id must be an integer") from None
    if isinstance(user_id, bool) or uid <= 0:
        raise ValueError("user_id must be a positive integer")
    user = get_user(con, uid)
    if user is None or not user["enabled"]:
        raise ValueError("unknown or disabled user")
    if ttl is not None and (not isinstance(ttl, int) or isinstance(ttl, bool) or ttl <= 0):
        raise ValueError("ttl must be a positive integer")
    subject = subject_for_user(uid)
    key = _session_key(current_sid)
    if key is None:
        raise ValueError("current_sid is required")
    ts = _now(now)
    row = con.execute("SELECT * FROM sessions WHERE sid_hash=?", (key,)).fetchone()
    if (row is None or row["subject"] != subject or row["revoked_at"] is not None
            or int(row["expires_at"]) <= ts or int(row["revision"]) != user["revision"]):
        raise ValueError("current session is not valid")

    updated = update_user(con, uid, password=new_password, now=ts)
    if updated is None:  # pragma: no cover - 上面已确认存在
        raise ValueError("unknown user")
    revoke_subject(con, subject, now=ts)
    return create_session(
        con, subject, updated["revision"], ttl,
        row["user_agent"] if ua is None else ua, now=ts,
    )


def _session_key(value: object) -> str | None:
    if not isinstance(value, str) or not value:
        return None
    if _looks_like_hash(value):
        return value.lower()
    return hash_token(value)


def revoke_session(con: sqlite3.Connection, sid: object, *, now: object = None) -> bool:
    """按 sid 或 sid_hash 吊销一个会话；已吊销/不存在返回 ``False``。"""
    key = _session_key(sid)
    if key is None:
        return False
    cur = con.execute("UPDATE sessions SET revoked_at=? WHERE sid_hash=? AND revoked_at IS NULL",
                      (_now(now), key))
    return cur.rowcount > 0


def revoke_subject(con: sqlite3.Connection, subject: str, *,
                   keep_sid: object = None, now: object = None) -> int:
    """吊销某主体的全部有效会话；``keep_sid`` 指定的会话保留。"""
    if not isinstance(subject, str) or not subject:
        raise ValueError("subject must be non-empty text")
    ts = _now(now)
    sql = "UPDATE sessions SET revoked_at=? WHERE subject=? AND revoked_at IS NULL"
    params: list[object] = [ts, subject]
    if keep_sid is not None:
        key = _session_key(keep_sid)
        if key is None:
            raise ValueError("keep_sid must be a sid or sid_hash")
        sql += " AND sid_hash<>?"
        params.append(key)
    return con.execute(sql, params).rowcount


def list_sessions(con: sqlite3.Connection, *, subject: str | None = None,
                  include_revoked: bool = False) -> list[dict[str, Any]]:
    sql = ("SELECT sid_hash, subject, revision, user_agent, created_at, last_seen_at, "
           "expires_at, revoked_at FROM sessions")
    clauses: list[str] = []
    params: list[object] = []
    if subject is not None:
        clauses.append("subject=?")
        params.append(subject)
    if not include_revoked:
        clauses.append("revoked_at IS NULL")
    if clauses:
        sql += " WHERE " + " AND ".join(clauses)
    sql += " ORDER BY created_at DESC, sid_hash"
    return [dict(row) for row in con.execute(sql, params).fetchall()]


def csrf_token(secret: object, sid: object) -> str:
    """CSRF 令牌：与会话 sid 绑定的 HMAC-SHA256 十六进制。"""
    if not isinstance(sid, str) or not sid:
        raise ValueError("sid must be non-empty text")
    return hmac.new(_key_bytes(secret), b"csrf:" + sid.encode("utf-8"),
                    hashlib.sha256).hexdigest()


def csrf_verify(secret: object, sid: object, token: object) -> bool:
    if not isinstance(token, str) or not isinstance(sid, str) or not sid:
        return False
    try:
        return constant_eq(csrf_token(secret, sid), token)
    except ValueError:
        return False


# ------------------------------------------------------------------ sync_state
def settings_get(con: sqlite3.Connection, key: str, default: object = None) -> object:
    row = con.execute("SELECT value FROM sync_state WHERE key=?", (key,)).fetchone()
    return row["value"] if row is not None else default


def settings_set(con: sqlite3.Connection, key: str, value: object) -> None:
    if value is None:
        con.execute("DELETE FROM sync_state WHERE key=?", (key,))
        return
    con.execute(
        "INSERT INTO sync_state(key, value) VALUES(?,?) "
        "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
        (key, str(value)),
    )


# ------------------------------------------------------- 鉴权是否曾经配置过
def mark_auth_configured(con: sqlite3.Connection) -> None:
    """一旦有用户/静态 key/OAuth 客户端/管理员密码，就永久记住“需要鉴权”。

    只置位从不清除：撤销最后一个 key 不能把服务退回匿名开放模式。
    """
    settings_set(con, AUTH_CONFIGURED_KEY, "1")


def auth_configured(con: sqlite3.Connection) -> bool:
    """是否曾经配置过鉴权；老库（没有 marker）按现存行回填。"""
    if settings_get(con, AUTH_CONFIGURED_KEY) == "1":
        return True
    legacy = False
    for table in ("agent_tokens", "oauth_clients", "users"):
        try:
            if con.execute(f"SELECT 1 FROM {table} LIMIT 1").fetchone() is not None:
                legacy = True
                break
        except sqlite3.Error:
            # 表缺失/库损坏时 fail-closed：调用方应要求鉴权。
            raise
    if legacy or admin_has_password(con):
        mark_auth_configured(con)
        return True
    return False


# ------------------------------------------------------------------ 静态 key
def _normalize_label(label: object) -> str:
    if not isinstance(label, str):
        raise ValueError("label must be text")
    text = label.strip()
    if not text or len(text) > 64 or any(ord(c) < 32 for c in text):
        raise ValueError("label must be 1-64 printable chars")
    return text


def _static_token_dict(row: sqlite3.Row) -> dict[str, Any]:
    return {
        "id": row["id"],
        "label": row["label"],
        "owner_user_id": row["owner_user_id"],
        "tool_policy_json": row["tool_policy_json"],
        "window_json": row["window_json"],
        "origin": row["origin"],
        "created_at": row["created_at"],
        "last_used_at": row["last_used_at"],
        "revoked_at": row["revoked_at"],
    }


def static_token_create(con: sqlite3.Connection, label: object, *,
                        token: object = None, owner_user_id: object = 0,
                        tool_policy_json: object = None, window_json: object = None,
                        origin: str = "ui", now: object = None) -> dict[str, Any]:
    """创建静态 key；返回含一次明文 ``token`` 的 dict（之后只能再生成新的）。"""
    name = _normalize_label(label)
    try:
        owner = int(owner_user_id)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        raise ValueError("owner_user_id must be an integer") from None
    if isinstance(owner_user_id, bool) or owner < 0:
        raise ValueError("owner_user_id must be a non-negative integer")
    if owner:
        if get_user(con, owner) is None:
            raise ValueError("owner user does not exist")
    if origin not in ("env", "ui"):
        raise ValueError("origin must be 'env' or 'ui'")
    if token is None:
        raw = secrets.token_urlsafe(32)
    elif isinstance(token, str) and token.strip():
        raw = token.strip()
    else:
        raise ValueError("token must be non-empty text")
    ts = _now(now)
    try:
        cur = con.execute(
            "INSERT INTO agent_tokens(label, hash, tool_policy_json, window_json, "
            "owner_user_id, origin, created_at, last_used_at, revoked_at) "
            "VALUES(?,?,?,?,?,?,?,NULL,NULL)",
            (name, hash_token(raw), _json_list(tool_policy_json, "tool_policy_json"),
             _json_dict(window_json, "window_json"), owner, origin, ts),
        )
    except sqlite3.IntegrityError:
        raise ValueError(f"token label or value already exists: {name}") from None
    mark_auth_configured(con)
    row = con.execute("SELECT * FROM agent_tokens WHERE id=?", (cur.lastrowid,)).fetchone()
    if row is None:  # pragma: no cover
        raise RuntimeError("token row disappeared after insert")
    data = _static_token_dict(row)
    data["token"] = raw
    return data


def static_token_lookup(con: sqlite3.Connection, raw: object, *,
                        now: object = None, touch: bool = True) -> dict[str, Any] | None:
    """明文 token → 有效记录；吊销、owner 停用、未知都返回 ``None``。"""
    if not isinstance(raw, str) or not raw.strip():
        return None
    digest = hash_token(raw.strip())
    row = con.execute("SELECT * FROM agent_tokens WHERE hash=?", (digest,)).fetchone()
    if row is None or not constant_eq(row["hash"], digest):
        return None
    if row["revoked_at"] is not None:
        return None
    owner = int(row["owner_user_id"])
    if owner:
        user = get_user(con, owner)
        if user is None or not user["enabled"]:
            return None
    ts = _now(now)
    last = row["last_used_at"]
    if touch and (last is None or ts - int(last) >= 60):
        _touch(con, "UPDATE agent_tokens SET last_used_at=? WHERE id=?", (ts, row["id"]))
        last = ts
    data = _static_token_dict(row)
    data["last_used_at"] = last
    return data


def static_token_list(con: sqlite3.Connection, *,
                      include_revoked: bool = False) -> list[dict[str, Any]]:
    sql = ("SELECT id, label, owner_user_id, tool_policy_json, window_json, origin, "
           "created_at, last_used_at, revoked_at FROM agent_tokens")
    if not include_revoked:
        sql += " WHERE revoked_at IS NULL"
    sql += " ORDER BY id"
    return [dict(row) for row in con.execute(sql).fetchall()]


def static_token_revoke(con: sqlite3.Connection, ident: object, *,
                        now: object = None) -> bool:
    """按 id（int）或 label（str）吊销；成功返回 ``True``。"""
    if isinstance(ident, bool):
        return False
    if isinstance(ident, int):
        sql, param = "UPDATE agent_tokens SET revoked_at=? WHERE id=? AND revoked_at IS NULL", ident
    elif isinstance(ident, str) and ident.strip():
        sql, param = ("UPDATE agent_tokens SET revoked_at=? WHERE label=? AND revoked_at IS NULL",
                      ident.strip())
    else:
        return False
    cur = con.execute(sql, (_now(now), param))
    return cur.rowcount > 0


def _parse_env_tokens(env: Mapping[str, object]) -> list[tuple[str, str]]:
    out: list[tuple[str, str]] = []
    raw_list = env.get("HEALTH_MCP_TOKENS", "") or ""
    for item in str(raw_list).split(","):
        item = item.strip()
        if not item:
            continue
        name, sep, tok = item.partition(":")
        name, tok = name.strip(), tok.strip()
        if not sep:
            name, tok = "unnamed", name
        if tok:
            out.append((name or "unnamed", tok))
    legacy = str(env.get("HEALTH_MCP_TOKEN", "") or "").strip()
    if legacy:
        out.append(("legacy", legacy))
    return out


def _unique_label(base: str, used: set[str]) -> str:
    if base not in used:
        return base
    n = 2
    while f"{base}-{n}" in used:
        n += 1
    return f"{base}-{n}"


def _safe_log_label(label: object, tokens: set[str] | tuple[str, ...] = ()) -> str:
    """日志里的标签：去掉控制字符、限长，且绝不包含任何 token 子串。"""
    text = "".join(c if c.isprintable() else "?" for c in str(label))
    if any(tok and tok in text for tok in tokens):
        return "[redacted-label]"
    if len(text) > 80:
        text = text[:77] + "..."
    return text


def import_env_tokens(con: sqlite3.Connection, *, env: Mapping[str, object] | None = None,
                      now: object = None, failures: list[str] | None = None) -> int:
    """首次启动把 ``HEALTH_MCP_TOKENS`` / ``HEALTH_MCP_TOKEN`` 导入数据库。

    只导入一次：全部处理成功才写 ``sync_state.tokens_imported=1``。之后改环境
    变量不会复活已撤销的 key。环境为空时不写标记，允许以后首次配置。

    非法标签（超过 64 字符、控制字符等）不会被静默吞掉：安全化后的标签写进
    ``failures`` 列表（若给了）并打印一行诊断（只含标签，绝不包含 token）。
    返回新增条数；有失败时不写导入标记，下次启动可重试。
    """
    if settings_get(con, "tokens_imported"):
        return 0
    source = os.environ if env is None else env
    parsed = _parse_env_tokens(source)
    if not parsed:
        return 0

    existing_labels = {row["label"] for row in
                       con.execute("SELECT label FROM agent_tokens").fetchall()}
    existing_hashes = {row["hash"] for row in
                       con.execute("SELECT hash FROM agent_tokens").fetchall()}
    inserted = 0
    failed_labels: list[str] = []
    token_values = {raw for _, raw in parsed}
    for label, raw in parsed:
        digest = hash_token(raw)
        if digest in existing_hashes:
            continue
        label = _unique_label(label, existing_labels)
        try:
            static_token_create(con, label, token=raw, origin="env", now=now)
        except ValueError:
            failed_labels.append(label)
            continue
        existing_labels.add(label)
        existing_hashes.add(digest)
        inserted += 1
    if failed_labels:
        safe = [_safe_log_label(label, token_values) for label in failed_labels]
        if failures is not None:
            failures.extend(safe)
        print(f"[auth] env token import skipped labels: {', '.join(safe)}", flush=True)
    if not failed_labels:
        settings_set(con, "tokens_imported", "1")
    return inserted


def static_token_import_env(con: sqlite3.Connection, *, env: Mapping[str, object] | None = None,
                            now: object = None, failures: list[str] | None = None) -> int:
    """``import_env_tokens`` 的静态 key 命名别名。"""
    return import_env_tokens(con, env=env, now=now, failures=failures)


# ------------------------------------------------------------------ 管理员
def admin_has_password(con: sqlite3.Connection) -> bool:
    return bool(settings_get(con, "admin_password_hash"))


@dataclass(frozen=True)
class BootstrapResult:
    """管理员引导结果；布尔值为是否新建，状态给启动日志用（不含密码）。"""

    created: bool
    status: str
    message: str = ""

    def __bool__(self) -> bool:
        return self.created

    @property
    def ok(self) -> bool:
        return self.created


def bootstrap_admin(con: sqlite3.Connection, *, env: Mapping[str, object] | None = None,
                    now: object = None) -> BootstrapResult:
    """首次启动用 ``HCB_ADMIN_PASSWORD`` 建管理员密码。

    不抛异常，保证短密码/缺配置不会让 ``/mcp`` 启动失败。返回
    ``BootstrapResult``：

    - ``created``：已写入哈希；
    - ``already_configured``：已有哈希，环境变量不会重置；
    - ``missing``：没有 ``HCB_ADMIN_PASSWORD``；
    - ``weak_password``：变量存在但短于 12 字符或全是空白。

    Task 4 对非 ``created`` 的结果记一行日志，并让 ``/mcp`` 继续服务。
    """
    if admin_has_password(con):
        return BootstrapResult(False, "already_configured", "admin password already set")
    source = os.environ if env is None else env
    password = source.get("HCB_ADMIN_PASSWORD", "") or ""
    if not isinstance(password, str):
        password = str(password)
    if not password:
        return BootstrapResult(False, "missing", "HCB_ADMIN_PASSWORD is not set")
    if len(password) < ADMIN_MIN_PASSWORD or not password.strip():
        return BootstrapResult(
            False, "weak_password",
            f"HCB_ADMIN_PASSWORD must be at least {ADMIN_MIN_PASSWORD} chars",
        )
    settings_set(con, "admin_password_hash", hash_password(password))
    mark_auth_configured(con)
    return BootstrapResult(True, "created", "")


def admin_check_password(con: sqlite3.Connection, password: object) -> bool:
    stored = settings_get(con, "admin_password_hash")
    if not stored or not isinstance(stored, str):
        return False
    return verify_password(password, stored)


def admin_set_password(con: sqlite3.Connection, password: object, *,
                       keep_sid: object = None, now: object = None) -> None:
    """后台/CLI 改管理员密码。

    同一事务里：写新密码哈希、吊销管理员会话（``keep_sid`` 除外）、吊销
    ``user_id=0`` 的 OAuth 令牌（访问与刷新整族）并删除未兑换的管理员授权码。
    管理员没有 ``users.revision``，所以必须显式撤令牌。
    """
    if not isinstance(password, str) or len(password) < ADMIN_MIN_PASSWORD or not password.strip():
        raise ValueError(f"admin password must be at least {ADMIN_MIN_PASSWORD} chars")
    ts = _now(now)
    settings_set(con, "admin_password_hash", hash_password(password))
    mark_auth_configured(con)
    revoke_subject(con, ADMIN_SUBJECT, keep_sid=keep_sid, now=ts)
    con.execute(
        "UPDATE oauth_tokens SET revoked_at=? WHERE user_id=0 AND revoked_at IS NULL",
        (ts,),
    )
    con.execute("DELETE FROM oauth_codes WHERE user_id=0")


# ------------------------------------------------------------------ 管理员 TOTP
def _totp_key(con: sqlite3.Connection) -> bytes:
    key = _b64d(_service_secret(con, "totp_key"))
    if len(key) < 16:
        raise ValueError("totp key is corrupt")
    return key


def _keystream(key: bytes, nonce: bytes, length: int) -> bytes:
    out = bytearray()
    counter = 0
    while len(out) < length:
        out += hmac.new(key, b"hcb-totp-stream:" + nonce + counter.to_bytes(4, "big"),
                        hashlib.sha256).digest()
        counter += 1
    return bytes(out[:length])


def _encrypt_totp_secret(con: sqlite3.Connection, plaintext: str) -> str:
    """HMAC-SHA256 计数器密钥流 + HMAC 标签。

    标准库没有 AEAD/AES；这里用 ``HMAC(key, nonce||counter)`` 生成密钥流，再对
    密文做 HMAC 标签。密钥在同一个库的 ``service_secrets`` 里，所以这只是避免
    明文出现在备份/截图/``SELECT`` 结果里，**不是**防住能读整个数据库的人；
    真正的边界仍是数据库文件 600 与目录权限。
    """
    key = _totp_key(con)
    nonce = secrets.token_bytes(16)
    data = plaintext.encode("utf-8")
    stream = _keystream(key, nonce, len(data))
    ciphertext = bytes(a ^ b for a, b in zip(data, stream))
    tag = hmac.new(key, b"hcb-totp-tag:" + nonce + ciphertext, hashlib.sha256).digest()
    return "v1:{}:{}:{}".format(_b64e(nonce), _b64e(ciphertext), _b64e(tag))


def _decrypt_totp_secret(con: sqlite3.Connection, stored: str) -> str | None:
    try:
        version, nonce_s, cipher_s, tag_s = stored.split(":")
        if version != "v1":
            return None
        key = _totp_key(con)
        nonce, ciphertext, tag = _b64d(nonce_s), _b64d(cipher_s), _b64d(tag_s)
        expected = hmac.new(key, b"hcb-totp-tag:" + nonce + ciphertext,
                            hashlib.sha256).digest()
        if not constant_eq(expected, tag):
            return None
        stream = _keystream(key, nonce, len(ciphertext))
        return bytes(a ^ b for a, b in zip(ciphertext, stream)).decode("utf-8")
    except (ValueError, TypeError, binascii.Error, UnicodeDecodeError):
        return None


def admin_totp_state(con: sqlite3.Connection) -> dict[str, Any]:
    """返回 ``{"secret": base32|None, "enabled": bool}``。

    密文被改坏时 ``secret`` 为 ``None`` 而 ``enabled`` 仍可能是 ``True``；登录
    方必须按 fail-closed 处理（见 ``admin_totp_verify``）。
    """
    enabled = settings_get(con, "admin_totp_enabled") == "1"
    stored = settings_get(con, "admin_totp_secret")
    secret = _decrypt_totp_secret(con, stored) if isinstance(stored, str) and stored else None
    return {"secret": secret, "enabled": enabled}


def _consume_totp_step(con: sqlite3.Connection, step: int) -> bool:
    """记下已用 step 并拒绝 <= 上一次的 step（防 ±1 窗口内重放）。

    用插入/条件更新做 CAS；并发时只有一个请求能消费同一个 step。
    """
    raw = settings_get(con, "admin_totp_last_step")
    if raw is None:
        cur = con.execute(
            "INSERT INTO sync_state(key, value) VALUES('admin_totp_last_step', ?) "
            "ON CONFLICT(key) DO NOTHING",
            (str(step),),
        )
        return cur.rowcount == 1
    if not isinstance(raw, str) or not raw:
        return False  # 坏值 fail-closed，走恢复码/CLI
    try:
        last = int(raw)
    except (TypeError, ValueError):
        return False
    if step <= last:
        return False
    cur = con.execute(
        "UPDATE sync_state SET value=? WHERE key='admin_totp_last_step' AND value=?",
        (str(step), raw),
    )
    return cur.rowcount == 1


def admin_totp_confirm(con: sqlite3.Connection, code_value: object, *,
                       at: object = None) -> bool:
    """用当前（待确认的）密钥校验一个 6 位码；成功才把 TOTP 标为已启用。

    Task 6 的流程：``admin_totp_enable(con)`` 拿到密钥并显示二维码 → 用户输入
    第一个码 → 本函数成功 → 再生成恢复码。
    """
    state = admin_totp_state(con)
    if not state["secret"]:
        return False
    step = totp.verify(state["secret"], code_value, at=at, window=TOTP_WINDOW)
    if step is None:
        return False
    if not _consume_totp_step(con, step):
        return False
    was_enabled = state["enabled"]
    settings_set(con, "admin_totp_enabled", "1")
    if not was_enabled:
        admin_recovery_clear(con)
    return True


def admin_totp_enable(con: sqlite3.Connection, secret: object = None, *,
                      confirm_code: object = _UNSET, now: object = None) -> str:
    """准备管理员 TOTP 密钥；返回 base32（用于二维码/手抄）。

    只有以下两种方式会把它标为已启用：

    - 传 ``confirm_code``（必须与 ``secret`` 配套；错误则抛 ``ValueError``，
      密钥保持待确认）；
    - 之后再调 ``admin_totp_confirm(con, code)`` 成功。

    不带确认时 ``admin_totp_state()["enabled"]`` 仍为 ``False``。重新准备密钥会
    清空旧恢复码、旧 last-step，避免旧码跨密钥继续有效。
    """
    if secret is None:
        normalized = totp.generate_secret()
    else:
        normalized = "".join(str(secret).split()).replace("-", "").rstrip("=").upper()
        if totp.code(normalized, at=now or 0) is None:
            raise ValueError("invalid TOTP secret")
    current = admin_totp_state(con)
    if current["enabled"] and confirm_code is _UNSET:
        raise ValueError("TOTP is already enabled; disable it before reconfiguring")

    old_secret = settings_get(con, "admin_totp_secret")
    old_enabled = settings_get(con, "admin_totp_enabled")
    old_last = settings_get(con, "admin_totp_last_step")
    old_recovery = settings_get(con, "admin_recovery_codes")

    settings_set(con, "admin_totp_last_step", None)
    settings_set(con, "admin_totp_secret", _encrypt_totp_secret(con, normalized))
    settings_set(con, "admin_totp_enabled", "0")
    admin_recovery_clear(con)
    if confirm_code is not _UNSET and not admin_totp_confirm(con, confirm_code, at=now):
        # 确认失败不能把已经启用的 2FA 留在关闭状态；恢复旧密钥/恢复码/last-step。
        settings_set(con, "admin_totp_secret", old_secret)
        settings_set(con, "admin_totp_enabled", old_enabled)
        settings_set(con, "admin_totp_last_step", old_last)
        settings_set(con, "admin_recovery_codes", old_recovery)
        raise ValueError("TOTP code did not verify")
    return normalized


def admin_totp_disable(con: sqlite3.Connection) -> None:
    settings_set(con, "admin_totp_secret", None)
    settings_set(con, "admin_totp_enabled", "0")
    settings_set(con, "admin_totp_last_step", None)
    admin_recovery_clear(con)


# ------------------------------------------------------------------ 恢复码
_RECOVERY_LEN = 16  # 8 字节 hex


def _recovery_key(code: object) -> str | None:
    if not isinstance(code, str):
        return None
    cleaned = code.strip().lower().replace("-", "").replace(" ", "")
    if len(cleaned) != _RECOVERY_LEN or any(c not in "0123456789abcdef" for c in cleaned):
        return None
    return cleaned


def _recovery_hashes(con: sqlite3.Connection) -> list[str]:
    raw = settings_get(con, "admin_recovery_codes")
    if not isinstance(raw, str) or not raw:
        return []
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError:
        return []
    if not isinstance(parsed, list):
        return []
    return [h for h in parsed if isinstance(h, str) and len(h) == TOKEN_HASH_LEN]


def admin_recovery_generate(con: sqlite3.Connection, count: int = 10) -> list[str]:
    """生成一次性恢复码；只返回明文一次，库里存 SHA-256 数组。"""
    if not isinstance(count, int) or isinstance(count, bool) or not 1 <= count <= 20:
        raise ValueError("count must be 1-20")
    seen: set[str] = set()
    plain: list[str] = []
    while len(plain) < count:
        raw = secrets.token_hex(_RECOVERY_LEN // 2)
        if raw in seen:
            continue
        seen.add(raw)
        plain.append("-".join(raw[i:i + 4] for i in range(0, _RECOVERY_LEN, 4)))
    settings_set(con, "admin_recovery_codes",
                 json.dumps([hash_token(p.replace("-", "")) for p in plain]))
    return plain


def admin_recovery_remaining(con: sqlite3.Connection) -> int:
    return len(_recovery_hashes(con))


def admin_recovery_consume(con: sqlite3.Connection, code: object) -> bool:
    """消费一个恢复码；TOTP 未启用、格式不对或未命中都返回 ``False``。

    用“读旧值 → 条件更新旧值”做一次 CAS，避免两个并发请求把同一个码各用一次。
    """
    if not admin_totp_state(con)["enabled"]:
        return False
    key = _recovery_key(code)
    if key is None:
        return False
    raw = settings_get(con, "admin_recovery_codes")
    if not isinstance(raw, str) or not raw:
        return False
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError:
        return False
    if not isinstance(parsed, list):
        return False
    hashes = [h for h in parsed if isinstance(h, str) and len(h) == TOKEN_HASH_LEN]
    digest = hash_token(key)
    match = None
    for h in hashes:
        if constant_eq(h, digest) and match is None:
            match = h
    if match is None:
        return False
    hashes.remove(match)
    cur = con.execute(
        "UPDATE sync_state SET value=? WHERE key='admin_recovery_codes' AND value=?",
        (json.dumps(hashes), raw),
    )
    return cur.rowcount == 1


def admin_recovery_clear(con: sqlite3.Connection) -> None:
    settings_set(con, "admin_recovery_codes", None)


def admin_totp_verify(con: sqlite3.Connection, code_value: object, *,
                      at: object = None) -> bool:
    state = admin_totp_state(con)
    if not state["enabled"] or not state["secret"]:
        return False
    step = totp.verify(state["secret"], code_value, at=at, window=TOTP_WINDOW)
    if step is None:
        return False
    return _consume_totp_step(con, step)


# ------------------------------------------------------------------ 两步登录 preauth
def preauth_create(con: sqlite3.Connection, subject: str, ttl: int = PREAUTH_TTL, *,
                   now: object = None) -> tuple[str, int]:
    """建一次性 preauth nonce；返回 ``(nonce, expires_at)``，库中只存哈希。

    普通用户必须存在且 ``enabled=1``，否则拒绝（Task 4 可在密码正确后安全调用）。
    """
    uid = user_id_for_subject(subject) if isinstance(subject, str) else None
    if uid is None:
        raise ValueError("invalid preauth subject")
    if uid:
        user = get_user(con, uid)
        if user is None or not user["enabled"]:
            raise ValueError("unknown or disabled user")
    if not isinstance(ttl, int) or isinstance(ttl, bool) or ttl <= 0:
        raise ValueError("ttl must be a positive integer")
    ts = _now(now)
    nonce = secrets.token_urlsafe(32)
    expires_at = ts + ttl
    # 顺手清掉一小时前就过期的行；不影响一次性语义。
    con.execute("DELETE FROM admin_preauth WHERE expires_at < ?", (ts - 3600,))
    con.execute(
        "INSERT INTO admin_preauth(nonce_hash, subject, created_at, expires_at, consumed_at) "
        "VALUES(?,?,?,?,NULL)",
        (hash_token(nonce), subject, ts, expires_at),
    )
    return nonce, expires_at


def preauth_consume(con: sqlite3.Connection, nonce: object, *,
                    now: object = None) -> str | None:
    """消费 preauth nonce；有效返回 subject，过期/重放/未知返回 ``None``。"""
    if not isinstance(nonce, str) or not nonce:
        return None
    ts = _now(now)
    digest = hash_token(nonce)
    cur = con.execute(
        "UPDATE admin_preauth SET consumed_at=? "
        "WHERE nonce_hash=? AND consumed_at IS NULL AND expires_at > ?",
        (ts, digest, ts),
    )
    if cur.rowcount != 1:
        return None
    row = con.execute("SELECT subject FROM admin_preauth WHERE nonce_hash=?", (digest,)).fetchone()
    return row["subject"] if row is not None else None
