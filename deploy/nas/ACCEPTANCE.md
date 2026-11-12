# 验收记录：NAS OAuth / 管理后台 / 找回 CLI（Task 9 全量验收）

状态：**本地自动化与本地端到端验收已完成（415 tests OK）；真实 NAS 部署、
nginx/edge、手机 `/ingest`、真实 MCP 客户端仍未执行**。本文件里凡是标
“待执行”的条目都还没有一手证据，不能因为本地测试通过就宣称线上 OAuth /
后台已验收。

本地环境：Python 3.11.2、bash 5.2.15、curl 7.88.1、shellcheck 0.9.0；
工作目录 `deploy/nas/`（分支 `feat/nas-oauth`）。

## 1. 本地自动化（已完成）

| # | 检查 | 命令 | 期望 | 实测（2026-11-02） |
| --- | --- | --- | --- | --- |
| 1 | 全量单元 + 端到端测试 | `python3 -m unittest discover -s deploy/nas/tests -v` | 全绿，退出 0 | **415 tests OK，177.972s** |
| 2 | 端到端验收子集 | `python3 -m unittest discover -s deploy/nas/tests -p 'test_acceptance.py'` | 7 tests OK | 7 tests OK，7.198s |
| 3 | `verify.sh` 行为测试 | `python3 -m unittest discover -s deploy/nas/tests -p 'test_verify_sh.py'` | 7 tests OK | 7 tests OK，7.600s |
| 4 | shell 语法 | `bash -n deploy/nas/install.sh deploy/nas/restart.sh deploy/nas/verify.sh` | 退出 0 | 退出 0 |
| 5 | shellcheck | `shellcheck deploy/nas/install.sh deploy/nas/restart.sh deploy/nas/verify.sh` | 无告警，退出 0 | 退出 0 |
| 6 | Python 语法 | `python3 -m compileall -q deploy/nas` | 退出 0 | 退出 0 |

证据：

```text
$ python3 -m unittest discover -s deploy/nas/tests -v
Ran 415 tests in 177.972s
OK
$ python3 -m unittest discover -s deploy/nas/tests -p 'test_acceptance.py'
Ran 7 tests in 7.198s
OK
$ python3 -m unittest discover -s deploy/nas/tests -p 'test_verify_sh.py'
Ran 7 tests in 7.600s
OK
$ bash -n deploy/nas/install.sh deploy/nas/restart.sh deploy/nas/verify.sh   # exit 0
$ shellcheck deploy/nas/install.sh deploy/nas/restart.sh deploy/nas/verify.sh # exit 0
$ python3 -m compileall -q deploy/nas                                        # exit 0
```

分文件覆盖（`deploy/nas/tests/`，共 12 个测试文件）：

| 文件 | 内容 |
| --- | --- |
| `test_db.py` | schema/迁移/分区文件 |
| `test_auth.py` | scrypt 密码、会话、CSRF、TOTP、recovery、preauth、静态 key 导入 |
| `test_oauth.py` | DCR、授权码 + PKCE、轮换、重用吊销 family、令牌只存哈希 |
| `test_http.py` | 路由、两份元数据、`/mcp` 401 挑战、登录/同意、限流、`/ingest` |
| `test_policy.py` | 四层工具策略、参数规则、时间窗、分区归属 |
| `test_admin.py` | 后台门禁、用户/密钥/客户端 CRUD、会话、64 KiB 表单、转义 |
| `test_audit.py` | 哈希链、篡改检测、保留锚点、清理 |
| `test_cli.py` | `hcb_admin.py` 六个子命令 |
| `test_install_sh.py` | `install.sh` 假 docker/curl：备份、同步、失败回滚 |
| `test_startup_guard.py` | 占位 token 启动拒绝 |
| `test_acceptance.py` | **本任务新增**：本地端到端验收（见第 2 节） |
| `test_verify_sh.py` | **本任务新增**：`verify.sh` 的 PASS/FAIL 行为（见第 3 节） |

## 2. 本地端到端验收 `test_acceptance.py`（已完成）

做法：临时目录 + 独立 SQLite + 真实 `ThreadingHTTPServer`（`127.0.0.1:0`），
用标准库 `http.client` 走真实 HTTP；不 mock 网络以外的业务路径（只有
`sync_now` 的云端调用被替换，验收只看授权链）。

