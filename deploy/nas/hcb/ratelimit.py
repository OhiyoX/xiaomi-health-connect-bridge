"""进程内固定窗口限流（只用标准库）。

登录、注册、换令牌等公开端点用 ``take(key, max, window)``：窗口内第
``max + 1`` 次返回 ``False``。桶在内存里，进程重启清零；按客户端 IP 分桶，
键由调用方拼好（例如 ``login:1.2.3.4``）。桶数量有硬上限，超出按最久未活动
的键淘汰，避免随机 IP 把内存撑爆。
"""
from __future__ import annotations

import threading
import time
from collections import OrderedDict

_LOCK = threading.Lock()
_BUCKETS: "OrderedDict[str, list[float]]" = OrderedDict()
_MAX_BUCKETS = 2048


def take(key: object, max: int, window: int, *, now: float | None = None) -> bool:
    """记一次；未超过 ``limit`` 返回 ``True``，否则返回 ``False``（不记）。"""
    if not isinstance(key, str) or not key:
        raise ValueError("key must be a non-empty string")
    if not isinstance(max, int) or isinstance(max, bool) or max <= 0:
        raise ValueError("max must be a positive integer")
    if not isinstance(window, (int, float)) or isinstance(window, bool) or window <= 0:
        raise ValueError("window must be positive")
    ts = time.monotonic() if now is None else float(now)
    cutoff = ts - float(window)
    with _LOCK:
        bucket = [t for t in _BUCKETS.pop(key, ()) if t > cutoff]
        if len(bucket) >= max:
            _BUCKETS[key] = bucket
            return False
        bucket.append(ts)
        _BUCKETS[key] = bucket
        if len(_BUCKETS) > _MAX_BUCKETS:
            _prune(cutoff)
        while len(_BUCKETS) > _MAX_BUCKETS:
            # 硬淘汰：最久没有活动的桶先走（OrderedDict 头）。
            _BUCKETS.popitem(last=False)
        return True


def _prune(cutoff: float) -> None:
    """丢掉已经全过期的桶，避免攻击者用随机 IP 撑大内存。"""
    for key in list(_BUCKETS):
        bucket = [t for t in _BUCKETS[key] if t > cutoff]
        if bucket:
            _BUCKETS[key] = bucket
        else:
            del _BUCKETS[key]


def remaining(key: str, max: int, window: int, *, now: float | None = None) -> int:
    """窗口内还剩几次（只读，不记）。"""
    ts = time.monotonic() if now is None else float(now)
    cutoff = ts - float(window)
    with _LOCK:
        used = len([t for t in _BUCKETS.get(key, ()) if t > cutoff])
    left = max - used
    return left if left > 0 else 0


def bucket_count() -> int:
    """当前桶数量（测试/观测用）。"""
    with _LOCK:
        return len(_BUCKETS)


def capacity() -> int:
    """硬上限（测试用）。"""
    return _MAX_BUCKETS


def reset(key: str | None = None) -> None:
    """清空一个桶（登录成功时用）或全部桶（测试/维护用）。"""
    with _LOCK:
        if key is None:
            _BUCKETS.clear()
        else:
            _BUCKETS.pop(key, None)


def clear() -> None:
    """``reset()`` 的别名。"""
    reset()
