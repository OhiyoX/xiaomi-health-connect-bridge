# hc-bridge NAS：OAuth + Web 管理后台 + 多账号设计

- 日期：2026-11-02
- 状态：设计评审中
- 目标仓库：`ai-pi/hc-bridge`，只改 `deploy/nas/`（NAS 上的 Python 服务）与部署配置
- 不改 Android app（`app/`）；手机仍用静态 Bearer
- 参考：`example-user/obsidian-mcp-server` 的 OAuth / 多账号 / 管理页；`example-user/mcp-admin-kit` 的
  admin 会话、CSRF、principal 语义
- 姊妹设计：`pan-scheduler` 的 `docs/superpowers/specs/2026-11-02-mcp-oauth-design.md`
  （同一套协议，两套独立实现；本服务不依赖它）

## 1. 意图

NAS 上的 `hc-health-mcp` 现在只有一个 Python 单文件、静态 Bearer、没有任何网页。业主 2026-11-02 要：

1. **OAuth**：MCP 客户端能像连 obsidian 一样自动发现、注册、登录、同意、换令牌。
2. **Web 管理后台**：在浏览器里看同步状态、数据覆盖、令牌、OAuth 应用、审计；不用 SSH 改 `.env`。
3. **多账号**：管理员建用户，每个用户自己的密码与 scope 上限；登录、同意、令牌都绑定到人。
4. 自己负责自己的 MCP：授权服务器与资源服务器都在本服务，不共用 obsidian 的。

服务仍然只读业务数据（`ingest` 是手机推送入口，见 §8），OAuth 不改变数据面。

## 2. 目标与非目标

### 目标

1. Python 标准库实现 OAuth 2.1 AS + RS：授权码 + PKCE(S256)、刷新令牌轮换、撤销、动态客户端注册、
   RFC 9728 资源元数据、RFC 8414 授权服务器元数据。
2. 访问令牌不透明，SQLite 只存 SHA-256；不引入 JWT/JWKS、不新增 Python 依赖。
3. 静态 Bearer 继续可用；手机 `HcPusher`、Termux、现有 MCP 客户端不受影响。
4. SSR 管理后台（无框架、无构建、无 JS 也能用）：概览、用户、OAuth 应用、静态令牌、审计、设置。
5. 多账号：`users` 表；管理员建号/停用/重置密码/改 scope 上限；用户可改自己的密码。
   会话带身份与 revision，停用/改密/删号立即失效。
6. 静态令牌从“只读 `.env`”升级为“首次启动导入数据库，之后在后台管理”，仍保留环境变量作为引导。
7. 每个 MCP 工具调用落审计；日志不出现令牌、密码、Cookie、健康数据。
8. **数据隔离可选**：每个用户选「共享」（用 admin 的 `health.db` 与凭据，像 obsidian admin 看全部）
   或「独立」（自己的分区 + 自己的小米账号凭据）。admin 永远能看全部分区；现有 `health.db` 归 admin。
9. **工具权限精细到每个主体**：用户、OAuth 客户端、OAuth 令牌、静态 key 各有一层工具白名单；
   支持参数级规则，多层取交集。
10. **分时授权**：同样四层各自可设时间窗（时区、星期、时段、绝对起止），多层取交集；
    窗外调用一律拒绝。
11. **管理员 TOTP**：密码之后再验 6 位 TOTP；首次启用显示 10 个一次性恢复码。
12. **会话管理**：服务端会话表；管理员看全部、用户看自己，可强制下线。
13. **审计链**：`access_log` 每行带 `prev_hash` / `hash`，可校验篡改；按天数/条数保留。
14. **管理员找回**：CLI 重置密码或 TOTP，不依赖网页。

### 非目标

- OIDC、第三方登录、多管理员。
- 用户在网页里自助导入小米凭据；凭据由管理员在后台代管。
- 改 Android app 的 UI 或同步逻辑；手机端 OAuth。
- 把 `mcp.health.example.com/mcp` 也升级成 OAuth（它继续只认静态 Bearer）。
- 多副本/高可用；仍是单进程，每个分区一个 SQLite 文件。
- 给每个工具单独发 scope。只有 `health.read` / `health.write`。

## 3. 现状（一手）

- `deploy/nas/server.py`：约 1355 行，`ThreadingHTTPServer` + `BaseHTTPRequestHandler`；
  路由只有 `GET /health`、`POST /ingest`、`POST /mcp`。
- 鉴权：`HEALTH_MCP_TOKENS`（`label:token,...`）与旧 `HEALTH_MCP_TOKEN`；常量时间比较；
  无令牌配置时开放。`/ingest` 与 `/mcp` 同一份 token。
- 数据：`health.db` 单文件，表 `heart_rate/spo2/steps/sleep/calories/workout/stress/weight/raw/sync_state`；
  `sync_state` 存 `last_sync`、`backfill_state`、`last_ingest`。
