"""分区的 SQLite 连接。

- owner 0：``HEALTH_DB``（默认 ``/data/health.db``）——admin / legacy / shared 用户共用。
- owner N>0：``<dirname(HEALTH_DB)>/users/<N>.db``——``data_mode='own'`` 的用户。

``db()`` 是上下文管理器：进入时连接、建表/迁移，退出时提交并关闭。
未显式给 owner 时读 ``hcb.context.owner``，所以老的 ``db()`` 调用点无需改参数。
"""
from __future__ import annotations

import os
import sqlite3
from contextlib import contextmanager
from typing import Iterator

from . import context, schema


def _check_owner(owner: int) -> int:
    if not isinstance(owner, int) or isinstance(owner, bool) or owner < 0:
        raise ValueError(f"invalid owner: {owner!r}")
    return owner


def health_db_path() -> str:
    """``HEALTH_DB`` 在调用时读取，便于测试与部署后改环境变量。"""
    return os.environ.get("HEALTH_DB", "/data/health.db")


def data_dir() -> str:
    return os.path.dirname(os.path.abspath(health_db_path()))


def users_dir() -> str:
    return os.path.join(data_dir(), "users")


def db_path(owner: int) -> str:
    owner = _check_owner(owner)
    if owner == 0:
        return health_db_path()
    return os.path.join(users_dir(), f"{owner}.db")


def user_partition(user_id: int) -> str:
    """返回用户分区的路径并建好目录（不建库）。"""
    user_id = _check_owner(user_id)
    if user_id == 0:
        raise ValueError("user_partition() 只接受用户 id（>0）")
    path = db_path(user_id)
    parent = os.path.dirname(path)
    os.makedirs(parent, mode=0o700, exist_ok=True)
    try:
        os.chmod(parent, 0o700)
    except OSError:
        pass  # 已存在且属于别人时让连接报错，不掩盖
    return path


def _safe_rollback(con: sqlite3.Connection) -> None:
    try:
        con.rollback()
    except Exception:  # noqa: BLE001
        pass


def _connect(path: str) -> sqlite3.Connection:
    fresh = not os.path.exists(path)
    con = sqlite3.connect(path, timeout=30)
    try:
        con.row_factory = sqlite3.Row
        con.execute("PRAGMA journal_mode=WAL")
        con.execute("PRAGMA foreign_keys=ON")
        con.execute("PRAGMA busy_timeout=5000")
    except BaseException:
        con.close()
        raise
    if fresh:
        try:
            os.chmod(path, 0o600)
        except OSError:
            pass
    return con


@contextmanager
def db(owner: int | None = None) -> Iterator[sqlite3.Connection]:
    """打开 owner 分区；``owner=None`` 用当前上下文的 owner。"""
    owner = _check_owner(context.get_owner() if owner is None else owner)
    if owner:
        path = user_partition(owner)
    else:
        path = db_path(0)
        parent = os.path.dirname(os.path.abspath(path))
        os.makedirs(parent, mode=0o700, exist_ok=True)
    con = _connect(path)
    try:
        if owner:
            schema.ensure_partition(con)
        else:
            schema.ensure_global(con)
        yield con
        con.commit()
    except BaseException:
        _safe_rollback(con)
        raise
    finally:
        con.close()
