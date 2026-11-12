# OPERATIONS —— 出问题怎么办（自愈优先，终端只是备选）

设计原则：**能自愈的都在 app 里做**；需要人点的只有"授予权限"和"扫码"两件事，都从通知一键进入，不用连电脑、不用终端。

## 一、自愈矩阵（app 内）

| 故障 | app 自己怎么办 | 需要人做的 |
| --- | --- | --- |
| HC 权限被系统回收 | 同步时检出 → 写状态 + 发通知「HC 权限需要恢复」 | 点通知 → `FixActivity` 里点一次"允许" → 自动续跑 |
| 小米登录态失效（passToken 过期） | 每个周期正常时都会**回写轮换后的新 token**；真失效时发通知「小米账号需要重新登录」 | 点通知 → `LoginActivity` 显示二维码 → 用小米账号扫 → 自动保存并恢复 |
| WorkManager 被系统清掉 / 开机 / 应用升级 | `BootReceiver`（BOOT_COMPLETED / MY_PACKAGE_REPLACED）→ 重排周期任务 + 立刻补一次 | — |
| **小米运动健康自己没同步**（手环→云那段停了） | 每轮同步都判上游新鲜度（`logic/UpstreamFreshness`）：
  睡眠/心率/步数/血氧过期或云端整窗为空 → **主动催它**（bind 它的设备服务 + 发屏幕变化广播，
  全静默），25 秒后补拉云端，5 分钟后再补一次；连续催不动就发通知 | 点通知 → 打开小米运动健康
  （或点「唤醒」再催一次）；安卓不允许 app 强制别的 app 同步，这一步必须人点 |
| HC 权限被部分回收（如 `WRITE_SLEEP`） | 同步前按**全 35 项**自检（`logic/HcPermissions`）：心率 2 项缺 → 整轮不跑；
  其余缺 → 报 `权限缺 N 项：写睡眠、写步数…` + 6 小时一次通知，修好后自动补写 | 点通知 → `FixActivity` 一次把全集请回来 |
| **Google 说睡眠数据不足**（记录没分期） | 睡眠体检每轮扫最近 30 天：本应用写的“有起止、没分期”的记录
  **删旧写新、把分期补上**（`logic/SleepStages`，小米 `items[].state` → HC stage） | — |
| 进程被 ColorOS 冻结 | 进程复活即跑；周期任务自带退避重试；建议把 app 加电池白名单（已加：`deviceidle whitelist`） | — |
| 写入 HC 失败 | 指数退避（10 分钟起），连续 3 次失败发通知 | 看通知内容 |
| **夜里掉网 / DNS 不解析 / 超时** | 判为**环境**而不是失败（`logic/NetFailure`）：不累加 `fails`、不发通知，
  日志写「网络未就绪（本轮跳过）」，状态 `net-wait`，等下个周期自动续 | — |
| 停机多日/漏采 | 每次同步按 `lastTs` 向前回溯**至少 2 天**（云 API 对窄窗口会返回空），最长 30 天；历史大洞用回补 | 需要时点/发一次回补（见三） |

## 二、状态与日志（不用电脑）

**先确认跑的是哪个包**：设置页底部「关于」三行全是当地时间——`.5.3 / 构建 10-29 08:57 · 当地时间 Asia/Shanghai (UTC+08:00) / 首次安装 10-23 23:49 · 本次安装 10-29 08:57`；
不打开界面就看状态文件的 `app` 段：

```bash
python3 -c "import json;print(json.load(open('/sdcard/Download/hc-bridge/app-sync-status.json'))['app'])"
# {'version': '0.5.3', 'version_code': 7, 'build_time': '2026-10-29 08:57',
#  'installed_at': '2026-10-29 08:57', 'first_installed_at': '2026-10-23 23:49', 'zone': 'Asia/Shanghai (UTC+08:00)'}
```

```bash
# app 自报状态（Termux 或任何有存储访问的终端）
cat /sdcard/Download/hc-bridge/app-sync-status.json
#   state: ok / ok-no-new / ok-backfill <n>d / auth... / error... / no-account / 权限文案
#   顺带带 from/to/fetched，能直接看出"云里有没有新数据"
#   导出只留一份正本；若看到 "app-sync-status (N).json"，那是旧版本/重装前留下的，
#   新版会清掉自己能删的副本，读的时候按修改时间取最新即可。

# 读一次 HC 现状（可见 Activity，会短暂切前台）
am start -n id.harness.hcbridge/.MainActivity
cat /sdcard/Download/hc-bridge/hc-latest.json     # 含 heart_rate_diag_30d / _365d

# 无窗口催一次同步
am broadcast -n id.harness.hcbridge/.CmdReceiver -a id.harness.hcbridge.CMD_SYNC

# 主动唤醒小米运动健康（催上游 + 补拉云端，不等判定；静默不抢前台）
am broadcast -n id.harness.hcbridge/.CmdReceiver -a id.harness.hcbridge.CMD_KICK
# 看判定与催的结果：
#   cat /sdcard/Download/hc-bridge/app-sync-status.json   → `upstream` 块（stale / verdict / kick / kicks_today）

# 历史回补（无窗口，可重跑；幂等）
am broadcast -n id.harness.hcbridge/.CmdReceiver -a id.harness.hcbridge.CMD_BACKFILL --ei days 365

# 重排周期任务（无窗口）
am broadcast -n id.harness.hcbridge/.CmdReceiver -a id.harness.hcbridge.CMD_RESCHEDULE
```

## 三、判定顺序（区分"没数据"和"没同步"）

1. `app-sync-status.json` 的 `state`：
   - `ok*` → app 侧正常，继续第 2 步；
   - `权限*` → 点通知恢复权限；`auth*` → 点通知扫码；
   - `error*` → 看 `msg`（网络/接口变更）。
   - 先看 `upstream.stale`：非空 = **小米运动健康自己没同步**（不是 HC 也不是桥的问题），
     桥已经催过（看 `upstream.kick` / `kicks_today`）；`after` 是催完那一次补拉写了什么。
     手环没戴/在充电时也会这样，属于物理原因，点通知里的「唤醒」或打开小米运动健康即可。
2. 云端有没有比 HC 更新的点（Termux 侧）：
   `~/bin/hc-cloud.py probe 1` —— 或看 status 里 `fetched`/`ok-no-new`。
   - 云端也没有新点 → **手环侧没在测**（充电/离腕/没戴），不是链路问题。
   - 云端有新点、HC 没有 → 同步问题，回到第 1 步。
3. 物理限制：小米云是**周期性采样 + 成批上传**（实测采样间隔中位 10 分钟，云端上传再滞后 30–60 分钟）。所以 HC 里的心率"看起来落后一小时"通常属于正常；要秒级实时只能 BLE 直连（见 harness-scripts 的 mi-band-bridge 调研）。

## 四、Termux 侧脚本（备胎，不装也能跑）

唯一源：`example-user/toolkit` → `tools/hc-cloud/`（`./install.sh hc-cloud`）。

- `hc-cloud.py login-qr|probe|fetch`：二维码登录 / 探测云端 / 抓取（主链路在 app 内，这里用于排查与 bootstrap）
- `hc-bridge-bootstrap.sh`：把凭据导入 app（已有凭据时只重排+跑一次，不会覆盖 app 内已轮换的 token）
- `hc-hr-watchdog.sh`：每 2h 读 `app-sync-status.json`；权限类问题用 Shizuku(rish) 授回并重同步；仍不行就发通知
- `hc-hr-sync.sh`：旧的手动通路（Termux 自己抓 → app 写），仅兜底

