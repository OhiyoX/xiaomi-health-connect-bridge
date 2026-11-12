"""管理页的只读聚合（标准库；60 秒 TTL 缓存；空库安全）。

设计：``docs/superpowers/specs/2026-11-03-admin-ui-redesign.md`` §4。

- 只发 ``SELECT``；GET 页面调用本模块不会写入任何表。
- 每类结果在进程内缓存 :data:`CACHE_TTL` 秒，键含数据库文件路径 + 参数；
  1.2M 行的聚合不会因为刷新页面被反复全表扫描。
- 缺表、空库、坏 JSON 一律返回空结构，页面不会 500。
- 展示时区固定 UTC+8，与 ``server.py`` 的 ``TZ_CN`` 一致（容器时区是 UTC）。

性能（``EXPLAIN QUERY PLAN`` 复核，见 ``tests/test_stats.py``）：

- ``COUNT(*)`` / ``GROUP BY source``：全索引扫描（``SCAN`` + ``USING COVERING INDEX``），
  与表大小线性相关，靠缓存摊薄。
- ``days30`` 与序列：``SEARCH ... USING COVERING INDEX``，只触达窗口内的行。
"""
from __future__ import annotations

import copy
import json
import sqlite3
import threading
import time
from datetime import datetime, timedelta, timezone

TZ = timezone(timedelta(hours=8))
"""展示/分桶时区（固定 UTC+8）。"""

CACHE_TTL = 60.0
"""聚合结果在进程内的有效期（秒）。"""

MAX_CACHE = 256
_TIMESTAMP_FMT = "%Y-%m-%d %H:%M"

#: 业务表 → (中文名, 时间列, 数值列)。顺序即页面顺序。
METRICS: tuple[tuple[str, str, str, str], ...] = (
    ("heart_rate", "心率", "ts", "bpm"),
    ("spo2", "血氧", "ts", "pct"),
    ("steps", "步数", "ts", "steps"),
    ("sleep", "睡眠", "bed", "minutes"),
    ("calories", "活动能量", "ts", "kcal"),
    ("stress", "压力", "ts", "stress"),
    ("weight", "体重", "ts", "kg"),
    ("workout", "运动", "start", "minutes"),
)

METRIC_LABELS = {name: label for name, label, _c, _v in METRICS}
METRIC_COLUMNS = {name: (column, value) for name, _l, column, value in METRICS}

SOURCE_LABELS = {
    "xiaomi-cloud": "小米云",
    "phone": "手机",
    "manual": "手动",
    "hc-bridge": "桥",
    "google-fit": "Google Fit",
    "huawei": "华为健康",
    "apple": "Apple 健康",
}

#: metric → (表, 中文名, 单位, SQL 聚合, 时间列)
_SERIES_SPECS: dict[str, tuple[str, str, str, str, str]] = {
    "steps": ("steps", "步数", "步", "COALESCE(SUM(steps), 0)", "ts"),
    "heart_rate": ("heart_rate", "心率", "bpm", "AVG(bpm)", "ts"),
    "sleep": ("sleep", "睡眠", "小时", "SUM(minutes) / 60.0", "bed"),
}

SERIES_METRICS = tuple(_SERIES_SPECS)

#: 睡眠标记 → 展示文案
SLEEP_FLAGS = {
    "missing": "缺口",
    "short": "短睡",
    "long": "偏长",
    "late": "晚睡",
    "fragile": "碎片",
    "invalid": "无效",
}

_CACHE_LOCK = threading.Lock()
_CACHE: dict[tuple, tuple[float, object]] = {}


# ------------------------------------------------------------------ 格式化
def fmt_ts(value: object) -> str:
    """UTC+8 的 ``YYYY-MM-DD HH:MM``；无效值 → ``—``。"""
    stamp = _as_int(value)
    if stamp is None:
        return "—"
    try:
        return datetime.fromtimestamp(stamp, TZ).strftime(_TIMESTAMP_FMT)
    except (OverflowError, OSError, ValueError):
        return "—"


def fmt_date(value: object) -> str:
    stamp = _as_int(value)
    if stamp is None:
        return "—"
    try:
        return datetime.fromtimestamp(stamp, TZ).strftime("%Y-%m-%d")
    except (OverflowError, OSError, ValueError):
        return "—"


