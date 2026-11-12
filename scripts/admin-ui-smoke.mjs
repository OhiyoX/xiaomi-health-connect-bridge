#!/usr/bin/env node
/**
 * 无浏览器渲染冒烟：把 admin-ui/{data,render}.js 放进一个最小 DOM stub 里跑一遍，
 * 确认每个路由 / 抽屉都能渲染出 HTML，且没有未定义值、没有未转义的用户文本。
 *
 * 覆盖两种真实状态形状（见 admin-ui/API.md）：
 *   - NAS：ISO 时间戳、audit{rows,chainOk}、coverage.rows
 *   - App：epoch 毫秒、audit 数组 + auditChainOk、coverage.records30d、caps 单条
 *
 * 用法：node scripts/admin-ui-smoke.mjs
 */
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const here = dirname(fileURLToPath(import.meta.url));
const spa = join(here, "..", "admin-ui");

const noop = () => {};
const stub = () => ({
  documentElement: { dataset: { theme: "dark" } },
  querySelector: () => null,
  querySelectorAll: () => [],
  addEventListener: noop,
  readyState: "complete",
});
const sandbox = {
  window: {},
  document: stub(),
  localStorage: { getItem: () => null, setItem: noop },
  location: { hash: "", search: "", href: "" },
  history: { replaceState: noop, pushState: noop },
  addEventListener: noop,
  setTimeout,
  clearTimeout,
  console,
};
const ctx = vm.createContext(sandbox);
for (const name of ["data.js", "render.js"]) {
  const src = readFileSync(join(spa, name), "utf8");
  vm.runInContext(src, ctx, { filename: name });
}
vm.runInContext("this.__HcbData = HcbData; this.__HcbRender = HcbRender;", ctx);
const HcbData = ctx.__HcbData;
const HcbRender = ctx.__HcbRender;

const ago = 3 * 3600e3;
const nowISO = "2026-11-03T08:16:00+08:00";
const nowMs = Date.parse(nowISO);
const iso = (ms) => new Date(nowMs - ms).toISOString();

const NAS_STATE = {
  me: { id: "1", username: "hiyo", display: "hiyo", role: "admin" },
  version: "0.9.0",
  now: nowISO,
  overview: {
    sync: {
      lastSyncAt: iso(7 * 60e3), lastSyncSource: "xiaomi-cloud", lastSyncRows: 18420,
      lagMin: 7, pipeline: "ok",
    },
    backfill: {
      status: "idle", progress: 1, rangeFrom: "2024-01-01", rangeTo: "2026-11-02",
      lastRun: iso(2 * 24 * 3600e3),
    },
  },
  users: [
    { id: "1", username: "hiyo", display: "hiyo", role: "admin", status: "active", totp: true, lastLogin: iso(20 * 60e3), createdAt: iso(400 * 24 * 3600e3), scopes: ["admin", "health.read"] },
    { id: "2", username: "ops", display: "<img src=x onerror=alert(1)>", role: "viewer", status: "disabled", totp: false, lastLogin: null, createdAt: null, scopes: ["health.read"] },
  ],
  sessions: [
    { id: "ses_a1", userId: "1", device: "Chrome · macOS", ip: "100.64.1.12", loc: "内网 · Tailscale", createdAt: iso(2 * 3600e3), lastSeen: iso(3 * 60e3), current: true },
    { id: "ses_b1", userId: "2", device: "Firefox · Linux", ip: "10.0.0.8", loc: "内网", createdAt: iso(3 * 24 * 3600e3), lastSeen: iso(3 * 24 * 3600e3), current: false },
  ],
  tokens: [
    { id: "tok_01", name: "MCP Claude Desktop", prefix: "hch_live_8f2a…", scopes: ["health.read", "health.write"], status: "active", userId: "1", createdAt: iso(40 * 24 * 3600e3), lastUsed: ago, expires: null },
    { id: "tok_02", name: "旧版调试", prefix: "hch_dbg_00ee…", scopes: ["health.read"], status: "revoked", userId: "1", createdAt: iso(120 * 24 * 3600e3), lastUsed: null, expires: iso(-90 * 24 * 3600e3) },
  ],
  oauth: [
    { id: "hc-bridge", name: "HC Bridge", clientId: "hc-bridge", kind: "confidential", redirects: ["https://health.example.com/oauth/callback"], status: "active", createdAt: iso(300 * 24 * 3600e3), lastAuth: ago },
    { id: "pending-cli", name: "待批准客户端", clientId: "pending-cli", kind: "public", redirects: [], status: "pending", createdAt: null, lastAuth: null },
  ],
  pending: [
    { id: "req_1", client: "Obsidian MCP", clientId: "obsidian-mcp", redirect: "127.0.0.1", scopes: ["health.read"], createdAt: iso(60e3), expiresAt: iso(-600e3) },
  ],
  coverage: [
    { type: "heart_rate", label: "心率", rows: 1248356, sources: ["apple_health", "huawei_health"], days30: 30, pct: 0.98, lastAt: iso(12 * 60e3) },
    { type: "weight", label: "体重", rows: 612, sources: [], days30: 22, pct: 0.73, lastAt: null },
  ],
  sleep: [
    { id: "2026-11-02", date: "2026-11-02", bed: "23:42", wake: "07:08", hours: 7.43, score: null, source: "apple_health", flags: [], note: "正常" },
    { id: "2026-10-29", date: "2026-10-29", bed: "—", wake: "—", hours: null, score: null, source: "—", flags: ["missing"], note: "当日无睡眠样本" },
  ],
  metrics: {
    days: 30,
    steps: { label: "步数", unit: "步", latest: 9842, avg: 8750.4, series: [8000, null, 9000, 7200] },
    heart_rate: { label: "心率（日均）", unit: "bpm", latest: 68, avg: 71, series: [70, 72, 69] },
    sleep: { label: "睡眠时长", unit: "h", decimals: 1, latest: 7.4, avg: 7.06, series: [7.1, 6.9, 7.4] },
  },
  audit: {
    rows: [
      { id: "1", at: iso(8 * 60e3), actor: "hiyo", action: "token.create", target: "tok_01", outcome: "ok", detail: "创建静态令牌" },
      { id: "2", at: iso(24 * 3600e3), actor: "unknown", action: "login.fail", target: "", outcome: "fail", detail: "密码错误" },
    ],
    chainOk: true,
  },
  security: {
    totpEnabled: true, totpEnrolledAt: iso(380 * 24 * 3600e3), passwordChangedAt: iso(45 * 24 * 3600e3),
    passwordSet: true, sessionTtlHours: 168, allowPasswordLogin: true, requireTotp: true,
    maxFailedLogins: 5, lockoutMinutes: 15, settingsEditable: true,
  },
  csrf: "csrf-token",
};

