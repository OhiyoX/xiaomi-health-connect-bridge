# hc-health-mcp（NAS）部署、找回与运维

本目录是 NAS 上的 Python 服务：`server.py` + `hcb/` 包 + `hcb_admin.py`。
它提供：

- Streamable-HTTP MCP：`/mcp`（OAuth 2.1 或静态 Bearer）。
- 手机推送：`POST /ingest`（公网可达，**只认静态 Bearer**；OAuth 403）。
- OAuth 2.1 授权服务器 + 资源服务器：`/.well-known/*`、`/register`、
  `/authorize`、`/token`、`/revoke`。
- 管理后台：`/admin`（共享 SPA，与 App 同一套前端）、`/admin/api/*` JSON、`/login`、`/login/otp`、`/logout`；旧 SSR 在 `/admin/legacy/*` 保留一个版本。
- 审计哈希链、保留清理与校验。

设计细节见
`docs/superpowers/specs/2026-11-02-oauth-admin-design.md`。部署验收清单见
`deploy/nas/ACCEPTANCE.md`。

## 0. 管理后台（共享 SPA）

`/admin` 返回 `hcb/admin_ui/index.html`，与 App WebView 使用同一套 `admin-ui/`。
前端由 `python3 scripts/build-admin-ui.py` 从 `docs/admin-ui-proto/` 生成/复制，**不要手改** `hcb/admin_ui/`。

- `/admin/api/state`：一次返回概览/用户/会话/令牌/OAuth/覆盖/睡眠/指标/审计/安全。
- `/admin/api/act`：JSON 写动作；需要 `X-CSRF-Token`（state 里的 `csrf`）＋管理员会话＋同源。
- `/admin/assets/*`：仅构建产物，`no-store`，无会话也可取（无数据）。
- 旧路由 `/admin/users` 等 302 到 `/admin#/users`；旧 SSR 在 `/admin/legacy/*` 保留一个版本。
- TOTP 启用后显示 10 个一次性恢复码；停用/重绑要当前 6 位动态码。

改前端后：`python3 scripts/build-admin-ui.py && ./scripts/check-admin-ui.sh`，再部署。

## 1. 目录与文件

| 文件 | 作用 |
| --- | --- |
| `server.py` | HTTP 入口、MCP 工具、后台/网页路由分发 |
| `hcb/` | DB、认证、OAuth、策略、审计、管理页 |
| `hcb_admin.py` | 管理员找回/诊断 CLI（bootstrap、reset-password [--revoke-static]、reset-totp --yes、revoke-sessions、audit-verify、status） |
| `install.sh` | 备份并校验 → 同步 `server.py`/`hcb/`/`hcb_admin.py`/`hcb/admin_ui/`（删除废弃模块）→ 重建容器 → health + `/mcp` 401 检查；失败回滚 |
| `docker-compose.yaml` | 容器、只读代码挂载、`192.0.2.9:18927:18927`、healthcheck |
| `env.example` | `.env` 模板（无真实秘密） |
| `nginx-health-example.com.conf` | edge/NAS nginx 样例（主站 + `mcp.health`） |
| `restart.sh` | 旧的手工重启脚本；新部署优先用 `install.sh` |
| `verify.sh` | 只读验收检查：`/health`、`/mcp` 401 挑战、OAuth 元数据、登录页、审计链；打印 PASS/FAIL，不改数据 |

容器把宿主 `./app` 只读挂载为 `/app`，`./data` 挂载为 `/data`。
`install.sh` 只同步代码，不动 `data/`、`creds.json`；`.env` 内容完全不改，
只把权限强制为 600。

## 2. 安装/升级

前提：NAS 有 Docker Compose、curl、bash；部署用户有 docker 权限（通常 `sudo`）。

```sh
cd <deploy-dir>
# 首次：准备 .env（从 env.example 复制，600）与 data/
sudo cp /path/to/hc-bridge/deploy/nas/env.example .env
sudo chmod 600 .env
# 编辑 .env：PUBLIC_BASE_URL、HCB_TRUST_PROXY、HCB_TRUSTED_PROXIES、
# HCB_ADMIN_PASSWORD（首次）等；不要提交 .env。

# 从代码 checkout 运行安装脚本（脚本按自身位置找源码）
cd /path/to/hc-bridge
sudo ./deploy/nas/install.sh
```