- 后台线程 `fetch_loop` 每 `FETCH_INTERVAL_SEC`（默认 900）拉小米云（改造后：逐分区、逐用户拉）。
- 部署：NAS Docker 容器 `hc-health-mcp`，`./app:/app:ro` 绑定挂载；改 `app/server.py` 后
  `docker restart` 即生效。公网入口有两个：`https://mcp.health.example.com/mcp`（独立证书，手机默认）
  与 `https://health.example.com/mcp`（通配符证书，路径式）；`/ingest` 也已公网放行。
- 仓库没有 Python 测试。

## 4. 方案比较（2026-11-02 已选 A）

### A. 本服务内嵌 AS + SSR 后台（选中）

同一个 Python 进程同时提供 MCP、OAuth、管理页；SQLite 存用户/客户端/令牌/审计。访问令牌不透明，
按哈希查库校验，因此不需要 JWT、JWKS、额外容器或依赖。多账号与管理页在同一套会话模型里。

### B. 复用 obsidian 作为授权服务器

本服务只做 RS，验 obsidian 签发的 JWT。少写一套 AS，但 hc-bridge 要引密码库做 RS256 验签，
依赖 obsidian 在线；而且与“每个项目自己负责自己的 MCP”冲突。不选。

### C. 独立认证容器

新增一个共享 AS 容器。运维面变大，收益与 A 相同；单人 NAS 环境不值得。不选。

## 5. 总体架构

```
浏览器 ── /admin, /login, /account ─┐
MCP 客户端 ── /mcp（OAuth 或静态）──┤  ThreadingHTTPServer（server.py）
手机 HcPusher ── /ingest（静态）────┤
                                    ├─ hcb/http.py     路由、静态页、CSRF、限流
                                    ├─ hcb/auth.py     用户、密码、会话、静态令牌
                                    ├─ hcb/oauth.py    AS：注册/授权/令牌/撤销/元数据
                                    ├─ hcb/admin.py    后台页面与动作
                                    └─ data/
                                         health.db              admin / legacy 分区
                                         users/<id>.db          每个用户一个分区
                                         users/<id>.creds.json  每个用户的小米凭据（600）
```

代码拆分（仍在 `deploy/nas/`）：`server.py` 保留入口与业务工具；新增同目录 `hcb/` 包
（`__init__.py`、`http.py`、`auth.py`、`oauth.py`、`admin.py`）。部署时把 `server.py` 与 `hcb/`
一起同步到 NAS 的 `app/`；容器仍只挂 `./app`。

**Host 结论（一句话）**：OAuth 与后台只挂在 `https://health.example.com`；`mcp.health.example.com`
只保留静态 Bearer。理由：`health.example.com` 是单层子域，现有通配符证书已覆盖；不为 OAuth 新开域名。
所有 `.well-known`、`/authorize`、`/token`、`/register`、`/revoke`、`/login`、`/admin`、`/account`
都改 `health.example.com` 的 vhost；`mcp.health.example.com` 的 vhost 完全不动。

Issuer 与资源：

- 生产 `PUBLIC_BASE_URL=https://health.example.com`
- issuer：`https://health.example.com`
- 资源：`https://health.example.com/mcp`
- 管理后台：`https://health.example.com/admin`
- `https://mcp.health.example.com/mcp` 继续只用静态 Bearer；OAuth 只在 `health.example.com`。

## 6. 端点契约

### 6.1 MCP 鉴权与发现

MCP 每次请求（不只刷新时）都校验：令牌存在、未过期、未撤销、`resource` 匹配、用户存在且
`enabled=1`、`user_revision == users.revision`、scope 仍在该用户当前上限内。任一不满足 → 401，
并吊销该 family。这样停用、删号、改密、收窄 scope 都是立即生效，不用等 1 小时过期。

`POST /mcp` 无凭据或凭据无效：

```
HTTP/1.1 401 Unauthorized
WWW-Authenticate: Bearer resource_metadata="https://health.example.com/.well-known/oauth-protected-resource/mcp"
```

`GET /.well-known/oauth-protected-resource` 与 `/.well-known/oauth-protected-resource/mcp`：

```json
{
  "resource": "https://health.example.com/mcp",
  "authorization_servers": ["https://health.example.com"],
  "scopes_supported": ["health.read", "health.write"],
  "resource_name": "hc-bridge Health MCP"
}
```

`GET /.well-known/oauth-authorization-server`：

```json
{
  "issuer": "https://health.example.com",
  "authorization_endpoint": "https://health.example.com/authorize",
  "token_endpoint": "https://health.example.com/token",
  "registration_endpoint": "https://health.example.com/register",
  "revocation_endpoint": "https://health.example.com/revoke",
  "response_types_supported": ["code"],
  "grant_types_supported": ["authorization_code", "refresh_token"],
  "code_challenge_methods_supported": ["S256"],
  "token_endpoint_auth_methods_supported": ["none", "client_secret_post"],
  "scopes_supported": ["health.read", "health.write"]
}
```

### 6.2 注册 / 授权 / 令牌 / 撤销

协议与 `pan-scheduler` 的姊妹设计一致（同一套字段与错误码），实现各自独立：