## 五、一次性 grant（仅新装/清数据后需要）

```bash
for p in READ_STEPS READ_DISTANCE READ_TOTAL_CALORIES_BURNED READ_ACTIVE_CALORIES_BURNED \
         READ_HEART_RATE READ_EXERCISE READ_SLEEP READ_OXYGEN_SATURATION READ_RESPIRATORY_RATE \
         READ_WEIGHT WRITE_HEART_RATE; do
  adb shell pm grant id.harness.hcbridge android.permission.health.$p
done
adb shell pm grant id.harness.hcbridge android.permission.POST_NOTIFICATIONS
# 凭据导入（或直接在手机上点通知走 LoginActivity 扫码）
adb shell am broadcast -n id.harness.hcbridge/.CmdReceiver -a id.harness.hcbridge.CMD_BOOTSTRAP --es creds "$(cat ~/.config/hc-cloud/creds.json)"
```

## 六、两个绕不开的人工点（设计如此，但都只要一次点击）

1. **HC 权限**：Android 不允许应用给自己授权，所以恢复必须有一次"允许"点击 —— 通知点进去就是 `FixActivity`，只需点一下。
2. **小米账号**：失效后可以**扫码**（`LoginActivity` 里直接显示二维码），也可以点「账号密码登录（网页）」在 app 内 WebView 里用账号密码/短信验证码登录——登录成功后 `userId`/`passToken` 会自动回传保存。平时每周期回写轮换 token，正常不会触发。

> 手机锁屏时通知仍然会到，但**点击需要先解锁**（否则系统不让 Activity 起来）。这是 Android 的限制，不是 app 的问题。

## 八、状态文件“怎么不更新了”

`/sdcard/Download/hc-bridge/app-sync-status.json` 是导出的副本；**唯一可靠的是 app 私有那份**
（`files/app-sync-status.json`，debug 包里可 `shz "run-as id.harness.hcbridge cat files/app-sync-status.json"` 直接读）。

重装 app 后 UID 会变，老的那份导出文件本应用既删不掉也盖不掉 → MediaStore 只能写成
`app-sync-status (1).json`，而外面读的 `app-sync-status.json` 就永远停在旧时间。
app 现在会：① 在 app 日志里写「导出被改名：… → …」；② 把真实文件名放进状态 JSON 的 `export_name`；
③ 导出彻底失败时也写 app 日志（以前只写 logcat，外面看不见）。

一次性修回正本（需要 shell/Shizuku）：

```bash
shz "rm -f '/sdcard/Download/hc-bridge/app-sync-status.json' '/sdcard/Download/hc-bridge/app-sync-status (1).json'"
# 之后随便触一次同步，正本就会重新长出来（owner 变成当前 UID）
am broadcast -n id.harness.hcbridge/.CmdReceiver -a id.harness.hcbridge.CMD_SYNC
```

## 七、后台读 HC 需要单独一项权限（踩过）

`android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND` —— **缺它时后台读会抛**
`must be in foreground to call aggregate method`。写（insertRecords）不需要它，所以"同步一直正常、读却全空"就是这个原因。
新装/清数据后要一起授（见五）。

## 八、全部自动化都是无窗口的

| 想做的事 | 无窗口命令 |
| --- | --- |
| 催同步 | `am broadcast -n id.harness.hcbridge/.CmdReceiver -a id.harness.hcbridge.CMD_SYNC` |
| 读一次 HC → 写 `hc-latest.json` | `am broadcast -n id.harness.hcbridge/.CmdReceiver -a id.harness.hcbridge.CMD_DUMP` |
| 历史回补 | `… CMD_BACKFILL --ei days 365` |
| 重排周期任务 | `… CMD_RESCHEDULE` |

只有两种会开窗口：用户**点通知**（`FixActivity` / `LoginActivity`），或手动 `am start …MainActivity` 看读数页面。
## 自带 MCP 服务器（127.0.0.1:8765，默认要凭据）

app 前台的 `McpService` 暴露一个 **streamable-HTTP MCP**（JSON-RPC 2.0），**只绑定回环地址**（不出局域网）。
从 **0.8.0** 起它**默认要求 Bearer**（`requireAuth=true`）：没有凭据的 `POST /mcp` 一律 **401**，
响应带 `WWW-Authenticate: Bearer resource_metadata=…`，客户端据此走 OAuth 或改用静态 key。

凭据只有两种来源，**都在 app 内发**（「设置 → 系统与权限 → MCP 管理」，进页面先过管理员密码/屏幕锁）：
HTTP 上**没有任何管理路由**，`/admin`、`/admin/*` 一律 **404**。

### 1. 静态 key（本机最快）

「MCP 管理 → 令牌与静态 key → 新建静态 key」：明文**只显示一次**，库里只存 SHA-256。然后：

```json
{
  "mcpServers": {
    "hc-bridge": {
      "url": "http://127.0.0.1:8765/mcp",
      "headers": { "Authorization": "Bearer hcb_st_…" }
    }
  }
}
```

自检（`$HCB_KEY` = 那个只出现一次的明文）：

```bash
HCB_KEY=hcb_st_…
curl -s -X POST http://127.0.0.1:8765/mcp \
  -H "Authorization: Bearer $HCB_KEY" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
# 去掉 -H "Authorization" ⇒ 401（响应的 WWW-Authenticate 指向资源元数据）
```

每个 key 可以单独设「工具策略」与「时间窗」（`[]` = 一个工具都不许；跨午夜时段与 `until` 都支持），
保存后立即生效。

### 2. OAuth 2.1（支持动态注册的 MCP 客户端）

- 资源元数据：`GET http://127.0.0.1:8765/.well-known/oauth-protected-resource/mcp`
- 授权服务器元数据：`GET http://127.0.0.1:8765/.well-known/oauth-authorization-server`
- scope 只有 `health.read`（20 个读工具）与 `health.write`（16 个写工具）；`ingest` 是保留 scope，永不下发
- `GET /authorize` 只回一页「请在 app 内确认」；真正的批准在 app 的「MCP 管理 → 待确认请求」里
  （PC 浏览器打不开手机 app 时也一样，不依赖浏览器跳转）
- 访问令牌 1 小时、刷新令牌 30 天、刷新严格轮换（旧刷新令牌再用一次 ⇒ `invalid_grant` + 吊销整个 family）

### 本机免鉴权（不推荐，且要两个开关）

确实只想本机不带凭据：**同时**关掉「要求 Bearer」**并**打开「仅回环免鉴权」，否则仍然 401。
公网基址/隧道开着、或请求带 `CF-Connecting-IP` / `X-Forwarded-For` 等转发头时，这个豁免**永远无效**。
升级后首次进面板会有一条一次性提示（不自动打开豁免）。

工具（后台执行，不弹窗）：

