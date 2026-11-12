# 共享管理前端与 `AdminAPI` 契约

日期：2026-11-03 · 状态：实现完成（共享 SPA 侧），后端按本文实现

这套前端只有一份（`admin-ui/`），NAS 与 App 共用**同一份** HTML / CSS / JS。
两端唯一的差别是构建时注入的 `adapter.js`：

| 目标 | 输出目录 | adapter | 传输 |
| --- | --- | --- | --- |
| NAS | `deploy/nas/hcb/admin_ui/` | `adapters/nas.js` | 同源 HTTP JSON（`/admin/api/*`） |
| App | `app/src/main/assets/admin/` | `adapters/app.js` | WebView JS 桥（`window.HcbAdmin`） |

构建 / 校验：`python3 scripts/build-admin-ui.py`（`--check` 只校验）、`./scripts/check-admin-ui.sh`。

---

## 1. 文件与加载顺序

```
admin-ui/
  index.html    壳：sprite 内联 + <link admin.css> + 4 个 defer <script>
  admin.css     由 docs/admin-ui-proto/src/1-head.html 生成（勿手改）
  sprite.svg    由 docs/admin-ui-proto/src/3-body.html 生成（勿手改）
  data.js       标签映射 / 空状态 / 状态规范化 / 时间与数字格式化
  render.js     全部页面与抽屉（纯函数，返回 HTML 字符串）
  app.js        路由、事件、确认框、动作调用、主题
  adapters/nas.js   NAS adapter（构建时复制为 adapter.js）
  adapters/app.js   App adapter（构建时复制为 adapter.js）
```

加载顺序固定：`admin.css` → `data.js` → `render.js` → `app.js` → `adapter.js`，全部 `defer`。
`app.js` 在 `DOMContentLoaded` 启动，所以 `AdminAPI` 一定已经就绪。

资源引用是**相对路径**（`admin.css`、`data.js`…）。构建时按目标改写：

- NAS：绝对路径 `/admin/assets/…`（`/admin` 没有尾斜杠，相对路径会解析到站点根）；
- App：保持相对路径（`file:///android_asset/admin/…`）。

CSP 要求（NAS 用响应头，App 用页面 meta，构建时注入）：

```
default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline';
connect-src 'self'; img-src 'self' data:; base-uri 'none'; frame-ancestors 'none'
```

页面里**没有内联脚本、没有 `onclick`、没有外链资源**（`scripts/check-admin-ui.sh` 会挡住）。
App 的 WebView 用 `default-src 'none'; script-src 'self' file:; style-src 'self' file: 'unsafe-inline'; img-src data:`
（`file://` 文档是不透明源，必须额外列出 `file:`）。

---

## 2. `AdminAPI` 接口

```js
window.AdminAPI = {
  async state(),              // -> 状态 JSON（第 3 节）
  async act(name, payload),   // -> {ok:true, data:{…}} | {ok:false, error:"…"}
  async clipboard(text),      // 返回 {ok:true} 或抛出 Error
  theme(),                    // App: 原生 DayNight -> "dark"/"light"；NAS 可省略
  logout(),                   // NAS: POST /logout；App: 关闭管理页
  ready()                     // 可选：首屏渲染完成信号（App 用来收起原生加载指示）
};
```

前端约定：

- 启动时先画加载态，`DATA = await AdminAPI.state()` 成功后渲染；失败显示错误横幅 + 「重试」。
- 所有写操作都走 `act`；`{ok:false}` 只 toast `error` 文本，不改页面。
- **成功后重新 `state()`**，页面永远等于后端事实；不拿 `act` 的返回值就地改数据。
- 一次性明文（令牌 / client_secret / TOTP 密钥）只从 `act` 的 `data` 里取，只显示一次。

---

## 3. 状态 JSON

时间戳：**epoch 毫秒（推荐）**、epoch 秒、ISO 8601 字符串都接受；`null` 表示未知。
数字可以是 `null`（未知），前端显示 `—`，不会当 0。