def fmt_hhmm(value: object) -> str:
    stamp = _as_int(value)
    if stamp is None:
        return "—"
    try:
        return datetime.fromtimestamp(stamp, TZ).strftime("%H:%M")
    except (OverflowError, OSError, ValueError):
        return "—"


def rel(value: object, *, now: int | None = None) -> str:
    """相对时间（``3 分钟前`` / ``2 小时后``）；无效值 → ``—``。"""
    stamp = _as_int(value)
    if stamp is None:
        return "—"
    current = int(time.time()) if now is None else int(now)
    delta = stamp - current
    if abs(delta) < 60:
        return "刚刚" if delta <= 0 else "1 分钟后"
    minutes = abs(delta) // 60
    if minutes < 60:
        text = f"{minutes} 分钟"
    elif minutes < 1440:
        text = f"{minutes // 60} 小时"
    elif minutes < 43200:
        text = f"{minutes // 1440} 天"
    else:
        return fmt_date(stamp)
    return text + ("后" if delta > 0 else "前")


def num(value: object) -> str:
    """千分位整数；无效/``None`` → ``—``。"""
    if value is None:
        return "—"
    number = _as_int(value)
    if number is None:
        try:
            return f"{float(value):,.2f}"
        except (TypeError, ValueError):
            return "—"
    return f"{number:,}"


def _as_int(value: object) -> int | None:
    if value is None or value == "":
        return None
    if isinstance(value, bool):
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def day_start(ts: object) -> int | None:
    """UTC+8 当天 00:00 的时间戳。"""
    stamp = _as_int(ts)
    if stamp is None:
        return None
    offset = int(TZ.utcoffset(None).total_seconds())
    shifted = stamp + offset
    return shifted - (shifted % 86400) - offset


def window_start(days: int, *, now: int | None = None) -> int:
    """最近 ``days`` 个自然日（含今天）的起点。"""
    current = int(time.time()) if now is None else int(now)
    start = day_start(current) or 0
    return start - (max(1, int(days)) - 1) * 86400


def _clamp_days(value: object, default: int = 30) -> int:
    number = _as_int(value)
    if number is None or number <= 0:
        return default
    return min(number, 366)


# ------------------------------------------------------------------ 缓存
def clear_cache() -> None:
    """丢掉全部缓存（测试或数据刚变化时用）。"""
    with _CACHE_LOCK:
        _CACHE.clear()


def cache_size() -> int:
    with _CACHE_LOCK:
        return len(_CACHE)


def _db_name(con: sqlite3.Connection) -> str:
    try:
        for row in con.execute("PRAGMA database_list").fetchall():
            if str(row[1]) == "main":
                return str(row[2] or ":memory:")
    except sqlite3.Error:
        pass
    return ""


def _cached(con: sqlite3.Connection, name: str, args: tuple, builder):
    key = (_db_name(con), name, args)
    now = time.monotonic()
    with _CACHE_LOCK:
        hit = _CACHE.get(key)
        if hit is not None and hit[0] > now:
            return copy.deepcopy(hit[1])
    value = builder()
    with _CACHE_LOCK:
        if len(_CACHE) >= MAX_CACHE:
            _CACHE.clear()
        _CACHE[key] = (now + CACHE_TTL, value)
    return copy.deepcopy(value)


# ------------------------------------------------------------------ 覆盖
def coverage(con: sqlite3.Connection, days: int = 30) -> list[dict]:
    """每种指标的行数/来源/有数天数/最近写入。

    返回 ``[{type, label, rows, sources, source_rows, days30, pct, last}]``；
    表不存在的类型不出现。``pct`` 是 ``days30 / days``（0–1）。
    """
    days = _clamp_days(days)
    return _cached(con, "coverage", (days,), lambda: _coverage(con, days))


def _coverage(con: sqlite3.Connection, days: int) -> list[dict]:
    out: list[dict] = []
    start = window_start(days)
    for table, label, column, _value in METRICS:
        item = _coverage_row(con, table, label, column, start, days)
        if item is not None:
            out.append(item)
    return out


