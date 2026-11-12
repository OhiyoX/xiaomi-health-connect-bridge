"use strict";
/* ============================================================
   HC Health 管理后台 · 交互层（NAS 与 App 共用）
   ------------------------------------------------------------
   - 数据只从 `AdminAPI.state()` 来，写操作只走 `AdminAPI.act(name, payload)`
     （接口见 admin-ui/API.md，两端 adapter 各自实现）。
   - 动作成功后**重新拉 state**，不用返回值就地改页面（页面永远等于后端事实）。
   - 本地持久化只有主题一项：`localStorage["hc-health-admin.theme"]`。
   - 没有登录页：NAS 由 `/login` 完成，App 由原生门禁完成；`#/login*` 一律回概览。
   - 所有插进 DOM 的用户文案都过 `esc()`。
   ============================================================ */
const HcbApp = (function () {
  const THEME_KEY = "hc-health-admin.theme";
  const THEME_COLOR = { light: "#f7f7f8", dark: "#101318" };
  const VIEW = { route: "overview", sub: null, q: {}, auditFilter: "all" };

  let booted = false;
  let loadError = "";
  let modalAction = null;
  let menuCtx = null;
  let toastTimer = 0;
  let themeLocked = false;

  const $ = (s, r) => (r || document).querySelector(s);
  const $$ = (s, r) => Array.prototype.slice.call((r || document).querySelectorAll(s));
  const esc = HcbRender.esc;
  const ic = HcbRender.ic;

  // ---------------------------------------------------------------- 主题
  function setTheme(value) {
    const theme = value === "light" ? "light" : "dark";
    document.documentElement.dataset.theme = theme;
    const meta = $('meta[name="theme-color"]');
    if (meta) meta.content = THEME_COLOR[theme];
    try { localStorage.setItem(THEME_KEY, theme); } catch (e) { /* 私密模式 */ }
  }

  function initTheme() {
    // App 里主题跟随宿主的 DayNight，不允许 localStorage 覆盖。
    try {
      if (window.AdminAPI && typeof window.AdminAPI.theme === "function") {
        const forced = window.AdminAPI.theme();
        if (forced === "dark" || forced === "light") {
          themeLocked = true;
          setTheme(forced);
          return;
        }
      }
    } catch (e) { /* 取不到就回退到本地偏好 */ }
    let theme = "dark";
    try { theme = localStorage.getItem(THEME_KEY) || theme; } catch (e) { /* 私密模式 */ }
    setTheme(theme);
  }

  // ---------------------------------------------------------------- 路由
  function parseHash() {
    const path = String(location.hash || "").replace(/^#\/?/, "").split("?")[0];
    const parts = path.split("/").filter(Boolean);
    let route = parts[0] || "overview";
    let sub = parts[1] || null;
    if (route === "login") {
      // 登录不在 SPA 里：整页登录由宿主（NAS /login、App 门禁）负责
      route = "overview";
      sub = null;
      history.replaceState(null, "", "#/overview");
    } else if (HcbRender.ROUTES.indexOf(route) < 0) {
      // 未知路由：回概览，并把地址栏改回真实路由
      route = "overview";
      sub = null;
      history.replaceState(null, "", "#/overview");
    } else if (sub && !HcbRender.DRAWER_ROUTES[route]) {
      sub = null;
      history.replaceState(null, "", "#/" + route);
    }
    if (sub) {
      try { sub = decodeURIComponent(sub); } catch (e) { sub = null; }
    }
    VIEW.route = route;
    VIEW.sub = sub;
  }

  function go(hash, replace) {
    if (replace) history.replaceState(null, "", hash);
    else history.pushState(null, "", hash);
    onRoute();
  }

  function onRoute() {
    parseHash();
    closeMenu();
    render();
  }

  // ---------------------------------------------------------------- 渲染
  function render() {
    const root = $("#root");
    if (!HcbData.loaded()) {
      root.innerHTML = loadError ? HcbRender.failure(VIEW, loadError) : HcbRender.loading(VIEW);
      closeDrawer(true);
      return;
    }
    root.innerHTML = HcbRender.shell(VIEW);
    syncDrawer();
  }

  function drawerKind() {
    return HcbRender.DRAWER_ROUTES[VIEW.route] || null;
  }

  function syncDrawer() {
    const kind = drawerKind();
    if (!VIEW.sub || !kind) return closeDrawer(true);
    const data = HcbRender.drawer(kind, VIEW.sub);
    if (!data) return closeDrawer(true);
    const el = $("#drawer");
    el.innerHTML = `<header class="bar"><h1>${esc(data.title)}</h1><span class="grow"></span><button class="ibtn" data-act="close-drawer" aria-label="关闭">${ic("x")}</button></header><div class="dbody scroll">${data.body}</div>${data.foot ? `<div class="dfoot">${data.foot}</div>` : ""}`;
    el.classList.add("on");
    $("#dscrim").classList.add("on");
  }

  function closeDrawer(silent) {
    const el = $("#drawer");
    if (el) el.classList.remove("on");
    const scrim = $("#dscrim");
    if (scrim) scrim.classList.remove("on");
    if (!silent && VIEW.sub) go(`#/${VIEW.route}`, true);
  }

  // ---------------------------------------------------------------- 状态
  let healthPoll = null;

  function pollHealth() {
    if (healthPoll) return;
    healthPoll = setTimeout(() => {
      healthPoll = null;
      load();
    }, 700);
  }

  async function load() {
    const first = !HcbData.loaded();
    if (first) { loadError = ""; render(); }
    let ok = false;
    try {
      if (!window.AdminAPI || typeof window.AdminAPI.state !== "function") {
        throw new Error("adapter.js 未加载：缺少 AdminAPI.state()");
      }
      const raw = await window.AdminAPI.state();
      if (raw && typeof raw === "object" && raw.error) throw new Error(String(raw.error));
      HcbData.set(raw);
      loadError = "";
      render();
      if (raw && raw.healthPending === true) pollHealth();
      ok = true;
    } catch (err) {
      const msg = (err && err.message) ? String(err.message) : "无法读取管理状态";
      if (first) { loadError = msg; render(); }
      else { toast("刷新失败：" + msg, { err: 1 }); }
    } finally {
      // 首屏无论成功/失败都要收起原生加载层；失败时页面自己显示错误 + 重试。
      if (window.AdminAPI && typeof window.AdminAPI.ready === "function") {
        try { window.AdminAPI.ready(); } catch (e) { /* 就绪信号可选 */ }
      }
    }
    return ok;
  }

  /**
   * 单个写操作：调 `AdminAPI.act` → 失败 toast。不重拉状态（命令 / 策略连发用）。
   */
  async function call(name, payload) {
    let res;
    try {
      res = await window.AdminAPI.act(name, payload || {});
    } catch (err) {
      toast("请求失败：" + ((err && err.message) || "网络或会话问题"), { err: 1 });
      return null;
    }
    if (!res || res.ok !== true) {
      toast((res && res.error) ? String(res.error) : "操作未完成", { err: 1 });
      return null;
    }
    return res;
  }

  /**
   * 统一的写操作：调 `AdminAPI.act` → 失败 toast → 成功重拉 state。
   * `after(res)` 在重拉之后跑，用来展示一次性明文（secret / TOTP）或探活结果。
   */
  async function mutate(name, payload, opts) {
    const o = opts || {};
    const res = await call(name, payload);
    if (!res) return null;
    if (o.toast) toast(o.toast);
    await load();
    if (o.route) go("#/" + o.route, true);
    if (typeof o.after === "function") {
      try { o.after(res); } catch (e) { /* 展示失败不影响已完成的写操作 */ }
    }
    return res;
  }

  /** 多个写操作按顺序发（每个都带自己的审计），最后一次重拉状态（半途失败也重拉，页面永远等于后端事实）。 */
  async function mutateSeq(calls, message) {
    let failed = false;
    for (const [name, payload] of calls) {
      const res = await call(name, payload);
      if (!res) { failed = true; break; }
    }
    await load();
    if (failed) return null;
    if (message) toast(message);
    return true;
  }

  // ---------------------------------------------------------------- toast / 模态
  function toast(message, opts) {
    const o = opts || {};
    const t = $("#toast");
    t.className = "toast on" + (o.err ? " err" : "");
    t.innerHTML = `${ic(o.err ? "alert" : "checkc")}<span>${esc(message)}</span>`;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.remove("on"), o.ms || 3200);
  }

  function openModal(html) {
    const m = $("#modal");
    m.dataset.type = "";
    m.classList.remove("kbdp");
    m.innerHTML = html;
    m.classList.add("on");
    $("#mscrim").classList.add("on");
    modalAction = null;
  }

  function closeModal() {
    const m = $("#modal");
    m.classList.remove("on");
    m.classList.remove("kbdp");
    m.dataset.type = "";
    $("#mscrim").classList.remove("on");
    modalAction = null;
  }

  /** 确认框（`type` = 需要输入的确认词，高爆操作才用）。 */
  function confirmBox(opts) {
    const o = opts || {};
    const danger = o.danger !== false;
    openModal(`<h3>${esc(o.title)}</h3>`
      + (o.body ? `<p>${o.body}</p>` : "")
      + (o.list ? `<ul>${o.list.map((x) => `<li>${x}</li>`).join("")}</ul>` : "")
      + (o.extra || "")
      + (o.type ? `<div class="tc">输入 <code>${esc(o.type)}</code> 以确认</div><input class="inp" data-typeconf autocomplete="off" enterkeyhint="done">` : "")
      + `<div class="acts"><button class="btn ghost" data-act="mcancel">取消</button><button class="btn ${danger ? "danger" : "primary"}" data-act="mok"${o.type ? " disabled" : ""}>${esc(o.ok || "确认")}</button></div>`);
    $("#modal").dataset.type = o.type || "";
    modalAction = typeof o.onOk === "function" ? o.onOk : null;
    setTimeout(() => {
      const target = o.type ? $("[data-typeconf]", $("#modal")) : $('[data-act="mok"]', $("#modal"));
      if (target) target.focus();
    }, 30);
  }

  function showKbd() {
    const row = (k, label) => `<div class="row"><span>${esc(label)}</span><span>${k.split(" ").map((x) => `<kbd>${esc(x)}</kbd>`).join(" ")}</span></div>`;
    const navRows = HcbRender.NAV.filter(Array.isArray).map((n) => row("g " + n[3], n[1])).join("");
    openModal(`<h3>键盘快捷键</h3><div class="cols"><h4>导航</h4>${navRows}<h4>通用</h4>${row("/", "搜索")}${row("Esc", "关闭抽屉 / 弹窗")}${row("t", "切换主题")}${row("?", "本面板")}</div><div class="acts"><button class="btn" data-act="mcancel">关闭</button></div>`);
    $("#modal").classList.add("kbdp");
  }

  // ---------------------------------------------------------------- 菜单
  function menuItems(kind, id) {
    const single = kind === "user" ? HcbData.data().caps.users === "single" : false;
    if (kind === "user") {
      const u = HcbRender.userOf(id);
      if (!u || single) return [["open", "查看详情", "", "eye"], ["copy", "复制用户名", "", "copy"]];
      return [["open", "查看详情", "", "eye"], ["copy", "复制用户名", "", "copy"], "|",
        u.status === "active" ? ["user-toggle", "停用", "dz2", "ban"] : ["user-toggle", "启用", "", "check"],
        ["del-user", "删除…", "dz2", "trash"]];
    }
    if (kind === "session") return [["open", "查看详情", "", "eye"], ["revoke-session", "撤销会话", "dz2", "ban"]];
    if (kind === "token") {
      const t = HcbData.data().tokens.filter((x) => x.id === id)[0];
      return [["open", "查看详情", "", "eye"]].concat(t && t.status === "active" ? [["revoke-token", "撤销令牌", "dz2", "ban"]] : []);
    }
    if (kind === "oauth") {
      const c = HcbData.data().oauth.filter((x) => x.id === id)[0];
      return [["open", "查看详情", "", "eye"],
        c && c.status === "active" ? ["oauth-toggle", "停用", "dz2", "ban"] : ["oauth-toggle", "启用", "", "check"],
        ["del-oauth", "删除…", "dz2", "trash"]];
    }
    return [["open", "打开", "", "eye"]];
  }

  function openMenu(btn, kind, id) {
    const items = menuItems(kind, id);
    const m = $("#menu");
    m.innerHTML = items.map((it) => (it === "|" ? "<hr>"
      : `<button data-mi="${esc(it[0])}" class="${esc(it[2] || "")}">${ic(it[3] || "chev")}<span class="grow">${esc(it[1])}</span></button>`)).join("");
    m.classList.add("on");
    menuCtx = { kind, id };
    const r = btn.getBoundingClientRect();
    const mw = m.offsetWidth, mh = m.offsetHeight;
    const left = Math.min(r.right - mw, window.innerWidth - mw - 8);
    let top = r.bottom + 4;
    if (top + mh > window.innerHeight - 8) top = Math.max(8, r.top - mh - 4);
    m.style.left = Math.max(8, left) + "px";
    m.style.top = top + "px";
    m.style.bottom = "auto";
    m.style.right = "auto";
    m.style.width = "";
  }

  function closeMenu() {
    const m = $("#menu");
    if (m) m.classList.remove("on");
    menuCtx = null;
  }

  function openMoreMenu() {
    const m = $("#menu");
    m.innerHTML = HcbRender.NAV.filter(Array.isArray)
      .map(([k, label, icon]) => `<button data-nav="${esc(k)}">${ic(icon)}<span class="grow">${esc(label)}</span></button>`).join("")
      + (HcbData.data().caps.followTheme ? "" : `<hr><button data-act="theme">${ic(document.documentElement.dataset.theme === "dark" ? "sun" : "moon")}<span class="grow">切换主题</span></button>`)
      + `<button data-act="logout">${ic("out")}<span class="grow">退出登录</span></button>`
      + `<hr><button data-act="menu-close">${ic("x")}<span class="grow">关闭</span></button>`;
    m.classList.add("on");
    m.style.left = "12px";
    m.style.right = "12px";
    m.style.width = "auto";
    m.style.top = "auto";
    m.style.bottom = "calc(64px + env(safe-area-inset-bottom))";
    menuCtx = { kind: "mmore", id: null };
  }

  function handleMi(mi) {
    const ctx = menuCtx;
    closeMenu();
    if (!ctx) return;
    if (mi === "open") {
      const route = Object.keys(HcbRender.DRAWER_ROUTES)
        .filter((k) => HcbRender.DRAWER_ROUTES[k] === ctx.kind)[0];
      if (route) go(`#/${route}/${encodeURIComponent(ctx.id)}`);
      return;
    }
    if (mi === "copy") {
      const u = HcbRender.userOf(ctx.id);
      if (u) copyText(u.username, "已复制用户名");
      return;
    }
    if (mi === "user-toggle") {
      const u = HcbRender.userOf(ctx.id);
      const enable = !u || u.status !== "active";
      return handleAct(mi, { dataset: { id: ctx.id, enabled: enable ? "1" : "0" } });
    }
    if (mi === "oauth-toggle") {
      const c = HcbData.data().oauth.filter((x) => x.id === ctx.id)[0];
      const enable = !c || c.status !== "active";
      return handleAct(mi, { dataset: { id: ctx.id, enabled: enable ? "1" : "0" } });
    }
    handleAct(mi, { dataset: ctx });
  }

  // ---------------------------------------------------------------- 剪贴板
  async function copyText(text, okMessage) {
    try {
      if (!window.AdminAPI || typeof window.AdminAPI.clipboard !== "function") {
        throw new Error("剪贴板不可用");
      }
      const res = await window.AdminAPI.clipboard(String(text));
      if (res === false || (res && res.ok === false)) {
        throw new Error((res && res.error) || "剪贴板不可用");
      }
      toast(okMessage || "已复制");
    } catch (err) {
      toast("复制失败：" + ((err && err.message) || "剪贴板不可用"), { err: 1 });
    }
  }

  /** 表单内联错误（校验失败时显示；不关表单、不清输入）。 */
  function formError(form, message) {
    const el = form ? form.querySelector("[data-form-err]") : null;
    if (!el) {
      if (message) toast(message, { err: 1 });
      return;
    }
    el.textContent = message || "";
    el.style.display = message ? "" : "none";
  }

  /**
   * 工具策略 / 时间窗字段：留空 = null（不限）；否则必须能解析，且顶层类型对。
   * 原文本发给后端规范化（前端不改写用户的 JSON）。
   */
  function parseJsonField(form, name, want) {
    const raw = val(form, name).trim();
    if (raw === "") return { ok: true, value: null };
    let parsed;
    try { parsed = JSON.parse(raw); } catch (e) { return { ok: false, error: "不是合法 JSON" }; }
    if (parsed === null) return { ok: true, value: null };
    if (want === "array" && !Array.isArray(parsed)) return { ok: false, error: "必须是数组或 null" };
    if (want === "object" && (typeof parsed !== "object" || Array.isArray(parsed))) {
      return { ok: false, error: "必须是对象或 null" };
    }
    return { ok: true, value: raw };
  }

  /** `local.command` → 进剪贴板（命令文本从不进日志，也不渲染到页面）。 */
  async function copyCommand(full) {
    const res = await call("local.command", { full: !!full });
    if (!res) return;
    const cmd = res.data && typeof res.data.command === "string" ? res.data.command : "";
    if (!cmd) return toast("该模式下没有要执行的命令", { err: 1 });
    return copyText(cmd, full ? "已复制完整命令（含 token）" : "已复制命令");
  }

  // ---------------------------------------------------------------- 一次性明文
  function showTokenSecret(res, name) {
    const secret = res && res.secret ? String(res.secret) : "";
    if (!secret) { toast("令牌已创建（服务端未返回明文）"); return; }
    openModal(`<h3>令牌已创建</h3><p>${esc(name || "新令牌")} 的明文<strong>只显示这一次</strong>，关闭后无法再次查看。</p>
  <div class="secret">${esc(secret)}<button class="ibtn" type="button" data-copy="${esc(secret)}" aria-label="复制">${ic("copy")}</button></div>
  <div class="acts"><button class="btn primary" data-act="copy-secret" data-secret="${esc(secret)}">复制并关闭</button><button class="btn ghost" data-act="mcancel">关闭</button></div>`);
  }

  function showClientSecret(res, name) {
    const secret = res && res.secret ? String(res.secret) : "";
    const clientId = res && res.clientId ? String(res.clientId) : "";
    openModal(`<h3>客户端已创建</h3><p>${esc(name || "新客户端")} 已创建${secret ? "，client_secret <strong>只显示这一次</strong>" : ""}。</p>
  ${clientId ? `<div class="kv"><div class="k">client_id</div><div class="mono">${esc(clientId)}</div></div>` : ""}
  ${secret ? `<div class="secret" style="margin-top:8px">${esc(secret)}<button class="ibtn" type="button" data-copy="${esc(secret)}" aria-label="复制">${ic("copy")}</button></div>` : ""}
  <div class="acts">${secret ? `<button class="btn primary" data-act="copy-secret" data-secret="${esc(secret)}">复制并关闭</button>` : ""}<button class="btn ghost" data-act="mcancel">关闭</button></div>`);
  }

  function showTotpSetup(res) {
    const secret = res && res.secret ? String(res.secret) : "";
    const uri = res && res.uri ? String(res.uri) : "";
    if (!secret) { toast("服务端没有返回 TOTP 密钥", { err: 1 }); return; }
    openModal(`<h3>绑定 TOTP</h3><p>把密钥加进身份验证器，再输入当前 6 位验证码完成绑定。</p>
  <div class="secret">${esc(secret)}<button class="ibtn" type="button" data-copy="${esc(secret)}" aria-label="复制">${ic("copy")}</button></div>
  ${uri ? `<div class="help mono" style="margin-top:8px;word-break:break-all">${esc(uri)}</div>` : ""}
  <form id="totp-form" data-form="totp-confirm">
    <div class="fld" style="margin-top:16px"><label>6 位验证码</label><input class="inp" name="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" pattern="[0-9]*" required></div>
  </form>
  <div class="acts"><button class="btn ghost" data-act="mcancel">取消</button><button class="btn primary" type="submit" form="totp-form">确认绑定</button></div>`);
  }

  function showRecoveryCodes(codes) {
    const list = Array.isArray(codes) ? codes.map(String).filter(Boolean) : [];
    if (!list.length) return;
    const joined = list.join("\n");
    openModal(`<h3>TOTP 已启用</h3><p><strong>恢复码只显示这一次。</strong>请离线保存；每个码只能用一次。</p>
  <div class="secret mono" style="white-space:pre-wrap;line-height:1.7">${list.map((c) => esc(c)).join("<br>")}</div>
  <div class="acts"><button class="btn primary" data-act="copy-secret" data-secret="${esc(joined)}">复制并关闭</button><button class="btn ghost" data-act="mcancel">关闭</button></div>`);
  }

  function totpCodeBox(title, body, ok, action) {
    openModal(`<h3>${esc(title)}</h3><p>${esc(body)}</p>
  <form id="totp-code-form" data-form="totp-code" data-action="${esc(action)}">
    <div class="fld"><label>当前 6 位验证码</label><input class="inp" name="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" pattern="[0-9]*" required></div>
    <div class="ferr" data-form-err></div>
  </form>
  <div class="acts"><button class="btn ghost" data-act="mcancel">取消</button><button class="btn ${action === "disable" ? "danger" : "primary"}" type="submit" form="totp-code-form">${esc(ok)}</button></div>`);
  }

  // ---------------------------------------------------------------- 动作
  function handleAct(act, el) {
    const dataset = (el && el.dataset) || {};
    const id = dataset.id || (menuCtx && menuCtx.id) || null;
    if (menuCtx) closeMenu();

    if (act === "theme") {
      if (themeLocked) return;
      setTheme(document.documentElement.dataset.theme === "dark" ? "light" : "dark");
      render();
      return;
    }
    if (act === "metric-days") {
      VIEW.metricDays = Number(dataset.days) || 14;
      render();
      return;
    }
    if (act === "kbd") return showKbd();
    if (act === "reload") {
      if (!HcbData.loaded()) loadError = "";
      return load();
    }
    if (act === "mcancel") return closeModal();
    if (act === "mok") {
      const fn = modalAction;
      closeModal();
      if (fn) fn();
      return;
    }
    if (act === "close-drawer") return closeDrawer();
    if (act === "mmore") {
      return $("#menu").classList.contains("on") ? closeMenu() : openMoreMenu();
    }
    if (act === "menu-close") return closeMenu();
    if (act === "logout") {
      return confirmBox({
        title: "退出管理后台？",
        body: "将结束当前管理会话。",
        ok: "退出",
        danger: false,
        onOk: async () => {
          if (!window.AdminAPI || typeof window.AdminAPI.logout !== "function") {
            return toast("该端不支持页面内退出", { err: 1 });
          }
          try {
            await window.AdminAPI.logout();
          } catch (err) {
            toast("退出失败：" + ((err && err.message) || "请手动关闭页面"), { err: 1 });
          }
        },
      });
    }
    if (act === "copy-secret") {
      const secret = dataset.secret || "";
      closeModal();
      return copyText(secret, "已复制");
    }

    // ---- 用户
    if (act === "new-user") return go("#/users/new");
    if (act === "user-toggle") {
      const u = HcbRender.userOf(id);
      if (!u) return;
      const enable = dataset.enabled === "1";
      if (enable) return mutate("user.toggle", { id, enabled: true }, { toast: "已启用" });
      return confirmBox({
        title: "停用用户？",
        body: `将停用 <b>${esc(u.display)}</b>，并立即失效其会话与令牌写入。`,
        ok: "停用",
        onOk: () => mutate("user.toggle", { id, enabled: false }, { toast: "已停用" }),
      });
    }
    if (act === "del-user") {
      const u = HcbRender.userOf(id);
      if (!u) return;
      return confirmBox({
        title: "删除用户？",
        body: "此操作不可恢复。",
        type: u.username,
        ok: "删除用户",
        onOk: () => mutate("user.delete", { id, username: u.username }, { toast: "用户已删除", route: "users" }),
      });
    }

    // ---- 会话
    if (act === "revoke-session") {
      const s = HcbData.data().sessions.filter((x) => x.id === id)[0];
      if (!s) return;
      if (s.current) return toast("不能撤销当前会话", { err: 1 });
      return confirmBox({
        title: "撤销会话？",
        body: `设备 <b>${esc(s.device)}</b> 需重新登录。`,
        ok: "撤销",
        onOk: () => mutate("session.revoke", { id }, { toast: "会话已撤销", route: "sessions" }),
      });
    }
    if (act === "revoke-others") {
      const others = HcbData.data().sessions.filter((s) => !s.current).length;
      return confirmBox({
        title: "撤销其他会话？",
        body: `将撤销 <b>${esc(String(others))}</b> 个非当前会话，相关设备需重新登录。`,
        ok: "全部撤销",
        onOk: () => mutate("session.revoke-others", {}, {
          after: (res) => {
            const n = res && res.data && res.data.revoked;
            toast(n ? "已撤销 " + n + " 个会话" : "其他会话已撤销");
          },
        }),
      });
    }

    // ---- 令牌
    if (act === "new-token") return go("#/tokens/new");
    if (act === "revoke-token") {
      const t = HcbData.data().tokens.filter((x) => x.id === id)[0];
      if (!t) return;
      const payload = { id };
      if (t.family) payload.family = t.family;
      return confirmBox({
        title: "撤销令牌？",
        body: `撤销 <b>${esc(t.name)}</b> 后立即失效，调用方需换新令牌。`,
        ok: "撤销令牌",
        onOk: () => mutate("token.revoke", payload, { toast: "令牌已撤销" }),
      });
    }

    // ---- OAuth
    if (act === "new-oauth") return go("#/oauth/new");
    if (act === "oauth-toggle") {
      const c = HcbData.data().oauth.filter((x) => x.id === id)[0];
      if (!c) return;
      const enable = dataset.enabled === "1";
      if (c.status === "pending" && enable) {
        return mutate("oauth.toggle", { id, enabled: true }, { toast: "已批准客户端" });
      }
      if (enable) return mutate("oauth.toggle", { id, enabled: true }, { toast: "已启用" });
      return confirmBox({
        title: "停用客户端？",
        body: `停用 <b>${esc(c.name)}</b> 后，已发出的令牌不再可用。`,
        ok: "停用",
        onOk: () => mutate("oauth.toggle", { id, enabled: false }, { toast: "已停用" }),
      });
    }
    if (act === "del-oauth") {
      const c = HcbData.data().oauth.filter((x) => x.id === id)[0];
      if (!c) return;
      return confirmBox({
        title: "删除 OAuth 客户端？",
        body: `将删除 <b>${esc(c.name)}</b>（<span class="mono">${esc(c.clientId)}</span>）。依赖此客户端的应用需重新配置。`,
        ok: "删除客户端",
        onOk: () => mutate("oauth.delete", { id }, { toast: "客户端已删除", route: "oauth" }),
      });
    }
    if (act === "pending-allow" || act === "pending-deny") {
      const p = HcbData.data().pending.filter((x) => x.id === id)[0];
      if (!p) return;
      const approve = act === "pending-allow";
      return confirmBox({
        title: approve ? "允许这个授权请求？" : "拒绝这个授权请求？",
        body: `${esc(p.client)}（<span class="mono">${esc(p.clientId)}</span>）请求：${p.scopes.map((s) => esc(s)).join("、") || "—"}`,
        ok: approve ? "允许" : "拒绝",
        danger: !approve,
        onOk: () => mutate("pending.decide", { id, approve }, { toast: approve ? "已允许" : "已拒绝" }),
      });
    }

    // ---- 健康数据
    if (act === "backfill-type") {
      return confirmBox({
        title: "触发回填？",
        body: `将为 <span class="mono">${esc(id)}</span> 排队一次回填任务。`,
        ok: "排队",
        onOk: () => mutate("coverage.backfill", { type: id }, { toast: "已排队回填" }),
      });
    }

    // ---- 审计
    if (act === "audit-verify") {
      return mutate("audit.verify", {}, {
        after: (res) => {
          const okChain = !!(res && res.data && res.data.chainOk === true);
          toast(okChain ? "审计链校验通过" : "审计链不一致，请排查", { err: !okChain });
        },
      });
    }

    // ---- 本机访问（App，`caps.localAccess`）
    if (act === "local-mode") {
      // 只改草稿（隐藏字段 + 勾选样式），不落库；也不重渲染，避免撤掉已输入的基址 / token
      const mode = dataset.mode || "";
      if (HcbData.TUNNEL_MODES.indexOf(mode) < 0) return;
      const form = el && el.closest ? el.closest("form") : null;
      if (!form) return;
      const hidden = form.querySelector("[data-local-mode]");
      if (hidden) hidden.value = mode;
      $$('[data-act="local-mode"]', form).forEach((b) => {
        const on = b.dataset.mode === mode;
        b.classList.toggle("on", on);
        b.setAttribute("aria-pressed", on ? "true" : "false");
      });
      return;
    }
    if (act === "local-require-auth" || act === "local-loopback" || act === "local-confirm-clients") {
      const enabled = dataset.enabled === "1";
      const name = act === "local-require-auth" ? "local.require-auth"
        : act === "local-loopback" ? "local.loopback" : "local.confirm-clients";
      if (act === "local-require-auth" && !enabled) {
        return confirmBox({
          title: "关闭 Bearer 要求？",
          body: "关掉后本机客户端不带令牌就能调用。公网 / 隧道开着时，后端会拒绝这个操作。",
          ok: "关闭鉴权",
          onOk: () => mutate(name, { enabled: false }, { toast: "已关闭 Bearer 要求" }),
        });
      }
      const label = act === "local-require-auth" ? "Bearer 要求"
        : act === "local-loopback" ? "回环免鉴权" : "新客户端批准";
      return mutate(name, { enabled }, { toast: (enabled ? "已开启 " : "已关闭 ") + label });
    }
    if (act === "local-copy-cmd" || act === "local-copy-full") {
      if (act === "local-copy-cmd") return copyCommand(false);
      return confirmBox({
        title: "复制完整命令？",
        body: "完整命令含 tunnel token 明文，只粘到你自己的终端；不要发到聊天或截图里。",
        ok: "复制",
        danger: false,
        onOk: () => copyCommand(true),
      });
    }
    if (act === "local-clear-token") {
      return confirmBox({
        title: "清除 tunnel token？",
        body: "清除后「完整命令」只能给出占位符，named tunnel 也起不来，直到重新粘一个 token。",
        ok: "清除",
        onOk: () => mutate("local.clear-token", {}, { toast: "已清除 tunnel token" }),
      });
    }
    if (act === "local-probe") {
      return mutate("local.probe", {}, {
        after: (res) => {
          const p = (res && res.data) || {};
          const detail = p.detail ? "：" + String(p.detail) : "";
          toast(p.ok === true ? "公网可达" + detail : "公网不可达" + detail, { err: p.ok !== true });
        },
      });
    }
    if (act === "policy-edit" || act === "policy-cancel") {
      const formId = dataset.formId || "";
      const form = formId ? document.getElementById(formId) : null;
      if (!form) return toast("编辑表单不在了，请重新打开抽屉", { err: 1 });
      const editing = act === "policy-edit";
      form.hidden = !editing;
      if (editing) {
        formError(form, "");
        const area = form.querySelector("textarea");
        if (area) area.focus();
      }
      return;
    }

    // ---- 安全
    if (act === "totp-begin") {
      return confirmBox({
        title: "绑定 TOTP？",
        body: "会生成新的密钥；完成验证码确认后生效。",
        ok: "开始绑定",
        danger: false,
        onOk: () => mutate("security.totp-begin", {}, { after: (res) => showTotpSetup(res.data) }),
      });
    }
    if (act === "totp-rebind") {
      return totpCodeBox(
        "重新绑定 TOTP",
        "先输入当前验证码。确认后会生成新密钥，当前验证器立即失效；未完成新绑定前可能无法登录。",
        "继续重新绑定",
        "rebind",
      );
    }
    if (act === "totp-disable") {
      return totpCodeBox(
        "停用 TOTP",
        "先输入当前验证码。停用后只靠密码保护管理后台，风险更高。",
        "停用 TOTP",
        "disable",
      );
    }
  }

  // ---------------------------------------------------------------- 表单
  const val = (form, name) => {
    const el = form.elements[name];
    if (!el) return "";
    if (typeof el.value === "string") return el.value;
    return "";
  };
  const checked = (form, name) => {
    const el = form.elements[name];
    return !!(el && el.checked);
  };
  const checkedList = (form, name) =>
    $$('input[name="' + name + '"]', form).filter((x) => x.checked).map((x) => String(x.value));

  function submitForm(form) {
    const kind = form.dataset.form;
    if (kind === "user-create") {
      const username = val(form, "username").trim();
      const display = val(form, "display").trim();
      const password = val(form, "password");
      const scopes = checkedList(form, "scopes");
      if (!username) return toast("请填用户名", { err: 1 });
      if (!scopes.length) return toast("至少选一个权限范围", { err: 1 });
      if (password.length < 10) return toast("密码至少 10 位", { err: 1 });
      if (password !== val(form, "password2")) return toast("两次密码不一致", { err: 1 });
      return mutate("user.create", { username, display, password, scopes }, { toast: "用户已创建", route: "users" });
    }
    if (kind === "user-edit") {
      const id = form.dataset.id;
      const payload = { id };
      const display = val(form, "display").trim();
      const password = val(form, "password");
      const scopes = checkedList(form, "scopes");
      if (!scopes.length) return toast("至少选一个权限范围", { err: 1 });
      payload.scopes = scopes;
      if (display) payload.display = display;
      if (password) {
        if (password.length < 10) return toast("密码至少 10 位", { err: 1 });
        payload.password = password;
      }
      return mutate("user.update", payload, { toast: "已保存" });
    }
    if (kind === "token-create") {
      const name = val(form, "name").trim();
      const scopes = checkedList(form, "scopes");
      if (!name) return toast("请填令牌名称", { err: 1 });
      if (!scopes.length) return toast("至少选一个权限范围", { err: 1 });
      return mutate("token.create", { name, scopes }, { toast: "令牌已创建", route: "tokens", after: (res) => showTokenSecret(res.data, name) });
    }
    if (kind === "oauth-create") {
      const name = val(form, "name").trim();
      const clientKind = val(form, "kind") || "public";
      const redirects = val(form, "redirects").split("\n").map((s) => s.trim()).filter(Boolean);
      if (!name) return toast("请填客户端名称", { err: 1 });
      if (!redirects.length) return toast("至少填一个回调地址", { err: 1 });
      return mutate("oauth.create", { name, kind: clientKind, redirects }, { toast: "客户端已创建", route: "oauth", after: (res) => showClientSecret(res.data, name) });
    }
    if (kind === "password") {
      const current = val(form, "current");
      const password = val(form, "password");
      if (password.length < 10) return toast("密码至少 10 位", { err: 1 });
      if (password !== val(form, "password2")) return toast("两次密码不一致", { err: 1 });
      return mutate("security.change-password",
        { current, password, password2: val(form, "password2") }, { toast: "密码已更新" });
    }
    if (kind === "settings") {
      const payload = {};
      const ttl = val(form, "sessionTtlHours").trim();
      const fails = val(form, "maxFailedLogins").trim();
      const lock = val(form, "lockoutMinutes").trim();
      if (ttl) payload.sessionTtlHours = Number(ttl);
      if (fails) payload.maxFailedLogins = Number(fails);
      if (lock) payload.lockoutMinutes = Number(lock);
      payload.requireTotp = checked(form, "requireTotp");
      payload.allowPasswordLogin = checked(form, "allowPasswordLogin");
      return mutate("settings.update", payload, { toast: "策略已保存" });
    }
    if (kind === "token-policy" || kind === "oauth-policy") {
      const isToken = kind === "token-policy";
      const id = form.dataset.id;
      const item = (isToken ? HcbData.data().tokens : HcbData.data().oauth)
        .filter((x) => x.id === id)[0];
      if (!item) return toast("对象已不存在，请重新打开抽屉", { err: 1 });
      const policy = parseJsonField(form, "policy", "array");
      const win = parseJsonField(form, "window", "object");
      if (!policy.ok) return formError(form, "工具策略：" + policy.error + "（未保存）");
      if (!win.ok) return formError(form, "时间窗：" + win.error + "（未保存）");
      const calls = [];
      if (policy.value !== item.policy) {
        calls.push([isToken ? "token.policy" : "oauth.policy", { id, policy: policy.value }]);
      }
      if (win.value !== item.window) {
        calls.push([isToken ? "token.window" : "oauth.window", { id, window: win.value }]);
      }
      if (!calls.length) return toast("没有改动");
      formError(form, "");
      return mutateSeq(calls, "策略已保存");
    }
    if (kind === "local-save") {
      const mode = val(form, "mode") || "off";
      if (HcbData.TUNNEL_MODES.indexOf(mode) < 0) return toast("隧道模式不合法", { err: 1 });
      const publicBase = val(form, "publicBase").trim();
      const token = val(form, "token");
      // token 留空 = 保留已保存的那个（界面从不回显 token）
      return mutate("local.save-public", { mode, publicBase: publicBase || null, token },
        { toast: "公网设置已保存" });
    }
    if (kind === "totp-confirm") {
      const code = val(form, "code").replace(/\D/g, "");
      if (code.length !== 6) return toast("请输入 6 位验证码", { err: 1 });
      closeModal();
      return mutate("security.totp-confirm", { code }, {
        after: (res) => {
          const codes = res && res.data && res.data.recoveryCodes;
          if (Array.isArray(codes) && codes.length) showRecoveryCodes(codes);
          else toast("TOTP 已启用");
        },
      });
    }
    if (kind === "totp-code") {
      const code = val(form, "code").replace(/\D/g, "");
      if (code.length !== 6) return formError(form, "请输入 6 位验证码");
      const action = form.dataset.action;
      closeModal();
      if (action === "rebind") {
        return mutate("security.totp-rebind", { code }, {
          after: (res) => showTotpSetup(res.data),
        });
      }
      return mutate("security.totp-disable", { code }, { toast: "TOTP 已停用" });
    }
    return undefined;
  }

  // ---------------------------------------------------------------- 事件
  function bindEvents() {
    document.addEventListener("click", (e) => {
      if (!e.target.closest("#menu") && !e.target.closest('[data-act="mmore"]')) closeMenu();
      const target = e.target.closest("[data-act],[data-open],[data-menu],[data-mi],[data-nav],[data-copy],[data-seg] button");
      if (!target) return;
      if (target.dataset.copy !== undefined) {
        e.preventDefault();
        copyText(target.dataset.copy, "已复制");
        return;
      }
      if (target.dataset.nav) {
        e.preventDefault();
        closeMenu();
        go("#/" + target.dataset.nav);
        return;
      }
      if (target.dataset.mi) {
        e.preventDefault();
        handleMi(target.dataset.mi);
        return;
      }
      if (target.dataset.menu) {
        e.preventDefault();
        e.stopPropagation();
        openMenu(target, target.dataset.menu, target.dataset.id);
        return;
      }
      if (target.dataset.open) {
        if (e.target.closest("[data-stop]")) return;
        e.preventDefault();
        const kind = target.dataset.open;
        const route = Object.keys(HcbRender.DRAWER_ROUTES)
          .filter((k) => HcbRender.DRAWER_ROUTES[k] === kind)[0] || VIEW.route;
        go(`#/${route}/${encodeURIComponent(target.dataset.id)}`);
        return;
      }
      const seg = target.closest("[data-seg]");
      if (seg) {
        const v = target.dataset.v;
        if (!v) return;
        if (seg.dataset.seg === "audit") {
          VIEW.auditFilter = v;
          render();
        }
        return;
      }
      if (target.dataset.act) {
        e.preventDefault();
        handleAct(target.dataset.act, target);
      }
    });

    document.addEventListener("submit", (e) => {
      const form = e.target.closest("form");
      if (!form || !form.dataset.form) return;
      e.preventDefault();
      submitForm(form);
    });

    document.addEventListener("input", (e) => {
      const t = e.target;
      if (t.matches("[data-typeconf]")) {
        const m = $("#modal");
        const okBtn = $('[data-act="mok"]', m);
        if (okBtn) okBtn.disabled = t.value !== m.dataset.type;
        return;
      }
      if (t.matches("[data-search]")) {
        const key = t.dataset.search;
        const pos = t.selectionStart;
        VIEW.q[key] = t.value;
        render();
        const again = $(`[data-search="${key}"]`);
        if (again) {
          again.focus();
          try { again.setSelectionRange(pos, pos); } catch (err) { /* 部分键盘 */ }
        }
      }
    });

    document.addEventListener("keydown", (e) => {
      const typing = e.target.matches("input,textarea,select");
      if (e.key === "Escape") {
        if ($("#modal").classList.contains("on")) return closeModal();
        if ($("#menu").classList.contains("on")) return closeMenu();
        if ($("#drawer").classList.contains("on")) return closeDrawer();
        return undefined;
      }
      if (typing) return undefined;
      if (e.key === "?") { e.preventDefault(); return showKbd(); }
      if (e.key === "t") { e.preventDefault(); return handleAct("theme"); }
      if (e.key === "/") {
        const box = $("[data-search]");
        if (box) { e.preventDefault(); box.focus(); }
        return undefined;
      }
      if (e.key === "g") {
        window.__hcbG = Date.now();
        return undefined;
      }
      if (window.__hcbG && Date.now() - window.__hcbG < 1200) {
        window.__hcbG = 0;
        const item = HcbRender.NAV.filter(Array.isArray).filter((n) => n[3] === e.key)[0];
        if (item) { e.preventDefault(); go("#/" + item[0]); }
      }
      return undefined;
    });

    const dscrim = $("#dscrim");
    if (dscrim) dscrim.addEventListener("click", () => closeDrawer());
    const mscrim = $("#mscrim");
    if (mscrim) mscrim.addEventListener("click", () => closeModal());
  }

  // ---------------------------------------------------------------- 启动
  function boot() {
    if (booted) return;
    booted = true;
    initTheme();
    if (!location.hash || location.hash === "#") history.replaceState(null, "", "#/overview");
    bindEvents();
    window.addEventListener("hashchange", onRoute);
    onRoute();
    load();
  }

  /**
   * 原生返回键入口：只处理“盖在页面上的东西”。
   * 关闭抽屉/弹窗/菜单后返回 true（这次返回被消费）；否则 false，由宿主关闭管理页。
   * 不调用 history.back()：WebView 的历史里可能是上一次整页加载，退回去会重新转圈。
   */
  window.HcbBack = function () {
    if ($("#modal").classList.contains("on")) { closeModal(); return true; }
    if ($("#menu").classList.contains("on")) { closeMenu(); return true; }
    if ($("#drawer").classList.contains("on")) { closeDrawer(); return true; }
    return false;
  };

  // 脚本顺序是 data → render → app → adapter，且全部 defer：
  // app.js 执行时 adapter.js 还没跑，所以启动必须等到 DOMContentLoaded 之后。
  if (document.readyState === "complete") boot();
  else document.addEventListener("DOMContentLoaded", boot);

  return { boot, load, go, view: VIEW, mutate };
})();
