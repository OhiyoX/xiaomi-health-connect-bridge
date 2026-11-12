# 测试与门禁（hc-bridge）

本文件是**约束**：改了哪块、必须过哪几道门、证据留在哪，写清楚；门禁不过不许装到设备、不许推 main。

一句话版本：

```bash
./scripts/gate.sh            # JVM 单测 + lint + debug/release 构建 + 模拟器仪器测试（全量门禁）
./scripts/gate.sh --fast     # 只跑 JVM 单测 + lint（改纯逻辑时够用）
```

非 0 退出 = 不过。

---

## 1. 分层与覆盖

| 层 | 位置 | 跑在哪 | 覆盖什么 |
| --- | --- | --- | --- |
| L1 JVM 单测 | `app/src/test/java/id/harness/hcbridge/logic/`、`app/src/test/java/id/harness/hcbridge/auth/` | 本机（秒级） | `logic/` 下的纯计算（对比度、拖动几何、睡眠聚合、血氧分桶、手势归属）＋ `auth/` 下的协议核心（PKCE、授权码、刷新轮换、策略/时间窗、TOTP、Tunnel 命令与探活解析、请求拆包）——**都不碰 Android 网络/回环端口** |
| L2 静态检查 | `app/build.gradle` 的 `lint { abortOnError true }` | 本机 | 资源/清单/API 误用；发布构建同样检查 |
| L3 仪器测试 | `app/src/androidTest/java/id/harness/hcbridge/` | **模拟器**（默认） | 长按拖动换位与持久化、竖向滑动仍滚动、图表触摸与手势归属契约、外壳三页冒烟、MCP 真 socket 与 `mcp.db` |
| L4 视觉验收 | 本机 `tools/verify_interactions.py`、`tools/audit_layouts.py`（`tools/` 在本仓 .gitignore 里：那是 Termux 侧脚本的位置，唯一源是 `example-user/toolkit`；构建门禁因此放在仓内 `scripts/`） | 真机 / 模拟器 | 截图逐项核对（"看起来不对"由人/截图判决，不由单测冒充） |

**L1 + L2 是每次改动的底线**；碰了触摸、布局、卡片顺序、图表绘制的改动，必须补 L3；界面上任何"观感"结论都要有 L4 的截图证据。

## 2. 每个测试文件守的是哪个回归

