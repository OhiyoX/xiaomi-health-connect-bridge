# hc-bridge NAS OAuth + 后台实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`).

**Goal:** 给 NAS 的 `hc-health-mcp` 加内嵌 OAuth 2.1、多账号、可选数据隔离、四层工具权限/分时授权、TOTP、会话、审计链和 Web 管理后台。

**Architecture:** 仍是 Python 标准库单进程 + SQLite。`server.py` 保留入口与业务工具，新增 `hcb/` 包放 schema/auth/oauth/web/admin/policy/audit。每个用户可选 `shared`（owner 0 的 `health.db`）或 `own`（`data/users/<id>.db` + 自己的小米凭据）。OAuth 令牌不透明，只存 SHA-256。

**Tech Stack:** Python 3.12 stdlib (`http.server`, `sqlite3`, `hashlib`, `hmac`, `secrets`), Docker, unittest.

**Spec:** `docs/superpowers/specs/2026-11-02-oauth-admin-design.md`

## Global Constraints

- 不新增 Python 依赖；只用标准库。
- 现有 `server.py` 的 MCP 工具契约、静态 Bearer、`/health`、`/ingest` 行为不能回退。
- 令牌/授权码/secret/恢复码只存 SHA-256；密码用 `hashlib.scrypt`；比较用 `hmac.compare_digest`。
- 数据分区：owner 0 = `data/health.db`；`own` 用户 = `data/users/<id>.db`；查询只打开当前 owner。
- 四层策略（用户/客户端/令牌/静态 key）取交集；时间窗多层取交集；admin 不受时间窗限制。
- `ingest` 永不给 OAuth；只接受静态 key，写入 key 的 owner 分区。
- 所有页面 SSR、无 JS 可用；CSRF 每个 POST；无秘密进入日志/审计 payload。
- 测试：`python -m unittest discover -s deploy/nas/tests -v`。

## Review Focus

1. 分区串库：owner 解析错误会让 A 看到 B 的健康数据。
2. 静态 key 迁移：`.env` 导入后不能重复导入或静默失效。
3. OAuth 同意：scope/工具/时间窗只能在用户上限内收窄。
4. 分时窗跨午夜与星期边界。
5. 管理员 TOTP 在 secret 缺失时必须 fail-closed；恢复码一次性。

---

### Task 1: 包骨架、数据库分区与 schema

**Files:**
- Create: `deploy/nas/hcb/__init__.py`
- Create: `deploy/nas/hcb/db.py`
- Create: `deploy/nas/hcb/schema.py`
- Create: `deploy/nas/hcb/context.py`
- Modify: `deploy/nas/server.py`（导入 `db`/schema，移除本地建表）
- Test: `deploy/nas/tests/test_db.py`

**Interfaces:**
- `hcb.context.owner` (`ContextVar[int]`, default 0)；`set_owner(value)`。
- `hcb.db.db_path(owner: int) -> str`；`hcb.db.db(owner: int | None = None)` context manager。
- `hcb.schema.ensure(con)` 建全部表/索引；`hcb.schema.ensure_all()` 对 owner 0 和已有用户分区执行。
- `hcb.db.user_partition(user_id) -> str`（创建目录，不建库）。

- [ ] **Step 1: 写失败测试**：临时 DATA_DIR，`db(0)` 建 `health.db`，`db(7)` 建 `data/users/7.db`；两库表集合相同；老 `health.db` 的 `heart_rate` 行保留；`owner` ContextVar 默认 0。
- [ ] **Step 2: 跑测试确认失败**：`python -m unittest deploy.nas.tests.test_db -v`（或 `cd deploy/nas && python -m unittest tests.test_db -v`）。
- [ ] **Step 3: 实现 schema**：业务表照现有 DDL；新增 `users`、`sessions`、`admin_preauth`、`agent_tokens`、`oauth_clients`、`oauth_codes`、`oauth_tokens`、`access_log`、`service_secrets`；`sync_state` 增加 admin/TOTP/audit 键由后续任务写。
- [ ] **Step 4: 实现 `db()`**：`sqlite3.connect(path)`, `row_factory=sqlite3.Row`, `PRAGMA journal_mode=WAL`, `foreign_keys=ON`, `busy_timeout=5000`；退出时 commit/close。
- [ ] **Step 5: 改 `server.py`**：删除本地 `db()`/建表，导入 `hcb.db.db`；保证 `/health` 仍工作。
- [ ] **Step 6: 跑测试 + 现有手工 `/health`，提交** `feat(nas): 分区数据库与 schema`。

---

### Task 2: 身份、会话、TOTP、静态 key

**Files:**
- Create: `deploy/nas/hcb/auth.py`
- Create: `deploy/nas/hcb/totp.py`
- Create: `deploy/nas/tests/test_auth.py`

