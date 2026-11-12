#!/usr/bin/env python3
"""hc-health-mcp 管理员找回 CLI（只在 NAS 容器内或对 owner 0 数据库运行）。

用法（在容器内）::

    docker exec -it hc-health-mcp python /app/hcb_admin.py status
    docker exec -it hc-health-mcp python /app/hcb_admin.py reset-password
    docker exec -it hc-health-mcp python /app/hcb_admin.py reset-totp --yes
    docker exec -it hc-health-mcp python /app/hcb_admin.py revoke-sessions --user alice
    docker exec -it hc-health-mcp python /app/hcb_admin.py bootstrap
    docker exec -it hc-health-mcp python /app/hcb_admin.py audit-verify

也可在宿主机对备份/离线库运行，用 ``--db /path/to/health.db`` 指定 owner 0
数据库（放在子命令前后都可以）。

安全约定：

- 密码只从 stdin（TTY 用 ``getpass``，否则读一行）或 ``HCB_ADMIN_PASSWORD``
  读取，绝不出现在 argv、stdout、stderr 或审计里。
- ``reset-password``：写新哈希、吊销管理员会话与 OAuth 令牌、删除管理员授权码
  和未兑换的 preauth，再写一行审计；``--revoke-static`` 同时吊销全部静态 key。
- ``reset-totp``：清空 TOTP 密钥、恢复码、已用 step，并撤销管理员会话/
  OAuth/授权码/preauth；必须显式 ``--yes``。
- ``audit-verify`` 与 ``status`` 用 SQLite 只读连接，不建表、不写审计。
- 所有失败返回非 0；用法/缺库/弱密码返回 2，链校验失败/未知用户返回 1。
"""
from __future__ import annotations

import argparse
import getpass
import json
import os
import sqlite3
import sys
import time
from urllib.parse import quote, urlsplit

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

from hcb import audit, auth, db as hdb  # noqa: E402

EXIT_OK = 0
EXIT_FAIL = 1
EXIT_USAGE = 2

EXPECTED_GLOBAL_TABLES = (
    "users", "sessions", "admin_preauth", "agent_tokens",
    "oauth_clients", "oauth_codes", "oauth_tokens",
    "access_log", "service_secrets", "sync_state",
)


class CliError(RuntimeError):
    """可预期的 CLI 错误；``code`` 是进程退出码。"""

    def __init__(self, message: str, code: int = EXIT_FAIL):
        super().__init__(message)
        self.code = code


# ------------------------------------------------------------------ 连接
def _db_path(args: argparse.Namespace) -> str:
    """``--db`` 覆盖 ``HEALTH_DB``；返回规范化后的绝对路径。"""
    raw = getattr(args, "db", None)
    if raw:
        path = os.path.abspath(os.path.expanduser(str(raw)))
        # hcb.db.health_db_path() 在每次打开时读环境变量，所以这里设置即可。
        os.environ["HEALTH_DB"] = path
        return path
    return os.path.abspath(os.path.expanduser(hdb.health_db_path()))


def _require_db(path: str) -> None:
    if os.path.isdir(path):
        raise CliError(f"database path is a directory: {path}", EXIT_USAGE)
    if not os.path.isfile(path):
        raise CliError(
            f"database not found: {path} (start the service once or pass --db)",
            EXIT_USAGE,
        )


def _readonly_connect(path: str) -> sqlite3.Connection:
    """只读连接：audit-verify/status 不创建 schema，也不修改数据库。"""
    _require_db(path)
    uri = "file:" + quote(path, safe="/") + "?mode=ro"
    try:
        con = sqlite3.connect(uri, uri=True, timeout=5)
    except sqlite3.Error as exc:
        raise CliError(f"cannot open database read-only: {exc}", EXIT_FAIL) from None
    con.row_factory = sqlite3.Row
    con.execute("PRAGMA busy_timeout=5000")
    return con


def _table_exists(con: sqlite3.Connection, name: str) -> bool:
    row = con.execute(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (name,)
    ).fetchone()
    return row is not None


def _count(con: sqlite3.Connection, table: str, where: str = "",
           params: tuple[object, ...] = ()) -> int:
    if not _table_exists(con, table):
        return 0
    sql = f"SELECT COUNT(*) FROM {table}"
    if where:
        sql += " WHERE " + where
    return int(con.execute(sql, params).fetchone()[0])


def _audit_recovery(con: sqlite3.Connection, action: str,
                    payload: object) -> None:
    """找回命令的审计：正常追加；链坏了也不能阻止管理员恢复。

    ``audit.append`` 自带 savepoint，失败只回滚它自己的插入；这里捕获后
    改变仍会在 ``db()`` 退出时提交。只打印异常类型，不打印内容。
    """
    try:
        audit.append(con, "admin", auth.ADMIN_SUBJECT, action, payload, "ok")
    except Exception as exc:  # noqa: BLE001
        print(f"warning: audit append failed ({type(exc).__name__}); "
              "run audit-verify", file=sys.stderr)


