<script>
"use strict";
const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
const ic = (n, cls = "") => `<svg class="i ${cls}"><use href="#i-${n}"/></svg>`;
const pad = (n) => String(n).padStart(2, "0");
function rel(iso) {
  if (!iso) return "—";
  const d = NOW - new Date(iso);
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
  return fmtDate(iso);
}
const sh = (iso) => new Date(new Date(iso).getTime() + 8 * H);
function fmtDate(iso) { const t = sh(iso); return `${t.getUTCFullYear()}-${pad(t.getUTCMonth() + 1)}-${pad(t.getUTCDate())}`; }
function fmt(iso) { if (!iso) return "—"; const t = sh(iso); return `${pad(t.getUTCMonth() + 1)}-${pad(t.getUTCDate())} ${pad(t.getUTCHours())}:${pad(t.getUTCMinutes())}`; }
function fmtFull(iso) { if (!iso) return "—"; const t = sh(iso); return `${fmtDate(iso)} ${pad(t.getUTCHours())}:${pad(t.getUTCMinutes())}:${pad(t.getUTCSeconds())}`; }
const short = (id) => (id && id.length > 14 ? id.slice(0, 10) + "…" : id || "");
const idc = (id, label) => `<button class="idc" data-copy="${esc(id)}" title="${esc(id)} · 点击复制">${esc(label ?? short(id))}${ic("copy")}</button>`;
const num = (n) => n == null ? "—" : Number(n).toLocaleString("zh-CN");
const stSpan = (map, k) => { const [l, c] = map[k] || [k, "off"]; return `<span class="st ${c}"><span class="dot"></span>${l}</span>`; };
const srcPill = (s) => `<span class="src-pill ${esc(s.split("_")[0])}">${esc(SRC_LABEL[s] || s)}</span>`;
const pageBar = (title, extra = "", cnt = "") => `<header class="bar"><h1>${title}${cnt !== "" ? `<span class="cnt">${cnt}</span>` : ""}</h1><span class="grow"></span>${extra}</header>`;
const searchBox = (ph, val, key) => `<label class="search">${ic("search")}<input data-search="${key}" placeholder="${esc(ph)}" value="${esc(val || "")}" enterkeyhint="search"><kbd class="hide-m">/</kbd></label>`;
const moreBtn = (kind, id) => `<button class="ibtn" data-menu="${kind}" data-id="${esc(id)}" aria-label="更多操作" title="更多操作">${ic("more")}</button>`;
const emptyState = (icn, title, desc, action = "") => `<div class="empty"><div class="ic">${ic(icn)}</div><b>${title}</b><span>${desc}</span>${action}</div>`;
function table(cols, rows, empty) {
  if (!rows.length) return `<div class="tw">${empty}</div>`;
  return `<div class="tw hide-m"><div class="tbl-scroll"><table class="tbl"><colgroup>${cols.map((c) => `<col${c[1] ? ` style="width:${c[1]}"` : ""}>`).join("")}</colgroup><thead><tr>${cols.map((c) => `<th class="${c[2] || ""}">${c[0]}</th>`).join("")}</tr></thead><tbody>${rows.join("")}</tbody></table></div></div>`;
}
const mcards = (cards) => (cards.length ? `<div class="mcards">${cards.join("")}</div>` : "");

function sparkline(vals, cls = "") {
  if (!vals || !vals.length) return "";
  const w = 240, h = 48, padY = 4;
  const min = Math.min(...vals), max = Math.max(...vals);
  const span = max - min || 1;
  const pts = vals.map((v, i) => {
    const x = (i / (vals.length - 1 || 1)) * w;
    const y = h - padY - ((v - min) / span) * (h - padY * 2);
    return [x, y];
  });
  const line = pts.map((p, i) => (i ? "L" : "M") + p[0].toFixed(1) + "," + p[1].toFixed(1)).join(" ");
  const area = `M0,${h} ` + pts.map((p) => `L${p[0].toFixed(1)},${p[1].toFixed(1)}`).join(" ") + ` L${w},${h} Z`;
  return `<svg class="spark ${cls}" viewBox="0 0 ${w} ${h}" preserveAspectRatio="none" aria-hidden="true"><path class="area" d="${area}"/><path class="line" d="${line}"/></svg>`;
}

function covBar(pct) {
  const cls = pct >= 0.9 ? "" : pct >= 0.7 ? "amber" : "danger";
  return `<div class="cov-bar ${cls}" title="${Math.round(pct * 100)}%"><i style="width:${Math.round(pct * 100)}%"></i></div>`;
}

function userOf(id) { return USERS.find((u) => u.id === id); }