`install.sh` 做的事：

1. 在 `backups/app-<时间戳>-<pid>/` 备份 `app/` 和 `docker-compose.yaml`，
   保留最近 `HCB_BACKUP_KEEP` 份（默认 5）。
2. 校验备份里有 `app/` 与 `MANIFEST` 后才继续；备份不完整就停止，不碰代码。
3. 把 `server.py`、`hcb_admin.py`、`hcb/*.py` 同步到 `app/`，权限收紧为
   目录 700、文件 600；删除 `app/hcb/` 里源目录已不存在的 `.py` 和旧
   `__pycache__`；`.env` 内容不变、权限 600；`data/` 700（存在时）。
4. `docker compose up -d --force-recreate`。
5. 轮询 `http://192.0.2.9:18927/health`，并确认无 token 的 `POST /mcp`
   返回 **401**。
6. 任一步失败：只在备份完整时恢复代码并重建容器，再检查；失败时保留备份
   并打印路径；备份失败则直接退出、不部署。

常用覆盖变量：

```sh
sudo HCB_DEPLOY_DIR=<deploy-dir> \
     HCB_HEALTH_TIMEOUT=90 ./deploy/nas/install.sh
# 同时覆盖部署目录的 compose（默认不改）：
sudo HCB_SYNC_COMPOSE=1 ./deploy/nas/install.sh
```

注意：

- 脚本不读取、不打印 `.env` 内容，也不改内容；只强制 `chmod 600`。
- `app/hcb/` 里源目录已不存在的 `.py` 会被删除（备份里仍有旧版本，可回滚）。
- 如果服务首次启动还没有任何鉴权配置（空库、无 token、无管理员），
  `/mcp` 可能暂时是开放模式，此时 401 冒烟会失败并触发回滚。首次安装请先在
  宿主机建管理员，避免“容器还没起来 → 只能 docker exec”的循环：

  ```sh
  cd /path/to/hc-bridge
  python3 deploy/nas/hcb_admin.py bootstrap \
    --db <deploy-dir>/data/health.db
  ```

  用该 `data/` 的属主用户运行（或容器 root；权限不足时用
  `sudo -u <属主>`）；TTY 提示输入密码，密码不进 argv。也可以在 `.env`
  配好 `HCB_ADMIN_PASSWORD` 或静态 token 再跑。
- 本仓库的 `nginx-health-example.com.conf` 只是样例，不会被 `install.sh`
  自动安装。

## 3. 首次启动与首次登录

首次启动时 `server.py` 会建 schema，并尝试：

- 用 `HCB_ADMIN_PASSWORD`（≥12 字符）建管理员密码；已有哈希时**不会覆盖**。
- 导入 `HEALTH_MCP_TOKEN(S)` 作为静态 key（幂等）；`HEALTH_MCP_TOKEN` 留空或
  填真实值，`__TOKEN__`/`CHANGE_ME` 等模板字面量会让 `server.py` 拒绝启动。

首次还没有容器时，在宿主机用 `--db` 建管理员（见第 2 节），避免依赖
`docker exec`。容器起来后也可以交互式建（密码不落 argv）：

```sh
sudo docker exec -it hc-health-mcp python /app/hcb_admin.py bootstrap
```

`bootstrap` 幂等：已有管理员时打印 `already configured` 并返回 0。

登录：

1. 打开 `https://health.example.com/login`。
2. 用户名 `admin`，密码为 `HCB_ADMIN_PASSWORD` 或 `bootstrap` 时输入的密码。
3. 若已启用 TOTP，再输入 6 位动态码或一个恢复码。

首次登录后立即到 `/admin/security`：

1. 生成 TOTP 密钥，用验证器扫二维码/手抄。
2. 输入当前 6 位码确认启用。
3. 保存一次显示的 10 个恢复码；每个只能用一次。
4. 已启用的 TOTP 不能直接换密钥；先关闭再重新启用。

恢复码在 `/login/otp` 与动态码同一输入框使用。

## 4. 管理员找回 CLI

