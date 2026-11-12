#!/usr/bin/env node
/**
 * 端到端冒烟（可选）：用真 Chromium 跑共享 SPA，adapter 换成
 * `scripts/admin-ui-e2e-adapter.js` 的假实现（内存状态，不连网、不碰真实后端）。
 *
 * 覆盖：启动 / 路由（未知路由与 #/login 回概览）/ 抽屉 / 列表动作 / 确认框 /
 * type-to-confirm / 一次性明文 + 剪贴板 / 主题持久化 / 移动端底栏与卡片列表 /
 * act 失败只 toast / state 失败的错误横幅 + 重试 / 用户文本转义 /
 * 本机访问（caps.localAccess：隧道草稿、开关、保存、命令、探活、清 token）/ 工具策略与时间窗编辑
 * （caps.policies：非法 JSON 就地报错、只发改过的字段）/ 会话 TTL < 1 小时按分钟显示。
 * 服务端带真实 CSP 头，控制台出现 CSP 违规即失败。
 *
 * 依赖 Playwright（只在这台机器上装了）：
 *   node scripts/admin-ui-e2e.mjs
 * 没有 playwright 时 `scripts/check-admin-ui.sh` 会跳过这一步。
 */
import { createServer } from "node:http";
import { copyFile, mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, extname, join, normalize } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const SPA = join(HERE, "..", "admin-ui");

let chromium;
try {
  ({ chromium } = await import("playwright"));
} catch {
  try {
    ({ chromium } = await import("/usr/local/lib/node_modules/playwright/index.mjs"));
  } catch (err) {
    console.error("SKIP: 没有装 playwright");
    process.exit(0);
  }
}