```sh
python3 -m unittest discover -s deploy/nas/tests -p 'test_acceptance.py' -v
# Ran 7 tests ... OK
```

| 测试 | 断言的关键路径 |
| --- | --- |
| `test_oauth_lifecycle_with_totp_and_write_tools` | 引导管理员 → 后台启用 TOTP（确认后 10 个恢复码）→ 清 Cookie → 密码 + `/login/otp` 两步登录 → `POST /register` → `/authorize` 同意 → `/token` 换码 → `/mcp` `tools/list` 含 `sync_now`、不含 `ingest` → 写工具放行（`-32003` 不出现）→ 读工具返回 → 刷新轮换（access/refresh 都变）→ 旧刷新令牌重用 400 `invalid_grant` 且整族 access 401 → 只读令牌 `tools/list` 无写工具、调 `sync_now` 得 `-32003 insufficient scope` → 库内只有 64 位十六进制哈希 → `/revoke` 200 且该 access 立即 401 |
| `test_static_ingest_policy_window_and_partition` | 后台建静态 key：`tool_policy=[]` → `/ingest` 403 `tool_denied`；关闭时间窗 → 403 `window_closed`；开放时间窗 → 200 且 `steps` 落 owner 0；own 用户 `olga` 的 key → 只落 `users/<id>.db`，admin 分区没有该行 |
| `test_admin_user_crud_and_isolation` | 后台建 alice/bob → 普通用户只能进 `/account`（`/admin` 302 `/account`）→ alice 的账号页不含 bob → 只读令牌写工具 `-32003` → 管理员加 `health.write` 后 alice 旧会话 302 `/login`、旧令牌 401 → 停用后登录 401、令牌 401 → 删除需用户名确认、用户行消失而 bob 仍可登录 |
| `test_unauthorized_surfaces_and_body_limits` | `/mcp` 无令牌 401 且 `WWW-Authenticate: Bearer resource_metadata="<base>/.well-known/oauth-protected-resource/mcp"`；坏令牌 401；`GET /mcp` 405；`/ingest` 无令牌 401、OAuth 令牌 403、静态 200；少 CSRF 的 `/authorize` 与后台 POST 403、坏 CSRF 登录 403；`/register` 17 KB → 413、后台表单 20 KB → 303、70 KB → 413、`/mcp` >1 MB → 413、`/ingest` >4 MB → 413 |
| `test_audit_verify_and_retention` | 审计链 `audit.verify` ok；`hcb_admin.py audit-verify --db …` 退出 0 且输出无令牌/密码；默认 90 天保留（把“现在”推到 400 天后）删除旧前缀并留锚点、`verify` 仍 ok、清理后还能继续接链；篡改末行后 `verify` 失败并给出 `first_bad_id` |
| `test_no_secrets_in_pages_logs_or_hashes` | 捕获 stdout/stderr 请求日志 + 14 个页面（`/health`、两份 well-known、`/login`、`/account`、8 个 `/admin/*`）：不含 access/refresh/静态 key、TOTP secret、恢复码、管理员密码、会话 Cookie；`/admin*`、`/account`、`/login` 均 `Cache-Control: no-store`；`oauth_tokens.token_hash`、`sessions.sid_hash` 均为 64 位十六进制、`admin_password_hash` 为 `scrypt$…` |
| `test_every_tool_has_a_kind` | 每个工具都被分类（read/write/ingest），写工具含 `sync_now`/`backfill`/`sleep_audit`，`ingest` 不属于只读集合 |

## 3. 只读验收脚本 `verify.sh`（已完成）

```sh
sudo ./deploy/nas/verify.sh                       # 宿主机，默认 http://192.0.2.9:18927
./deploy/nas/verify.sh --base-url https://health.example.com
sudo ./deploy/nas/verify.sh --audit-db <deploy-dir>/data/health.db
./deploy/nas/verify.sh --skip-audit               # 数据库不在本机时
```