// ---------------------------------------------------------------- login / otp
function renderLogin() {
  return `<div class="login"><form class="box" data-form="login">
  <h1><svg class="logo" style="width:28px;height:28px"><use href="#logo"/></svg>HC Health</h1>
  <p class="lede" style="margin:0">管理后台登录。原型任意非空密码进入下一步 TOTP。</p>
  ${S.loginErr ? `<div class="banner danger">${ic("alert")}<div>密码不正确或会话已失效</div></div>` : ""}
  ${S.loginLocked ? `<div class="banner amber">${ic("lock")}<div>连续失败已临时锁定（dev=lock）。稍后重试。</div></div>` : ""}
  <div class="fld"><label>用户名</label><input class="inp" name="username" autocomplete="username" value="hiyo" ${S.loginLocked ? "disabled" : ""}></div>
  <div class="fld"><label>管理密码</label><div class="pw"><input class="inp" type="password" name="password" autocomplete="current-password" placeholder="输入管理密码" ${S.loginLocked ? "disabled" : ""} autofocus><button type="button" class="ibtn" data-act="pwtoggle" aria-label="显示密码">${ic("eye")}</button></div></div>
  <button class="btn primary" type="submit" ${S.loginLocked ? "disabled" : ""}>继续</button>
  <div class="foot"><span>v${VERSION}</span><button type="button" class="btn ghost sm" data-act="theme">${ic(document.documentElement.dataset.theme === "dark" ? "sun" : "moon")}主题</button></div>
  </form></div>`;
}

function renderOtp() {
  return `<div class="login"><form class="box" data-form="otp">
  <h1><svg class="logo" style="width:28px;height:28px"><use href="#logo"/></svg>双因素验证</h1>
  <p class="lede" style="margin:0">输入身份验证器中的 6 位验证码。</p>
  ${S.otpErr ? `<div class="banner danger">${ic("alert")}<div>验证码不正确，请重试</div></div>` : ""}
  <div class="otp-row" data-otp>
    ${[0,1,2,3,4,5].map((i) => `<input class="inp" inputmode="numeric" maxlength="1" pattern="[0-9]" aria-label="第 ${i + 1} 位" data-otp-i="${i}">`).join("")}
  </div>
  <p class="otp-hint">原型任意 6 位数字即可通过</p>
  <button class="btn primary" type="submit">验证并进入</button>
  <div class="foot"><a href="#/login">返回登录</a><button type="button" class="btn ghost sm" data-act="theme">${ic(document.documentElement.dataset.theme === "dark" ? "sun" : "moon")}主题</button></div>
  </form></div>`;
}