| 测试 | 守的问题（都真实发生过） |
| --- | --- |
| `logic/ContrastTest` | 动态配色下血氧 `90%` 标签发暗（深色 on-color 落在深色卡面上）；tooltip 同色不可见 |
| `logic/ReorderPlanTest` | 拖动换位的几何：一次一格、不越位、不跳位 |
| `logic/SleepMathTest` | 同一天多段睡眠画成一团（同源重复区间交叉）；跨零点时长算成负数；纵轴 18:00→12:00 |
| `logic/SleepMergeTest` | 睡眠被写两遍：小米已写过同段仍在写（10-10…10-21 每晩一对）；云端修订起床时间后同晩堆 3 条；起止漂移几十秒被当成新段；用空的 `clientRecordId` 删命中 0 条；小米自己一晚三条时读的时候要收敛成一条（且不能吃掉真正的夜间+午睡）；空夜体检按“起床那天”判缺、小米那份被删后这一夜要能重新被看见；库里同一段没分期时删旧写新补分期 |
| `logic/BodyMathTest` | 体成分趋势：x 轴必须按真实时间比例（等距下标会让相隔半年和相隔一天一样宽）、同一天多次称重只留最后一条、y 轴留边不裁极点、触摸吸附超距不选中、副文/tooltip 缺项省略 |
| `logic/SleepStagesTest` | Google Health 说 hcb「睡眠数据不足」（记录只有起止、没分期）：小米 `items[].state` → HC stage 的映射（拿汇总字段对出来）、按会话裁剪、碎段合并、未知不猜 |
| `logic/StageMathTest` | 睡眠分期：HC 常量口径（4=浅 5=深 6=REM，猜错会让 450 分钟报成 REM 311 分钟）、清醒/离床计入卧床不计入睡眠、未知阶段不静默丢弃、堆叠条比例和为 1、缺数据显示「—」而不是 0 |
| `logic/Spo2MathTest` | 竖条在"桶内几乎没跨度"时退化成 2px 短线（手机实机 vs 稀疏模拟器数据观感不一致） |
| `logic/SecondaryPullTest` | 副机拉回的 JSON 解析（时间两种形状、范围过滤、睡眠段校验、按天步数）|
| `logic/SyncDiagnosisTest` | 同步失败的人话提示：域名解析失败必须指向系统「联网控制」（平板被 UID 拦那次）|
| `logic/UpstreamFreshnessTest` | 上游（小米运动健康）没同步时桥只会傻等：睡眠/心率/步数/血氧/云端整窗空的过期判定、夜里不误判、45 分钟内不重复催 |
| `logic/HcPermissionsTest` | 权限自检只看心率漏掉 `WRITE_SLEEP`/`WRITE_STEPS`（睡眠/步数静默写不进去，状态却写 ok-no-new）：全集缺项要算出来、每项有中文名、描述长时收口成“等 N 项” |
| `logic/NetFailureTest` | 夜间掉网/DNS 不解析被当成普通失败（`fails` 顶到 5 + 假告警）：DNS/超时/连不上/TLS 算瞬时，接口变更/解析报错仍要报警 |
| `logic/AboutInfoTest` | 设置页「关于」的时间必须按**当地时区**显示（不是构建机的 UTC）：epoch→当地格式化、拿不到显示「—」、首次安装=本次安装时只写一行 |
| `logic/GestureArbitrationTest` | 图表在 `ACTION_DOWN` 抢拦截 → 长按拖动永久失效 |
| `ReorderGestureTest` | 端到端：从**图表区域**长按拖动必须换位并写入顺序；竖向滑动必须仍然滚动 |
| `ChartTouchTest` | 同一个契约在 View 层再钉一遍（按下不打拦截 / 只横向抢 / 抬手释放 / tooltip 仍回调） |
| `ShellNavigationTest` | 外壳与三页布局坏掉时的第一道红 |
| `McpDbTest` | `mcp.db` 的真实 SQLite 行为：WAL 下的单条 CAS（刷新令牌只能消费一次）、client 吊销级联、审计哈希链与 prune 后仍自洽、`settings` 里的鉴权/公网配置往返 |
| `McpAdminTest` | 原生管理页（仪器，隐藏回退）：待确认请求能批准/拒绝并写回库、客户端待批准→active、静态 key 明文只出现一次且库里只有哈希、策略/时间窗保存前过 `canonical`、TOTP secret 只进 `Secure` 不进 `settings` |
| `McpWebAdminTest` | WebView 管理页（仪器，默认）：解锁后**只**加载 `file:///android_asset/admin/`、状态 JSON 是单管理员/单会话且不含密码；`onStop` 后管理内容藏起 + JS 桥移除 + WebView 回 `about:blank` + 后续 `act` 全拒；`adminUiMode` 默认 `web`、可切 `native`、脏值退回 `web`；5 次错密码仍锁定 |
| `AdminStateJsonTest` | 共享前端的**状态契约**（JVM）：`me/overview/users/sessions/tokens/oauth/pending/coverage/sleep/metrics/audit/security/localAccess/caps` 形状、单管理员/单会话、静态 key 只有 `key#id`、撤销令牌不进列表、审计 outcome/chainOk、安全策略只读（`sessionTtlMinutes`）、`tokens[]/oauth[]` 的 `policy/window`、`localAccess`（三个开关/隧道/`hasToken`/`lastProbe`）与 `caps.localAccess|policies`；并断言序列化结果里**没有**密码哈希/TOTP secret/令牌明文/client_secret/tunnel token |
| `AdminActionsTest` | 共享前端的**动作契约**（JVM）：锁定时全拒；`user.*` 明确不支持且写审计；`session.revoke` = 上锁、`revoke-others` 返回 0；`token.create` 一次性明文 + 库里只有哈希、`token.revoke` 分辨 `key:`/`tok:`；OAuth 建/停/批/删；`pending.decide`；回填排队与上限；`audit.verify`；改密码；TOTP 绑定-确认（无恢复码）、**停用/重绑必须先验当前动态码**（错码不改状态）、重绑返回新 secret 且旧码立即失效；`token.policy/window` 与 `oauth.policy/window` 走 `canonical`（非法不写库，令牌族访问+刷新都改）；`local.*` 开关与守卫（公网开着不能关鉴权）、`local.save-public` 校验失败什么都不写、命令占位/完整、探活结果回写；`settings.update` 拒绝；审计里无凭据 |
| `AdminHealthMapTest` | 覆盖/睡眠/指标的**口径**（JVM）：睡眠按起床日归日、多段合计、`short/late_bed/fragile/fragmented/long/missing` 标记、坏时间戳跳过；覆盖用 `records_30d`/`days_30d`/`sources` 且未知类型不丢；指标窗口逐日对齐（缺数据 `null`）、睡眠分钟换小时 |
| `AdminBridgeTest` | `window.HcbAdmin` 的"永不抛"（JVM）：宿主抛异常 ⇒ `{"error":…}`（不带路径/栈）、`act` 名字与 payload 原样转发（含 `local.command`、`security.totp-rebind {code}`）、一次性明文留在顶层、`close`/`ready` 转发、哪些剪贴板内容会被定时清理 |
| `McpServiceHttpTest` | 真 socket 打 `127.0.0.1:8765`：无令牌 `/mcp` 401 + `WWW-Authenticate`、发现端点公开、`/admin*` 404、声明超长体 413、超长头 413 |