| # | 检查 | 期望输出 |
| --- | --- | --- |
| 1 | `GET /health` | `PASS health: GET /health -> 200 ok` |
| 2 | 无凭据 `POST /mcp` | `PASS mcp 401: challenge points at resource metadata`（`WWW-Authenticate` 精确匹配） |
| 3 | `GET /.well-known/oauth-protected-resource` | `PASS resource metadata: resource/issuer/scopes ok` |
| 4 | `GET /.well-known/oauth-authorization-server` | `PASS as metadata: issuer/endpoints/PKCE/grant types ok` |
| 5 | `GET /login`（表单 + CSRF + `Cache-Control`） | `PASS login page: form rendered, Cache-Control no-store` |
| 6 | 未登录 `GET /admin` | `PASS admin gate:未登录 302 /login?next=…` |
| 7 | `hcb_admin.py audit-verify --db …` | `PASS audit chain: verified (N rows)`；失败打印 `FAIL audit chain: …` |

样例输出（本地真服务）：

```text
PASS health: GET /health -> 200 ok
PASS mcp 401: challenge points at resource metadata
PASS resource metadata: resource/issuer/scopes ok
PASS as metadata: issuer/endpoints/PKCE/grant types ok
PASS login page: form rendered, Cache-Control no-store
PASS admin gate:未登录 302 /login?next=…
PASS audit chain: verified (1 rows)
SUMMARY: PASS=7 FAIL=0 SKIP=0 (base http://192.0.2.9:18927)
```

行为约定（`test_verify_sh.py` 7 tests 覆盖）：

- 只读：`GET` 与无凭据 `POST`；审计用 `mode=ro` 连接。不写库、不改配置。
- 不读取 `.env`、不需要令牌/密码，输出里不出现密码、令牌、Cookie。
- 全 PASS（可含 SKIP）退出 0；有 FAIL 退出 1；用法错误退出 2。
- 连不上、状态码不对、元数据 issuer/resource 不符、`/admin` 不再 302、
  审计链被篡改 → 对应项 `FAIL` 且退出 1；数据库缺失/`--skip-audit` → `SKIP`。

## 4. 本地已验证 vs 仍需真实环境验证

| 项目 | 本地 | 真实 NAS/公网 |
| --- | --- | --- |
| `/mcp` 401 + `WWW-Authenticate` 资源元数据 | 已验证 | 待执行（`verify.sh`） |
| 两份 `.well-known` 的 issuer/resource | 已验证（`PUBLIC_BASE_URL` = 测试根） | 待执行（必须是 `https://health.example.com`） |
| OAuth 全流程（DCR→同意→换码→tools/list→刷新→撤销） | 已验证（标准库客户端） | 待执行（MCP Inspector/Claude/ChatGPT） |
| 写工具允许/拒绝、只读 scope、`ingest` 对 OAuth 403 | 已验证 | 待执行（真实客户端） |
| 四层策略/时间窗/分区（静态 key、own 用户） | 已验证 | 待执行（后台+手机） |
| 后台用户 CRUD、会话立即失效、隔离 | 已验证 | 待执行（浏览器） |
| 审计链校验与保留（含篡改检测） | 已验证 | 待执行（容器内 `audit-verify`） |
| 请求体 16k/64k/1m/4m | 应用层已验证（按 `Content-Length` 拒绝） | 待执行（nginx `client_max_body_size` 实测 413） |
| 页面/日志不出现秘密 | 已验证 | 待执行（真实访问日志、浏览器缓存） |
| nginx、TLS/HSTS、`limit_req`、`proxy_buffering` | 不适用 | **待执行** |
| 手机 HcPusher `/ingest`、小米凭据同步 | 不适用 | **待执行** |

## 5. NAS 部署（待执行，需要 NASCODE / 人工确认）

- [ ] 备份现有 `app/`、`docker-compose.yaml`、`.env`、nginx vhost
- [ ] 首次无容器时，宿主机先跑
      `python /path/to/hcb_admin.py bootstrap --db .../data/health.db`
      （避免还没有容器时的循环；重复执行应输出 already configured 且不改密码）
- [ ] `.env` 含 `PUBLIC_BASE_URL=https://health.example.com`、`HCB_TRUST_PROXY=1`、
      真实 `HCB_TRUSTED_PROXIES`（nginx/网关的 `/32` 或最小 CIDR，不是 `172.16.0.0/12`）
- [ ] `sudo ./deploy/nas/install.sh` 成功；输出无秘密
- [ ] `bash -n deploy/nas/install.sh deploy/nas/verify.sh` 与
      `shellcheck …` 在上线前重跑一次（本地已通过）