```jsonc
{
  "version": "0.9.0",                 // 侧栏底部；可空
  "now": 1763700000000,               // 服务端时钟；可空（前端用本机时间做相对时间）
  "me": {"id": "1", "username": "hiyo", "display": "hiyo", "role": "admin"},

  "overview": {
    "sync": {
      "lastSyncAt": 1763700000000,
      "lastSyncSource": "xiaomi-cloud",   // 别名：last_source
      "lastSyncRows": 18420,
      "lagMin": 7,                        // 别名：lag_min
      "pipeline": "ok"                    // "ok" | "warn" | "unknown"
    },
    "backfill": {
      "status": "idle",                   // "idle" | "running" | "error"
      "progress": 1.0,                    // 0–1；null = 未知
      "rangeFrom": "2024-01-01",
      "rangeTo": "2026-11-02",
      "lastRun": 1763600000000,
      "note": ""
    },
    "kpis": {                             // 可选：不给就由前端从下面各段推导
      "heartRows": 1248356, "coverageTypes": 8, "lowCoverage": 2,
      "activeSessions": 3, "activeUsers": 2, "auditFails24h": 1
    }
  },

  "users": [{
    "id": "1", "username": "hiyo", "display": "hiyo",
    "role": "admin",                    // admin | viewer | service（缺省按 scopes 推导）
    "status": "active",                 // active | disabled（也接受 enabled:false）
    "totp": true,
    "lastLogin": 1763700000000,         // null = 从未登录
    "createdAt": 1760000000000,
    "sessions": 2,
    "note": "",
    "scopes": ["admin", "health.read"]
  }],

  "sessions": [{
    "id": "ses_a1", "userId": "1",
    "device": "Chrome · macOS", "ip": "100.64.1.12", "loc": "内网 · Tailscale",
    "createdAt": 1763690000000, "lastSeen": 1763700000000,
    "current": true, "expiresAt": null
  }],

  "tokens": [{
    "id": "tok_01",                    // 不透明字符串（App 会有 "key:1" / "tok:<family>:<kind>:<prefix>"）
    "name": "MCP Claude Desktop", "prefix": "hch_live_8f2a…",
    "scopes": ["health.read", "health.write"],
    "status": "active",                // active | revoked | expired
    "userId": "1",
    "createdAt": 1760000000000, "lastUsed": 1763700000000, "expires": null,
    "kind": "",                        // 可选：static | access | refresh（App）
    "family": "",                      // 可选：OAuth 令牌族，撤销时回传
    "policy": null,                    // 可选：工具策略 JSON 文本；null = 不限（App，caps.policies）
    "window": null                     // 可选：时间窗 JSON 文本；null = 不限时（App，caps.policies）
  }],

  "oauth": [{
    "id": "hc-bridge", "name": "HC Bridge", "clientId": "hc-bridge",
    "kind": "confidential",            // public | confidential
    "redirects": ["https://health.example.com/oauth/callback"],
    "status": "active",                // active | pending | disabled | revoked
    "createdAt": 1760000000000, "lastAuth": 1763700000000,
    "policy": null,                    // 可选：工具策略 JSON 文本；null = 不限（App，caps.policies）
    "window": null                     // 可选：时间窗 JSON 文本；null = 不限时（App，caps.policies）
  }],

  "pending": [{                        // /authorize 的待确认请求；没有就是 []
    "id": "req_1", "client": "Obsidian MCP", "clientId": "obsidian-mcp",
    "redirect": "127.0.0.1", "scopes": ["health.read"],
    "createdAt": 1763700000000, "expiresAt": 1763700600000
  }],

  "coverage": [{
    "type": "heart_rate", "label": "心率",
    "rows": 1248356,                   // 别名：records30d / records；null = 不统计
    "sources": ["apple_health", "huawei_health"],
    "days30": 30,                      // 别名：days_30d
    "pct": 0.98,                       // 缺省用 days30 / 30（覆盖窗口固定 30 天）
    "lastAt": 1763700000000            // 别名：last
  }],

  "sleep": [{
    "date": "2026-11-02",
    "bed": "23:42", "wake": "07:08",   // 已格式化的时钟字符串，可直接展示
    "hours": 7.43, "score": null,
    "source": "apple_health",
    "flags": ["short"],                // missing|short|long|late|late_bed|fragile|fragmented|invalid
    "note": ""
  }],

  "metrics": {
    "days": 90,                        // 后端给 90 日序列；页面本地切 14/30/90
    "steps":      {"label": "步数", "unit": "步", "decimals": 0, "latest": 9842, "avg": 8750, "series": [8000, null, 9000]},
    "heart_rate": {"label": "心率（日均）", "unit": "bpm", "decimals": 0, "latest": 68, "avg": 71, "series": [70, 72]},
    "sleep":      {"label": "睡眠时长", "unit": "h", "decimals": 1, "latest": 7.4, "avg": 7.1, "series": [7.1, 7.4]}
  },

  "audit": {                           // 也接受直接给数组（App 旧形状）
    "rows": [{
      "id": "1024", "at": 1763700000000, "actor": "hiyo",
      "action": "token.create", "target": "tok_01",
      "outcome": "ok",                 // ok | fail；ok/approved -> 成功，其余 -> 失败
      "detail": "创建静态令牌"
    }],
    "chainOk": true                    // null = 未提供（页面显示"未提供"）
  },

  "security": {
    "totpEnabled": true, "totpEnrolledAt": 1760000000000,
    "passwordChangedAt": 1762000000000, "passwordSet": true,
    "sessionTtlHours": 168, "allowPasswordLogin": true, "requireTotp": true,
    "maxFailedLogins": 5, "lockoutMinutes": 15,
    "settingsEditable": true           // false = 隐藏"会话与锁定策略"的编辑表单
  },

  "localAccess": {                     // 可选：本机 MCP 服务器（App；caps.localAccess 才渲染）
    "requireAuth": true,               // 要求 Bearer
    "allowLoopbackNoAuth": false,      // 127.0.0.1 免鉴权
    "confirmNewClients": true,         // 新 OAuth 客户端要管理员批准
    "tunnelMode": "off",               // off | quick | named
    "publicBase": null,                // 公网基址（裸 https://host[:port]）；null = 只走本机
    "hasToken": true,                  // 只报"存没存"：tunnel token 本身绝不进状态
    "lastProbe": {                     // null = 从没检测过
      "ok": false, "at": 1763700000000, "detail": "HTTP 502"
    }
  },

  "caps": {                            // 可选：该端能力（App adapter 注入；NAS 缺省=full）
    "users": "single", "sessions": "single", "settings": false,
    "localAccess": true,               // App：安全页显示"本机访问"卡片
    "policies": true                   // App：令牌 / OAuth 抽屉显示策略与时间窗编辑器
  },

  "csrf": "…"                          // 仅 NAS：写请求要回传的 CSRF token
}
```

