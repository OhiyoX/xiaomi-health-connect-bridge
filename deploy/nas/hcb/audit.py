"""审计哈希链、保留与清理。

每一行 ``access_log`` 都带 ``prev_hash`` / ``hash``：

    hash = sha256(prev_hash + actor + action + payload_json + result + created_at)

``payload_json`` 是脱敏后的 canonical JSON（排序键、紧凑分隔符、UTF-8）。
链头存在 ``sync_state`` 的 ``audit_head``（JSON：id/hash/count），与行写入
在同一个事务里更新；保留清理的锚点存在 ``audit_anchor``。

约定：

- 已写入的历史行不重写、不重新加封；只有保留清理会删除过期行。
- 写操作使用 ``BEGIN IMMEDIATE``（调用方已有事务时沿用调用方事务）。
- 链头与末行不一致时拒绝追加（fail-closed），不把断链接上。
- payload 里的密码、令牌、授权码、Cookie、TOTP 密钥与健康数据先脱敏。
- ``kind`` / ``tool`` 也写进哈希覆盖的 payload；``access_log.kind`` /
  ``access_log.tool`` 列只是展示用的投影，不能脱离 payload 单独改。
- 链头/anchor 与行在同一快照里校验；没有外层事务时用 ``BEGIN`` 包住三次读取。
"""
from __future__ import annotations

import hashlib
import json
import os
import sqlite3
import time
from typing import Any, Mapping

AUDIT_RETENTION_DAYS = 90
HEAD_KEY = "audit_head"
ANCHOR_KEY = "audit_anchor"
MAX_ACTOR = 120
MAX_ACTION = 120
MAX_STRING = 500
MAX_PAYLOAD = 4000

# 键名命中这些片段就整体替换为 ``[redacted]``。宁可多脱敏，不漏密钥。
_SECRET_KEY_PARTS = (
    "password", "passwd", "pass", "secret", "token", "cookie", "authorization",
    "auth", "credential", "code", "verifier", "sid", "session", "totp",
    "recovery", "bearer", "key",
)
_HEALTH_KEY_PARTS = (
    "health", "heart", "bpm", "spo2", "step", "sleep", "calorie", "workout",
    "weight", "stress", "glucose", "raw", "payload", "data",
)


class AuditError(RuntimeError):
    """审计链不可用或已损坏；调用方必须让安全动作失败。"""


def _clean_text(value: object, limit: int) -> str:
    text = "" if value is None else str(value)
    text = "".join(ch for ch in text if ch == "\t" or ch >= " ")
    return text[:limit]


def _sensitive_key(key: str) -> bool:
    normalized = "".join(ch for ch in key.lower() if ch.isalnum())
    return any(part in normalized for part in _SECRET_KEY_PARTS) or any(
        part in normalized for part in _HEALTH_KEY_PARTS)


def _sanitize(value: object, depth: int = 0) -> Any:
    if depth > 5:
        return "[truncated]"
    if isinstance(value, Mapping):
        out: dict[str, Any] = {}
        for key, item in value.items():
            name = _clean_text(key, 80)
            if _sensitive_key(name):
                out[name] = "[redacted]"
            else:
                out[name] = _sanitize(item, depth + 1)
        return out
    if isinstance(value, (list, tuple)):
        return [_sanitize(item, depth + 1) for item in list(value)[:50]]
    if isinstance(value, str):
        return _clean_text(value, MAX_STRING)
    if isinstance(value, (int, float, bool)) or value is None:
        return value
    return _clean_text(value, MAX_STRING)


def _canonical(payload: object, *, kind: str = "", tool: str | None = None) -> str:
    cleaned = _sanitize(payload if payload is not None else {})
    if not isinstance(cleaned, dict):
        cleaned = {"value": cleaned}
    # kind/tool 也进哈希覆盖的 payload；展示列只是投影。
    cleaned["_kind"] = kind
    cleaned["_tool"] = tool
    text = json.dumps(cleaned, ensure_ascii=False, sort_keys=True,
                      separators=(",", ":"), default=str)
    if len(text.encode("utf-8")) > MAX_PAYLOAD:
        cleaned = {
            "truncated": True,
            "bytes": len(text.encode("utf-8")),
            "sha256": hashlib.sha256(text.encode("utf-8")).hexdigest(),
            "_kind": kind,
            "_tool": tool,
        }
        text = json.dumps(cleaned, ensure_ascii=False, sort_keys=True,
                          separators=(",", ":"))
    return text