// ---------------------------------------------------------------- overview
function renderOverview() {
  const rows = COVERAGE.slice(0, 5).map((c) => `<tr class="click" data-open="coverage" data-id="${c.type}">
    <td><div class="nm"><b>${esc(c.label)}</b><span class="sub2 mono">${esc(c.type)}</span></div></td>
    <td class="num cellt">${num(c.rows)}</td>
    <td>${c.sources.map(srcPill).join(" ")}</td>
    <td>${covBar(c.pct)}<span class="muted num" style="font-size:13px">${Math.round(c.pct * 100)}%</span></td>
    <td class="cellt num" title="${esc(fmtFull(c.lastAt))}">${rel(c.lastAt)}</td></tr>`);
  const cards = COVERAGE.slice(0, 5).map((c) => `<div class="mc" data-open="coverage" data-id="${c.type}"><div class="b"><b>${esc(c.label)}</b><div class="meta"><span class="num">${num(c.rows)} 行</span><span>${Math.round(c.pct * 100)}% · 30 日</span><span>${rel(c.lastAt)}</span></div></div></div>`);
  const failN = AUDIT.filter((a) => a.outcome === "fail" && NOW - new Date(a.at) < D).length;
  return pageBar("概览", `<button class="btn sm" data-act="refresh">${ic("refresh")}刷新</button>`) + `<div class="content scroll"><div class="page">
  <div class="stats">
    <a class="stat" href="#/coverage"><div class="t">${ic("heart")}心率样本</div><div class="n">${num(1248356)}</div><div class="d">最近同步 ${rel(SYNC.lastSyncAt)}</div></a>
    <a class="stat" href="#/coverage"><div class="t">${ic("cover")}覆盖类型</div><div class="n">${COVERAGE.length}</div><div class="d">${COVERAGE.filter((c) => c.pct < 0.9).length ? `<span class="w">${COVERAGE.filter((c) => c.pct < 0.9).length} 项不足 90%</span>` : "近 30 日良好"}</div></a>
    <a class="stat" href="#/sessions"><div class="t">${ic("session")}活跃会话</div><div class="n">${SESSIONS.filter((s) => NOW - new Date(s.lastSeen) < D).length}</div><div class="d">${USERS.filter((u) => u.status === "active").length} 个有效用户</div></a>
    <a class="stat" href="#/audit"><div class="t">${ic("shield")}24h 审计失败</div><div class="n">${failN}</div><div class="d">${failN ? `<span class="w">需关注</span>` : "无失败"}</div></a>
  </div>
  <div class="sect"><div class="sect-h"><h2>同步与回填</h2></div>
    <div class="banner acc">${ic("activity")}<div><b>管道正常</b> · 上次同步 ${esc(SRC_LABEL[SYNC.lastSyncSource] || SYNC.lastSyncSource)} · +${num(SYNC.lastSyncRows)} 行 · 延迟约 ${SYNC.lagMin} 分钟<span class="mono" style="display:block;margin-top:4px;color:var(--tx3)">${esc(fmtFull(SYNC.lastSyncAt))}</span></div></div>
    <div class="kv">
      <div class="k">回填状态</div><div>${SYNC.backfill.status === "idle" ? '<span class="st ok"><span class="dot"></span>空闲</span>' : `<span class="st"><span class="dot"></span>${esc(SYNC.backfill.status)}</span>`}<span class="kv-rest">· 进度 ${Math.round(SYNC.backfill.progress * 100)}%</span></div>
      <div class="k">回填区间</div><div class="mono">${esc(SYNC.backfill.rangeFrom)} → ${esc(SYNC.backfill.rangeTo)}</div>
      <div class="k">上次回填</div><div title="${esc(fmtFull(SYNC.backfill.lastRun))}">${rel(SYNC.backfill.lastRun)}</div>
    </div>
  </div>
  <div class="ov2">
    <div class="sect" style="margin:0"><div class="sect-h"><h2>数据覆盖</h2><span class="grow"></span><a href="#/coverage" class="btn ghost sm">全部</a></div>
      ${table([["类型"], ["行数", "110px"], ["来源", "160px"], ["30 日覆盖", "140px"], ["最近", "100px"]], rows, emptyState("db", "暂无覆盖数据", "同步后会出现在这里"))}
      ${mcards(cards)}
    </div>
    <div class="sect" style="margin:0"><div class="sect-h"><h2>快捷入口</h2></div>
      <div class="list">
        <a class="li" href="#/sleep">${ic("sleep")}<div class="grow"><b>睡眠体检</b><div class="muted">缺口与质量标记</div></div>${ic("chev","xs")}</a>
        <a class="li" href="#/metrics">${ic("activity")}<div class="grow"><b>指标趋势</b><div class="muted">步数 / 心率 / 睡眠</div></div>${ic("chev","xs")}</a>
        <a class="li" href="#/users">${ic("users")}<div class="grow"><b>用户与会话</b><div class="muted">${USERS.length} 用户 · ${SESSIONS.length} 会话</div></div>${ic("chev","xs")}</a>
        <a class="li" href="#/security">${ic("lock")}<div class="grow"><b>安全设置</b><div class="muted">TOTP ${SECURITY.totpEnabled ? "已启用" : "未启用"}</div></div>${ic("chev","xs")}</a>
      </div>
    </div>
  </div>
  </div></div>`;
}

// ---------------------------------------------------------------- users
function renderUsers() {
  const q = (S.q.users || "").trim().toLowerCase();
  const list = USERS.filter((u) => !q || u.username.includes(q) || u.display.includes(q) || u.role.includes(q));
  const rows = list.map((u) => `<tr class="click ${u.status === "disabled" ? "dim" : ""} ${S.sub === u.id ? "cur" : ""}" data-open="user" data-id="${u.id}">
    <td><div class="nm"><b>${esc(u.display)}</b><span class="sub2">${idc(u.id, u.username)} · ${esc(ROLE_LABEL[u.role] || u.role)}</span></div></td>
    <td>${stSpan(USER_ST, u.status)}</td>
    <td>${u.totp ? '<span class="tag ok">TOTP</span>' : '<span class="tag">无 TOTP</span>'}</td>
    <td class="cellt num" title="${esc(fmtFull(u.lastLogin))}">${rel(u.lastLogin)}</td>
    <td class="act" data-stop>${moreBtn("user", u.id)}</td></tr>`);
  const cards = list.map((u) => `<div class="mc ${S.sub === u.id ? "cur" : ""}" data-open="user" data-id="${u.id}"><div class="b"><b>${esc(u.display)}</b><div class="meta">${stSpan(USER_ST, u.status)}<span>${esc(ROLE_LABEL[u.role])}</span><span>${u.totp ? "TOTP" : "无 TOTP"}</span><span>${rel(u.lastLogin)}</span></div></div>${moreBtn("user", u.id)}</div>`);
  return pageBar("用户", `<button class="btn primary sm" data-act="new-user">${ic("plus")}新建用户</button>`, list.length) + `<div class="content scroll"><div class="page">
  <div class="toolbar">${searchBox("搜索用户名 / 角色", S.q.users, "users")}</div>
  ${table([["用户"], ["状态", "100px"], ["双因素", "100px"], ["最近登录", "110px"], ["", "48px", "act"]], rows, emptyState("user", "没有匹配的用户", "调整搜索或新建用户"))}
  ${mcards(cards)}
  </div></div>`;
}