| 工具 | 作用 |
| --- | --- |
| `hc_status` | HC 服务/权限、账号、上次写入、同步间隔、失败次数、最近状态 |
| `hc_dump` | 读 HC 现状（7 天汇总、每日步数、睡眠、心率 30d/365d、血氧、体重）返回 JSON |
| `hc_sync_now` | 立刻从小米云同步一次 |
| `hc_backfill` | 回补历史（`days`，默认 365，最大 730，幂等） |
| `hc_set_interval` | 设后台同步间隔（分钟，≥15） |
| `hc_upstream` | 上游新鲜度：`stale` / `verdict` / 上次催的结果 / 今日催了几次 / 小米运动健康装没装 |
| `hc_kick` | 立刻主动催一次小米运动健康同步（不等判定），后台执行，随后自动补拉云端到 HC |

启停（无窗口）：`am broadcast -n id.harness.hcbridge/.CmdReceiver -a id.harness.hcbridge.CMD_MCP_START` / `…CMD_MCP_STOP`；
开机由 `BootReceiver` 自动拉起。自检（**不带凭据现在返回 401**）：

```bash
curl -s -X POST http://127.0.0.1:8765/mcp \
  -H "Authorization: Bearer $HCB_KEY" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

### 公网入口（可选：Cloudflare Tunnel）

app **不打包、也不启动** `cloudflared`：它只做「存配置 / 拼命令 / 探活」三件事。公网转发由 Termux 里的
`cloudflared` 从公网域名落到 `127.0.0.1:8765`（Termux：`pkg install cloudflared`，或从 Cloudflare 官方包装）。

- **quick tunnel**（临时域名，进程重启就换）：

  ```bash
  cloudflared tunnel --url http://127.0.0.1:8765 --no-autoupdate
  ```

  把输出里的 `https://<随机>.trycloudflare.com` 粘进「MCP 管理 → 公网地址与隧道 → 公网基址」再保存。

- **named tunnel**（固定域名，需要 tunnel token）：**只认 token**。别写成
  `cloudflared tunnel run <隧道名>` —— 那会去读手机上根本不存在的 `cert.pem`：

  ```bash
  cloudflared tunnel run --token "$TUNNEL_TOKEN"
  ```

  在管理页存过 token 后可用「复制完整命令」；界面、日志、审计都**不回显 token**（只显示「已保存（不可读）」），
  含 token 的剪贴板内容**会尝试**在 60 秒后清除（best effort：系统可能拒绝后台读剪贴板，**以系统行为为准**；
  所以别把这条当保证，用完自己复制点别的东西覆盖它）。

规则：

- 公网基址必须是**裸 `https://host[:port]`**（不带路径、查询、尾斜杠）。写坏 ⇒ 服务退回本机 issuer，
  同时强制鉴权（不会因为"地址无效"顺手放松）。
- 只要**公网基址存在**或**隧道模式不是 `off`**：`requireAuth` 强制为 `true`（管理页里关不掉），
  回环免鉴权同时失效，空闲自动停服也关掉（公网要常驻）。
- 换公网地址 = 换 issuer：旧访问令牌/刷新令牌全部失配，客户端要重新授权。
- **管理页只在 app 内**：HTTP 上没有任何 `/admin*` 路由（一律 404）。隧道只该转发
  `/mcp`、`/.well-known/*`、`/authorize`、`/token`、`/register`、`/revoke`、`/health`。
- 连通检测（管理页「连通检测」按钮）：经公网 `GET /health` ＋ 元数据 `resource` 必须等于
  `<公网基址>/mcp`，两步都过才算可达。检测在后台线程跑，主线程不发网络请求。

## 面板（人用的 UI）

打开 app 即控制台：状态（HC 权限 / 账号 / 上次写入 / 同步间隔）、操作按钮（立即同步、读取 HC、回补 90/365 天、
恢复权限、重新登录）、间隔设置（15/30/60）、最近 7 天步数柱状图、最近状态文本。
按钮走的都是后台 Worker，不会额外弹窗。
## app 内日志

- **面板**：控制台底部「app 内日志」区，显示最近 60 行（时间 / 模块 / 内容），可选中复制，带「刷新日志」「清空日志」。
- **MCP**：`hc_logs`（`lines`，默认 80，上限 300）——Termux 上的 pi 直接：
  ```bash
  curl -s -X POST http://127.0.0.1:8765/mcp \
    -H "Authorization: Bearer $HCB_KEY" -H 'Content-Type: application/json' \
    -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hc_logs","arguments":{"lines":20}}}'
  ```
- **记录的内容**：同步开始/无新采样/成功写入/失败原因/登录态失效/回补完成、每次广播命令、MCP 每次工具调用、
  权限恢复、扫码结果、面板上的操作（回补、改间隔）。
- **落在哪**：app 私有 SharedPreferences（`hc_bridge_log`），环形缓冲 300 行，不出设备。
## 健康数据 MCP 工具（对齐社区项目命名）

app 的 MCP 服务器（`127.0.0.1:8765/mcp`）现有 **36 个工具**（`health.read` 20 个 + `health.write` 16 个），
其中一半是"看健康数据"；命名与返回形状对齐 `binglua/mi-fitness-mcp-cn`，习惯可直接迁移。
`tools/list` 只列出**当前凭据的 scope ∩ 策略**允许的工具（空策略 `[]` ⇒ 一个都不列）。

| 工具 | 参数 | 说明 |
| --- | --- | --- |
| `get_connection_status` | — | 同 `hc_status` |
| `get_profile` | — | 小米账号 userId、平台/机型、HC 可用性 |
| `get_daily_summary` | `date?` | 某天步数/距离/卡路里/心率/睡眠 |
| `query_metric_series` | `metric,start_date,end_date,granularity` | 指标序列（steps/distance_km/distance_m/active_kcal/total_kcal/heart_rate_avg/weight_kg/spo2；day/week/month） |
| `query_heart_rate` | `start_date?,end_date?,limit` | 心率采样（默认最近 1 天，limit 默认 500） |
| `query_sleep` | `start_date?,end_date?,include_naps,dedupe` | 睡眠会话（时长/标题/来源）；默认把“同一段被写两遍”收敛成一条，`duplicates` 报收敛了多少 |
| `query_workouts` | `start_date?,end_date?,min_duration` | 运动记录（类型/时长） |
| `query_body_measurements` | `start_date?,end_date?` | 体重记录 + 最新一条 |
| `query_spo2` | `start_date?,end_date?,limit` | 血氧采样 |
| `get_data_coverage` | — | 各类型在 HC 的首末时间与条数（心率 cap 20 万） |
| `sync_data` | `days?` | 触发同步（`days>0` 则回补 N 天） |

运维向：`hc_status` `hc_dump` `hc_sync_now` `hc_backfill` `hc_logs` `hc_set_interval`
`get_sync_history`（最近 10 次同步结果）`export_logs`（导出 `/sdcard/Download/hc-bridge/logs-<ts>.txt`）。

**已知不覆盖**（HC 里本来就没有对应 record）：静息心率、压力、异常心率事件、睡眠分期；
`MI Fitness` 的血糖/血压要等设备写入 HC 才有。体重目前为空（没有记录来源）。
## 导出 CSV（`hc_export_csv`）

任意区间导出到 `/sdcard/Download/hc-bridge/export-<types>-<start>_<end>.csv`，可直接丢 Excel / pandas / 别的 app。