- `POST /register`：RFC 7591；`redirect_uris` 精确匹配；只允许 https，localhost 例外；
  公开客户端 `none` 或机密客户端 `client_secret_post`；客户端上限 200。
- `GET /authorize`：`response_type=code`、`client_id`、`redirect_uri`、`code_challenge`、
  `code_challenge_method=S256`、`state`、`scope`、`resource`。redirect 无效 → 400 HTML，不重定向。
  scope 缺省 = 当前用户上限；请求的 scope 必须是上限的子集，超出 → `error=invalid_scope`。
- 未登录 → `302 /login?next=<原路径+查询串>`；登录后回同意页。
- 同意页（SSR）：显示“以 <用户名> 身份”、客户端名、redirect 主机、资源、scope 复选框
  （只能选用户上限内的；默认勾选客户端请求的 scope，可取消）、允许/拒绝；表单带 CSRF。
- `POST /authorize`：拒绝 → `error=access_denied&state`；允许且至少一个 scope → 授权码
  （32 字节，只存哈希，5 分钟，一次性），302 回 redirect。
- `POST /token`：授权码 + PKCE 校验；`resource` 可省（省则用授权码里存的值，给了必须相等）；
  发访问令牌（1 小时）与刷新令牌（30 天）；刷新严格轮换，旧刷新令牌重用 → 吊销整个 family。
- `POST /revoke`：始终 200；撤销刷新令牌废 family。

授权码与令牌都绑定 `user_id` / `username` / `user_revision`；兑换与刷新时重读用户，
不存在、停用、revision 不同、上限不再覆盖 → `invalid_grant`。

### 6.3 页面路由

公开：`/health`、`/login`、`/logout`、`/authorize`、`/token`、`/register`、`/revoke`、
`/.well-known/*`、`/admin.css`、`/assets/*`。

管理员：`/admin`、`/admin/users`、`/admin/oauth`、`/admin/tokens`、`/admin/audit`、
`/admin/security`、`/admin/sessions`、`/admin/settings`。

用户：`/account`、`/account/password`。

门禁：管理员会话可进全部；普通用户只可进 `/account*`、`/logout`、`/authorize` 与上面的公开路径；
其他路径 302 `/account`。未登录访问受保护页面 → 302 `/login?next=…`。

## 7. 数据模型（`db()` 里 `CREATE TABLE IF NOT EXISTS`）

```sql
CREATE TABLE users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  username TEXT NOT NULL UNIQUE COLLATE NOCASE,
  display_name TEXT NOT NULL,
  password_hash TEXT NOT NULL,
  scopes_json TEXT NOT NULL DEFAULT '["health.read"]',
  tool_policy_json TEXT,                      -- NULL = 不额外限制；规则见 §8.2
  window_json TEXT,                           -- NULL = 不限时；规则见 §8.3
  data_mode TEXT NOT NULL DEFAULT 'shared',   -- 'shared' = admin 分区/凭据；'own' = 自己的分区/凭据
  enabled INTEGER NOT NULL DEFAULT 1,
  revision INTEGER NOT NULL DEFAULT 1,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE sessions (
  sid_hash TEXT PRIMARY KEY,          -- SHA-256(sid)，Cookie 里才是原值
  subject TEXT NOT NULL,              -- 'admin' 或 'user:<id>'
  revision INTEGER NOT NULL,
  user_agent TEXT,                    -- 截断到 120 字符
  created_at INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  revoked_at INTEGER
);
CREATE INDEX ix_sessions_subject ON sessions(subject, revoked_at);

CREATE TABLE agent_tokens (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  label TEXT NOT NULL UNIQUE,
  hash TEXT NOT NULL UNIQUE,
  tool_policy_json TEXT,                     -- NULL = 不额外限制；静态 key 自己的工具权限
  window_json TEXT,                          -- NULL = 不限时；静态 key 自己的时间窗
  owner_user_id INTEGER NOT NULL DEFAULT 0,  -- 0 = admin/legacy；手机推送写入该分区
  origin TEXT NOT NULL DEFAULT 'ui',  -- 'env' | 'ui'
  created_at INTEGER NOT NULL,
  last_used_at INTEGER,
  revoked_at INTEGER
);

CREATE TABLE oauth_clients (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  client_id TEXT NOT NULL UNIQUE,
  client_secret_hash TEXT,            -- NULL = 公开客户端
  client_name TEXT NOT NULL,
  redirect_uris_json TEXT NOT NULL,
  token_endpoint_auth_method TEXT NOT NULL DEFAULT 'none',
  tool_policy_json TEXT,                     -- NULL = 不额外限制；客户端上限
  window_json TEXT,                          -- NULL = 不限时；客户端上限
  created_at INTEGER NOT NULL,
  last_used_at INTEGER,
  revoked_at INTEGER
);

CREATE TABLE oauth_codes (
  code_hash TEXT PRIMARY KEY,
  client_id TEXT NOT NULL,
  redirect_uri TEXT NOT NULL,
  code_challenge TEXT NOT NULL,
  scopes_json TEXT NOT NULL,
  resource TEXT NOT NULL,
  session_sid TEXT NOT NULL,
  user_id INTEGER NOT NULL,           -- 0 = admin
  username TEXT NOT NULL,
  user_revision INTEGER NOT NULL,     -- 与 users.revision 比对
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  consumed_at INTEGER
);

CREATE TABLE oauth_tokens (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  token_hash TEXT NOT NULL UNIQUE,
  prefix TEXT NOT NULL,               -- 前 12 字符，只用于页面识别
  kind TEXT NOT NULL CHECK (kind IN ('access','refresh')),
  client_id TEXT NOT NULL,
  family_id TEXT NOT NULL,
  scopes_json TEXT NOT NULL,
  tool_policy_json TEXT,              -- 令牌级策略；NULL = 不额外限制（仍受 scope/用户/客户端约束）
  window_json TEXT,                   -- 令牌级时间窗；NULL = 不限时
  user_id INTEGER NOT NULL,           -- 0 = admin
  username TEXT NOT NULL,
  user_revision INTEGER NOT NULL,     -- 改密/停用/改 scope 后令牌立即失效
  resource TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  revoked_at INTEGER,
  replaced_by_hash TEXT,
  last_used_at INTEGER
);

CREATE TABLE access_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  actor TEXT NOT NULL,                -- 'admin' / 用户名 / 令牌标签
  kind TEXT NOT NULL,                 -- 'static' | 'oauth'
  action TEXT NOT NULL,               -- 'mcp.call' / 'admin.sync' / 'oauth.consent' …
  tool TEXT,
  result TEXT,
  detail TEXT,
  prev_hash TEXT,
  hash TEXT,
  created_at INTEGER NOT NULL
);
CREATE INDEX ix_access_hash ON access_log(hash);

CREATE TABLE service_secrets (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL                 -- 会话/CSRF 签名密钥；首次启动生成
);
```