兼容别名（App 现有序列化器已经在用，前端都接受）：
顶层 `sync` / `sync.backfill` 等同于 `overview.sync` / `overview.backfill`；
`audit` 直接给数组时，链校验看顶层 `auditChainOk`；`coverage[].records30d` → `rows`；
`users[].display_name` → `display`；`sessions[].user_id` → `userId`；
`security.sessionTtlMinutes` → `sessionTtlHours`（÷60，仅在没有 `sessionTtlHours` 时用）。

策略与时间窗（`tokens[]` / `oauth[]` 的 `policy` / `window`，App 专属）：

- 值是**JSON 文本**（也接受已解析的值，前端会序列化），`null` = 不限；空串等于 `null`。
- `policy` 顶层只能是数组：`[]` = 全禁，`["hc_status"]` = 白名单，`[{"tool":…,"args":…}]` = 带参数规则。
- `window` 顶层只能是对象：如 `{"days":[1,2,3,4,5],"start":"08:00","end":"23:00"}`；`{}` = 全天。
- 页面只做 **JSON 语法 + 顶层类型**校验，规范化一律交给后端；非法输入就地报错、不清空、不发请求。
- 显示：`null` 显示「不限（null）」，其余原样显示（已转义）。

`security.sessionTtlHours` 的显示：`>= 1` 按小时（`168 小时`），`< 1` 按分钟（`0.083` → `5 分钟`）。

约定：

- **状态里没有秘密**：没有密码、密码哈希、TOTP secret、client_secret、令牌明文、Cookie、tunnel token。
  `localAccess.hasToken` 只能是布尔；`local.command` 的完整命令只出现在动作返回值里（页面只把它放进剪贴板，不渲染、不记录）。
- `tokens[].prefix` 只能是不可用的标识（短 id / 哈希前 8 位 / 真前缀脱敏）。
- 某一段取不到数据时给空数组 / `null`，不要让整个 `state` 失败。

---

## 4. 动作

请求：`POST /admin/api/act`，`{ "name": "<动作名>", "payload": {…} }`。
响应：`{ "ok": true, "data": {…} }` 或 `{ "ok": false, "error": "给人看的中文短句" }`。
**错误文案会原样 toast**，所以不要带路径、SQL、ID 之外的内部细节。

