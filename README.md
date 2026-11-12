# hc-bridge — Xiaomi Health Cloud → Android Health Connect

**Put your Xiaomi health data to full use, as standard Android Health Connect data — with a complete MCP API on top.**

**把小米健康数据用到底，落成标准 Android Health Connect 数据，再以完整的 MCP 接口交出去。**

> Reads your own **Xiaomi Mi Fitness / 小米运动健康** data (band, scale, sleep) from the Xiaomi health cloud,
> makes it complete and usable inside **Health Connect**, and serves the same model over MCP to your scripts,
> apps and AI agents. No root, no patched APK, no exported database — and the Xiaomi app keeps working as before.

[English](#english) · [中文](#中文)

---

## English

### Three things this project gets right

| | What it means |
| --- | --- |
| **Xiaomi data, fully used** | Not a partial export. Heart rate, sleep, body composition, steps and distance, stress, oxygen saturation, workouts — everything the Xiaomi cloud holds for your account comes out, and comes back in one consistent model. |
| **Fully compatible with Google Health** | Everything is written as **standard Health Connect** records with the metadata Android asks for. Any app that reads Health Connect — Google Fit, Samsung Health, Strava, your own code — sees ordinary health data, not a private format. Data that only lives on the phone can flow back the same way. |
| **A complete MCP API** | One consistent data model, one admin model, two MCP servers: 28 tools on the device and 10 on the server. Agents query the same health data the human sees, under real permission checks. |

That is the whole idea. Everything below is how it is built.

### Two parts, one model

| Part | Runs on | Role | MCP |
| --- | --- | --- | --- |
| **App** (`app/`) | Your Android phone | Owns Health Connect: reads it out, writes to it, keeps it current, and serves a **built-in MCP server** on `127.0.0.1:8765` | 28 tools |
| **Server** (`deploy/nas/`) | Any Docker host (NAS, VPS, home server) | Owns the cloud: pulls it on its own, stores it in SQLite, and serves **MCP over HTTPS** to agents that must work when the phone is offline | 10 tools |

```text
   band / scale ──► Xiaomi Mi Fitness ──► Xiaomi health cloud
                                              │
                          ┌───────────────────┴────────────────────┐
                     (pull on a schedule)                   (sync / backfill)
                          │                                        │
                ┌─────────▼──────────┐                  ┌──────────▼───────────┐
                │  Server (Docker)   │                  │  App (Android)       │
                │  Python, stdlib    │                  │  Kotlin              │
                │  SQLite per owner  │                  │  Health Connect      │
                └─────────┬──────────┘                  └──────────┬───────────┘
                          │                                        │
                MCP over HTTPS + OAuth 2.1              MCP on 127.0.0.1:8765
                          │                                        │
            ┌─────────────┴───────────┐            ┌───────────────┴──────────────┐
            │ Claude, Codex, Cursor,  │            │ Agents and scripts on the    │
            │ Pi, your own code       │            │ phone (Termux)               │
            └─────────────────────────┘            └──────────────────────────────┘
```

The two halves are **parallel readers of the same cloud**, not a chain: each one is useful on its own, and together they cover "phone offline", "phone in hand", and "give an agent an API".

### What the app gives you

- **Health Connect stays complete and current.** The app reads Health Connect out to JSON and CSV, writes the data that only the cloud has, and keeps the loop running on a schedule — including after a reboot, and including the cases where the vendor app stops syncing on its own.
- **Standard data, so every other app sees it.** Records carry the standard types and metadata Android requires, and the vendor's own records are kept side by side — the bridge never patches, replaces or reads inside the Mi Fitness app, and never touches its account or device pairing.
- **A built-in MCP server.** `127.0.0.1:8765`, loopback only, 28 tools: query health data, trigger and inspect sync, export, and ask why a number differs between two sources.

### What the server gives you

- **The cloud, on your own disk.** A persistent login, a scheduled pull, backfill with resume, deduplication, and per-source breakdowns — stored in SQLite, partitioned per account.
- **MCP over HTTPS.** Agents keep working when the phone is off, behind nginx with TLS.
- **A reverse channel.** Phone-only data (a workout from another app, a weight from the scale) can be pushed back in, so both MCP servers describe the same reality.

### Admin and multi-agent permissions

Both halves ship the same four-layer model, and every layer can only narrow what the layer above allowed:

| Layer | Question | Where |
| --- | --- | --- |
| **Identity** | Who is calling? Static per-agent bearer token, or an OAuth 2.1 access token | `auth.py`, `auth/Principals.kt` |
| **Tool gate** | May this identity call this tool at all? | `policy.py`, `auth/ToolGate.kt` |
| **Scope and time window** | May it read this metric, in this period? | `policy.py`, `auth/TimeWindow.kt` |
| **Partition** | Whose rows are these? | `context.py`, `db.py` |

On top of it: an **OAuth 2.1 authorization server** (dynamic client registration, PKCE, refresh, revocation, discovery) so OAuth-capable MCP clients work without a hand-made key; legacy static tokens kept working so migration is incremental; **TOTP**-gated admin with no shipped default; per-identity rate limits; and a **hash-chained audit log** where every call links to the one before, with retention rules.

The admin UI is one SPA (`admin-ui/`) with two adapters (`app.js`, `nas.js`), backed by a JSON API, so the UI is never the only way in.

### Tool catalogue

**App (28)** — device side: sync and diagnostics (`hc_sync_now`, `hc_backfill`, `hc_dump`, `hc_status`, `hc_logs`, `hc_set_interval`, `hc_upstream`, `hc_kick`), queries (`query_sleep`, `query_sleep_stages`, `query_body_composition`, `query_body_measurements`, `query_metric_series`, `query_heart_rate`, `query_nightly_recovery`, `query_workouts`, `hc_steps_breakdown`), maintenance (`hc_sleep_raw`, `hc_sleep_extras`, `hc_sleep_gap_check`, `hc_sleep_cleanup`, `hc_sleep_prune`, `hc_sleep_shift`, `hc_sleep_force_import`, `hc_weight_import`, `hc_activity_cleanup`), interop (`hc_export_csv`, `hc_pull_from_server`).

**Server (10)** — cloud side: `query_heart_rate`, `query_sleep`, `query_sleep_stages`, `query_body_composition`, `query_metric_series`, `query_stress`, `query_weight`, `query_workouts`, `query_raw`, `ingest`.

### Quick start

```sh
# App: JDK 17+, Android SDK platform 36
cd app && ./gradlew assembleDebug
adb install -r build/outputs/apk/debug/app-debug.apk

# Server
cd deploy/nas && cp env.example .env    # account, secrets, token list
docker compose up -d
```

Point an MCP client at `http://127.0.0.1:8765/mcp` on the phone, or at your server's `/mcp` with a bearer token or OAuth. First deployment and acceptance: `deploy/nas/install.sh`, `verify.sh`, `ACCEPTANCE.md`.

### Data rules worth knowing

Steps and distance are averaged across sources rather than summed (two apps record the same walk), sleep is attributed to the wake day, and body composition keeps the scale's own numbers. The point is agreement: the same numbers everywhere, whichever way you look.

### Safety and limits

- Only **your own account** is read; accounts and devices are never modified, and no vendor app is patched or replaced.
- The cloud endpoints are **unofficial reverse-engineered ones** (see `NOTICE.md`); a vendor change can break them.
- The server is read-only towards the phone; the app writes only the record types it owns; the app's MCP server binds to loopback.

### Licence

MIT. Third-party attribution and disclaimers: [`NOTICE.md`](NOTICE.md).

---

## 中文

### 这个项目做对的三件事

| | 含义 |
| --- | --- |
| **小米数据用到底** | 不是导出个半成品。心率、睡眠、体成分、步数与距离、压力、血氧、运动 —— 小米云端为你账号保存的数据都取出来，用同一套模型还回去。 |
| **完整兼容 Google Health** | 全部落成**标准 Health Connect 记录**，字段与元数据按 Android 要求填。任何读 Health Connect 的应用（Google Fit、三星健康、Strava、你自己的代码）看到的都是普通健康数据，不是私有格式；只存在于手机上的数据也能原路回传。 |
| **MCP 信息全面完整** | 一套数据模型、一套权限模型、两个 MCP 服务器：设备上 28 个工具，服务器上 10 个。Agent 拿到的是和人看到的同一份健康数据，而且每次调用都过真实权限。 |

这就是全部立意，下面都是怎么做到的。

### 两部分，一套模型

| 部分 | 跑在哪 | 角色 | MCP |
| --- | --- | --- | --- |
| **App**（`app/`） | 你的 Android 手机 | 掌管 Health Connect：读出来、写进去、保持最新，并在 `127.0.0.1:8765` 上提供**内建 MCP 服务器** | 28 个工具 |
| **Server**（`deploy/nas/`） | 任意 Docker 主机（NAS / VPS / 家用服务器） | 掌管云端：自己定时拉取、落 SQLite，并在手机离线时继续为 agent 提供 **HTTPS 上的 MCP** | 10 个工具 |

两者的关系是**同一朵云的两个平行读者**，不是一条链：各自单独可用，合起来覆盖"手机离线""手机在手""给 agent 一个 API"三种场景。

### app 给你什么

- **Health Connect 保持完整与最新**：读出来落 JSON 与 CSV，写进云端才有的数据，按计划自转 —— 重启后能自己拉起，厂商 app 停摆时也能把链路接回去。
- **标准数据，别的 app 直接看得懂**：记录类型与元数据按 Android 的要求填，小米自己写的记录原样保留、并列显示 —— 桥不 patch、不替换、不读小米运动健康的内部存储，也不碰它的账号与设备绑定。
- **内建 MCP 服务器**：`127.0.0.1:8765`，只绑回环，28 个工具：查健康数据、触发与检查同步、导出，以及回答"两个来源为什么数字不一样"。

### server 给你什么

- **云端落在你自己的盘上**：持久登录、定时拉取、可续跑的 backfill、去重、按来源拆分，存进 SQLite 并按账号分区。
- **HTTPS 上的 MCP**：手机关机时 agent 照样能查，前面挂 nginx 与 TLS。
- **反向通道**：只存在于手机的数据（别的 app 记的运动、体脂秤的体重）能推回来，于是两个 MCP 服务器描述的是同一个真实。

### 管理与多 agent 权限

两边实现同一套**四层模型**，每层只能在上层允许的范围内再收窄：

| 层 | 回答什么 | 代码 |
| --- | --- | --- |
| **身份** | 谁在调？每个 agent 一把静态 Bearer，或一个 OAuth 2.1 令牌 | `auth.py`、`auth/Principals.kt` |
| **工具闸** | 这个身份能不能调这个工具 | `policy.py`、`auth/ToolGate.kt` |
| **范围与时间窗** | 能不能读这个指标、这一段时期 | `policy.py`、`auth/TimeWindow.kt` |
| **分区** | 这些行属于谁 | `context.py`、`db.py` |

在此之上：**OAuth 2.1 授权服务器**（动态注册、PKCE、刷新、撤销、发现元数据），旧静态 token 继续可用以便逐步迁移；**TOTP** 保护管理面，不存在出厂默认；按身份限流；**哈希链审计**，每次调用链到上一行，可发现篡改，另有保留策略。

管理后台是同一套 SPA（`admin-ui/`），两个适配器（`app.js`、`nas.js`），背后是 JSON API —— 界面从来不是唯一的入口。

### 工具清单

**App（28 个）**：同步与诊断（`hc_sync_now`、`hc_backfill`、`hc_dump`、`hc_status`、`hc_logs`、`hc_set_interval`、`hc_upstream`、`hc_kick`）、查询（`query_sleep`、`query_sleep_stages`、`query_body_composition`、`query_body_measurements`、`query_metric_series`、`query_heart_rate`、`query_nightly_recovery`、`query_workouts`、`hc_steps_breakdown`）、维护（`hc_sleep_raw`、`hc_sleep_extras`、`hc_sleep_gap_check`、`hc_sleep_cleanup`、`hc_sleep_prune`、`hc_sleep_shift`、`hc_sleep_force_import`、`hc_weight_import`、`hc_activity_cleanup`）、互通（`hc_export_csv`、`hc_pull_from_server`）。

**Server（10 个）**：`query_heart_rate`、`query_sleep`、`query_sleep_stages`、`query_body_composition`、`query_metric_series`、`query_stress`、`query_weight`、`query_workouts`、`query_raw`、`ingest`。

### 快速开始

```sh
# App：JDK 17+，Android SDK platform 36
cd app && ./gradlew assembleDebug
adb install -r build/outputs/apk/debug/app-debug.apk

# Server
cd deploy/nas && cp env.example .env    # 账号、密钥、token 列表
docker compose up -d
```

手机上把 MCP 客户端指向 `http://127.0.0.1:8765/mcp`；服务器上指向你的 `/mcp`，用 Bearer 或 OAuth。首次部署与验收见 `deploy/nas/install.sh`、`verify.sh`、`ACCEPTANCE.md`。

### 三条口径

步数与距离按来源取平均而不是相加（两个 app 记的是同一段走路），睡眠归到起床日，体成分保留体脂秤自己的数字。目的只有一个：**无论从哪个入口看，看到的都是同一组数字。**

### 安全与边界

- 只读**你自己的账号**；不改设备、不改账号、不给厂商 app 打补丁或替换。
- 云端端点是**非官方逆向结果**（见 `NOTICE.md`），厂商一改版就可能失效。
- server 对手机只读；app 只写它认领的记录类型；app 的 MCP 服务器只绑回环。

### 许可

MIT。第三方来源与免责声明见 [`NOTICE.md`](NOTICE.md)。