const APP_STATE = {
  version: "0.9.0",
  now: nowMs,
  caps: { users: "single", sessions: "single", settings: false, localAccess: true, policies: true },
  sync: {
    lastSyncAt: nowMs - 3 * 60e3, lastSyncResult: "ok", lastSyncRows: 120, lagMin: 3,
    pipeline: "ok", backfill: { status: "idle", progress: 1, rangeFrom: null, rangeTo: null, lastRun: null },
  },
  users: [{
    id: "admin", username: "admin", display: "管理员", role: "admin", status: "active",
    totp: true, lastLogin: nowMs - 600e3, createdAt: null, sessions: 1,
    note: "本机管理员 · 单账号",
  }],
  sessions: [{
    id: "unlock", userId: "admin", device: "Pixel 7", ip: "127.0.0.1", loc: "本机",
    createdAt: nowMs - 600e3, lastSeen: nowMs, current: true, expiresAt: nowMs + 3600e3,
  }],
  tokens: [
    { id: "key:1", kind: "static", name: "桌面 key", prefix: "ab12cd34…", scopes: ["不限"], status: "active", userId: "admin", createdAt: nowMs - 86400e3, lastUsed: null, expires: null, family: null, policy: '["hc_status"]', window: '{"days":[1,2,3,4,5],"start":"08:00","end":"23:00"}' },
    { id: "tok:fam1:access:abc", kind: "access", name: "Obsidian · 访问", prefix: "hcb_a1b2…", scopes: ["health.read"], status: "expired", userId: "admin", createdAt: nowMs - 900e3, lastUsed: nowMs - 800e3, expires: nowMs - 60e3, family: "fam1", policy: null, window: null },
  ],
  oauth: [{ id: "obsidian", name: "Obsidian MCP", clientId: "obsidian", kind: "public", redirects: ["http://127.0.0.1:22360/callback"], status: "active", createdAt: nowMs - 86400e3, lastAuth: nowMs - 3600e3, policy: null, window: '{"start":"22:00","end":"06:00"}' }],
  localAccess: {
    requireAuth: true, allowLoopbackNoAuth: false, confirmNewClients: true,
    tunnelMode: "named", publicBase: "https://hcb-demo.trycloudflare.com", hasToken: true,
    lastProbe: { ok: false, at: nowMs - 120e3, detail: `HTTP 502 · <b>bad</b> gateway` },
  },
  pending: [],
  coverage: [
    { type: "steps", label: "步数", records30d: 12000, days30: 28, sources: ["health_connect"], firstAt: null, lastAt: nowMs - 3600e3, pct: 0.93 },
    { type: "sleep", label: "睡眠", records30d: null, days30: null, sources: [], firstAt: null, lastAt: null, pct: null },
  ],
  sleep: [
    { id: "2026-11-02", date: "2026-11-02", bed: "23:42", wake: "07:08", hours: 7.43, score: null, source: "Health Connect", flags: ["short"], note: "无分期数据" },
    { id: "2026-11-01", date: "2026-11-01", bed: null, wake: null, hours: null, score: null, source: "—", flags: ["missing"], note: "当日无睡眠样本" },
  ],
  metrics: {
    days: 14,
    steps: { label: "步数", unit: "步", decimals: 0, latest: 9842, avg: 8750, series: [8000, null, 9000] },
    heart_rate: { label: "心率（日均）", unit: "bpm", decimals: 0, latest: 68, avg: 71, series: [] },
    sleep: { label: "睡眠时长", unit: "h", decimals: 1, latest: 7.4, avg: 7.1, series: [7.1, 7.4] },
  },
  audit: [
    { id: "11", at: nowMs - 60e3, actor: "admin", action: "key_create", target: "桌面 key", outcome: "ok", detail: "" },
    { id: "12", at: nowMs - 120e3, actor: "admin", action: "totp_fail", target: "", outcome: "fail", detail: "码不对" },
  ],
  auditChainOk: false,
  security: {
    totpEnabled: true, totpEnrolledAt: null, passwordChangedAt: null, passwordSet: true,
    sessionTtlHours: 0.083, sessionTtlMinutes: 5, allowPasswordLogin: true, requireTotp: false,
    maxFailedLogins: 5, lockoutMinutes: 1,
  },
};

