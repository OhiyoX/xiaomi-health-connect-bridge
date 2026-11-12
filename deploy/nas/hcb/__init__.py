"""hc-bridge NAS 服务端的内置包：数据库、schema、认证、OAuth、后台。

只用 Python 标准库；部署时与 ``server.py`` 一起同步到 NAS 的 ``app/``。
"""
from __future__ import annotations

__all__ = ["admin", "auth", "context", "db", "oauth", "policy",
           "ratelimit", "schema", "views", "web"]