```
tools/call hc_export_csv
{ "types": ["heart_rate","steps","sleep"], "start_date": "2026-10-15", "end_date": "2026-10-18" }
→ { "ok": true, "path": "…/export-heart_rate-steps-sleep-2026-10-15_2026-10-19.csv",
    "bytes": 52893, "rows": { "heart_rate": 963, "steps": 5, "sleep": 4 } }
```

`types` 可用：`heart_rate`（一行一个采样：`at,epoch,bpm,origin`）、`steps`（按天：`date,steps,distance_km,calories_kcal,active_kcal`）、
`sleep`（`start,end,minutes,title,origin`）、`workouts`（`start,end,minutes,type,title,origin`）、
`spo2`（`at,percent,origin`）、`weight`（`at,kg,origin`）。CSV 内按类型分段（`== type ==`）。

面板上也有「导出 CSV（7天）」按钮（心率+步数+睡眠）。

## 九、为什么 HD 里"数据看起来少"（2026-10-18 实测）

- 写入权限没问题：心率已写入 **34,224 条记录 / 251,518 采样**（origin `id.harness.hcbridge`）。
- 少的不是心率，而是**其它类型的历史**：小米运动健康只写最近一段，历史在云里。现已补：
  `sleep`（10 → 113 段，近 90 天）、`spo2`（0 → 5000+）、`steps`（**只补 HC 当天为 0 的日子**，避免与小米自身步数重复计入）。
- 依旧没有的：`weight`（无数据来源）；`resting HR / stress / 睡眠分期`（HC 无对应 record）。
- MCP 服务注意：Android 14+ 不允许从后台广播启动 dataSync 型前台服务 ⇒ **打开一次 app 面板**即可拉起（BootReceiver 仍会尝试）。

### 九·补：睡眠重复写入（2026-10-21 修复，已验证）
`sleep` 搬进来时少了一道护栏（步数有、睡眠没有）：小米运动健康**自己就把睡眠写 HC**，
桥再从云端搬一份 ⇒ 每晩在 HC 里是**两条完全重叠的记录**。取证（`query_sleep` 带 `origin`）：
10-10…10-21 共 34 条 = 17 对「同段双来源」；另外云端修订起床时间时
（10-19 的 07:22→10:35→11:24），`clientRecordId` 按 bedtime 秒算 ⇒ 同一晩又堆出更多条。

规则（`logic/SleepMerge`，纯函数 + 15 项单测）与实现（`HcWriter.reconcileSleep`）：

| 情形 | 处理 |
| --- | --- |
| 别的来源（如 `com.mi.health`）已写过同一段（重叠 > 较短那段 50%） | **不写**，并删我们自己过去写的同区间记录 |
| 我们自己写过、起止一致（差 ≤ 120s） | 不重复写 |
| 我们自己写过、区间变了（云端修订） | **删旧写新**，同一晩只留一条 |
| 夜间睡眠 + 午睡（不重叠） | 两段都写 |
| 那几天小米没写（10-05…10-11 这种空白日） | 照旧补上，不算重复 |

**删的是 HC 的平台记录 id（`metadata.id`）**，不是 `clientRecordId`：实测我们自己写进去的记录
读回来 `clientRecordId` 是空串（小米那条倒是有），拿它删命中 0 条。

一次性清理历史（无窗口，不需要小米账号，只读/删 HC）：

```bash
am broadcast -n id.harness.hcbridge/.CmdReceiver -a id.harness.hcbridge.CMD_SLEEP_CLEANUP --ei days 365
# 或 MCP：hc_sleep_cleanup {"days":365}
# 结果：日志 `[sleep-cleanup] … 删除 N 条重复睡眠` + 状态文件 state=ok-sleep-cleanup
```

phone 实测（2026-10-21 09:36）：删 8 条，10-12…10-21 每晩只剩小米那一条；
保留的 8 条 own 记录全落在小米没有数据的 10-05…10-11。之后每次同步 `sleep=+0/-0(小米已有N)`。

> 小米自己也有多条的（10-19 三条、10-20 两条）—— 那是小米的睡眠分期写入，**不是我们的，也不该删**；
> 图表侧由 `SleepMath.dedupe` 按区间重叠去重显示。

**它自己那几条删不掉，所以读的时候收敛（2026-10-21 补）**：HC 的 `deleteRecords` 只能删**本应用写的**
记录，小米/Google Fit 的重复删除不了（也不能通过 adb/Shizuku 删）。所以查询层默认去重：

```bash
# 默认 dedupe=true：重叠 >50% 只留最长那条，收敛条数以 duplicates 报出来（不默默藏起来）
query_sleep {"start_date":"2026-10-10","end_date":"2026-10-21"}
# → count 13 / raw_count 18 / duplicates 5（那 5 条全是小米与 Google Fit 自己的）
query_sleep {"...","dedupe":false}   # 要看原始数据时
```

顺带修了 `get_daily_summary` 的睡眠时长：旧实现读 `s..e+1day` 且直接求和 ⇒ 一晚被算两次
（10-19 实测报 **650** 分钟，实际 291），小米一晚多条时再翻一倍。现在只取与当天有交集的会话、
裁到当天、先去重再求和，归到**起床那天**（与 NAS MCP 口径一致）：10-19=291 / 10-20=359 / 10-21=190。

### 九·补二：机内「只留一份」与「hc-bridge 完整」怎么同时成立（2026-10-21）

HC 的 `deleteRecords` 只能删本应用写的记录 ⇒ 物理上**只能删我们那份、留小米那份**（反过来不行，adb/Shizuku 也不行）。
所以现在的取舍是：**机内每晚安一条（优先小米写的），我们只在小米没写的日子补**。

那“hc-bridge 自己还完整吗”——靠**空夜自愈**兜住，不看一份记录归谁：

- 每次同步扫 HC 最近 30 天的睡眠，只看“这一晚有没有记录”（`SleepMerge.missingNights`，按**起床那天**归属）。
- 有空夜就从云端补回（`reconcileSleep` 写入，小米有就不写）。6 小时一次，不每轮都扫。
- 于是小米那份若被它自己/系统删掉、或小米停写，15 分钟内我们会把它补回来 → hc-bridge 永远不缺数据。
- 立即体检：`am broadcast -n id.harness.hcbridge/.CmdReceiver -a id.harness.hcbridge.CMD_SLEEP_GAP --ei days 30`
  或 MCP `hc_sleep_gap_check`；日志 `[sleep-gap] 体检 30d：库内 41 段，空夜 0 个`。
- 实测（phone 2026-10-21 12:00）：30 天空夜 0；同步日志 `sleep=+0/-0(小米已有2)` —— 一条不多、一条不缺。

> 如果一定要从 HC 里**物理删掉**小米那几条：只能人工「Health Connect 设置 → 应用权限 → 小米运动健康 →
> 删除睡眠数据」（会连它全部睡眠记录一起删，而且它每晚还会再写多条）—— 不建议，收益只是“库里好看”。

## 十、后台保活与功耗（实测 2026-10-18 18:49）

