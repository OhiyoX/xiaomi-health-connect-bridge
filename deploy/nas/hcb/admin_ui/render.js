"use strict";
/* ============================================================
   HC Health 管理后台 · 渲染层（NAS 与 App 共用）
   ------------------------------------------------------------
   - 页面与抽屉全部是**纯函数**：输入 `view`（路由 / 搜索 / 筛选）+ `HcbData.data()`，
     输出 HTML 字符串；不碰 DOM、不发请求、不改数据。
   - 所有用户数据都过 `esc()`；`innerHTML` 只拼接已经转义的值。
   - 只有 `view` 里的路由/搜索词是本地状态；其余全部来自 `AdminAPI.state()`。
   - 动作按钮统一 `data-act="<action name>"`，名字见 admin-ui/API.md。
   ============================================================ */
const HcbRender = (function () {
  const L = HcbData.LABELS;
  const D = () => HcbData.data();
  const esc = (s) => String(s === null || s === undefined ? "" : s)
    .replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

  const ic = (n, cls) => `<svg class="i ${cls || ""}" aria-hidden="true"><use href="#i-${esc(n)}"/></svg>`;
  const tag = (text, cls) => `<span class="tag ${esc(cls || "")}">${esc(text)}</span>`;
  const stSpan = (map, k) => {
    const row = map[k] || [k, "off"];
    return `<span class="st ${esc(row[1])}"><span class="dot"></span>${esc(row[0])}</span>`;
  };
  const num = (n) => HcbData.num(n);
  const rel = (t) => HcbData.rel(t);

  // ---------------------------------------------------------------- 基础件
  const NAV = [
    null, "工作台",
    ["overview", "概览", "home", "o"],
    null, "身份与接入",
    ["users", "用户", "users", "u"],
    ["sessions", "会话", "session", "e"],
    ["tokens", "静态令牌", "key", "k"],
    ["oauth", "OAuth 客户端", "app", "c"],
    null, "健康数据",
    ["coverage", "覆盖", "cover", "v"],
    ["sleep", "睡眠体检", "sleep", "p"],
    ["metrics", "指标趋势", "activity", "m"],
    null, "系统",
    ["audit", "审计", "list", "a"],
    ["security", "安全设置", "shield", "s"],
  ];
  const DRAWER_ROUTES = {
    users: "user", sessions: "session", tokens: "token",
    oauth: "oauth", coverage: "coverage", sleep: "sleep",
  };
  const ROUTES = ["overview", "users", "sessions", "tokens", "oauth",
    "coverage", "sleep", "metrics", "audit", "security"];

  function navCount(k) {
    const d = D();
    const map = {
      users: d.users.length,
      sessions: d.sessions.length,
      tokens: d.tokens.filter((t) => t.status === "active").length,
      oauth: d.oauth.filter((c) => c.status === "active").length,
      coverage: d.coverage.length,
      sleep: d.sleep.length,
      audit: d.audit.rows.length,
      pending: d.pending.length,
    };
    return map[k];
  }

  const pageBar = (title, extra, cnt) =>
    `<header class="bar"><h1>${esc(title)}${cnt === "" || cnt === undefined ? "" : `<span class="cnt">${esc(cnt)}</span>`}</h1><span class="grow"></span>${extra || ""}</header>`;

  const searchBox = (ph, val, key) =>
    `<label class="search">${ic("search")}<input data-search="${esc(key)}" placeholder="${esc(ph)}" value="${esc(val || "")}" enterkeyhint="search"><kbd class="hide-m">/</kbd></label>`;

  const moreBtn = (kind, id) =>
    `<button class="ibtn" data-menu="${esc(kind)}" data-id="${esc(id)}" aria-label="更多操作" title="更多操作">${ic("more")}</button>`;

  const emptyState = (icn, title, desc, action) =>
    `<div class="empty"><div class="ic">${ic(icn)}</div><b>${esc(title)}</b><span>${esc(desc || "")}</span>${action || ""}</div>`;

  const idc = (id, label) =>
    `<button class="idc" type="button" data-copy="${esc(id)}" title="${esc(id)} · 点击复制">${esc(label === undefined ? HcbData.short(id) : label)}${ic("copy")}</button>`;

  const srcPill = (s) => {
    const cls = String(s).split("_")[0].replace(/[^a-z0-9-]/gi, "");
    return `<span class="src-pill ${esc(cls)}" title="${esc(s)}">${esc(L.source[s] || s)}</span>`;
  };

  function table(cols, rows, empty) {
    if (!rows.length) return `<div class="tw">${empty}</div>`;
    const head = cols.map((c) => `<th class="${esc(c[2] || "")}">${c[0]}</th>`).join("");
    const colgroup = cols.map((c) => `<col${c[1] ? ` style="width:${esc(c[1])}"` : ""}>`).join("");
    return `<div class="tw hide-m"><div class="tbl-scroll"><table class="tbl"><colgroup>${colgroup}</colgroup><thead><tr>${head}</tr></thead><tbody>${rows.join("")}</tbody></table></div></div>`;
  }
  const mcards = (cards) => (cards.length ? `<div class="mcards">${cards.join("")}</div>` : "");

  const fields = (rows) => `<div class="grid2">${rows.join("")}</div>`;
  const fld = (label, input, help, span2) => {
    const m = /name="([^"]+)"/.exec(input);
    const id = m ? "f-" + m[1].replace(/[^a-zA-Z0-9_-]/g, "-") : "";
    const field = id ? input.replace(/<input /, `<input id="${id}" `) : input;
    return `<div class="fld"${span2 ? ' style="grid-column:1/-1"' : ""}><label${id ? ` for="${id}"` : ""}>${esc(label)}</label>${field}${help ? `<span class="help">${esc(help)}</span>` : ""}</div>`;
  };
  const inp = (name, opts) => {
    const o = opts || {};
    return `<input class="inp" name="${esc(name)}"${o.type ? ` type="${esc(o.type)}"` : ""}`
      + `${o.value ? ` value="${esc(o.value)}"` : ""}`
      + `${o.placeholder ? ` placeholder="${esc(o.placeholder)}"` : ""}`
      + `${o.autocomplete ? ` autocomplete="${esc(o.autocomplete)}"` : ""}`
      + `${o.required ? " required" : ""}${o.extra || ""}>`;
  };
  const textarea = (name, value, ph, rows) =>
    `<textarea class="inp" name="${esc(name)}" rows="${rows || 3}" placeholder="${esc(ph || "")}" style="height:auto;min-height:72px;padding:6px 8px;font:400 14px/20px var(--mono)">${esc(value || "")}</textarea>`;
  const scopeChips = (name, selected) => {
    const on = selected || [];
    return `<div class="chips">${L.scopes.map((s) =>
      `<label class="chip${on.indexOf(s) >= 0 ? " on" : ""}"><input type="checkbox" name="${esc(name)}" value="${esc(s)}"${on.indexOf(s) >= 0 ? " checked" : ""}> ${esc(s)}</label>`).join("")}</div>`;
  };
  const sect = (title, body, extra) =>
    `<div class="sect"><div class="sect-h"><h2>${esc(title)}</h2><span class="grow"></span>${extra || ""}</div>${body}</div>`;

  function sparkline(values, cls) {
    const p = HcbData.sparkPaths(values, 240, 48, 4);
    if (!p) {
      return `<div class="muted" style="font-size:13px;height:48px;display:flex;align-items:center">没有足够的日聚合数据</div>`;
    }
    return `<svg class="spark ${esc(cls || "")}" viewBox="0 0 240 48" preserveAspectRatio="none" aria-hidden="true"><path class="area" d="${p.area}"/><path class="line" d="${p.line}"/></svg>`;
  }

  const pctText = (p) => (typeof p === "number" ? Math.round(p * 100) + "%" : "—");

  /** 时长：够 1 小时按小时，否则按分钟（避免 0.083 小时这种显示）。 */
  const durText = (hours) => {
    if (typeof hours !== "number" || !isFinite(hours) || !(hours > 0)) return "—";
    if (hours >= 1) return num(hours) + " 小时";
    return Math.max(1, Math.round(hours * 60)) + " 分钟";
  };

  /** 策略 / 时间窗的只读单元：null = 不限，否则原样 JSON（已转义、可换行）。 */
  const jsonCell = (v) => (v === null
    ? '<span class="muted">不限（null）</span>'
    : `<span class="mono" style="display:block;white-space:pre-wrap;word-break:break-all">${esc(v)}</span>`);

  /** 开关行（按钮就是开关；动作名与目标状态都在 data-* 里，无内联事件）。 */
  const swRow = (act, on, label, desc) => `<div style="display:flex;align-items:center;gap:var(--s3);padding:8px 0">
    <div class="grow" style="min-width:0"><b style="font-size:14px;font-weight:600">${esc(label)}</b><div class="help">${esc(desc)}</div></div>
    <button class="switch${on ? " on" : ""}" type="button" role="switch" aria-checked="${on ? "true" : "false"}" aria-label="${esc(label)}" data-act="${esc(act)}" data-enabled="${on ? "0" : "1"}"></button>
  </div>`;

  function covBar(pct) {
    const known = typeof pct === "number";
    const p = known ? Math.max(0, Math.min(1, pct)) : 0;
    return `<div class="cov-bar ${HcbData.covLevel(known ? p : null)}" title="${pctText(known ? p : null)}"><i style="width:${Math.round(p * 100)}%"></i></div>`;
  }

  const userOf = (id) => D().users.find((u) => u.id === id);
  const userLabel = (id) => {
    const u = userOf(id);
    return u ? u.display : (id || "—");
  };

  // ---------------------------------------------------------------- 壳
  function sidebar(view) {
    let html = "";
    for (const n of NAV) {
      if (n === null) continue;
      if (typeof n === "string") { html += `<div class="sec-h">${esc(n)}</div>`; continue; }
      const [k, label, icon] = n;
      const cnt = navCount(k);
      html += `<a class="ni ${view.route === k ? "on" : ""}" href="#/${esc(k)}">${ic(icon)}<span class="lbl">${esc(label)}</span><span class="cnt">${cnt === undefined ? "" : esc(cnt)}</span></a>`;
    }
    const theme = document.documentElement.dataset.theme === "dark" ? "sun" : "moon";
    return `<aside class="side" data-pid="sidebar"><div class="bar brand"><svg class="logo" aria-hidden="true"><use href="#logo"/></svg><b>HC Health</b><span>admin</span></div>
  <nav class="side-body scroll">${html}</nav>
  <div class="side-foot">
    <span class="ver grow">v${esc(D().version || "—")}</span>
    ${D().caps.followTheme ? "" : `<button class="ibtn" data-act="theme" title="切换主题 (t)" aria-label="切换主题">${ic(theme)}</button>`}
    <button class="ibtn" data-act="kbd" title="快捷键 (?)" aria-label="快捷键">${ic("kbd")}</button>
    <button class="ibtn" data-act="logout" title="退出登录" aria-label="退出">${ic("out")}</button>
  </div></aside>`;
  }

  function mnav(view) {
    const items = [
      ["overview", "概览", "home"],
      ["users", "身份", "users"],
      ["coverage", "健康", "heart"],
      ["more", "更多", "menu"],
    ];
    const prim = ["overview", "users", "coverage"];
    return `<nav class="mnav" aria-label="移动导航">${items.map(([k, label, icon]) => {
      const href = k === "more" ? "#" : "#/" + k;
      const on = k === "more" ? prim.indexOf(view.route) < 0 : view.route === k;
      return `<a href="${href}"${k === "more" ? ' data-act="mmore"' : ""} class="${on ? "on" : ""}">${ic(icon)}<span>${esc(label)}</span></a>`;
    }).join("")}</nav>`;
  }

  function shell(view) {
    return `<div class="app" data-pid="app">${sidebar(view)}<main class="main" data-pid="main">${page(view)}</main></div>${mnav(view)}`;
  }

  function page(view) {
    const map = {
      overview: pageOverview, users: pageUsers, sessions: pageSessions,
      tokens: pageTokens, oauth: pageOauth, coverage: pageCoverage,
      sleep: pageSleep, metrics: pageMetrics, audit: pageAudit, security: pageSecurity,
    };
    return (map[view.route] || pageOverview)(view);
  }

  function loading(view) {
    return `<div class="app">${sidebar(view)}<main class="main">${pageBar("加载中")}<div class="content scroll"><div class="page">${emptyState("refresh", "正在加载管理数据…", "从管理接口读取状态，请稍候。")}</div></div></main></div>${mnav(view)}`;
  }

  function failure(view, message) {
    return `<div class="app">${sidebar(view)}<main class="main">${pageBar("加载失败")}<div class="content scroll"><div class="page">
  <div class="banner danger">${ic("alert")}<div><b>管理数据加载失败</b><div>${esc(message || "未知错误")}</div></div></div>
  <button class="btn primary" data-act="reload">${ic("refresh")}重试</button>
  </div></div></main></div>${mnav(view)}`;
  }

  // ---------------------------------------------------------------- 概览
  function pageOverview(view) {
    const d = D();
    const cov = d.coverage.slice(0, 5);
    const rows = cov.map((c) => `<tr class="click" data-open="coverage" data-id="${esc(c.type)}">
    <td><div class="nm"><b>${esc(c.label)}</b><span class="sub2 mono">${esc(c.type)}</span></div></td>
    <td class="num cellt">${num(c.rows)}</td>
    <td>${c.sources.map(srcPill).join(" ")}</td>
    <td>${covBar(c.pct)}<span class="muted num" style="font-size:13px">${pctText(c.pct)}</span></td>
    <td class="cellt num" title="${esc(HcbData.fmtFull(c.lastAt))}">${rel(c.lastAt)}</td></tr>`);
    const cards = cov.map((c) => `<div class="mc" data-open="coverage" data-id="${esc(c.type)}"><div class="b"><b>${esc(c.label)}</b><div class="meta"><span class="num">${num(c.rows)} 行</span><span>${pctText(c.pct)} · 30 日</span><span>${rel(c.lastAt)}</span></div></div></div>`);
    const k = d.kpis;
    const pipelineOk = d.sync.pipeline === "ok";
    const backfillCls = d.backfill.status === "idle" ? "ok" : d.backfill.status === "error" ? "danger" : "";
    const backfillLabel = d.backfill.status === "idle" ? "空闲" : d.backfill.status === "running" ? "运行中"
      : d.backfill.status === "error" ? "失败" : d.backfill.status;
    return pageBar("概览", `<button class="btn sm" data-act="reload">${ic("refresh")}刷新</button>`) + `<div class="content scroll"><div class="page">
  <div class="stats">
    <a class="stat" href="#/coverage"><div class="t">${ic("heart")}心率样本</div><div class="n">${num(k.heartRows)}</div><div class="d">最近同步 ${rel(d.sync.lastSyncAt)}</div></a>
    <a class="stat" href="#/coverage"><div class="t">${ic("cover")}覆盖类型</div><div class="n">${num(k.coverageTypes)}</div><div class="d">${k.lowCoverage ? `<span class="w">${num(k.lowCoverage)} 项不足 90%</span>` : "近 30 日良好"}</div></a>
    <a class="stat" href="#/sessions"><div class="t">${ic("session")}活跃会话</div><div class="n">${num(k.activeSessions)}</div><div class="d">${num(k.activeUsers)} 个有效用户</div></a>
    <a class="stat" href="#/audit"><div class="t">${ic("shield")}24h 审计失败</div><div class="n">${num(k.auditFails24h)}</div><div class="d">${k.auditFails24h ? '<span class="w">需关注</span>' : "无失败"}</div></a>
  </div>
  ${d.pending.length ? `<div class="banner amber">${ic("alert")}<div><b>${num(d.pending.length)} 个待确认的授权请求</b> · 处理前客户端拿不到令牌。<a href="#/oauth">去处理</a></div></div>` : ""}
  <div class="sect"><div class="sect-h"><h2>同步与回填</h2></div>
    <div class="banner ${pipelineOk ? "acc" : d.sync.pipeline === "warn" ? "amber" : ""}">${ic("activity")}<div><b>${pipelineOk ? "管道正常" : d.sync.pipeline === "warn" ? "管道需关注" : "同步状态未知"}</b>${d.sync.lastSyncSource ? ` · 上次同步 ${esc(L.source[d.sync.lastSyncSource] || d.sync.lastSyncSource)}` : ""}${d.sync.lastSyncAt ? ` · +${num(d.sync.lastSyncRows)} 行` : ""}${d.sync.lagMin === null ? "" : ` · 延迟约 ${num(d.sync.lagMin)} 分钟`}<span class="mono" style="display:block;margin-top:4px;color:var(--tx3)">${esc(HcbData.fmtFull(d.sync.lastSyncAt))}</span></div></div>
    <div class="kv">
      <div class="k">回填状态</div><div><span class="st ${esc(backfillCls)}"><span class="dot"></span>${esc(backfillLabel)}</span>${d.backfill.progress === null ? "" : `<span class="kv-rest">· 进度 ${Math.round(d.backfill.progress * 100)}%</span>`}</div>
      <div class="k">回填区间</div><div class="mono">${esc(d.backfill.rangeFrom || "—")} → ${esc(d.backfill.rangeTo || "—")}</div>
      <div class="k">上次回填</div><div title="${esc(HcbData.fmtFull(d.backfill.lastRun))}">${rel(d.backfill.lastRun)}</div>
    </div>
    ${d.backfill.note ? `<div class="help" style="margin-top:8px">${esc(d.backfill.note)}</div>` : ""}
  </div>
  <div class="ov2">
    <div class="sect" style="margin:0"><div class="sect-h"><h2>数据覆盖</h2><span class="grow"></span><a href="#/coverage" class="btn ghost sm">全部</a></div>
      ${table([["类型", "84px"], ["行数", "90px"], ["来源", "120px"], ["30 日覆盖", "110px"], ["最近", "80px"]], rows, emptyState("db", "暂无覆盖数据", "同步后会出现在这里"))}
      ${mcards(cards)}
    </div>
    <div class="sect" style="margin:0"><div class="sect-h"><h2>快捷入口</h2></div>
      <div class="list">
        <a class="li" href="#/sleep">${ic("sleep")}<div class="grow"><b>睡眠体检</b><div class="muted">缺口与质量标记</div></div>${ic("chev", "xs")}</a>
        <a class="li" href="#/metrics">${ic("activity")}<div class="grow"><b>指标趋势</b><div class="muted">步数 / 心率 / 睡眠</div></div>${ic("chev", "xs")}</a>
        <a class="li" href="#/users">${ic("users")}<div class="grow"><b>用户与会话</b><div class="muted">${num(d.users.length)} 用户 · ${num(d.sessions.length)} 会话</div></div>${ic("chev", "xs")}</a>
        <a class="li" href="#/security">${ic("lock")}<div class="grow"><b>安全设置</b><div class="muted">TOTP ${d.security.totpEnabled ? "已启用" : "未启用"}</div></div>${ic("chev", "xs")}</a>
      </div>
    </div>
  </div>
  </div></div>`;
  }

  // ---------------------------------------------------------------- 用户
  function pageUsers(view) {
    const d = D();
    const single = d.caps.users === "single";
    const q = (view.q.users || "").trim().toLowerCase();
    const list = d.users.filter((u) => !q || u.username.toLowerCase().indexOf(q) >= 0
      || u.display.toLowerCase().indexOf(q) >= 0 || u.role.indexOf(q) >= 0);
    const rows = list.map((u) => `<tr class="click ${u.status === "disabled" ? "dim" : ""} ${view.sub === u.id ? "cur" : ""}" data-open="user" data-id="${esc(u.id)}">
    <td><div class="nm"><b>${esc(u.display)}</b><span class="sub2">${idc(u.id, u.username)} · ${esc(L.role[u.role] || u.role)}</span></div></td>
    <td>${stSpan(L.userStatus, u.status)}</td>
    <td>${u.totp ? '<span class="tag ok">TOTP</span>' : '<span class="tag">无 TOTP</span>'}</td>
    <td class="cellt num" title="${esc(HcbData.fmtFull(u.lastLogin))}">${rel(u.lastLogin)}</td>
    <td class="act" data-stop>${moreBtn("user", u.id)}</td></tr>`);
    const cards = list.map((u) => `<div class="mc ${view.sub === u.id ? "cur" : ""}" data-open="user" data-id="${esc(u.id)}"><div class="b"><b>${esc(u.display)}</b><div class="meta">${stSpan(L.userStatus, u.status)}<span>${esc(L.role[u.role] || u.role)}</span><span>${u.totp ? "TOTP" : "无 TOTP"}</span><span>${rel(u.lastLogin)}</span></div></div>${moreBtn("user", u.id)}</div>`);
    const extra = single ? "" : `<button class="btn primary sm" data-act="new-user">${ic("plus")}新建用户</button>`;
    return pageBar("用户", extra, list.length) + `<div class="content scroll"><div class="page">
  ${single ? `<div class="banner">${ic("info")}<div>本机只有一个管理员账号：密码与 TOTP 在<a href="#/security">安全设置</a>里管理。</div></div>` : ""}
  <div class="toolbar">${searchBox("搜索用户名 / 角色", view.q.users, "users")}</div>
  ${table([["用户"], ["状态", "100px"], ["双因素", "100px"], ["最近登录", "110px"], ["", "48px", "act"]], rows, emptyState("user", "没有匹配的用户", single ? "本机没有多用户体系" : "调整搜索或新建用户"))}
  ${mcards(cards)}
  </div></div>`;
  }

  function drawerUser(id) {
    const d = D();
    const single = d.caps.users === "single";
    if (id === "new") {
      if (single) {
        return {
          title: "本机单管理员",
          body: `<div class="banner">${ic("info")}<div>这个管理端只有一个本机管理员，没有多用户体系。管理员密码与 TOTP 在<a href="#/security">安全设置</a>里管理。</div></div>`,
          foot: `<button class="btn" data-act="close-drawer">关闭</button>`,
        };
      }
      const formId = "user-create";
      return {
        title: "新建用户",
        body: `<form id="${formId}" data-form="user-create">
  ${fields([
          fld("用户名", inp("username", { placeholder: "3-32 位 a-z0-9._-", autocomplete: "off", required: true }), "登录名，创建后不可改。"),
          fld("显示名", inp("display", { placeholder: "例如：运维只读", autocomplete: "off" })),
          fld("初始密码", inp("password", { type: "password", autocomplete: "new-password", required: true }), "至少 10 位。"),
          fld("确认密码", inp("password2", { type: "password", autocomplete: "new-password", required: true })),
        ])}
  <div class="dsec"><h3>权限范围</h3>${scopeChips("scopes", ["health.read"])}<div class="help" style="margin-top:6px">health.write 允许触发同步与写入；管理员角色由 admin scope 决定。</div></div>
  </form>`,
        foot: `<button class="btn" data-act="close-drawer">取消</button><span class="grow"></span><button class="btn primary" type="submit" form="${formId}">创建</button>`,
      };
    }
    const u = userOf(id);
    if (!u) return null;
    const sess = d.sessions.filter((s) => s.userId === id);
    const danger = single ? "" : `<div class="dsec"><h3>危险操作</h3>
    <div class="danger-zone">
      ${u.status === "active"
        ? `<div class="dz"><div class="t"><b>停用用户</b>立即失效其会话与令牌写入</div><button class="btn danger-o sm" data-act="user-toggle" data-id="${esc(u.id)}" data-enabled="0">停用</button></div>`
        : `<div class="dz"><div class="t"><b>启用用户</b>恢复登录与写入</div><button class="btn sm" data-act="user-toggle" data-id="${esc(u.id)}" data-enabled="1">启用</button></div>`}
      <div class="dz"><div class="t"><b>删除用户</b>不可恢复（需输入用户名确认）</div><button class="btn danger-o sm" data-act="del-user" data-id="${esc(u.id)}">删除</button></div>
    </div></div>`;
    const editFormId = "user-edit";
    const edit = single ? "" : `<div class="dsec"><h3>编辑</h3>
    <form id="${editFormId}" data-form="user-edit" data-id="${esc(u.id)}">
      ${fields([
        fld("显示名", inp("display", { value: u.display, autocomplete: "off" }), "", true),
        fld("权限范围", scopeChips("scopes", u.scopes), "", true),
        fld("重置密码", inp("password", { type: "password", autocomplete: "new-password", placeholder: "留空则不修改" }), "至少 10 位；留空保持原密码。", true),
      ])}
    </form></div>`;
    return {
      title: u.display,
      body: `<div class="dsec"><h3>基本信息</h3>
    <div class="kv">
      <div class="k">用户名</div><div class="mono">${esc(u.username)}</div>
      <div class="k">角色</div><div>${esc(L.role[u.role] || u.role)} <span class="mono muted">${esc(u.role)}</span></div>
      <div class="k">状态</div><div>${stSpan(L.userStatus, u.status)}</div>
      <div class="k">TOTP</div><div>${u.totp ? '<span class="st ok"><span class="dot"></span>已启用</span>' : '<span class="st amber"><span class="dot"></span>未启用</span>'}</div>
      <div class="k">创建</div><div title="${esc(HcbData.fmtFull(u.createdAt))}">${rel(u.createdAt)}</div>
      <div class="k">最近登录</div><div title="${esc(HcbData.fmtFull(u.lastLogin))}">${rel(u.lastLogin)}</div>
      ${u.note ? `<div class="k">备注</div><div>${esc(u.note)}</div>` : ""}
    </div>
  </div>
  <div class="dsec"><h3>会话（${sess.length}）</h3>
    <div class="list">${sess.length ? sess.map((s) => `<div class="li"><span class="dot ${s.current ? "ok" : ""}"></span><div class="grow"><b>${esc(s.device)}</b><div class="muted">${esc(s.ip)} · ${esc(s.loc)}</div></div><span class="r">${rel(s.lastSeen)}</span></div>`).join("") : '<div class="li muted">无会话</div>'}</div>
  </div>
  ${edit}
  ${danger}`,
      foot: `${edit ? `<button class="btn primary" type="submit" form="${editFormId}">保存</button><span class="grow"></span>` : ""}<button class="btn" data-act="close-drawer">关闭</button>`,
    };
  }

  // ---------------------------------------------------------------- 会话
  function pageSessions(view) {
    const d = D();
    const single = d.caps.sessions === "single";
    const q = (view.q.sessions || "").trim().toLowerCase();
    const list = d.sessions.filter((s) => {
      const u = userOf(s.userId);
      return !q || s.device.toLowerCase().indexOf(q) >= 0 || s.ip.toLowerCase().indexOf(q) >= 0
        || (u && u.username.toLowerCase().indexOf(q) >= 0);
    });
    const rows = list.map((s) => `<tr class="click ${view.sub === s.id ? "cur" : ""}" data-open="session" data-id="${esc(s.id)}">
      <td><div class="nm"><b>${esc(s.device)}</b><span class="sub2">${esc(userLabel(s.userId))}${s.current ? ' · <span class="tag acc">当前</span>' : ""}</span></div></td>
      <td class="mono cellt">${esc(s.ip)}</td>
      <td class="cellt">${esc(s.loc)}</td>
      <td class="cellt num" title="${esc(HcbData.fmtFull(s.lastSeen))}">${rel(s.lastSeen)}</td>
      <td class="act" data-stop>${moreBtn("session", s.id)}</td></tr>`);
    const cards = list.map((s) => `<div class="mc" data-open="session" data-id="${esc(s.id)}"><div class="b"><b>${esc(s.device)}</b><div class="meta"><span>${esc(userLabel(s.userId))}</span><span class="mono">${esc(s.ip)}</span><span>${rel(s.lastSeen)}</span>${s.current ? '<span class="tag acc">当前</span>' : ""}</div></div>${moreBtn("session", s.id)}</div>`);
    const others = d.sessions.filter((s) => !s.current).length;
    const extra = (!single && others) ? `<button class="btn danger-o sm" data-act="revoke-others">${ic("ban")}撤销其他会话（${others}）</button>` : "";
    return pageBar("会话", extra, list.length) + `<div class="content scroll"><div class="page">
  ${single ? `<div class="banner">${ic("info")}<div>本机只有一个解锁会话，锁屏或超时后自动结束。</div></div>` : ""}
  <div class="toolbar">${searchBox("设备 / IP / 用户", view.q.sessions, "sessions")}</div>
  ${table([["设备"], ["IP", "120px"], ["位置", "140px"], ["最近活跃", "110px"], ["", "48px", "act"]], rows, emptyState("session", "没有会话", "登录后会出现在这里"))}
  ${mcards(cards)}
  </div></div>`;
  }

  function drawerSession(id) {
    const s = D().sessions.find((x) => x.id === id);
    if (!s) return null;
    return {
      title: s.device,
      body: `<div class="dsec"><h3>会话详情</h3>
    <div class="kv">
      <div class="k">用户</div><div>${esc(userLabel(s.userId))} <span class="mono muted">${esc((userOf(s.userId) || {}).username || "")}</span></div>
      <div class="k">IP</div><div class="mono">${esc(s.ip)}</div>
      <div class="k">位置</div><div>${esc(s.loc)}</div>
      <div class="k">创建</div><div title="${esc(HcbData.fmtFull(s.createdAt))}">${rel(s.createdAt)}</div>
      <div class="k">最近活跃</div><div title="${esc(HcbData.fmtFull(s.lastSeen))}">${rel(s.lastSeen)}</div>
      ${s.expiresAt ? `<div class="k">过期</div><div>${rel(s.expiresAt)}</div>` : ""}
      <div class="k">当前会话</div><div>${s.current ? "是" : "否"}</div>
    </div>
  </div>
  <div class="dsec"><h3>危险操作</h3>
    <div class="danger-zone"><div class="dz"><div class="t"><b>撤销会话</b>设备需重新登录</div><button class="btn danger-o sm" data-act="revoke-session" data-id="${esc(s.id)}"${s.current ? ' disabled title="不能撤销当前会话"' : ""}>撤销</button></div></div>
  </div>`,
      foot: `<button class="btn" data-act="close-drawer">关闭</button>`,
    };
  }

  // ---------------------------------------------------------------- 令牌
  function pageTokens(view) {
    const d = D();
    const rows = d.tokens.map((t) => `<tr class="click ${t.status === "revoked" ? "dim" : ""} ${view.sub === t.id ? "cur" : ""}" data-open="token" data-id="${esc(t.id)}">
      <td><div class="nm"><b>${esc(t.name)}</b><span class="sub2 mono">${esc(t.prefix)}</span></div></td>
      <td>${stSpan(L.tokenStatus, t.status)}</td>
      <td><div class="tags">${t.scopes.map((sc) => tag(sc)).join("") || '<span class="muted">—</span>'}</div></td>
      <td class="cellt">${esc(t.userId ? userLabel(t.userId) : "—")}</td>
      <td class="cellt num" title="${esc(HcbData.fmtFull(t.lastUsed))}">${rel(t.lastUsed)}</td>
      <td class="act" data-stop>${moreBtn("token", t.id)}</td></tr>`);
    const cards = d.tokens.map((t) => `<div class="mc ${t.status === "revoked" ? "dim" : ""}" data-open="token" data-id="${esc(t.id)}"><div class="b"><b>${esc(t.name)}</b><div class="meta">${stSpan(L.tokenStatus, t.status)}<span class="mono">${esc(t.prefix)}</span><span>${rel(t.lastUsed)}</span></div></div>${moreBtn("token", t.id)}</div>`);
    return pageBar("静态令牌", `<button class="btn primary sm" data-act="new-token">${ic("plus")}新建令牌</button>`, d.tokens.length) + `<div class="content scroll"><div class="page">
  <div class="banner amber">${ic("alert")}<div>新建后明文<strong>只显示一次</strong>。列表仅保留前缀。</div></div>
  ${table([["名称"], ["状态", "90px"], ["权限", "200px"], ["归属", "90px"], ["最近使用", "100px"], ["", "48px", "act"]], rows, emptyState("key", "暂无令牌", "创建后供 MCP / 自动化使用"))}
  ${mcards(cards)}
  </div></div>`;
  }

  function drawerToken(id) {
    if (id === "new") {
      const formId = "token-create";
      return {
        title: "新建静态令牌",
        body: `<form id="${formId}" data-form="token-create">
  ${fields([fld("名称", inp("name", { placeholder: "例如：MCP Claude Desktop", autocomplete: "off", required: true }), "给调用方看的标签。", true)])}
  <div class="dsec"><h3>权限范围</h3>${scopeChips("scopes", ["health.read"])}</div>
  <div class="banner amber" style="margin-top:16px">${ic("alert")}<div>创建成功后明文只出现一次，请当场复制。</div></div>
  </form>`,
        foot: `<button class="btn" data-act="close-drawer">取消</button><span class="grow"></span><button class="btn primary" type="submit" form="${formId}">创建令牌</button>`,
      };
    }
    const t = D().tokens.find((x) => x.id === id);
    if (!t) return null;
    const active = t.status === "active";
    return {
      title: t.name,
      body: `<div class="dsec"><h3>令牌</h3>
    <div class="kv">
      <div class="k">前缀</div><div class="mono">${esc(t.prefix)}</div>
      <div class="k">状态</div><div>${stSpan(L.tokenStatus, t.status)}</div>
      <div class="k">权限</div><div class="tags">${t.scopes.map((sc) => tag(sc)).join("") || "—"}</div>
      <div class="k">归属</div><div>${esc(t.userId ? userLabel(t.userId) : "—")}</div>
      ${t.kind ? `<div class="k">类型</div><div class="mono">${esc(t.kind)}</div>` : ""}
      ${t.family ? `<div class="k">family</div><div class="mono">${esc(HcbData.short(t.family))}</div>` : ""}
      <div class="k">创建</div><div>${rel(t.createdAt)}</div>
      <div class="k">最近使用</div><div>${rel(t.lastUsed)}</div>
      <div class="k">过期</div><div>${t.expires === null ? "永不过期" : rel(t.expires)}</div>
    </div>
  </div>
  ${D().caps.policies ? policySection("token", t.id, t) : ""}
  ${active ? `<div class="dsec"><h3>危险操作</h3><div class="danger-zone"><div class="dz"><div class="t"><b>撤销令牌</b>立即失效，不可恢复明文</div><button class="btn danger-o sm" data-act="revoke-token" data-id="${esc(t.id)}" data-family="${esc(t.family)}">撤销</button></div></div></div>` : ""}`,
      foot: `<button class="btn" data-act="close-drawer">关闭</button>`,
    };
  }

  // ---------------------------------------------------------------- OAuth
  function pendingRows(p) {
    return p.map((x) => `<div class="dz" style="border-top-color:var(--line)">
      <div class="t"><b>${esc(x.client)}</b><span class="mono">${esc(x.clientId)}</span> · ${esc(x.redirect || "—")} · ${x.scopes.map((s) => tag(s)).join(" ")}</div>
      <button class="btn sm" data-act="pending-allow" data-id="${esc(x.id)}">允许</button>
      <button class="btn danger-o sm" data-act="pending-deny" data-id="${esc(x.id)}">拒绝</button>
    </div>`);
  }

  function pageOauth(view) {
    const d = D();
    const rows = d.oauth.map((c) => `<tr class="click ${c.status === "disabled" ? "dim" : ""} ${view.sub === c.id ? "cur" : ""}" data-open="oauth" data-id="${esc(c.id)}">
    <td><div class="nm"><b>${esc(c.name)}</b><span class="sub2 mono">${esc(c.clientId)}</span></div></td>
    <td><span class="kind">${esc(c.kind === "confidential" ? "机密" : c.kind === "public" ? "公共" : c.kind)}</span></td>
    <td>${stSpan(L.oauthStatus, c.status)}</td>
    <td class="cellt num" title="${esc(HcbData.fmtFull(c.lastAuth))}">${rel(c.lastAuth)}</td>
    <td class="act" data-stop>${moreBtn("oauth", c.id)}</td></tr>`);
    const cards = d.oauth.map((c) => `<div class="mc" data-open="oauth" data-id="${esc(c.id)}"><div class="b"><b>${esc(c.name)}</b><div class="meta">${stSpan(L.oauthStatus, c.status)}<span class="mono">${esc(c.clientId)}</span><span>${rel(c.lastAuth)}</span></div></div>${moreBtn("oauth", c.id)}</div>`);
    return pageBar("OAuth 客户端", `<button class="btn primary sm" data-act="new-oauth">${ic("plus")}新建客户端</button>`, d.oauth.length) + `<div class="content scroll"><div class="page">
  ${d.pending.length ? `<div class="sect"><div class="sect-h"><h2>待确认的授权请求</h2><span class="grow"></span>${tag(String(d.pending.length), "amber")}</div><div class="danger-zone">${pendingRows(d.pending).join("")}</div></div>` : ""}
  ${table([["客户端"], ["类型", "110px"], ["状态", "90px"], ["最近授权", "110px"], ["", "48px", "act"]], rows, emptyState("app", "暂无客户端", "创建后供 OAuth 接入"))}
  ${mcards(cards)}
  </div></div>`;
  }

  function drawerOauth(id) {
    if (id === "new") {
      const formId = "oauth-create";
      return {
        title: "新建 OAuth 客户端",
        body: `<form id="${formId}" data-form="oauth-create">
  ${fields([
          fld("名称", inp("name", { placeholder: "例如：Obsidian MCP", autocomplete: "off", required: true }), "", true),
          fld("客户端类型", `<select class="sel" name="kind"><option value="public">公共（PKCE，无 secret）</option><option value="confidential">机密（有 secret）</option></select>`, "confidential 的 secret 只在创建结果里出现一次。", true),
        ])}
  <div class="dsec"><h3>回调地址</h3>${textarea("redirects", "", "每行一个，例如：\nhttp://127.0.0.1:22360/callback", 4)}<div class="help" style="margin-top:6px">至少一个；必须是完整 URI。</div></div>
  </form>`,
        foot: `<button class="btn" data-act="close-drawer">取消</button><span class="grow"></span><button class="btn primary" type="submit" form="${formId}">创建</button>`,
      };
    }
    const c = D().oauth.find((x) => x.id === id);
    if (!c) return null;
    const active = c.status === "active";
    const pending = c.status === "pending";
    return {
      title: c.name,
      body: `<div class="dsec"><h3>客户端</h3>
    <div class="kv">
      <div class="k">client_id</div><div>${esc(c.clientId)}</div>
      <div class="k">类型</div><div>${esc(c.kind === "confidential" ? "机密" : c.kind === "public" ? "公共" : c.kind)}</div>
      <div class="k">状态</div><div>${stSpan(L.oauthStatus, c.status)}</div>
      <div class="k">回调</div><div>${c.redirects.map((r) => `<div class="mono" style="margin-bottom:4px">${esc(r)}</div>`).join("") || "—"}</div>
      <div class="k">创建</div><div>${rel(c.createdAt)}</div>
      <div class="k">最近授权</div><div>${rel(c.lastAuth)}</div>
    </div>
  </div>
  ${D().caps.policies ? policySection("oauth", c.id, c) : ""}
  <div class="dsec"><h3>危险操作</h3>
    <div class="danger-zone">
      ${active ? `<div class="dz"><div class="t"><b>停用客户端</b>已发出的令牌不再可用</div><button class="btn danger-o sm" data-act="oauth-toggle" data-id="${esc(c.id)}" data-enabled="0">停用</button></div>`
        : `<div class="dz"><div class="t"><b>${pending ? "批准客户端" : "启用客户端"}</b></div><button class="btn sm" data-act="oauth-toggle" data-id="${esc(c.id)}" data-enabled="1">${pending ? "批准" : "启用"}</button></div>`}
      <div class="dz"><div class="t"><b>删除客户端</b>不可恢复</div><button class="btn danger-o sm" data-act="del-oauth" data-id="${esc(c.id)}">删除</button></div>
    </div>
  </div>`,
      foot: `<button class="btn" data-act="close-drawer">关闭</button>`,
    };
  }

  // ---------------------------------------------------------------- 覆盖
  function healthLoading() {
    return D().healthPending
      ? `<div class="health-loading"><span class="boot-spin" aria-hidden="true"></span><span>正在读取健康数据…</span></div>`
      : "";
  }

  function pageCoverage(view) {
    const d = D();
    const rows = d.coverage.map((c) => `<tr class="click" data-open="coverage" data-id="${esc(c.type)}">
    <td><div class="nm"><b>${esc(c.label)}</b><span class="sub2 mono">${esc(c.type)}</span></div></td>
    <td class="num cellt">${num(c.rows)}</td>
    <td>${c.sources.map(srcPill).join(" ")}</td>
    <td class="num cellt">${c.days30 === null ? "—" : c.days30 + "/" + HcbData.COVERAGE_DAYS}</td>
    <td>${covBar(c.pct)}<span class="muted num" style="font-size:13px;margin-left:6px">${pctText(c.pct)}</span></td>
    <td class="cellt num" title="${esc(HcbData.fmtFull(c.lastAt))}">${rel(c.lastAt)}</td></tr>`);
    const cards = d.coverage.map((c) => `<div class="mc" data-open="coverage" data-id="${esc(c.type)}"><div class="b"><b>${esc(c.label)}</b><div class="meta"><span class="num">${num(c.rows)}</span><span>${c.days30 === null ? "—" : c.days30 + "/" + HcbData.COVERAGE_DAYS + " 天"}</span><span>${pctText(c.pct)}</span><span>${rel(c.lastAt)}</span></div><div class="meta">${c.sources.map(srcPill).join(" ")}</div></div></div>`);
    return pageBar("数据覆盖", `<button class="btn sm" data-act="reload">${ic("refresh")}刷新</button>`, d.coverage.length) + `<div class="content scroll"><div class="page">
  ${healthLoading()}
  <p class="lede">按指标类型与来源查看入库规模与近 30 日覆盖。</p>
  ${table([["指标"], ["行数", "110px"], ["来源", "180px"], ["有数天数", "90px"], ["覆盖", "140px"], ["最近写入", "100px"]], rows, emptyState("cover", "暂无覆盖数据", "同步后会出现在这里"))}
  ${mcards(cards)}
  </div></div>`;
  }

  function drawerCoverage(type) {
    const c = D().coverage.find((x) => x.type === type);
    if (!c) return null;
    return {
      title: c.label,
      body: `<div class="dsec"><h3>覆盖详情</h3>
    <div class="kv">
      <div class="k">类型</div><div class="mono">${esc(c.type)}</div>
      <div class="k">总行数</div><div class="num">${num(c.rows)}</div>
      <div class="k">来源</div><div>${c.sources.map(srcPill).join(" ") || "—"}</div>
      <div class="k">近 ${num(HcbData.COVERAGE_DAYS)} 日</div><div>${c.days30 === null ? "—" : `${num(c.days30)} 天有数据 · ${pctText(c.pct)}`}</div>
      <div class="k">最近写入</div><div title="${esc(HcbData.fmtFull(c.lastAt))}">${rel(c.lastAt)} · ${esc(HcbData.fmtFull(c.lastAt))}</div>
    </div>
  </div>
  <div class="dsec"><h3>操作</h3>
    <div style="display:flex;flex-wrap:wrap;gap:8px">
      <button class="btn sm" data-act="backfill-type" data-id="${esc(c.type)}">${ic("refresh")}触发回填</button>
      <a class="btn sm" href="#/metrics">${ic("activity")}查看趋势</a>
    </div>
  </div>`,
      foot: `<button class="btn" data-act="close-drawer">关闭</button>`,
    };
  }

  // ---------------------------------------------------------------- 睡眠
  function sleepFlags(s) {
    if (!s.flags.length) return '<span class="tag ok">正常</span>';
    return s.flags.map((f) => tag(L.sleepFlag[f] || f,
      f === "missing" || f === "invalid" ? "danger" : "amber")).join(" ");
  }

  function pageSleep(view) {
    const d = D();
    const rows = d.sleep.map((s) => `<tr class="click ${s.flags.indexOf("missing") >= 0 ? "fail" : ""}" data-open="sleep" data-id="${esc(s.id)}">
    <td class="mono num">${esc(s.date)}</td>
    <td class="cellt num">${esc(s.bed)} → ${esc(s.wake)}</td>
    <td class="num cellt">${s.hours === null ? "—" : s.hours.toFixed(2) + " h"}</td>
    <td class="num cellt">${s.score === null ? "—" : esc(s.score)}</td>
    <td>${sleepFlags(s)}</td>
    <td class="cellt">${esc(s.note || "—")}</td></tr>`);
    const cards = d.sleep.map((s) => `<div class="mc ${s.flags.indexOf("missing") >= 0 ? "fail" : ""}" data-open="sleep" data-id="${esc(s.id)}"><div class="b"><b class="mono">${esc(s.date)}</b><div class="meta"><span>${esc(s.bed)} → ${esc(s.wake)}</span><span>${s.hours === null ? "—" : s.hours.toFixed(2) + " h"}</span>${sleepFlags(s)}</div></div></div>`);
    return pageBar("睡眠体检", "", d.sleep.length) + `<div class="content scroll"><div class="page">
  ${healthLoading()}
  <p class="lede">逐日睡眠样本质检：缺口、偏短、入睡偏晚、碎片化等标记。</p>
  ${table([["日期", "110px"], ["入睡 → 起床", "140px"], ["时长", "90px"], ["评分", "70px"], ["标记", "160px"], ["说明"]], rows, emptyState("sleep", "暂无睡眠记录", "同步后会出现在这里"))}
  ${mcards(cards)}
  </div></div>`;
  }

  function drawerSleep(id) {
    const s = D().sleep.find((x) => x.id === id);
    if (!s) return null;
    return {
      title: s.date,
      body: `<div class="dsec"><h3>睡眠日详</h3>
    <div class="kv">
      <div class="k">入睡</div><div class="num">${esc(s.bed)}</div>
      <div class="k">起床</div><div class="num">${esc(s.wake)}</div>
      <div class="k">时长</div><div class="num">${s.hours === null ? "—" : s.hours.toFixed(2) + " 小时"}</div>
      <div class="k">评分</div><div class="num">${s.score === null ? "—" : esc(s.score)}</div>
      <div class="k">来源</div><div>${s.source === "—" ? "—" : srcPill(s.source)}</div>
      <div class="k">标记</div><div>${sleepFlags(s)}</div>
      <div class="k">说明</div><div>${esc(s.note || "—")}</div>
    </div>
  </div>`,
      foot: `<button class="btn" data-act="close-drawer">关闭</button>`,
    };
  }

  // ---------------------------------------------------------------- 指标
  function pageMetrics(view) {
    const d = D();
    const maxDays = Math.max(14, d.metrics.days || 0,
      ...["steps", "heart_rate", "sleep"].map((k) => (d.metrics[k].series || []).length));
    const days = Math.max(1, Math.min(Number(view && view.metricDays) || 14, maxDays));
    const cards = ["steps", "heart_rate", "sleep"].map((key, idx) => {
      const [icon, cls] = [["steps", ""], ["heart", "hr"], ["sleep", "sleep"]][idx];
      const m = d.metrics[key];
      const series = (m.series || []).slice(-days);
      const known = series.filter((v) => v !== null);
      const latest = known.length ? known[known.length - 1] : null;
      const avg = known.length ? known.reduce((a, b) => a + b, 0) / known.length : null;
      const latestText = latest === null ? "—" : (m.decimals ? latest.toFixed(m.decimals) : Math.round(latest));
      const avgText = avg === null ? "—" : (m.decimals ? avg.toFixed(m.decimals) : Math.round(avg));
      return `<div class="chart-card"><div class="t">${ic(icon)}${esc(m.label)}</div><div class="n">${esc(latestText)}<span class="muted" style="font-size:14px;font-weight:400;margin-left:6px">${esc(m.unit)}</span></div><div class="muted" style="font-size:13px">近 ${num(days)} 日均 ${esc(avgText)}${m.unit === "步" ? "" : " " + esc(m.unit)}</div>${sparkline(series, cls)}</div>`;
    }).join("");
    const seg = `<div class="seg">${[14, 30, 90].map((n) =>
      `<button data-act="metric-days" data-days="${n}" class="${n === days ? "on" : ""}">${n} 日</button>`).join("")}</div>`;
    return pageBar("指标趋势", seg) + `<div class="content scroll"><div class="page">
  ${healthLoading()}
  <p class="lede">步数 / 心率 / 睡眠近 ${num(days)} 日 sparkline（SVG），缺数据的日期不画断点。</p>
  <div class="chart-grid">${cards}</div>
  <div class="sect"><div class="sect-h"><h2>说明</h2></div>
    <div class="banner">${ic("info")}<div>数据来自后端日聚合查询（带缓存）；多来源明细见「数据覆盖」。</div></div>
  </div>
  </div></div>`;
  }

  // ---------------------------------------------------------------- 审计
  function pageAudit(view) {
    const d = D();
    const filter = view.auditFilter || "all";
    const all = d.audit.rows;
    const list = all.filter((a) => filter === "all" || a.outcome === filter);
    const rows = list.map((a) => `<tr class="${a.outcome === "fail" ? "fail" : ""}">
    <td class="cellt num" title="${esc(HcbData.fmtFull(a.at))}">${rel(a.at)}</td>
    <td>${esc(a.actor)}</td>
    <td><span class="mono" style="font-size:13px">${esc(a.action)}</span></td>
    <td class="mono cellt">${esc(a.target || "—")}</td>
    <td>${stSpan(L.outcome, a.outcome)}</td>
    <td class="cellt">${esc(a.detail || "—")}</td></tr>`);
    const cards = list.map((a) => `<div class="mc ${a.outcome === "fail" ? "fail" : ""}"><div class="b"><b class="mono">${esc(a.action)}</b><div class="meta">${stSpan(L.outcome, a.outcome)}<span>${esc(a.actor)}</span><span class="mono">${esc(a.target || "—")}</span><span>${rel(a.at)}</span></div><div class="meta">${esc(a.detail || "")}</div></div></div>`);
    const chain = d.audit.chainOk === null
      ? `<div class="banner">${ic("info")}<div>该端未提供审计链校验结果。</div></div>`
      : d.audit.chainOk
        ? `<div class="banner">${ic("checkc")}<div>审计链校验通过。</div></div>`
        : `<div class="banner danger">${ic("alert")}<div><b>审计链不一致</b> · 记录可能被外部修改，请立即排查。</div></div>`;
    const head = `<div class="toolbar">
    <div class="seg" data-seg="audit">
      <button data-v="all" class="${filter === "all" ? "on" : ""}">全部<span class="n">${all.length}</span></button>
      <button data-v="ok" class="${filter === "ok" ? "on" : ""}">成功<span class="n">${all.filter((a) => a.outcome === "ok").length}</span></button>
      <button data-v="fail" class="${filter === "fail" ? "on" : ""}">失败<span class="n">${all.filter((a) => a.outcome === "fail").length}</span></button>
    </div><span class="grow"></span>
    <button class="btn sm" data-act="audit-verify">${ic("shield")}校验审计链</button>
  </div>`;
    return pageBar("审计", "", list.length) + `<div class="content scroll"><div class="page">
  ${head}
  ${chain}
  ${table([["时间", "100px"], ["主体", "80px"], ["动作", "140px"], ["对象", "110px"], ["结果", "80px"], ["详情"]], rows, emptyState("list", "没有审计记录", "调整筛选条件"))}
  ${mcards(cards)}
  </div></div>`;
  }

  // ---------------------------------------------------------------- 本机访问（App）
  /** 最近一次公网探活结果（不做网络请求，只展示状态里的 lastProbe）。 */
  function probeLine(l) {
    const p = l.lastProbe;
    if (!p) return '<div class="help" style="margin-top:10px">最近检测：没有记录。</div>';
    return `<div class="help" style="margin-top:10px">最近检测：<span class="st ${p.ok ? "ok" : "danger"}"><span class="dot"></span>${p.ok ? "可达" : "不可达"}</span> <span title="${esc(HcbData.fmtFull(p.at))}">${esc(rel(p.at))}</span>${p.detail ? " · " + esc(p.detail) : ""}</div>`;
  }

  /**
   * 「本机访问」卡片（`caps.localAccess`）：鉴权开关 + 隧道配置 + 命令 / 探活。
   * 隧道模式是**草稿**：只有「保存公网设置」才落库（模式 / 基址 / token 一起提交）。
   * 命令与探活一律针对**已保存**的配置（动作只有 `{full}`，没有模式参数）。
   */
  function localCard(l) {
    const mode = l.tunnelMode;
    const modes = HcbData.TUNNEL_MODES.map((m) =>
      `<button type="button" class="${m === mode ? "on" : ""}" data-act="local-mode" data-mode="${esc(m)}" aria-pressed="${m === mode ? "true" : "false"}">${esc(L.tunnel[m] || m)}</button>`).join("");
    const tokenPh = l.hasToken ? "已保存，留空不变" : "未保存（named 隧道必须填）";
    const cmdOff = mode === "off" ? " disabled" : "";
    return `<div style="border:1px solid var(--line);border-radius:var(--r2);padding:var(--s3)">
    ${swRow("local-require-auth", l.requireAuth, "要求 Bearer 令牌", "关闭后本机客户端不带令牌就能调用；公网/隧道开着时后端会拒绝关闭。")}
    ${swRow("local-loopback", l.allowLoopbackNoAuth, "回环免鉴权", "来自 127.0.0.1 的请求不查令牌（还要关掉「要求 Bearer」才真正生效）。")}
    ${swRow("local-confirm-clients", l.confirmNewClients, "新客户端要批准", "新注册的 OAuth 客户端停在待批准，管理员批准后才生效。")}
    <form data-form="local-save" style="margin-top:var(--s3);border-top:1px solid var(--line);padding-top:var(--s3)">
      <input type="hidden" name="mode" value="${esc(mode)}" data-local-mode>
      <div class="fld" style="margin-bottom:var(--s3)"><label>隧道模式</label><div class="seg" style="align-self:flex-start">${modes}</div>
        <span class="help">保存后公网/隧道强制开启鉴权；旧令牌需重新授权。</span></div>
      ${fields([
        fld("公网基址", inp("publicBase", { value: l.publicBase || "", placeholder: "https://xxx.trycloudflare.com", autocomplete: "off" }), "裸 https://host[:port]，不带路径；留空 = 只走本机。", true),
        fld("tunnel token", inp("token", { type: "password", autocomplete: "off", placeholder: tokenPh }), l.hasToken ? "已保存（密文，界面不回显）" : "未保存", true),
      ])}
      <div style="display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin-top:12px">
        <button class="btn primary" type="submit">保存公网设置</button>
        <span class="grow"></span>
        <button class="btn sm" type="button" data-act="local-copy-cmd"${cmdOff}>${ic("copy")}复制命令</button>
        <button class="btn sm" type="button" data-act="local-copy-full"${cmdOff}>${ic("copy")}完整命令</button>
      </div>
      <div style="display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin-top:8px">
        <button class="btn sm" type="button" data-act="local-probe">${ic("globe")}连通检测</button>
        <button class="btn danger-o sm" type="button" data-act="local-clear-token"${l.hasToken ? "" : " disabled"}>${ic("ban")}清除 token</button>
      </div>
      ${probeLine(l)}
    </form>
  </div>`;
  }

  // ---------------------------------------------------------------- 安全
  function pageSecurity() {
    const d = D();
    const s = d.security;
    const totpClass = s.totpEnabled ? "ok" : "danger";
    const totpBtn = s.totpEnabled
      ? `<button class="btn sm" data-act="totp-rebind">${ic("refresh")}重新绑定</button><button class="btn danger-o sm" data-act="totp-disable">${ic("ban")}停用 TOTP</button>`
      : `<button class="btn primary sm" data-act="totp-begin">${ic("shield")}绑定 TOTP</button>`;
    const pwForm = `<form data-form="password">
    ${fields([
      fld("当前密码", inp("current", { type: "password", autocomplete: "current-password" }), "用于确认操作者是本人。", true),
      fld("新密码", inp("password", { type: "password", autocomplete: "new-password", required: true })),
      fld("确认新密码", inp("password2", { type: "password", autocomplete: "new-password", required: true })),
    ])}
    <div style="margin-top:12px;display:flex;align-items:center;gap:12px;flex-wrap:wrap">
      <button class="btn primary" type="submit">保存密码</button>
      <span class="help">上次修改：${rel(s.passwordChangedAt)}</span>
    </div>
  </form>`;
    const policyRows = `<div class="kv">
      <div class="k">会话有效期</div><div>${durText(s.sessionTtlHours)}</div>
      <div class="k">密码登录</div><div>${s.allowPasswordLogin ? "允许" : "禁止"}</div>
      <div class="k">失败上限</div><div>${s.maxFailedLogins === null ? "—" : num(s.maxFailedLogins) + " 次"}${s.lockoutMinutes === null ? "" : " / 锁定 " + num(s.lockoutMinutes) + " 分钟"}</div>
    </div>`;
    const ttlHelp = s.sessionTtlHours !== null && s.sessionTtlHours < 1
      ? `当前 ${durText(s.sessionTtlHours)}；单位是小时，小于 1 小时可以填小数。`
      : "";
    const settings = s.settingsEditable ? `<form data-form="settings" style="margin-top:12px">
    ${fields([
      fld("会话有效期（小时）", inp("sessionTtlHours", { type: "number", value: s.sessionTtlHours === null ? "" : String(s.sessionTtlHours), extra: ' min="0.01" max="720" step="any"' }), ttlHelp),
      fld("失败上限（次）", inp("maxFailedLogins", { type: "number", value: s.maxFailedLogins === null ? "" : String(s.maxFailedLogins), extra: ' min="1" max="50"' })),
      fld("锁定时长（分钟）", inp("lockoutMinutes", { type: "number", value: s.lockoutMinutes === null ? "" : String(s.lockoutMinutes), extra: ' min="1" max="1440"' })),
    ])}
    <div class="chips" style="margin-top:12px">
      <label class="chip${s.requireTotp ? " on" : ""}"><input type="checkbox" name="requireTotp"${s.requireTotp ? " checked" : ""}> 强制 TOTP</label>
      <label class="chip${s.allowPasswordLogin ? " on" : ""}"><input type="checkbox" name="allowPasswordLogin"${s.allowPasswordLogin ? " checked" : ""}> 允许密码登录</label>
    </div>
    <div style="margin-top:12px"><button class="btn primary" type="submit">保存策略</button></div>
  </form>` : `<div class="help" style="margin-top:8px">该端策略固定，不能从页面修改。</div>`;
    return pageBar("安全设置") + `<div class="content scroll"><div class="page" style="max-width:640px">
  ${sect("双因素认证（TOTP）", `<div class="kv">
      <div class="k">状态</div><div><span class="st ${esc(totpClass)}"><span class="dot"></span>${s.totpEnabled ? "已启用" : "未启用"}</span></div>
      <div class="k">登记时间</div><div title="${esc(HcbData.fmtFull(s.totpEnrolledAt))}">${rel(s.totpEnrolledAt)}</div>
      <div class="k">强制 2FA</div><div>${s.requireTotp ? "是" : "否"}</div>
      <div class="k">密码</div><div>${s.passwordSet ? "是" : "否"}</div>
    </div><div style="margin-top:12px;display:flex;gap:8px;flex-wrap:wrap">${totpBtn}</div>`)}
  ${sect("修改密码", pwForm)}
  ${sect("会话与锁定策略", policyRows + settings)}
  ${d.caps.localAccess ? sect("本机访问", localCard(d.localAccess)) : ""}
  ${d.caps.followTheme ? "" : sect("外观", `<button class="btn" data-act="theme">${ic(document.documentElement.dataset.theme === "dark" ? "sun" : "moon")}切换浅色 / 深色</button>`)}
  </div></div>`;
  }

  // ---------------------------------------------------------------- 策略 / 时间窗（App）
  /** 工具策略与时间窗的只读展示 + 内联 JSON 编辑表单（`caps.policies` 才渲染）。 */
  function policySection(kind, id, item) {
    const formId = kind === "token" ? "token-policy" : "oauth-policy";
    return `<div class="dsec"><h3>工具策略与时间窗</h3>
    <div class="kv">
      <div class="k">工具策略</div><div>${jsonCell(item.policy)}</div>
      <div class="k">时间窗</div><div>${jsonCell(item.window)}</div>
    </div>
    <div style="margin-top:12px"><button class="btn sm" type="button" data-act="policy-edit" data-form-id="${esc(formId)}">${ic("edit")}编辑</button></div>
    <form id="${esc(formId)}" data-form="${esc(formId)}" data-id="${esc(id)}" hidden>
      <div class="fld" style="margin-top:12px">
        <label>工具策略（JSON）</label>
        ${textarea("policy", item.policy, '留空 = 不限；[] = 全禁；["hc_status"] = 白名单', 3)}
        <span class="help">只接受 null 或数组；留空等于 null（不限）。</span>
      </div>
      <div class="fld" style="margin-top:12px">
        <label>时间窗（JSON）</label>
        ${textarea("window", item.window, '留空 = 不限；{"days":[1,2,3,4,5],"start":"08:00","end":"23:00"}', 3)}
        <span class="help">只接受 null 或对象；留空等于 null（不限时）。</span>
      </div>
      <div class="help warn" data-form-err style="margin-top:8px;display:none"></div>
      <div style="display:flex;gap:8px;margin-top:12px">
        <button class="btn primary" type="submit">保存</button>
        <button class="btn ghost" type="button" data-act="policy-cancel" data-form-id="${esc(formId)}">取消</button>
      </div>
    </form>
  </div>`;
  }

  // ---------------------------------------------------------------- 抽屉
  function drawer(kind, id) {
    const map = {
      user: drawerUser, session: drawerSession, token: drawerToken,
      oauth: drawerOauth, coverage: drawerCoverage, sleep: drawerSleep,
    };
    const fn = map[kind];
    return fn ? fn(id) : null;
  }

  return {
    NAV, ROUTES, DRAWER_ROUTES,
    shell, page, drawer, loading, failure, esc, ic, table, mcards, stSpan, emptyState,
    sparkline, covBar, userOf,
  };
})();