`sync_state` 继续存业务状态；新增 `fetch_interval_override`、`tokens_imported`、`admin_password_hash`、
`admin_totp_secret`（base32，加密后存）、`admin_totp_enabled`、`admin_recovery_codes`（哈希数组）、
`audit_chain_head`。

### 7.1 数据分区（可选隔离）

业务表结构不变，分区只是一组 SQLite 文件：

- `data/health.db`：admin / legacy / 所有 `data_mode='shared'` 的用户共用。现有数据留在这里。
- `data/users/<user_id>.db`：`data_mode='own'` 的用户自己的分区。
- `data/users/<user_id>.creds.json`（600）：own 用户自己的小米凭据；shared 用户用 `data/creds.json`。
- `db(owner)`：shared 用户与 admin → owner 0；own 用户 → 自己的 id。MCP 只打开对应分区，不跨分区。
- **own 的前提**：用户必须有自己的小米账号凭据。没有凭据时其分区为空，看不到任何人的数据。
- **shared 的语义**：和管理员看同一份数据、用同一套凭据；这是“不隔离”，不是泄露——
  由管理员明确选择。
- 管理员后台可以逐个分区看概览/触发同步；自己的 MCP 只读 owner 0。
- 用户从 own 改 shared：分区文件保留但不再可查；改回 own 时可继续用。删除 own 用户：
  先把 `.db` 与凭据移到 `data/archive/<user_id>-<时间>/`，再删行。shared 用户删除只删行。
- 备份/迁移：`data/` 整目录；新增用户不需要改表结构。

迁移策略：沿用现有 `CREATE TABLE IF NOT EXISTS`；新增列用现有“PRAGMA table_info → ALTER TABLE”模式。
旧代码忽略新表；回滚不需要回退数据。

## 8. Scope、工具与多账号

### 8.1 scope

- `health.read`：只读工具。
- `health.write`：`sync_now`、`backfill`、`sleep_audit`（`fix=true` 会写，按写算）。
- 两个 scope 都授予：scope 层不额外限制（工具仍受 §8.2 的 policy 约束）。
- 只给一个：只开放该 scope 对应的工具类；`tools/list` 按 scope 过滤，越权调用返回 JSON-RPC 错误
  （`code: -32003`, `message: "insufficient scope"`）。
- **`ingest` 永不进入 OAuth 白名单**：它是手机/机器的写入入口，只接受静态 Bearer。
  这样普通用户即使有 `health.write` 也不能伪造健康数据。
- 新增工具默认归写；只读集合显式列在 `hcb/oauth.py`，测试断言 `tool_list()` 每个工具都被分类。

初始只读集合：`health_status`、`query_heart_rate`、`query_spo2`、`query_metric_series`、
`query_sleep`、`get_daily_summary`、`query_workouts`、`query_weight`、`query_stress`、
`query_raw`、`query_body_composition`、`sources`、`get_data_coverage`、`query_sleep_stages`、
`health_export_csv`。

### 8.2 每个主体的工具权限（tool policy）

四层上限，取交集；任何一层拒绝就拒绝：