所有命令都在容器内运行；也可在宿主机对 owner 0 数据库运行：

```sh
python3 deploy/nas/hcb_admin.py status --db /path/to/health.db
```

`--db` 可放在子命令前后；省略时用 `$HEALTH_DB`（容器内默认
`/data/health.db`）。所有写命令都只操作 owner 0 库，不碰业务分区表。

### 4.1 忘记管理员密码

```sh
sudo docker exec -it hc-health-mcp python /app/hcb_admin.py reset-password
```

TTY 下用 `getpass` 提示两次；非 TTY 从 stdin 读一行。密码绝不出现在
argv、输出或审计里。效果：

- 写入新的管理员密码哈希（≥12 字符）。
- 吊销全部管理员会话。
- 吊销 `user_id=0` 的 OAuth 访问/刷新令牌，删除管理员未兑换授权码。
- 删除管理员未兑换的 preauth。
- 写一行 `admin.password.reset_cli` 审计。
- **不改**普通用户、业务表；TOTP 保持原状；静态 key 默认保留。
- 加 `--revoke-static` 时同时吊销**所有**静态 Bearer key（管理员与普通用户）。
  怀疑密码泄漏时建议加；不加则请到 `/admin/tokens` 逐个轮换/撤销。

若审计链已经损坏，找回仍会完成，stderr 打印
`warning: audit append failed (...)`；随后用 `audit-verify` 查看断点，
保留数据库取证并从可信备份恢复审计。

脚本化时不要把密码写成命令参数，用 stdin：

```sh
read -rsp 'New admin password: ' PW; echo
sudo docker exec -i hc-health-mcp python /app/hcb_admin.py reset-password <<<"$PW"
unset PW
```

### 4.2 丢失 TOTP 设备

```sh
sudo docker exec -it hc-health-mcp python /app/hcb_admin.py reset-totp --yes
```

必须带 `--yes`。效果：清空 TOTP 密钥、启用标记、已用 step、全部恢复码；
同时撤销**全部管理员会话**、`user_id=0` 的 OAuth 访问/刷新令牌、未兑换
管理员授权码和 preauth，并写审计。管理员密码、普通用户、静态 key 不受影响。
之后重新登录，到 `/admin/security` 重新启用 TOTP。

### 4.3 强制下线

```sh
# 只吊销管理员会话
sudo docker exec -it hc-health-mcp python /app/hcb_admin.py revoke-sessions

# 吊销一个普通用户（用户名或用户 id）
sudo docker exec -it hc-health-mcp python /app/hcb_admin.py revoke-sessions --user alice
```

管理员吊销还会清掉管理员 preauth。用户会话立即失效；OAuth/静态令牌不会
因“只吊销会话”而撤销，需要时在后台 OAuth/静态令牌页面撤销，或用
`reset-password --revoke-static` 一次性撤掉全部静态 key。

### 4.4 审计链校验

```sh
sudo docker exec -it hc-health-mcp python /app/hcb_admin.py audit-verify
```

- 只读连接（`mode=ro`），不建表、不写审计。
- 输出 JSON：`ok`、`rows`、`head`、`error`、`first_bad_id`。
- 链正常退出 0；被改/断链/链头不一致退出 1。

被篡改时不要删表“修复”；保留数据库用于取证，从可信备份恢复，或按审计
记录人工核对。审计保留默认 90 天（`AUDIT_RETENTION_DAYS`），清理会留锚点。
找回命令在链损坏时仍会执行并给出警告（见 4.1），但校验结果仍以
`audit-verify` 的退出码为准。

### 4.5 状态摘要

```sh
sudo docker exec -it hc-health-mcp python /app/hcb_admin.py status
```

只读 JSON：管理员密码是否设置、TOTP 是否启用、恢复码剩余数、用户/会话/
OAuth/静态 key 计数、审计链状态、`PUBLIC_BASE_URL`、代理信任开关等。
**不输出**密码、令牌、Cookie、TOTP 密钥或恢复码。

### 4.6 只读验收检查（verify.sh）

```sh
sudo ./deploy/nas/verify.sh                                  # 宿主机 + 默认端口映射
./deploy/nas/verify.sh --base-url https://health.example.com  # 走公网域名
sudo ./deploy/nas/verify.sh --audit-db <deploy-dir>/data/health.db
```

