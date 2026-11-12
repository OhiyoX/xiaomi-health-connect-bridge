# Xiaomi Health Connect Bridge

**Your Xiaomi health data, in Health Connect and AI — with on-device MCP, no server required.**

Sync supported Xiaomi Mi Fitness records to Android Health Connect and let compatible AI clients query your health history through an MCP server running directly on your Android phone. An optional Docker deployment supports independent server-side access. No root or modified Mi Fitness app required.

**中文：** 将小米运动健康数据同步到 Android Health Connect，并通过 MCP 让 AI 助手查询健康趋势。Android App 内置本机 MCP Server，无需 NAS 或 Docker 即可接入兼容的本地 AI 客户端；也支持自动同步与可选的自托管服务，无需 Root。

[English](#english) · [简体中文](#简体中文)

## English

### From wearable data to useful insights

Your Xiaomi wearable tracks your day, but getting those records into other apps or your own workflows can be difficult. Xiaomi Health Connect Bridge gives that data two practical destinations:

- **Android Health Connect:** write supported Xiaomi cloud records in a standard format that other authorized, compatible Android apps can use.
- **Your AI tools:** ask an MCP-compatible assistant about sleep, heart rate, activity and other supported history without manually exporting spreadsheets.

**No Docker or NAS needed for MCP:** the Android app itself hosts a local MCP endpoint for compatible clients on the phone. Docker is an optional, separate deployment for server-side workflows. Note that on-device MCP describes where the service runs; fetching new Xiaomi cloud records may still require internet access.

### What you can do

| Capability | Why it matters |
| --- | --- |
| **Health Connect sync** | Bring supported Xiaomi metrics into Android's shared health-data layer. |
| **On-device MCP — no server required** | The Android app hosts its own local MCP endpoint for compatible clients, without Docker or a NAS. |
| **Resilient background sync** | Schedule sync, check upstream freshness, retry transient failures, and backfill available history. |
| **Optional self-hosting** | Run a Docker service with local SQLite storage for desktop or remote workflows. |
| **Access controls** | Configure agent-specific permissions, OAuth 2.1, and audit logging for sensitive data. |
| **No root required** | Keep the official Mi Fitness app and device pairing intact. |

Supported data paths include heart rate, sleep, activity, body measurements/composition, stress, oxygen saturation and workouts. **Coverage varies by Xiaomi device, cloud data availability, Android Health Connect record type and integration path.** Not every metric is guaranteed to appear in every destination.

### See it in action

**Example: ask your AI assistant about your health history**

> “How did my sleep duration and resting heart rate change over the last two weeks?”

A compatible MCP client can request supported historical records from the bridge and summarize trends. The example is illustrative, not a recorded AI response. Results depend on available data, client permissions and the selected tools.

**Android workflow:** connect your Xiaomi account → grant Health Connect permissions → sync → connect a same-device MCP client to the Android app.

**Server workflow:** configure the optional Docker deployment → collect supported cloud records → connect an authenticated MCP client.

*Real screenshots and a verified end-to-end demo are still needed. We do not substitute concept mockups for actual app screenshots.*

### Choose your setup

**Android app — Health Connect + on-device MCP.** Sync supported Xiaomi cloud data to Health Connect, export records, and serve MCP directly on your phone. For a compatible client on the same device, no Docker server is required. The loopback endpoint is not directly reachable from another device.

**Docker server — for desktop AI and self-hosting.** Collect Xiaomi cloud data into SQLite and expose an authenticated MCP endpoint independently of the phone.

You can use either component separately or combine them. The server does not automatically make its data available to Android apps; Health Connect integration happens on the phone.

### How it works

```text
Xiaomi wearable / scale
          |
       Mi Fitness
          |
    Xiaomi health cloud
          |
     +----+------------------+
     |                       |
 Android app            Docker server (optional)
     |                       |
 +---+---------+         SQLite + MCP
 |             |              |
Health Connect Local MCP   Desktop AI agents
 |             |          and other MCP clients
Android apps   On-device AI clients
```

### Get started

**Android app** (JDK 17+, Android SDK platform 36):

```bash
cd app
./gradlew assembleDebug
adb install -r build/outputs/apk/debug/app-debug.apk
```

Grant the required Health Connect permissions and configure the Xiaomi cloud connection. The Android app runs its own MCP server at `http://127.0.0.1:8765/mcp` (loopback only; Bearer authentication is enabled by default in the documented 0.8.0+ implementation). A compatible MCP client on the same phone can connect without deploying Docker. Loopback is not a LAN endpoint, and obtaining fresh Xiaomi cloud data may still need network access.

**Optional Docker server:**

```bash
cd deploy/nas
cp env.example .env
# Configure account credentials and authentication secrets in .env
docker compose up -d
```

See [deployment files](deploy/nas), [operations](OPERATIONS.md), and [acceptance guidance](deploy/nas/ACCEPTANCE.md) for configuration, verification, and troubleshooting. Building and deploying require technical setup; this is not yet a one-tap consumer installation.

### What has been verified?

The Android operations guide documents the local Streamable HTTP MCP endpoint, its loopback binding, and Bearer authentication (enabled by default in the documented 0.8.0+ implementation). A compatible client must complete authentication; the URL alone is not sufficient.

The NAS acceptance report dated 2026-11-02 records **415 passing local automated tests**, but explicitly marks real NAS/edge deployment, phone ingestion and real MCP-client end-to-end checks as **not yet verified**. Automated test coverage is not a production availability guarantee.

### Privacy, compatibility and limitations

- **Unofficial integration:** this project is not affiliated with Xiaomi or Google. Xiaomi cloud endpoints are reverse-engineered and may change without notice.
- **Sensitive data:** health information is stored in Android Health Connect and/or your own server, depending on what you deploy. Secure server exposure and grant only necessary permissions to agents.
- **App compatibility:** another app must support and be authorized to read the relevant Health Connect record types; synchronization does not guarantee that every app will display them.
- **Data quality:** availability, completeness and timestamps depend on upstream records and sync behavior. Check the source before interpreting health trends.
- **Not a medical device:** outputs are for personal data access, not diagnosis.

See [NOTICE.md](NOTICE.md) for third-party and compatibility disclaimers.

### For developers

The Android component is written in Kotlin and exposes local MCP tools for queries, sync, export and diagnostics. The Docker component provides an independent cloud collector and authenticated MCP API, with an administration interface in [admin-ui/](admin-ui/). Detailed API and deployment information belongs in the [docs/](docs/) and [operations guide](OPERATIONS.md), rather than the product introduction.

**License:** [MIT](LICENSE).

---

## 简体中文

### 从设备记录，到真正可用的健康数据

小米手环、手表和体脂秤记录了大量健康数据，但把它们用于其他 Android 应用、个人分析或 AI 助手并不总是方便。

**Xiaomi Health Connect Bridge** 提供两条路径：将支持的记录同步到 Android **Health Connect**，以及通过 **MCP** 让 AI 助手查询历史健康数据。你还可以选择在自己的服务器上保存和管理数据。

### 核心能力

| 功能 | 用户价值 |
| --- | --- |
| **同步到 Health Connect** | 让获得授权、兼容相关数据类型的 Android 应用使用小米健康记录 |
| **手机端原生 MCP，无需服务器** | Android App 自带 MCP Server，兼容的同机 AI 客户端可以直接查询健康历史，不必部署 Docker 或 NAS |
| **可靠的后台同步** | 自动调度、检查上游新鲜度、异常重试，并回补可获取的历史记录 |
| **可选 Docker 自托管** | 手机离线时，桌面端 Agent 仍可查询服务器已有的数据 |
| **细粒度权限控制** | 通过 OAuth、独立权限和审计机制管理 Agent 访问 |
| **无需 Root** | 不修改官方小米运动健康 App，也不改变设备配对 |

涉及心率、睡眠、活动、体成分、压力、血氧和运动等数据。**实际覆盖范围取决于设备、云端可用数据、Health Connect 支持情况及具体同步路径，不承诺所有指标完整同步。**

### 看看它能做什么

**示例：直接向 AI 询问健康趋势**

> “过去两周，我的睡眠时长和静息心率有什么变化？”

兼容的 MCP 客户端可以读取已授权的历史数据，并据此分析趋势。这是使用场景示例，并非已经录制的 AI 实测回复；实际结果取决于可用数据、客户端权限和工具能力。

**手机使用路径：** 连接小米账号 → 授予 Health Connect 权限 → 同步 → 同机 MCP 客户端连接 Android App。

**自托管路径：** 可选部署 Docker → 采集云端数据 → 经认证的 MCP 客户端连接服务。

*项目仍需要真实应用截图与经过验证的端到端 Demo；不会用概念图冒充实机截图。*

### 两种使用方式

**Android App：Health Connect + 纯本机 MCP 调用。** 手机 App 自带 MCP Server，兼容的同机 AI 客户端可以直接连接，不依赖 Docker、NAS 或外部 MCP 服务。这里的「本机」指 MCP 服务运行位置；首次获取或更新小米云端数据仍可能需要联网。本机回环地址不能直接供其他设备访问。

**Docker Server：** 适合希望在 NAS 或服务器上保存数据、让电脑上的 AI Agent 查询健康历史的用户。

两者可以独立使用，也可以组合部署。服务器中的数据不会仅因为部署了 Docker 就自动出现在手机的 Health Connect 中。

### 工作原理

```text
小米手环 / 手表 / 体脂秤
           |
       小米运动健康
           |
       小米健康云
           |
     +-----+---------------+
     |                     |
 Android App          Docker Server（可选）
     |                     |
 +---+---------+       SQLite + MCP
 |             |            |
Health Connect 本机 MCP    桌面 AI Agent
 |             |          / 脚本
兼容 Android App 同机 AI 客户端
```

### 快速开始

**Android App**（JDK 17+、Android SDK platform 36）：

```bash
cd app
./gradlew assembleDebug
adb install -r build/outputs/apk/debug/app-debug.apk
```

安装后需配置小米云端连接并授权 Health Connect。Android App 自带 MCP Server，地址为 `http://127.0.0.1:8765/mcp`，仅监听本机回环地址；运维文档所述 0.8.0 及之后的实现默认要求 Bearer 认证。兼容的同机 MCP 客户端可直接调用，无需 Docker；其他设备不能直接通过该回环地址访问。

**可选 Docker 服务：**

```bash
cd deploy/nas
cp env.example .env
# 在 .env 中配置账号和认证密钥
docker compose up -d
```

详细配置和验证方式见 [部署目录](deploy/nas)、[运维文档](OPERATIONS.md) 与 [验收说明](deploy/nas/ACCEPTANCE.md)。目前仍需要一定的技术配置，并非一键安装的消费级产品。

### 已验证与待验证

Android 运维文档记录了本机 Streamable HTTP MCP、仅监听回环地址，以及从文档所述 0.8.0 版本起默认启用的 Bearer 认证；仅知道服务 URL 不等于可以直接调用。

2026-11-02 的 NAS 验收记录显示 **415 项本地自动化测试通过**，但明确标注真实 NAS/公网部署、手机数据接入及真实 MCP 客户端端到端验收**尚未完成**。本地测试通过不代表生产环境已经验收。

### 隐私与限制

- **非官方项目：** 与小米、Google 无关联。云端接口基于逆向研究，可能因上游改动失效。
- **数据由你管理：** 根据部署方式，数据存储在手机 Health Connect 和/或自托管服务器中。请妥善保护账号凭据、服务入口及 Agent 权限。
- **兼容性存在边界：** 第三方应用必须支持相应 Health Connect 记录类型并获得授权，才能读取相关数据。
- **数据可能不完整：** 设备型号、历史记录、同步时间和数据来源均可能影响结果。
- **不提供医疗诊断：** 本项目用于个人健康数据访问与分析，不属于医疗器械。

更多说明见 [NOTICE.md](NOTICE.md)。

### 开发者信息

Android 端使用 Kotlin，提供同步、查询、导出和诊断等本机 MCP 能力；Docker 端提供独立的云端数据采集与认证 MCP 接口；管理界面位于 [admin-ui/](admin-ui/)。技术细节见 [docs/](docs/) 和 [OPERATIONS.md](OPERATIONS.md)。

**开源协议：** [MIT](LICENSE)。