1. 用户：`users.tool_policy_json`（NULL = 在 scope 内不额外限制）
2. OAuth 客户端：`oauth_clients.tool_policy_json`（NULL = 不额外限制）
3. OAuth 令牌：`oauth_tokens.tool_policy_json`（签发时按授予 scope 生成，后台可再收窄）
4. 静态 key：`agent_tokens.tool_policy_json`（NULL = 该 key 不额外限制）

规则格式：

- `["query_heart_rate","query_sleep"]`：工具白名单。
- `[{"tool":"query_metric_series","args":{"metric":["steps","distance_km"]}}]`：参数级；
  参数值写数组表示“任一命中”。
- `NULL`（SQL NULL / Python `None`）：该层不额外限制。
- `[]`：该层不允许任何工具（“全不选”）；与 `NULL` 不同，不是“不限制”。

评估顺序：

1. scope 分类：`health.read` / `health.write` 决定工具能不能被授予。
2. 每一层 policy 各自判定；全部通过才继续。
3. 分时授权（§8.3）：各层时间窗都通过。
4. 数据分区与账号所有权（§8.5）。
5. `ingest` 仍然只给静态 key；任何 OAuth 层出现 `ingest` 都忽略并拒绝。

`tools/list` 只返回各层都允许的工具（按工具名判定）；参数不匹配时调用返回
`-32003 insufficient scope`。显式 policy 里的新工具默认不可用，管理员加进去才可用。
用户/客户端 policy 改完立即生效，每次调用重读；令牌级 policy 可在后台单独收窄。

### 8.3 分时授权（时间窗）

四层各自可选：`users.window_json`、`oauth_clients.window_json`、`oauth_tokens.window_json`、
`agent_tokens.window_json`（静态 key）。`NULL` = 该层不限时；多层取交集，任何一层不在窗内就拒绝。

```json
{
  "tz": "Asia/Shanghai",
  "days": [1, 2, 3, 4, 5],
  "start": "09:00",
  "end": "22:00",
  "from": "2026-11-01T00:00:00+08:00",
  "until": "2026-12-01T00:00:00+08:00"
}
```

- `days`：1=周一 … 7=周日；省略 = 每天。
- `start`/`end`：`HH:MM`；支持跨午夜（`22:00`–`06:00`）。
- `from`/`until`：绝对起止，可省略；过了 `until` 立即失效。
- 评估在**每次 MCP 调用**时做；`admin` 不受时间窗限制。
- 静态 key 若 owner 是某个用户，还要同时满足该用户的 `window_json`（多层交集）。
- OAuth 的授权/换令牌不因窗外失败；调用工具时拒绝（`-32003`，message 写原因），审计记录原因。
- 窗外时 `tools/list` 仍可返回（无副作用），调用全部拒绝；窗一开同一令牌立刻可用。
- 页面上的拒绝原因写清楚（如“不在授权时段 09:00–22:00 Asia/Shanghai”）。

### 8.4 身份

- 管理员：`admin`，密码来自 `HCB_ADMIN_PASSWORD` 首次启动 → `scrypt` 哈希存
  `sync_state.admin_password_hash`；之后改环境变量不重置（后台可改密）。
- 首次启动没有 `HCB_ADMIN_PASSWORD`：**服务照常启动，MCP/`/ingest` 不受影响**；`/admin`、`/login`
  返回 503 页面，说明要设置该变量并重启。首次部署可先在宿主机用
  `hcb_admin.py bootstrap --db <data/health.db>` 建管理员（避免还没有容器时的循环），
  再跑 `install.sh`；否则 `/mcp` 401 冒烟会失败并回滚。
- 忘记密码或丢 TOTP：用 `hcb_admin.py reset-password` / `reset-totp --yes`
  （密码只从 stdin/TTY 读；reset-password 撤管理员会话/OAuth/授权码/preauth，
  可选 `--revoke-static`；reset-totp 同时撤管理员会话/OAuth/授权码/preauth）；
  不提供网页自助重置，避免暴露攻击面。
- 普通用户：`users` 表；用户名 3–32，`[a-z0-9._-]`，大小写不敏感唯一，保留 `admin`。
- 密码：`hashlib.scrypt(n=16384, r=8, p=1)`，16 字节随机盐；格式
  `scrypt$16384$8$1$<salt_b64>$<hash_b64>`；比较用 `hmac.compare_digest`。
- scope 上限：`health.read` 默认；管理员可加 `health.write`。管理员视为两者都有。

### 8.5 用户与数据的绑定

- `principal` 带上 `owner`：admin 与 shared 用户 = 0；own 用户 = `users.id`。
- `call_tool(..., owner)` 与 `db(owner)` 只打开该分区；查询天然只能看到本分区行。
- 静态令牌按 `agent_tokens.owner_user_id` 决定分区；`/ingest` 与 MCP `ingest` 都写进该令牌的
  owner 分区：手机推送用 owner 0 的令牌写共享分区，own 用户自己的 key 写自己的分区，不会串到其他分区。