function drawerUser(id) {
  const u = userOf(id); if (!u) return closeDrawer();
  const sess = SESSIONS.filter((s) => s.userId === id);
  return { title: esc(u.display), body: `
  <div class="dsec"><h3>基本信息</h3>
    <div class="kv">
      <div class="k">用户名</div><div class="mono">${esc(u.username)}</div>
      <div class="k">角色</div><div>${esc(ROLE_LABEL[u.role] || u.role)} <span class="mono muted">${esc(u.role)}</span></div>
      <div class="k">状态</div><div>${stSpan(USER_ST, u.status)}</div>
      <div class="k">TOTP</div><div>${u.totp ? '<span class="st ok"><span class="dot"></span>已启用</span>' : '<span class="st amber"><span class="dot"></span>未启用</span>'}</div>
      <div class="k">创建</div><div title="${esc(fmtFull(u.createdAt))}">${rel(u.createdAt)}</div>
      <div class="k">备注</div><div>${esc(u.note || "—")}</div>
    </div>
  </div>
  <div class="dsec"><h3>会话（${sess.length}）</h3>
    <div class="list">${sess.length ? sess.map((s) => `<div class="li"><span class="dot ${s.current ? "ok" : ""}"></span><div class="grow"><b>${esc(s.device)}</b><div class="muted">${esc(s.ip)} · ${esc(s.loc)}</div></div><span class="r">${rel(s.lastSeen)}</span></div>`).join("") : '<div class="li muted">无会话</div>'}</div>
  </div>
  <div class="dsec"><h3>危险操作</h3>
    <div class="danger-zone">
      ${u.status === "active" ? `<div class="dz"><div class="t"><b>停用用户</b>将立即失效其会话与令牌写入</div><button class="btn danger-o sm" data-act="disable-user" data-id="${u.id}">停用</button></div>` : `<div class="dz"><div class="t"><b>启用用户</b></div><button class="btn sm" data-act="enable-user" data-id="${u.id}">启用</button></div>`}
      <div class="dz"><div class="t"><b>删除用户</b>不可恢复（需输入用户名确认）</div><button class="btn danger-o sm" data-act="del-user" data-id="${u.id}">删除</button></div>
    </div>
  </div>`, foot: `<button class="btn" data-act="close-drawer">关闭</button><span class="grow"></span><button class="btn primary" data-act="save-user" data-id="${u.id}">保存（原型）</button>` };
}

// ---------------------------------------------------------------- sessions
function renderSessions() {
  const q = (S.q.sessions || "").trim().toLowerCase();
  const list = SESSIONS.filter((s) => {
    const u = userOf(s.userId);
    return !q || s.device.toLowerCase().includes(q) || s.ip.includes(q) || (u && u.username.includes(q));
  });
  const rows = list.map((s) => {
    const u = userOf(s.userId);
    return `<tr class="click ${S.sub === s.id ? "cur" : ""}" data-open="session" data-id="${s.id}">
      <td><div class="nm"><b>${esc(s.device)}</b><span class="sub2">${esc(u?.display || s.userId)}${s.current ? ' · <span class="tag acc">当前</span>' : ""}</span></div></td>
      <td class="mono cellt">${esc(s.ip)}</td>
      <td class="cellt">${esc(s.loc)}</td>
      <td class="cellt num" title="${esc(fmtFull(s.lastSeen))}">${rel(s.lastSeen)}</td>
      <td class="act" data-stop>${moreBtn("session", s.id)}</td></tr>`;
  });
  const cards = list.map((s) => {
    const u = userOf(s.userId);
    return `<div class="mc" data-open="session" data-id="${s.id}"><div class="b"><b>${esc(s.device)}</b><div class="meta"><span>${esc(u?.display || "")}</span><span class="mono">${esc(s.ip)}</span><span>${rel(s.lastSeen)}</span>${s.current ? '<span class="tag acc">当前</span>' : ""}</div></div>${moreBtn("session", s.id)}</div>`;
  });
  return pageBar("会话", "", list.length) + `<div class="content scroll"><div class="page">
  <div class="toolbar">${searchBox("设备 / IP / 用户", S.q.sessions, "sessions")}</div>
  ${table([["设备"], ["IP", "120px"], ["位置", "140px"], ["最近活跃", "110px"], ["", "48px", "act"]], rows, emptyState("session", "没有会话", "登录后会出现在这里"))}
  ${mcards(cards)}
  </div></div>`;
}