def _coverage_row(con: sqlite3.Connection, table: str, label: str,
                  column: str, start: int, days: int) -> dict | None:
    if not _has_table(con, table):
        return None
    try:
        total = 0
        last: int | None = None
        sources: list[dict] = []
        for row in con.execute(
                f"SELECT source, COUNT(*) AS n, MIN({column}) AS lo, "
                f"MAX({column}) AS hi FROM {table} GROUP BY source"):
            count = int(row["n"] or 0)
            if count <= 0:
                continue
            total += count
            name = str(row["source"] or "?")
            sources.append({"source": name,
                            "label": SOURCE_LABELS.get(name, name),
                            "rows": count})
            hi = _as_int(row["hi"])
            if hi is not None and (last is None or hi > last):
                last = hi
        days_with = _days_with_data(con, table, column, start) if total else 0
        days_with = max(0, min(days_with, days))
        sources.sort(key=lambda item: -item["rows"])
        return {
            "type": table,
            "label": label,
            "rows": total,
            "sources": [item["source"] for item in sources],
            "source_rows": sources,
            "days30": days_with,
            "pct": (days_with / days) if days else 0.0,
            "last": last,
        }
    except sqlite3.Error:
        return None


def _days_with_data(con: sqlite3.Connection, table: str, column: str,
                    start: int) -> int:
    row = con.execute(
        f"SELECT COUNT(DISTINCT date({column}, 'unixepoch', '+8 hours')) "
        f"FROM {table} WHERE {column} >= ?", (int(start),)).fetchone()
    return int(row[0] or 0) if row is not None else 0


def rows(con: sqlite3.Connection) -> dict[str, int]:
    """每种指标的总行数（缺表不出现）。"""
    return _cached(con, "rows", (), lambda: _rows(con))


def _rows(con: sqlite3.Connection) -> dict[str, int]:
    out: dict[str, int] = {}
    for table, _label, _column, _value in METRICS:
        if not _has_table(con, table):
            continue
        try:
            row = con.execute(f"SELECT COUNT(*) FROM {table}").fetchone()
        except sqlite3.Error:
            continue
        out[table] = int(row[0] or 0) if row is not None else 0
    return out


def sources(con: sqlite3.Connection, table: str) -> list[dict]:
    """单表按来源的行数。``table`` 必须在 :data:`METRICS` 里。"""
    if table not in METRIC_COLUMNS:
        return []
    return _cached(con, "sources", (table,), lambda: _sources(con, table))


def _sources(con: sqlite3.Connection, table: str) -> list[dict]:
    if not _has_table(con, table):
        return []
    try:
        found = [{"source": str(row["source"] or "?"),
                  "label": SOURCE_LABELS.get(str(row["source"] or "?"),
                                             str(row["source"] or "?")),
                  "rows": int(row["n"] or 0)}
                 for row in con.execute(
                     f"SELECT source, COUNT(*) AS n FROM {table} "
                     "GROUP BY source ORDER BY n DESC")]
    except sqlite3.Error:
        return []
    return found


def recent(con: sqlite3.Connection, table: str, limit: int = 10) -> list[dict]:
    """最近的若干行（``LIMIT`` 有界，走主键索引倒序）。"""
    spec = METRIC_COLUMNS.get(table)
    if spec is None:
        return []
    column, value_column = spec
    limit = max(1, min(_as_int(limit) or 10, 50))
    if not _has_table(con, table):
        return []
    try:
        rows_out = []
        for row in con.execute(
                f"SELECT {column} AS ts, {value_column} AS value "
                f"FROM {table} ORDER BY {column} DESC LIMIT ?", (limit,)):
            rows_out.append({"ts": _as_int(row["ts"]), "value": row["value"]})
        return rows_out
    except sqlite3.Error:
        return []


# ------------------------------------------------------------------ 序列
def series(con: sqlite3.Connection, metric: str, days: int = 14) -> dict:
    """步数/心率/睡眠的日聚合；缺数据的日 ``value`` 为 ``None``。"""
    days = _clamp_days(days, 14)
    if metric not in _SERIES_SPECS:
        return _empty_series(metric, days, axis=False)
    return _cached(con, "series", (metric, days),
                   lambda: _series(con, metric, days))