- OAuth 令牌：每次请求校验用户仍启用、revision 一致、scope 覆盖，再按 `data_mode` 解析 owner。
- 管理员改用户的凭据、触发同步/回补，只影响该用户的分区（shared 用户影响 owner 0，需明确提示）；
  后台概览按分区逐个统计。
- 用户切换 `data_mode` 后，下一次 MCP 调用立即按新模式解析 owner。

### 8.6 会话

- `hcb_session` Cookie：`base64url(payload).hmac_sha256(secret, payload)`，
  payload `{sub, rev, sid, iat, exp}`；`HttpOnly; SameSite=Lax; Path=/`，https 时 `Secure`。
- `sub` = `admin` 或 `user:<id>`；普通用户每请求校验存在、`enabled=1`、`revision == rev`。
- `sessions` 表用于“当前会话列表/强制下线”和 `sid` 绑定；签名 Cookie 本身不存明文。
- CSRF：`hmac_sha256(secret, "csrf:" + sid)`；所有 POST 表单校验。
- 登录两步：密码正确 → 5 分钟有效的 `hcb_preauth` 签名 Cookie（只含 subject/exp/nonce）→
  `/login/otp` 校验 TOTP（±1 窗口）或一次性恢复码 → 建正式会话。preauth 一次性，重放拒绝。
- 管理员启用 TOTP 后，密码正确但 OTP 错误拿不到会话；恢复码用一次即作废。
- 登录/OTP 失败限流：每 IP 5 次/5 分钟；成功清零。
- 会话 Cookie 里 `sid` 原值，`sessions` 表存 `SHA-256(sid)`；每个请求校验会话未吊销/未过期。

## 9. 管理后台

### 9.1 概览 `/admin`

导航含：概览、用户、OAuth、静态令牌、审计、安全、会话、设置。

- 服务：版本、启动时间、`last_sync`、`last_ingest`、backfill 状态、下次抓取时间。
- 分区选择器：admin（0）或某个用户；选中后显示该分区各表条数、首末时间（复用 `health_status`，
  结果缓存 30 秒）。不提供跨分区合并查询。
- 账号：该分区凭据文件是否存在、mtime；不显示 cookie。
- 静态令牌数、OAuth 客户端数、活跃用户数。
- 动作：立即同步、回补 N 天（后台跑，页面显示进度）、改抓取间隔（300–86400 秒）。

### 9.2 用户 `/admin/users`

- 列表：用户名、显示名、scope 上限、状态、创建时间、有效令牌数。
- 新建：用户名、显示名、初始密码（≥12）、scope 复选框（默认只 `health.read`）、
  数据模式（共享/独立）、工具权限、时间窗。
- 工具权限编辑器：按只读/写分组的复选框 + “全选/全不选/不限制”；JSON 文本框写参数级规则。
- 时间窗编辑器：时区、星期复选、起止时间、可选绝对起止；留空 = 不限时。
- 操作：启用/停用、重置密码、改 scope、改工具权限、改时间窗、切换数据模式、删除
  （吊销其全部令牌、会话立即失效；own 数据归档不删除）。
- 每行展开「数据」：数据模式（共享 / 独立）、分区条数/首末时间、凭据状态（已配置/未配置）、
  「切换为共享/独立」、导入或替换小米凭据（粘贴 JSON 或上传文件，不回显）、立即同步、回补 N 天。
  切到独立前先要求配置凭据；切到共享时提示“将看到管理员的数据”。

### 9.3 OAuth `/admin/oauth`

- 客户端列表：名称、client_id、类型、redirect URI、创建、最近使用、有效令牌、状态。
- 操作：查看令牌（只显示前缀/过期）、撤销全部令牌、删除应用（输入名称确认）。
- 每个客户端可设工具权限与时间窗上限；令牌详情可“调整该令牌权限/时间窗”（只收窄）。
- 新建客户端（给不做动态注册的客户端用）：名称、redirect URI、公开/机密；机密客户端的
  `client_secret` 只在创建成功页显示一次，库里只存哈希。

### 9.4 静态令牌 `/admin/tokens`

- 列表：标签、来源（env/ui）、owner、创建、最近使用、状态。
- 新建：标签、owner 分区（admin 或某个 own 用户）、工具权限（不限制/勾选/JSON 规则）、
  时间窗；显示一次完整令牌；只存哈希；可撤销。
- 首次启动把 `HEALTH_MCP_TOKENS` / `HEALTH_MCP_TOKEN` 导入（按 label 幂等，`origin=env`），
  写入 `tokens_imported`。之后数据库为准；环境变量保留作回滚与引导，不再覆盖数据库。
- 若数据库没有任何有效令牌且环境变量也没有 → 保持现有“开放”行为，但概览页红字警告。

### 9.5 审计 `/admin/audit`

- `access_log` 最近 200 条：时间、actor、kind、action、tool、result。
- 过滤：actor / action / result。
- 记录：MCP 调用（成功/拒绝）、登录成功/失败（不含密码）、同意/拒绝、令牌签发/刷新/撤销、
  用户变更、后台动作、TOTP/会话操作。**不记参数与健康数据**。