function drawerSession(id) {
  const s = SESSIONS.find((x) => x.id === id); if (!s) return closeDrawer();
  const u = userOf(s.userId);
  return { title: esc(s.device), body: `
  <div class="dsec"><h3>会话详情</h3>
    <div class="kv">
      <div class="k">用户</div><div>${esc(u?.display || s.userId)} <span class="mono muted">${esc(u?.username || "")}</span></div>
      <div class="k">IP</div><div class="mono">${esc(s.ip)}</div>
      <div class="k">位置</div><div>${esc(s.loc)}</div>
      <div class="k">创建</div><div title="${esc(fmtFull(s.createdAt))}">${rel(s.createdAt)}</div>
      <div class="k">最近活跃</div><div title="${esc(fmtFull(s.lastSeen))}">${rel(s.lastSeen)}</div>
      <div class="k">当前会话</div><div>${s.current ? "是" : "否"}</div>
    </div>
  </div>
  <div class="dsec"><h3>危险操作</h3>
    <div class="danger-zone"><div class="dz"><div class="t"><b>撤销会话</b>设备需重新登录</div><button class="btn danger-o sm" data-act="revoke-session" data-id="${s.id}" ${s.current ? "disabled title=\"不能撤销当前会话\"" : ""}>撤销</button></div></div>
  </div>`, foot: `<button class="btn" data-act="close-drawer">关闭</button>` };
}

// ---------------------------------------------------------------- tokens
function renderTokens() {
  const rows = TOKENS.map((t) => {
    const u = userOf(t.userId);
    return `<tr class="click ${t.status === "revoked" ? "dim" : ""} ${S.sub === t.id ? "cur" : ""}" data-open="token" data-id="${t.id}">
      <td><div class="nm"><b>${esc(t.name)}</b><span class="sub2 mono">${esc(t.prefix)}</span></div></td>
      <td>${stSpan(TOK_ST, t.status)}</td>
      <td><div class="tags">${t.scopes.map((sc) => `<span class="tag">${esc(sc)}</span>`).join("")}</div></td>
      <td class="cellt">${esc(u?.display || "—")}</td>
      <td class="cellt num" title="${esc(fmtFull(t.lastUsed))}">${rel(t.lastUsed)}</td>
      <td class="act" data-stop>${moreBtn("token", t.id)}</td></tr>`;
  });
  const cards = TOKENS.map((t) => `<div class="mc ${t.status === "revoked" ? "dim" : ""}" data-open="token" data-id="${t.id}"><div class="b"><b>${esc(t.name)}</b><div class="meta">${stSpan(TOK_ST, t.status)}<span class="mono">${esc(t.prefix)}</span><span>${rel(t.lastUsed)}</span></div></div>${moreBtn("token", t.id)}</div>`);
  return pageBar("静态令牌", `<button class="btn primary sm" data-act="new-token">${ic("plus")}新建令牌</button>`, TOKENS.length) + `<div class="content scroll"><div class="page">
  <div class="banner amber">${ic("alert")}<div>新建后明文<strong>只显示一次</strong>。列表仅保留前缀。</div></div>
  ${table([["名称"], ["状态", "90px"], ["权限", "200px"], ["归属", "90px"], ["最近使用", "100px"], ["", "48px", "act"]], rows, emptyState("key", "暂无令牌", "创建后供 MCP / 自动化使用"))}
  ${mcards(cards)}
  </div></div>`;
}

function drawerToken(id) {
  const t = TOKENS.find((x) => x.id === id); if (!t) return closeDrawer();
  const u = userOf(t.userId);
  return { title: esc(t.name), body: `
  <div class="dsec"><h3>令牌</h3>
    <div class="kv">
      <div class="k">前缀</div><div class="mono">${esc(t.prefix)}</div>
      <div class="k">状态</div><div>${stSpan(TOK_ST, t.status)}</div>
      <div class="k">权限</div><div class="tags">${t.scopes.map((sc) => `<span class="tag">${esc(sc)}</span>`).join("")}</div>
      <div class="k">归属</div><div>${esc(u?.display || t.userId)}</div>
      <div class="k">创建</div><div>${rel(t.createdAt)}</div>
      <div class="k">最近使用</div><div>${rel(t.lastUsed)}</div>
      <div class="k">过期</div><div>${t.expires ? rel(t.expires) : "永不过期"}</div>
    </div>
  </div>
  ${t.status === "active" ? `<div class="dsec"><h3>危险操作</h3><div class="danger-zone"><div class="dz"><div class="t"><b>撤销令牌</b>立即失效，不可恢复明文</div><button class="btn danger-o sm" data-act="revoke-token" data-id="${t.id}">撤销</button></div></div></div>` : ""}`, foot: `<button class="btn" data-act="close-drawer">关闭</button>` };
}