def _hash(prev: str, actor: str, action: str, payload_json: str,
          result: str, created_at: int) -> str:
    material = (prev or "") + actor + action + payload_json + result + str(
        int(created_at))
    return hashlib.sha256(material.encode("utf-8")).hexdigest()


def _get_setting(con: sqlite3.Connection, key: str):
    row = con.execute("SELECT value FROM sync_state WHERE key=?",
                      (key,)).fetchone()
    return None if row is None else row["value"]


def _set_setting(con: sqlite3.Connection, key: str, value: str) -> None:
    con.execute(
        "INSERT INTO sync_state(key, value) VALUES(?,?) "
        "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
        (key, value),
    )


def _load_head(con: sqlite3.Connection) -> dict | None:
    raw = _get_setting(con, HEAD_KEY)
    if raw is None:
        return None
    try:
        head = json.loads(raw)
    except (TypeError, ValueError) as exc:
        raise AuditError("audit head is corrupt") from exc
    if (not isinstance(head, dict) or not isinstance(head.get("hash"), str)
            or isinstance(head.get("id"), bool)
            or not isinstance(head.get("id"), int)
            or isinstance(head.get("count"), bool)
            or not isinstance(head.get("count"), int)):
        raise AuditError("audit head is corrupt")
    return head


def _save_head(con: sqlite3.Connection, head: Mapping[str, object]) -> None:
    _set_setting(con, HEAD_KEY, json.dumps(
        {"id": int(head["id"]), "hash": str(head["hash"]),
         "count": int(head["count"])}, sort_keys=True, separators=(",", ":")))


def _load_anchor(con: sqlite3.Connection) -> dict | None:
    raw = _get_setting(con, ANCHOR_KEY)
    if raw is None:
        return None
    try:
        anchor = json.loads(raw)
    except (TypeError, ValueError) as exc:
        raise AuditError("audit anchor is corrupt") from exc
    if (not isinstance(anchor, dict) or not isinstance(anchor.get("hash"), str)
            or isinstance(anchor.get("id"), bool)
            or not isinstance(anchor.get("id"), int)):
        raise AuditError("audit anchor is corrupt")
    return anchor


def _begin_write(con: sqlite3.Connection) -> bool:
    """没有事务时开 ``BEGIN IMMEDIATE``；返回是否由本函数开启。"""
    if con.in_transaction:
        return False
    con.execute("BEGIN IMMEDIATE")
    return True


def _rollback(con: sqlite3.Connection) -> None:
    try:
        con.rollback()
    except Exception:  # noqa: BLE001
        pass


def _rollback_savepoint(con: sqlite3.Connection, name: str) -> None:
    try:
        con.execute(f"ROLLBACK TO {name}")
        con.execute(f"RELEASE {name}")
    except Exception:  # noqa: BLE001
        pass


