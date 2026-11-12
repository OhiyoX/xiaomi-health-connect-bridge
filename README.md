# hc-bridge

### Your Xiaomi band already knows everything about you.  
### It just won't tell anything else.

**hc-bridge turns Xiaomi health data into standard Android Health Connect data — then hands it to your apps, your scripts and your AI agents through a complete MCP API.**

**把小米手环知道的一切，变成整个 Android 都能用的标准健康数据 —— 再通过完整的 MCP 接口，交给你的应用、脚本和 AI Agent。**

No root. No patched APK. No exported database. Mi Fitness keeps working exactly as before.

[English](#english) · [中文](#中文)

---

# English

## Your health data is yours. Start acting like it.

You wear the band. You step on the scale. You sleep eight hours — or four. It all goes into the Xiaomi cloud, and there it stays: visible in one app, readable by nothing else. Not by Google Health. Not by your laptop. Certainly not by the agent you talk to every day.

hc-bridge is the two-way door.

**Out:** everything the Xiaomi cloud holds for your account — heart rate, sleep, body composition, steps and distance, stress, oxygen saturation, workouts — becomes **standard Health Connect records** with the metadata Android expects. The Health app sees them. Samsung Health sees them. Strava sees them. Anything that reads Health Connect sees ordinary health data, written by an app called hc-bridge, sitting side by side with the vendor's own records.

**In:** ask the way you actually ask. *"How has my sleep been this month?" "Is my resting heart rate improving?" "Show me the weeks I walked more than 10 km a day."* Your agent answers from the same data you see — because both of you are reading the same records.

**And it keeps itself current.** Scheduled sync. Restarts after reboot. Reconnects when the vendor app goes quiet. You never open it to keep it working.

## Three promises

### 1. Every bit of your Xiaomi data, used

Not a partial export. Not "steps and heart rate, good luck with the rest." The bridge works the whole surface of the cloud account: cardiac, sleep, body composition, activity, stress, oxygen, workouts — one consistent model, no gaps you have to fill by hand.

### 2. Fully at home in Google Health

Standard records, standard metadata, standard record origins. Health Connect is the contract, and hc-bridge honours all of it — so the data doesn't live in a silo with a nice API on top. It lives where Android expects health data to live, and your other apps inherit it for free.

### 3. An MCP API that is actually complete

Two MCP servers, one data model: **28 tools on the phone**, **10 tools on the server**. Query, export, sync, diagnose, repair. Behind them, a real permission system — not an API key in a config file and a hope.

## A week with hc-bridge

| When | What happens |
| --- | --- |
| **Monday morning** | Your agent reports last night's sleep and the trend over 30 days — read straight from Health Connect records, no screenshots, no manual export. |
| **Wednesday** | You notice Health shows fewer steps than the band. You ask why. The bridge breaks the number down by source instead of guessing. |
| **Friday** | The phone is in another room, off. Your desktop agent still answers from the self-hosted server, which pulls the same cloud on its own schedule. |
| **Sunday** | A new app wants your workouts. It asks Health Connect, gets standard records, works immediately. You changed nothing. |

## How it works

```text
   band / scale ──► Mi Fitness ──► Xiaomi health cloud
                                        │
                        ┌───────────────┴────────────────┐
                   (pull on a schedule)            (sync / backfill)
                        │                                │
              ┌─────────▼──────────┐          ┌──────────▼───────────┐
              │  Server (Docker)   │          │  App (Android)       │
              │  Python, stdlib    │          │  Kotlin              │
              │  SQLite per owner  │          │  Health Connect      │
              └─────────┬──────────┘          └──────────┬───────────┘
                        │                                │
              MCP over HTTPS + OAuth 2.1        MCP on 127.0.0.1:8765
                        │                                │
        ┌───────────────┴─────────────┐      ┌───────────┴─────────────┐
        │ Claude, Codex, Cursor, Pi,  │      │ Agents and scripts on   │
        │ anything that speaks MCP    │      │ the phone (Termux)      │
        └─────────────────────────────┘      └─────────────────────────┘
```

Two halves, both useful alone, together covering every case: **phone in hand**, **phone offline**, **agent needs an API**.

## What you get

**The app** — a Kotlin Android app (Health Connect 1.1) that owns the device side:

- Reads Health Connect out to JSON and CSV; writes back what only the cloud has.
- Runs on a schedule, survives reboots, recovers a stalled vendor sync without you touching anything.
- Serves an MCP server on `127.0.0.1:8765`, loopback only — 28 tools.
- Never patches, replaces, or reaches inside Mi Fitness. It doesn't touch the account or the pairing. Both apps write to Health Connect under their own origin, and the vendor's records stay exactly where they are.

**The server** — a Docker service in pure Python (standard library, nothing to install):

- Logs into the cloud with your account, pulls on a schedule, backfills with resume, deduplicates, and stores everything in SQLite, partitioned per account.
- Serves MCP over HTTPS, so agents keep working when the phone is off.
- Accepts the reverse direction too: workout and weight data that only exists on the phone can be pushed back in, so both servers describe the same life.

**The admin plane** — one web UI (`admin-ui/`) with two adapters, for the app and for the server, plus a JSON API behind it:

| Layer | Question it answers |
| --- | --- |
| **Identity** | Who is calling — a per-agent bearer token, or an OAuth 2.1 access token? |
| **Tool gate** | May this identity call this tool at all? |
| **Scope and window** | May it read this metric, in this period? |
| **Partition** | Whose rows are these? |

Plus an **OAuth 2.1 authorization server** (dynamic client registration, PKCE, refresh, revocation, discovery) so modern MCP clients connect without hand-made keys; **TOTP** on the admin plane, with no shipped default; per-identity rate limits; and a **hash-chained audit log** where every call links to the one before — because an agent's access to your health data should be inspectable, not assumed.

## The API surface

**On the phone (28 tools)** — sync and diagnostics, health queries, maintenance, interop:
`hc_sync_now` · `hc_backfill` · `hc_dump` · `hc_status` · `hc_logs` · `hc_set_interval` · `hc_upstream` · `hc_kick` · `query_sleep` · `query_sleep_stages` · `query_body_composition` · `query_body_measurements` · `query_metric_series` · `query_heart_rate` · `query_nightly_recovery` · `query_workouts` · `hc_steps_breakdown` · `hc_sleep_raw` · `hc_sleep_extras` · `hc_sleep_gap_check` · `hc_sleep_cleanup` · `hc_sleep_prune` · `hc_sleep_shift` · `hc_sleep_force_import` · `hc_weight_import` · `hc_activity_cleanup` · `hc_export_csv` · `hc_pull_from_server`

**On the server (10 tools)** — the cloud view, read-only towards the device:
`query_heart_rate` · `query_sleep` · `query_sleep_stages` · `query_body_composition` · `query_metric_series` · `query_stress` · `query_weight` · `query_workouts` · `query_raw` · `ingest`

## Get started

```sh
# The app (JDK 17+, Android SDK platform 36)
cd app && ./gradlew assembleDebug
adb install -r build/outputs/apk/debug/app-debug.apk

# The server
cd deploy/nas && cp env.example .env    # account, secrets, tokens
docker compose up -d
```

Then point your MCP client at `http://127.0.0.1:8765/mcp` on the phone, or at your server's `/mcp` with a bearer token or OAuth. First deployment and acceptance: `deploy/nas/install.sh`, `verify.sh`, `ACCEPTANCE.md`.

## Honest answers

**Does it break Mi Fitness?** No. It never patches or replaces the app, never reads its private storage, never touches the account or the pairing. When a nudge is needed it only calls services the app already exports — the same thing the system does when you open it.

**Does it need root?** No. Health Connect permission plus ordinary storage access is enough.

**Where does my data go?** Into Health Connect on your phone, and into SQLite on your own server. Nothing else. The app's MCP server binds to loopback only; the server listens where you deploy it.

**Is this official Xiaomi or Google code?** No. The cloud endpoints are reverse-engineered and can break when Xiaomi changes them — see [`NOTICE.md`](NOTICE.md). Only your own account is read.

**What if I stop using it?** The records are standard Health Connect data. They stay, readable by everything else, exactly as if the vendor app had written them.

## Licence

MIT. Third-party attribution and disclaimers: [`NOTICE.md`](NOTICE.md).

---

# 中文

## 你的健康数据是你的，那就让它真属于你。

你戴着手环，你站上体脂秤，你睡八小时 —— 或者四小时。这些数据都进了小米云，然后就停在那儿：只有一个 app 看得见，别的什么都读不到。Google Health 读不到，你的电脑读不到，天天跟你对话的那个 AI Agent 更读不到。

hc-bridge 就是那扇双向门。

**出去**：小米云端为你账号保存的一切 —— 心率、睡眠、体成分、步数与距离、压力、血氧、运动 —— 全部变成**标准 Health Connect 记录**，字段与元数据按 Android 的要求填。健康 App 看得见，三星健康看得见，Strava 看得见；任何读 Health Connect 的东西看到的都是普通健康数据，和小米自己写的那份并列摆着。

**进来**：你怎么问都行。*"这个月我睡得怎么样？""静息心率在变好吗？""哪几周我每天走了一万步以上？"* Agent 从你看到的同一份数据里回答 —— 因为你们读的是同一批记录。

**而且它自己保持最新。** 定时同步、重启自动拉起、厂商 app 停摆时把链路接回去。你不需要为了让它工作而打开它。

## 三个承诺

### 一、小米数据用到底

不是导个半成品，也不是"步数和心率自己想办法"。它覆盖云端账号的整个面：心率、睡眠、体成分、活动、压力、血氧、运动 —— 一套一致的模型，没有需要你手工补的缺口。

### 二、完整融入 Google Health

标准记录、标准元数据、标准来源标识。Health Connect 是那份契约，hc-bridge 完整遵守 —— 所以这些数据不是躺在孤岛里配一个好用的 API，而是住在 Android 期望健康数据住的地方，你的其它应用顺手就继承了。

### 三、MCP 接口真的完整

两个 MCP 服务器、一套数据模型：**手机端 28 个工具**，**服务器端 10 个**。查询、导出、同步、诊断、修复。后面站着真正的权限体系 —— 不是一个塞在配置文件里的 key，然后祈祷。

## 一周的样子

| 时间 | 发生什么 |
| --- | --- |
| **周一早上** | Agent 报出昨晚的睡眠和 30 天趋势 —— 直接读 Health Connect 记录，不用截图，不用手工导出。 |
| **周三** | 你发现健康 App 的步数比手环少。问一句为什么，桥把数字按来源拆开，而不是猜。 |
| **周五** | 手机在另一个房间、已经关机。桌面上的 agent 照样能答 —— 自托管服务器按自己的节奏从同一朵云取数。 |
| **周日** | 新装的运动 App 想要你的运动记录。它只需要问 Health Connect，拿到标准记录，当场可用。你什么都没改。 |

## 它是怎么搭的

```text
手环 / 体脂秤 ──► 小米运动健康 ──► 小米健康云
                                      │
                     ┌────────────────┴────────────────┐
                （按计划拉取）                    （同步 / 回补）
                     │                                  │
           ┌─────────▼──────────┐            ┌──────────▼───────────┐
           │  Server（Docker）  │            │  App（Android）      │
           │  Python 纯标准库   │            │  Kotlin              │
           │  SQLite 按账号分区 │            │  Health Connect      │
           └─────────┬──────────┘            └──────────┬───────────┘
                     │                                  │
           HTTPS + OAuth 2.1                  127.0.0.1:8765
                     │                                  │
       ┌─────────────┴────────────┐      ┌──────────────┴─────────────┐
       │ Claude / Codex / Cursor / │      │ 手机上的 agent 与脚本      │
       │ Pi，任何会说 MCP 的客户端 │      │ （Termux）                 │
       └──────────────────────────┘      └────────────────────────────┘
```

两半各自单独可用，合起来覆盖全部场景：**手机在手上**、**手机关机**、**agent 要一个 API**。

## 你会得到什么

**App** —— Kotlin 写的 Android 应用（Health Connect 1.1），掌管设备这一侧：

- 把 Health Connect 读成 JSON 与 CSV，把云端才有的数据写回来。
- 按计划自转、重启后自己拉起、厂商同步停摆时自己恢复 —— 你不用碰它。
- 在 `127.0.0.1:8765` 提供 MCP 服务器，只绑回环，28 个工具。
- 从不 patch、不替换、不深入小米运动健康的内部；不碰账号与设备绑定。两个 app 各以自己的来源标识往 Health Connect 写，小米的记录原样就在那儿。

**Server** —— 纯 Python 标准库写的 Docker 服务，没有依赖要装：

- 用你的账号登录云端，定时拉取、可续跑回补、去重，全部落进按账号分区的 SQLite。
- 对外提供 HTTPS 上的 MCP，手机关机时 agent 照样工作。
- 也接受反方向：只存在于手机的运动与体重可以推回来，于是两个服务器描述的是同一份生活。

**管理面** —— 同一套网页后台（`admin-ui/`）两个适配器，App 与 Server 通用，后面是 JSON API：

| 层 | 回答什么问题 |
| --- | --- |
| **身份** | 谁在调 —— 每个 agent 一把 Bearer，还是 OAuth 2.1 令牌？ |
| **工具闸** | 这个身份配不配调这个工具？ |
| **范围与时间窗** | 能不能读这个指标、这一段时期？ |
| **分区** | 这些行属于谁？ |

在此之上是完整的 **OAuth 2.1 授权服务器**（动态注册、PKCE、刷新、撤销、发现元数据），现代 MCP 客户端不必手工配 key 就能连；管理面用 **TOTP** 保护，不存在出厂默认；按身份限流；**哈希链审计**让每次调用都链到上一行 —— agent 访问你的健康数据这件事，应该是可查的，不是靠信任。

## 接口清单

**手机端（28 个工具）** —— 同步与诊断、健康查询、维护、互通：
`hc_sync_now` · `hc_backfill` · `hc_dump` · `hc_status` · `hc_logs` · `hc_set_interval` · `hc_upstream` · `hc_kick` · `query_sleep` · `query_sleep_stages` · `query_body_composition` · `query_body_measurements` · `query_metric_series` · `query_heart_rate` · `query_nightly_recovery` · `query_workouts` · `hc_steps_breakdown` · `hc_sleep_raw` · `hc_sleep_extras` · `hc_sleep_gap_check` · `hc_sleep_cleanup` · `hc_sleep_prune` · `hc_sleep_shift` · `hc_sleep_force_import` · `hc_weight_import` · `hc_activity_cleanup` · `hc_export_csv` · `hc_pull_from_server`

**服务器端（10 个工具）** —— 云端视角，对设备只读：
`query_heart_rate` · `query_sleep` · `query_sleep_stages` · `query_body_composition` · `query_metric_series` · `query_stress` · `query_weight` · `query_workouts` · `query_raw` · `ingest`

## 五分钟开始

```sh
# App（JDK 17+，Android SDK platform 36）
cd app && ./gradlew assembleDebug
adb install -r build/outputs/apk/debug/app-debug.apk

# Server
cd deploy/nas && cp env.example .env    # 账号、密钥、token
docker compose up -d
```

然后把 MCP 客户端指向手机上的 `http://127.0.0.1:8765/mcp`，或服务器的 `/mcp`（Bearer 或 OAuth）。首次部署与验收：`deploy/nas/install.sh`、`verify.sh`、`ACCEPTANCE.md`。

## 诚实回答

**会影响小米运动健康吗？** 不会。不 patch、不替换、不读它的私有存储，不碰账号与设备绑定。需要催一下的时候，只调它自己已经导出的服务 —— 和系统打开这个 app 时做的事一样。

**需要 root 吗？** 不需要。Health Connect 权限加普通存储权限就够了。

**我的数据会去哪？** 手机上进 Health Connect，服务器上进你自己的 SQLite。没有别的地方。App 的 MCP 只绑回环；服务器只在你自己部署的地方监听。

**这是小米或 Google 的官方代码吗？** 不是。云端端点是逆向结果，小米改版可能失效 —— 见 [`NOTICE.md`](NOTICE.md)。只读你自己的账号。

**以后不用了怎么办？** 这些记录就是标准 Health Connect 数据。它们会留在原地，别的应用照样能读，就像小米自己写的一样。

## 许可

MIT。第三方来源与免责声明见 [`NOTICE.md`](NOTICE.md)。