// ---------------------------------------------------------------- oauth
function renderOauth() {
  const rows = OAUTH.map((c) => `<tr class="click ${c.status === "disabled" ? "dim" : ""} ${S.sub === c.id ? "cur" : ""}" data-open="oauth" data-id="${c.id}">
    <td><div class="nm"><b>${esc(c.name)}</b><span class="sub2 mono">${esc(c.clientId)}</span></div></td>
    <td><span class="kind">${esc(c.kind)}</span></td>
    <td>${stSpan(OAUTH_ST, c.status)}</td>
    <td class="cellt num" title="${esc(fmtFull(c.lastAuth))}">${rel(c.lastAuth)}</td>
    <td class="act" data-stop>${moreBtn("oauth", c.id)}</td></tr>`);
  const cards = OAUTH.map((c) => `<div class="mc" data-open="oauth" data-id="${c.id}"><div class="b"><b>${esc(c.name)}</b><div class="meta">${stSpan(OAUTH_ST, c.status)}<span class="mono">${esc(c.clientId)}</span><span>${rel(c.lastAuth)}</span></div></div>${moreBtn("oauth", c.id)}</div>`);
  return pageBar("OAuth 客户端", `<button class="btn primary sm" data-act="new-oauth">${ic("plus")}新建客户端</button>`, OAUTH.length) + `<div class="content scroll"><div class="page">
  ${table([["客户端"], ["类型", "110px"], ["状态", "90px"], ["最近授权", "110px"], ["", "48px", "act"]], rows, emptyState("app", "暂无客户端", "创建后供 OAuth 接入"))}
  ${mcards(cards)}
  </div></div>`;
}

function drawerOauth(id) {
  const c = OAUTH.find((x) => x.id === id); if (!c) return closeDrawer();
  return { title: esc(c.name), body: `
  <div class="dsec"><h3>客户端</h3>
    <div class="kv">
      <div class="k">client_id</div><div>${idc(c.clientId)}</div>
      <div class="k">类型</div><div>${esc(c.kind)}</div>
      <div class="k">状态</div><div>${stSpan(OAUTH_ST, c.status)}</div>
      <div class="k">回调</div><div>${c.redirects.map((r) => `<div class="mono" style="margin-bottom:4px">${esc(r)}</div>`).join("")}</div>
      <div class="k">创建</div><div>${rel(c.createdAt)}</div>
      <div class="k">最近授权</div><div>${rel(c.lastAuth)}</div>
    </div>
  </div>
  <div class="dsec"><h3>危险操作</h3>
    <div class="danger-zone">
      ${c.status === "active" ? `<div class="dz"><div class="t"><b>停用客户端</b></div><button class="btn danger-o sm" data-act="disable-oauth" data-id="${c.id}">停用</button></div>` : `<div class="dz"><div class="t"><b>启用客户端</b></div><button class="btn sm" data-act="enable-oauth" data-id="${c.id}">启用</button></div>`}
      <div class="dz"><div class="t"><b>删除客户端</b>不可恢复</div><button class="btn danger-o sm" data-act="del-oauth" data-id="${c.id}">删除</button></div>
    </div>
  </div>`, foot: `<button class="btn" data-act="close-drawer">关闭</button>` };
}

// ---------------------------------------------------------------- coverage
function renderCoverage() {
  const rows = COVERAGE.map((c) => `<tr class="click" data-open="coverage" data-id="${c.type}">
    <td><div class="nm"><b>${esc(c.label)}</b><span class="sub2 mono">${esc(c.type)}</span></div></td>
    <td class="num cellt">${num(c.rows)}</td>
    <td>${c.sources.map(srcPill).join(" ")}</td>
    <td class="num cellt">${c.days30}/30</td>
    <td>${covBar(c.pct)}<span class="muted num" style="font-size:13px;margin-left:6px">${Math.round(c.pct * 100)}%</span></td>
    <td class="cellt num" title="${esc(fmtFull(c.lastAt))}">${rel(c.lastAt)}</td></tr>`);
  const cards = COVERAGE.map((c) => `<div class="mc" data-open="coverage" data-id="${c.type}"><div class="b"><b>${esc(c.label)}</b><div class="meta"><span class="num">${num(c.rows)}</span><span>${c.days30}/30 天</span><span>${Math.round(c.pct * 100)}%</span><span>${rel(c.lastAt)}</span></div><div class="meta">${c.sources.map(srcPill).join(" ")}</div></div></div>`);
  return pageBar("数据覆盖", `<button class="btn sm" data-act="refresh">${ic("refresh")}刷新</button>`, COVERAGE.length) + `<div class="content scroll"><div class="page">
  <p class="lede">按指标类型与来源查看入库规模与近 30 日覆盖。行数规模对齐现网（如 heart_rate ≈ 1.2M）。</p>
  ${table([["指标"], ["行数", "110px"], ["来源", "180px"], ["有数天数", "90px"], ["覆盖", "140px"], ["最近写入", "100px"]], rows, emptyState("cover", "暂无覆盖", ""))}
  ${mcards(cards)}
  </div></div>`;
}