def append(con: sqlite3.Connection, kind: str, actor: str, action: str,
           payload: object = None, result: str = "ok", *,
           tool: str | None = None, created_at: object = None) -> dict:
    """追加一条审计并推进链头；返回 ``id`` / ``hash`` / ``prev_hash``。

    调用方已经在事务里时沿用该事务（与业务动作一起提交/回滚）。否则用
    ``BEGIN IMMEDIATE`` 串行化并立即提交。链头缺失或与末行不一致时抛
    :class:`AuditError`，绝不接上断链。
    """
    ts = int(time.time() if created_at is None else created_at)
    actor = _clean_text(actor, MAX_ACTOR) or "unknown"
    action = _clean_text(action, MAX_ACTION) or "unknown"
    kind = _clean_text(kind, 40) or "system"
    result = _clean_text(result, 40) or "ok"
    tool_name = None if tool is None else _clean_text(tool, MAX_ACTION)
    payload_json = _canonical(payload, kind=kind, tool=tool_name)

    begun = _begin_write(con)
    savepoint = "audit_append"
    try:
        con.execute(f"SAVEPOINT {savepoint}")
        head = _load_head(con)
        last = con.execute(
            "SELECT id, hash FROM access_log WHERE hash IS NOT NULL "
            "ORDER BY id DESC LIMIT 1"
        ).fetchone()
        legacy_start = False
        if head is None:
            if last is not None:
                raise AuditError("audit head missing but rows exist")
            prev = ""
            count = 0
            legacy_start = con.execute(
                "SELECT COUNT(*) FROM access_log").fetchone()[0] > 0
        else:
            if (last is None or last["hash"] != head["hash"]
                    or int(last["id"]) != int(head["id"])):
                raise AuditError("audit head does not match chain tail")
            prev = head["hash"]
            count = int(head["count"])
            # 末行内容被改过时 head 仍会指向旧 hash；重算末行可发现并拒绝接链。
            tail = con.execute(
                "SELECT id, actor, action, detail, result, prev_hash, hash, "
                "created_at FROM access_log WHERE hash IS NOT NULL "
                "ORDER BY id DESC LIMIT 1"
            ).fetchone()
            before = con.execute(
                "SELECT hash FROM access_log WHERE hash IS NOT NULL AND id < ? "
                "ORDER BY id DESC LIMIT 1", (tail["id"],)).fetchone()
            if before is None:
                anchor = _load_anchor(con)
                expected_prev = ""
                if (anchor is not None
                        and int(anchor["id"]) == int(tail["id"])
                        and anchor["hash"] == tail["hash"]):
                    expected_prev = tail["prev_hash"] or ""
            else:
                expected_prev = before["hash"]
            tail_payload = tail["detail"] if tail["detail"] is not None else ""
            if ((tail["prev_hash"] or "") != expected_prev
                    or _hash(expected_prev, tail["actor"], tail["action"],
                             tail_payload, tail["result"],
                             int(tail["created_at"])) != tail["hash"]):
                raise AuditError("audit tail hash mismatch")
        digest = _hash(prev, actor, action, payload_json, result, ts)
        cur = con.execute(
            "INSERT INTO access_log(actor, kind, action, tool, result, detail, "
            "prev_hash, hash, created_at) VALUES(?,?,?,?,?,?,?,?,?)",
            (actor, kind, action, tool_name, result, payload_json,
             prev or None, digest, ts),
        )
        row_id = int(cur.lastrowid)
        _save_head(con, {"id": row_id, "hash": digest, "count": count + 1})
        if legacy_start:
            # 旧版本留下的是无哈希行：不重封它们，从第一条新行开始建锚点。
            _set_setting(con, ANCHOR_KEY, json.dumps(
                {"id": row_id, "hash": digest}, sort_keys=True,
                separators=(",", ":")))
        con.execute(f"RELEASE {savepoint}")
        if begun:
            con.commit()
        return {"id": row_id, "hash": digest, "prev_hash": prev or None,
                "created_at": ts}
    except BaseException:
        # 只回滚审计自己的插入/链头写入；调用方事务里的业务动作保持可提交。
        _rollback_savepoint(con, savepoint)
        if begun:
            _rollback(con)
        raise


def _fail(message: str, first_bad_id: int | None = None,
          rows: int = 0, head: str | None = None) -> dict:
    return {"ok": False, "rows": rows, "head": head, "error": message,
            "first_bad_id": first_bad_id}


def verify(con: sqlite3.Connection) -> dict:
    """在一致快照里校验整条链（head/anchor/rows 三次读取不会跨事务）。

    没有外层事务时用 ``BEGIN`` 打开读事务，读完提交；已有事务则直接复用。
    """
    begun = False
    if not con.in_transaction:
        con.execute("BEGIN")
        begun = True
    try:
        result = _verify_body(con)
        if begun:
            con.commit()
        return result
    except BaseException:
        if begun:
            _rollback(con)
        raise


