"""SQLite schema：业务表（每个分区）+ 账号/OAuth/审计表（仅 owner 0）。

分区规则（2026-11 复核裁定）：``health.db``（owner 0）含业务表 + 账号/OAuth/审计表；
``users/<id>.db`` 只含业务表。认证/后台代码必须显式 ``db(0)``。

迁移：沿用 ``CREATE TABLE IF NOT EXISTS``。老库（2026-10-21 之前）的业务表主键里
没有 ``source``，由 ``_migrate_legacy`` 在显式 ``BEGIN IMMEDIATE`` 里就地重建；失败
则整体回滚，老表与数据保持原样。进度用 ``PRAGMA user_version`` 的位标记记录，避免
每次 ``/health`` 都跑整套 DDL：

- bit 0：业务表已建好且老表迁移成功；
- bit 1：全局（账号/OAuth/审计）表已建好（只有 owner 0 会设置）。
"""
from __future__ import annotations

import os
import sqlite3

BUSINESS_VERSION = 1
GLOBAL_VERSION = 2

BUSINESS_TABLES = """
CREATE TABLE IF NOT EXISTS heart_rate (
    ts INTEGER NOT NULL, bpm INTEGER,
    source TEXT NOT NULL DEFAULT 'xiaomi-cloud', PRIMARY KEY(ts, source));
CREATE TABLE IF NOT EXISTS spo2 (
    ts INTEGER NOT NULL, pct REAL,
    source TEXT NOT NULL DEFAULT 'xiaomi-cloud', PRIMARY KEY(ts, source));
CREATE TABLE IF NOT EXISTS steps (
    ts INTEGER NOT NULL, steps INTEGER, distance_m REAL,
    source TEXT NOT NULL DEFAULT 'xiaomi-cloud', PRIMARY KEY(ts, source));
CREATE TABLE IF NOT EXISTS sleep (
    bed INTEGER NOT NULL, wake INTEGER, minutes INTEGER, deep_min INTEGER,
    source TEXT NOT NULL DEFAULT 'xiaomi-cloud', PRIMARY KEY(bed, source));
CREATE TABLE IF NOT EXISTS calories (
    ts INTEGER NOT NULL, kcal REAL,
    source TEXT NOT NULL DEFAULT 'xiaomi-cloud', PRIMARY KEY(ts, source));
-- 手机推送（HC 独有、云端没有的类型）：workouts / weight
CREATE TABLE IF NOT EXISTS workout (
    start INTEGER NOT NULL, end INTEGER, minutes INTEGER,
    exercise_type INTEGER, title TEXT, origin TEXT,
    source TEXT NOT NULL DEFAULT 'phone', PRIMARY KEY(start, source));
CREATE TABLE IF NOT EXISTS stress (
    ts INTEGER NOT NULL, stress INTEGER,
    source TEXT NOT NULL DEFAULT 'xiaomi-cloud', PRIMARY KEY(ts, source));
CREATE TABLE IF NOT EXISTS weight (
    ts INTEGER NOT NULL, kg REAL, origin TEXT,
    bmi REAL, fat_rate REAL, muscle_kg REAL, body_age INTEGER,
    source TEXT NOT NULL DEFAULT 'xiaomi-cloud', PRIMARY KEY(ts, source));
CREATE TABLE IF NOT EXISTS sync_state (key TEXT PRIMARY KEY, value TEXT);
-- 完整 payload 原样保存：加字段/改解析时从本地重算，不再打小米云（2026-10-21 业主定的口径）
CREATE TABLE IF NOT EXISTS raw (
    key TEXT NOT NULL, ts INTEGER NOT NULL, value TEXT,
    PRIMARY KEY(key, ts));
"""