def _empty_series(metric: str, days: int, *, axis: bool = True) -> dict:
    """空结果；``axis`` 为真时仍给出 ``days`` 个等距日期（值全 ``None``）。"""
    label = METRIC_LABELS.get(metric, metric)
    points: list[dict] = []
    if axis:
        start = window_start(days)
        points = [{"date": fmt_date(start + index * 86400), "value": None}
                  for index in range(days)]
    return {"metric": metric, "label": label, "unit": "", "days": days,
            "points": points, "values": [None] * len(points),
            "latest": None, "latest_date": None,
            "avg": None, "total": None, "max": None, "count": 0,
            "has_data": False}


def _series(con: sqlite3.Connection, metric: str, days: int) -> dict:
    table, label, unit, agg, column = _SERIES_SPECS[metric]
    result = _empty_series(metric, days)
    result["unit"] = unit
    if not _has_table(con, table):
        return result
    start = window_start(days)
    try:
        found: dict[str, float] = {}
        for row in con.execute(
                f"SELECT date({column}, 'unixepoch', '+8 hours') AS d, "
                f"{agg} AS v FROM {table} WHERE {column} >= ? "
                "GROUP BY d ORDER BY d", (start,)):
            value = row["v"]
            if value is None:
                continue
            found[str(row["d"])] = float(value)
    except sqlite3.Error:
        return result
    points = []
    for index in range(days):
        stamp = start + index * 86400
        key = fmt_date(stamp)
        points.append({"date": key, "value": found.get(key)})
    values = [point["value"] for point in points]
    known = [value for value in values if value is not None]
    latest = known[-1] if known else None
    latest_date = None
    for point in reversed(points):
        if point["value"] is not None:
            latest_date = point["date"]
            break
    result.update({
        "points": points,
        "values": values,
        "latest": latest,
        "latest_date": latest_date,
        "avg": (sum(known) / len(known)) if known else None,
        "total": sum(known) if known else None,
        "max": max(known) if known else None,
        "count": len(known),
        "has_data": bool(known),
    })
    return result


def daily_counts(con: sqlite3.Connection, table: str, days: int = 30) -> dict:
    """任意指标每天的行数（缺口一眼可见）。"""
    days = _clamp_days(days)
    if table not in METRIC_COLUMNS:
        return _empty_series(table, days, axis=False)
    return _cached(con, "daily_counts", (table, days),
                   lambda: _daily_counts(con, table, days))


def _daily_counts(con: sqlite3.Connection, table: str, days: int) -> dict:
    column = METRIC_COLUMNS[table][0]
    result = _empty_series(table, days)
    result["unit"] = "行"
    if not _has_table(con, table):
        return result
    start = window_start(days)
    try:
        found: dict[str, float] = {}
        for row in con.execute(
                f"SELECT date({column}, 'unixepoch', '+8 hours') AS d, "
                f"COUNT(*) AS n FROM {table} WHERE {column} >= ? "
                "GROUP BY d ORDER BY d", (start,)):
            found[str(row["d"])] = float(row["n"] or 0)
    except sqlite3.Error:
        return result
    points = []
    for index in range(days):
        key = fmt_date(start + index * 86400)
        points.append({"date": key, "value": found.get(key)})
    values = [point["value"] for point in points]
    known = [value for value in values if value is not None]
    result.update({
        "points": points,
        "values": values,
        "latest": known[-1] if known else None,
        "avg": (sum(known) / len(known)) if known else None,
        "total": sum(known) if known else None,
        "max": max(known) if known else None,
        "count": len(known),
        "has_data": bool(known),
    })
    return result


# ------------------------------------------------------------------ 睡眠
def sleep_days(con: sqlite3.Connection, days: int = 30) -> list[dict]:
    """逐日睡眠体检：缺口/短睡/偏长/晚睡/碎片标记。新的在前。"""
    days = _clamp_days(days)
    return _cached(con, "sleep", (days,), lambda: _sleep_days(con, days))