**Interfaces:**
- `hash_password(p) -> str` / `verify_password(p, stored) -> bool`（`scrypt$N$r$p$salt$hash`）。
- `totp_secret() -> str`；`totp_code(secret, at=None) -> str|None`；`verify_totp(secret, code, at=None, window=1) -> int|None`（返回 step）。
- `create_user(...)`, `by_username`, `get_user`, `update_user`, `list_users`；revision bump on password/scopes/enabled。
- `create_session(con, subject, revision, ttl, ua) -> (sid, cookie)`；`read_session(con, cookie, secret) -> dict|None`；`revoke_session`, `revoke_subject`。
- `static_token_create/lookup/revoke/import_env`。
- `settings_get/set`。

- [ ] **Step 1: 测试**：scrypt 往返；TOTP RFC 向量、±1、坏 secret；会话篡改/过期/吊销；用户 revision；静态 key 哈希；env 导入幂等。
- [ ] **Step 2: 实现密码与 TOTP**：base32、HMAC-SHA1、30s、6 位。
- [ ] **Step 3: 实现会话**：Cookie `hcb_session=base64(payload).hmac`；payload `{sub,rev,sid,exp}`；DB 存 `sid_hash`；UA 截断 120。
- [ ] **Step 4: 实现静态 key**：`agent_tokens(label,hash,owner_user_id,tool_policy_json,window_json,origin,...)`；首次启动把 `HEALTH_MCP_TOKENS`/`HEALTH_MCP_TOKEN` 导入一次，`tokens_imported` 标记。
- [ ] **Step 5: 跑测试并提交** `feat(nas): 用户、会话、TOTP 与静态 key`。

---

### Task 3: OAuth 核心

**Files:**
- Create: `deploy/nas/hcb/oauth.py`
- Create: `deploy/nas/tests/test_oauth.py`

**Interfaces:**
- `register_client`, `get_client`, `list_clients`, `revoke_client`.
- `validate_authorize(con, params, canonical_resource) -> dict`.
- `issue_code(con, {...}) -> code`；`exchange_code(con, {...}) -> tokens`；`refresh(con, {...}) -> tokens`；`verify_access(con, raw, canonical_resource) -> principal|None`；`revoke_token`.
- `capabilities_for_scopes(scopes) -> None | list[str]`；`SCOPES = ("health.read","health.write")`。

- [ ] **Step 1: 测试**：PKCE、码一次性/5 分钟、client/redirect/resource 绑定、刷新轮换/重用吊销、scope 子集、令牌只存哈希。
- [ ] **Step 2: 实现客户端注册**：redirect 精确匹配、https/回环例外、公开/机密、上限 200。
- [ ] **Step 3: 实现码与令牌**：`oc_`/`oa_`/`or_`；60 分钟/30 天；`family_id`；事务内消费码与轮换刷新。
- [ ] **Step 4: 实现 scope→工具白名单**：`health.read` 列表、`health.write`；`ingest` 永不在 OAuth 白名单。
- [ ] **Step 5: 跑测试并提交** `feat(nas): OAuth 码、PKCE、刷新与令牌`。

---

### Task 4: HTTP 路由、发现与 MCP 鉴权

**Files:**
- Create: `deploy/nas/hcb/web.py`
- Create: `deploy/nas/hcb/ratelimit.py`
- Modify: `deploy/nas/server.py`（Handler 调用 web 路由，401 challenge，principal）
- Test: `deploy/nas/tests/test_http.py`

**Interfaces:**
- `web.handle(handler) -> bool`：处理 `/.well-known/*`、`/register`、`/authorize`、`/token`、`/revoke`、`/login`、`/logout`、`/admin*`、`/account*`、`/admin.css`。
- `web.metadata(canonical)`；`web.consent_page(...)`。
- `ratelimit.take(key,max,window)->bool`。
- `server.resolve_principal(auth_header) -> dict|None`；`rpc(msg, principal)`。

- [ ] **Step 1: 测试**：元数据字段；无令牌 `/mcp` 401 + `WWW-Authenticate`；注册→登录→同意→换码→`tools/list`；CSRF；body 16KB；限流。
- [ ] **Step 2: 实现公开路由**：`PUBLIC_BASE_URL` 默认 `https://health.example.com`，无则按请求推导（trusted private peer）。
- [ ] **Step 3: 实现同意**：会话行推导身份；scope 复选 = 请求 ∩ 用户上限；签名/暂存请求防篡改。
- [ ] **Step 4: 接入 MCP**：`oa_` → `verify_access`，静态 key → `agent_tokens`；401 challenge；`/ingest` 只静态。
- [ ] **Step 5: 跑测试并提交** `feat(nas): OAuth HTTP 与 MCP 鉴权`。

---

### Task 5: 工具权限、分时与分区隔离

**Files:**
- Create: `deploy/nas/hcb/policy.py`
- Modify: `deploy/nas/server.py`（`rpc`/`call_tool`/`fetch_loop`/`sync_once`/`backfill_async`）
- Test: `deploy/nas/tests/test_policy.py`