## 3. 命令

```bash
# 全量（默认挑在线模拟器跑仪器测试）
./scripts/gate.sh

# 指定设备（模拟器/真机都行；CI 或本地多设备时用）
ANDROID_SERIAL=emulator-5554 ./scripts/gate.sh
ANDROID_SERIAL=192.0.2.13:33837 ./scripts/gate.sh     # 手机（无线 adb）

# 单跑一层
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest

# 报告
app/build/reports/tests/testDebugUnitTest/index.html
app/build/reports/androidTests/connected/debug/index.html
app/build/reports/lint-results-debug.html
```

## 4. 设备矩阵

| 设备 | 用途 | 说明 |
| --- | --- | --- |
| `emulator-5554` / `emulator-5556`（API 35，x86_64） | **L3 主场地** | 仪器测试的默认目标；无手环/无 HC 数据也能跑（测试自带合成数据，不依赖真机数据） |
| 手机 PJZ110（phone） | 主设备实机、L4 视觉验收 | 无线 adb；主角色（云 → Health Connect） |
| 平板 23046RP50C | expanded 布局验收 | 副角色（只从自建服务拉取） |

## 5. 什么时候必须重跑

- **改了 `logic/`** → L1（改了哪个模块跑哪个类即可，`--tests`）。
- **改了 `auth/`、MCP 配置/管理页/公网段**（`McpConfig`、`McpRouter`、`McpAdminActivity`、`McpWebAdminActivity`、`AdminActions`/`AdminStateJson`、`Tunnel`）→ L1 `auth/` 全部（`--tests 'id.harness.hcbridge.auth.*'`）+ `id.harness.hcbridge.Admin*Test` + L3 的 `McpDbTest`、`McpAdminTest`、`McpWebAdminTest`、`McpServiceHttpTest`；动了公网段还要按 §5 的设备清单手工过一次「保存公网基址 → 探活 → 公网 401/404」。
- **改了共享前端或 App 资产**（`admin-ui/`、`app/src/main/assets/admin/`）→ `python3 scripts/build-admin-ui.py --check` + `./scripts/check-admin-ui.sh`（含 Playwright 端到端）；**不要手改**构建产物。
- **改了触摸/手势/拖动/卡片顺序** → L1 的 `GestureArbitrationTest`、`ReorderPlanTest` + L3 全部。
- **改了 `res/layout*`、主题色、图表绘制** → L2 + L3 + L4（截图）。
- **改了同步/权限/网络**（`SyncWorker`、`SecondarySync`、`CloudApi`、`HcReader/HcWriter`）→ 目前**没有自动化覆盖**（见 §6），必须给真实设备上的前后状态证据（同步前后条数、`app-sync-status.json`、截图）。
- **改了存储导出**（`DownloadStore`、`SyncStatus`、`DumpWorker.write`）→ L3 的 `DownloadStoreTest` + 真机上 `ls /sdcard/Download/hc-bridge/` 确认只有一份、内容是最新（重装后旧 UID 的文件删不掉，必要时先手动清理）。
- **同一候选**：本机跑过的同一版本结果直接复用，不因 SHA/阶段变化重跑全套；只有**内容**变了才补跑受影响的那一层。