| 项 | 现状 |
| --- | --- |
| 电池优化豁免 | **已开**（`deviceidle whitelist` 命中 `id.harness.hcbridge`；面板可一键再申请） |
| 周期同步 | WorkManager，每 15/30/60 分钟（面板可改），persisted（重启仍在） |
| 空转省电 | 距上次成功写入 **< 10 分钟直接跳过整轮**，不发网络请求 |
| MCP 服务 | **按需启动**：打开面板时起；**空闲 10 分钟自动停**（不留常驻进程与通知） |
| 内存实测 | MCP 在前台时 `TOTAL PSS ≈ 75 MB`（Java 31 / Native 19），服务停后被系统回收 |
| 开机 | `BootReceiver` 重排 WorkManager + 尝试拉起 MCP（Android 14+ 可能拒绝 FGS，属系统限制） |

pi 侧取数建议：无窗口用 `CMD_DUMP` + 读 `hc-latest.json`；需要 MCP 工具时先 `am start -n id.harness.hcbridge/.MainActivity`（一次即可，之后 10 分钟内可用）。

## 十·补：NAS 侧 hc-health-mcp 的两个真 bug + 新工具（2026-10-21，已验证）

**① 睡眠 `minutes` 字段是错的（口径混用）**。写入用的是云端的 `sleep_duration`，而它有时不是
「本次睡眠的分钟数」：实测 2026-08-20 一段 20:42→21:19 的 37 分钟小睡带着 `sleep_duration=420`，
同夜的 00:13→07:33（440 分钟）才对得上（云端另有 `duration` 字段=440）。⇒ `query_sleep` 会返回
「起止 37 分钟、minutes 420」这种自相矛盾的记录。

修法：**时长只信起止**（`minutes = (wake-bed)//60`），写入时算、读时也算（历史行也不再自相矛盾）；
顺手把库里 70 行历史 `minutes` 按起止改对了。

**② `get_daily_summary` 的睡眠时长会翻倍**。旧实现 `SUM(minutes) WHERE bed BETWEEN a+1d AND b+1d`：
既用了错口径的 minutes，又把傍晚开始、次日醒的那段整段算进"当天"。改成：取与当天**有交集**的会话、
裁到当天、先去重（重叠 >50% 只留最长）再求和，归到**起床那天**（与手机 HC 口径一致）。
实测 10-19=291 / 10-20=359，与手机侧一致。

**新工具**（MCP）：

| 工具 | 作用 |
| --- | --- |
| `backfill {days, wait}` | 历史回补（默认 365 天，最大 730），7 天切片、默认后台跑（进度看 `health_status.backfill`）；幂等可重跑 |
| `sleep_audit {days, fix}` | 睡眠体检：重叠重复段 / `minutes` 与起止不一致 / 缺夜（按起床那天）；`fix=true` 修 minutes 并从云端补缺夜 |
| `query_sleep {dedupe}` | 默认把重叠 >50% 的段收敛成一条，并报 `raw_count` / `duplicates` |

**为什么切片是 7 天**：云端历史数据的采样很密（2025-10 时一天 1300+ 个心率点），30 天切片一次要
拉 4 万条、单请求能跑 40+ 秒；7 天切片内存/耗时都稳。另加两层韧性：单请求失败重试 2 次
（NAS→hlth.io.mi.com 偶发 TLS 握手超时，要 30s 才抛）、整片失败退避 3/8/20s 并在整轮末尾补漏一轮。

**实测（NAS 上另起一个 18928 实例，用库副本验证）**：`backfill 90` → 心率 10,026→14,983、
血氧 7,191→10,575、步数 5,613→9,032、睡眠 70→105 段（首个 2026-07-21），全程 0 重复；
`sleep_audit fix` → minutes 不一致 0 条、缺夜补齐 2 段。

## 十·补五：把云端体重/体脂补进 HC（2026-10-21 实测踩坑）

**结论：HC 里现在有 29 条体重**（28 条云端体称 + 1 条 Google Fit 自己写的）、**24 条体脂**，最早 2025-01-16。

三个坑，缺一个都会"看起来跑成功了但数据没进去"：

1. **必须同时有 `READ_BODY_FAT`**。`insertBodyFat` 要先读已有记录做 ±60s 去重，
   只有 `WRITE_BODY_FAT` 时 HC 直接抛
   `SecurityException: Caller does not have permission to read data for (BodyFatRecord) from other applications`，
   而且异常在 runCatching 里被 `if (msg.length < 100)` 吞掉 ⇒ 日志上只看到"同步成功"。
   现在：读写都声明，且体重块**无论 0 条还是异常都汇报**（`weight=写入/云端`、`weightErr=` 不再吞）。
2. **小米云对单次查询的时间跨度有上限**：一次要 1825 天只回最近一年（实测 8 条）。
   必须切窗（现用 180 天，`CMD_WEIGHT_IMPORT` 内部循环）才能拿全 28 条。
3. **体重是稀疏数据**：5 年只有 28 次体称，按天/按 30 天切片跑常常 0 条命中 ⇒ 需要专用入口：
   MCP `hc_weight_import {days}` / 广播 `CMD_WEIGHT_IMPORT --ei days 1825`。
   它会一次拉全量、写 HC，并把结果写进状态文件（`ok-weight-import`）。

另：`query_body_measurements` 的默认窗口只有约 1 年，想核对全历史要显式给
`start_date`/`end_date`（否则会误以为"只写进 8 条"）。

## 十·补九：OOM 崩溃修复——coverage 不许整表读心率（2026-10-22 早）

**现象**：2026-10-22 07:29 与 07:48，app 被系统杀掉两次（用户感受是"卡死"），
crash buffer 里是 `java.lang.OutOfMemoryError ... Failed to allocate a 32 byte allocation`，
栈顶在 `HeartRateRecordInternal.toExternalRecord` ⇒ **读心率记录时爆 256MB 堆**。

**根因**：`HcQuery.coverage()` 用 `readAll<HeartRateRecord>(cap = 200000)` 扫 10 年。
以前 HC 里心率才 3 万多条（还好），**回补 5 年后变成 124 万心率采样 / 11 万血氧**，
一次性转成 record 对象必爆。覆盖率是给界面看的诊断信息，不值得这么读。

**修法**（`HcQuery.coverage` 重写）：
- 首/末时间：各取**一页 1 条**（`pageOf` 升序最早 / 降序最新），不整表读；
- 条数：只数**最近 30 天**（有界，≈2.8k 条），字段改名 `records_30d`，不再假装是总数；
- 血氧同样处理；睡眠/运动/体重/体脂/呼吸率/HRV 量级小（1.5k/168/30/24/712/89），整表读无害；
- 界面文案跟着改：数据页副文「近 30 天心率 N 条」、心率详情「HC 近 30 天 N 条记录」。

**验证**：连续 4 轮「force-stop → 启动 → 切数据页」，crash buffer 无新 OOM；
`get_data_coverage` 返回心率 first `2021-10-22 22:00` / last 当天 / `records_30d=2825`，
血氧 `records_30d=4636`，其余类型正常。

**教训**：凡是"扫全历史"的读法，碰到**高频采样类型**（心率、血氧）必须改成
「取边界一条 + 限窗统计」；`cap` 不是内存保护，`readAll` 会把中间结果全留在内存里。

## 十·补八：手动「立即同步」= 强制拉取（2026-10-21）

App 里的三个手动入口——概览页「立即同步」、顶栏刷新图标、MCP `hc_sync_now`——都走
`SyncWorker.runNow(ctx, force = true)`，会真的去小米云拉一次。