**Interfaces:**
- `TOOL_SCOPES`；`READ_TOOLS`；`capabilities_for_scopes`。
- `policy_allow(principal, tool, args, now) -> (bool, reason)`；四层 AND；`window_allow(window, now)`；`windows_allow(list, now)`。
- `owner_for(principal) -> int`；`set_owner` around request.

- [ ] **Step 1: 测试**：每个 `tool_list()` 工具都有分类；只读令牌不能 `sync_now`/`backfill`/`sleep_audit`；`ingest` 对 OAuth 403；参数级规则；时间窗交叉；admin 豁免；owner 0/own。
- [ ] **Step 2: 实现 policy**：规则 JSON 同 pan-scheduler；损坏列 fail-closed。
- [ ] **Step 3: 接入 `rpc`/`call_tool`**：`tools/list` 过滤；越权 -32003；审计拒绝。
- [ ] **Step 4: 分区**：`fetch_loop` 遍历有凭据的 own 用户 + owner 0；`sync_once`/`backfill_async` 接受 owner；`call_tool` 用当前 owner。
- [ ] **Step 5: 跑测试并提交** `feat(nas): 四层权限、时间窗与分区`。

---

### Task 6: 管理后台

**Files:**
- Create: `deploy/nas/hcb/admin.py`
- Create: `deploy/nas/hcb/views.py`
- Modify: `deploy/nas/hcb/web.py`（挂载）
- Test: `deploy/nas/tests/test_admin.py`

**Interfaces:** `/admin` 概览；`/admin/users`；`/admin/oauth`；`/admin/tokens`；`/admin/audit`；`/admin/security`；`/admin/sessions`；`/admin/settings`；`/account`；`/account/password`。
- `views.page(title, body, csrf, nav)`, `views.table`, `views.form`.
- `admin.handle(...)` returns bool.

- [ ] **Step 1: 测试**：非 admin 302 `/account`；创建用户后能登录并只查自己的分区；TOTP 启用/关闭；静态 key 创建/撤销；OAuth 客户端/令牌；会话吊销。
- [ ] **Step 2: 实现概览/用户**：分区选择器、凭据状态、导入/替换凭据、同步/回补、用户 CRUD。
- [ ] **Step 3: 实现 OAuth/静态 key**：客户端、令牌、工具/时间窗、撤销。
- [ ] **Step 4: 实现安全/会话/审计页**：TOTP、恢复码、会话、审计过滤。
- [ ] **Step 5: 跑测试并提交** `feat(nas): Web 管理后台`。

---

### Task 7: 审计链、保留与清理

**Files:**
- Create: `deploy/nas/hcb/audit.py`
- Modify: `deploy/nas/server.py`（写审计、后台清理）
- Test: `deploy/nas/tests/test_audit.py`

**Interfaces:** `write(con, actor, kind, action, tool, result, detail)` 链式；`verify(con)`；`prune(con, days)`；`cleanup(con)`。

- [ ] **Step 1: 测试**：链校验；改/删一行被发现；head 缺失 fail-closed；prune 锚点；清理只删过期。
- [ ] **Step 2: 实现审计**：`prev_hash`/`hash`；链头/锚点 settings；`BEGIN IMMEDIATE`。
- [ ] **Step 3: 接入**：MCP 调用、登录、同意、令牌、用户变更、后台动作。
- [ ] **Step 4: 跑测试并提交** `feat(nas): 审计链与保留`。

---

### Task 8: 找回 CLI、安装脚本、文档

**Files:**
- Modify: `deploy/nas/server.py`（`--reset-admin-password`, `--reset-totp`, `--verify-audit`）
- Create: `deploy/nas/install.sh`
- Modify: `deploy/nas/docker-compose.yaml`, `deploy/nas/env.example`, `README.md`, `OPERATIONS.md`
- Test: `deploy/nas/tests/test_recovery.py`

- [ ] **Step 1: 测试**：重置后旧密码失败、新密码成功；TOTP 清空；审计写；不动业务表；缺库失败。
- [ ] **Step 2: 实现 CLI**：密码从 stdin，不落 argv；`--verify-audit` 只读。
- [ ] **Step 3: 写 install.sh**：同步 `server.py` + `hcb/` 到容器 `app/`，备份、重启、`/health` 检查、失败回滚。
- [ ] **Step 4: 文档**：OAuth/多账号/隔离/权限/时间窗/Tunnel（NAS 侧）、找回、env。
- [ ] **Step 5: 提交** `feat(nas): 找回、安装与文档`。

---

### Task 9: 全量验证

- [ ] **Step 1**：`python -m unittest discover -s deploy/nas/tests -v` 全绿。
- [ ] **Step 2**：本地起服务，curl 走发现→注册→登录→同意→换码→`tools/list`→刷新→撤销。
- [ ] **Step 3**：两个 owner 分区隔离测试；`ingest` 静态 key 写入正确分区；OAuth `ingest` 403。
- [ ] **Step 4**：写 `deploy/nas/ACCEPTANCE.md`，标明未验证项（真实 MCP 客户端、nginx、NAS 部署）。
- [ ] **Step 5**：提交 `test(nas): 验收记录`。