| 动作 | payload | `data`（成功） | 说明 |
| --- | --- | --- | --- |
| `user.create` | `{username, display, password, scopes[]}` | `{id}` | 用户名唯一；密码 ≥10；scopes ⊂ `health.read`,`health.write`。App 不支持（返回 `ok:false`）。 |
| `user.update` | `{id, display?, scopes?, password?}` | `{}` | `password` 非空才重置；`scopes` 省略=不改。App 不支持。 |
| `user.toggle` | `{id, enabled: bool}` | `{}` | 显式目标状态，不做"翻转"。App 不支持。 |
| `user.delete` | `{id, username}` | `{}` | `username` 必须等于目标用户名（type-to-confirm）。App 不支持。 |
| `session.revoke` | `{id}` | `{}` | 后端仍需拒绝撤销当前会话。App：等价"锁定"（id 为 `unlock`）。 |
| `session.revoke-others` | `{}` | `{revoked: int}` | App 只有当前会话，返回 `{revoked: 0}`。 |
| `token.create` | `{name, scopes[]}` | `{id, secret, prefix}` | `secret` 只在此次返回里出现。App：映射到 `key.create`（`label = name`，scopes 忽略）。 |
| `token.revoke` | `{id, family?}` | `{}` | App：`id` 前缀 `key:` → `key.revoke`；`tok:` → `token.revoke {family}`。 |
| `oauth.create` | `{name, kind, redirects[]}` | `{clientId, secret?}` | `kind`: `public`/`confidential`；`secret` 只有 confidential 才有，只返回一次。 |
| `oauth.toggle` | `{id, enabled: bool}` | `{}` | App：`enabled:true` → `client.approve`（pending→active）；`false` → `client.revoke`。 |
| `oauth.delete` | `{id}` | `{}` | App：`client.delete`（只能删已吊销的）。 |
| `pending.decide` | `{id, approve: bool}` | `{}` | App：`pending.allow` / `pending.deny`。 |
| `coverage.backfill` | `{type?}` | `{queued: true}` | `type` 省略=全量；App：`backfill {days}`。 |
| `audit.verify` | `{}` | `{chainOk: bool, checked?: int}` | 前端用 `data.chainOk` 提示结果。App：`audit.verify`。 |
| `security.change-password` | `{current, password, password2}` | `{}` | NAS 校验 `current`；App 映射 `password.change`（忽略 `current`，用 `password2`）。 |
| `security.totp-begin` | `{}` | `{secret, uri}` | 一次性明文；未确认前不要落库/启用。App：`totp.begin`。 |
| `security.totp-confirm` | `{code}` | `{recoveryCodes?: string[]}` | 6 位数字；NAS 返回 10 个一次性恢复码，SPA 必须展示一次。App：`totp.confirm`。 |
| `security.totp-rebind` | `{code}` | `{secret, uri}` | 先校验当前动态码，再换新密钥；App 同。 |
| `security.totp-disable` | `{code}` | `{}` | 必须带当前 6 位动态码；App：`totp.disable`。 |
| `settings.update` | `{sessionTtlHours?, maxFailedLogins?, lockoutMinutes?, requireTotp?, allowPasswordLogin?}` | `{changed: int}` | 只改 payload 里出现的字段。App：`security.set`（本机表单默认隐藏）。 |
| `token.policy` | `{id, policy}` | `{}` | 写一层工具策略；`policy` 是 JSON 文本或 `null`（不限）。App 专属（`caps.policies`）：静态 key → `key.policy`，OAuth 令牌 → 令牌层的策略。 |
| `token.window` | `{id, window}` | `{}` | 写一层时间窗；`window` 是 JSON 文本或 `null`（不限时）。App 专属（`caps.policies`）。 |
| `oauth.policy` | `{id, policy}` | `{}` | 客户端层工具策略；`null` = 不限。App 专属（`caps.policies`）。 |
| `oauth.window` | `{id, window}` | `{}` | 客户端层时间窗；`null` = 不限时。App 专属（`caps.policies`）。 |
| `local.require-auth` | `{enabled: bool}` | `{}` | 是否要求 Bearer。App 专属（`caps.localAccess`）：公网/隧道开着时拒绝 `enabled:false`。 |
| `local.loopback` | `{enabled: bool}` | `{}` | 回环免鉴权开关。App 专属。 |
| `local.confirm-clients` | `{enabled: bool}` | `{}` | 新 OAuth 客户端是否要管理员批准。App 专属。 |
| `local.save-public` | `{mode, publicBase, token}` | `{}` | `mode` ∈ `off`/`quick`/`named`；`publicBase` 空/`null` = 只走本机；`token` 空串 = **保留已存的**（界面不回显 token）。三项一起写，任一校验失败就什么都不写。App 专属。 |
| `local.clear-token` | `{}` | `{}` | 清除已存的 tunnel token。App 专属。 |
| `local.probe` | `{}` | `{ok, at, detail}` | 经**已保存**的公网基址探活；`detail` 是给人看的短句。App 专属。 |
| `local.command` | `{full: bool}` | `{command}` | Termux 里要跑的命令；`full:false` 给占位符版，`full:true` 含真 token。按**已保存**的模式拼。App 专属。 |