- 「校验链」：从链头或锚点重算，报告第一处不一致的 id。
- 保留：`AUDIT_RETENTION_DAYS`（默认 90）；清理时保留 `audit.prune` 锚点行（记录被删范围
  最后一条 hash），链仍可从锚点继续校验。

### 9.6 安全 `/admin/security`

- TOTP 状态（未启用/已启用）、启用向导（otpauth URI/secret、10 个恢复码只显示一次）、
  关闭（当前密码 + TOTP）、重新生成恢复码。
- 修改管理员密码（当前密码 + 新密码 ≥12）。
- TOTP secret 存在 `sync_state`，DB 文件 600；恢复码只存哈希。

### 9.7 会话 `/admin/sessions`

- 全部会话：主体、创建、最近活动、过期、状态、UA（截断）。
- 操作：吊销单个、吊销某用户全部、吊销除当前外全部。
- 管理员会话 TTL 4 小时，用户会话 TTL 12 小时；每次请求校验未吊销/未过期。

### 9.8 设置 `/admin/settings`

- 抓取间隔（存 `sync_state`，`fetch_loop` 每轮读取）。
- 修改管理员密码（当前密码 + 新密码）。
- 只读显示：`PUBLIC_BASE_URL`、数据库路径、版本。

## 10. 登录与同意流程

1. 未登录访问 `/authorize` → `/login?next=…`（`next` 只接受以 `/authorize?` 或 `/account` 开头的
   同源路径；拒绝 `//`、反斜杠、绝对 URL）。
2. `/login` 同时接受 `admin` 与普通用户；用户名不存在与密码错误同一句提示。
3. 登录成功 → 回 `next`（同意页）或 `/admin`（管理员）/ `/account`（普通用户）。
4. 同意页显示当前用户与 scope 上限；普通用户选不到超出上限的 scope。
5. 同意 → 授权码 → 客户端换令牌；审计记 `oauth.consent.allow` / `oauth.token.issue`。

## 11. 安全约束

- 令牌、授权码、client secret、会话签名密钥只以哈希/HMAC 形式落库；日志与页面不回显。
- Cookie：`HttpOnly`、`SameSite=Lax`、https 时 `Secure`；`/admin` 响应加
  `Cache-Control: no-store`、`X-Frame-Options: DENY`、`Content-Security-Policy: default-src 'none';
  style-src 'self' 'unsafe-inline'; form-action 'self'`。
- 所有 POST 走 CSRF；Origin 与 Host 不同则拒绝（同源检查）。
- 请求体上限：表单 64 KB、OAuth JSON 16 KB；`/mcp` 维持现有上限（加 1 MB 保护）。
- 限流：`/login` 5/5 分钟/IP；`/register` 10/小时/IP；`/token` 60/分钟/IP；
  `/authorize` 30/分钟/IP；内存桶 + 锁，重启清零。
- 客户端 IP：只有 peer 是回环或 RFC1918（容器网关/nginx）且 `HCB_TRUST_PROXY=1`（容器内默认 1）时，
  才信 nginx 设置的 `X-Real-IP`；其他情况用 socket peer。**不解析客户端自带的 `X-Forwarded-For`**，
  防止伪造。nginx 必须写 `proxy_set_header X-Real-IP $remote_addr`。
- redirect URI 精确匹配；issuer 与 resource 只来自 `PUBLIC_BASE_URL`，不信任请求 Host 拼装。
- 未配置 `PUBLIC_BASE_URL` 时按 `X-Forwarded-Proto` + Host 推导，但生产启动打 warning；
  非 https 且非 localhost 拒绝启动 OAuth 元数据。
- 静态令牌比较、密码比较都用 `hmac.compare_digest`；令牌查库按 SHA-256，不存明文。
- `/ingest` 公网可达，但只接受静态令牌；OAuth 令牌即使有 `health.write` 也返回 403。
  nginx 侧用 `limit_req` 限流（如 60r/m、burst 20）；**不要用 LAN/私网 ACL**：
  edge/edge-host + FRP 拓扑下 nginx 看到的来源不是手机 LAN 地址，ACL 不成立。

## 12. 测试

Python `unittest`（新增 `deploy/nas/tests/`，`python -m unittest discover -s deploy/nas/tests`）：

- 哈希/校验：scrypt 密码、会话签名、CSRF、令牌哈希。
- 路由：`/health`、`/login`、`/admin` 门禁、普通用户被挡、静态令牌与 OAuth 令牌。
- 多账号：A 看不到 B 的令牌；停用/改密/删号后会话与令牌立即失效；scope 上限收窄。
- 数据隔离可选：own 用户 A 与 own 用户 B 各一个 SQLite，互不可见；shared 用户看到 owner 0 的
  全部数据；切换 data_mode 后下一次调用立即生效；管理员后台切换分区后看到对应数据。