ACCOUNT_TABLES = """
CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE COLLATE NOCASE,
    display_name TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    scopes_json TEXT NOT NULL DEFAULT '["health.read"]',
    tool_policy_json TEXT,                      -- NULL = 不额外限制
    window_json TEXT,                           -- NULL = 不限时
    data_mode TEXT NOT NULL DEFAULT 'shared',   -- 'shared' = admin 分区/凭据；'own' = 自己的分区/凭据
    enabled INTEGER NOT NULL DEFAULT 1,
    revision INTEGER NOT NULL DEFAULT 1,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS sessions (
    sid_hash TEXT PRIMARY KEY,          -- SHA-256(sid)，Cookie 里才是原值
    subject TEXT NOT NULL,              -- 'admin' 或 'user:<id>'
    revision INTEGER NOT NULL,
    user_agent TEXT,                    -- 截断到 120 字符
    created_at INTEGER NOT NULL,
    last_seen_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    revoked_at INTEGER
);
CREATE INDEX IF NOT EXISTS ix_sessions_subject ON sessions(subject, revoked_at);

-- 两步登录的 preauth nonce：只存哈希，一次性，5 分钟有效
CREATE TABLE IF NOT EXISTS admin_preauth (
    nonce_hash TEXT PRIMARY KEY,
    subject TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    consumed_at INTEGER
);
CREATE INDEX IF NOT EXISTS ix_admin_preauth_expires ON admin_preauth(expires_at);

CREATE TABLE IF NOT EXISTS agent_tokens (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    label TEXT NOT NULL UNIQUE,
    hash TEXT NOT NULL UNIQUE,
    tool_policy_json TEXT,                     -- NULL = 不额外限制
    window_json TEXT,                          -- NULL = 不限时
    owner_user_id INTEGER NOT NULL DEFAULT 0,  -- 0 = admin/legacy；手机推送写入该分区
    origin TEXT NOT NULL DEFAULT 'ui',         -- 'env' | 'ui'
    created_at INTEGER NOT NULL,
    last_used_at INTEGER,
    revoked_at INTEGER
);
"""

OAUTH_TABLES = """
CREATE TABLE IF NOT EXISTS oauth_clients (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    client_id TEXT NOT NULL UNIQUE,
    client_secret_hash TEXT,                   -- NULL = 公开客户端
    client_name TEXT NOT NULL,
    redirect_uris_json TEXT NOT NULL,
    token_endpoint_auth_method TEXT NOT NULL DEFAULT 'none',
    tool_policy_json TEXT,                     -- NULL = 不额外限制；客户端上限
    window_json TEXT,                          -- NULL = 不限时；客户端上限
    created_at INTEGER NOT NULL,
    last_used_at INTEGER,
    revoked_at INTEGER
);

CREATE TABLE IF NOT EXISTS oauth_codes (
    code_hash TEXT PRIMARY KEY,
    client_id TEXT NOT NULL,
    redirect_uri TEXT NOT NULL,
    code_challenge TEXT NOT NULL,
    scopes_json TEXT NOT NULL,
    resource TEXT NOT NULL,
    session_sid TEXT NOT NULL,
    user_id INTEGER NOT NULL,           -- 0 = admin
    username TEXT NOT NULL,
    user_revision INTEGER NOT NULL,     -- 与 users.revision 比对
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    consumed_at INTEGER
);

CREATE TABLE IF NOT EXISTS oauth_tokens (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    token_hash TEXT NOT NULL UNIQUE,
    prefix TEXT NOT NULL,               -- 前 12 字符，只用于页面识别
    kind TEXT NOT NULL CHECK (kind IN ('access','refresh')),
    client_id TEXT NOT NULL,
    family_id TEXT NOT NULL,
    scopes_json TEXT NOT NULL,
    tool_policy_json TEXT,              -- 令牌级策略；NULL = 不额外限制
    window_json TEXT,                   -- 令牌级时间窗；NULL = 不限时
    user_id INTEGER NOT NULL,           -- 0 = admin
    username TEXT NOT NULL,
    user_revision INTEGER NOT NULL,     -- 改密/停用/改 scope 后令牌立即失效
    resource TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    revoked_at INTEGER,
    replaced_by_hash TEXT,
    last_used_at INTEGER
);
"""

