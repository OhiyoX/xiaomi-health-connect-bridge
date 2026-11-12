# hc-bridge app 内置 MCP：鉴权、OAuth、app 内后台与 Cloudflare Tunnel 设计

- 日期：2026-11-02
- 状态：设计待评审
- 范围：Android app（`app/`）里的 `McpService`（现在 `127.0.0.1:8765`，无鉴权）
- 不改 NAS 服务；NAS 的 OAuth/后台见 `2026-11-02-oauth-admin-design.md`
- 参考：NAS 设计的协议与表结构；obsidian 的 OAuth/多账号做法；mcp-admin-kit 的 admin 会话/CSRF

## 1. 意图

app 现在只在回环暴露 MCP，没有鉴权，也不能公网访问。目标：

1. app 自带 OAuth 2.1 授权服务器 + 资源服务器，本地和公网客户端都能用标准 OAuth 连接。
2. 默认要求 Bearer；保留一个显式「仅回环免鉴权」开关，供旧本地客户端过渡。
3. 在 app 内做管理后台：客户端、令牌、工具权限、时间窗、公网地址、审计。
4. 用 Cloudflare Tunnel 把 `127.0.0.1:8765` 暴露到公网；`cloudflared` 跑在 Termux，不在 app 内嵌 Go 二进制。

## 2. 目标与非目标

### 目标

- 同一套 OAuth 端点：`/.well-known/oauth-authorization-server`、
  `/.well-known/oauth-protected-resource`（及 `/mcp` 变体）、`/register`、`/authorize`、
  `/token`、`/revoke`。
- 授权码 + PKCE(S256)、刷新轮换/重用吊销、撤销、动态客户端注册、手工建客户端。
- 访问令牌不透明，只存 SHA-256；不引入 JWT/JWKS。
- 单管理员：app 内设置密码（或复用设备生物识别解锁），同意页在 app 内确认。
- 工具权限/时间窗：按 OAuth 客户端、按令牌、按静态 key 三层；工具清单来自 app 的 36 个工具。
- app 内「MCP 管理」页：状态、绑定、鉴权开关、公网地址、客户端、令牌、工具权限、审计、tunnel 配置。
- Cloudflare Tunnel：命名隧道（token）与 quick tunnel 两种；app 存 token/公网 URL、显示状态、
  提供「复制 Termux 命令」；不把管理员面暴露在 tunnel 上。

### 非目标

- 设备上多用户/多租户；app 只有一个管理员。
- 在 app 内嵌 `cloudflared` 或自己实现 Cloudflare Tunnel 协议。
- OIDC、第三方登录、JWT/JWKS、token introspection。
- 改 NAS 服务的鉴权；app 与 NAS 各自负责自己的 MCP。
- 用 tunnel 暴露 app 管理页；管理只在 app UI 内。

## 3. 现状

- `McpService.kt`：`ServerSocket` 绑 `127.0.0.1:8765`；JSON-RPC 2.0；`tools/list` 返回 36 个工具；
  无 `Authorization` 校验。
- 前台服务由 `BootReceiver` / `CmdReceiver` / `MainActivity` 启动，空闲 10 分钟停。
- 没有用户表、没有客户端/令牌表、没有管理页；设置存 `SharedPreferences`。
- Termux 已在同机可用；Pi Pocket 容器用 `cloudflared` quick tunnel 的运维经验可复用。

## 4. 总体架构

```
Claude/ChatGPT ── HTTPS ──> Cloudflare edge ── tunnel ──> cloudflared (Termux)
                                                            │ http://127.0.0.1:8765
本地 pi/MCP 客户端 ──────────────────────────────────────────┤
                                                            ▼
                                              McpService (Kotlin)
                                                ├─ JSON-RPC /mcp
                                                ├─ OAuth AS/RS 路由
                                                ├─ TokenStore (SQLite/Room)
                                                └─ 工具执行（HC/云同步）
app UI
  └─ MCP 管理页：状态 / 客户端 / 令牌 / 工具权限 / 时间窗 / 审计 / Tunnel
```

Issuer 与资源：

- 本地：`http://127.0.0.1:8765`，resource `http://127.0.0.1:8765/mcp`。
- 公网：用户在 app 里填公网基址（命名隧道域名，或 quick tunnel 的 `https://…trycloudflare.com`），
  issuer/resource 随之切换。切换后旧令牌的 `resource` 不匹配 → 自动失效（客户端重新授权）。
- app 只监听 `127.0.0.1`；公网由 tunnel 转发，不直接 bind `0.0.0.0`。

## 5. 鉴权与 OAuth

- 默认 `requireAuth = true`：`/mcp` 必须有 Bearer（OAuth 访问令牌或 app 内生成的静态 key）。
- `allowLoopbackNoAuth`（默认 false）：开启后，来源必须是 `127.0.0.1` 且无 `X-Forwarded-For`，
  才允许无令牌；tunnel 流量永远不享受这个豁免。
- 静态 key：app 内生成，只显示一次，只存哈希；用于旧 pi/Termux 客户端迁移。
- OAuth：与 NAS 相同的协议与错误码；客户端注册（DCR）默认只允许 `https` 回调，
  `http://127.0.0.1` / `http://localhost` 例外（本机客户端）。