- 写入归属：owner=1 的静态令牌推送 `/ingest`，只落 1 号分区；owner=0 的 MCP `ingest` 只落 admin。
- 删除 own 用户后数据在 `data/archive/` 可找回；shared 用户删除不影响 owner 0。
- OAuth：元数据；DCR 校验；完整授权码 + PKCE 流程（用 `urllib` + CookieJar）；错误 verifier；
  码过期/重用；刷新轮换与重用吊销 family；撤销。
- Scope：只读令牌 `tools/list` 不含写工具；调 `sync_now` 返回 -32003；`ingest` 对 OAuth 永远 403。
- 工具权限：四层交集（用户/客户端/令牌/静态 key）各自拒绝时都拒绝；参数级规则（如
  `query_metric_series` 只允许 `steps`）生效；`tools/list` 不出现被任何一层禁止的工具；
  改用户政策后下一次调用立即变化。
- TOTP：RFC 6238 测试向量、±1 窗口、错误码、恢复码一次性、preauth 重放拒绝。
- 审计链：正常追加可校验；改一行/删一行都能被「校验链」发现；清理后锚点仍可校验。
- 会话：吊销后同一 Cookie 立即 302/401；改密只保留当前会话；过期会话被拒。
- 分时授权：星期/时段/跨午夜/绝对起止/时区各有用例；四层交集；静态 key 叠加 owner 用户的时间窗；
  窗外调用拒绝且审计有原因；窗一开同一令牌立刻可用。
- 审计：拒绝也落一行；不含参数与健康数据。
- 静态令牌导入：env → DB 幂等；撤销后 401。

手工验收（部署后）：

- `curl` 两份 `.well-known`，issuer/resource 正确。
- MCP Inspector 或 Claude/ChatGPT 连接器走完 OAuth，`tools/list` 成功，`query_heart_rate` 有数据。
- 手机 `/ingest` 与 MCP `ingest` 仍用静态令牌成功。
- 后台：建用户、授权、撤销、停用，客户端立即 401。

## 13. 部署与回滚

- 数据目录：`data/users/` 新增；`install.sh` 不移动现有 `health.db` 与 `creds.json`。
- 代码：把 `deploy/nas/server.py`、`deploy/nas/hcb_admin.py` 与 `deploy/nas/hcb/`
  同步到 NAS 的 `<deploy-dir>/app/`；
  `install.sh` 先备份并校验 `app/` + `MANIFEST`，再同步（删除源目录已不存在的
  `.py` 与旧 `__pycache__`），然后 `docker compose up -d --force-recreate`；
  失败自动回滚，`--rollback` 可手工恢复。
- 环境变量：新增 `PUBLIC_BASE_URL=https://health.example.com`、
  `HCB_ADMIN_PASSWORD`（仅首次引导）、`HCB_TRUST_PROXY`、
  `HCB_TRUSTED_PROXIES`（真实反代/Docker 网关的 `/32` 或最小 CIDR，不能写
  `172.16.0.0/12`）。`.env` 内容不改、权限 600。
- nginx（`health.example.com` vhost，edge edge-host）新增：
  `/.well-known/`、`/login`、`/login/otp`、`/logout`、`/authorize`、`/token`、
  `/register`、`/revoke`、`/admin*`、`/account*`、`/admin.css`。
  `/authorize` 与 `/token` 关闭 access log。请求体上限按 location：OAuth 16k、
  表单 64k、`/mcp` 1M、`/ingest` 4M；`/mcp` 关闭 `proxy_buffering`。
- `/ingest` **保持公网可达**（`health.example.com`），只认静态 Bearer，OAuth 令牌
  返回 403；4M + nginx `limit_req`（如 60r/m、burst 20）。**不要**加 LAN/私网
  allow 列表：edge/edge-host + FRP 下源地址不可靠，ACL 不成立。
- `mcp.health.example.com` vhost 不改；它的 `/mcp` 继续只认静态 Bearer；OAuth/
  后台路径返回 404。
- 回滚：恢复上一份 `app/` 备份并重启；新表留在库里不影响旧代码；nginx 配置先备份再改。
- 管理员找回：`docker exec -it hc-health-mcp python /app/hcb_admin.py`
  的 `reset-password`（可 `--revoke-static`）或 `reset-totp --yes`；
  宿主机可用 `--db` 先 `bootstrap`。脚本只改 admin 设置、撤管理员
  会话/OAuth/授权码/preauth 并写审计。
- 审计保留：`AUDIT_RETENTION_DAYS`（默认 90）；启动后每小时清理一次，保留锚点。
- 部署后按第 12 节手工验收；未验收不宣称“OAuth/后台已上线”。

## 14. 留给实现者

- `hcb/` 模块的具体切分与函数边界；保持 `server.py` 可独立启动。
- 管理页面的排版细节：按现有无框架 SSR 风格，手机可读，无 JS 也能用。
- `access_log` 的保留条数/清理策略（建议保留 90 天或 5 万条，后台可清空）。
- 概览统计的缓存与代价（大库 `COUNT(*)` 可能慢，先缓存 30 秒）。
- 是否在概览页显示 `creds.json` 的账号名（只显示脱敏值；拿不到就显示“已配置”）。