const UI_ACTS = new Set([
  "theme", "kbd", "reload", "mcancel", "mok", "close-drawer", "logout", "copy-secret",
  "metric-days",
  "mmore", "new-user", "user-toggle", "del-user", "revoke-session", "revoke-others",
  "new-token", "revoke-token", "new-oauth", "oauth-toggle", "del-oauth",
  "pending-allow", "pending-deny", "backfill-type", "audit-verify",
  "totp-begin", "totp-rebind", "totp-disable",
  "local-mode", "local-require-auth", "local-loopback", "local-confirm-clients",
  "local-copy-cmd", "local-copy-full", "local-clear-token", "local-probe",
  "policy-edit", "policy-cancel",
]);
const FORMS = new Set(["user-create", "user-edit", "token-create", "oauth-create", "password", "settings", "totp-confirm",
  "token-policy", "oauth-policy", "local-save"]);

const failures = [];
const check = (label, html) => {
  if (typeof html !== "string" || html.length < 40) failures.push(`${label}: 输出为空`);
  if (/undefined|NaN|\[object Object\]/.test(html)) failures.push(`${label}: 含 undefined/NaN/[object Object]`);
  if (/<script/i.test(html)) failures.push(`${label}: 含 <script`);
  for (const m of html.matchAll(/<[a-zA-Z][^<>]*>/g)) {
    // 只看真正的标签内部，并先抹掉引号里的属性值：
    // 用户文本（&lt;img onerror=…&gt;）即使出现在 value="…" 里也只是字符串，不会被浏览器当属性
    const bare = m[0].replace(/"[^"]*"/g, '""').replace(/'[^']*'/g, "''");
    if (/\son[a-z]+\s*=/i.test(bare)) failures.push(`${label}: 含内联事件属性`);
  }
  if (/<img/i.test(html)) failures.push(`${label}: 用户文本未转义（<img> 泄漏）`);
  for (const m of html.matchAll(/data-act="([^"]*)"/g)) {
    if (!UI_ACTS.has(m[1])) failures.push(`${label}: 未知 UI 动作 ${m[1]}`);
  }
  for (const m of html.matchAll(/data-form="([^"]*)"/g)) {
    if (!FORMS.has(m[1])) failures.push(`${label}: 未知表单 ${m[1]}`);
  }
  for (const m of html.matchAll(/data-open="([^"]*)"/g)) {
    if (!Object.values(HcbRender.DRAWER_ROUTES).includes(m[1])) failures.push(`${label}: 未知抽屉 ${m[1]}`);
  }
};

