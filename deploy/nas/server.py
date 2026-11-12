#!/usr/bin/env python3
"""hc-health-mcp —— NAS 上的健康数据 MCP（公网可查，手机离线也能用）

数据来源：**只有**小米健康云（hlth.io.mi.com）—— 本服务自己登小米账号、每 15 分钟拉一次存 SQLite。
手机上的 hc-bridge 写的是**手机本地 Health Connect**，与本服务没有直接的 Health Connect 上行通道：
两条链路平行，各自从云端取数。所以手机 HC 里的历史要靠本服务的 backfill 自己再拉一遍。
手机 HC 独有（云端没有）的数据，如 Google Fit 的 walking 记录，走反方向：MCP 工具 `ingest`
（手机 hcb 每轮同步末尾自动推最近 30 天，也可 `CMD_PUSH` 手动）把 `workout`/`weight` upsert 进
`workout`/`weight` 表，`query_workouts` / `query_weight` 可查。
（另有 `POST /ingest` 直连端点，静态 Bearer only；公网反代已放行，和 MCP `ingest` 共用鉴权、
四层权限与分区检查。）
其余五类仍以云端为准，手机不重复搬（省带宽/存储）。
对外：Streamable-HTTP MCP（JSON-RPC 2.0），路径 /mcp；鉴权 = 静态 Bearer（container 侧校验）。
每个 agent 一把 token：`HEALTH_MCP_TOKENS=名字:token[,名字:token...]`，命中时把名字写进访问日志便于审计；
旧的单 token `HEALTH_MCP_TOKEN` 继续可用（记为 agent=legacy），全部迁完后把它从 .env 删掉即失效。
只读：不写手机、不写 HC。
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import struct
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from hcb import audit, auth, context, oauth, policy, schema, web
from hcb.db import db, users_dir

TZ_CN = timezone(timedelta(hours=8))
API_TOKEN = os.environ.get("HEALTH_MCP_TOKEN", "")


def _parse_agent_tokens(raw):
    """`名字:token[,名字:token]` → [(名字, token)]；只给 token 没给名字时记作 unnamed。"""
    out = []
    for item in (raw or "").split(","):
        item = item.strip()
        if not item:
            continue
        name, sep, tok = item.partition(":")
        name, tok = name.strip(), tok.strip()
        if not sep:
            name, tok = "unnamed", name
        if tok:
            out.append((name, tok))
    return out


AGENT_TOKENS = _parse_agent_tokens(os.environ.get("HEALTH_MCP_TOKENS", ""))
AUTH_ON = bool(AGENT_TOKENS or API_TOKEN)
_PLACEHOLDER_SECRETS = frozenset({
    "__token__", "change_me", "changeme", "change-me", "replace_me",
})


def _reject_placeholder_secrets():
    """模板里的占位 token 绝不能当成真实凭据启动。"""
    raw = (os.environ.get("HEALTH_MCP_TOKEN") or "").strip().lower()
    if raw in _PLACEHOLDER_SECRETS:
        print("[auth] HEALTH_MCP_TOKEN is still a placeholder; refusing to start",
              file=sys.stderr, flush=True)
        raise SystemExit(2)
    for name, token in AGENT_TOKENS:
        if str(token).strip().lower() in _PLACEHOLDER_SECRETS:
            print(f"[auth] HEALTH_MCP_TOKENS entry {name!r} is still a "
                  "placeholder; refusing to start", file=sys.stderr, flush=True)
            raise SystemExit(2)


def match_agent(header_value):
    """Authorization 头 → agent 名；不匹配返回 None。逐条常量时间比较，且不提前退出。"""
    header_value = header_value or ""
    if not header_value.startswith("Bearer "):
        return None
    got = header_value[7:].strip()
    if not got:
        return None
    hit = None
    for name, tok in AGENT_TOKENS:
        if hmac.compare_digest(got, tok) and hit is None:
            hit = name
    if hit is None and API_TOKEN and hmac.compare_digest(got, API_TOKEN):
        hit = "legacy"
    return hit
CREDS_PATH = os.environ.get("HEALTH_CREDS", "/data/creds.json")


def creds_path(owner: int = 0) -> str:
    """owner 0 用共享凭据；own 用户用 ``data/users/<id>.creds.json``。"""
    if not owner:
        return CREDS_PATH
    return os.path.join(users_dir(), f"{int(owner)}.creds.json")


FETCH_INTERVAL = int(os.environ.get("FETCH_INTERVAL_SEC", "900"))  # 15 分钟
MAX_MCP_BODY = 1024 * 1024      # MCP 批量请求；OAuth/后台表单另用 16 KB
MAX_INGEST_BODY = 4 * 1024 * 1024   # 手机批量推送可能很大，不能套 16 KB（nginx 4m）

UA = "Android-12-3.59.1-OnePlus-PJZ110"
LOGIN_UA = ("Dalvik/2.1.0 (Linux; U; Android 12; PJZ110 Build/ab8c0d1.1) APP/mi.health "
            "APPV/359001 MK/U0FKMQ SDKV/5.3.0.release.68 CPN/com.mi.health PassportSDK/")
LOGIN_URL = "https://account.xiaomi.com/pass/serviceLogin"
HEALTH_BASE = "https://hlth.io.mi.com"
FITNESS_PATH = "/app/v1/data/get_fitness_data_by_time"
SID = "miothealth"


# ------------------------------------------------------------------ 小米云
def rc4(key: bytes, data: bytes) -> bytes:
    s = list(range(256))
    j = 0
    for i in range(256):
        j = (j + s[i] + key[i % len(key)]) % 256
        s[i], s[j] = s[j], s[i]
    i = j = 0
    for _ in range(1024):
        i = (i + 1) % 256
        j = (j + s[i]) % 256
        s[i], s[j] = s[j], s[i]
    out = bytearray()
    for b in data:
        i = (i + 1) % 256
        j = (j + s[i]) % 256
        s[i], s[j] = s[j], s[i]
        out.append(b ^ s[(s[i] + s[j]) % 256])
    return bytes(out)


def http(url, method="GET", data=None, headers=None, timeout=30):
    req = urllib.request.Request(url, data=data, method=method)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace"), r.headers
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace"), e.headers


def parse_mi(text: str) -> dict:
    return json.loads(text[len("&&&START&&&"):] if text.startswith("&&&START&&&") else text)


def cookies_from(hdrs) -> dict:
    out = {}
    for item in hdrs.get_all("Set-Cookie") or []:
        head = item.split(";", 1)[0]
        if "=" in head:
            k, v = head.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def service_login(creds: dict, path: str | None = None):
    path = CREDS_PATH if path is None else path
    q = urllib.parse.urlencode({"_json": "true", "sid": SID})
    st, body, hdrs = http(f"{LOGIN_URL}?{q}", headers={
        "Cookie": f"userId={creds['userId']}; passToken={creds['passToken']}",
        "User-Agent": LOGIN_UA})
    if st != 200:
        raise RuntimeError(f"serviceLogin HTTP {st}")
    data = parse_mi(body)
    if data.get("code") not in (0, None):
        raise RuntimeError(f"serviceLogin code={data.get('code')}")
    ss = base64.b64decode(data["ssecurity"])
    ck = dict(cookies_from(hdrs))
    loc = data.get("location", "")
    if loc:
        _, _, h2 = http(loc, headers={"User-Agent": LOGIN_UA})
        ck.update(cookies_from(h2))
    new_pass = data.get("passToken") or creds["passToken"]
    if new_pass != creds["passToken"]:
        creds["passToken"] = new_pass
        with open(path, "w") as f:
            json.dump(creds, f)
        os.chmod(path, 0o600)
    return ck, ss


def api_post(cookies: dict, ssecurity: bytes, path: str, payload: dict) -> dict:
    form = {"data": json.dumps(payload, separators=(",", ":"))}
    nonce = os.urandom(8) + struct.pack(">I", int(time.time() // 60))
    sn = hashlib.sha256(ssecurity + nonce).digest()

    def sig(values: dict) -> str:
        base = "POST&" + path + "&data=" + values["data"]
        if "rc4_hash__" in values:
            base += "&rc4_hash__=" + values["rc4_hash__"]
        base += "&" + base64.b64encode(sn).decode()
        return base64.b64encode(hashlib.sha1(base.encode()).digest()).decode()

    form["rc4_hash__"] = sig(form)
    enc = {k: base64.b64encode(rc4(sn, v.encode())).decode() for k, v in form.items()}
    enc["signature"] = sig(enc)
    enc["_nonce"] = base64.b64encode(nonce).decode()
    body = urllib.parse.urlencode(enc).encode()
    # 单请求重试：NAS 到 hlth.io.mi.com 偶发 TLS 握手超时（实测 30s 才抛），
    # 在这里重试 2 次比让整片 7 天窗口报废划算得多。签名每次现算，重发同样的 body 也合法。
    last = None
    for attempt in range(3):
        if attempt:
            time.sleep(1.5 * attempt)
        try:
            st, text, _ = http(HEALTH_BASE + path, "POST", body, {
                "Cookie": "; ".join(f"{k}={v}" for k, v in cookies.items()),
                "Content-Type": "application/x-www-form-urlencoded",
                "User-Agent": UA}, timeout=40)
            last = (st, text)
            break
        except Exception as e:  # noqa: BLE001
            last = e
            print(f"[api] 第 {attempt + 1} 次请求失败: {e}", flush=True)
    if isinstance(last, Exception):
        raise last
    st, text = last
    if st != 200:
        raise RuntimeError(f"接口 HTTP {st}")
    data = json.loads(rc4(sn, base64.b64decode(text)).decode())
    if data.get("code") != 0:
        raise RuntimeError(f"接口 code={data.get('code')} {data.get('message')}")
    return data.get("result", {})


def fetch_key(cookies, ssecurity, key: str, start_ts: int, end_ts: int) -> list:
    out, next_key, pages = [], None, 0
    while True:
        pages += 1
        if pages > 200:
            break
        p = {"start_time": start_ts, "end_time": end_ts, "key": key}
        if next_key:
            p["next_key"] = next_key
        res = api_post(cookies, ssecurity, FITNESS_PATH, p)
        out.extend(res.get("data_list", []))
        if not res.get("has_more") or not res.get("next_key"):
            break
        next_key = str(res["next_key"])
    return out


def val(item: dict) -> dict:
    v = item.get("value")
    if isinstance(v, dict):
        return v
    try:
        return json.loads(v or "{}")
    except Exception:
        return {}


# ------------------------------------------------------------------ SQLite
def set_state(con, k, v):
    con.execute("INSERT INTO sync_state(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=?", (k, v, v))
    con.commit()


def get_state(con, k, default=""):
    row = con.execute("SELECT value FROM sync_state WHERE key=?", (k,)).fetchone()
    return row[0] if row else default


_BACKFILL_LOCKS: dict[int, threading.Lock] = {}
_BACKFILL_LOCKS_GUARD = threading.Lock()


def _backfill_lock(owner: int) -> threading.Lock:
    with _BACKFILL_LOCKS_GUARD:
        return _BACKFILL_LOCKS.setdefault(owner, threading.Lock())


def backfill_async(days: int, resume: bool = True, owner: int | None = None) -> bool:
    """启动后台回补；同一分区已有回补在跑则返回 False（单飞）。

    [owner]: 数据分区；缺省用当前 ``hcb.context``。进度写进该分区的 sync_state。
    [resume]: 同一天数窗口上次被中断（容器重启等）时，从上次完成的切片继续，不从头再拉一遍。
    """
    owner = context.get_owner() if owner is None else int(owner)
    lock = _backfill_lock(owner)
    if not lock.acquire(blocking=False):
        return False

    start_from, prev_stats = None, {}
    try:
        with db(owner) as con:
            prev = json.loads(get_state(con, "backfill_state", "{}") or "{}")
            if resume and prev.get("days") == days and prev.get("cursor") and not prev.get("running"):
                start_from = int(prev["cursor"])
                prev_stats = prev.get("stats") or {}
    except Exception:  # noqa: BLE001
        pass

    def run():
        try:
            with db(owner) as con:
                total_slices = max(1, (days + SLICE_DAYS - 1) // SLICE_DAYS)

                def progress(done, total, stats, cursor=None):
                    st = {"running": True, "days": days, "slice": done, "slices": total,
                          "stats": stats, "at": fmt(int(time.time()))}
                    if cursor:
                        st["cursor"] = cursor
                    set_state(con, "backfill_state", json.dumps(st))

                progress(0, total_slices, prev_stats)
                stats = sync_window(con, days, progress, start_from, owner=owner)
                set_state(con, "last_sync", str(int(time.time())))
                set_state(con, "last_sync_stats", json.dumps(stats))
                set_state(con, "backfill_state", json.dumps(
                    {"running": False, "days": days, "ok": "errors" not in stats,
                     "stats": stats, "done_at": fmt(int(time.time()))}))
        except Exception as e:  # noqa: BLE001
            print(f"[backfill] owner={owner} 失败: {e}", flush=True)
            try:
                with db(owner) as con:
                    set_state(con, "backfill_state", json.dumps(
                        {"running": False, "days": days, "ok": False, "error": str(e),
                         "done_at": fmt(int(time.time()))}))
            except Exception:  # noqa: BLE001
                pass
        finally:
            lock.release()

    threading.Thread(target=run, daemon=True).start()
    return True


def sync_once(days: int = 3, owner: int | None = None) -> dict:
    owner = context.get_owner() if owner is None else int(owner)
    now = int(time.time())
    start = now - days * 86400
    with db(owner) as con:
        stats = _sync_window_rows(con, start, now, owner=owner)
        set_state(con, "last_sync", str(now))
        set_state(con, "last_sync_stats", json.dumps(stats))
    return stats


def _sync_owners() -> list[int]:
    """owner 0 + 所有启用、data_mode='own' 且已有凭据的用户分区。"""
    owners = {0}
    try:
        with db(0) as con:
            for user in auth.list_users(con):
                if user["enabled"] and user["data_mode"] == "own" \
                        and os.path.exists(creds_path(user["id"])):
                    owners.add(int(user["id"]))
    except Exception as e:  # noqa: BLE001
        print(f"[sync] 列举分区失败: {type(e).__name__}", flush=True)
    return sorted(owners)


def fetch_interval():
    """后台抓取间隔：后台设置 ``fetch_interval_override`` 优先，范围 300–86400。

    设置值损坏时回退到启动时的 ``FETCH_INTERVAL``，绝不因为一个坏设置停掉同步。
    """
    try:
        with db(0) as con:
            raw = get_state(con, "fetch_interval_override", "")
        if raw:
            value = int(raw)
            if 300 <= value <= 86400:
                return value
    except Exception:  # noqa: BLE001
        pass
    return FETCH_INTERVAL


def fetch_loop():
    while True:
        for owner in _sync_owners():
            try:
                stats = sync_once(3, owner=owner)
                print(f"[sync] owner={owner} {datetime.now(TZ_CN):%F %T} {stats}", flush=True)
            except Exception as e:  # noqa: BLE001
                print(f"[sync] owner={owner} 失败: {e}", flush=True)
        time.sleep(fetch_interval())


# ------------------------------------------------------------------ 查询
def day_bounds(date_str: str | None):
    d = datetime.strptime(date_str, "%Y-%m-%d").replace(tzinfo=TZ_CN) if date_str else \
        datetime.now(TZ_CN).replace(hour=0, minute=0, second=0, microsecond=0)
    return int(d.timestamp()), int((d + timedelta(days=1)).timestamp()), d


def tool_list():
    def t(name, desc, props=None, req=None):
        schema = {"type": "object", "properties": props or {}, "additionalProperties": False}
        if req:
            schema["required"] = req
        return {"name": name, "description": desc, "inputSchema": schema}

    return [
        t("health_status", "服务与数据状态：上次同步、各表条数与时间覆盖。"),
        t("sync_now", "立刻从小米云同步一次（后台，几秒）。"),
        t("query_heart_rate", "心率采样。", {
            "start_date": {"type": "string"}, "end_date": {"type": "string"},
            "limit": {"type": "integer"}}),
        t("query_spo2", "血氧采样。", {
            "start_date": {"type": "string"}, "end_date": {"type": "string"},
            "limit": {"type": "integer"}}),
        t("query_metric_series", "按天指标序列。支持：steps / distance_km / distance_m / heart_rate_avg / "
                                "spo2_avg / stress_avg / active_kcal / sleep_minutes / sleep_hours / "
                                "weight_kg / bmi / body_fat_pct。", {
            "metric": {"type": "string", "enum": [
                "steps", "distance_km", "distance_m", "heart_rate_avg", "spo2_avg", "stress_avg",
                "active_kcal", "sleep_minutes", "sleep_hours", "weight_kg", "bmi", "body_fat_pct"]},
            "start_date": {"type": "string"}, "end_date": {"type": "string"}, "days": {"type": "integer"},
            "source": {"type": "string"}}),
        t("query_sleep", "睡眠会话（起止/时长/深睡）。可按来源筛选。", {
            "start_date": {"type": "string"}, "end_date": {"type": "string"},
            "source": {"type": "string"}, "dedupe": {"type": "boolean"}}),
        t("get_daily_summary", "某天汇总（步数/距离/心率统计/睡眠）。", {
            "date": {"type": "string"}}),
        t("query_workouts", "运动记录（来自手机推送的 HC 独有数据，云端没有）。", {
            "start_date": {"type": "string"}, "end_date": {"type": "string"}}),
        t("query_weight", "体重记录（云端体称 + 手机推送）。", {
            "start_date": {"type": "string"}, "end_date": {"type": "string"}}),
        t("query_stress", "压力采样（小米云；Health Connect 没有对应 record 类型，只有本服务有）。", {
            "start_date": {"type": "string"}, "end_date": {"type": "string"},
            "limit": {"type": "integer"}}),
        t("ingest", "手机推送 HC 独有数据（workout/weight）进来 upsert。items 每项形如 "
                    "{\"type\":\"workout\",\"start\":epoch,\"end\":epoch,\"minutes\":int,\"exercise_type\":int}"
                    " 或 {\"type\":\"weight\",\"ts\":epoch,\"kg\":float}。",
          {"items": {"type": "array", "items": {"type": "object"}}}),
        t("query_raw", "原始 payload（云端原样保存的全字段 JSON）。加新字段时用它重算，不必重拉云端。", {
            "key": {"type": "string", "enum": ["heart_rate", "sleep", "spo2", "steps",
                                               "calories", "stress", "weight"]},
            "start_date": {"type": "string"}, "end_date": {"type": "string"},
            "limit": {"type": "integer"}}),
        t("query_body_composition", "体成分：体重 + 体脂率 + BMI + 基础代谢/肌肉/体水分等（小米体称全字段），带统计量。", {
            "start_date": {"type": "string"}, "end_date": {"type": "string"},
            "source": {"type": "string", "description": "只看某个来源，如 xiaomi-cloud / phone"}}),
        t("sources", "各表按**来源**的条数明细（多来源存了哪些数据一目了然）。"),
        t("get_data_coverage", "各类型首末时间与条数。"),
        t("backfill", "历史回补：把最近 days 天（默认 365，最大 1825）从云端拉进库；7 天切片、幂等可重跑。"
                       "默认后台跑（进度看 health_status.backfill），wait=true 则同步等结果。",
          {"days": {"type": "integer"}, "wait": {"type": "boolean"}}),
        t("query_sleep_stages", "睡眠分期：深睡/浅睡/REM/清醒分钟 + 效率 + 夜间心率/HRV/血氧/呼吸（从小米 raw 的 items 复原）。", {
            "date": {"type": "string", "description": "看某一天（按起床日），缺省=最近 7 天"},
            "start_date": {"type": "string"}, "end_date": {"type": "string"}}),
        t("sleep_audit", "睡眠体检：缺夜 / 重复段 / minutes 与起止不一致的行。fix=true 时修 minutes 并从云端补缺夜。",
          {"days": {"type": "integer"}, "fix": {"type": "boolean"}}),
        t("health_export_csv", "导出区间 CSV（heart_rate/steps/spo2/sleep），返回内容（<=200KB）。", {
            "types": {"type": "array", "items": {"type": "string"}},
            "start_date": {"type": "string"}, "end_date": {"type": "string"}}),
    ]


def rng(args, default_days=1):
    """区间：优先 start_date/end_date，否则用 days（再否则 default_days）。

    `days` 以前只在个别工具里手工处理，导致传了 days 也不生效（2026-10-21 修）。
    """
    end = args.get("end_date")
    start = args.get("start_date")
    _, e_ts, _ = day_bounds(end) if end else (0, int(time.time()), None)
    if start:
        s_ts, _, _ = day_bounds(start)
    else:
        days = args.get("days")
        try:
            days = int(days) if days else default_days
        except (TypeError, ValueError):
            days = default_days
        s_ts = e_ts - max(1, days) * 86400
    return s_ts, e_ts


def fmt(ts: int) -> str:
    return datetime.fromtimestamp(ts, TZ_CN).strftime("%Y-%m-%d %H:%M:%S")


# ------------------------------------------------------------ 睡眠去重（读侧）
DUP_RATIO = 0.5   # 重叠超过较短那段的这个比例 = 同一段被写了两遍


def overlap_ratio(a, b) -> float:
    ov = min(a[1], b[1]) - max(a[0], b[0])
    if ov <= 0:
        return 0.0
    shorter = min(a[1] - a[0], b[1] - b[0])
    return ov / shorter if shorter > 0 else 0.0


def dedupe_bounds(bounds):
    """[(start_ts,end_ts)] → 重叠 >50% 的只留最长那条，按 start 升序返回。"""
    kept = []
    for b in sorted(bounds, key=lambda x: x[0] - x[1]):   # 时长降序
        if all(overlap_ratio(k, b) <= DUP_RATIO for k in kept):
            kept.append(b)
    return sorted(kept, key=lambda x: x[0])


def dedupe_sessions(sessions):
    """与 dedupe_bounds 同规则，但输入是 query_sleep 的 dict 列表（按 start/end 字符串）。"""
    def bnd(x):
        return (int(datetime.strptime(x["start"], "%Y-%m-%d %H:%M:%S").replace(tzinfo=TZ_CN).timestamp()),
                int(datetime.strptime(x["end"], "%Y-%m-%d %H:%M:%S").replace(tzinfo=TZ_CN).timestamp()))
    kept = []
    for x in sorted(sessions, key=lambda x: bnd(x)[0] - bnd(x)[1]):
        bx = bnd(x)
        if all(overlap_ratio(bnd(k), bx) <= DUP_RATIO for k in kept):
            kept.append(x)
    return sorted(kept, key=lambda x: x["start"])


SLICE_DAYS = 7          # 回补切片：云端老数据一天就有 1300+ 个心率点，7 天一片内存更稳


def save_raw(con, key: str, items: list) -> int:
    """把一次拉取拿到的 items **原样**存进 raw（含 item 层字段如 time/type + value 全字段）。"""
    rows = []
    for it in items:
        try:
            ts = int(it.get("time", 0) or val(it).get("time", 0) or 0)
        except Exception:  # noqa: BLE001
            ts = 0
        if ts <= 0:
            continue
        rows.append((key, ts, json.dumps(it, ensure_ascii=False, separators=(",", ":"))))
    if rows:
        con.executemany("INSERT OR REPLACE INTO raw(key,ts,value) VALUES(?,?,?)", rows)
    return len(rows)


def sync_window(con, days: int, progress=None, start_from=None, owner: int | None = None) -> dict:
    """把 [now-days, now] 拉一遍（按 SLICE_DAYS 切片；云端对**当前**窄窗口会返回空，
    但历史窄窗口没问题 —— 实测 2025-10-21 单天 1333 条心率，0.6s）。

    单切片失败不让整轮报废：每片最多 4 次、退避 3/8/20s（对端 TLS 握手偶发超时，实测 30s 一次），
    失败的片记下来，整轮末尾再补一轮；所有写入都是 INSERT OR REPLACE，回补可重跑。
    """
    owner = context.get_owner() if owner is None else int(owner)
    path = creds_path(owner)
    now = int(time.time())
    cursor = now - days * 86400
    total = {}
    errors = []
    if start_from and start_from > cursor:                 # 断点续跑（重启/中断后不从头再来）
        cursor = start_from
        print(f"[backfill] 从断点继续: {fmt(cursor)}", flush=True)
    with open(path) as f:
        creds = json.load(f)
    sess = service_login(creds, path)    # 整轮共用一次登录
    slices = max(1, (days + SLICE_DAYS - 1) // SLICE_DAYS)
    idx = 0
    failed = []
    while cursor < now:
        slice_end = min(cursor + SLICE_DAYS * 86400, now)
        got = None
        for attempt, wait in enumerate((0, 3, 8, 20)):
            if wait:
                time.sleep(wait)
            try:
                got = _sync_window_rows(con, cursor, slice_end, sess, owner=owner)
                break
            except Exception as e:  # noqa: BLE001
                print(f"[backfill] {fmt(cursor)}..{fmt(slice_end)} 第 {attempt + 1} 次失败: {e}", flush=True)
                errors.append(f"{fmt(cursor)}: {e}")
                sess = service_login(creds, path)   # 会话可能已经坏了，重登
        if got:
            for k, v in got.items():
                total[k] = total.get(k, 0) + v
        else:
            failed.append((cursor, slice_end))
        idx += 1
        cursor = slice_end + 1
        if progress:
            progress(idx, slices, total, cursor)
        time.sleep(0.3)

    # 末尾补一轮：只重试整片失败的窗口（各一次，慢退避）
    for i, (cs, ce) in enumerate(failed):
        time.sleep(10)
        try:
            got = _sync_window_rows(con, cs, ce, sess, owner=owner)
            for k, v in got.items():
                total[k] = total.get(k, 0) + v
            print(f"[backfill] 补漏 {fmt(cs)}..{fmt(ce)} ok {got}", flush=True)
        except Exception as e:  # noqa: BLE001
            print(f"[backfill] 补漏 {fmt(cs)}..{fmt(ce)} 仍失败: {e}", flush=True)
            errors.append(f"{fmt(cs)}: {e}")
        if progress:
            progress(min(slices, idx + i + 1), slices, total, ce)

    if errors:
        total["errors"] = errors[:5]
        total["failed_slices"] = len(failed)
    return total


def _sync_window_rows(con, start: int, end: int, sess=None, owner: int | None = None) -> dict:
    """拉 [start, end] 写库；返回各表写入条数。sync_once 与回补共用。

    [sess]=(cookies, ssecurity) 可复用登录会话，回补不必每片重新登录（少一半请求）。
    """
    if sess is None:
        owner = context.get_owner() if owner is None else int(owner)
        path = creds_path(owner)
        with open(path) as f:
            creds = json.load(f)
        cookies, ss = service_login(creds, path)
    else:
        cookies, ss = sess
    stats = {}
    stats["raw"] = 0
    hr = fetch_key(cookies, ss, "heart_rate", start, end)
    stats["raw"] += save_raw(con, "heart_rate", hr)
    rows = []
    for it in hr:
        v = val(it)
        bpm = v.get("bpm") or v.get("heart_rate")
        if bpm and 20 <= int(bpm) <= 250:
            rows.append((int(it.get("time", 0)), int(bpm)))
    con.executemany("INSERT OR REPLACE INTO heart_rate(ts,bpm,source) VALUES(?,?,'xiaomi-cloud')", rows)
    stats["heart_rate"] = len(rows)
    con.commit()

    sp = fetch_key(cookies, ss, "spo2", start, end)
    stats["raw"] += save_raw(con, "spo2", sp)
    rows = []
    for it in sp:
        v = val(it)
        pct = v.get("spo2")
        if pct and 50 <= float(pct) <= 100:
            rows.append((int(it.get("time", 0)), float(pct)))
    con.executemany("INSERT OR REPLACE INTO spo2(ts,pct,source) VALUES(?,?,'xiaomi-cloud')", rows)
    stats["spo2"] = len(rows)
    con.commit()

    st = fetch_key(cookies, ss, "steps", start, end)
    stats["raw"] += save_raw(con, "steps", st)
    rows = []
    for it in st:
        v = val(it)
        rows.append((int(it.get("time", 0)), int(v.get("steps", 0) or 0), float(v.get("distance", 0) or 0)))
    con.executemany("INSERT OR REPLACE INTO steps(ts,steps,distance_m,source) VALUES(?,?,?,'xiaomi-cloud')", rows)
    stats["steps"] = len(rows)
    con.commit()

    ce = fetch_key(cookies, ss, "calories", start, end)
    stats["raw"] += save_raw(con, "calories", ce)
    rows = []
    for it in ce:
        v = val(it)
        kcal = v.get("calories")
        if kcal is not None and float(kcal) >= 0:
            rows.append((int(it.get("time", 0)), float(kcal)))
    con.executemany("INSERT OR REPLACE INTO calories(ts,kcal,source) VALUES(?,?,'xiaomi-cloud')", rows)
    stats["calories"] = len(rows)
    con.commit()

    st2 = fetch_key(cookies, ss, "stress", start, end)
    stats["raw"] += save_raw(con, "stress", st2)
    rows = []
    for it in st2:
        v = val(it)
        sv = v.get("stress")
        if sv is not None and 0 <= int(sv) <= 100:
            rows.append((int(it.get("time", 0)), int(sv)))
    con.executemany("INSERT OR REPLACE INTO stress(ts,stress,source) VALUES(?,?,'xiaomi-cloud')", rows)
    stats["stress"] = len(rows)
    con.commit()

    wt = fetch_key(cookies, ss, "weight", start, end)
    stats["raw"] += save_raw(con, "weight", wt)
    rows = []
    for it in wt:
        v = val(it)
        kg = v.get("weight")
        if kg is None:
            ff, fm = v.get("fat_free_body"), v.get("fat_mass")
            kg = (float(ff) + float(fm)) if (ff and fm) else None
        if kg and 20 <= float(kg) <= 300:
            rows.append((int(it.get("time", 0) or v.get("time", 0) or 0), float(kg),
                         "xiaomi-cloud", float(v.get("bmi") or 0), float(v.get("body_fat_rate") or 0),
                         float(v.get("muscle_mass") or 0), int(v.get("body_age") or 0)))
    con.executemany("INSERT OR REPLACE INTO weight(ts,kg,origin,bmi,fat_rate,muscle_kg,body_age,source) "
                    "VALUES(?,?,?,?,?,?,?,'xiaomi-cloud')", rows)
    stats["weight"] = len(rows)
    con.commit()

    sl = fetch_key(cookies, ss, "sleep", start, end)
    stats["raw"] += save_raw(con, "sleep", sl)
    rows = []
    for it in sl:
        v = val(it)
        bed = int(v.get("bedtime", 0) or 0)
        wake = int(v.get("device_wake_up_time", 0) or 0) or (bed + int(v.get("sleep_duration", 0) or 0) * 60)
        if bed and wake > bed:
            rows.append((bed, wake, (wake - bed) // 60, int(v.get("sleep_deep_duration", 0) or 0)))
    con.executemany("INSERT OR REPLACE INTO sleep(bed,wake,minutes,deep_min,source) VALUES(?,?,?,?,'xiaomi-cloud')", rows)
    stats["sleep"] = len(rows)
    con.commit()
    con.commit()
    return stats


def call_tool(name, args):
    with db() as con:
        if name == "ingest":
            return ingest({"items": args.get("items") or []})

        if name == "health_status":
            out = {"last_sync": get_state(con, "last_sync"),
                   "last_sync_stats": json.loads(get_state(con, "last_sync_stats", "{}"))}
            bf = get_state(con, "backfill_state", "")
            if bf:
                try:
                    out["backfill"] = json.loads(bf)
                except Exception:  # noqa: BLE001
                    out["backfill"] = {"raw": bf}
            rw = con.execute("SELECT COUNT(*) FROM raw").fetchone()[0]
            out["raw"] = {"count": rw, "first": "", "last": ""}
            for tbl in ("heart_rate", "spo2", "steps", "calories", "sleep", "stress", "workout", "weight"):
                if tbl == "sleep":
                    row = con.execute("SELECT COUNT(*), MIN(bed), MAX(bed) FROM sleep").fetchone()
                else:
                    col = "start" if tbl == "workout" else "ts"
                    row = con.execute(f"SELECT COUNT(*), MIN({col}), MAX({col}) FROM {tbl}").fetchone()
                out[tbl] = {"count": row[0],
                            "first": fmt(row[1]) if row[1] else "",
                            "last": fmt(row[2]) if row[2] else ""}
            return out

        if name == "sync_now":
            return {"ok": True, "stats": sync_once(3, owner=context.get_owner())}

        if name == "query_heart_rate":
            s, e = rng(args)
            lim = int(args.get("limit", 500))
            src = args.get("source")
            if src:
                rows = con.execute("SELECT ts,bpm,source FROM heart_rate WHERE ts BETWEEN ? AND ? AND source=? "
                                   "ORDER BY ts LIMIT ?", (s, e, src, lim)).fetchall()
                total = con.execute("SELECT COUNT(*) FROM heart_rate WHERE ts BETWEEN ? AND ? AND source=?",
                                    (s, e, src)).fetchone()[0]
            else:
                rows = con.execute("SELECT ts,bpm,source FROM heart_rate WHERE ts BETWEEN ? AND ? ORDER BY ts LIMIT ?",
                                   (s, e, lim)).fetchall()
                total = con.execute("SELECT COUNT(*) FROM heart_rate WHERE ts BETWEEN ? AND ?",
                                    (s, e)).fetchone()[0]
            return {"from": fmt(s), "to": fmt(e), "samples_total": total, "returned": len(rows),
                    "sources": sorted({r[2] for r in rows}),
                    "samples": [{"at": fmt(t), "epoch": t, "bpm": b, "source": o} for t, b, o in rows]}

        if name == "query_spo2":
            s, e = rng(args)
            lim = int(args.get("limit", 200))
            rows = con.execute("SELECT ts,pct FROM spo2 WHERE ts BETWEEN ? AND ? ORDER BY ts LIMIT ?",
                               (s, e, lim)).fetchall()
            return {"from": fmt(s), "to": fmt(e), "count": len(rows),
                    "samples": [{"at": fmt(t), "percent": p} for t, p in rows]}

        if name == "query_metric_series":
            metric = args.get("metric", "steps")
            src = args.get("source")
            s, e = rng(args, 30)
            out = []
            d = datetime.fromtimestamp(s, TZ_CN).date()
            last = datetime.fromtimestamp(e, TZ_CN).date()
            where_src = " AND source=?" if src else ""
            p_src = [src] if src else []
            while d <= last:
                a, b, _ = day_bounds(d.isoformat())
                v = None
                if metric in ("steps", "distance_km", "distance_m"):
                    if metric == "steps":
                        v = con.execute("SELECT SUM(steps) FROM steps WHERE ts BETWEEN ? AND ?" + where_src,
                                        [a, b] + p_src).fetchone()[0]
                        v = int(v or 0)
                    else:
                        tot = con.execute("SELECT SUM(distance_m) FROM steps WHERE ts BETWEEN ? AND ?" + where_src,
                                          [a, b] + p_src).fetchone()[0] or 0
                        v = round(tot / 1000, 2) if metric == "distance_km" else round(tot, 1)
                elif metric in ("heart_rate_avg",):
                    v = con.execute("SELECT AVG(bpm) FROM heart_rate WHERE ts BETWEEN ? AND ?" + where_src,
                                    [a, b] + p_src).fetchone()[0]
                    v = round(v, 1) if v is not None else None
                elif metric in ("spo2_avg", "spo2"):
                    v = con.execute("SELECT AVG(pct) FROM spo2 WHERE ts BETWEEN ? AND ?" + where_src,
                                    [a, b] + p_src).fetchone()[0]
                    v = round(v, 1) if v is not None else None
                elif metric in ("stress_avg", "stress"):
                    v = con.execute("SELECT AVG(stress) FROM stress WHERE ts BETWEEN ? AND ?" + where_src,
                                    [a, b] + p_src).fetchone()[0]
                    v = round(v, 1) if v is not None else None
                elif metric in ("active_kcal", "kcal"):
                    v = con.execute("SELECT SUM(kcal) FROM calories WHERE ts BETWEEN ? AND ?" + where_src,
                                    [a, b] + p_src).fetchone()[0]
                    v = round(v, 1) if v is not None else 0
                elif metric in ("sleep_minutes", "sleep_hours"):
                    # 归到起床日，先裁到当天再去重求和（与 get_daily_summary 同口径）
                    rows = con.execute("SELECT bed,wake FROM sleep WHERE wake > ? AND bed < ?" + where_src,
                                       [a, b] + p_src).fetchall()
                    crop = [(max(bd, a), min(wk, b)) for bd, wk in rows if min(wk, b) > max(bd, a)]
                    mins = sum((wk - bd) // 60 for bd, wk in dedupe_bounds(crop))
                    v = round(mins / 60, 2) if metric == "sleep_hours" else mins
                elif metric in ("weight_kg", "bmi", "body_fat_pct"):
                    col = {"weight_kg": "kg", "bmi": "bmi", "body_fat_pct": "fat_rate"}[metric]
                    v = con.execute(f"SELECT {col} FROM weight WHERE ts BETWEEN ? AND ?" + where_src +
                                    " ORDER BY ts DESC LIMIT 1", [a, b] + p_src).fetchone()
                    v = round(v[0], 1) if v and v[0] is not None else None
                else:
                    v = None
                out.append({"date": d.isoformat(), "value": v})
                d += timedelta(days=1)
            return {"metric": metric, "source": src, "series": out}

        if name == "query_body_composition":
            s, e = rng(args, 365)
            src = args.get("source")
            q = "SELECT ts,kg,origin,bmi,fat_rate,muscle_kg,body_age,source FROM weight WHERE ts BETWEEN ? AND ?"
            params = [s, e]
            if src:
                q += " AND source=?"
                params.append(src)
            rows = con.execute(q + " ORDER BY ts", params).fetchall()
            # 小米体称的完整字段在 raw 里（基础代谢/体水分/骨量/体得分…），按 ts 找回来
            extras = {}
            try:
                for ts, val_ in con.execute("SELECT ts,value FROM raw WHERE key='weight' AND ts BETWEEN ? AND ?",
                                            (s, e)):
                    try:
                        inner = json.loads(json.loads(val_)["value"]) if isinstance(val_, str) else {}
                    except Exception:  # noqa: BLE001
                        continue
                    extras[int(ts)] = inner
            except Exception:  # noqa: BLE001
                pass

            recs = []
            for ts, kg, origin, bmi, fat, muscle, age, sc in rows:
                ex = extras.get(int(ts), {})
                recs.append({
                    "at": fmt(ts), "epoch": ts, "kg": kg, "bmi": bmi, "body_fat_pct": fat,
                    "muscle_kg": muscle, "body_age": age, "origin": origin, "source": sc,
                    "basal_metabolism": ex.get("basal_metabolism"),
                    "moisture_rate": ex.get("moisture_rate"),
                    "body_score": ex.get("body_score"),
                    "bone_mass": ex.get("bone_mass"),
                    "fat_free_body": ex.get("fat_free_body"),
                })

            # 按天合并（同一天多次称重取最后一次）
            by_day = {}
            for r in recs:
                by_day[r["at"][:10]] = r
            series = [{"date": d, **{k: v for k, v in r.items()
                                     if k in ("kg", "bmi", "body_fat_pct", "muscle_kg", "body_age")}}
                      for d, r in sorted(by_day.items())]

            def stats(key, nd=1):
                vs = [x[key] for x in recs
                      if isinstance(x.get(key), (int, float)) and x[key] > 0]
                if not vs:
                    return {"count": 0}
                return {"count": len(vs), "min": round(min(vs), nd), "max": round(max(vs), nd),
                        "avg": round(sum(vs) / len(vs), nd), "last": round(vs[-1], nd),
                        "change": round(vs[-1] - vs[0], nd)}

            return {"from": fmt(s), "to": fmt(e), "count": len(recs),
                    "sources": sorted({r["source"] for r in recs}),
                    "latest": recs[-1] if recs else None,
                    "stats": {k: stats(k) for k in ("kg", "bmi", "body_fat_pct", "muscle_kg")},
                    "series": series, "records": recs}

        if name == "query_sleep_stages":
            if args.get("date"):
                s, e, _ = day_bounds(args["date"])
            else:
                s, e = rng(args, 7)
            rows = con.execute("SELECT bed,wake FROM sleep WHERE wake > ? AND bed < ? ORDER BY bed",
                               (s, e)).fetchall()
            kept = dedupe_bounds([(max(bd, s), min(wk, e)) for bd, wk in rows
                                  if min(wk, e) > max(bd, s)])
            out = []
            for bd, wk in kept:
                # 找这条会话对应的 raw payload（raw.ts 是 item 的 time，真实 bedtime 在 value 里）
                best = None
                for (val_,) in con.execute("SELECT value FROM raw WHERE key='sleep' AND ts BETWEEN ? AND ?",
                                           (bd - 2 * 86400, bd + 2 * 86400)):
                    try:
                        inner = json.loads(json.loads(val_)["value"]) if isinstance(val_, str) else {}
                    except Exception:  # noqa: BLE001
                        continue
                    if (abs(int(inner.get("bedtime", 0)) - bd) <= 3600
                            or abs(int(inner.get("bed_timestamp", 0)) - bd) <= 3600):
                        best = inner
                        break
                stage = {}
                if best:
                    for it in (best.get("items") or []):
                        st = it.get("state")
                        dur = (int(it.get("end_time", 0)) - int(it.get("start_time", 0))) // 60
                        if st is not None and dur > 0:
                            stage[st] = stage.get(st, 0) + dur
                b = best or {}
                out.append({
                    "start": fmt(bd), "end": fmt(wk), "minutes": (wk - bd) // 60,
                    "deep_minutes": b.get("sleep_deep_duration"),
                    "light_minutes": b.get("sleep_light_duration"),
                    "rem_minutes": b.get("sleep_rem_duration"),
                    "awake_minutes": b.get("sleep_awake_duration"),
                    "efficiency": b.get("sleep_efficiency"),
                    "in_bed_minutes": ((b.get("bed_duration") or 0) // 60) or None,
                    "avg_hr": b.get("avg_hr"), "min_hr": b.get("min_hr"), "max_hr": b.get("max_hr"),
                    "avg_hrv": b.get("avg_hrv"), "hrv_median": b.get("hrv_median"),
                    "avg_spo2": b.get("avg_spo2"), "avg_breath": b.get("avg_breath"),
                    "stage_minutes_by_state": {f"state_{k}": v for k, v in sorted(stage.items())},
                })
            return {"from": fmt(s), "to": fmt(e), "count": len(out), "sessions": out}

        if name == "query_sleep":
            s, e = rng(args, 7)
            src = args.get("source")
            if src:
                rows = con.execute("SELECT bed,wake,minutes,deep_min FROM sleep WHERE bed BETWEEN ? AND ? "
                                   "AND source=? ORDER BY bed", (s, e, src)).fetchall()
            else:
                rows = con.execute("SELECT bed,wake,minutes,deep_min FROM sleep WHERE bed BETWEEN ? AND ? ORDER BY bed",
                                   (s, e)).fetchall()
            sess = [{"start": fmt(b), "end": fmt(w), "minutes": (w - b) // 60, "deep_minutes": dm}
                    for b, w, _m, dm in rows]
            if not args.get("dedupe", True):
                return {"count": len(sess), "sessions": sess}
            kept = dedupe_sessions(sess)
            return {"count": len(kept), "raw_count": len(sess),
                    "duplicates": len(sess) - len(kept), "sessions": kept}

        if name == "get_daily_summary":
            date = args.get("date") or datetime.now(TZ_CN).strftime("%Y-%m-%d")
            a, b, d = day_bounds(date)
            steps = con.execute("SELECT SUM(steps), SUM(distance_m) FROM steps WHERE ts BETWEEN ? AND ?",
                                (a, b)).fetchone()
            hr = con.execute("SELECT COUNT(*), AVG(bpm), MIN(bpm), MAX(bpm) FROM heart_rate WHERE ts BETWEEN ? AND ?",
                             (a, b)).fetchone()
            sp = con.execute("SELECT COUNT(*), AVG(pct) FROM spo2 WHERE ts BETWEEN ? AND ?", (a, b)).fetchone()
            kcal = con.execute("SELECT SUM(kcal) FROM calories WHERE ts BETWEEN ? AND ?", (a, b)).fetchone()[0]
            stress = con.execute("SELECT AVG(stress), MAX(stress), COUNT(*) FROM stress WHERE ts BETWEEN ? AND ?",
                                 (a, b)).fetchone()
            # 睡眠：与这一天有交集的会话，裁到当天、先去重再求和，归到**起床那天**（与手机侧 HC 口径一致）。
            # 旧写法 `bed BETWEEN a+1d..b+1d` + SUM(minutes) 既用了错口径的 minutes，又可能把同一段算两遍。
            rows = con.execute("SELECT bed,wake FROM sleep WHERE wake > ? AND bed < ? ORDER BY bed", (a, b)).fetchall()
            crop = [(max(bd, a), min(wk, b)) for bd, wk in rows if min(wk, b) > max(bd, a)]
            sleep_min = sum((wk - bd) // 60 for bd, wk in dedupe_bounds(crop))
            return {"date": date, "steps": int(steps[0] or 0), "distance_km": round((steps[1] or 0) / 1000, 2),
                    "heart_rate_samples": hr[0], "heart_rate_avg": round(hr[1], 1) if hr[1] else None,
                    "heart_rate_min": hr[2], "heart_rate_max": hr[3],
                    "spo2_samples": sp[0], "spo2_avg": round(sp[1], 1) if sp[1] else None,
                    "active_kcal": round(kcal, 1) if kcal else 0,
                    "stress_avg": round(stress[0], 1) if stress[0] else None,
                    "stress_max": stress[1], "stress_samples": stress[2],
                    "sleep_minutes": int(sleep_min)}

        if name == "query_workouts":
            a, b = rng(args, 30)
            src = args.get("source")
            if src:
                rows = con.execute("SELECT start,end,minutes,exercise_type,title,origin,source FROM workout "
                                   "WHERE start BETWEEN ? AND ? AND source=? ORDER BY start", (a, b, src)).fetchall()
            else:
                rows = con.execute("SELECT start,end,minutes,exercise_type,title,origin,source FROM workout "
                                   "WHERE start BETWEEN ? AND ? ORDER BY start", (a, b)).fetchall()
            return {"count": len(rows), "workouts": [
                {"start": fmt(s0), "end": fmt(e0), "minutes": m, "exercise_type": et,
                 "title": ti, "origin": o, "source": sc} for s0, e0, m, et, ti, o, sc in rows]}

        if name == "query_stress":
            a, b = rng(args, 30)
            lim = int(args.get("limit", 2000))
            rows = con.execute("SELECT ts,stress FROM stress WHERE ts BETWEEN ? AND ? ORDER BY ts LIMIT ?",
                               (a, b, lim)).fetchall()
            total = con.execute("SELECT COUNT(*), AVG(stress), MIN(stress), MAX(stress) FROM stress "
                                "WHERE ts BETWEEN ? AND ?", (a, b)).fetchone()
            return {"count": len(rows), "samples_total": total[0],
                    "avg": round(total[1], 1) if total[1] else None,
                    "min": total[2], "max": total[3],
                    "samples": [{"at": fmt(t), "stress": v} for t, v in rows]}

        if name == "query_weight":
            a, b = rng(args, 3650)
            src = args.get("source")
            if src:
                rows = con.execute("SELECT ts,kg,origin,bmi,fat_rate,muscle_kg,body_age,source FROM weight "
                                   "WHERE ts BETWEEN ? AND ? AND source=? ORDER BY ts", (a, b, src)).fetchall()
            else:
                rows = con.execute("SELECT ts,kg,origin,bmi,fat_rate,muscle_kg,body_age,source FROM weight "
                                   "WHERE ts BETWEEN ? AND ? ORDER BY ts", (a, b)).fetchall()
            return {"count": len(rows), "records": [
                {"at": fmt(t), "kg": kg, "origin": o, "bmi": bmi, "body_fat_rate": fr,
                 "muscle_kg": mm, "body_age": ba, "source": sc}
                for t, kg, o, bmi, fr, mm, ba, sc in rows]}

        if name == "query_raw":
            key = args.get("key")
            if key not in ("heart_rate", "sleep", "spo2", "steps", "calories", "stress", "weight"):
                raise ValueError("不支持的 key")
            a, b = rng(args, 1)
            lim = int(args.get("limit", 200))
            rows = con.execute("SELECT ts,value FROM raw WHERE key=? AND ts BETWEEN ? AND ? "
                               "ORDER BY ts LIMIT ?", (key, a, b, lim)).fetchall()
            total = con.execute("SELECT COUNT(*) FROM raw WHERE key=? AND ts BETWEEN ? AND ?",
                                (key, a, b)).fetchone()[0]
            return {"key": key, "samples_total": total, "returned": len(rows),
                    "items": [json.loads(v) for _t, v in rows]}

        if name == "sources":
            out = {}
            for tbl, col in (("heart_rate", "ts"), ("spo2", "ts"), ("steps", "ts"), ("calories", "ts"),
                             ("sleep", "bed"), ("stress", "ts"), ("weight", "ts"), ("workout", "start")):
                try:
                    rows = con.execute(f"SELECT source, COUNT(*), MIN({col}), MAX({col}) FROM {tbl} "
                                       f"GROUP BY source ORDER BY COUNT(*) DESC").fetchall()
                    out[tbl] = [{"source": sc, "count": n,
                                 "first": fmt(a) if a else "", "last": fmt(b) if b else ""}
                                for sc, n, a, b in rows]
                except Exception as e:  # noqa: BLE001
                    out[tbl] = [{"error": str(e)}]
            return out

        if name == "get_data_coverage":
            out = {}
            for tbl, col in (("heart_rate", "ts"), ("spo2", "ts"), ("steps", "ts"),
                             ("calories", "ts"), ("sleep", "bed"), ("stress", "ts"),
                             ("workout", "start"), ("weight", "ts")):
                row = con.execute(f"SELECT COUNT(*), MIN({col}), MAX({col}) FROM {tbl}").fetchone()
                out[tbl] = {"count": row[0], "first": fmt(row[1]) if row[1] else "",
                            "last": fmt(row[2]) if row[2] else ""}
            return out

        if name == "backfill":
            days = max(1, min(int(args.get("days", 365)), 1825))
            if args.get("wait"):
                stats = sync_window(con, days, owner=context.get_owner())
                set_state(con, "last_sync", str(int(time.time())))
                set_state(con, "last_sync_stats", json.dumps(stats))
                return {"ok": True, "days": days, "written": stats}
            started = backfill_async(days, owner=context.get_owner())
            return {"ok": True, "days": days, "queued": started,
                    "note": "进度看 health_status.backfill" if started else "已有回补在跑"}

        if name == "sleep_audit":
            days = max(1, min(int(args.get("days", 30)), 1825))
            fix = bool(args.get("fix"))
            now = int(time.time())
            since = now - days * 86400
            # 注意：多退一天。按“起床日”判缺时，窗口第一天的上一晚（如 30 天窗口里的
            # 09-29 07:33 那次）wake 刚好早于 since，会被滤掉、然后又被判成“缺夜”——
            # 2026-10-29 实测：数据其实在（09-28 22:28→09-29 07:33），audit 却报缺 09-29。
            rows = con.execute("SELECT bed,wake,minutes,deep_min FROM sleep WHERE wake > ? ORDER BY bed",
                               (since - 86400,)).fetchall()
            # 1) 同一段被写两遍（>50% 重叠）
            bounds = [(b, w) for b, w, _m, _d in rows]
            dupes = []
            for i in range(len(bounds)):
                for j in range(i + 1, len(bounds)):
                    if overlap_ratio(bounds[i], bounds[j]) > DUP_RATIO:
                        dupes.append({"a": [fmt(bounds[i][0]), fmt(bounds[i][1])],
                                      "b": [fmt(bounds[j][0]), fmt(bounds[j][1])]})
            # 2) minutes 与起止不一致（历史坏口径）
            bad = [{"bed": fmt(b), "wake": fmt(w), "minutes": m, "should_be": (w - b) // 60}
                   for b, w, m, _d in rows if m != (w - b) // 60]
            if fix and bad:
                con.executemany("UPDATE sleep SET minutes=? WHERE bed=?",
                                [((w - b) // 60, b) for b, w, _m, _d in rows if _m != (w - b) // 60])
                con.commit()
            # 3) 缺夜（按起床那天）
            covered = {datetime.fromtimestamp(w, TZ_CN).date() for _b, w, _m, _d in rows}
            today = datetime.now(TZ_CN).date()
            missing = [str(today - timedelta(days=k)) for k in range(1, days + 1)
                       if (today - timedelta(days=k)) not in covered]
            filled = 0
            if fix and missing:
                lo = int(datetime.combine(datetime.strptime(missing[-1], "%Y-%m-%d").date() - timedelta(days=1),
                                          datetime.min.time()).replace(tzinfo=TZ_CN).timestamp())
                hi = int(datetime.combine(today + timedelta(days=1),
                                          datetime.min.time()).replace(tzinfo=TZ_CN).timestamp())
                before = con.execute("SELECT COUNT(*) FROM sleep").fetchone()[0]
                _sync_window_rows(con, lo, hi, owner=context.get_owner())
                filled = con.execute("SELECT COUNT(*) FROM sleep").fetchone()[0] - before
            return {"days": days, "sessions": len(rows),
                    "duplicates": dupes[:20], "duplicates_count": len(dupes),
                    "minutes_mismatch": len(bad), "minutes_mismatch_sample": bad[:5],
                    "fixed_minutes": len(bad) if fix else 0,
                    "missing_nights": missing, "missing_count": len(missing),
                    "filled_sessions": filled}

        if name == "health_export_csv":
            types = args.get("types") or ["heart_rate", "steps", "sleep"]
            s, e = rng(args, 7)
            buf = []
            counts = {}
            if "heart_rate" in types:
                buf.append("== heart_rate ==\nat,epoch,bpm")
                rows = con.execute("SELECT ts,bpm FROM heart_rate WHERE ts BETWEEN ? AND ? ORDER BY ts", (s, e)).fetchall()
                buf += [f"{fmt(t)},{t},{b}" for t, b in rows]
                counts["heart_rate"] = len(rows)
            if "steps" in types:
                buf.append("\n== steps (daily) ==\ndate,steps,distance_km")
                d = datetime.fromtimestamp(s, TZ_CN).date()
                last = datetime.fromtimestamp(e, TZ_CN).date()
                n = 0
                while d <= last:
                    a, b, _ = day_bounds(d.isoformat())
                    v = con.execute("SELECT SUM(steps), SUM(distance_m) FROM steps WHERE ts BETWEEN ? AND ?",
                                    (a, b)).fetchone()
                    buf.append(f"{d.isoformat()},{int(v[0] or 0)},{round((v[1] or 0)/1000, 2)}")
                    n += 1
                    d += timedelta(days=1)
                counts["steps"] = n
            if "spo2" in types:
                buf.append("\n== spo2 ==\nat,percent")
                rows = con.execute("SELECT ts,pct FROM spo2 WHERE ts BETWEEN ? AND ? ORDER BY ts", (s, e)).fetchall()
                buf += [f"{fmt(t)},{p}" for t, p in rows]
                counts["spo2"] = len(rows)
            if "sleep" in types:
                buf.append("\n== sleep ==\nstart,end,minutes,deep_minutes")
                rows = con.execute("SELECT bed,wake,minutes,deep_min FROM sleep WHERE bed BETWEEN ? AND ? ORDER BY bed",
                                   (s, e)).fetchall()
                buf += [f"{fmt(b)},{fmt(w)},{m},{dm}" for b, w, m, dm in rows]
                counts["sleep"] = len(rows)
            csv = "\n".join(buf) + "\n"
            return {"rows": counts, "bytes": len(csv), "csv": csv[:200000]}

        raise ValueError(f"unknown tool {name}")


# ------------------------------------------------------------------ HTTP/MCP
# ---------------------------------------------------------------- 鉴权

def _bearer(header_value: object) -> str | None:
    if not isinstance(header_value, str):
        return None
    scheme, _, raw = header_value.strip().partition(" ")
    if scheme.lower() != "bearer":
        return None
    raw = raw.strip()
    return raw or None


def _static_principal(label: str, owner: int, *, user_id: int = 0,
                      data_mode: str = "admin", token_id=None,
                      tool_policy_json=None, window_json=None,
                      user_tool_policy_json=None, user_window_json=None,
                      source="db"):
    """静态 key 的 principal。

    ``owner`` 是数据分区（shared 用户为 0）；``user_id`` 是 key 归属人，
    ``data_mode`` 是归属人的模式，供 Task 5/6 复核。管理员/环境变量 key 为 0。
    """
    return {
        "kind": "static",
        "auth": "static",
        "source": source,
        "token_id": token_id,
        "label": label,
        "username": label,
        "user_id": user_id,
        "owner": owner,
        "data_mode": data_mode,
        "is_admin": user_id == 0,
        "subject": auth.ADMIN_SUBJECT if user_id == 0 else auth.subject_for_user(user_id),
        "scopes": None,
        "scope": "",
        "tool_policy_json": tool_policy_json,
        "window_json": window_json,
        # 静态 key 若属于某个用户，还要同时满足该用户的 policy/window。
        "user_tool_policy_json": user_tool_policy_json,
        "user_window_json": user_window_json,
    }


def resolve_principal(auth_header: object, handler=None, *, touch: bool = True):
    """Authorization 头 → principal（OAuth 优先，其次静态 key）；无效返回 ``None``。

    OAuth 访问令牌以 ``oa_`` 开头，按当前规范资源（``<base>/mcp``）校验；
    静态 key 先查数据库（``agent_tokens``），再回退到模块加载时的环境变量，
    这样首次导入失败也不会让现有客户端掉线。
    """
    raw = _bearer(auth_header)
    if raw is None:
        return None
    if raw.startswith(oauth.ACCESS_PREFIX):
        canonical = web.resource_url(handler)
        if canonical:
            try:
                with db(0) as con:
                    principal = oauth.verify_access(con, raw, canonical)
                if principal is not None:
                    return principal
            except Exception as e:  # noqa: BLE001
                print(f"[auth] oauth verify failed: {type(e).__name__}", flush=True)
        # 失败则继续按静态 key 查：静态 key 也允许以 oa_ 开头，不能直接判死。
    record = None
    imported = False
    principal = None
    try:
        with db(0) as con:
            record = auth.static_token_lookup(con, raw, touch=touch)
            imported = bool(auth.settings_get(con, "tokens_imported"))
            if record is not None:
                owner_id = int(record["owner_user_id"])
                user = auth.get_user(con, owner_id) if owner_id else None
                # 归属用户已停用/删除时，静态 key 立即不能认证（Task 6 还会撤销）。
                if owner_id and (user is None or not user["enabled"]):
                    record = None
                else:
                    data_mode = "admin"
                    owner = 0
                    user_tool_policy_json = None
                    user_window_json = None
                    if owner_id:
                        data_mode = user["data_mode"]
                        owner = owner_id if data_mode == "own" else 0
                        user_tool_policy_json = user.get("tool_policy_json")
                        user_window_json = user.get("window_json")
                    principal = _static_principal(
                        str(record["label"]), owner, user_id=owner_id,
                        data_mode=data_mode, token_id=record["id"],
                        tool_policy_json=record["tool_policy_json"],
                        window_json=record["window_json"],
                        user_tool_policy_json=user_tool_policy_json,
                        user_window_json=user_window_json)
    except Exception as e:  # noqa: BLE001
        print(f"[auth] static token lookup failed: {type(e).__name__}", flush=True)
    if principal is not None:
        return principal
    # 导入完成后数据库是唯一依据：环境变量里的 key 被撤销后不能借回退复活。
    if not imported:
        name = match_agent(raw)
        if name is not None:
            return _static_principal(name, 0, user_id=0, data_mode="admin",
                                     source="env")
    return None


def auth_required() -> bool:
    """是否需要鉴权。

    只要曾经配置过（用户/key/OAuth 客户端/管理员密码），即使后来全部撤销也
    继续要求鉴权；数据库读失败同样 fail-closed。只有“从未配置过”的旧库才保持
    匿名开放，避免升级后突然没人能连。
    """
    if AUTH_ON:
        return True
    try:
        with db(0) as con:
            return auth.auth_configured(con)
    except Exception as e:  # noqa: BLE001
        print(f"[auth] auth_required fallback: {type(e).__name__}", flush=True)
        return True


def _principal_actor(principal: dict | None) -> str:
    if not principal:
        return "anonymous"
    for key in ("username", "label", "subject"):
        value = principal.get(key)
        if value:
            return str(value)
    return "anonymous"


def _audit_mcp(principal: dict | None, tool: str | None, result: str,
               reason: object = None) -> None:
    """MCP 调用/拒绝的最佳努力审计；审计故障不改变工具结果。"""
    payload = {"tool": tool}
    if reason is not None:
        payload["reason"] = getattr(reason, "value", str(reason))
    try:
        with db(0) as con:
            audit.append(con, "mcp", _principal_actor(principal), "mcp.call",
                         payload, result, tool=tool)
    except Exception as exc:  # noqa: BLE001
        print(f"[audit] mcp.call failed: {type(exc).__name__}", flush=True)


def _audit_ingest(principal: dict | None, result: str,
                  reason: object = None) -> None:
    """``/ingest`` 的最佳努力审计；不记录 token、请求体或健康数据。"""
    payload: dict[str, object] = {}
    if reason is not None:
        payload["reason"] = getattr(reason, "value", str(reason))
    try:
        with db(0) as con:
            audit.audit_event(
                con, "ingest", actor=_principal_actor(principal), kind="ingest",
                payload=payload, result=result, tool="ingest")
    except Exception as exc:  # noqa: BLE001
        print(f"[audit] ingest failed: {type(exc).__name__}", flush=True)


def rpc(msg: dict, principal: dict | None = None):
    mid = msg.get("id")
    method = msg.get("method")
    if mid is None:
        return None
    if method == "initialize":
        return {"jsonrpc": "2.0", "id": mid, "result": {
            "protocolVersion": (msg.get("params") or {}).get("protocolVersion", "2025-06-18"),
            "capabilities": {"tools": {}},
            "serverInfo": {"name": "hc-health-mcp", "version": "1.0"}}}
    if method == "ping":
        return {"jsonrpc": "2.0", "id": mid, "result": {}}
    if method == "tools/list":
        tools = policy.allowed_tools(principal, tool_list())
        return {"jsonrpc": "2.0", "id": mid, "result": {"tools": tools}}
    if method == "tools/call":
        p = msg.get("params") or {}
        name = p.get("name")
        arguments = p.get("arguments")
        if arguments is None:
            arguments = {}
        allowed, reason = policy.authorize(principal, name, now=int(time.time()),
                                            args=arguments)
        if not allowed:
            detail = (policy.window_denial_detail(principal)
                      if reason is policy.Reason.WINDOW_CLOSED else None)
            _audit_mcp(principal, name, "denied", reason=reason)
            return {"jsonrpc": "2.0", "id": mid, "error": {
                "code": -32003,
                "message": policy.reason_message(reason, detail)}}
        owner_token = None
        if principal is not None:
            owner_token = context.set_owner(policy.owner_for(principal))
        try:
            out = call_tool(name, arguments)
            _audit_mcp(principal, name, "ok")
            text = out if isinstance(out, str) else json.dumps(out, ensure_ascii=False, indent=2)
            return {"jsonrpc": "2.0", "id": mid,
                    "result": {"content": [{"type": "text", "text": text}]}}
        except Exception as e:  # noqa: BLE001
            _audit_mcp(principal, name, "error", reason=type(e).__name__)
            return {"jsonrpc": "2.0", "id": mid,
                    "error": {"code": -32603, "message": f"{type(e).__name__}: {e}"}}
        finally:
            if owner_token is not None:
                context.reset_owner(owner_token)
    return {"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": f"unknown method {method}"}}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "hc-health-mcp/1.0"

    def log_message(self, fmt_, *a):  # 安静点
        sys.stderr.write("%s - %s agent=%s\n" % (self.address_string(), fmt_ % a, getattr(self, "_agent", "-")))

    def _authenticate(self):
        """解析 Bearer → principal；无凭据且未配置任何 key 时回到旧版开放模式。"""
        self._agent = "-"
        self._principal = None
        header = self.headers.get("Authorization", "")
        principal = resolve_principal(header, self)
        if principal is not None:
            self._principal = principal
            self._agent = str(principal.get("label") or principal.get("username") or "-")
            return principal
        if not header.strip() and not auth_required():
            self._principal = {"kind": "anonymous", "auth": "none", "owner": 0,
                               "is_admin": False, "scopes": None, "username": "anonymous"}
            return self._principal
        return None

    def _read_limited(self, limit):
        """读请求体；超过上限返回 ``None``（连接随即关闭，避免残包）。"""
        try:
            n = int(self.headers.get("Content-Length", "0") or 0)
        except (TypeError, ValueError):
            n = 0
        if n < 0:
            n = 0
        if n > limit:
            self.close_connection = True
            return None
        try:
            return self.rfile.read(n) if n else b""
        except Exception:  # noqa: BLE001
            return b""
        finally:
            self._body_read = True

    def _drain(self, limit=MAX_MCP_BODY):
        """丢掉未读请求体：否则 keep-alive 连接上残包会被当成下一个请求 → 日志满屏 400。"""
        if getattr(self, "_body_read", False):
            return
        try:
            n = int(self.headers.get("Content-Length", "0") or 0)
        except (TypeError, ValueError):
            n = 0
        if n > limit:
            self.close_connection = True
            return
        if n > 0:
            try:
                self.rfile.read(n)
            except Exception:  # noqa: BLE001
                pass
            finally:
                self._body_read = True

    def _deny(self, code=403, body=b'{"error":"forbidden"}', *, challenge=False):
        self._drain()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        metadata_url = web.metadata_url(self) if challenge else None
        if metadata_url:
            self.send_header(
                "WWW-Authenticate",
                f'Bearer resource_metadata="{metadata_url}"')
        self.send_header("Connection", "close")
        self.close_connection = True
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        self._body_read = False
        if web.handle(self):
            return
        if self.path.split("?")[0] == "/health":
            with db() as con:
                last_sync = get_state(con, "last_sync")
            body = json.dumps({"ok": True, "last_sync": last_sync}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self._deny(405, b'{"error":"use POST /mcp"}')

    def do_POST(self):
        self._body_read = False
        if web.handle(self):
            return
        if self.path.split("?")[0] == "/ingest":
            principal = self._authenticate()
            if principal is None or principal.get("kind") == "anonymous":
                return self._deny(401, b'{"error":"unauthorized"}')
            kind = principal.get("kind")
            if kind == "oauth":
                _audit_ingest(principal, "denied", policy.Reason.OAUTH_INGEST)
                return self._deny(403, b'{"error":"ingest requires a static token"}')
            if kind not in ("static", "session"):
                _audit_ingest(principal, "denied", policy.Reason.PRINCIPAL_INVALID)
                return self._deny(403, b'{"error":"ingest requires a static token"}')
            raw = self._read_limited(MAX_INGEST_BODY)
            if raw is None:
                return self._deny(413, b'{"error":"payload too large"}')
            try:
                doc = json.loads(raw.decode() or "{}")
            except Exception:  # noqa: BLE001
                return self._deny(400, b'{"error":"bad json"}')
            if not isinstance(doc, dict):
                return self._deny(400, b'{"error":"bad json"}')
            allowed, reason = policy.authorize(
                principal, "ingest", now=int(time.time()),
                args={"items": doc.get("items") or []})
            if not allowed:
                _audit_ingest(principal, "denied", reason)
                detail = (policy.window_denial_detail(principal)
                          if reason is policy.Reason.WINDOW_CLOSED else None)
                body = json.dumps({
                    "error": policy.reason_message(reason, detail),
                    "reason": reason.value,
                }, ensure_ascii=False).encode()
                return self._deny(403, body)
            owner_token = context.set_owner(int(principal.get("owner", 0) or 0))
            try:
                out = ingest(doc)
            except Exception as e:  # noqa: BLE001
                _audit_ingest(principal, "error")
                return self._deny(400, json.dumps({"error": str(e)}).encode())
            finally:
                context.reset_owner(owner_token)
            _audit_ingest(principal, "ok")
            body = json.dumps(out, ensure_ascii=False).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if self.path.split("?")[0] != "/mcp":
            return self._deny(404)
        principal = self._authenticate()
        if principal is None:
            return self._deny(401, b'{"error":"unauthorized"}', challenge=True)
        raw = self._read_limited(MAX_MCP_BODY)
        if raw is None:
            return self._deny(413, b'{"error":"payload too large"}')
        try:
            msg = json.loads(raw.decode() or "{}")
        except Exception:
            return self._deny(400, b'{"error":"bad json"}')
        resp = rpc(msg, principal)
        if resp is None:
            self.send_response(202)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        body = json.dumps(resp, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def ingest(doc: dict) -> dict:
    """手机/别的来源 → 本服务。**多来源**：每行都带 source，同一时刻不同来源各存一行。

    支持类型：heart_rate / spo2 / steps / calories / sleep / stress / weight / workout。
    `source` 缺省时：workout/weight 用 item.origin，其它用 'phone'。upsert 主键 (ts|bed|start, source)。
    """
    items = doc.get("items") or []
    acc = {}
    rej = 0
    with db() as con:
        for it in items:
            t = (it.get("type") or "").lower()
            src = (it.get("source") or it.get("origin") or "phone").strip() or "phone"
            try:
                if t == "workout":
                    con.execute("INSERT OR REPLACE INTO workout(start,end,minutes,exercise_type,title,origin,source) "
                                "VALUES(?,?,?,?,?,?,?)",
                                (int(it["start"]), int(it["end"]), int(it.get("minutes") or 0),
                                 int(it.get("exercise_type") or 0), str(it.get("title") or ""),
                                 str(it.get("origin") or src), src))
                elif t == "weight":
                    con.execute("INSERT OR REPLACE INTO weight(ts,kg,origin,source) VALUES(?,?,?,?)",
                                (int(it["ts"]), float(it["kg"]), str(it.get("origin") or src), src))
                elif t == "heart_rate":
                    con.execute("INSERT OR REPLACE INTO heart_rate(ts,bpm,source) VALUES(?,?,?)",
                                (int(it["ts"]), int(it["bpm"]), src))
                elif t == "spo2":
                    con.execute("INSERT OR REPLACE INTO spo2(ts,pct,source) VALUES(?,?,?)",
                                (int(it["ts"]), float(it["pct"]), src))
                elif t == "steps":
                    con.execute("INSERT OR REPLACE INTO steps(ts,steps,distance_m,source) VALUES(?,?,?,?)",
                                (int(it["ts"]), int(it.get("steps") or 0),
                                 float(it.get("distance_m") or 0), src))
                elif t == "calories":
                    con.execute("INSERT OR REPLACE INTO calories(ts,kcal,source) VALUES(?,?,?)",
                                (int(it["ts"]), float(it.get("kcal") or 0), src))
                elif t == "stress":
                    con.execute("INSERT OR REPLACE INTO stress(ts,stress,source) VALUES(?,?,?)",
                                (int(it["ts"]), int(it.get("stress") or 0), src))
                elif t == "sleep":
                    bed, wake = int(it["start"]), int(it["end"])
                    con.execute("INSERT OR REPLACE INTO sleep(bed,wake,minutes,deep_min,source) VALUES(?,?,?,?,?)",
                                (bed, wake, int(it.get("minutes") or (wake - bed) // 60),
                                 int(it.get("deep_minutes") or 0), src))
                else:
                    rej += 1
                    continue
                acc[t] = acc.get(t, 0) + 1
            except Exception:  # noqa: BLE001
                rej += 1
        con.commit()
        set_state(con, "last_ingest", json.dumps(
            {"at": fmt(int(time.time())), "accepted": acc, "rejected": rej}))
    return {"ok": True, "accepted": acc, "rejected": rej}


def clear_stale_backfill():
    """进程重启后，DB 里可能留着 running=true 的假状态（线程已随进程死掉）。"""
    try:
        with db() as con:
            st = json.loads(get_state(con, "backfill_state", "{}") or "{}")
            if st.get("running"):
                st["running"] = False
                st["ok"] = False
                st["note"] = "进程重启导致中断；再调 backfill 会从断点继续（resume 默认 true）"
                set_state(con, "backfill_state", json.dumps(st))
                print("[backfill] 清理上次未完成的 running 状态", flush=True)
    except Exception:  # noqa: BLE001
        pass


def startup():
    """建 schema、引导管理员/导入静态 key；任何失败都不能把 MCP 弄挂。"""
    try:
        schema.ensure_all()
    except Exception as e:  # noqa: BLE001
        print(f"[schema] 分区初始化失败: {e}", flush=True)
    # 两步各自独立提交：env 导入失败不能把刚建好的管理员密码一起回滚。
    try:
        with db(0) as con:
            result = auth.bootstrap_admin(con)
            if result.created:
                audit.append(con, "system", "system", "auth.bootstrap",
                             {"status": result.status}, "ok")
        if result.created:
            print("[auth] 管理员密码已初始化", flush=True)
        elif result.status != "already_configured":
            print(f"[auth] 管理员未初始化：{result.status}", flush=True)
    except Exception as e:  # noqa: BLE001
        # 只记类型：异常文本可能包含配置内容；/mcp 与 /health 继续用旧路径。
        print(f"[auth] 管理员引导跳过（{type(e).__name__}）", flush=True)
    try:
        with db(0) as con:
            imported = auth.import_env_tokens(con)
            # 老库没有 auth_configured marker：有历史用户/key/客户端时补上。
            auth.auth_configured(con)
            if imported:
                audit.append(con, "system", "system", "auth.import",
                             {"count": imported, "source": "env"}, "ok")
        if imported:
            print(f"[auth] 从环境变量导入 {imported} 个静态 key", flush=True)
    except Exception as e:  # noqa: BLE001
        print(f"[auth] 静态 key 导入跳过（{type(e).__name__}）", flush=True)
    for warning in web.startup_warnings():
        print(f"[web] {warning}", flush=True)
    clear_stale_backfill()


def audit_cleanup_once():
    """后台保留清理；失败只记类型，不影响服务。"""
    try:
        with db(0) as con:
            result = audit.cleanup(con)
        if result.get("deleted"):
            print(f"[audit] 清理 {result['deleted']} 条过期记录 "
                  f"(anchor={result.get('anchor')})", flush=True)
        return result
    except Exception as exc:  # noqa: BLE001
        print(f"[audit] 清理失败: {type(exc).__name__}", flush=True)
        return None


def audit_cleanup_loop():
    """启动后每小时清理一次；AUDIT_RETENTION_DAYS 默认 90 天。"""
    while True:
        audit_cleanup_once()
        time.sleep(3600)


def main():
    _reject_placeholder_secrets()
    startup()
    threading.Thread(target=fetch_loop, daemon=True).start()
    threading.Thread(target=audit_cleanup_loop, daemon=True).start()
    port = int(os.environ.get("PORT", "18927"))
    srv = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    legacy = "+legacy" if API_TOKEN else ""
    print(f"hc-health-mcp listening on :{port} "
          f"(tokens={len(AGENT_TOKENS)}{legacy})" if AUTH_ON else
          f"hc-health-mcp listening on :{port} (auth=off)", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