function drawerCoverage(type) {
  const c = COVERAGE.find((x) => x.type === type); if (!c) return closeDrawer();
  return { title: esc(c.label), body: `
  <div class="dsec"><h3>覆盖详情</h3>
    <div class="kv">
      <div class="k">类型</div><div class="mono">${esc(c.type)}</div>
      <div class="k">总行数</div><div class="num">${num(c.rows)}</div>
      <div class="k">来源</div><div>${c.sources.map(srcPill).join(" ")}</div>
      <div class="k">近 30 日</div><div>${c.days30} / 30 天有数据 · ${Math.round(c.pct * 100)}%</div>
      <div class="k">最近写入</div><div title="${esc(fmtFull(c.lastAt))}">${rel(c.lastAt)} · ${esc(fmtFull(c.lastAt))}</div>
    </div>
  </div>
  <div class="dsec"><h3>操作（原型）</h3>
    <div style="display:flex;flex-wrap:wrap;gap:8px">
      <button class="btn sm" data-act="backfill-type" data-id="${c.type}">${ic("refresh")}触发回填</button>
      <a class="btn sm" href="#/metrics">${ic("activity")}查看趋势</a>
    </div>
  </div>`, foot: `<button class="btn" data-act="close-drawer">关闭</button>` };
}

// ---------------------------------------------------------------- sleep
function renderSleep() {
  const rows = SLEEP_AUDIT.map((s) => `<tr class="click ${s.flags.includes("missing") ? "fail" : ""}" data-open="sleep" data-id="${s.id}">
    <td class="mono num">${esc(s.date)}</td>
    <td class="cellt num">${esc(s.bed)} → ${esc(s.wake)}</td>
    <td class="num cellt">${s.hours == null ? "—" : s.hours.toFixed(2) + " h"}</td>
    <td class="num cellt">${s.score == null ? "—" : s.score}</td>
    <td>${s.flags.length ? s.flags.map((f) => `<span class="tag ${f === "missing" ? "danger" : "amber"}">${esc(f)}</span>`).join(" ") : '<span class="tag ok">正常</span>'}</td>
    <td class="cellt">${esc(s.note || "—")}</td></tr>`);
  const cards = SLEEP_AUDIT.map((s) => `<div class="mc ${s.flags.includes("missing") ? "fail" : ""}" data-open="sleep" data-id="${s.id}"><div class="b"><b class="mono">${esc(s.date)}</b><div class="meta"><span>${esc(s.bed)} → ${esc(s.wake)}</span><span>${s.hours == null ? "—" : s.hours.toFixed(2) + " h"}</span><span>分 ${s.score ?? "—"}</span>${s.flags.map((f) => `<span class="tag amber">${esc(f)}</span>`).join("")}</div></div></div>`);
  return pageBar("睡眠体检", "", SLEEP_AUDIT.length) + `<div class="content scroll"><div class="page">
  <p class="lede">逐日睡眠样本质检：缺口、偏短、入睡偏晚、碎片化等标记。</p>
  ${table([["日期", "110px"], ["入睡 → 起床", "140px"], ["时长", "90px"], ["评分", "70px"], ["标记", "160px"], ["说明"]], rows, emptyState("sleep", "暂无睡眠记录", ""))}
  ${mcards(cards)}
  </div></div>`;
}

function drawerSleep(id) {
  const s = SLEEP_AUDIT.find((x) => x.id === id); if (!s) return closeDrawer();
  return { title: esc(s.date), body: `
  <div class="dsec"><h3>睡眠日详</h3>
    <div class="kv">
      <div class="k">入睡</div><div class="num">${esc(s.bed)}</div>
      <div class="k">起床</div><div class="num">${esc(s.wake)}</div>
      <div class="k">时长</div><div class="num">${s.hours == null ? "—" : s.hours.toFixed(2) + " 小时"}</div>
      <div class="k">评分</div><div class="num">${s.score ?? "—"}</div>
      <div class="k">来源</div><div>${s.source === "—" ? "—" : srcPill(s.source)}</div>
      <div class="k">标记</div><div>${s.flags.length ? s.flags.map((f) => `<span class="tag amber">${esc(f)}</span>`).join(" ") : "无"}</div>
      <div class="k">说明</div><div>${esc(s.note || "—")}</div>
    </div>
  </div>`, foot: `<button class="btn" data-act="close-drawer">关闭</button>` };
}

// ---------------------------------------------------------------- metrics
function renderMetrics() {
  const cards = [
    ["steps", "steps", ""],
    ["heart_rate", "heart", "hr"],
    ["sleep", "sleep", "sleep"],
  ].map(([key, icon, cls]) => {
    const m = METRICS[key];
    return `<div class="chart-card"><div class="t">${ic(icon)}${esc(m.label)}</div><div class="n">${typeof m.latest === "number" && m.latest > 100 ? num(m.latest) : m.latest}<span class="muted" style="font-size:14px;font-weight:400;margin-left:6px">${esc(m.unit)}</span></div><div class="muted" style="font-size:13px">14 日均 ${m.avg14}${esc(m.unit === "步" ? "" : " " + m.unit)}</div>${sparkline(m.series, cls)}</div>`;
  }).join("");
  return pageBar("指标趋势", `<div class="seg"><button class="on">14 日</button><button disabled title="原型">30 日</button><button disabled title="原型">90 日</button></div>`) + `<div class="content scroll"><div class="page">
  <p class="lede">步数 / 心率 / 睡眠近 14 日 sparkline（SVG）。数据为 mock，形状贴近现网波动。</p>
  <div class="chart-grid">${cards}</div>
  <div class="sect"><div class="sect-h"><h2>说明</h2></div>
    <div class="banner">${ic("info")}<div>生产环境将对接聚合查询；原型不请求真实数据库。体重等多来源指标见「数据覆盖」。</div></div>
  </div>
  </div></div>`;
}