def _sleep_days(con: sqlite3.Connection, days: int) -> list[dict]:
    start = window_start(days)
    if not _has_table(con, "sleep"):
        return []
    found: dict[str, dict] = {}
    try:
        for row in con.execute(
                "SELECT bed, wake, minutes, deep_min, source FROM sleep "
                "WHERE bed >= ? ORDER BY bed DESC", (start,)):
            key = fmt_date(row["bed"])
            candidate = {
                "bed": _as_int(row["bed"]),
                "wake": _as_int(row["wake"]),
                "minutes": _as_int(row["minutes"]),
                "deep_min": _as_int(row["deep_min"]),
                "source": str(row["source"] or "?"),
            }
            previous = found.get(key)
            if previous is None or _sleep_span(candidate) > _sleep_span(previous):
                found[key] = candidate
    except sqlite3.Error:
        return []
    out = []
    for index in range(days - 1, -1, -1):
        date = fmt_date(start + index * 86400)
        out.append(_sleep_row(date, found.get(date)))
    return out


def _sleep_span(item: dict) -> int:
    minutes = item.get("minutes")
    if isinstance(minutes, int) and minutes > 0:
        return minutes
    bed, wake = item.get("bed"), item.get("wake")
    if isinstance(bed, int) and isinstance(wake, int):
        return max(0, wake - bed)
    return 0


def _sleep_row(date: str, item: dict | None) -> dict:
    if item is None:
        return {"date": date, "bed": "—", "wake": "—", "hours": None,
                "score": None, "source": "—", "flags": ["missing"],
                "flag_labels": [SLEEP_FLAGS["missing"]], "note": "当天没有睡眠记录",
                "bed_ts": None, "wake_ts": None}
    bed, wake = item["bed"], item["wake"]
    minutes = item["minutes"] if isinstance(item["minutes"], int) else None
    if (minutes is None or minutes <= 0) and isinstance(bed, int) \
            and isinstance(wake, int) and wake > bed:
        minutes = (wake - bed) // 60
    hours = round(minutes / 60.0, 2) if minutes and minutes > 0 else None
    flags: list[str] = []
    notes: list[str] = []
    if hours is None or (isinstance(wake, int) and isinstance(bed, int)
                         and wake <= bed):
        flags.append("invalid")
        notes.append("起止时间异常")
    else:
        if hours < 6:
            flags.append("short")
            notes.append(f"少 {round(6 - hours, 1)} 小时")
        elif hours > 11:
            flags.append("long")
            notes.append("时长偏长")
        if isinstance(bed, int):
            hour = datetime.fromtimestamp(bed, TZ).hour
            if 1 <= hour <= 11:
                flags.append("late")
                notes.append(f"入睡 {fmt_hhmm(bed)}")
        deep = item["deep_min"]
        if isinstance(deep, int) and minutes and deep >= 0 \
                and deep / minutes < 0.12:
            flags.append("fragile")
            notes.append(f"深睡仅 {deep} 分钟")
    return {
        "date": date,
        "bed": fmt_hhmm(bed),
        "wake": fmt_hhmm(wake),
        "hours": hours,
        "score": None,
        "source": item["source"],
        "flags": flags,
        "flag_labels": [SLEEP_FLAGS[name] for name in flags],
        "note": "；".join(notes) if notes else "正常",
        "bed_ts": bed,
        "wake_ts": wake,
    }


# ------------------------------------------------------------------ 同步
def sync_summary(con: sqlite3.Connection, interval: int | None = None) -> dict:
    """同步/回填/手机推送状态。读不到的部分一律给出空值。"""
    key = _as_int(interval) or 0
    return _cached(con, "sync", (key,), lambda: _sync_summary(con, key or None))


def _state(con: sqlite3.Connection, key: str) -> str | None:
    try:
        row = con.execute("SELECT value FROM sync_state WHERE key=?",
                          (key,)).fetchone()
    except sqlite3.Error:
        return None
    if row is None:
        return None
    value = row[0]
    return None if value is None else str(value)


def _json_dict(text: str | None) -> dict:
    if not text:
        return {}
    try:
        parsed = json.loads(text)
    except (json.JSONDecodeError, TypeError, ValueError):
        return {}
    return parsed if isinstance(parsed, dict) else {}


def _parse_stamp(value: object) -> int | None:
    """``sync_state`` 里的时间可能是 epoch，也可能是 ``YYYY-MM-DD HH:MM:SS``。"""
    stamp = _as_int(value)
    if stamp is not None:
        return stamp
    if isinstance(value, str) and value.strip():
        try:
            return int(datetime.strptime(value.strip(), "%Y-%m-%d %H:%M:%S")
                       .replace(tzinfo=TZ).timestamp())
        except ValueError:
            return None
    return None