def _env_int(name: str, default: int, *, minimum: int = 1) -> int:
    raw = os.environ.get(name)
    if raw is None or not str(raw).strip():
        return default
    try:
        value = int(str(raw).strip())
    except (TypeError, ValueError):
        return default
    return value if value >= minimum else default


def _env_bool(name: str) -> bool:
    return (os.environ.get(name, "0") or "0").strip().lower() in (
        "1", "true", "yes", "on")


def _public_base_for_status() -> str | None:
    """状态页只展示公开根；若环境值里带 userinfo，绝不回显。"""
    raw = (os.environ.get("PUBLIC_BASE_URL") or "").strip()
    if not raw:
        return None
    try:
        parts = urlsplit(raw)
    except ValueError:
        return None
    if parts.username or parts.password or parts.query or parts.fragment:
        return "[redacted: PUBLIC_BASE_URL contains credentials or query]"
    return raw


# ------------------------------------------------------------------ 输入
def _read_password(label: str, *, confirm: bool) -> str:
    """从 TTY 用 getpass，从管道读一行；空输入报用法错误。"""
    if sys.stdin.isatty():
        first = getpass.getpass(label)
        if confirm:
            second = getpass.getpass("Repeat: ")
            if first != second:
                raise CliError("passwords do not match", EXIT_USAGE)
        value = first
    else:
        line = sys.stdin.readline()
        if line == "":
            raise CliError(
                "no password received on stdin; pipe one line or run in a TTY",
                EXIT_USAGE,
            )
        value = line.rstrip("\r\n")
    if not value:
        raise CliError("empty password", EXIT_USAGE)
    return value


def _bootstrap_password() -> tuple[str, str]:
    value = os.environ.get("HCB_ADMIN_PASSWORD", "")
    if value:
        return value, "env"
    return _read_password("New admin password: ", confirm=True), "stdin"


# ------------------------------------------------------------------ 命令
def cmd_bootstrap(args: argparse.Namespace, path: str) -> int:
    """首次建管理员；已有密码时幂等返回 0，不覆盖。"""
    if os.path.isfile(path):
        with hdb.db(0) as con:
            if auth.admin_has_password(con):
                print("admin password already configured; no change")
                return EXIT_OK
    password, source = _bootstrap_password()
    if len(password) < auth.ADMIN_MIN_PASSWORD or not password.strip():
        raise CliError(
            f"admin password must be at least {auth.ADMIN_MIN_PASSWORD} chars",
            EXIT_USAGE)
    if not os.path.isdir(os.path.dirname(path)):
        # db() 也会建，但提前建可以给出更清楚的错误。
        try:
            os.makedirs(os.path.dirname(path), mode=0o700, exist_ok=True)
        except OSError as exc:
            raise CliError(f"cannot create database directory: {exc}", EXIT_USAGE) from None
    with hdb.db(0) as con:
        result = auth.bootstrap_admin(
            con, env={"HCB_ADMIN_PASSWORD": password})
        if result.created:
            _audit_recovery(con, "auth.bootstrap_cli", {
                "source": source, "status": result.status})
        elif result.status != "already_configured":
            raise CliError(result.message or result.status, EXIT_USAGE)
    if result.created:
        print("admin password created")
    else:
        print("admin password already configured; no change")
    return EXIT_OK


def cmd_reset_password(args: argparse.Namespace, path: str) -> int:
    """改管理员密码并撤掉管理员会话/令牌/授权码/preauth。"""
    _require_db(path)
    password = _read_password("New admin password: ", confirm=True)
    static_revoked = 0
    try:
        with hdb.db(0) as con:
            auth.admin_set_password(con, password)
            deleted = con.execute(
                "DELETE FROM admin_preauth WHERE subject=?",
                (auth.ADMIN_SUBJECT,)).rowcount
            if getattr(args, "revoke_static", False):
                static_revoked = con.execute(
                    "UPDATE agent_tokens SET revoked_at=? "
                    "WHERE revoked_at IS NULL", (int(time.time()),)).rowcount
            _audit_recovery(con, "admin.password.reset_cli", {
                "source": "cli",
                "preauth_deleted": max(0, deleted),
                "static_revoked": max(0, static_revoked),
            })
    except ValueError as exc:
        raise CliError(str(exc), EXIT_USAGE) from None
    message = ("admin password reset; admin sessions, OAuth tokens, codes "
               "and preauth revoked")
    if getattr(args, "revoke_static", False):
        message += f"; {max(0, static_revoked)} static token(s) revoked"
    print(message)
    return EXIT_OK