AUDIT_TABLES = """
CREATE TABLE IF NOT EXISTS access_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    actor TEXT NOT NULL,                -- 'admin' / 用户名 / 令牌标签
    kind TEXT NOT NULL,                 -- 'static' | 'oauth'
    action TEXT NOT NULL,               -- 'mcp.call' / 'admin.sync' / 'oauth.consent' …
    tool TEXT,
    result TEXT,
    detail TEXT,
    prev_hash TEXT,
    hash TEXT,
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_access_hash ON access_log(hash);

CREATE TABLE IF NOT EXISTS service_secrets (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL                 -- 会话/CSRF 签名密钥；首次启动生成
);
"""

GLOBAL_TABLES = ACCOUNT_TABLES + OAUTH_TABLES + AUDIT_TABLES

# 2026-10-21 之前的单来源表 → 多来源（主键带 source）。定义固定，表名来自本模块。
_LEGACY_MULTI_SOURCE = {
    "heart_rate": ("ts INTEGER NOT NULL, bpm INTEGER, "
                   "source TEXT NOT NULL DEFAULT 'xiaomi-cloud', PRIMARY KEY(ts, source)"),
    "spo2": ("ts INTEGER NOT NULL, pct REAL, "
             "source TEXT NOT NULL DEFAULT 'xiaomi-cloud', PRIMARY KEY(ts, source)"),
    "steps": ("ts INTEGER NOT NULL, steps INTEGER, distance_m REAL, "
              "source TEXT NOT NULL DEFAULT 'xiaomi-cloud', PRIMARY KEY(ts, source)"),
    "calories": ("ts INTEGER NOT NULL, kcal REAL, "
                 "source TEXT NOT NULL DEFAULT 'xiaomi-cloud', PRIMARY KEY(ts, source)"),
    "stress": ("ts INTEGER NOT NULL, stress INTEGER, "
               "source TEXT NOT NULL DEFAULT 'xiaomi-cloud', PRIMARY KEY(ts, source)"),
    "sleep": ("bed INTEGER NOT NULL, wake INTEGER, minutes INTEGER, deep_min INTEGER, "
              "source TEXT NOT NULL DEFAULT 'xiaomi-cloud', PRIMARY KEY(bed, source)"),
    "weight": ("ts INTEGER NOT NULL, kg REAL, origin TEXT, bmi REAL, fat_rate REAL, "
               "muscle_kg REAL, body_age INTEGER, "
               "source TEXT NOT NULL DEFAULT 'xiaomi-cloud', PRIMARY KEY(ts, source)"),
    "workout": ("start INTEGER NOT NULL, end INTEGER, minutes INTEGER, exercise_type INTEGER, "
                "title TEXT, origin TEXT, source TEXT NOT NULL DEFAULT 'phone', "
                "PRIMARY KEY(start, source)"),
}


def _user_version(con: sqlite3.Connection) -> int:
    return int(con.execute("PRAGMA user_version").fetchone()[0] or 0)


def _set_user_version(con: sqlite3.Connection, value: int) -> None:
    con.execute(f"PRAGMA user_version = {int(value)}")


def _safe_rollback(con: sqlite3.Connection) -> None:
    """回滚失败不能掩盖原始异常（例如连接已坏）。"""
    try:
        con.rollback()
    except Exception:  # noqa: BLE001
        pass


