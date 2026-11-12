# hc-bridge — Xiaomi Health Cloud → Health Connect

**Your Xiaomi health data, out of the vendor cloud and into standard Android Health Connect — plus an MCP API for your own tools and AI agents.**

**把小米健康云的健康数据取出来，落成标准 Android Health Connect 数据，再通过 MCP 交给自己的工具和 AI Agent。**

> Reads your own **Xiaomi Mi Fitness / 小米运动健康** data (band, scale, sleep) through the Xiaomi health cloud API,
> writes it as **standard Health Connect records** any Android app can read, and keeps the vendor app's own
> connection untouched. No root, no patched APK, no exported database.

`hc-bridge` is two programs that share one data model and one admin UI:

| Part | Runs on | What it does | MCP |
| --- | --- | --- | --- |
| **App** (`app/`) | Your Android phone | Reads and writes **Health Connect**, keeps it fed when the vendor app stops syncing, and serves a **built-in MCP server** on `127.0.0.1:8765` | 28 tools |
| **Server** (`deploy/nas/`) | Any Docker host (NAS, VPS, home server) | Logs into the vendor cloud on its own, stores everything in SQLite, and serves **MCP over HTTPS** with OAuth 2.1 | 10 tools |

[English](#english) · [中文](#中文)

---

## English

### The problem

Health data is locked in three places at once: the vendor app, the vendor cloud, and Google's Health Connect — and none of them talks to the other two reliably.

- **Termux has no health API.** Scripts on the phone cannot read your steps, sleep, or heart rate.
- **Two sources double-count.** Xiaomi and Google Fit both write steps; naive sums are wrong.
- **The vendor stops syncing silently.** The band → phone → cloud chain breaks, and the bridge keeps reporting "no new data" — which looks like "everything is fine".
- **Agents need a queryable API.** A chat model cannot read a proprietary app's screen. It can call MCP tools.

`hc-bridge` answers all four: the app owns Health Connect, the server owns the cloud, and both expose the same shape of data over MCP with real permission checks.

### Architecture

```text
   band ──► Xiaomi Health app ──► Xiaomi health cloud
                                        │
                        ┌───────────────┴────────────────┐
                        │                                │
                   (pull every 15 min)              (sync / backfill)
                        │                                │
              ┌─────────▼──────────┐          ┌──────────▼───────────┐
              │  Server (Docker)   │          │  App (Android)       │
              │  Python, stdlib    │          │  Kotlin              │
              │  SQLite, per-owner │          │  Health Connect      │
              └─────────┬──────────┘          └──────────┬───────────┘
                        │                                │
              MCP over HTTPS + OAuth 2.1        MCP on 127.0.0.1:8765
                        │                                │
        ┌───────────────┴────────────┐      ┌────────────┴────────────┐
        │ Agents: Claude, Codex,     │      │ Agents and scripts on   │
        │ Cursor, Pi, your own code  │      │ the phone (Termux)      │
        └────────────────────────────┘      └─────────────────────────┘
```

The two halves are **parallel readers of the same cloud**, not a chain: the server reads the cloud directly, the app reads the cloud and writes Health Connect. Phone-only data (Google Fit workouts, body weight) flows the other way through an `ingest` tool.

### The app

An Android app (Kotlin, `minSdk 28`, Health Connect 1.1) that makes Health Connect a first-class citizen on a device whose vendor app cannot be trusted to sync.

**Standard data, automatic sync, and no conflict with Mi Fitness.**

- **Standard Android health data.** Everything the bridge writes is plain Health Connect — `HeartRateRecord`, `SleepSessionRecord` **with stages**, `WeightRecord`, `ExerciseSessionRecord` — with the standard metadata (`recordingMethod`, `Device`, record type) that Android requires. Any app that reads Health Connect (Google Fit, Samsung Health, Strava, your own code) sees the same records as if the vendor app had written them. Nothing is stored in a private format.
- **Automatic, not manual.** A periodic worker syncs on a schedule, a boot receiver restarts it after a reboot, and the upstream watchdog re-pulls when the cloud has new data. You do not tap anything to keep it current.
- **The Mi Fitness app keeps working, unchanged.** The bridge never patches, replaces, or reads the vendor app's private storage, and never touches its account or device pairing. When it needs a nudge it only calls services the vendor app already exports (`bind` to its device service, a screen-change broadcast), which is the same thing the system does when you open the app. Both apps then write to Health Connect under their own record origins, so the vendor's own entries stay intact and Health Connect keeps them side by side.

- **Read out**: Health Connect → JSON on shared storage and CSV export, so Termux scripts and agents can read data without any health API.
- **Write back**: heart rate, **sleep sessions with stages** (deep / light / REM / awake, mapped from the cloud payload and verified against the vendor's own summary fields), weight, workouts.
- **Upstream watchdog**: detects a stalled vendor sync (`logic/UpstreamFreshness`, 14 unit tests), then kicks the vendor app by cost — bind its exported device service, bind the settings service, broadcast a screen-change — waits, re-pulls, and re-schedules. When it cannot, it posts a notification that a human must tap, because Android forbids one app from commanding another's sync.
- **Permission self-check**: verifies the **whole set of 35 Health Connect permissions** the sync actually uses before writing, instead of silently losing sleep and oxygen data behind two heart-rate grants.
- **Self-healing**: periodic work, boot receiver, cleanup of duplicated and stage-less sleep records written by older versions.
- **Built-in MCP server**: `127.0.0.1:8765`, 28 tools, its own OAuth 2.1 authorization server, static principals, TOTP-gated admin, tool policy, rate limits, audit log — the same permission model as the server, on the device.

### The server

A single Python file tree with **no dependencies outside the standard library**, shipped as a Docker Compose service.

- **Owns the cloud**: a persistent login, a 15-minute pull loop, backfill with resume, dedupe, and per-source step breakdown (which answers "why does the phone show a different number").
- **MCP over HTTPS**: Streamable-HTTP JSON-RPC at `/mcp`, served behind nginx with TLS; works when the phone is offline.
- **Multi-owner partitions**: one SQLite file, one partition per account, with the schema, audit and OAuth tables kept in the owner partition only.
- **`ingest`**: the reverse channel for data the cloud does not have (Google Fit workouts, scale weights).
- **Admin SPA**: the same web UI the app uses (`admin-ui/`, with `adapters/app.js` and `adapters/nas.js`), plus a JSON API (`/admin/api/state`, `/admin/api/act`) so the UI is not the only way in.

### Admin and multi-agent permissions

Both halves implement the same four-layer model. Every connected agent gets its own identity, and every layer can only narrow what the layer above allowed:

| Layer | Question it answers | Where |
| --- | --- | --- |
| **1. Identity** | Who is calling? Static per-agent bearer token, or an OAuth 2.1 access token | `auth.py`, `auth/Principals.kt` |
| **2. Tool gate** | May this identity call this tool at all? | `policy.py`, `auth/ToolGate.kt` |
| **3. Scope and window** | May it read this metric, inside this time window? | `policy.py`, `auth/TimeWindow.kt` |
| **4. Partition** | Whose rows are these? | `context.py`, `db.py` |

On top of that:

- **OAuth 2.1 authorization server**: dynamic client registration, authorization code + PKCE, refresh, revocation, discovery metadata — so MCP clients that speak OAuth work without a hand-made key.
- **Legacy static tokens** keep working (logged as `legacy`), so migration is incremental.
- **TOTP** (RFC 6238) gates the admin plane; the admin bootstrap happens on first login, never with a shipped default.
- **Rate limiting** per identity, in-process fixed window.
- **Hash-chained audit log**: every call appends a row that chains to the previous hash, so tampering is detectable, with retention and cleanup.

### Tool catalogue

**App (28)** — device side, writes Health Connect:

| Group | Tools |
| --- | --- |
| Sync | `hc_sync_now`, `hc_backfill`, `hc_dump`, `hc_status`, `hc_logs`, `hc_set_interval`, `hc_upstream`, `hc_kick` |
| Sleep | `query_sleep`, `query_sleep_stages`, `hc_sleep_raw`, `hc_sleep_extras`, `hc_sleep_gap_check`, `hc_sleep_cleanup`, `hc_sleep_prune`, `hc_sleep_shift`, `hc_sleep_force_import` |
| Body and activity | `query_body_composition`, `query_body_measurements`, `query_metric_series`, `query_heart_rate`, `query_nightly_recovery`, `query_workouts`, `hc_steps_breakdown`, `hc_weight_import`, `hc_activity_cleanup` |
| Interop | `hc_export_csv`, `hc_pull_from_server` |

**Server (10)** — cloud side, read-only for the device:

`query_heart_rate`, `query_sleep`, `query_sleep_stages`, `query_body_composition`, `query_metric_series`, `query_stress`, `query_weight`, `query_workouts`, `query_raw`, `ingest`

### Quick start

**App**

```sh
cd app
./gradlew assembleDebug          # JDK 17+, Android SDK platform 36
adb install -r build/outputs/apk/debug/app-debug.apk
```

Grant Health Connect permissions on first launch (the app asks for the full set), then point an MCP client at `http://127.0.0.1:8765/mcp`.

**Server**

```sh
cd deploy/nas
cp env.example .env      # fill in the account, secrets and token list
docker compose up -d
curl -s https://health.example.com/mcp -H "Authorization: Bearer $TOKEN" \
     -H 'content-type: application/json' \
     -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

`install.sh`, `verify.sh` and `ACCEPTANCE.md` in the same folder cover the first deployment and the acceptance run.

### Data rules worth knowing

1. **Steps and distance are averaged across sources**, not summed: Xiaomi and Google Fit record the same walking, and Health Connect keeps both.
2. **Sleep belongs to the wake day**, and one night is deduplicated by interval before summing.
3. **BMI** is computed as weight / height², with height inferred from the scale's own `(kg, bmi)` pair, because Health Connect has no height record.

### Safety and limits

- Only **your own account** is read; accounts and devices are never modified, and no vendor app is patched.
- The cloud endpoints are **unofficial reverse-engineered ones** (see `NOTICE.md`); a vendor change can break them.
- The server is read-only towards the phone; the app is read-only towards Health Connect except for the record types it explicitly owns.
- Nothing leaves your hosts unless you deploy the server publicly; the app's MCP server binds to loopback.

### Licence

MIT. Third-party attribution and disclaimers: [`NOTICE.md`](NOTICE.md).

---

## 中文

### 它解决什么

健康数据同时被锁在三处：厂商 app、厂商云、Google 的 Health Connect —— 而这三者互不守约。

- **Termux 没有健康 API**：手机上的脚本读不到步数、睡眠、心率。
- **两个来源会重复计数**：小米和 Google Fit 各写一份步数，直接相加是错的。
- **厂商会静默停摆**：手环 → 手机 → 云这条链断了，桥只会老实报"没有新数据"—— 从外面看像是一切正常。
- **Agent 需要一个能查的接口**：聊天模型看不了封闭 app 的屏幕，但能调 MCP 工具。

`hc-bridge` 就是这四条的答案：**app 负责 Health Connect，server 负责云端**，两边用同一套数据口径，都通过 MCP 暴露出去，并且都带真实的权限校验。

### 两大部分

| 部分 | 跑在哪 | 干什么 | MCP |
| --- | --- | --- | --- |
| **App**（`app/`） | 你的 Android 手机 | 读写 **Health Connect**、在厂商 app 停摆时自己催、并且在 `127.0.0.1:8765` 上**内建 MCP 服务器** | 28 个工具 |
| **Server**（`deploy/nas/`） | 任意 Docker 主机（NAS / VPS / 家用服务器） | 自己登录厂商云、落 SQLite、对外提供 **HTTPS 上的 MCP**，带 OAuth 2.1 | 10 个工具 |

两者的关系是**同一朵云的两个平行读者**，不是一条链：server 直接读云，app 读云并写进 Health Connect。云端没有的数据（Google Fit 的运动记录、体脂秤的体重）走反方向，由 `ingest` 工具回传。

```text
手环 ──► 小米运动健康 ──► 小米健康云
                              │
              ┌───────────────┴────────────────┐
         （每 15 分钟拉一次）              （同步 / 回补）
              │                                │
    ┌─────────▼──────────┐          ┌──────────▼───────────┐
    │  Server（Docker）  │          │  App（Android）      │
    │  Python 纯标准库   │          │  Kotlin              │
    │  SQLite，按账号分区│          │  Health Connect      │
    └─────────┬──────────┘          └──────────┬───────────┘
              │                                │
      HTTPS + OAuth 2.1             127.0.0.1:8765
              │                                │
    ┌─────────┴──────────┐          ┌──────────┴───────────┐
    │ Claude / Codex /   │          │ 手机上的 agent 与     │
    │ Cursor / Pi / 自写 │          │ 脚本（Termux）        │
    └────────────────────┘          └──────────────────────┘
```

### app 做什么

Kotlin 写的 Android 应用（`minSdk 28`，Health Connect 1.1），让 Health Connect 真正属于你。

**标准数据、自动同步、不影响小米运动健康。**

- **落的是 Android 标准健康数据**：桥写进去的全是普通 Health Connect 记录 —— 心率、**带分期的睡眠**、体重、运动，字段与元数据（`recordingMethod`、`Device`、记录类型）都按 Android 的要求填。任何读 Health Connect 的应用（Google Fit、三星健康、Strava、你自己的代码）看到的记录，和小米自己写的没有格式差别，不存在私有格式。
- **自动同步，不用手点**：周期任务按计划同步，开机广播自动拉起，上游看护发现云端有新数据就补拉 —— 保持最新不需要任何手动操作。
- **小米运动健康照常工作**：不 patch、不替换、不读它的私有存储，也不碰它的账号与设备绑定。需要催的时候只调它自己导出的服务（bind 设备服务、发一次亮屏广播），和系统打开这个 app 时做的事一样。两边各自以自己 app 的身份往 Health Connect 写，小米写的记录原样保留，Health Connect 会把两份并列显示。

- **读出来**：Health Connect → 共享存储上的 JSON + CSV 导出，Termux 脚本和 agent 不用任何健康 API 就能读。
- **写回去**：心率、**带分期的睡眠**（深睡 / 浅睡 / REM / 清醒，从云端 payload 的 `items[].state` 映射，并拿厂商自己的汇总字段逐项核对过）、体重、运动记录。
- **上游看护**：判断厂商同步是否停摆（`logic/UpstreamFreshness`，14 条单测），然后按代价从小到大去催 —— 先 bind 它导出的设备服务，再 bind 设置服务，最后广播一次亮屏事件；催完等一会儿补拉，并把下一次补拉排进 WorkManager。催不动时发通知让人点，因为 Android 不允许一个 app 命令另一个 app 同步。
- **权限自检**：按同步**真正用到的 35 项** Health Connect 权限检查，而不是只看两条心率权限 —— 后者会让睡眠和血氧被拒却依然报"正常"。
- **自愈**：周期任务、开机拉起、清理旧版本写下的重复与无分期睡眠。
- **内建 MCP 服务器**：`127.0.0.1:8765`，28 个工具，自带 OAuth 2.1 授权服务器、静态凭据、TOTP 保护的后台、工具权限、限流与审计 —— 权限模型与 server 端完全一致，只是搬到了设备上。

### server 做什么

一棵**只用 Python 标准库**的代码树，用 Docker Compose 部署：

- **替你看住云端**：持久登录、15 分钟轮询、可续跑的 backfill、去重，以及按来源拆分的步数（回答"为什么手机显示的数字不一样"）。
- **HTTPS 上的 MCP**：`/mcp` 的 Streamable-HTTP JSON-RPC，前面挂 nginx 与 TLS；手机离线也能查。
- **多账号分区**：一个 SQLite 文件，每个账号一个分区；schema、审计、OAuth 表只留在 owner 分区。
- **`ingest`**：云端没有的数据的反向通道。
- **管理后台**：与 app 共用同一套前端（`admin-ui/`，`adapters/app.js` 与 `adapters/nas.js`），另有 JSON API（`/admin/api/state`、`/admin/api/act`）。

### 管理与多 agent 权限

两边实现的是同一套**四层模型**，每一层只能在上层允许的范围内再收窄：

| 层 | 回答什么问题 | 代码 |
| --- | --- | --- |
| **1. 身份** | 谁在调？每个 agent 一把静态 Bearer，或一个 OAuth 2.1 访问令牌 | `auth.py`、`auth/Principals.kt` |
| **2. 工具闸** | 这个身份能不能调这个工具？ | `policy.py`、`auth/ToolGate.kt` |
| **3. 范围与时间窗** | 能不能读这个指标、读这个时间段？ | `policy.py`、`auth/TimeWindow.kt` |
| **4. 分区** | 这些行属于谁？ | `context.py`、`db.py` |

在此之上：

- **OAuth 2.1 授权服务器**：动态客户端注册、授权码 + PKCE、刷新与撤销、发现元数据 —— 会说 OAuth 的 MCP 客户端不必再手工塞 key。
- **旧静态 token 继续可用**（审计里记为 `legacy`），迁移可以一段一段来。
- **TOTP**（RFC 6238）保护管理面；管理员在首次登录时自己设定，不存在出厂默认口令。
- **限流**：按身份的进程内固定窗口。
- **哈希链审计**：每次调用追加一行并链到上一行的哈希，改动可被发现，另有保留与清理策略。

### 工具清单

**App（28 个）**：同步类 `hc_sync_now`、`hc_backfill`、`hc_dump`、`hc_status`、`hc_logs`、`hc_set_interval`、`hc_upstream`、`hc_kick`；睡眠类 `query_sleep`、`query_sleep_stages`、`hc_sleep_raw`、`hc_sleep_extras`、`hc_sleep_gap_check`、`hc_sleep_cleanup`、`hc_sleep_prune`、`hc_sleep_shift`、`hc_sleep_force_import`；身体与活动类 `query_body_composition`、`query_body_measurements`、`query_metric_series`、`query_heart_rate`、`query_nightly_recovery`、`query_workouts`、`hc_steps_breakdown`、`hc_weight_import`、`hc_activity_cleanup`；互通类 `hc_export_csv`、`hc_pull_from_server`。

**Server（10 个）**：`query_heart_rate`、`query_sleep`、`query_sleep_stages`、`query_body_composition`、`query_metric_series`、`query_stress`、`query_weight`、`query_workouts`、`query_raw`、`ingest`。

### 快速开始

```sh
# App
cd app && ./gradlew assembleDebug        # JDK 17+，Android SDK platform 36
adb install -r build/outputs/apk/debug/app-debug.apk

# Server
cd deploy/nas && cp env.example .env     # 填账号、密钥、token 列表
docker compose up -d
```

App 首次启动会申请整套 Health Connect 权限；之后把 MCP 客户端指向 `http://127.0.0.1:8765/mcp`。
Server 的首次部署与验收见同目录的 `install.sh`、`verify.sh`、`ACCEPTANCE.md`。

### 三条口径（容易踩）

1. **步数与距离按来源取平均**，不是相加 —— 小米与 Google Fit 记的是同一段走路，Health Connect 两份都留。
2. **睡眠归到起床日**，同一晚先按区间去重再求和。
3. **BMI = 体重 / 身高²**，身高由体脂秤自己报的 `(kg, bmi)` 反推，因为 Health Connect 没有身高记录。

### 安全与边界

- 只读**你自己的账号**；不改设备、不改账号、不给厂商 app 打补丁。
- 云端端点是**非官方逆向结果**（见 `NOTICE.md`），厂商一改版就可能失效。
- server 对手机只读；app 对 Health Connect 只写它明确认领的那几类记录。
- 除非你主动把 server 部署到公网，数据不出你的机器；app 的 MCP 服务器只绑回环地址。

### 许可

MIT。第三方来源与免责声明见 [`NOTICE.md`](NOTICE.md)。