def _verify_body(con: sqlite3.Connection) -> dict:
    """从锚点（或创世行）走到链头，返回 ``{ok, rows, head, error, ...}``。

    检测：行内容/前后链接被改、id 断档、锚点缺失、链头缺失或与末行不一致。
    保留清理后第一行是锚点，它的 ``prev_hash`` 指向已删除行，因此只信任
    锚点自己的 ``prev_hash`` 作为起点。
    """
    try:
        head = _load_head(con)
        anchor = _load_anchor(con)
    except AuditError as exc:
        return _fail(str(exc))
    rows = con.execute(
        "SELECT id, actor, kind, action, tool, detail, result, prev_hash, hash, "
        "created_at FROM access_log WHERE hash IS NOT NULL ORDER BY id"
    ).fetchall()
    if not rows:
        if head is not None and int(head["count"]) != 0:
            return _fail("audit head exists but no rows")
        return {"ok": True, "rows": 0, "head": None if head is None
                else head["hash"], "error": None, "first_bad_id": None}
    stray = con.execute(
        "SELECT id FROM access_log WHERE hash IS NULL AND id > ? "
        "ORDER BY id LIMIT 1", (int(rows[-1]["id"]),)).fetchone()
    if stray is not None:
        return _fail("unhashed audit row after chain",
                     first_bad_id=int(stray["id"]), rows=len(rows),
                     head=head["hash"] if head else None)
    if head is None:
        return _fail("audit head missing but rows exist",
                     first_bad_id=int(rows[0]["id"]), rows=len(rows))
    first = rows[0]
    if anchor is not None:
        if (int(first["id"]) != int(anchor["id"])
                or first["hash"] != anchor["hash"]):
            return _fail("audit anchor does not match first row",
                         first_bad_id=int(first["id"]), rows=len(rows),
                         head=head["hash"])
        prev = first["prev_hash"] or ""
    else:
        if first["prev_hash"] not in (None, ""):
            return _fail("missing retention anchor",
                         first_bad_id=int(first["id"]), rows=len(rows),
                         head=head["hash"])
        prev = ""
    expected_id = int(first["id"])
    for index, row in enumerate(rows):
        if int(row["id"]) != expected_id:
            return _fail("gap in audit ids", first_bad_id=expected_id,
                         rows=len(rows), head=head["hash"])
        expected_id += 1
        if index > 0 and (row["prev_hash"] or "") != prev:
            return _fail("audit row prev_hash mismatch",
                         first_bad_id=int(row["id"]), rows=len(rows),
                         head=head["hash"])
        payload_json = row["detail"] if row["detail"] is not None else ""
        digest = _hash(prev, row["actor"], row["action"], payload_json,
                       row["result"], int(row["created_at"]))
        if digest != row["hash"]:
            return _fail("audit row hash mismatch",
                         first_bad_id=int(row["id"]), rows=len(rows),
                         head=head["hash"])
        # 新行把 kind/tool 也写进 payload；展示列被单独改动时在这里发现。
        # 旧行（本字段加入前）payload 没有这两个键，只能按遗留行处理。
        try:
            hashed_payload = json.loads(payload_json or "{}")
        except (TypeError, ValueError):
            hashed_payload = None
        if isinstance(hashed_payload, dict) and (
                "_kind" in hashed_payload or "_tool" in hashed_payload):
            if (hashed_payload.get("_kind") != row["kind"]
                    or hashed_payload.get("_tool") != row["tool"]):
                return _fail("audit kind/tool mismatch",
                             first_bad_id=int(row["id"]), rows=len(rows),
                             head=head["hash"])
        prev = digest
    last = rows[-1]
    if (last["hash"] != head["hash"] or int(last["id"]) != int(head["id"])
            or int(head["count"]) != len(rows)):
        return _fail("audit head does not match chain tail",
                     first_bad_id=int(last["id"]), rows=len(rows),
                     head=head["hash"])
    return {"ok": True, "rows": len(rows), "head": last["hash"],
            "error": None, "first_bad_id": None}


def _prefix_before_cutoff(con: sqlite3.Connection, cutoff: int) -> list:
    rows = con.execute(
        "SELECT id, hash, created_at FROM access_log WHERE hash IS NOT NULL "
        "ORDER BY id").fetchall()
    for index, row in enumerate(rows):
        if int(row["created_at"]) >= cutoff:
            return rows[:index]
    return rows