- 同意：app 内弹窗/页面显示客户端、回调主机、scope、工具权限摘要；管理员用 app 密码/生物识别确认。
- 令牌 TTL：访问 1 小时，刷新 30 天；刷新严格轮换，重用吊销 family。
- 授权码 5 分钟、一次性；`resource` 绑定当前 issuer 的 `/mcp`。

## 6. 数据模型（app SQLite/Room）

```sql
oauth_clients(id, client_id, client_secret_hash, client_name, redirect_uris_json,
              tool_policy_json, window_json, created_at, last_used_at, revoked_at)
oauth_codes(code_hash, client_id, redirect_uri, code_challenge, scopes_json,
            resource, created_at, expires_at, consumed_at)
oauth_tokens(id, token_hash, prefix, kind, client_id, family_id, scopes_json,
             tool_policy_json, window_json, resource, created_at, expires_at,
             revoked_at, replaced_by_hash, last_used_at)
static_keys(id, label, hash, tool_policy_json, window_json, created_at,
            last_used_at, revoked_at)
audit_log(id, actor, action, tool, result, detail, prev_hash, hash, created_at)
settings(key, value)   -- 管理员密码 hash、issuer、requireAuth、allowLoopbackNoAuth、tunnel 配置
sessions(sid_hash, subject, created_at, expires_at, revoked_at)  -- 同意页会话（短）
```

- 单管理员，不需要 `users` 表；同意只需管理员会话。
- 工具策略规则与 NAS 相同：`["tool", {"tool":"x","args":{...}}]`；`NULL` = 不限制，`[]` = 不允许任何工具。
- 时间窗同 NAS：`tz/days/start/end/from/until`，三层交集。

## 7. app 内管理页

新增「MCP 管理」（设置 → MCP）：

- 状态：服务是否在跑、绑定地址、鉴权开关、当前 issuer、最近请求、错误。
- 公网：公网基址、tunnel 模式（关闭/命名/quick）、tunnel token（只写不读）、
  「复制 Termux 命令」、连通检测（请求 `/.well-known/oauth-protected-resource`）。
- 客户端：列表、手工新建、工具权限、时间窗、撤销全部令牌、删除。
- 令牌/静态 key：列表、创建（显示一次）、工具权限、时间窗、撤销。
- 审计：最近 200 条，按 actor/action/result 过滤；不记录参数与健康数据。
- 安全：管理员密码修改、退出所有同意会话。

## 8. Cloudflare Tunnel

### 8.1 命名隧道（推荐，稳定域名）

Termux 里：

```bash
cloudflared tunnel login
cloudflared tunnel create hc-bridge-phone
cloudflared tunnel route dns hc-bridge-phone hc.example.com
cloudflared tunnel run --token <TOKEN> hc-bridge-phone
```

app 侧只存 tunnel token（加密）与公网基址 `https://hc.example.com`；提供复制命令与状态检测。
token 不进日志、不进备份明文。

### 8.2 quick tunnel（临时）

```bash
cloudflared tunnel --url http://127.0.0.1:8765 --no-autoupdate
```

输出随机 `https://…trycloudflare.com`；app 可粘贴该地址，或扫描日志。quick tunnel 重启即变，
旧 OAuth 令牌因 resource 不匹配失效，需重新授权。

### 8.3 进程与权限

- `cloudflared` 由 Termux 的 runit/systemd 类服务托管；app 不直接 kill/start 其他应用进程。
- app 通过 `RUN_COMMAND`（Termux:API）可选地启动/停止一个固定脚本；没有权限时只显示命令。
- 公网开启时强制 `requireAuth=true`；`allowLoopbackNoAuth` 对 tunnel 流量无效。

## 9. 安全

- 令牌、码、secret、静态 key 只存 SHA-256；`MessageDigest.isEqual` 常量时间比较。
- 管理页只在 app 内；OAuth 端点不提供管理 API。
- 同意页 CSRF/一次性 nonce；授权请求签名或服务端暂存，防篡改。
- 限流：`/register` 10/h、`/token` 60/min、`/authorize` 30/min（按来源；tunnel 下用 Cloudflare 头
  只在本地可信时采信）。
- tunnel 公网时：强制 https issuer；拒绝非 https 回调（本机回环例外）。
- 日志不出现密码、token、code、Cookie、健康数据。

## 10. 测试与验收

- JVM 单元：PKCE、授权码 TTL/一次性、刷新轮换/重用、scope/工具策略/时间窗、静态 key 哈希。
- Android instrumentation：管理页创建客户端/令牌、鉴权开关、tunnel 地址切换、审计列表。
- 手工：MCP Inspector 走 OAuth；`curl` 无令牌 401；带令牌 `tools/list`；quick tunnel 公网连接；
  命名 tunnel 重启后令牌仍有效；关鉴权仅回环可用，tunnel 仍 401。
- 未验收不宣称「app MCP 已公网可用」。

## 11. 实现顺序

1. OAuth 核心 + 表 + 鉴权（不改工具行为）。
2. app 管理页（客户端/令牌/工具权限/时间窗）。
3. Cloudflare Tunnel 配置与状态页。
4. 迁移旧本地客户端到静态 key；默认开启鉴权。