## 6. 诚实的覆盖边界（还没测的）

- 同步链路：**解析部分已有单测**（`logic/SecondaryPullTest`：epoch/at 两种时间、范围过滤、睡眠段校验、按天步数；`logic/SyncDiagnosisTest`：解析失败/超时/TLS 的人话提示）；但**真实 HTTP 往返没有测试**（无 MockWebServer），也没有对 `HcWriter` 写入语义的断言。
- 副机（secondary）路径：失败会写 `app-sync-status.json`（`state=secondary-failed` + `hint` + `notes`）——这是 2026-10-18 平板那次“四个 query_* 全失败却只有空 notes”的直接修补；**写入格式本身**由 `DownloadStoreTest` 覆盖（导出只留一份、可读回），但 secondary 专属字段没有断言。
- 存储层（2026-10-23 补）：`DownloadStoreTest` 钉住「同一主名覆盖写只留一份 + 读得到 `(N)` 副本 + SyncStatus 私有副本可读」。
  背景：旧实现按正本名删、删不掉就每次被 MediaStore 改名，堆到 32 份后 insert 抛 `Failed to build unique file`，状态文件自 2026-10-22 起停更。
  注意模拟器重装会换 UID，上一轮留下的同名文件查不到也删不掉（不是被测行为），所以测试文件名带时间戳。
- 账号登录（2026-10-23 补）：`LoginCookieTest` 覆盖 WebView 回传的 cookie 解析（`userId`/`passToken` 精确匹配、引号/空格/空值）。
  **真实小米登录页在 WebView 里的行为没有自动化测试**：页面是否被 UA 拦、HttpOnly cookie 能否被 `CookieManager` 读到，只能在真机上人工登录一次确认；失败就退回扫码入口。
- `Snapshot` 的缓存 TTL、并发查询、持久化覆盖率缓存没有测试。
- **内存/性能没有回归门禁**（2026-10-22 教训）：`coverage()` 整表读 124 万心率采样导致 OOM 崩溃两次，
  L1/L2 都发现不了。现在的约定：任何"扫全历史"的读法遇到高频类型（心率/血氧）只能
  「取边界一条 + 限窗统计」；改动这类读法必须在真机上连续 force-stop 启动 ≥3 次并检查
  `logcat -b crash` 无 `OutOfMemoryError`（已记在 OPERATIONS.md §十·补九）。
- 平板 `layout-sw600dp`（rail + list-detail）只有 L4 截图，没有仪器测试断言。
- **UI 新增的体成分卡/分期条/夜间生理没有仪器测试**（2026-10-21 增补）：L1 + L2 已过，L4 已在真机完成
  （截图与逐屏核对见 `docs/PLAN-2026-10-21-ui-body-night.md` §6）；**L3 仍缺**——本机没有可用模拟器
  （`emulator` 需 x86_64 宿主），下次在 PC/CI 侧补 `:app:connectedDebugAndroidTest`。
  这次 L4 抓到两个 L1/L2 抓不到的 bug（详情页标题、tooltip 回调漏分支），说明自绘 UI 的回归**只能靠看**。
- `McpService` 的 HTTP 行为**已有仪器测试**（`McpServiceHttpTest`，真 socket 打 `127.0.0.1:8765`：无令牌 401、发现端点公开、`/admin*` 404、413）；
  它之外的 `CmdReceiver` 广播接口（`CMD_MCP_START` / `CMD_DUMP` …）仍**没有自动化覆盖**，实测方式：
  `curl -s -X POST http://127.0.0.1:8765/mcp -H "Authorization: Bearer $HCB_KEY" -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'`
  （静态 key 在 app 的「MCP 管理」里发；不带 `Authorization` 现在返回 401）。
- 真机重启后的 `Termux:Boot → sshd` 链路属于 phone 主机侧运维，不在本项目门禁内（见 phone 主机笔记）。

要补真实 HTTP/MCP 往返，先加依赖（MockWebServer / mockk）——加之前先在本文件登记，别悄悄引入。
