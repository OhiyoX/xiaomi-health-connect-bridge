# 管理后台 UI 重设计落地（NAS + App）

日期：2026-11-03
原型：`docs/admin-ui-proto/`（ai-pi/hc-bridge main `53699c7`）
状态：设计已定，待实现

## 1. 目标

按原型把两套管理后台完整落地：

- **NAS**：`https://health.example.com/admin`，Python 3.12 标准库 SSR。
- **App**：Android 本地 MCP 后台，WebView 加载内置 asset，JS bridge 调本地 store。

视觉、信息架构、交互层级以原型为准；数据全部接真实后端，不再用 mock。

## 2. 非目标

- 不引入前端框架、构建链、CDN、外部字体。
- 不把 NAS 改成 SPA/JSON API；保留 SSR + POST/CSRF。
- 不在 App 新增 `/admin` HTTP 路由；管理面只存在于 WebView + bridge。
- 不改变 OAuth/MCP 协议行为；只改管理面。
- 不新增 App 的 Room/Compose/biometric 依赖。

## 3. 统一设计系统

单一来源：`docs/admin-ui-proto/src/1-head.html` 的 `:root` / `[data-theme]` / 组件 CSS。

- 由 `scripts/build-admin-ui.py` 抽取 CSS，生成：
  - `deploy/nas/hcb/style.py`（`ADMIN_CSS` 常量，install.sh 已复制 `hcb/*.py`）
  - `app/src/main/assets/admin/admin.css`
- 同时复制图标 sprite（`3-body.html` 的 `<symbol>`）到两端模板。
- 断点：1100px、720px；52px 页头；4px 栅格；字号 13/14/16/20；字重 400/600。
- 深浅主题：NAS 用 `?theme=dark|light` + cookie；App 跟随系统，侧栏可切，存 `localStorage`（仅主题）。
- 移动端：底栏四键、表格转卡片、抽屉转底 sheet。

## 4. 共享前端（唯一实现）

原型 SPA 收敛为 `admin-ui/`，NAS 与 App 共用同一套 HTML/CSS/JS：

```
admin-ui/
  index.html          # 壳 + 路由 + 页面（来自原型）
  admin.css           # 由原型 CSS 生成
  sprite.svg
  data.js             # 标签映射/空状态/DATA 形状
  render.js           # 列表/抽屉/图表渲染
  app.js              # 路由、交互、动作调用
  adapters/nas.js     # fetch /admin/api/*
  adapters/app.js     # window.HcbAdmin bridge
```

- 唯一差异是 `adapter.js`：构建时 NAS 用 `adapters/nas.js`，App 用 `adapters/app.js`。
- 统一接口：

```js
window.AdminAPI = {
  async state(),                    // -> DATA
  async act(name, payload),         // -> {ok, data?, error?}
  clipboard(text),                  // NAS: navigator.clipboard；App: bridge
  logout()                          // NAS: /logout；App: 关闭 Activity
}
```

- `DATA` 形状固定：`me, overview{kpis,sync,backfill,coverage,shortcuts}, users, sessions,
  tokens, oauth, pending, coverage, sleep, metrics, audit{rows,chainOk}, security, csrf?`。
- 前端不再 mock；所有 mutation 调 `AdminAPI.act`，成功后重新 `state()`。
- 登录：NAS 由 `/login` SSR 完成后跳 `/admin`；App 由原生门禁解锁后加载。
  SPA 内不保留 `#/login`（仅 401 时整页跳转/提示）。
- 路由：hash 路由保留；NAS 同时把旧 `/admin/users` 等 302 到 `/admin#/users`。
- 移动端/主题/抽屉/toast 全部沿用原型。

## 5. NAS 后端（API）

- `/admin` 返回共享 SPA；`/admin/assets/*` 提供静态文件（install.sh 复制 `admin_ui/`）。
- `GET /admin/api/state`：一次性返回 `DATA`。
- `POST /admin/api/act`：JSON `{name,payload}`，要求同源 + `X-CSRF-Token`；
  返回 `{ok:true,data}` / `{ok:false,error}`；401 返回 JSON 而非 302。
- 保留管理员会话门禁；所有写动作逐项校验并写审计。
- CSP 收紧为 `script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'`；
  脚本全部外链，无内联 `onclick`。
- `hcb/stats.py`：聚合查询（coverage/series/sleep_days/sync/audit fails），60s TTL。
- 旧 SSR 页面保留为回退一周：`/admin/legacy/*`；主入口 `/admin` 只走 SPA。
- 数据缺口：角色由 scopes 推导；最近登录取 sessions；IP/位置「—」；
  token prefix 用短 id；OAuth 类型由 `auth_method` 推导。

## 5b. App 后端（bridge）

- WebView 加载同一套 `admin-ui`，`adapter.js` 换成 `adapters/app.js`。
- `AdminBridge.state()` / `act(name,payloadJson)` / `clipboard()` / `close()`。
- `AdminStateJson`、`AdminActions` 复用现有 store/OAuth/HcQuery；无 HTTP 管理面。
- users→单管理员，sessions→解锁会话；其余路由与原型一致。
- 健康页现场查 HC，IO + 60s 缓存；回填走 `SyncWorker`。

## 6. 安全

- 所有输出转义；NAS 只用 `views.raw` 包自建标记；SVG 仅数字。
- 新 POST 必须过 `_same_origin` + `csrf_verify` + `_admin_session`；GET 不触发写。
- 新查询加 TTL 缓存与 `ratelimit`，避免 1.2M 行表被反复扫。
- App bridge 每个方法独立校验 `AdminThrottle`；不相信页面状态。
- 日志/审计不含密码、token、code、Cookie、健康数值明细。
- WebView CSP：`default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data:`。

## 7. 验收

NAS：

- `python3 -m unittest discover -s deploy/nas/tests` 全绿（现有 415+ 新测试）。
- `/admin`、`/admin/coverage`、`/admin/sleep`、`/admin/metrics` 200；未登录 302；CSRF 缺失 400。
- 1.2M 行库上 coverage/metrics 单次 < 1s（缓存前 < 3s）。
- 线上 `health.example.com/admin` 登录、TOTP、四组导航、移动端底栏。

App：

- `scripts/gate.sh --fast` 全绿；`assembleDebug`。
- 真机：解锁后 WebView 加载、四组导航、一次性 key、待确认、覆盖/睡眠/指标、锁定销毁、`/admin` 仍 404。
- APK 覆盖安装（debug keystore 签名）保留数据。

## 8. 风险

- NAS 1.2M 行聚合性能：先 `EXPLAIN QUERY PLAN`，必要时按 `ts` 范围查询 + 缓存。
- 原型 CSS 内联到 Python 字符串的体积（约 30KB）可接受。
- App WebView lint `addJavascriptInterface` 警告：显式抑制并写明理由。
- 两套实现视觉漂移：CSS 由脚本从同一源生成。