其它约束：

- 每个动作独立校验管理员门禁、同源、CSRF；全部写审计。
- `local.*` 与 `token.policy` / `oauth.*` 策略动作是 **App 专属**：没有对应 `caps` 的端点（NAS）不会渲染入口；
  NAS 收到这些名字可以直接返回 `ok:false`（不必实现）。
- 新增动作必须先更新本文，并在 `admin-ui/app.js` 的 `handleAct` / `submitForm` 里接线；
  `scripts/admin-ui-smoke.mjs` 会挡住页面里出现未登记的 `data-act` / `data-form`。
- 一次性的 `secret` 不能写日志、不能进审计 detail。

---

## 5. NAS adapter 细节

- `GET /admin/api/state` → 第 3 节 JSON，带 `csrf` 字段。
- `POST /admin/api/act` → 必须带 `X-CSRF-Token`（值 = state 里的 `csrf`）；缺失/错误返回
  `400`，跨站返回 `403`，未登录返回 **`401` JSON**（不是 302；页面自己会整页跳 `/login?next=/admin`）。
- 静态资源：`/admin` 返回 `admin_ui/index.html`，其余文件走 `/admin/assets/*`。
  构建出的 NAS 版 `index.html` 已经写成 `/admin/assets/{admin.css,data.js,render.js,app.js,adapter.js}`，
  所以 `/admin` 不需要尾斜杠、也不需要 `<base>`。
- `logout`：POST `/logout`，表单字段 `_csrf`（与 `hcb/web.py` 的 `_logout` 一致）。
- 前端不保留 SSR 表单路径；旧 `/admin/users` 等仍 302 到 `/admin#/users`（Track N5）。

状态各段的现成来源（NAS）：

| 段 | 来源 |
| --- | --- |
| `overview.sync` / `overview.backfill` | `hcb.stats.sync_summary()` |
| `coverage` | `hcb.stats.coverage()`（`rows`/`sources`/`days30`/`pct`/`last` 已就位） |
| `sleep` | `hcb.stats.sleep_days()`（`flags` 已是字符串码） |
| `metrics` | `hcb.stats.series("steps"/"heart_rate"/"sleep", 90)` → `values` 即 `series`；页面本地切 14/30/90 |
| `audit` | `hcb.audit.list_recent()` + `hcb.audit.verify()` |
| `users` / `sessions` | `hcb.auth.list_users()` + `hcb.auth.list_sessions()` + `_last_seen_map` |
| `tokens` | `hcb.auth.static_token_list()` |
| `oauth` / `pending` | `hcb.oauth.list_clients()` / `list_pending()` |
| `security` | TOTP 状态 + 设置项（`/admin/security` 现有读法） |

---

## 6. App adapter 细节与限制

原生侧必须暴露 `window.HcbAdmin`（`addJavascriptInterface`，方法名固定）：

```java
HcbAdmin.state()                 // -> 状态 JSON 字符串
HcbAdmin.act(name, payloadJson)  // -> {"ok":true,...} / {"ok":false,"error":"…"}
HcbAdmin.clipboard(text)         // -> {"ok":true} / {"ok":false,"error":"…"}
HcbAdmin.theme()                 // -> "dark" / "light"（跟随 App DayNight）
HcbAdmin.close()                 // 关闭管理页
HcbAdmin.ready()                 // 可选：首屏就绪
```

