"""TOTP（RFC 6238）：base32 密钥、HMAC-SHA1、30 秒、6 位。

只用标准库。生成的密钥是 160 位（20 字节）base32，无填充。
``code()`` 对坏密钥返回 ``None``，不抛异常；``verify()`` 返回命中的绝对 step
（``unix 时间 // 30``），未命中返回 ``None``。
"""
from __future__ import annotations

import base64
import binascii
import hashlib
import hmac
import re
import secrets
import time
from datetime import datetime

STEP = 30
DIGITS = 6
MIN_SECRET_BYTES = 10  # 80 位；生成的是 160 位

_B32_RE = re.compile(r"^[A-Z2-7]+$")


def generate_secret() -> str:
    """生成新的 base32 密钥（20 字节，无 ``=`` 填充）。"""
    return base64.b32encode(secrets.token_bytes(20)).decode("ascii").rstrip("=")


def _decode_secret(secret: object) -> bytes | None:
    if not isinstance(secret, str):
        return None
    cleaned = "".join(secret.split()).replace("-", "").rstrip("=").upper()
    if not cleaned or not _B32_RE.fullmatch(cleaned):
        return None
    try:
        key = base64.b32decode(cleaned + "=" * (-len(cleaned) % 8), casefold=True)
    except (binascii.Error, ValueError):
        return None
    return key if len(key) >= MIN_SECRET_BYTES else None


def _epoch(at: object = None) -> float:
    if at is None:
        return time.time()
    if isinstance(at, datetime):
        return at.timestamp()
    if isinstance(at, bool):
        raise ValueError("at must be a timestamp")
    return float(at)  # type: ignore[arg-type]


def _normalize_code(value: object) -> str | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return f"{value:06d}" if 0 <= value < 10 ** DIGITS else None
    if isinstance(value, str):
        cleaned = value.strip().replace(" ", "").replace("-", "")
        if len(cleaned) == DIGITS and cleaned.isdigit():
            return cleaned
    return None


def _code_for_counter(key: bytes, counter: int) -> str:
    digest = hmac.new(key, counter.to_bytes(8, "big"), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    value = int.from_bytes(digest[offset:offset + 4], "big") & 0x7FFFFFFF
    return f"{value % (10 ** DIGITS):0{DIGITS}d}"


def code(secret: object, at: object = None) -> str | None:
    """返回 ``at`` 时刻的 6 位码；密钥无效时返回 ``None``。"""
    key = _decode_secret(secret)
    if key is None:
        return None
    counter = int(_epoch(at) // STEP)
    if counter < 0:
        return None
    return _code_for_counter(key, counter)


def verify(secret: object, code: object, at: object = None, window: int = 1) -> int | None:
    """校验 6 位码；返回命中的绝对 step，失败返回 ``None``。

    ``window`` 是前后允许的步数（默认 ±1，即 ±30 秒）。
    """
    if not isinstance(window, int) or isinstance(window, bool) or window < 0:
        raise ValueError("window must be a non-negative integer")
    candidate = _normalize_code(code)
    key = _decode_secret(secret)
    if candidate is None or key is None:
        return None
    base = int(_epoch(at) // STEP)
    hit: int | None = None
    for offset in range(-window, window + 1):
        step = base + offset
        if step < 0:
            continue
        if hmac.compare_digest(_code_for_counter(key, step), candidate) and hit is None:
            hit = step
    # 不在第一个命中处提前返回，避免窗口内的比较次数泄露位置。
    return hit


# 实现计划里用过这些名字；保留别名，方便 Task 4/6 按计划直接调用。
totp_secret = generate_secret
totp_code = code
verify_totp = verify