- 只读：`GET /health`、无凭据 `POST /mcp`、两份 `.well-known` 元数据、
  `GET /login`、未登录 `GET /admin`，审计链用 `hcb_admin.py audit-verify`
  （只读连接）。不写库、不改配置、不需要令牌或密码。
- 每项打印 `PASS` / `FAIL` / `SKIP`，最后一行
  `SUMMARY: PASS=… FAIL=… SKIP=…`；有 FAIL 退出 1，用法错误退出 2。
- 不打印任何秘密；`--skip-audit` 用于数据库不在本机时（该行显示 SKIP）。
- `HCB_BASE_URL`、`HCB_AUDIT_DB`、`HCB_DEPLOY_DIR`、`HCB_VERIFY_TIMEOUT`
  可覆盖默认值。

## 5. OAuth 与多账号

发现地址（`PUBLIC_BASE_URL=https://health.example.com`）：

- `GET /.well-known/oauth-protected-resource`
- `GET /.well-known/oauth-protected-resource/mcp`
- `GET /.well-known/oauth-authorization-server`

端点：`POST /register`（动态客户端注册）、`GET/POST /authorize`、`POST /token`、
`POST /revoke`。授权码 + PKCE(S256)，刷新令牌轮换；令牌只存 SHA-256。

多账号要点：

- 管理员在 `/admin/users` 建用户，设置 `health.read` / `health.write`
  scope 上限、工具策略、时间窗、`shared`/`own` 数据模式。
- `shared` 用户使用 owner 0 的 `health.db` 与小米凭据；`own` 用户使用
  `data/users/<id>.db` 与 `data/users/<id>.creds.json`。管理员始终能看全部分区。
- 工具权限四层交集：用户、OAuth 客户端、OAuth 令牌、静态 key。
  `NULL` = 不额外限制，`[]` = 全部拒绝。参数级规则也可叠加。
- 时间窗同样四层交集；窗外调用拒绝并写审计原因。
- 删除 `own` 用户时其分区归档到 `data/archive/`，可找回；`shared` 用户删除
  不影响 owner 0。
- 静态 Bearer 继续可用；`POST /ingest` **公网可达**，但只接受静态令牌，
  OAuth 令牌即使有 `health.write` 也 403。4M 上限，nginx 侧 `limit_req`
  限流；不要加“仅 LAN”ACL（edge/FRP 下源地址不可靠）。

`mcp.health.example.com` 只保留 `/mcp`（静态 Bearer）与 `/health`，不提供
OAuth/后台；样例 nginx 明确 404 掉这些路径。

## 6. nginx

参考 `deploy/nas/nginx-health-example.com.conf`：

- TLS 1.2/1.3，HTTP 301 到 HTTPS，HSTS。
- `/.well-known/`、OAuth 四端点、`/login`、`/login/otp`、`/logout`、
  `/admin*`、`/account*`、`/admin.css`、`/health`、`/mcp`。
- `/mcp` 关闭 `proxy_buffering`（SSE），1M 上限；`/ingest` 4M、公网可达、
  只认静态 Bearer、`limit_req` 限流、关闭 request buffering；表单 64k；
  其余 16k。`/ingest` 不加私网 allow/deny：edge 看到的不是手机 LAN 地址。
- `/authorize` 与 `/token` 关闭 access log。
- `proxy_set_header X-Real-IP $remote_addr`；应用不解析客户端自带的
  `X-Forwarded-For`。
- 应用侧 `HCB_TRUST_PROXY=1`；`HCB_TRUSTED_PROXIES` 必须是 nginx/网关的真实
  单地址或最小 CIDR（不是整个 `172.16.0.0/12`）。

应用本身发送 `Cache-Control: no-store`、CSP、`X-Frame-Options: DENY` 等；
nginx 不要覆盖这些头，也不要给 `/admin`、`/account`、`/login` 配缓存。

改 nginx 前先备份 vhost，改完：

```sh
sudo nginx -t && sudo nginx -s reload
```

## 7. 备份

### 代码/配置

