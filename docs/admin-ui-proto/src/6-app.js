<script>
"use strict";
const storageKey = (n) => "hc-health-admin." + n;
const params = new URLSearchParams(location.search);
const DEV = params.get("dev") === "1";
const devp = (k) => (DEV ? params.get(k) : null);
const S = {
  route: "overview", sub: null, q: {},
  authed: true, loginErr: false, loginLocked: false, otpErr: false,
  auditFilter: "all",
};
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
const isMobile = () => matchMedia("(max-width:720px)").matches;

function setTheme(t) {
  document.documentElement.dataset.theme = t;
  const meta = $('meta[name="theme-color"]');
  if (meta) meta.content = t === "light" ? "#f7f7f8" : "#101318";
  try { localStorage.setItem(storageKey("theme"), t); } catch {}
}

function parseHash() {
  const h = location.hash.replace(/^#\/?/, "");
  const [path] = h.split("?");
  const parts = path.split("/").filter(Boolean);
  S.route = parts[0] || "overview";
  S.sub = parts[1] || null;
}
function go(hash, replace) {
  if (replace) history.replaceState(null, "", hash);
  else history.pushState(null, "", hash);
  onRoute();
}
function onRoute() {
  parseHash();
  closeMenu();
  if (S.route === "login") {
    S.authed = false;
    if (S.sub === "otp") { /* stay */ }
  } else if (!S.authed && S.route !== "login") {
    // allow deep links when already marked authed; cold start defaults authed for design browsing
  }
  render();
  const kind = DRAWER_ROUTES[S.route];
  if (S.sub && kind && S.route !== "login") openDrawer(kind, S.sub, true);
  else if (S.route !== "login") closeDrawer(true);
}

function navCount(k) {
  return {
    users: USERS.length,
    sessions: SESSIONS.length,
    tokens: TOKENS.filter((t) => t.status === "active").length,
    oauth: OAUTH.filter((c) => c.status === "active").length,
    coverage: COVERAGE.length,
    sleep: SLEEP_AUDIT.length,
    audit: AUDIT.length,
  }[k];
}

function sidebar() {
  let html = "";
  for (const n of NAV) {
    if (n === null) continue;
    if (typeof n === "string") { html += `<div class="sec-h">${n}</div>`; continue; }
    const [k, l, icn] = n;
    const cnt = navCount(k);
    html += `<a class="ni ${S.route === k ? "on" : ""}" href="#/${k}">${ic(icn)}<span class="lbl">${l}</span><span class="cnt">${cnt ?? ""}</span></a>`;
  }
  return `<aside class="side"><div class="bar brand"><svg class="logo"><use href="#logo"/></svg><b>HC Health</b><span>admin</span></div>
  <nav class="side-body scroll">${html}</nav>
  <div class="side-foot">
    <span class="ver grow">v${VERSION}</span>
    <button class="ibtn" data-act="theme" title="切换主题 (t)" aria-label="切换主题">${ic(document.documentElement.dataset.theme === "dark" ? "sun" : "moon")}</button>
    <button class="ibtn" data-act="kbd" title="快捷键 (?)" aria-label="快捷键">${ic("kbd")}</button>
    <button class="ibtn" data-act="logout" title="退出登录" aria-label="退出">${ic("out")}</button>
  </div></aside>`;
}

function mnav() {
  // 4 IA groups: 概览 / 身份(用户) / 健康(覆盖) / 更多(系统+全部)
  const items = [
    ["overview", "概览", "home"],
    ["users", "身份", "users"],
    ["coverage", "健康", "heart"],
    ["more", "更多", "menu"],
  ];
  const prim = ["overview", "users", "coverage"];
  return `<nav class="mnav" aria-label="移动导航">${items.map(([k, l, icn]) => {
    const href = k === "more" ? "#" : "#/" + k;
    const on = k === "more" ? !prim.includes(S.route) : S.route === k;
    return `<a href="${href}" ${k === "more" ? 'data-act="mmore"' : ""} class="${on ? "on" : ""}">${ic(icn)}<span>${l}</span></a>`;
  }).join("")}</nav>`;
}

function render() {
  const root = $("#root");
  if (S.route === "login" && S.sub === "otp") { root.innerHTML = renderOtp(); bindOtpInputs(); return; }
  if (S.route === "login" || !S.authed) { root.innerHTML = renderLogin(); return; }
  const map = {
    overview: renderOverview, users: renderUsers, sessions: renderSessions,
    tokens: renderTokens, oauth: renderOauth, coverage: renderCoverage,
    sleep: renderSleep, metrics: renderMetrics, audit: renderAudit, security: renderSecurity,
  };
  let main = (map[S.route] || renderOverview)();
  if (devp("empty") === S.route) {
    const title = NAV.find((n) => n && n[0] === S.route)?.[1] || "";
    main = pageBar(title) + `<div class="content"><div class="page">${emptyState("info", "这里还没有内容", "空状态示例（?dev=1&empty=" + S.route + "）")}</div></div>`;
  }
  const st = $(".content")?.scrollTop;
  root.innerHTML = `<div class="app">${sidebar()}<main class="main">${main}</main></div>${mnav()}`;
  if (st && $(".content")) $(".content").scrollTop = st;
}

function bindOtpInputs() {
  const inputs = $$("[data-otp-i]");
  inputs.forEach((inp, i) => {
    inp.addEventListener("input", () => {
      inp.value = inp.value.replace(/\D/g, "").slice(0, 1);
      if (inp.value && i < inputs.length - 1) inputs[i + 1].focus();
    });
    inp.addEventListener("keydown", (e) => {
      if (e.key === "Backspace" && !inp.value && i > 0) inputs[i - 1].focus();
    });
    inp.addEventListener("paste", (e) => {
      const t = (e.clipboardData.getData("text") || "").replace(/\D/g, "").slice(0, 6);
      if (!t) return;
      e.preventDefault();
      t.split("").forEach((ch, j) => { if (inputs[j]) inputs[j].value = ch; });
      inputs[Math.min(t.length, 5)]?.focus();
    });
  });
  setTimeout(() => inputs[0]?.focus(), 30);
}

// ---------------------------------------------------------------- toast / modal / menu
let toastT;
function toast(msg, opts = {}) {
  const t = $("#toast");
  t.className = "toast on" + (opts.err ? " err" : "");
  t.innerHTML = `${ic(opts.err ? "alert" : "checkc")}<span>${msg}</span>`;
  clearTimeout(toastT); toastT = setTimeout(() => t.classList.remove("on"), opts.ms || 3200);
}
let modalOk = null;
function confirmBox({ title, body, list, ok = "确认", danger = true, type, onOk, extra = "" }) {
  const m = $("#modal");
  m.innerHTML = `<h3>${title}</h3>${body ? `<p>${body}</p>` : ""}${list ? `<ul>${list.map((x) => `<li>${x}</li>`).join("")}</ul>` : ""}${extra}${type ? `<div class="tc">输入 <code>${esc(type)}</code> 以确认</div><input class="inp" data-typeconf autocomplete="off" enterkeyhint="done">` : ""}<div class="acts"><button class="btn ghost" data-act="mcancel">取消</button><button class="btn ${danger ? "danger" : "primary"}" data-act="mok" ${type ? "disabled" : ""}>${ok}</button></div>`;
  m.classList.add("on"); $("#mscrim").classList.add("on");
  modalOk = onOk; m.dataset.type = type || "";
  setTimeout(() => (type ? $("[data-typeconf]", m) : $("[data-act=mok]", m))?.focus(), 30);
}
function closeModal() { $("#modal").classList.remove("on"); $("#mscrim").classList.remove("on"); modalOk = null; $("#modal").classList.remove("kbdp"); }

function showKbd() {
  const row = (k, l) => `<div class="row"><span>${l}</span><span>${k.split(" ").map((x) => `<kbd>${x}</kbd>`).join(" ")}</span></div>`;
  const m = $("#modal"); m.classList.add("kbdp");
  m.innerHTML = `<h3>键盘快捷键</h3><div class="cols"><h4>导航</h4>${NAV.filter(Array.isArray).map((n) => row("g " + n[3], n[1])).join("")}<h4>通用</h4>${row("/", "搜索")}${row("Esc", "关闭抽屉 / 弹窗")}${row("t", "切换主题")}${row("?", "本面板")}</div><div class="acts"><button class="btn" data-act="mcancel">关闭</button></div>`;
  m.classList.add("on"); $("#mscrim").classList.add("on");
}

let menuCtx = null;
function openMenu(btn, kind, id) {
  const items = menuItems(kind, id);
  const m = $("#menu");
  m.innerHTML = items.map((it) => it === "|" ? "<hr>" : `<button data-mi="${it[0]}" class="${it[2] || ""}">${ic(it[3] || "chev")}<span class="grow">${it[1]}</span></button>`).join("");
  m.classList.add("on");
  menuCtx = { kind, id };
  const r = btn.getBoundingClientRect();
  const mw = m.offsetWidth, mh = m.offsetHeight;
  let left = Math.min(r.right - mw, innerWidth - mw - 8);
  let top = r.bottom + 4;
  if (top + mh > innerHeight - 8) top = Math.max(8, r.top - mh - 4);
  m.style.left = Math.max(8, left) + "px";
  m.style.top = top + "px";
  m.style.bottom = "auto";
  m.style.right = "auto";
  m.style.width = "";
}
function closeMenu() { $("#menu")?.classList.remove("on"); menuCtx = null; }
function menuItems(kind, id) {
  if (kind === "user") {
    const u = userOf(id);
    return [["open", "查看详情", "", "eye"], ["copy", "复制用户名", "", "copy"], "|", u?.status === "active" ? ["disable-user", "停用", "dz2", "ban"] : ["enable-user", "启用", "", "check"], ["del-user", "删除…", "dz2", "trash"]];
  }
  if (kind === "session") return [["open", "查看详情", "", "eye"], ["revoke-session", "撤销会话", "dz2", "ban"]];
  if (kind === "token") {
    const t = TOKENS.find((x) => x.id === id);
    return [["open", "查看详情", "", "eye"], ...(t?.status === "active" ? [["revoke-token", "撤销令牌", "dz2", "ban"]] : [])];
  }
  if (kind === "oauth") {
    const c = OAUTH.find((x) => x.id === id);
    return [["open", "查看详情", "", "eye"], c?.status === "active" ? ["disable-oauth", "停用", "dz2", "ban"] : ["enable-oauth", "启用", "", "check"], ["del-oauth", "删除…", "dz2", "trash"]];
  }
  return [["open", "打开", "", "eye"]];
}

// ---------------------------------------------------------------- drawer
function openDrawer(kind, id, fromRoute) {
  const map = {
    user: drawerUser, session: drawerSession, token: drawerToken,
    oauth: drawerOauth, coverage: drawerCoverage, sleep: drawerSleep,
  };
  const fn = map[kind]; if (!fn) return;
  const d = fn(id); if (!d) return;
  const el = $("#drawer");
  el.innerHTML = `<header class="bar"><h1>${d.title}</h1><span class="grow"></span><button class="ibtn" data-act="close-drawer" aria-label="关闭">${ic("x")}</button></header><div class="dbody scroll">${d.body}</div>${d.foot ? `<div class="dfoot">${d.foot}</div>` : ""}`;
  el.classList.add("on"); $("#dscrim").classList.add("on");
  if (!fromRoute) {
    const base = Object.entries(DRAWER_ROUTES).find(([, v]) => v === kind)?.[0] || S.route;
    go(`#/${base}/${id}`);
  }
}
function closeDrawer(silent) {
  $("#drawer")?.classList.remove("on");
  $("#dscrim")?.classList.remove("on");
  if (!silent && S.sub && DRAWER_ROUTES[S.route]) go(`#/${S.route}`, true);
}

function flashSecret(prefix) {
  const secret = prefix.replace("…", "") + "mock_" + Math.random().toString(36).slice(2, 10);
  confirmBox({
    title: "令牌已创建",
    body: "请立即复制明文。关闭后无法再次查看。",
    danger: false,
    ok: "已复制，关闭",
    extra: `<div class="secret">${esc(secret)}<button class="ibtn" data-copy="${esc(secret)}" aria-label="复制">${ic("copy")}</button></div>`,
    onOk: () => { try { navigator.clipboard?.writeText(secret); } catch {} toast("令牌已创建（原型）"); },
  });
}

// ---------------------------------------------------------------- actions
function handleAct(act, el) {
  const id = el?.dataset?.id || menuCtx?.id;
  if (act === "theme") {
    const next = document.documentElement.dataset.theme === "dark" ? "light" : "dark";
    setTheme(next); render();
    if ($("#drawer").classList.contains("on") && S.sub) openDrawer(DRAWER_ROUTES[S.route], S.sub, true);
    return;
  }
  if (act === "kbd") return showKbd();
  if (act === "logout") return confirmBox({ title: "退出管理后台？", body: "将结束当前管理会话。", ok: "退出", danger: false, onOk: () => { S.authed = false; go("#/login"); } });
  if (act === "mcancel") return closeModal();
  if (act === "mok") { const fn = modalOk; closeModal(); fn && fn(); return; }
  if (act === "close-drawer") return closeDrawer();
  if (act === "pwtoggle") {
    const inp = el.closest(".pw")?.querySelector("input");
    if (inp) inp.type = inp.type === "password" ? "text" : "password";
    return;
  }
  if (act === "refresh") { toast("已刷新（原型）"); render(); return; }
  if (act === "mmore") {
    const m = $("#menu");
    m.innerHTML = NAV.filter(Array.isArray).map(([k, l, icn]) => `<button data-nav="${k}">${ic(icn)}<span class="grow">${l}</span></button>`).join("")
      + `<hr><button data-act="theme">${ic(document.documentElement.dataset.theme === "dark" ? "sun" : "moon")}<span class="grow">切换主题</span></button>`
      + `<button data-act="logout">${ic("out")}<span class="grow">退出登录</span></button>`;
    m.classList.add("on");
    m.style.left = "12px";
    m.style.right = "12px";
    m.style.width = "auto";
    m.style.bottom = "calc(64px + env(safe-area-inset-bottom))";
    m.style.top = "auto";
    menuCtx = { kind: "mmore" };
    return;
  }
  if (act === "new-user") {
    return confirmBox({ title: "新建用户（原型）", body: "正式环境将打开创建表单抽屉。此处仅演示入口。", ok: "知道了", danger: false, onOk: () => toast("原型：未写入") });
  }
  if (act === "new-token") {
    const t = { id: "tok_new", name: "新令牌", prefix: "hch_new_ab12…", scopes: ["health.read"], status: "active", userId: "usr_01", createdAt: ago(0), lastUsed: null, expires: null };
    TOKENS.unshift(t); render(); flashSecret(t.prefix); return;
  }
  if (act === "new-oauth") {
    return confirmBox({ title: "新建 OAuth 客户端（原型）", body: "正式环境将打开创建抽屉。", ok: "知道了", danger: false, onOk: () => toast("原型：未写入") });
  }
  if (act === "disable-user" || act === "enable-user") {
    const u = userOf(id); if (!u) return;
    if (act === "disable-user") {
      return confirmBox({ title: "停用用户？", body: `将停用 <b>${esc(u.display)}</b>，并失效其会话。`, ok: "停用", onOk: () => { u.status = "disabled"; toast("已停用"); render(); if (S.sub === id) openDrawer("user", id, true); } });
    }
    u.status = "active"; toast("已启用"); render(); if (S.sub === id) openDrawer("user", id, true); return;
  }
  if (act === "del-user") {
    const u = userOf(id); if (!u) return;
    return confirmBox({ title: "删除用户？", body: "此操作不可恢复。", type: u.username, ok: "删除用户", onOk: () => { const i = USERS.findIndex((x) => x.id === id); if (i >= 0) USERS.splice(i, 1); toast("已删除（原型）"); go("#/users"); } });
  }
  if (act === "save-user") { toast("已保存（原型）"); return; }
  if (act === "revoke-session") {
    const s = SESSIONS.find((x) => x.id === id); if (!s || s.current) return toast("不能撤销当前会话", { err: 1 });
    return confirmBox({ title: "撤销会话？", body: `设备 <b>${esc(s.device)}</b> 需重新登录。`, ok: "撤销", onOk: () => { const i = SESSIONS.findIndex((x) => x.id === id); if (i >= 0) SESSIONS.splice(i, 1); toast("会话已撤销"); go("#/sessions"); } });
  }
  if (act === "revoke-token") {
    const t = TOKENS.find((x) => x.id === id); if (!t) return;
    return confirmBox({ title: "撤销令牌？", body: `撤销 <b>${esc(t.name)}</b> 后立即失效，调用方需换新令牌。`, ok: "撤销令牌", onOk: () => { t.status = "revoked"; toast("令牌已撤销"); render(); if (S.sub === id) openDrawer("token", id, true); } });
  }
  if (act === "disable-oauth" || act === "enable-oauth") {
    const c = OAUTH.find((x) => x.id === id); if (!c) return;
    c.status = act === "disable-oauth" ? "disabled" : "active";
    toast(act === "disable-oauth" ? "已停用" : "已启用"); render(); if (S.sub === id) openDrawer("oauth", id, true); return;
  }
  if (act === "del-oauth") {
    const c = OAUTH.find((x) => x.id === id); if (!c) return;
    return confirmBox({ title: "删除 OAuth 客户端？", body: `将删除 <b>${esc(c.name)}</b>（<span class="mono">${esc(c.clientId)}</span>）。依赖此客户端的应用需重新配置。`, ok: "删除客户端", onOk: () => { const i = OAUTH.findIndex((x) => x.id === id); if (i >= 0) OAUTH.splice(i, 1); toast("已删除"); go("#/oauth"); } });
  }
  if (act === "backfill-type") { toast("已排队回填 " + id + "（原型）"); return; }
  if (act === "totp-reset") {
    return confirmBox({ title: "重新绑定 TOTP？", body: "当前验证器将立即失效，未完成新绑定前可能无法登录。此操作影响面大。", type: "重新绑定", ok: "重新绑定", onOk: () => toast("原型：展示二维码步骤未实现") });
  }
}

function handleMi(mi) {
  if (!menuCtx) return;
  const { kind, id } = menuCtx;
  closeMenu();
  if (mi === "open") {
    const route = Object.entries(DRAWER_ROUTES).find(([, v]) => v === kind)?.[0];
    if (route) go(`#/${route}/${id}`);
    return;
  }
  if (mi === "copy") {
    const u = userOf(id);
    if (u) { try { navigator.clipboard?.writeText(u.username); } catch {} toast("已复制用户名"); }
    return;
  }
  handleAct(mi, { dataset: { id } });
}

// ---------------------------------------------------------------- events
document.addEventListener("click", (e) => {
  const t = e.target.closest("[data-act],[data-open],[data-menu],[data-mi],[data-nav],[data-copy],[data-seg] button,[data-v]");
  if (!t) {
    if (!e.target.closest("#menu")) closeMenu();
    return;
  }
  if (t.dataset.copy != null) {
    e.preventDefault();
    try { navigator.clipboard?.writeText(t.dataset.copy); toast("已复制"); } catch { toast("复制失败", { err: 1 }); }
    return;
  }
  if (t.dataset.nav) { e.preventDefault(); closeMenu(); go("#/" + t.dataset.nav); return; }
  if (t.dataset.mi) { e.preventDefault(); handleMi(t.dataset.mi); return; }
  if (t.dataset.menu) { e.preventDefault(); e.stopPropagation(); openMenu(t, t.dataset.menu, t.dataset.id); return; }
  if (t.dataset.open) {
    if (e.target.closest("[data-stop]")) return;
    e.preventDefault();
    const kind = t.dataset.open;
    const route = Object.entries(DRAWER_ROUTES).find(([, v]) => v === kind)?.[0] || S.route;
    go(`#/${route}/${t.dataset.id}`);
    return;
  }
  if (t.closest("[data-seg]")) {
    const seg = t.closest("[data-seg]");
    const v = t.dataset.v; if (!v) return;
    if (seg.dataset.seg === "audit") { S.auditFilter = v; render(); }
    return;
  }
  if (t.dataset.act) { e.preventDefault(); handleAct(t.dataset.act, t); }
});

document.addEventListener("submit", (e) => {
  const f = e.target.closest("form");
  if (!f) return;
  e.preventDefault();
  if (f.dataset.form === "login") {
    if (S.loginLocked) return;
    const pw = f.password?.value || "";
    if (!pw) { S.loginErr = true; render(); return; }
    S.loginErr = false;
    go("#/login/otp");
    return;
  }
  if (f.dataset.form === "otp") {
    const code = $$("[data-otp-i]").map((i) => i.value).join("");
    if (code.length < 6) { S.otpErr = true; render(); bindOtpInputs(); return; }
    S.otpErr = false; S.authed = true;
    go("#/overview");
    toast("已登录");
    return;
  }
  if (f.dataset.form === "password") {
    const nw = f.nw?.value || "", nw2 = f.nw2?.value || "";
    if (!nw || nw !== nw2) { toast("两次密码不一致", { err: 1 }); return; }
    toast("密码已更新（原型）");
    f.reset();
  }
});

document.addEventListener("input", (e) => {
  if (e.target.matches("[data-typeconf]")) {
    const m = $("#modal");
    const ok = $("[data-act=mok]", m);
    if (ok) ok.disabled = e.target.value !== m.dataset.type;
  }
  if (e.target.matches("[data-search]")) {
    S.q[e.target.dataset.search] = e.target.value;
    const st = e.target.selectionStart;
    render();
    const inp = $(`[data-search="${e.target.dataset.search}"]`);
    if (inp) { inp.focus(); try { inp.setSelectionRange(st, st); } catch {} }
  }
});

document.addEventListener("keydown", (e) => {
  if (e.key === "Escape") {
    if ($("#modal").classList.contains("on")) return closeModal();
    if ($("#menu").classList.contains("on")) return closeMenu();
    if ($("#drawer").classList.contains("on")) return closeDrawer();
  }
  if (e.key === "?" && !e.target.matches("input,textarea")) { e.preventDefault(); showKbd(); }
  if (e.key === "t" && !e.target.matches("input,textarea")) { e.preventDefault(); handleAct("theme"); }
  if (e.key === "/" && !e.target.matches("input,textarea")) {
    const s = $("[data-search]"); if (s) { e.preventDefault(); s.focus(); }
  }
  if (e.key === "g" && !e.target.matches("input,textarea")) {
    window.__g = Date.now();
    return;
  }
  if (window.__g && Date.now() - window.__g < 1200 && !e.target.matches("input,textarea")) {
    window.__g = 0;
    const n = NAV.filter(Array.isArray).find((x) => x[3] === e.key);
    if (n) { e.preventDefault(); go("#/" + n[0]); }
  }
});

$("#dscrim").addEventListener("click", () => closeDrawer());
$("#mscrim").addEventListener("click", () => closeModal());

// boot
(function boot() {
  let theme = "dark";
  try { theme = localStorage.getItem(storageKey("theme")) || theme; } catch {}
  if (devp("theme") === "light" || devp("theme") === "dark") theme = devp("theme");
  setTheme(theme);
  if (devp("lock") === "1") S.loginLocked = true;
  if (!location.hash || location.hash === "#") history.replaceState(null, "", "#/overview");
  // Prototype defaults to authed for design review; use #/login to see auth
  if (location.hash.startsWith("#/login")) S.authed = false;
  onRoute();
  addEventListener("hashchange", onRoute);
  if (devp("kbd") === "1") setTimeout(showKbd, 200);
})();
</script>
</body>
</html>