let cases = 0;
for (const [name, fixture] of [["nas", NAS_STATE], ["app", APP_STATE]]) {
  const data = HcbData.set(fixture);
  for (const route of HcbRender.ROUTES) {
    const view = { route, sub: null, q: {}, auditFilter: "all" };
    check(`${name}/${route}`, HcbRender.shell(view));
    cases++;
    if (route === "audit") {
      check(`${name}/audit-fail`, HcbRender.shell(Object.assign({}, view, { auditFilter: "fail" })));
      cases++;
    }
    if (route === "users" || route === "tokens") {
      check(`${name}/${route}-search`, HcbRender.shell(Object.assign({}, view, { q: { [route]: "zzz" } })));
      cases++;
    }
  }
  for (const kind of ["user", "token", "oauth"]) {
    const d = HcbRender.drawer(kind, "new");
    if (!d) continue; // 该端不支持新建（App 的单管理员 / 无多用户）
    check(`${name}/drawer-${kind}-new`, d.body + (d.foot || ""));
    cases++;
  }
  const ids = {
    user: data.users.map((u) => u.id),
    session: data.sessions.map((s) => s.id),
    token: data.tokens.map((t) => t.id),
    oauth: data.oauth.map((c) => c.id),
    coverage: data.coverage.map((c) => c.type),
    sleep: data.sleep.map((s) => s.id),
  };
  for (const [kind, list] of Object.entries(ids)) {
    for (const id of list) {
      const d = HcbRender.drawer(kind, id);
      if (!d) { failures.push(`${name}/drawer ${kind} ${id}: 未渲染`); continue; }
      check(`${name}/drawer ${kind} ${id}`, d.body + (d.foot || ""));
      cases++;
    }
  }
  check(`${name}/loading`, HcbRender.loading({ route: "overview" }));
  check(`${name}/failure`, HcbRender.failure({ route: "overview" }, "boom <x>"));
  cases += 2;
}

// 健康数据仍在后台读取：页面必须有可见的加载提示，而不是空白
const pending = JSON.parse(JSON.stringify(APP_STATE));
pending.healthPending = true;
HcbData.set(pending);
const pendingHtml = HcbRender.shell({ route: "coverage", sub: null, q: {}, auditFilter: "all" });
if (!/正在读取健康数据/.test(pendingHtml)) failures.push("pending health: 覆盖页没有加载提示");
check("app/coverage-pending", pendingHtml);

// caps 门禁：App 专属段不得出现在 NAS 形状态里，反之 App 必须渲染
const securityOf = (data) => {
  HcbData.set(data);
  return HcbRender.shell({ route: "security", sub: null, q: {}, auditFilter: "all" });
};
const nasSec = securityOf(NAS_STATE);
if (/本机访问/.test(nasSec)) failures.push("nas/security: caps.localAccess 缺省却渲染了「本机访问」");
check("nas/security-no-local", nasSec);
const appSec = securityOf(APP_STATE);
if (!/本机访问/.test(appSec)) failures.push("app/security: caps.localAccess 为真但没渲染「本机访问」");
if (!/hcb-demo\.trycloudflare\.com/.test(appSec)) failures.push("app/security: 公网基址没渲染");
if (!/不可达/.test(appSec)) failures.push("app/security: lastProbe 没渲染");
check("app/security-local", appSec);
cases += 3;

// 会话 TTL < 1 小时要显示分钟，不能出现「0.083 小时」
if (/0\.083 小时/.test(appSec)) failures.push("app/security: 会话 TTL 仍按小时显示小数");
if (!/5 分钟/.test(appSec)) failures.push("app/security: 会话 TTL 未显示分钟");

// localAccess 缺省 / 脏值 / 无 token：卡片仍在，但不许出现 undefined
for (const [label, local] of [
  ["empty", {}],
  ["dirty", { tunnelMode: "bogus", publicBase: "   ", lastProbe: { ok: false } }],
  ["no-token", { tunnelMode: "quick", hasToken: false }],
]) {
  check(`app/security-local-${label}`, securityOf(Object.assign({}, APP_STATE, { localAccess: local })));
  cases++;
}

// 策略编辑器：caps.policies 开才有
HcbData.set(NAS_STATE);
const nasTok = HcbRender.drawer("token", NAS_STATE.tokens[0].id);
if (/policy-edit/.test(nasTok.body)) failures.push("nas/token: caps.policies 缺省却渲染了策略编辑器");
check("nas/drawer token (no policy)", nasTok.body + (nasTok.foot || ""));
HcbData.set(APP_STATE);
const appTok = HcbRender.drawer("token", APP_STATE.tokens[0].id);
if (!/policy-edit/.test(appTok.body)) failures.push("app/token: caps.policies 为真但没渲染策略编辑器");
check("app/drawer token (policy)", appTok.body + (appTok.foot || ""));
const appCli = HcbRender.drawer("oauth", APP_STATE.oauth[0].id);
if (!/policy-edit/.test(appCli.body)) failures.push("app/oauth: caps.policies 为真但没渲染策略编辑器");
check("app/drawer oauth (policy)", appCli.body + (appCli.foot || ""));
cases += 3;

if (failures.length) {
  console.error("admin-ui smoke: FAIL");
  for (const line of failures) console.error("  - " + line);
  process.exit(1);
}
console.log(`admin-ui smoke: ok (${cases} renders, 2 state shapes)`);