def prune(con: sqlite3.Connection, days: object = None, *,
          now: object = None) -> dict:
    """删除 90 天前的旧行，保留最后一条旧行作为锚点并写 ``audit.retention``。

    只删除 ``created_at < cutoff`` 的连续前缀；不碰比 cutoff 新的行。删除、
    锚点、链头计数和保留事件在同一事务里完成，提交前后各校验一次。
    ``count`` 是实际删除行数（含无哈希旧行）。若调用方已有事务，返回前的
    校验发生在调用方提交之前；调用方提交后应再跑一次 :func:`verify`。
    """
    if days is None:
        days = AUDIT_RETENTION_DAYS
    try:
        days = int(days)
    except (TypeError, ValueError) as exc:
        raise ValueError("days must be a positive integer") from exc
    if days <= 0:
        raise ValueError("days must be a positive integer")
    ts = int(time.time() if now is None else now)
    cutoff = ts - days * 86400

    begun = _begin_write(con)
    try:
        old = _prefix_before_cutoff(con, cutoff)
        if len(old) < 2:
            if begun:
                con.commit()
            return {"deleted": 0, "cutoff": cutoff, "days": days,
                    "anchor": None, "verified": True}
        anchor = old[-1]
        delete_ids = [int(row["id"]) for row in old[:-1]]
        deleted_cur = con.execute(
            "DELETE FROM access_log WHERE id <= ?", (delete_ids[-1],))
        deleted = max(0, int(deleted_cur.rowcount))
        remaining = int(con.execute(
            "SELECT COUNT(*) FROM access_log WHERE hash IS NOT NULL"
        ).fetchone()[0])
        head = _load_head(con)
        if head is not None:
            _save_head(con, {"id": head["id"], "hash": head["hash"],
                             "count": remaining})
        _set_setting(con, ANCHOR_KEY, json.dumps(
            {"id": int(anchor["id"]), "hash": anchor["hash"]},
            sort_keys=True, separators=(",", ":")))
        append(con, "system", "system", "audit.retention", {
            "cutoff": cutoff,
            "days": days,
            "count": deleted,
            "anchor": {"id": int(anchor["id"]), "hash": anchor["hash"]},
        }, "ok", created_at=ts)
        check = verify(con)
        if not check["ok"]:
            raise AuditError("audit verify failed after prune: "
                             + str(check.get("error")))
        if begun:
            con.commit()
    except BaseException:
        if begun:
            _rollback(con)
        raise
    after = verify(con)
    if not after["ok"]:
        raise AuditError("audit verify failed after prune: "
                         + str(after.get("error")))
    return {"deleted": deleted, "cutoff": cutoff, "days": days,
            "anchor": {"id": int(anchor["id"]), "hash": anchor["hash"]},
            "verified": True, "remaining": after["rows"]}


def cleanup(con: sqlite3.Connection, days: object = None, *,
            now: object = None) -> dict:
    """后台清理入口；``days`` 未给时读 ``AUDIT_RETENTION_DAYS``（默认 90）。"""
    if days is None:
        raw = os.environ.get("AUDIT_RETENTION_DAYS")
        if raw in (None, ""):
            days = AUDIT_RETENTION_DAYS
        else:
            try:
                days = int(raw)
            except (TypeError, ValueError):
                days = AUDIT_RETENTION_DAYS
    return prune(con, days, now=now)


def list_recent(con: sqlite3.Connection, limit: object = 200, *,
                actor: str | None = None, action: str | None = None,
                result: str | None = None) -> list[dict]:
    """最近的审计行（新的在前），供 ``/admin/audit`` 只读展示。"""
    try:
        limit = max(1, min(int(limit), 1000))
    except (TypeError, ValueError):
        limit = 200
    clauses: list[str] = []
    params: list[object] = []
    for column, value in (("actor", actor), ("action", action),
                          ("result", result)):
        if value:
            clauses.append(f"{column}=?")
            params.append(str(value)[:120])
    sql = ("SELECT id, actor, kind, action, tool, result, detail, "
           "created_at, hash FROM access_log")
    if clauses:
        sql += " WHERE " + " AND ".join(clauses)
    sql += " ORDER BY id DESC LIMIT ?"
    params.append(limit)
    return [dict(row) for row in con.execute(sql, params).fetchall()]


# ------------------------------------------------------------------ 适配器
def audit_event(con: sqlite3.Connection, action: str, *,
                actor: str = "admin", kind: str = "admin",
                payload: object = None, result: str = "ok",
                detail: object = None, tool: str | None = None,
                created_at: object = None) -> dict:
    """后台钩子用的宽松接口；``detail`` 只放 id/用户名/标签。"""
    if payload is None and detail is not None:
        payload = {"detail": detail}
    elif payload is not None and detail is not None:
        # 不使用 "data"：它命中健康数据脱敏名单，会把整个 payload 丢掉。
        payload = {"detail": detail, "extra": payload}
    return append(con, kind, actor, action, payload, result, tool=tool,
                  created_at=created_at)


def record_admin_action(con: sqlite3.Connection, action: str, *,
                        actor: str = "admin", result: str = "ok",
                        detail: object = None) -> dict:
    """严格匹配 ``admin.record_admin_action`` 的调用签名。"""
    return audit_event(con, action, actor=actor, kind="admin",
                       result=result, detail=detail)


def write(con: sqlite3.Connection, actor: str, kind: str, action: str,
          tool: str | None = None, result: str = "ok",
          detail: object = None, *, payload: object = None) -> dict:
    """旧计划里的 ``write(con, actor, kind, action, tool, result, detail)``。"""
    if payload is None and detail is not None:
        payload = {"detail": detail}
    return append(con, kind, actor, action, payload, result, tool=tool)
