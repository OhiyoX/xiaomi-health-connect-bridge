"use strict";
/* ============================================================
   HC Health 管理后台 · 数据层（NAS 与 App 共用）
   ------------------------------------------------------------
   这里只有三件事：
   1. 标签映射（状态 / 来源 / 角色 / 睡眠标记 / 权限 scope）；
   2. `normalize()`：把 `AdminAPI.state()` 的原始 JSON 收敛成固定 `DATA` 形状，
      空状态、缺字段、时间格式差异都在这一处吸收（契约见 admin-ui/API.md）；
   3. 纯格式化与几何（相对时间 / 数字 / sparkline 路径 / 覆盖档位）。

   不放：任何 mock 数据、任何 HTML 拼装（在 render.js）、任何 IO（在 adapter.js）。
   时间戳一律归一成 epoch 毫秒，格式化为**浏览器/设备本地时区**。
   ============================================================ */
const HcbData = (function () {
  const H = 3600e3, D = 24 * H, M = 60e3;

  // ---------------------------------------------------------------- 标签
  const LABELS = {
    source: {
      apple_health: "Apple", huawei_health: "华为", manual: "手动", garmin: "Garmin",
      "xiaomi-cloud": "小米云", xiaomi_cloud: "小米云", mi_cloud: "小米云",
      health_connect: "Health Connect", hc: "Health Connect", "?": "未知",
      "com.fitbit.FitbitMobile": "Fitbit", "id.harness.hcbridge": "hc-bridge",
      fitbit: "Fitbit", hcbridge: "hc-bridge",
      "com.google.android.apps.fitness": "Google Fit",
      "com.mi.health": "小米健康", "com.huawei.health": "华为运动健康",
      "com.garmin.android.apps.connectmobile": "Garmin",
    },
    role: { admin: "管理员", viewer: "只读", service: "服务", user: "普通用户" },
    userStatus: { active: ["有效", "ok"], disabled: ["已停用", "off"] },
    tokenStatus: {
      active: ["有效", "ok"], revoked: ["已撤销", "danger"], expired: ["已过期", "amber"],
    },
    oauthStatus: {
      active: ["有效", "ok"], disabled: ["已停用", "off"],
      pending: ["待批准", "amber"], revoked: ["已吊销", "danger"],
    },
    outcome: { ok: ["成功", "ok"], fail: ["失败", "danger"] },
    sleepFlag: {
      missing: "无样本", short: "偏短", long: "偏长", late: "晚睡",
      late_bed: "入睡偏晚", fragile: "易醒", fragmented: "碎片化", invalid: "异常",
    },
    scopes: ["health.read", "health.write"],
    tunnel: { off: "关闭", quick: "quick", named: "named" },
  };

  /** 隧道模式（顺序即界面分段控件的顺序）。 */
  const TUNNEL_MODES = ["off", "quick", "named"];

  // ---------------------------------------------------------------- 小工具
  const obj = (v) => (v && typeof v === "object" && !Array.isArray(v) ? v : {});
  const arr = (v) => (Array.isArray(v) ? v : []);
  const isNum = (v) => typeof v === "number" && isFinite(v);
  const numOr = (v, d) => (isNum(v) ? v : d);
  const str = (v, d) => (typeof v === "string" && v !== "" ? v : (d === undefined ? "" : d));
  const clamp01 = (v) => Math.max(0, Math.min(1, v));

  function strList(v) {
    return arr(v).map((x) => (typeof x === "string" ? x : str(obj(x).source, "")))
      .filter((x) => x !== "");
  }

  /** 时间戳：epoch 毫秒 / epoch 秒 / ISO 字符串 / 空。统一成毫秒或 null。 */
  function stamp(v) {
    if (v === null || v === undefined || v === "") return null;
    if (isNum(v)) return v > 1e11 ? Math.round(v) : Math.round(v * 1000);
    if (typeof v === "string") {
      const t = Date.parse(v);
      return isNaN(t) ? null : t;
    }
    return null;
  }

  const pad = (n) => String(n).padStart(2, "0");
  const now = () => Date.now();

  function rel(v) {
    const t = stamp(v);
    if (t === null) return "—";
    const d = now() - t;
    if (d < 0) {
      const f = -d;
      if (f < H) return Math.round(f / M) + " 分钟后";
      if (f < D) return Math.round(f / H) + " 小时后";
      return Math.round(f / D) + " 天后";
    }
    if (d < 60e3) return "刚刚";
    if (d < H) return Math.round(d / M) + " 分钟前";
    if (d < D) return Math.round(d / H) + " 小时前";
    if (d < 30 * D) return Math.round(d / D) + " 天前";
    return fmtDate(t);
  }

  function fmtDate(v) {
    const t = stamp(v);
    if (t === null) return "—";
    const d = new Date(t);
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  }

  function fmt(v) {
    const t = stamp(v);
    if (t === null) return "—";
    const d = new Date(t);
    return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  }

  function fmtFull(v) {
    const t = stamp(v);
    if (t === null) return "—";
    const d = new Date(t);
    return `${fmtDate(t)} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
  }

  const num = (n) => (isNum(n) ? n.toLocaleString("zh-CN") : "—");
  const short = (id) => {
    const s = String(id === null || id === undefined ? "" : id);
    return s.length > 14 ? s.slice(0, 10) + "…" : s;
  };

  /** 角色由 scopes 推导（与 NAS 后端同一口径）。 */
  function deriveRole(scopes) {
    const list = arr(scopes);
    if (list.indexOf("admin") >= 0) return "admin";
    if (list.indexOf("health.write") >= 0) return "service";
    return "viewer";
  }

  function outcomeOf(v) {
    const s = String(v === null || v === undefined ? "" : v).toLowerCase();
    return s === "ok" || s === "approved" || s === "success" ? "ok" : "fail";
  }

  // ------------------------------------------------------------ 规范化：行
  function mapUser(raw) {
    const u = obj(raw);
    const id = str(u.id, str(u.username, ""));
    const scopes = strList(u.scopes);
    const display = str(u.display, str(u.displayName, str(u.display_name, str(u.username, id))));
    return {
      id,
      username: str(u.username, id),
      display: display || "—",
      role: str(u.role, deriveRole(scopes)),
      status: str(u.status, u.enabled === false ? "disabled" : "active"),
      totp: u.totp === true || u.totpEnabled === true,
      lastLogin: stamp(u.lastLogin),
      createdAt: stamp(u.createdAt),
      sessions: u.sessions === null || u.sessions === undefined ? null : numOr(u.sessions, 0),
      note: str(u.note, ""),
      scopes,
    };
  }

  function mapSession(raw) {
    const s = obj(raw);
    return {
      id: str(s.id, ""),
      userId: str(s.userId, str(s.user_id, "")),
      device: str(s.device, "—"),
      ip: str(s.ip, "—"),
      loc: str(s.loc, "—"),
      createdAt: stamp(s.createdAt),
      lastSeen: stamp(s.lastSeen),
      current: s.current === true,
      expiresAt: stamp(s.expiresAt),
    };
  }

  /** 策略 / 时间窗：JSON 文本（也接受已解析的值），空 ⇒ null（= 不限）。 */
  function jsonText(v) {
    if (typeof v === "string") return v.trim() === "" ? null : v;
    if (v && typeof v === "object") {
      try { return JSON.stringify(v); } catch (e) { return null; }
    }
    return null;
  }

  function mapToken(raw) {
    const t = obj(raw);
    const expires = stamp(t.expires);
    let status = str(t.status, "");
    if (!status) {
      status = t.revokedAt ? "revoked" : (expires !== null && expires <= now() ? "expired" : "active");
    }
    return {
      id: str(t.id, ""),
      name: str(t.name, "—"),
      prefix: str(t.prefix, "—"),
      scopes: strList(t.scopes),
      status,
      userId: str(t.userId, ""),
      createdAt: stamp(t.createdAt),
      lastUsed: stamp(t.lastUsed),
      expires,
      family: str(t.family, ""),
      kind: str(t.kind, ""),
      policy: jsonText(t.policy),
      window: jsonText(t.window),
    };
  }

  function mapOauth(raw) {
    const c = obj(raw);
    const clientId = str(c.clientId, str(c.client_id, str(c.id, "")));
    return {
      id: str(c.id, clientId),
      name: str(c.name, str(c.clientName, clientId || "—")),
      clientId,
      kind: str(c.kind, c.secretHash ? "confidential" : "public"),
      redirects: strList(c.redirects),
      status: str(c.status, "active"),
      createdAt: stamp(c.createdAt),
      lastAuth: stamp(c.lastAuth || c.lastUsedAt),
      policy: jsonText(c.policy),
      window: jsonText(c.window),
    };
  }

  function mapPending(raw) {
    const p = obj(raw);
    return {
      id: str(p.id, ""),
      client: str(p.client, str(p.clientId, "—")),
      clientId: str(p.clientId, ""),
      redirect: str(p.redirect, ""),
      scopes: strList(p.scopes),
      createdAt: stamp(p.createdAt),
      expiresAt: stamp(p.expiresAt),
    };
  }

  function mapCoverage(raw) {
    const c = obj(raw);
    const days30 = numOr(c.days30, numOr(c.days_30d, null));
    const rows = numOr(c.rows, numOr(c.records30d, numOr(c.records, null)));
    const pct = isNum(c.pct) ? clamp01(c.pct)
      : (days30 === null ? null : clamp01(days30 / COVERAGE_DAYS));
    return {
      type: str(c.type, ""),
      label: str(c.label, str(c.type, "—")),
      rows,
      sources: strList(c.sources),
      days30,
      pct,
      lastAt: stamp(c.lastAt || c.last),
    };
  }

  function mapSleep(raw) {
    const s = obj(raw);
    const date = str(s.date, "");
    return {
      id: str(s.id, date),
      date,
      bed: str(s.bed, "—"),
      wake: str(s.wake, "—"),
      hours: numOr(s.hours, null),
      score: numOr(s.score, null),
      source: str(s.source, "—"),
      flags: strList(s.flags),
      note: str(s.note, ""),
    };
  }

  /** 覆盖页的窗口固定 30 天（与 stats.coverage 口径一致）。 */
  const COVERAGE_DAYS = 30;

  const METRIC_DEFAULTS = {
    steps: { label: "步数", unit: "步", decimals: 0 },
    heart_rate: { label: "心率（日均）", unit: "bpm", decimals: 0 },
    sleep: { label: "睡眠时长", unit: "h", decimals: 1 },
  };

  function seriesOf(m) {
    const raw = arr(m.series).length ? arr(m.series)
      : arr(m.values).length ? arr(m.values)
        : arr(m.points).map((p) => (p && typeof p === "object" ? p.value : p));
    return raw.map((v) => (isNum(v) ? v : null));
  }

  function mapMetric(key, raw) {
    const m = obj(raw);
    const def = METRIC_DEFAULTS[key] || { label: key, unit: "", decimals: 0 };
    const series = seriesOf(m);
    const known = series.filter((v) => v !== null);
    const last = known.length ? known[known.length - 1] : null;
    const avg = known.length ? known.reduce((a, b) => a + b, 0) / known.length : null;
    return {
      label: str(m.label, def.label),
      unit: str(m.unit, def.unit),
      decimals: numOr(m.decimals, def.decimals),
      latest: numOr(m.latest, last),
      avg: numOr(m.avg, avg),
      series,
      hasData: known.length > 0,
    };
  }

  function mapAuditRow(raw) {
    const a = obj(raw);
    return {
      id: str(a.id, ""),
      at: stamp(a.at),
      actor: str(a.actor, "—"),
      action: str(a.action, "—"),
      target: str(a.target, ""),
      outcome: outcomeOf(a.outcome || a.result),
      detail: str(a.detail, ""),
      ip: str(a.ip, ""),
    };
  }

  function mapCaps(raw) {
    const c = obj(raw);
    return {
      users: c.users === "single" ? "single" : "full",
      sessions: c.sessions === "single" ? "single" : "full",
      settings: c.settings !== false,
      // App 专属段（NAS 不给 ⇒ 整段不渲染）
      localAccess: c.localAccess === true,
      policies: c.policies === true,
      followTheme: c.followTheme === true,
    };
  }

  /** `localAccess`：本机 MCP 服务器的鉴权 / 隧道 / 探活状态。没有任何秘密（token 只有 hasToken）。 */
  function mapLocalAccess(raw) {
    const l = obj(raw);
    const mode = str(l.tunnelMode, "off");
    const probe = obj(l.lastProbe);
    const hasProbe = l.lastProbe !== null && typeof l.lastProbe === "object"
      && (typeof probe.ok === "boolean" || probe.at !== undefined);
    return {
      requireAuth: l.requireAuth === true,
      allowLoopbackNoAuth: l.allowLoopbackNoAuth === true,
      confirmNewClients: l.confirmNewClients === true,
      tunnelMode: TUNNEL_MODES.indexOf(mode) >= 0 ? mode : "off",
      publicBase: str(l.publicBase, "").trim() || null,
      hasToken: l.hasToken === true,
      lastProbe: hasProbe
        ? { ok: probe.ok === true, at: stamp(probe.at), detail: str(probe.detail, "") }
        : null,
    };
  }

  function mapSecurity(raw, caps) {
    const s = obj(raw);
    return {
      totpEnabled: s.totpEnabled === true,
      totpEnrolledAt: stamp(s.totpEnrolledAt),
      passwordChangedAt: stamp(s.passwordChangedAt),
      passwordSet: s.passwordSet === true,
      // App 只报分钟时折算成小时；显示层再把 <1 小时换回分钟
      sessionTtlHours: numOr(s.sessionTtlHours,
        isNum(s.sessionTtlMinutes) ? s.sessionTtlMinutes / 60 : null),
      allowPasswordLogin: s.allowPasswordLogin !== false,
      requireTotp: s.requireTotp === true,
      maxFailedLogins: numOr(s.maxFailedLogins, null),
      lockoutMinutes: numOr(s.lockoutMinutes, null),
      settingsEditable: typeof s.settingsEditable === "boolean" ? s.settingsEditable : caps.settings,
    };
  }

  function mapSync(overview, root) {
    const sync = obj(overview.sync || root.sync);
    return {
      lastSyncAt: stamp(sync.lastSyncAt),
      lastSyncSource: str(sync.lastSyncSource, str(sync.last_source, "")),
      lastSyncRows: numOr(sync.lastSyncRows, 0),
      lagMin: numOr(sync.lagMin, numOr(sync.lag_min, null)),
      pipeline: str(sync.pipeline, "unknown"),
      lastSyncResult: str(sync.lastSyncResult, ""),
    };
  }

  function mapBackfill(overview, root) {
    const sync = obj(overview.sync || root.sync);
    const b = obj(overview.backfill || sync.backfill || root.backfill);
    const progress = isNum(b.progress) ? clamp01(b.progress) : null;
    return {
      status: str(b.status, b.running === true ? "running" : "idle"),
      progress,
      rangeFrom: str(b.rangeFrom, str(b.range_from, "")),
      rangeTo: str(b.rangeTo, str(b.range_to, "")),
      lastRun: stamp(b.lastRun || b.last_run || b.done_at),
      note: str(b.note, ""),
    };
  }

  // ------------------------------------------------------------ 规范化：整包
  function normalize(raw) {
    const root = obj(raw);
    const caps = mapCaps(root.caps);
    const days = numOr(obj(root.metrics).days, 30);

    const sessions = arr(root.sessions).map(mapSession);
    const users = arr(root.users).map(mapUser);
    const counts = {};
    for (const s of sessions) counts[s.userId] = (counts[s.userId] || 0) + 1;
    for (const u of users) if (u.sessions === null) u.sessions = counts[u.id] || 0;

    const coverage = arr(root.coverage).map(mapCoverage);
    const auditIn = root.audit;
    const auditRows = (Array.isArray(auditIn) ? auditIn : arr(obj(auditIn).rows)).map(mapAuditRow);
    let chainOk = null;
    if (Array.isArray(auditIn)) {
      if (typeof root.auditChainOk === "boolean") chainOk = root.auditChainOk;
    } else if (typeof obj(auditIn).chainOk === "boolean") {
      chainOk = obj(auditIn).chainOk;
    }

    const metricsIn = obj(root.metrics);
    const overview = obj(root.overview);
    const sync = mapSync(overview, root);
    const backfill = mapBackfill(overview, root);
    const security = mapSecurity(root.security, caps);
    const me = obj(root.me);
    const firstUser = users[0] || {};

    const audit = {
      rows: auditRows,
      chainOk,
      fails24h: auditRows.filter((a) => a.outcome === "fail" && a.at !== null && now() - a.at < D).length,
    };

    const kpiIn = obj(overview.kpis);
    const heart = coverage.find((c) => c.type === "heart_rate");
    const kpis = {
      heartRows: numOr(kpiIn.heartRows, heart ? heart.rows : null),
      coverageTypes: numOr(kpiIn.coverageTypes, coverage.length),
      lowCoverage: numOr(kpiIn.lowCoverage,
        coverage.filter((c) => c.pct !== null && c.pct < 0.9).length),
      activeSessions: numOr(kpiIn.activeSessions,
        sessions.filter((s) => s.lastSeen !== null && now() - s.lastSeen < D).length),
      activeUsers: numOr(kpiIn.activeUsers, users.filter((u) => u.status === "active").length),
      auditFails24h: numOr(kpiIn.auditFails24h, audit.fails24h),
    };

    return {
      version: str(root.version, ""),
      me: {
        id: str(me.id, str(firstUser.id, "")),
        username: str(me.username, str(firstUser.username, "")),
        display: str(me.display, str(firstUser.display, "")),
        role: str(me.role, str(firstUser.role, "admin")),
      },
      caps,
      sync,
      backfill,
      kpis,
      users,
      sessions,
      tokens: arr(root.tokens).map(mapToken),
      oauth: arr(root.oauth).map(mapOauth),
      pending: arr(root.pending).map(mapPending),
      coverage,
      sleep: arr(root.sleep).map(mapSleep),
      metrics: {
        days,
        steps: mapMetric("steps", metricsIn.steps),
        heart_rate: mapMetric("heart_rate", metricsIn.heart_rate),
        sleep: mapMetric("sleep", metricsIn.sleep),
      },
      audit,
      security,
      localAccess: mapLocalAccess(root.localAccess),
      healthPending: root.healthPending === true,
      csrf: str(root.csrf, ""),
    };
  }

  // ------------------------------------------------------------ 当前状态
  let DATA = normalize({});
  let loaded = false;

  function set(raw) {
    DATA = normalize(raw);
    loaded = true;
    return DATA;
  }

  // ------------------------------------------------------- 覆盖条 / 折线几何
  function covLevel(pct) {
    if (!isNum(pct)) return "";
    return pct >= 0.9 ? "" : pct >= 0.7 ? "amber" : "danger";
  }

  /** sparkline 的 path：跳过缺值日（不画断点），少于 2 个点就没有折线。 */
  function sparkPaths(values, w, h, padY) {
    const vals = arr(values).filter((v) => isNum(v));
    if (vals.length < 2) return null;
    const min = Math.min.apply(null, vals);
    const max = Math.max.apply(null, vals);
    const span = max - min || 1;
    const step = (w || 240) / (vals.length - 1);
    const pts = vals.map((v, i) => [
      i * step,
      (h || 48) - (padY || 4) - ((v - min) / span) * ((h || 48) - (padY || 4) * 2),
    ]);
    const line = pts.map((p, i) => (i ? "L" : "M") + p[0].toFixed(1) + "," + p[1].toFixed(1)).join(" ");
    const area = `M0,${h || 48} ` + pts.map((p) => `L${p[0].toFixed(1)},${p[1].toFixed(1)}`).join(" ")
      + ` L${w || 240},${h || 48} Z`;
    return { line, area };
  }

  return {
    LABELS, TUNNEL_MODES, D, H, COVERAGE_DAYS,
    normalize, set, loaded: () => loaded, data: () => DATA,
    rel, fmtDate, fmt, fmtFull, num, short, stamp,
    covLevel, sparkPaths, deriveRole, outcomeOf,
    empty: () => normalize({}),
  };
})();