- [ ] `curl -fsS http://192.0.2.9:18927/health` → `{"ok":true,...}`
- [ ] 无 token `POST /mcp` → 401
- [ ] `sudo ./deploy/nas/verify.sh --audit-db …/data/health.db` → `FAIL=0`
- [ ] 容器重建后 `docker compose ps` 为 healthy
- [ ] 旧 `health.db`、`creds.json`、`data/users/*.db` 未被移动/改写
- [ ] 失败演练：让 health 失败确认自动回滚；或执行
      `install.sh --rollback <备份>` 并确认恢复

## 6. 首次登录 / TOTP / 找回（本地自动化已完成；NAS 待执行）

- [x] 本地：`HCB_ADMIN_PASSWORD` 引导管理员，重复引导幂等（`test_auth.py`、
      `test_cli.py`）
- [x] 本地：`/login` 密码 + `/login/otp` 两步登录（`test_http.py`、
      `test_acceptance.py`）
- [x] 本地：后台启用 TOTP → 确认 → 10 个恢复码只显示一次；恢复码一次性
      （`test_admin.py`、`test_auth.py`）
- [x] 本地：`reset-password`/`reset-totp --yes`/`revoke-sessions` 撤会话、
      OAuth、授权码、preauth；普通用户不受影响（`test_cli.py`）
- [ ] NAS：真实浏览器完成上述步骤
- [ ] NAS：`reset-password --revoke-static` 后旧密码失败、新密码成功
- [ ] NAS：`docker exec … audit-verify` 退出 0；手工改一行后退出 1；恢复后再次 0

## 7. OAuth / MCP（本地自动化已完成；真实客户端待执行）

- [x] 本地：`/.well-known/oauth-protected-resource`、
      `…/oauth-protected-resource/mcp`、`…/oauth-authorization-server`
- [x] 本地：DCR 校验（redirect 精确匹配、https/回环、公开/机密、200 上限）
- [x] 本地：同意 → 授权码 → PKCE 换码 → `tools/list` → 工具调用
- [x] 本地：只读令牌 `tools/list` 无写工具；写工具 `-32003`
- [x] 本地：`ingest` 对 OAuth 永远 403（MCP 工具与 `POST /ingest`）
- [x] 本地：刷新轮换；旧刷新令牌重用吊销整族；`/revoke` 后立即 401
- [x] 本地：令牌只存 SHA-256；`client_secret` 只显示一次
- [ ] NAS/公网：`verify.sh` 检查 issuer/resource 为
      `https://health.example.com` 与 `https://health.example.com/mcp`
- [ ] NAS/公网：MCP Inspector 或 Claude/ChatGPT 连接器完成发现→注册→登录→
      同意→换码→`tools/list`
- [ ] NAS/公网：`query_heart_rate` 返回真实数据
- [ ] NAS/公网：`mcp.health.example.com/mcp` 静态 Bearer 成功；
      同域 `/.well-known/*`、`/authorize`、`/token`、`/admin` 为 404
- [ ] NAS/公网：MCP `ingest` 仍用静态 Bearer 成功；手机 HcPusher 直连/回落可用

## 8. 后台 / 多账号 / 权限（本地自动化已完成；NAS 待执行）

- [x] 本地：建普通用户（shared 与 own）、登录 `/account`
- [x] 本地：A 看不到 B 的页面/会话；停用/改 scope/删除后旧会话与令牌立即失效
- [x] 本地：own 用户分区互不可见；静态 key 按 owner 落分区
- [x] 本地：删除 own 用户分区归档到 `data/archive/`（`test_admin.py`）
- [x] 本地：四层工具策略（用户/客户端/令牌/静态 key）各自拒绝时都拒绝
- [x] 本地：参数级规则（如只允许 `steps`）；`tools/list` 隐藏被禁工具
- [x] 本地：时间窗（星期/时段/跨午夜/时区/绝对起止）窗外拒绝、窗内恢复
- [ ] NAS：浏览器实测上述页面（手机宽度可读、无 JS 可用）
- [ ] NAS：真实数据分区统计、凭据上传/同步、删除后归档可找回

## 9. nginx（待执行，本任务未改 nginx）