def cmd_reset_totp(args: argparse.Namespace, path: str) -> int:
    """清空管理员 TOTP/恢复码，并撤销管理员会话、OAuth 与待兑换码。"""
    if not args.yes:
        raise CliError(
            "refusing to reset TOTP without --yes (this clears the secret, "
            "recovery codes, and admin sessions/tokens)", EXIT_USAGE)
    _require_db(path)
    with hdb.db(0) as con:
        auth.admin_totp_disable(con)
        now = int(time.time())
        sessions = auth.revoke_subject(con, auth.ADMIN_SUBJECT, now=now)
        tokens = con.execute(
            "UPDATE oauth_tokens SET revoked_at=? "
            "WHERE user_id=0 AND revoked_at IS NULL", (now,)).rowcount
        codes = con.execute(
            "DELETE FROM oauth_codes WHERE user_id=0").rowcount
        preauth = con.execute(
            "DELETE FROM admin_preauth WHERE subject=?",
            (auth.ADMIN_SUBJECT,)).rowcount
        _audit_recovery(con, "admin.totp.reset_cli", {
            "source": "cli",
            "sessions_revoked": int(sessions),
            "tokens_revoked": max(0, tokens),
            "codes_deleted": max(0, codes),
            "preauth_deleted": max(0, preauth),
        })
    print("admin TOTP disabled; admin sessions/OAuth/codes/preauth and "
          "recovery codes cleared")
    return EXIT_OK


def _resolve_subject(con: sqlite3.Connection, value: str | None
                     ) -> tuple[str, str]:
    """``--user`` → (subject, 显示名)；默认 admin。"""
    if value is None or not str(value).strip():
        return auth.ADMIN_SUBJECT, auth.ADMIN_SUBJECT
    raw = str(value).strip()
    if raw.lower() == auth.ADMIN_SUBJECT:
        return auth.ADMIN_SUBJECT, auth.ADMIN_SUBJECT
    name = raw[5:] if raw.lower().startswith("user:") else raw
    user = auth.by_username(con, name)
    if user is None and name.isdigit():
        user = auth.get_user(con, int(name))
    if user is None:
        raise CliError(f"unknown user: {raw}", EXIT_FAIL)
    return auth.subject_for_user(user["id"]), str(user["username"])


def cmd_revoke_sessions(args: argparse.Namespace, path: str) -> int:
    """吊销管理员或指定用户的全部有效会话。"""
    _require_db(path)
    with hdb.db(0) as con:
        subject, display = _resolve_subject(con, args.user)
        count = auth.revoke_subject(con, subject)
        preauth = 0
        if subject == auth.ADMIN_SUBJECT:
            preauth = con.execute(
                "DELETE FROM admin_preauth WHERE subject=?",
                (auth.ADMIN_SUBJECT,)).rowcount
        _audit_recovery(con, "admin.session.revoke_cli",
                        {"target": display, "revoked": int(count),
                         "preauth_deleted": max(0, preauth)})
    print(f"revoked {count} session(s) for {display}")
    return EXIT_OK


def _audit_verify_result(path: str) -> dict:
    con = _readonly_connect(path)
    try:
        try:
            result = audit.verify(con)
        except Exception as exc:  # noqa: BLE001
            result = {
                "ok": False, "rows": 0, "head": None,
                "error": f"{type(exc).__name__}: {exc}",
                "first_bad_id": None,
            }
    finally:
        con.close()
    return result


def cmd_audit_verify(args: argparse.Namespace, path: str) -> int:
    """只读校验审计哈希链；失败退出 1。"""
    result = _audit_verify_result(path)
    print(json.dumps(result, ensure_ascii=False, sort_keys=True, indent=2))
    return EXIT_OK if result.get("ok") else EXIT_FAIL


