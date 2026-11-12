"""当前请求的数据分区（owner）。

``owner`` 是 ``ContextVar``：线程/任务各自独立，默认 0（admin / legacy / shared 用户）。
``db(owner=None)`` 在未显式给 owner 时读这个值。Web 层在解析出身份后 ``set_owner``，
并在请求结束时 ``reset_owner`` 回原值。
"""
from __future__ import annotations

from contextvars import ContextVar, Token

owner: ContextVar[int] = ContextVar("hcb_owner", default=0)


def _check(value: int) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        raise ValueError(f"invalid owner: {value!r}")
    return value


def set_owner(value: int) -> Token:
    """把当前上下文切到 ``value`` 分区，返回可传给 ``reset_owner`` 的 token。"""
    return owner.set(_check(value))


def reset_owner(token: Token) -> None:
    owner.reset(token)


def get_owner() -> int:
    return owner.get()