- [ ] 备份原 vhost；`sudo nginx -t` 通过后再 reload
- [ ] `health.example.com` TLS、HSTS、HTTP→HTTPS
- [ ] `/.well-known/`、`/authorize`、`/token`、`/register`、`/revoke`、
      `/login`、`/login/otp`、`/logout`、`/admin*`、`/account*` 可达
- [ ] `/mcp` 1M 上限、`proxy_buffering off`（SSE 不卡）
- [ ] `/ingest` 公网可达、4M、`limit_req` 生效（超限 429）、静态令牌仍生效
- [ ] 表单 64k、OAuth 16k；超限返回 413（应用层已验证，需 nginx 实测）
- [ ] `/authorize`、`/token` 不写 access log
- [ ] `/admin`、`/account`、`/login` 响应 `Cache-Control: no-store`
- [ ] `X-Real-IP` 正确；伪造 `X-Forwarded-For` 不影响限流/审计 IP
- [ ] `mcp.health` 的 OAuth/后台路径为 404

## 10. 明确未验证 / 已知限制

- **已执行（2026-11-03）**：真实 NAS 部署、`NASCODE` sudo、容器重建、
  host-b LAN vhost 与 edge-host 公网 edge vhost 的 `nginx -t` + reload。
- 真实 `limit_req` 429 与 `client_max_body_size` 413：未单独压测；
  应用层 1M/4M/16k/64k 已有自动化覆盖。
- 真实 MCP Inspector / Claude / ChatGPT 连接器：未执行。
- 真实手机 `/ingest`、小米凭据同步与云端拉取：未执行（本地只 mock 了
  `sync_now` 的云端调用）。
- 数据备份/恢复演练、真实审计清理（90 天线上数据）：未执行。
- 单进程 SQLite、无 HA；限流重启清零；无 OIDC/多管理员。
- `install.sh` 会删除 `app/hcb/` 源目录已不存在的 `.py`；不安装 nginx、
  不改 `.env` 内容、不备份 `data/`；备份失败会直接退出。
- `/ingest` 没有可靠的 LAN ACL：edge/FRP 下 nginx 看到的是转发地址；安全
  边界是静态 Bearer + token 轮换，nginx 只做 4M + `limit_req`。
- 未认证的 `/ingest` 拒绝当前不落审计（已知残留，见 Task 8 报告）。

## 11. 证据与签署

| 日期 | 环境 | 检查 | 结果 | 证据/备注 |
| --- | --- | --- | --- | --- |
| 2026-11-02 | 本地 worktree | 全量自动化 | PASS | 415 tests OK，177.972s |
| 2026-11-02 | 本地 worktree | 端到端验收 | PASS | `test_acceptance.py` 7 tests OK，7.198s |
| 2026-11-02 | 本地 worktree | `verify.sh` | PASS | `test_verify_sh.py` 7 tests OK，7.600s |
| 2026-11-02 | 本地 worktree | shell/编译检查 | PASS | `bash -n`、`shellcheck`、`compileall` 全 0 |
| 2026-11-03 | NAS `hc-health-mcp` | 部署 | PASS | `install.sh`；`/health` 200、无 token `/mcp` 401；备份 `backups/app-20261103-001235-2593436` |
| 2026-11-03 | NAS | 静态 key | PASS | `/mcp tools/list` 200，19 tools；日志 `agent=legacy` 的 `/ingest` 200 |
| 2026-11-03 | NAS / LAN HTTPS | 管理员 | PASS | `admin` 登录 303→`/admin` 200；`audit-verify` ok，17 行 |
| 2026-11-03 | `health.example.com` LAN | OAuth 端到端 | PASS | DCR→登录→同意→token→`/mcp` 200（read 15 tools）→refresh 200→reuse 400 |
| 2026-11-03 | `health.example.com` 公网 edge | 路由 | PASS | metadata/login 200、`/admin` 302、`/mcp` POST 401、`/ingest` 405 |
| 2026-11-03 | `mcp.health.example.com` | 限制 | PASS | `/mcp` 405、OAuth/`/login` 403 |
| | 生产域名 | `limit_req`/413/手机 TOTP | 待执行 | 限流与表单上限未压测；管理员 TOTP 尚未开启 |

验收人：__________　日期：__________