def cmd_status(args: argparse.Namespace, path: str) -> int:
    """只读状态摘要，绝不输出密码、令牌、Cookie、TOTP 密钥或恢复码。"""
    con = _readonly_connect(path)
    try:
        if not _table_exists(con, "access_log"):
            raise CliError(
                "database schema is not initialized (run the service or bootstrap)",
                EXIT_FAIL)
        now = int(time.time())
        missing = [name for name in EXPECTED_GLOBAL_TABLES
                   if not _table_exists(con, name)]
        audit_result = _audit_verify_result(path)
        if _table_exists(con, "sync_state"):
            admin = {
                "password_set": auth.admin_has_password(con),
                "totp_enabled": auth.admin_totp_state(con)["enabled"],
                "recovery_codes_remaining": auth.admin_recovery_remaining(con),
            }
        else:
            admin = {"password_set": None, "totp_enabled": None,
                     "recovery_codes_remaining": None}
        data = {
            "ok": True,
            "db": path,
            "database_bytes": os.path.getsize(path),
            "schema": {"complete": not missing, "missing_tables": missing},
            "admin": admin,
            "users": {
                "total": _count(con, "users"),
                "enabled": _count(con, "users", "enabled=1"),
                "own_partitions": _count(con, "users", "data_mode='own'"),
            },
            "sessions": {
                "active": _count(con, "sessions",
                                 "revoked_at IS NULL AND expires_at>?", (now,)),
                "admin_active": _count(
                    con, "sessions",
                    "subject=? AND revoked_at IS NULL AND expires_at>?",
                    (auth.ADMIN_SUBJECT, now)),
                "preauth_pending": _count(
                    con, "admin_preauth",
                    "consumed_at IS NULL AND expires_at>?", (now,)),
            },
            "oauth": {
                "clients": _count(con, "oauth_clients"),
                "active_clients": _count(con, "oauth_clients",
                                         "revoked_at IS NULL"),
                "active_tokens": _count(
                    con, "oauth_tokens",
                    "revoked_at IS NULL AND expires_at>?", (now,)),
                "pending_codes": _count(
                    con, "oauth_codes",
                    "consumed_at IS NULL AND expires_at>?", (now,)),
            },
            "static_tokens": {
                "active": _count(con, "agent_tokens", "revoked_at IS NULL"),
            },
            "audit": {
                "rows": _count(con, "access_log"),
                "chain_ok": bool(audit_result.get("ok")),
                "error": audit_result.get("error"),
                "first_bad_id": audit_result.get("first_bad_id"),
                "head": (audit_result.get("head") or "")[:12] or None,
            },
            "config": {
                "public_base_url": _public_base_for_status(),
                "trust_proxy": _env_bool("HCB_TRUST_PROXY"),
                "trusted_proxies_configured": bool(
                    (os.environ.get("HCB_TRUSTED_PROXIES") or "").strip()),
                "audit_retention_days": _env_int("AUDIT_RETENTION_DAYS", 90),
                "version": os.environ.get("HCB_VERSION", "1.0"),
            },
        }
    finally:
        con.close()
    print(json.dumps(data, ensure_ascii=False, sort_keys=True, indent=2))
    return EXIT_OK


# ------------------------------------------------------------------ 入口
def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="hcb_admin.py",
        description="hc-health-mcp 管理员找回与诊断 CLI（只操作 owner 0 数据库）",
    )
    parser.add_argument(
        "--db", default=None,
        help="owner 0 SQLite 路径；默认 $HEALTH_DB 或 /data/health.db")
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument(
        "--db", default=argparse.SUPPRESS,
        help="同全局 --db（可放在子命令之后）")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("bootstrap", parents=[common],
                       help="用 HCB_ADMIN_PASSWORD 或 stdin 首次建管理员（幂等）")
    p.set_defaults(func=cmd_bootstrap)

    p = sub.add_parser("reset-password", parents=[common],
                       help="重置管理员密码并吊销其会话/OAuth/授权码/preauth")
    p.add_argument(
        "--revoke-static", action="store_true",
        help="同时吊销所有静态 Bearer key（owner 0 与普通用户；默认保留）")
    p.set_defaults(func=cmd_reset_password)

    p = sub.add_parser("reset-totp", parents=[common],
                       help="清空管理员 TOTP 与恢复码，并撤销管理员会话/令牌（需要 --yes）")
    p.add_argument("--yes", action="store_true", help="确认清空 TOTP")
    p.set_defaults(func=cmd_reset_totp)

    p = sub.add_parser("revoke-sessions", parents=[common],
                       help="吊销管理员或 --user 的全部会话")
    p.add_argument("--user", default=None,
                   help="用户名或用户 id；省略时只吊销管理员会话")
    p.set_defaults(func=cmd_revoke_sessions)

    p = sub.add_parser("audit-verify", parents=[common],
                       help="只读校验审计哈希链；失败退出 1")
    p.set_defaults(func=cmd_audit_verify)

    p = sub.add_parser("status", parents=[common],
                       help="只读状态摘要（不含任何秘密）")
    p.set_defaults(func=cmd_status)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        path = _db_path(args)
        return int(args.func(args, path))
    except CliError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return exc.code
    except BrokenPipeError:
        return EXIT_OK
    except KeyboardInterrupt:
        print("error: interrupted", file=sys.stderr)
        return 130
    except Exception as exc:  # noqa: BLE001
        # 不打印 traceback；异常文本里不会出现密码/令牌（调用方从不传入）。
        print(f"error: {type(exc).__name__}: {exc}", file=sys.stderr)
        return EXIT_FAIL


if __name__ == "__main__":
    sys.exit(main())