修掉的问题：以前 `runNow()` 不带 force，而 worker 里有「距上次写入 < 600 秒就省电跳过」的判定
（给 15 分钟周期任务用的），于是**刚同步完再点按钮，日志只有一句「跳过」**，看着像按钮坏了。
现在手动入口一律 `force=true` 跳过该判定；周期任务仍然带省电判定不变。

实测（2026-10-21 21:58）：连续点两次（间隔 10 秒），两次都真跑：
```
21:58:47 [sync] 开始（上次写入 …，间隔 15min）
21:58:49 [sync] 无新采样（拉回 317 条，last=…）
21:58:57 [sync] 开始（上次写入 …，间隔 15min）   ← 第二次不再被跳过
```

注意语义区别：
- 「立即同步」= 常规增量同步（心率 + 睡眠/血氧/步数缺口/体重/推送到 NAS），**不做历史回补**；
  要回补历史用设置页的 90/365 天按钮或 `CMD_BACKFILL` / MCP `hc_backfill`。
- 首次或长时间未同步时，`from` 会至少回溯 2 天（小米云对窄窗口返回空）。

## 十·补七：HC 能传的指标补齐（2026-10-21 盘点）

盘点原则：**HC 支持的 record 类型 × 小米云里真有值的** ⇒ 全部写入；没有数据来源的不硬造。

本轮新写入 6 类（以前一直是空的，因为只写了体重/体脂）：

| HC record | 小米来源字段 | 实测条数 | 说明 |
| --- | --- | --- | --- |
| `BasalMetabolicRateRecord` | `basal_metabolism`（1571 kcal/日） | 26 | |
| `BoneMassRecord` | `bone_mass`（3.0 kg） | 24 | |
| `LeanBodyMassRecord` | `fat_free_body`（55.6 kg） | 24 | 去脂体重 |
| `BodyWaterMassRecord` | `body_moisture_mass`（40.2 kg） | 24 | |
| `HeightRecord` | 由 `(kg, bmi)` 反推 1.80 m | 29 | HC 自身没身高记录 |
| `RespiratoryRateRecord` | sleep 的 `avg_breath` | **712**（= 有该字段的夜数） | |
| `HeartRateVariabilityRmssdRecord` | sleep 的 `avg_hrv` / `hrv_median` | **89**（= 有该字段的夜数） | 小米只有 2026-07 之后有 HRV |

入口：体重相关的走 `hc_weight_import`（1825 天切窗），睡眠衍生走新工具
`hc_sleep_extras {days}` / 广播 `CMD_SLEEP_EXTRAS`（180 天切窗）。两个入口都幂等（同 ts±60s 跳过）。

### 仍为空的 HC 类型（**没有数据来源，不硬造**）
血糖、血压、体温/基础体温、皮温、爬楼层 `FloorsClimbed`、爬升 `ElevationGained`、速度 `Speed`、
饮水 `Hydration`、营养 `Nutrition`、正念 `Mindfulness`、月经相关、排卵、宫颈粘液、性行为、VO2max、
功率 `Power`、轮椅推进行、静息心率 `RestingHeartRate`。
（静息心率：小米只给睡眠期间的 avg/min/max 心率，直接当 RHR 是编数据，故不写。）

### 已由别的 app 写满、我们不重复写
步数/距离/睡眠/血氧/心率/活动卡路里（小米写）、运动记录（Google Fit 60 条 + 小米 1 条）、
总卡路里 `TotalCaloriesBurned`（5,017 条，Google Fit 写）。

## 十·补六：工具补齐（体成分 / 睡眠分期 / 指标序列），2026-10-21

原则：**每一类数据在「手机 hcb」和「NAS server」两边都有对称的查询入口**，口径一致（睡眠归起床日、去重、
步数多来源取平均、支持 source 过滤）。补完后的清单：

| 能力 | 手机 hcb（127.0.0.1:8765） | NAS server（health.example.com/mcp） |
| --- | --- | --- |
| 日汇总 | `get_daily_summary` | `get_daily_summary` |
| 指标序列 | `query_metric_series`（steps/distance/active_kcal/total_kcal/hr_avg/weight_kg/body_fat_pct/sleep_minutes） | `query_metric_series`（steps/distance_km/heart_rate_avg/spo2_avg/stress_avg/active_kcal/sleep_minutes/sleep_hours/weight_kg/bmi/body_fat_pct） |
| 心率 | `query_heart_rate` | `query_heart_rate`（可 source 过滤） |
| 血氧 | `query_spo2` | `query_spo2` |
| 睡眠 | `query_sleep` + `query_sleep_stages`（HC stages） | `query_sleep` + `query_sleep_stages`（raw 复原）+ `sleep_audit` |
| 运动 | `query_workouts` | `query_workouts` |
| **体成分** | `query_body_composition`（体重+体脂+BMI+统计） | `query_body_composition`（含基础代谢/体水分/体骨量/体得分） |
| 体重（旧） | `query_body_measurements` | `query_weight` |
| 压力 | —（HC 无该类型） | `query_stress` |
| 覆盖率 | `get_data_coverage`（含 body_fat/active_calories/distance） | `get_data_coverage` + `sources` |
| 原始数据 | —（HC 即原始） | `query_raw` |
| 写/改 | `hc_weight_import`、`hc_backfill`、`hc_activity_cleanup`、`hc_sleep_cleanup`、`hc_sleep_gap_check`、`hc_steps_breakdown` | `ingest`、`backfill`、`sync_now` |

要点：
- **BMI**：HC 没有 BMI 记录类型，也没有 HeightRecord ⇒ 用小米体称 payload 的 `(kg, bmi)` 反推身高
  `h=sqrt(kg/bmi)`（实测 1.80m），存 `Prefs.heightM`，BMI = kg/h²。server 端直接用 payload 里的 `bmi`。
- **睡眠分期口径**：HC 的 `SleepSessionRecord.stages` 常量是
  `0=UNKNOWN 1=AWAKE 2=SLEEPING 3=OUT_OF_BED 4=LIGHT 5=DEEP 6=REM`（**4 才是浅睡，5 深睡，6 REM**——
  一开始按 2/3/4 猜错过，和 server 的 10-20/10-21 数据一对照才定下来）。
  server 端从小米 raw 的 `items.state` 复原，两边实测一致（10-20：深 48 / 浅 237 / REM 43 / 清醒 31）。
- **`rng()` 修 bug**：以前只有个别工具认 `days`，多数工具传 `days` 不生效（默认 30 天）。
  现在 `rng()` 统一支持 `days`，所有 query_* 都生效。
- **覆盖率 cap**：血氧 5 年 11 万条，`cap=5000` 会让覆盖率看起来只到 2023-09，已提到 20 万。

## 十·补四：server 库结构 = 多来源 + 完整 payload（2026-10-21）

**口径（业主定）**：server 要能存**多来源**数据、能**区分来源**；每次拉取把**完整 payload**原样落库，
以后加字段从本地重算，不再打小米云。

### 表（每张都带 `source`，**主键含 source** ⇒ 同一时刻不同来源各存一行，不互相覆盖）

