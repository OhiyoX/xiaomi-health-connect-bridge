# Xiaomi Health Connect Bridge

**Bring your Xiaomi health data to Health Connect — and make it accessible to your AI tools.**

Sync health records from Xiaomi Mi Fitness to Android Health Connect, explore your history with MCP-compatible AI assistants, and optionally keep a self-hosted copy. No root or modified Mi Fitness app required.

**中文：** 将小米运动健康数据同步到 Android Health Connect，并通过 MCP 让 AI 助手查询健康趋势。支持自动同步和可选的自托管服务，无需 Root。

[English](#english) · [简体中文](#简体中文)

## English

### Make more of the health data you already have

Your Xiaomi wearable tracks your day, but getting those records into other apps or your own workflows can be difficult. Xiaomi Health Connect Bridge gives that data two practical destinations:

- **Android Health Connect:** write supported Xiaomi cloud records in a standard format that other authorized, compatible Android apps can use.
- **Your AI tools:** ask an MCP-compatible assistant about sleep, heart rate, activity and other supported history without manually exporting spreadsheets.

The optional Docker server lets desktop agents query a self-hosted data store even when your phone is offline.

### What it offers

| Capability | Why it matters |
| --- | --- |
| **Health Connect sync** | Bring supported Xiaomi metrics into Android's shared health-data layer. |
| **AI-ready MCP access** | Query historical measurements and trends from compatible AI clients. |
| **Automatic sync and backfill** | Keep records current and recover historical data without repeated manual exports. |
| **Optional self-hosting** | Run a Docker service with local SQLite storage for desktop or remote workflows. |
| **Access controls** | Configure agent-specific permissions, OAuth 2.1, and audit logging for sensitive data. |
| **No root required** | Keep the official Mi Fitness app and device pairing intact. |

Supported data paths include heart rate, sleep, activity, body measurements/composition, stress, oxygen saturation and workouts. **Coverage varies by Xiaomi device, cloud data availability, Android Health Connect record type and integration path.** Not every metric is guaranteed to appear in every destination.

### Choose your setup

**Android app — for Health Connect users.** Sync Xiaomi cloud data to Health Connect, export records, and access a local MCP endpoint.

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
 Android bridge         Docker server
     |                       |
 Health Connect         SQLite + MCP
     |                       |
 Compatible Android     Desktop AI agents
 health apps            and other MCP clients
```

### Get started

**Android app** (JDK 17+, Android SDK platform 36):

```bash
cd app
./gradlew assembleDebug
adb install -r build/outputs/apk/debug/app-debug.apk
```

Grant the required Health Connect permissions and configure the Xiaomi cloud connection. The phone's local MCP endpoint is `http://127.0.0.1:8765/mcp` (loopback only).

**Optional Docker server:**

```bash
cd deploy/nas
cp env.example .env
# Configure account credentials and authentication secrets in .env
docker compose up -d
```

See [deployment files](deploy/nas), [operations](OPERATIONS.md), and [acceptance guidance](deploy/nas/ACCEPTANCE.md) for configuration, verification, and troubleshooting. Building and deploying require technical setup; this is not yet a one-tap consumer installation.

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

### 让小米健康数据，不再局限于一个 App

小米手环、手表和体脂秤记录了大量健康数据，但把它们用于其他 Android 应用、个人分析或 AI 助手并不总是方便。

**Xiaomi Health Connect Bridge** 提供两条路径：将支持的记录同步到 Android **Health Connect**，以及通过 **MCP** 让 AI 助手查询历史健康数据。你还可以选择在自己的服务器上保存和管理数据。

### 核心能力

| 功能 | 用户价值 |
| --- | --- |
| **同步到 Health Connect** | 让获得授权、兼容相关数据类型的 Android 应用使用小米健康记录 |
| **连接 AI 助手** | 查询睡眠、心率、活动等历史数据和变化趋势 |
| **自动同步与历史回补** | 减少手动导出，补齐可获取的历史记录 |
| **可选 Docker 自托管** | 手机离线时，桌面端 Agent 仍可查询服务器已有的数据 |
| **细粒度权限控制** | 通过 OAuth、独立权限和审计机制管理 Agent 访问 |
| **无需 Root** | 不修改官方小米运动健康 App，也不改变设备配对 |

涉及心率、睡眠、活动、体成分、压力、血氧和运动等数据。**实际覆盖范围取决于设备、云端可用数据、Health Connect 支持情况及具体同步路径，不承诺所有指标完整同步。**

### 两种使用方式

**Android App：** 适合希望将小米健康记录同步到 Health Connect 的用户，同时提供本机 MCP 接口。

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
 Android App          Docker Server
     |                     |
 Health Connect       SQLite + MCP
     |                     |
 兼容的 Android App    AI Agent / 脚本
```

### 快速开始

**Android App**（JDK 17+、Android SDK platform 36）：

```bash
cd app
./gradlew assembleDebug
adb install -r build/outputs/apk/debug/app-debug.apk
```

安装后需配置小米云端连接并授权 Health Connect。手机本机 MCP 地址为 `http://127.0.0.1:8765/mcp`，仅监听本机回环地址。

**可选 Docker 服务：**

```bash
cd deploy/nas
cp env.example .env
# 在 .env 中配置账号和认证密钥
docker compose up -d
```

详细配置和验证方式见 [部署目录](deploy/nas)、[运维文档](OPERATIONS.md) 与 [验收说明](deploy/nas/ACCEPTANCE.md)。目前仍需要一定的技术配置，并非一键安装的消费级产品。

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