def _table_names(con: sqlite3.Connection) -> set[str]:
    return {r[0] for r in con.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")}


def _columns(con: sqlite3.Connection, table: str) -> list[sqlite3.Row]:
    return con.execute(f"PRAGMA table_info({table})").fetchall()


def _add_missing_weight_columns(con: sqlite3.Connection) -> None:
    """老库补列（CREATE TABLE IF NOT EXISTS 不会给已存在的表加列）。"""
    cols = {r[1] for r in _columns(con, "weight")}
    for col, typ in (("bmi", "REAL"), ("fat_rate", "REAL"),
                     ("muscle_kg", "REAL"), ("body_age", "INTEGER")):
        if col not in cols:
            con.execute(f"ALTER TABLE weight ADD COLUMN {col} {typ}")


def _rebuild_table(con: sqlite3.Connection, table: str, newdef: str) -> bool:
    """在显式写事务里重建一张老表；失败整体回滚。

    Python ``sqlite3`` 的 legacy 隔离级别不会为 DDL 自动开事务，所以这里手动
    ``BEGIN IMMEDIATE``：RENAME / CREATE / INSERT / DROP 要么全成，要么全退，
    不会出现「live 表空、数据只剩在 <table>_old」的半截状态。
    """
    con.commit()  # 结束前面补列等操作的隐式事务
    con.execute("BEGIN IMMEDIATE")
    try:
        info = _columns(con, table)
        cols = [r[1] for r in info]
        pk = [r[1] for r in info if r[5]]
        if "source" in cols and "source" in pk:
            con.commit()
            return False
        if f"{table}_old" in _table_names(con):
            raise RuntimeError(f"{table}_old 已存在，拒绝覆盖")
        con.execute(f"ALTER TABLE {table} RENAME TO {table}_old")
        con.execute(f"CREATE TABLE {table} ({newdef})")
        new_cols = {r[1] for r in _columns(con, table)}
        copy = [c for c in cols if c != "source" and c in new_cols]
        names = ",".join(copy)
        con.execute(f"INSERT OR REPLACE INTO {table}({names},source) "
                    f"SELECT {names},'xiaomi-cloud' FROM {table}_old")
        con.execute(f"DROP TABLE {table}_old")
        con.commit()
        return True
    except BaseException:
        _safe_rollback(con)
        raise


def _migrate_legacy(con: sqlite3.Connection) -> bool:
    """升级所有单来源老表；任何一张失败都返回 False，且该表保持原样。"""
    ok = True
    for table, newdef in _LEGACY_MULTI_SOURCE.items():
        try:
            if _rebuild_table(con, table, newdef):
                print(f"[migrate] {table} → 多来源结构", flush=True)
        except Exception as e:  # noqa: BLE001
            ok = False
            print(f"[migrate] {table} 跳过: {e}", flush=True)
    return ok


def ensure_partition(con: sqlite3.Connection) -> bool:
    """只建业务表（用户分区用）。返回老表迁移是否全部成功。"""
    version = _user_version(con)
    if version & BUSINESS_VERSION:
        return True
    con.executescript(BUSINESS_TABLES)
    _add_missing_weight_columns(con)
    ok = _migrate_legacy(con)
    if ok:
        _set_user_version(con, _user_version(con) | BUSINESS_VERSION)
        con.commit()
    return ok


def ensure_global(con: sqlite3.Connection) -> bool:
    """owner 0：业务表 + 账号/OAuth/审计表。返回老表迁移是否全部成功。"""
    ok = ensure_partition(con)
    version = _user_version(con)
    if not version & GLOBAL_VERSION:
        con.executescript(GLOBAL_TABLES)
        _set_user_version(con, _user_version(con) | GLOBAL_VERSION)
        con.commit()
    return ok


def ensure_all() -> None:
    """owner 0 建全局 schema；每个已有 ``users/<id>.db`` 建业务 schema。

    单个分区损坏只记录并继续，后续分区仍会初始化。
    """
    from . import db as hdb  # 延迟导入，避免 db ↔ schema 循环

    with hdb.db(0):
        pass
    udir = hdb.users_dir()
    if not os.path.isdir(udir):
        return
    for name in sorted(os.listdir(udir)):
        stem, ext = os.path.splitext(name)
        if ext != ".db" or not (stem.isascii() and stem.isdigit()):
            continue
        user_id = int(stem)
        if user_id <= 0:
            continue
        try:
            with hdb.db(user_id):
                pass
        except Exception as e:  # noqa: BLE001
            print(f"[schema] users/{user_id}.db: {e}", flush=True)