def _sync_summary(con: sqlite3.Connection, interval: int | None) -> dict:
    now = int(time.time())
    last_sync = _parse_stamp(_state(con, "last_sync"))
    last_stats = _json_dict(_state(con, "last_sync_stats"))
    ingest_raw = _json_dict(_state(con, "last_ingest"))
    backfill_raw = _json_dict(_state(con, "backfill_state"))
    override = _as_int(_state(con, "fetch_interval_override"))
    effective = interval or override or 900

    last_rows = 0
    for name, value in last_stats.items():
        if name.startswith("_"):
            continue
        count = _as_int(value)
        if count:
            last_rows += count

    lag_min = (now - last_sync) // 60 if last_sync else None
    stale = None
    if lag_min is not None:
        stale = lag_min > max(5, int(effective * 2 / 60))

    accepted: dict[str, int] = {}
    raw_accepted = ingest_raw.get("accepted")
    if isinstance(raw_accepted, dict):
        for name, value in raw_accepted.items():
            count = _as_int(value)
            if count:
                accepted[str(name)] = count
    ingest = None
    if ingest_raw:
        ingest = {
            "at": _parse_stamp(ingest_raw.get("at")),
            "at_raw": ingest_raw.get("at"),
            "accepted": accepted,
            "accepted_rows": sum(accepted.values()),
            "rejected": _as_int(ingest_raw.get("rejected")) or 0,
        }

    backfill = {
        "running": bool(backfill_raw.get("running")),
        "days": _as_int(backfill_raw.get("days")),
        "progress": _backfill_progress(backfill_raw),
        "ok": backfill_raw.get("ok") if isinstance(backfill_raw.get("ok"), bool) else None,
        "done_at": _parse_stamp(backfill_raw.get("done_at")),
        "started_at": _parse_stamp(backfill_raw.get("at")),
        "note": str(backfill_raw.get("note"))[:200] if backfill_raw.get("note") else None,
        "error": str(backfill_raw.get("error"))[:200] if backfill_raw.get("error") else None,
        "range_from": None,
        "range_to": None,
    }
    days = backfill["days"]
    if days:
        backfill["range_from"] = fmt_date(window_start(days))
        backfill["range_to"] = fmt_date(now)

    return {
        "last_sync": last_sync,
        "lag_min": lag_min,
        "last_rows": last_rows,
        "last_stats": {str(k): v for k, v in last_stats.items()},
        "last_source": "xiaomi-cloud" if last_sync else None,
        "interval": int(effective),
        "next_sync": (last_sync + int(effective)) if last_sync else None,
        "stale": stale,
        "healthy": bool(last_sync) and stale is False,
        "ingest": ingest,
        "backfill": backfill,
    }


def _backfill_progress(raw: dict) -> float | None:
    done = _as_int(raw.get("slice"))
    total = _as_int(raw.get("slices"))
    if done is None or not total or total <= 0:
        return None
    return max(0.0, min(done / total, 1.0))


# ------------------------------------------------------------------ 审计
def audit_fail_count(con: sqlite3.Connection, hours: int = 24) -> int:
    """最近 ``hours`` 小时里结果不是 ok/approved 的审计行数。"""
    hours = _clamp_days(hours, 24) if hours and hours > 0 else 24
    hours = min(int(hours), 24 * 30)
    return _cached(con, "audit_fail", (hours,), lambda: _audit_fail(con, hours))


def _audit_fail(con: sqlite3.Connection, hours: int) -> int:
    since = int(time.time()) - int(hours) * 3600
    try:
        row = con.execute(
            "SELECT COUNT(*) FROM access_log WHERE created_at >= ? AND "
            "COALESCE(result, '') NOT IN ('ok', 'approved')",
            (since,)).fetchone()
    except sqlite3.Error:
        return 0
    return int(row[0] or 0) if row is not None else 0


def _has_table(con: sqlite3.Connection, table: str) -> bool:
    if table not in METRIC_LABELS and table != "access_log":
        return False
    try:
        row = con.execute(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
            (table,)).fetchone()
    except sqlite3.Error:
        return False
    return row is not None