// ---------------------------------------------------------------- audit
function renderAudit() {
  const filter = S.auditFilter || "all";
  const list = AUDIT.filter((a) => filter === "all" || a.outcome === filter);
  const rows = list.map((a) => `<tr class="${a.outcome === "fail" ? "fail" : ""}">
    <td class="cellt num" title="${esc(fmtFull(a.at))}">${rel(a.at)}</td>
    <td>${esc(a.actor)}</td>
    <td><span class="mono" style="font-size:13px">${esc(a.action)}</span></td>
    <td class="mono cellt">${esc(a.target)}</td>
    <td>${stSpan(OUT_ST, a.outcome)}</td>
    <td class="cellt">${esc(a.detail)}</td></tr>`);
  const cards = list.map((a) => `<div class="mc ${a.outcome === "fail" ? "fail" : ""}"><div class="b"><b class="mono">${esc(a.action)}</b><div class="meta">${stSpan(OUT_ST, a.outcome)}<span>${esc(a.actor)}</span><span class="mono">${esc(a.target)}</span><span>${rel(a.at)}</span></div><div class="meta">${esc(a.detail)}</div></div></div>`);
  return pageBar("审计", "", list.length) + `<div class="content scroll"><div class="page">
  <div class="toolbar">
    <div class="seg" data-seg="audit">
      <button data-v="all" class="${filter === "all" ? "on" : ""}">全部</button>
      <button data-v="ok" class="${filter === "ok" ? "on" : ""}">成功</button>
      <button data-v="fail" class="${filter === "fail" ? "on" : ""}">失败<span class="n">${AUDIT.filter((a) => a.outcome === "fail").length}</span></button>
    </div>
  </div>
  ${table([["时间", "100px"], ["主体", "80px"], ["动作", "140px"], ["对象", "110px"], ["结果", "80px"], ["详情"]], rows, emptyState("list", "没有审计记录", ""))}
  ${mcards(cards)}
  </div></div>`;
}

// ---------------------------------------------------------------- security
function renderSecurity() {
  return pageBar("安全设置") + `<div class="content scroll"><div class="page" style="max-width:640px">
  <div class="sect"><div class="sect-h"><h2>双因素认证（TOTP）</h2></div>
    <div class="kv">
      <div class="k">状态</div><div>${SECURITY.totpEnabled ? '<span class="st ok"><span class="dot"></span>已启用</span>' : '<span class="st danger"><span class="dot"></span>未启用</span>'}</div>
      <div class="k">登记时间</div><div title="${esc(fmtFull(SECURITY.totpEnrolledAt))}">${rel(SECURITY.totpEnrolledAt)}</div>
      <div class="k">强制 TOTP</div><div>${SECURITY.requireTotp ? "是（所有管理员）" : "否"}</div>
    </div>
    <div style="margin-top:12px;display:flex;gap:8px;flex-wrap:wrap">
      <button class="btn sm" data-act="totp-reset">${ic("refresh")}重新绑定（原型）</button>
    </div>
  </div>
  <div class="sect"><div class="sect-h"><h2>修改密码</h2></div>
    <form class="grid2" data-form="password" style="gap:var(--s3)">
      <div class="fld" style="grid-column:1/-1"><label>当前密码</label><input class="inp" type="password" name="cur" autocomplete="current-password"></div>
      <div class="fld"><label>新密码</label><input class="inp" type="password" name="nw" autocomplete="new-password"></div>
      <div class="fld"><label>确认新密码</label><input class="inp" type="password" name="nw2" autocomplete="new-password"></div>
      <div style="grid-column:1/-1"><button class="btn primary" type="submit">保存密码（原型）</button>
        <span class="help" style="margin-left:12px">上次修改：${rel(SECURITY.passwordChangedAt)}</span></div>
    </form>
  </div>
  <div class="sect"><div class="sect-h"><h2>会话与锁定策略</h2></div>
    <div class="kv">
      <div class="k">会话有效期</div><div>${SECURITY.sessionTtlHours} 小时</div>
      <div class="k">密码登录</div><div>${SECURITY.allowPasswordLogin ? "允许" : "禁止"}</div>
      <div class="k">失败上限</div><div>${SECURITY.maxFailedLogins} 次 / 锁定 ${SECURITY.lockoutMinutes} 分钟</div>
    </div>
  </div>
  <div class="sect"><div class="sect-h"><h2>外观</h2></div>
    <button class="btn" data-act="theme">${ic(document.documentElement.dataset.theme === "dark" ? "sun" : "moon")}切换浅色 / 深色</button>
  </div>
  </div></div>`;
}
</script>