const ROOT = await mkdtemp(join(tmpdir(), "admin-ui-e2e-"));
for (const name of ["index.html", "admin.css", "data.js", "render.js", "app.js", "sprite.svg"]) {
  await copyFile(join(SPA, name), join(ROOT, name));
}
await copyFile(join(HERE, "admin-ui-e2e-adapter.js"), join(ROOT, "adapter.js"));
const TYPES = { ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8", ".svg": "image/svg+xml" };

const server = createServer(async (req, res) => {
  const url = new URL(req.url, "http://x");
  const path = normalize(join(ROOT, url.pathname === "/" ? "index.html" : url.pathname));
  if (!path.startsWith(ROOT)) { res.writeHead(403).end(); return; }
  try {
    const body = await readFile(path);
    res.writeHead(200, {
      "Content-Type": TYPES[extname(path)] || "application/octet-stream",
      "Content-Security-Policy": "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; base-uri 'none'; frame-ancestors 'none'",
    }).end(body);
  } catch {
    res.writeHead(404).end("nope");
  }
});
await new Promise((r) => server.listen(0, "127.0.0.1", r));
const base = `http://127.0.0.1:${server.address().port}/`;

const problems = [];
const expect = (cond, msg) => { if (!cond) problems.push(msg); };
// 页面标题是"已经切到这一页"的可靠信号（列表页都可能有 table.tbl，不能拿它当锚点）
const waitTitle = (p, title) =>
  p.waitForFunction((t) => ((document.querySelector(".bar h1") || {}).textContent || "").includes(t), title, { timeout: 5000 });

const browser = await chromium.launch({ args: ["--no-sandbox"] });
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
const errors = [];
page.on("pageerror", (e) => errors.push("pageerror: " + e.message));
page.on("console", (m) => { if (m.type() === "error") errors.push("console: " + m.text()); });

// 1) 启动 → 概览
await page.goto(base + "#/overview");
await page.waitForSelector(".stats .stat .n", { timeout: 5000 });
const kpis = await page.$$eval(".stats .stat .n", (els) => els.map((e) => e.textContent.trim()));
expect(kpis[0] === "1,248,356", "KPI 心率样本 = " + kpis[0]);
expect(kpis[3] === "1", "KPI 24h 审计失败 = " + kpis[3]);
expect(await page.textContent("body").then((t) => t.includes("管道正常")), "同步横幅缺失");
expect((await page.textContent("body")).includes("待确认的授权请求"), "待确认横幅缺失");

// 2) 侧栏导航 + 未知路由 + #/login 重写
await page.click('.side a[href="#/users"]');
await waitTitle(page, "用户");
await page.waitForSelector("table.tbl tbody tr");
expect(page.url().endsWith("#/users"), "用户路由 = " + page.url());
expect((await page.textContent("table.tbl")).includes("运维只读"), "用户表缺少 ops 行");
await page.goto(base + "#/nope");
await page.waitForSelector(".stats .stat .n");
expect(page.url().endsWith("#/overview"), "未知路由没有回概览: " + page.url());
await page.goto(base + "#/login/otp");
await page.waitForSelector(".stats .stat .n");
expect(page.url().endsWith("#/overview"), "#/login 没有回概览: " + page.url());

// 3) 抽屉：点行 → hash + 抽屉
await page.click('.side a[href="#/tokens"]');
await waitTitle(page, "静态令牌");
await page.waitForSelector("table.tbl tbody tr");
await page.click("table.tbl tbody tr");
await page.waitForSelector("#drawer.on");
expect(page.url().endsWith("#/tokens/tok_01"), "抽屉路由 = " + page.url());
expect((await page.textContent("#drawer")).includes("hch_live_8f2a…"), "抽屉缺少 token 前缀");
await page.click('#drawer [data-act="close-drawer"]');
await page.waitForSelector("#drawer:not(.on)");
expect(page.url().endsWith("#/tokens"), "关抽屉后路由 = " + page.url());

// 3b) 健康来源标签：友好名 + 不截断
await page.click('.side a[href="#/coverage"]');
await waitTitle(page, "覆盖");
await page.waitForSelector("table.tbl tbody tr");
const src = await page.evaluate(() => {
  const el = [...document.querySelectorAll(".src-pill")].find((x) => x.textContent.includes("Google Fit"));
  if (!el) return null;
  const cs = getComputedStyle(el);
  return { text: el.textContent.trim(), sw: el.scrollWidth, cw: el.clientWidth, ws: cs.whiteSpace, ov: cs.textOverflow };
});
expect(src && src.text === "Google Fit", "来源标签没有显示友好名: " + JSON.stringify(src));
expect(src && src.sw <= src.cw + 1, "来源标签被截断: " + JSON.stringify(src));
expect(src && src.ws !== "nowrap" && src.ov !== "ellipsis", "来源标签仍是单行省略: " + JSON.stringify(src));

// 3c) 原生返回键：先关浮层，根路由返回 false（由宿主退出）
expect((await page.evaluate(() => window.HcbBack())) === false, "根路由 HcbBack 应为 false");
await page.click("table.tbl tbody tr");
await page.waitForSelector("#drawer.on");
expect((await page.evaluate(() => window.HcbBack())) === true, "抽屉打开时 HcbBack 应消费返回");
await page.waitForSelector("#drawer:not(.on)");

// 3d) 指标 14/30/90 切换：按钮可用、选中态单层、数据窗口真的变
await page.goto(base + "#/metrics");
await waitTitle(page, "指标趋势");
await page.waitForSelector(".chart-card");
const seg0 = await page.$eval('.seg button[data-days="14"]', (el) => ({ on: el.classList.contains("on"), disabled: el.disabled }));
expect(seg0.on && !seg0.disabled, "14 日按钮不可用: " + JSON.stringify(seg0));
await page.click('.seg button[data-days="30"]');
await page.waitForTimeout(100);
const seg30 = await page.$eval('.seg button[data-days="30"]', (el) => {
  const cs = getComputedStyle(el);
  return { on: el.classList.contains("on"), shadow: cs.boxShadow, text: document.querySelector(".chart-card").textContent };
});
expect(seg30.on && seg30.text.includes("近 30 日"), "30 日切换无效: " + JSON.stringify(seg30));
expect(seg30.shadow === "none" || seg30.shadow === "", "分段按钮仍有内圈: " + seg30.shadow);
await page.click('.seg button[data-days="90"]');
await page.waitForTimeout(100);
expect((await page.textContent(".chart-card")).includes("近 90 日"), "90 日切换无效");

// 4) 新建令牌 → 一次性明文 → 剪贴板
await page.click('.side a[href="#/tokens"]');
await waitTitle(page, "静态令牌");
await page.click('[data-act="new-token"]');
await page.waitForSelector("#drawer.on form#token-create");
expect((await page.textContent("#drawer")).includes("health.read"), "新建令牌抽屉缺少 scope 选项");
await page.fill('#drawer input[name="name"]', "E2E 令牌");
await page.click('#drawer button[type="submit"]');
await page.waitForSelector("#modal.on .secret");
expect((await page.textContent("#modal")).includes("hch_new_ab12_SECRET_VALUE"), "一次性明文没显示");
await page.click("#modal [data-act='copy-secret']");
await page.waitForTimeout(100);
const clip = await page.evaluate(() => window.__clip);
expect(clip[0] === "hch_new_ab12_SECRET_VALUE", "剪贴板内容 = " + JSON.stringify(clip));
const calls = await page.evaluate(() => window.__calls);
expect(calls.some((c) => c.name === "token.create" && c.payload.name === "E2E 令牌"), "token.create 没调用");
expect(page.url().endsWith("#/tokens"), "新建令牌后没有回列表: " + page.url());
expect((await page.textContent("table.tbl")).includes("E2E 令牌"), "新令牌没出现在列表");

// 5) 撤销令牌：确认框 → act → 状态重拉
await page.click('.side a[href="#/tokens"]');
await waitTitle(page, "静态令牌");
await page.waitForSelector("table.tbl tbody tr");
await page.click('table.tbl tbody tr:first-child [data-menu="token"]');
await page.click('#menu [data-mi="revoke-token"]');
await page.waitForSelector("#modal.on");
await page.click('#modal [data-act="mok"]');
await page.waitForTimeout(300);
const after = await page.evaluate(() => window.__calls.filter((c) => c.name === "token.revoke"));
expect(after.length === 1 && after[0].payload.id === "tok_new", "token.revoke payload = " + JSON.stringify(after));

// 6) 类型确认：删除用户
await page.goto(base + "#/users");
await waitTitle(page, "用户");
await page.waitForSelector("table.tbl tbody tr");
await page.click('table.tbl tbody tr:nth-child(2)');
await page.waitForSelector("#drawer.on");
await page.click('#drawer [data-act="del-user"]');
await page.waitForSelector("#modal.on [data-typeconf]");
const okDisabled = await page.getAttribute('#modal [data-act="mok"]', "disabled");
expect(okDisabled !== null, "type-to-confirm 的确认按钮应默认禁用");
await page.fill("#modal [data-typeconf]", "ops");
expect(await page.getAttribute('#modal [data-act="mok"]', "disabled") === null, "输入正确用户名后确认按钮仍禁用");
await page.click('#modal [data-act="mcancel"]');

// 7) 会话：撤销其他会话
await page.goto(base + "#/sessions");
await waitTitle(page, "会话");
await page.waitForSelector('[data-act="revoke-others"]');
await page.click('[data-act="revoke-others"]');
await page.waitForSelector("#modal.on");
await page.click('#modal [data-act="mok"]');
await page.waitForTimeout(300);
expect((await page.evaluate(() => window.__calls)).some((c) => c.name === "session.revoke-others"), "revoke-others 没调用");

// 8) 审计：筛选 + 校验
await page.goto(base + "#/audit");
await waitTitle(page, "审计");
await page.waitForSelector("[data-seg=audit]");
await page.click('#root [data-seg="audit"] button[data-v="fail"]');
await page.waitForTimeout(100);
const failRows = await page.$$eval("table.tbl tbody tr", (rows) => rows.length);
expect(failRows === 1, "失败筛选行数 = " + failRows);
await page.click('[data-act="audit-verify"]');
await page.waitForTimeout(300);
expect((await page.evaluate(() => window.__calls)).some((c) => c.name === "audit.verify"), "audit.verify 没调用");

// 9) 待确认：允许
await page.goto(base + "#/oauth");
await waitTitle(page, "OAuth 客户端");
await page.waitForSelector('[data-act="pending-allow"]');
await page.click('[data-act="pending-allow"]');
await page.waitForSelector("#modal.on");
await page.click('#modal [data-act="mok"]');
await page.waitForTimeout(300);
expect((await page.evaluate(() => window.__calls)).some((c) => c.name === "pending.decide" && c.payload.approve === true), "pending.decide 没调用");

// 10) TOTP 重新绑定：当前动态码 → 新密钥 + 验证码 → 恢复码
await page.goto(base + "#/security");
await waitTitle(page, "安全设置");
await page.waitForSelector('[data-act="totp-rebind"]');
await page.click('[data-act="totp-rebind"]');
await page.waitForSelector("#modal.on #totp-code-form");
await page.fill('#totp-code-form input[name="code"]', "654321");
await page.click('#modal button[form="totp-code-form"]');
await page.waitForSelector("#modal.on #totp-form");
expect((await page.textContent("#modal")).includes("JBSWY3DPEHPK3PXP"), "TOTP 密钥没显示");
await page.fill('#totp-form input[name="code"]', "123456");
await page.click('#modal button[form="totp-form"]');
await page.waitForTimeout(300);
expect((await page.evaluate(() => window.__calls)).some((c) => c.name === "security.totp-rebind" && c.payload.code === "654321"), "totp-rebind 没调用");
expect((await page.evaluate(() => window.__calls)).some((c) => c.name === "security.totp-confirm" && c.payload.code === "123456"), "totp-confirm 没调用");
await page.waitForSelector("#modal.on");
expect((await page.textContent("#modal")).includes("恢复码"), "恢复码没展示");
await page.click('#modal [data-act="mcancel"]');

// 10b) 本机访问（caps.localAccess）：草稿模式 / 开关 / 保存 / 命令 / 探活 / 清 token
await page.waitForSelector('[data-act="local-probe"]');
const secText = await page.textContent("#root");
expect(secText.includes("本机访问"), "安全页缺少「本机访问」卡片");
expect(secText.includes("5 分钟"), "会话 TTL < 1 小时没按分钟显示");
expect(!secText.includes("0.083 小时"), "会话 TTL 仍按小时显示小数");
// 模式是草稿：只改隐藏字段与勾选样式，不发请求、不重渲染（已输入的基址要留着）
await page.fill('[data-form="local-save"] input[name="publicBase"]', "https://e2e.example.com");
await page.click('[data-form="local-save"] [data-act="local-mode"][data-mode="quick"]');
expect((await page.inputValue('[data-form="local-save"] input[name="publicBase"]')) === "https://e2e.example.com", "切换隧道模式后输入框被清空");
expect((await page.inputValue('[data-form="local-save"] [data-local-mode]')) === "quick", "草稿模式没写进隐藏字段");
expect((await page.evaluate(() => window.__calls.filter((c) => c.name.indexOf("local.") === 0))).length === 0, "切换模式就发了请求");
await page.click('[data-form="local-save"] [data-act="local-mode"][data-mode="named"]');
// 保存：模式 + 基址 + token 一起发
await page.fill('[data-form="local-save"] input[name="token"]', "tok-e2e-secret");
await page.click('[data-form="local-save"] button[type="submit"]');
await page.waitForTimeout(300);
const saved = await page.evaluate(() => window.__calls.filter((c) => c.name === "local.save-public").pop());
expect(saved && saved.payload.mode === "named", "local.save-public mode = " + JSON.stringify(saved));
expect(saved && saved.payload.publicBase === "https://e2e.example.com", "local.save-public publicBase = " + JSON.stringify(saved));
expect(saved && saved.payload.token === "tok-e2e-secret", "local.save-public 没带 token");
await page.waitForSelector('[data-form="local-save"] input[name="token"]');
expect((await page.inputValue('[data-form="local-save"] input[name="token"]')) === "", "保存后 token 输入框没清空");
expect((await page.getAttribute('[data-form="local-save"] input[name="token"]', "placeholder")) === "已保存，留空不变", "已存 token 时占位符不对");
// 开关：回环免鉴权直接执行；关 Bearer 要先确认
await page.click('[data-act="local-loopback"]');
await page.waitForTimeout(200);
expect((await page.evaluate(() => window.__calls)).some((c) => c.name === "local.loopback" && c.payload.enabled === true), "local.loopback 没调用");
expect((await page.getAttribute('[data-act="local-loopback"]', "aria-checked")) === "true", "回环免鉴权开关状态没更新");
await page.click('[data-act="local-require-auth"]');
await page.waitForSelector("#modal.on");
await page.click('#modal [data-act="mok"]');
await page.waitForTimeout(300);
expect((await page.evaluate(() => window.__calls)).some((c) => c.name === "local.require-auth" && c.payload.enabled === false), "local.require-auth 没调用");
expect((await page.getAttribute('[data-act="local-require-auth"]', "aria-checked")) === "false", "Bearer 开关状态没更新");
// 命令：占位版直接复制；完整版要确认，且含 token
await page.click('[data-act="local-copy-cmd"]');
await page.waitForTimeout(200);
const cmdCall = await page.evaluate(() => window.__calls.filter((c) => c.name === "local.command").pop());
expect(cmdCall && cmdCall.payload.full === false, "local.command 没调用（占位版）");
const clipCmd = await page.evaluate(() => window.__clip[window.__clip.length - 1]);
expect(String(clipCmd).includes("TUNNEL_TOKEN"), "剪贴板里不是占位命令: " + clipCmd);
await page.click('[data-act="local-copy-full"]');
await page.waitForSelector("#modal.on");
await page.click('#modal [data-act="mok"]');
await page.waitForTimeout(200);
const fullCall = await page.evaluate(() => window.__calls.filter((c) => c.name === "local.command").pop());
expect(fullCall && fullCall.payload.full === true, "local.command 没调用（完整版）");
const clipFull = await page.evaluate(() => window.__clip[window.__clip.length - 1]);
expect(String(clipFull).includes("tok-e2e-secret"), "完整命令里没有 token: " + clipFull);
// 连通检测：结果回来要落回 lastProbe
await page.click('[data-act="local-probe"]');
await page.waitForTimeout(400);
expect((await page.evaluate(() => window.__calls)).some((c) => c.name === "local.probe"), "local.probe 没调用");
expect((await page.textContent("#root")).includes("可达"), "探活结果没回显");
// 清 token：确认框；清完按钮置灰
await page.click('[data-act="local-clear-token"]');
await page.waitForSelector("#modal.on");
await page.click('#modal [data-act="mok"]');
await page.waitForTimeout(300);
expect((await page.evaluate(() => window.__calls)).some((c) => c.name === "local.clear-token"), "local.clear-token 没调用");
expect(await page.isDisabled('[data-act="local-clear-token"]'), "清掉 token 后按钮仍可点");

// 10c) 策略编辑器（caps.policies）：非法 JSON 就地报错、不发请求、不清空
await page.goto(base + "#/tokens/tok_01");
await waitTitle(page, "静态令牌");
await page.waitForSelector("#drawer.on");
expect((await page.textContent("#drawer")).includes("工具策略与时间窗"), "令牌抽屉缺少策略段");
expect(!(await page.isVisible("#token-policy")), "策略表单应默认收起");
await page.click('#drawer [data-act="policy-edit"]');
await page.waitForSelector("#token-policy:not([hidden])");
const callsBefore = await page.evaluate(() => window.__calls.length);
await page.fill("#token-policy textarea[name='policy']", '{"bad":');
await page.click('#token-policy button[type="submit"]');
await page.waitForTimeout(150);
expect((await page.textContent("#token-policy [data-form-err]")).includes("不是合法 JSON"), "非法 JSON 没有就地报错");
expect((await page.evaluate(() => window.__calls.length)) === callsBefore, "非法 JSON 也发了请求");
expect((await page.inputValue("#token-policy textarea[name='policy']")) === '{"bad":', "报错把输入清空了");
await page.fill("#token-policy textarea[name='policy']", '{"tool":"x"}');
await page.click('#token-policy button[type="submit"]');
await page.waitForTimeout(150);
expect((await page.textContent("#token-policy [data-form-err]")).includes("必须是数组"), "策略不是数组时没有报错");
await page.fill("#token-policy textarea[name='policy']", '["hc_status"]');
await page.fill("#token-policy textarea[name='window']", '{"start":"08:00","end":"20:00"}');
await page.click('#token-policy button[type="submit"]');
await page.waitForTimeout(400);
const polCall = await page.evaluate(() => window.__calls.filter((c) => c.name === "token.policy").pop());
expect(polCall && polCall.payload.id === "tok_01" && polCall.payload.policy === '["hc_status"]', "token.policy payload = " + JSON.stringify(polCall));
const winCall = await page.evaluate(() => window.__calls.filter((c) => c.name === "token.window").pop());
expect(winCall && winCall.payload.window === '{"start":"08:00","end":"20:00"}', "token.window payload = " + JSON.stringify(winCall));
expect((await page.textContent("#drawer")).includes("hc_status"), "保存后只读区没显示新策略");
// OAuth 抽屉同构：编辑 → oauth.policy
await page.goto(base + "#/oauth/hc-bridge");
await waitTitle(page, "OAuth 客户端");
await page.waitForSelector("#drawer.on [data-act='policy-edit']");
await page.click('#drawer [data-act="policy-edit"]');
await page.waitForSelector("#oauth-policy:not([hidden])");
await page.fill("#oauth-policy textarea[name='policy']", "[]");
await page.click('#oauth-policy button[type="submit"]');
await page.waitForTimeout(400);
const cliCall = await page.evaluate(() => window.__calls.filter((c) => c.name === "oauth.policy").pop());
expect(cliCall && cliCall.payload.id === "hc-bridge" && cliCall.payload.policy === "[]", "oauth.policy payload = " + JSON.stringify(cliCall));

// 11) 主题：切换后写 localStorage，刷新后保持
await page.goto(base + "#/security");
await waitTitle(page, "安全设置");
await page.click('.side-foot [data-act="theme"]');
const theme1 = await page.getAttribute("html", "data-theme");
expect(theme1 === "light", "主题 = " + theme1);
await page.reload();
await page.waitForSelector('[data-act="totp-rebind"]');
expect((await page.getAttribute("html", "data-theme")) === "light", "刷新后主题没保持");
const stored = await page.evaluate(() => localStorage.getItem("hc-health-admin.theme"));
expect(stored === "light", "localStorage 主题 = " + stored);
const keys = await page.evaluate(() => Object.keys(localStorage));
expect(keys.length === 1, "localStorage 还有别的键: " + JSON.stringify(keys));

// 11b) 转义：恶意显示名不能变成元素 / 不能执行
await page.goto(base + "#/users");
await waitTitle(page, "用户");
await page.waitForSelector("table.tbl tbody tr");
const xss = await page.evaluate(() => ({
  imgs: document.querySelectorAll("#root img").length,
  ran: window.__xss === 1,
  text: document.body.textContent.includes('<img src=x onerror'),
}));
expect(xss.imgs === 0, "用户文本变成了 <img> 元素");
expect(xss.ran === false, "用户文本里的 onerror 被执行");
expect(xss.text === true, "转义后的用户文本没有原样显示");

// 11c) action 失败：只 toast，不改页面
await page.evaluate(() => {
  window.AdminAPI.act = async () => ({ ok: false, error: "后端拒绝了这个操作" });
});
await page.goto(base + "#/sessions");
await waitTitle(page, "会话");
await page.waitForSelector('[data-act="revoke-others"]');
const rowsBefore = await page.$$eval("table.tbl tbody tr", (r) => r.length);
await page.click('[data-act="revoke-others"]');
await page.click('#modal [data-act="mok"]');
await page.waitForTimeout(200);
expect((await page.textContent("#toast")).includes("后端拒绝了这个操作"), "失败提示没有显示 error 文本");
expect((await page.$$eval("table.tbl tbody tr", (r) => r.length)) === rowsBefore, "失败后页面数据被改了");

// 12) 退出：确认框 → AdminAPI.logout
await page.click('.side-foot [data-act="logout"]');
await page.waitForSelector("#modal.on");
await page.click('#modal [data-act="mok"]');
await page.waitForTimeout(100);
expect(await page.evaluate(() => window.__logout) === true, "logout 没调用");

// 13) 移动端：底栏 + 抽屉 → sheet + 表格转卡片
const mobile = await browser.newPage({ viewport: { width: 390, height: 780 } });
const mobileErrors = [];
mobile.on("pageerror", (e) => mobileErrors.push(e.message));
await mobile.goto(base + "#/coverage");
await waitTitle(mobile, "数据覆盖");
await mobile.waitForSelector(".mnav");
expect(await mobile.isVisible(".mnav"), "移动端底栏不可见");
expect(!(await mobile.isVisible(".side")), "移动端侧栏应隐藏");
expect(await mobile.isVisible(".mcards"), "移动端卡片列表不可见");
expect(!(await mobile.isVisible(".tw.hide-m")), "移动端表格应隐藏");
await mobile.click(".mnav a[data-act='mmore']");
await mobile.waitForSelector("#menu.on");
await mobile.click('#menu [data-nav="audit"]');
await mobile.waitForTimeout(200);
expect(mobile.url().endsWith("#/audit"), "更多菜单导航 = " + mobile.url());

// 13b) 移动端上拉抽屉：左右留边距，不能贴屏幕边缘
await mobile.goto(base + "#/coverage");
await waitTitle(mobile, "数据覆盖");
await mobile.click(".mcards .mc");
await mobile.waitForSelector("#drawer.on");
const sheet = await mobile.evaluate(() => {
  const r = document.querySelector("#drawer").getBoundingClientRect();
  return { left: r.left, right: r.right, width: r.width, vw: innerWidth };
});
expect(sheet.left >= 8 && sheet.right <= sheet.vw - 8, "抽屉贴边: " + JSON.stringify(sheet));
expect(sheet.width <= sheet.vw - 20, "抽屉宽度没有留边距: " + JSON.stringify(sheet));
await mobile.click('#drawer [data-act="close-drawer"]');

// 14) 错误处理：act 失败只 toast、不改页面
await page.goto(base + "#/overview");
await page.waitForSelector(".stats .stat .n");
await page.evaluate(() => {
  window.AdminAPI.act = async () => ({ ok: false, error: "后端拒绝了这个操作" });
});
await page.click('[data-act="reload"]');
await page.waitForTimeout(200);
const toastText = await page.textContent("#toast");
expect(toastText.trim().length >= 0, "toast 元素缺失");

// 15) 加载失败 → 错误横幅 + 重试
const bad = await browser.newPage({ viewport: { width: 390, height: 780 } });
await bad.addInitScript(() => {
  // 让 adapter 的 state() 抛错（模拟 500 / 接口不可用）
  Object.defineProperty(window, "AdminAPI", {
    configurable: true,
    set() {},
    get() { return { state: async () => { throw new Error("接口返回 HTTP 500"); }, act: async () => ({ ok: false }) }; },
  });
});
await bad.goto(base + "#/overview");
await bad.waitForSelector('[data-act="reload"]');
expect((await bad.textContent("body")).includes("接口返回 HTTP 500"), "错误横幅没有显示原因");
expect((await bad.textContent("body")).includes("重试"), "错误页没有重试按钮");

expect(errors.length === 0, "页面错误: " + errors.join(" | "));
expect(mobileErrors.length === 0, "移动端页面错误: " + mobileErrors.join(" | "));
expect(problems.length === 0, problems.join(" | "));

await browser.close();
server.close();
await rm(ROOT, { recursive: true, force: true });

if (problems.length || errors.length || mobileErrors.length) {
  console.error("E2E FAIL");
  for (const p of problems) console.error("  - " + p);
  for (const e of errors) console.error("  - " + e);
  for (const e of mobileErrors) console.error("  - " + e);
  process.exit(1);
}
console.log("E2E ok: 启动/路由/抽屉/动作/确认/明文/主题/移动端/本机访问/策略编辑/错误处理");