| 表 | 列 | 行数（2026-10-21 实测） |
| --- | --- | --- |
| `heart_rate` | ts, bpm, source | 1,243,565 |
| `spo2` | ts, pct, source | 112,899 |
| `steps` | ts, steps, distance_m, source | 409,241 |
| `calories` | ts, kcal, source | 466,870 |
| `stress` | ts, stress, source | 107,640 |
| `sleep` | bed, wake, minutes, deep_min, source | 1,627 |
| `weight` | ts, kg, origin, bmi, fat_rate, muscle_kg, body_age, source | 28 |
| `workout` | start, end, minutes, exercise_type, title, origin, source | 16 |
| `raw` | key, ts, value（**完整 item JSON**） | 1,552,146 |
| `sync_state` | key, value（last_sync / backfill_state / last_ingest） | 4 |

- `source`：云拉的统一 `xiaomi-cloud`；手机推的用 item 里的 `origin/source`（实测 `com.google.android.apps.fitness`）。
- 库体积 30 MB → **387 MB**，涨的是 `raw`（120 万条心率 × 完整 JSON）。需要瘦身时可只保留近 N 天 raw。
- 老库升级：`migrate_multi_source()` 自动重建表并把老数据标成 `xiaomi-cloud`（只跑一次，靠 PRAGMA 判主键）。
- 新工具：`sources`（各表按来源的条数/时间范围）、`query_raw`（看原始 payload）；`query_*` 都支持 `source` 过滤。
- **踩坑**：迁移把 `workout` 也标成 `xiaomi-cloud`，实际是手机推的，已按 `origin` 纠正。

### hcb 步数口径：多来源取平均
设备 HC 里有小米 + Google Fit 都在写步数，`COUNT_TOTAL` 是"并集覆盖"⇒ 重复计算
（10-14：云端 6,431 / 聚合 10,314；10-17 三个来源合计 34,670 / 聚合 13,092）。
业主定：**hcb 的步数取各来源平均值**（不是相加、不是取最大）。
`HcQuery.dailyStepsAvg` 已在 `dailySummary` / `stepsToday` / `metricSeries` 三处统一；
`com.android.healthconnect.phone.*`（HC 系统模块迁移副本，不是独立测量）不参与平均。
实测：10-14 = **8,373**（(10,315+6,431)/2）、10-17 = **11,557**（(18,001+8,371)/2）。
诊断工具：MCP `hc_steps_breakdown {date}` 给出每天按来源拆分 + 聚合值 + 平均值。

## 十·补三：HC 的「读 30 天以上历史」权限 + 步数/卡路里去重（2026-10-21）

**踩到的坑**：`query_metric_series` 里 9 月的步数/卡路里/睡眠全是 0，但云端明明有。根因不是写入，
而是**缺 `READ_HEALTH_DATA_HISTORY`** —— Health Connect 没有它时只允许读**最近 30 天**，
更早的数据一律读成空。后果有两层：
1. 写进去的东西"看不见"（09-15 步数报 0，实际写了 5858，云端 5859）；
2. 更糟：`HC 当天为 0 才补`的判据在 30 天外**永远成立** ⇒ 在小米已经写过的日子又补了一份（真重复）。

修法：manifest 声明 + `pm grant READ_HEALTH_DATA_HISTORY`（现在 20 项权限全给），
之后 `steps` 有值天数 34/100 → **99/100**。

**顺带修**：日汇总的睡眠没算跨零点的会话（HC 的 `TimeRangeFilter.between` 不会把
「前一天 23:51 开始、今天 07:31 结束」算进今天）⇒ 读窗口往前放 1 天再裁剪。
09-15 睡眠 0 → **451 分钟**（与云端一致）。

**新增去重**：`CMD_ACTIVITY_CLEANUP --ei days 365` / MCP `hc_activity_cleanup`
——同一天只要有**别的来源**的步数/活动卡路里记录，就删掉我们那天的记录（不碰别人的数据）。
phone 实测：删步数 **2,656** 条 / 卡路里 **1,532** 条（涉及 201 个重复天）；
之后活动卡路里与云端逐日一致（10-12 279 / 10-14 369 / 10-17 530）。
步数仍比云端高（设备 7516 vs 云端 4750）——这**不是重复**，是小米本地计步比云端聚合更细。

**公网 `/ingest` 已开通**：edge（edge-host，只能从 host-b 用 `id_ed25519_edge-host` 登 edge-host 进）
`/etc/nginx/sites-available/health.example.com.conf` 加了 `location = /ingest`（同 FRP 端口 18650）。
实测：无 token 401、带 token 200 并落库。手机 `HcPusher` 现在**优先直连 `/ingest`**，
失败自动回落到 MCP 的 `ingest` 工具（两条路都验过）。

**2026-11-02 OAuth/管理后台（代码已实现，按 `deploy/nas/README.md` 部署）**：

- `health.example.com` 新增 `/.well-known/*`、`/authorize`、`/token`、`/register`、`/revoke`、
  `/login`、`/login/otp`、`/logout`、`/admin*`、`/account*`、`/admin.css`。
  `mcp.health.example.com` 仍只保留 `/mcp` 与 `/health`，OAuth/后台路径 404。
- `/ingest` **保持公网可达**，只认静态 Bearer；OAuth 令牌即使有 `health.write` 也 403。
  4M 上限，nginx `limit_req` 限流。**不要加 LAN/私网 allow 列表**：edge/edge-host + FRP 拓扑下
  nginx 看到的来源不是手机 LAN 地址，ACL 不成立；安全边界是静态 token 轮换。
- 部署用 `deploy/nas/install.sh`：备份并校验 `app/` + `MANIFEST` → 同步
  `server.py`/`hcb_admin.py`/`hcb/`（删除废弃模块）→ `docker compose up -d --force-recreate`
  → `/health` + 无 token `/mcp` 401；失败自动回滚。
- 管理员找回/诊断用 `python /app/hcb_admin.py`：`bootstrap`、`reset-password`
  （可 `--revoke-static`）、`reset-totp --yes`（同时撤管理员会话/OAuth/授权码/preauth）、
  `revoke-sessions`、`audit-verify`、`status`。宿主机可用 `--db` 先 bootstrap，
  避免首次没有容器时的循环。

## 十·补二：双向同步——server ⇄ 设备（2026-10-21）

先说清两条链路各自的方向（之前文档里那句"手机 HC 独有数据由手机推送"只是设想，代码里没有）：

| 方向 | 机制 | 哪些数据 | 状态 |
| --- | --- | --- | --- |
| 云端 → 手机 HC | `SyncWorker` 拉小米云写本机 HC | 心率/睡眠/血氧/步数/活动卡路里 | ✅ |
| 云端 → NAS | NAS 自己的 `fetch_loop`（**与手机无关**） | 同上 5 类 | ✅ |
| NAS → 设备 | 副机 `SecondarySync`（拉 NAS 的 `query_*` 写本机 HC） | 同上 | ✅ |
| 设备 → NAS | `HcPusher`（MCP 工具 `ingest`，`CMD_PUSH` 可手动） | **HC 独有**：workout / weight | ✅ |

- 手机那 365 天/34k 心率**不会**自动出现在 NAS：两边各自从云端取，所以 NAS 必须自己 `backfill`。
- 反方向只搬"云端没有的"（Google Fit 的 walking 记录、将来的体重），其余不重复搬。
- 公网与 LAN 都可直达 `/ingest`（静态 Bearer，OAuth 403）；手机 `HcPusher` 优先直连，
  失败自动回落到 **MCP 工具 `ingest`**。edge/FRP 下不要给 `/ingest` 加 LAN ACL（源地址不可靠），
  用量由 nginx `limit_req` 和静态 token 轮换控制。