`install.sh` 每次运行自动备份 `app/` 与 `docker-compose.yaml` 到
`backups/app-*`，保留最近 5 份。`.env` 不复制到备份（避免多份秘密）。

### 数据

`install.sh` **不备份** `data/`。需要一致备份时先停容器：

```sh
cd <deploy-dir>
sudo docker compose stop hc-health-mcp
sudo tar -C <deploy-dir> \
     -czf backups/data-$(date +%F-%H%M%S).tgz data
sudo docker compose start hc-health-mcp
```

`data/` 含 `health.db`、`users/*.db`、`creds.json`（小米凭据）与归档；
压缩包和目录权限保持 600/700，不要放进仓库或聊天。恢复时停容器、换回
`data/`、再启动，然后跑 `audit-verify`。

## 8. 回滚

`install.sh` 在 `compose up` 或冒烟失败时会自动恢复本次备份并重建容器。
手工回滚：

```sh
# 最近一份
sudo ./deploy/nas/install.sh --rollback

# 指定备份
sudo ./deploy/nas/install.sh --rollback <deploy-dir>/backups/app-20261102-120000-1234
```

恢复内容是 `app/` 与 `docker-compose.yaml`，然后 `docker compose up -d
--force-recreate` 并重跑 health/401 检查。新表留在数据库里不影响旧代码；
需要退回数据时用第 7 节的数据备份。

nginx 回滚：恢复改前的 vhost 备份，`nginx -t` 后 reload。

## 9. 安全注意事项

- `.env` 600；`data/` 700；`app/` 与 `hcb/` 700，代码文件 600；备份目录 700。
- 管理员密码至少 12 字符；启用 TOTP 并离线保存恢复码。
- 管理员会话默认 4 小时、普通用户 12 小时、preauth 5 分钟；可用
  `HCB_ADMIN_SESSION_TTL`、`HCB_USER_SESSION_TTL`、`HCB_PREAUTH_TTL` 调整。
- `HCB_TOTP_WINDOW` 默认 1（±30 秒）；`0` 只认当前步长。
- 所有 POST 同源检查 + CSRF；登录/注册/令牌/授权有内存限流，重启清零。
- `HCB_TRUSTED_PROXIES` 只写真实反代/网关地址；写宽网段会让同网段容器
  伪造 `X-Real-IP`。不确定时留空（只信回环）。
- `HCB_ADMIN_PASSWORD` 只用于首次引导；建好后从 `.env` 删除。
- 审计链只追加；`AUDIT_RETENTION_DAYS` 默认 90。定期跑 `audit-verify`
  并把数据库纳入备份。
- 不要把令牌、密码、Cookie、`creds.json`、恢复码贴进聊天、提交或日志。
- CLI 密码只走 stdin/`getpass`；不要用 `--password` 一类参数（本 CLI 不提供）。
- `/ingest` 公网可达，只认静态 Bearer（OAuth 403）；`/mcp` 的 `ingest`
  工具仍可用。不要用 LAN ACL，edge/FRP 拓扑下不成立。
- `mcp.health.example.com` 不启用 OAuth；只用于静态 Bearer。

## 10. 已知限制

- 单进程、单机 SQLite；无多副本/高可用。每个 `own` 用户一个分区文件。
- 限流是进程内内存桶，重启清零；不是全局限流。
- 数据备份/恢复是手工停机流程，没有 PITR。
- `install.sh` 只同步代码，不迁移/回填数据，不修改 `.env` 内容，不安装
  nginx 配置；会删除 `app/hcb/` 中源目录已不存在的 `.py`。
- `/ingest` 没有可靠的 LAN ACL：edge/FRP 下 nginx 看到的是转发地址，安全
  边界是应用层静态 Bearer 与 token 轮换；nginx 只做 4M + `limit_req`。
- `status` 的审计链检查会全表扫描；审计很大时会变慢。
- `AUDIT_RETENTION_DAYS`、会话/TOTP 环境变量在进程启动时读取，改后要重启。
- 没有 OIDC、第三方登录、多管理员；用户不能自助导入小米凭据；手机端仍用
  静态 Bearer，不做手机 OAuth。
- 本任务没有在真实 NAS/nginx/真实 MCP 客户端上验收；见 `ACCEPTANCE.md`。