adapter 负责把原生**扁平**结果（`{ok:true, secret…}`）收进共享契约的 `data` 里，
所以后端不用改返回形状。

`caps.followTheme=true` 时页面不显示主题按钮，并由 `AdminAPI.theme()` 强制跟随 App。

App 的已知限制（页面按 `caps` 自动收敛，不隐藏数据、不伪造动作）：

- **users 单条**：只有本机管理员。新建 / 编辑 / 停用 / 删除全部隐藏；密码与 TOTP 在安全设置里。
- **sessions 单条**：只有当前解锁会话；不能从页面撤销，"撤销其他会话"不出现。
- **settings 只读**：解锁超时与锁定阈值来自 `AdminGate`，页面显示但不能改。
- 无 HTTP 管理面：页面只能用 bridge；`/admin` 在 App 里仍是 404。

adapter 注入的 `caps` 默认值（native state 里的显式值优先，缺的键才补）：

```js
{ users: "single", sessions: "single", settings: false, localAccess: true, policies: true }
```

WebView 宿主需要配合的两点（Track A）：

1. `shouldInterceptRequest` 要放行**整个** `/android_asset/admin/` 目录（现在只放行
   `index.html`，否则 `admin.css` / `*.js` 会被挡成空响应）；
2. 页面 meta CSP 换成第 1 节里的外链版本（构建时自动注入），不再需要 `script-src 'unsafe-inline'`。

---

## 7. 页面内的 UI 钩子（`data-act` / `data-form`）

`data-act` 是**页面钩子**，不是动作名；部分钩子会先弹确认框或多字段表单，再调统一动作：

| `data-act` | 行为 | 调用的动作 |
| --- | --- | --- |
| `user-toggle` | 停用需确认，启用直接执行 | `user.toggle` |
| `del-user` | type-to-confirm 用户名 | `user.delete` |
| `revoke-session` / `revoke-others` | 确认框 | `session.revoke` / `session.revoke-others` |
| `revoke-token` | 确认框 | `token.revoke` |
| `oauth-toggle` / `del-oauth` | 停用/删除需确认 | `oauth.toggle` / `oauth.delete` |
| `pending-allow` / `pending-deny` | 确认框 | `pending.decide` |
| `backfill-type` | 确认框 | `coverage.backfill` |
| `audit-verify` | 直接执行 | `audit.verify` |
| `totp-begin` / `totp-rebind` / `totp-disable` | 确认（重绑定要输入"重新绑定"） | `security.totp-*` |
| `local-mode` | **纯前端草稿**：只改隐藏字段与勾选样式，不落库、不重渲染 | —（保存时随表单提交） |
| `local-require-auth` / `local-loopback` / `local-confirm-clients` | 关鉴权需确认，其余直接执行 | `local.require-auth` / `local.loopback` / `local.confirm-clients` |
| `local-copy-cmd` / `local-copy-full` | 取命令进剪贴板；完整命令需确认（含 token） | `local.command` |
| `local-clear-token` | 确认框 | `local.clear-token` |
| `local-probe` | 直接执行（结果落回 state 的 `lastProbe`） | `local.probe` |
| `policy-edit` / `policy-cancel` | 纯前端：显示 / 收起抽屉里的内联 JSON 表单 | — |
| `theme` / `kbd` / `logout` / `reload` / `mmore` / `close-drawer` / `mcancel` / `mok` / `copy-secret` / `new-*` | 纯前端 | — |

`data-form`：`user-create`、`user-edit`、`token-create`、`oauth-create`、`password`、`settings`、
`totp-confirm`、`token-policy`、`oauth-policy`、`local-save`（都调上表的动作）。

策略表单（`token-policy` / `oauth-policy`）一次提交两个字段：只发**改过的那一个**（策略 → `token.policy` / `oauth.policy`，
时间窗 → `token.window` / `oauth.window`）；两个都没改就只提示「没有改动」。
`local-save` 一次提交 `{mode, publicBase, token}` → `local.save-public`。

---

## 8. 不在 SPA 里的东西

- 登录 / TOTP 登录页：NAS 走 `/login`（SSR），App 走原生门禁。`#/login`、`#/login/otp`
  一律改写为 `#/overview`。
- 旧 SSR 管理页：`/admin/legacy/*` 保留一周做回退，不由本前端渲染。
- 主题是唯一持久化状态（`localStorage["hc-health-admin.theme"]`）；搜索词与筛选只在内存里。