- 回补现在支持**断点续跑**：容器重启后 `backfill_state` 里的假 `running` 会在启动时清掉，
  再调 `backfill` 从上次的 `cursor` 继续（`resume` 默认 true）。

实测（2026-10-21 13:00）：`CMD_PUSH` → `推送 16 条（workout 16 / weight 0）`，NAS `query_workouts` 16 条一致。

## 十一、两个 MCP 的分工（2026-10-18 晚）

| 位置 | 地址 | 常驻 | 数据 | 用途 |
| --- | --- | --- | --- | --- |
| **NAS docker** `hc-health-mcp` | `https://mcp.health.example.com/mcp`（Bearer） | ✅ 常开 | 小米云：心率/血氧/步数/睡眠（每 15 分钟） | **默认查询入口**，手机离线也可用 |
| **手机 app** `McpService` | `http://127.0.0.1:8765/mcp`（**默认要 Bearer**：静态 key 或 OAuth） | ❌ **默认关闭**（面板开关） | 手机 HC 现场数据 + 写操作（同步/回补） | 本机调试、离线、需要 HC 独有数据时 |

- 手机侧开关：面板「本机 MCP 服务器（默认关闭）」；`CMD_MCP_START` 也尊重开关（`--ez force true` 可强制）。
- 手机侧无窗口取数仍推荐 `CMD_DUMP` + 读 `hc-latest.json`（不依赖 MCP 开关）。

## 远端 MCP token 的存储与供给

**存储**：token 只进 `EncryptedSharedPreferences`（主密钥在 Android Keystore，AES256-GCM / 键名 AES256-SIV），
文件 `shared_prefs/secure_prefs.xml` 里只有密文；旧的明文 prefs / 文件在首次读取时迁移并删除。

**供给（推荐无窗口广播）**：

```bash
adb shell am broadcast -n id.harness.hcbridge/.CmdReceiver   -a id.harness.hcbridge.CMD_SET_TOKEN --es token "$HEALTH_MCP_TOKEN"
```

设置页的「Bearer token + 保存并重测」等价；`--es token ''` 清除。

### 旧方案（已废弃）

早期允许把 token 写到 `/sdcard/Download/hc-bridge/remote-token.txt`。Android 11+ 的分区存储下，
**别的进程（adb/Termux）创建的该文件对本 app 不可读**，因此该兜底只在迁移时读一次并删除，不要再依赖。

## 两个可用入口（app 里可改，不写死）

| 入口 | 证书 | 状态 |
|---|---|---|
| `https://mcp.health.example.com/mcp` | `mcp.health.example.com`（单域名，acme.sh dns_he，至 2027-01-16） | **默认** |
| `https://health.example.com/mcp` | `*.example.com` 通配符 | 保留可用（路径式） |

设置 → 远端服务 → **服务地址** 可直接编辑并保存（`Prefs.remoteUrl`，默认值是 `https://mcp.health.example.com/mcp`）；
也可以用广播一次改掉：

```bash
adb shell am broadcast -n id.harness.hcbridge/.CmdReceiver   -a id.harness.hcbridge.CMD_SET_TOKEN --es url https://mcp.health.example.com/mcp
```

edge 侧配置（edge-host）：`/etc/nginx/sites-available/mcp.health.example.com.conf`（`include snippets/ssl-mcp-health-example-com.conf`）、
cert 落在 `/etc/nginx/conf.d/others/ssl/cer_mcp_health_example_com{,_prv_key}.pem`，
续期由 `acme.sh --install-cert … --reloadcmd "nginx -s reload"` 挂好；
`/etc/nginx/derper_stream.conf` 的 SNI map 里加 `mcp.health.example.com web_backend;`。

## 历史：为什么曾经只有 `health.example.com/mcp`

通配符证书 `*.example.com` **只覆盖一层子域**：`mcp.health.example.com` 是两层，没有匹配证书，因此该域名已禁用。
现方案在单层域名上用路径区分（`/mcp`、`/ingest`、OAuth/后台路径）。
若要改成 host 路由，需要为 `*.health.example.com` 另签一张证书并加 vhost + SNI 映射。

## 旧：三种供给方式（历史记录）

1. **无 UI（推荐给 Termux/pi 自动化）**：把 token 写进
   `/sdcard/Download/hc-bridge/remote-token.txt`（纯文本，无换行也可），
   app 的 `Prefs.remoteToken` 在 SharedPreferences 为空时自动回落读这个文件。
   ```bash
   printf '%s' "$HEALTH_MCP_TOKEN" > /sdcard/Download/hc-bridge/remote-token.txt
   ```
2. **设置页**：设置 → 远端服务 → Bearer token → 保存并重测。
3. **设置页「从文件导入」**：同上文件，点一次即可写入 SharedPreferences（之后优先用 prefs）。

验证：设置页「远端服务」应显示 `可达 · <ms> ms · N 个工具`；或直接
`curl -s -X POST https://health.example.com/mcp -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'`。

## 注意：重装（覆盖安装）后 token 可能失效

`EncryptedSharedPreferences` 的主密钥在 Keystore 里与 app 的 uid 绑定；用新的调试签名/新 uid 覆盖安装后，
密文可能读不出来（表现为设置页显示「服务在，但需要 token」）。此时重新下发一次即可：

```bash
adb shell am broadcast -n id.harness.hcbridge/.CmdReceiver   -a id.harness.hcbridge.CMD_SET_TOKEN --es token "$HEALTH_MCP_TOKEN"
```

（2026-10-18 实测：重装后 len=0 → 广播后 len=32、远端显示 `可达 · 182 ms · 9 个工具`。）

## 副设备（secondary）同步

把 app 装到平板/模拟器并切成副机，它会**从自建服务拉全量**写进本机 Health Connect（不再碰小米云）。

```bash
# 1) 下发地址 + token + 角色
adb shell am broadcast -n id.harness.hcbridge/.CmdReceiver   -a id.harness.hcbridge.CMD_SET_TOKEN   --es url https://health.example.com/mcp --es token "$HEALTH_MCP_TOKEN" --es role secondary
# 2) 触发同步
adb shell am broadcast -n id.harness.hcbridge/.CmdReceiver -a id.harness.hcbridge.CMD_SYNC
adb logcat -s HCBridge | grep secondary     # 期望: written=N notes=[心率 .., 血氧 .., 睡眠 .., 步数 ..]
```

要点/坑：
- 服务端 `query_*` 的 `start_date/end_date` **只接受 `%Y-%m-%d`**（带时间或 ISO 都会 `ValueError`）。
- 心率按 5 分钟桶写；睡眠按 session 写；**步数服务端只有按天序列**，写成"覆盖当天"的 StepsRecord，且**当天已有步数就跳过**（否则每次同步会叠加）。
- 本机若缺 `READ_EXERCISE` 等权限，`coverage()` 曾整块失败 → 现已按类型单独容错；
  `dailySummary` 的聚合也改成逐级降级（全量 → 步数+心率 → 仅步数），避免一处缺权限整块取不到。
- 调试用：`adb shell am broadcast -a id.harness.hcbridge.CMD_SEED`（**仅 debug 构建**）写一份演示数据。
