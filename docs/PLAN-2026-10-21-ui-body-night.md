# 开发计划：UI 展示体成分与夜间生理（2026-10-21）

> 触发原因：数据侧已经补齐（体重/体脂/BMI/基础代谢/骨量/去脂体重/体水分/身高/呼吸率/HRV/睡眠分期，
> 见 `OPERATIONS.md` §十·补四~七），但 **app 页面里一个都看不到**——数据进得去、看不到，等于用户拿不到价值。
> 本文件是这次 UI 改动的**计划 + 验收口径**，改完与 `docs/UI.md`（规范）、`docs/TESTING.md`（门禁）三处对齐。

## 1. 参考的规范（先读再动手）

| 规范来源 | 本次约束 |
| --- | --- |
| `docs/UI.md` §2 结构 | 单 Activity 外壳 + 三视图；数据页是卡片流；详情是二级页（平板右栏） |
| `docs/UI.md` §3 指标差异化 | 每类指标要有**符合语义**的图形，不套同一个模板 |
| `docs/UI.md` §4 颜色 | 只用官方 M3 角色（容器/on-容器配对），不自造色值，不给同类指标重复用同一容器角色 |
| `docs/UI.md` §5 颜色引擎 | 取色一律 `View.m3("attr")`，不绑定 material 的 R 符号 |
| `docs/UI.md` §11 单一来源 | 指标样式只写 `MetricStyle`；详情只写 `MetricDetailBinder` |
| `docs/UI.md` §3 文本规格 | 卡片数值 `titleLarge`、副文 `bodySmall`、刻度/浮层 `labelSmall`；tooltip 固定在卡片标题行右侧 |
| `docs/TESTING.md` §1/§5 | 改 `logic/` → L1；改布局/图表 → L2 + L3 + L4；L4 必须有截图证据 |

## 2. 目标（可验收）

1. **数据页新增「体成分」卡**：最新体重（大数）+ BMI/体脂/测量日期（副文）+ 体重趋势图（稀疏点线）。
   点卡片或图 → 详情页 `body`。
2. **睡眠详情页新增两块**：
   - **分期**：最近一晚深睡/浅睡/REM/清醒分钟 + 占比 + 睡眠效率；
   - **夜间生理**：呼吸率、HRV（RMSSD）最近值 + 近 7 晚迷你序列。
3. **详情页 `body`**：大数=体重；图=体重趋势；统计=体脂/BMI/肌肉/骨量/体水分最新值 + 区间变化；
   明细=最近 8 次称重记录。
4. 全部**只读 HC**，不引入新的网络请求；数据进 `Snapshot` 的“详档”缓存（180s）。

## 3. 不做（明确划界）

- 不改概览页的三项今日摘要（避免信息过载；体重进入详情/数据页即可）。
- 不新增底部 tab（M3 规定 3–5 个同权目的地，当前 3 个已够）。
- 不为体成分做“目标体重/减脂曲线”（没有产品口径，不做猜测）。
- 不写 HC 的静息心率/爬楼层等（无数据来源，见 `OPERATIONS.md` §十·补七）。

## 4. 设计（按 UI.md 的规则落到具体决定）

### 4.1 卡片与配色

| 指标 | 容器角色 | on-容器 | accent | 依据 |
| --- | --- | --- | --- | --- |
| 步数 | `colorSurfaceContainerHighest` | `colorOnSurface` | `colorOnSurface` | 既有 |
| 心率 | `colorPrimaryContainer` | `colorOnPrimaryContainer` | 同 on | 既有 |
| 血氧 | `colorSecondaryContainer` | `colorOnSecondaryContainer` | 同 on | 既有 |
| 睡眠 | `colorTertiaryContainer` | `colorOnTertiaryContainer` | 同 on | 既有 |
| **体成分（新）** | `colorSurfaceContainerHigh` | `colorOnSurface` | 由 `MetricStyle.accent` 保证 ≥3:1 | 官方角色，且与步数的 Highest 区分 |

`MetricStyle.of("body")` 增加一项；其余全部走既有单一来源，不新开样式路径。

### 4.2 体成分图形（`BodyTrendView`）

语义：**稀疏的时间序列**（5 年 29 次称重），不是连续曲线。
- x 轴按**真实时间比例**（不是等距下标），否则两次相隔半年的称重会挤在一起，视觉上骗人；
- 每个测量画实心点（5px，选中 9px），相邻点间画 1.5px 连线；
- y 轴只标 min/max（`labelSmall`），不画网格线以外的装饰；
- 触摸：吸附最近点（按 x 距离），回调 `onSelect("MM-dd · 65.0 kg · 20.7")`，抬手 1.5s 后消失；
- 无障碍：`contentDescription` = "体重趋势，N 次测量，最新 X kg（日期）"。

### 4.3 睡眠详情

- 分期条：**横向堆叠条**（深/浅/REM/清醒），颜色用睡眠容器角色的深浅变体（`colorOnTertiaryContainer` 加 alpha，
  不使用自造 RGB）；右侧文字给分钟数 + 占比。
- 夜间生理：两行文本（呼吸率、HRV）+ 一行近 7 晚迷你数值（用 `labelSmall` 文本，不做第二张图，避免抢主图）。
- 数据缺失（小米只有 2026-07 之后才有 HRV）→ 显示「—」并注明「设备未提供」，不显示 0。

### 4.4 数据流

```
HcQuery.bodyComposition(...)        → Snapshot.bodyLatest / bodySeries        （详档缓存 180s）
HcQuery.sleepStages(...)            → Snapshot.lastNightStages               （详档）
HcQuery.nightlyRecovery(days=7)     → Snapshot.recovery（rr/hrv 最新 + 序列） （详档）
```
三个查询与既有四个查询**并行**（`async`），只有需要详档时才发（不拖慢概览首屏）。

### 4.5 纯逻辑下沉（可单测）

| 新文件 | 职责 |
| --- | --- |
| `logic/BodyMath.kt` | 归一化序列（时间→x、kg→y）、min/max、按 x 找最近点、tooltip 文案、稀疏点去抖（同天取最后一条） |
| `logic/StageMath.kt` | 分期汇总 → 分钟/百分比/效率；缺失阶段按 0 并标记 `hasData=false` |

## 5. 实施步骤（含门禁）

| 步 | 内容 | 门禁 |
| --- | --- | --- |
| 1 | `logic/BodyMath.kt` + `logic/StageMath.kt` + 单测 | L1：`:app:testDebugUnitTest` |
| 2 | `HcQuery.nightlyRecovery()`；`Snapshot` 加 4 个字段 + 并行查询 | L1 + L2 |
| 3 | `BodyTrendView`（自绘，复用 Paint/Path，onDraw 不分配对象） | L2 |
| 4 | `view_data.xml` 加体成分卡；`MetricStyle` 加 `body`；`MainActivity` 绑定/渲染/拖拽 5 卡 | L2 |
| 5 | 详情 `body` 分支 + 睡眠分期/夜间生理区块（`MetricDetailBinder` 单一实现） | L2 |
| 6 | 真机安装 + 数值核对（与 MCP `query_body_composition` / `query_sleep_stages` 对照）+ 截图 | L4 |
| 7 | 文档回写：`docs/UI.md`、`docs/TESTING.md`、`OPERATIONS.md`、Vault 笔记 | 读回校验 |

**L3（仪器测试）说明**：本次改动确实碰了布局与自绘图表，按 §5 应跑 `:app:connectedDebugAndroidTest`。
本机（phone / Termux）无 Android 模拟器（`emulator` 需 x86_64 宿主），仪器测试只能在 PC/CI 侧跑；
因此本次以 **L1 + L2 + L4（真机截图与数值对照）** 交付，并在 `docs/TESTING.md` 的“覆盖边界”里登记这条待补项。

## 6. 验收结果（2026-10-21 21:30 实测）

| 项 | 结果 | 证据 |
| --- | --- | --- |
| 数据层：体成分序列 | ✅ | MCP `query_body_composition`：30 次测量、BMI 20.7、体脂 14.3%、身高 1.80 m |
| 数据层：睡眠分期 | ✅ | MCP `query_sleep_stages`：深 33 / 浅 141 / REM 16 / 清醒 0，效率 100% |
| 数据层：夜间生理 | ✅ | 新 MCP `query_nightly_recovery`：呼吸率 17（30 晚）、HRV 33（30 晚） |
| L1 单测 | ✅ | 78 个测试全过（新增 `BodyMathTest` 9 + `StageMathTest` 10） |
| L2 lint + debug 构建 | ✅ | `:app:lintDebug` + `:app:assembleDebug` BUILD SUCCESSFUL |
| 装机不崩 | ✅ | 覆盖安装后 `am start` 无 FATAL，`load 3326ms`（概览冷启动，与改动前同量级） |
| 详情页烟测 | ✅ | 依次 `am start -n .MetricDetailActivity --es metric {body,sleep,heart_rate,steps}`，logcat 无 `AndroidRuntime` FATAL（4 个分支的 View 查找/新图表 setData/分期条都跑到了） |
| L4 截图验收 | ✅ | 解锁后逐屏截图核对，见下方“实测截图记录” |
| 长按拖动 5 卡排序 | ✅ | `input draganddrop` 把「体成分」从第 5 位拖到第 2 位；`am force-stop` 重启后顺序仍是 `心率/步数/体成分/血氧/睡眠`（持久化成功） |

### 实测截图记录（2026-10-21 21:33–21:37，真机 phone）

| 屏 | 实测内容 | 截图 |
| --- | --- | --- |
| 数据页 | 5 张卡；`体成分 65.0 kg` / `BMI 20.7 · 10-18 · 26 天记录`；趋势图 min 63.6 / max 67.3；睡眠副文 `深睡33m 浅睡141m REM16m` | `~/pi-work/tmpx/v8.png` |
| 体成分详情 | 标题「体成分」、大数 65.0、`体重趋势 · 26 天记录（x 轴按真实时间）`、统计（最新/BMI 20.7/区间变化 -1.2 kg/有记录的 26 天）、明细 8 行含体脂与 BMI | `~/pi-work/tmpx/v9.png` |
| 睡眠详情 | 分期堆叠条 + `最近一晚分期（190 分钟，效率 100%）`、`深睡 33m（17%）浅睡 141m（74%）REM 16m（8%）`、`呼吸率 17/min · HRV 33ms`、明细 3 晚 | `~/pi-work/tmpx/v7.png` |
| 触摸 tooltip | 体成分详情：`01-25 · 65.9 kg · 21.0` + 竖向虚线 + 吸附圆环；概览心率：`10:47 · 77 bpm` | `v13.png` / `v12.png` |
| 拖拽排序 + 持久化 | 体成分 → 第 2 位；重启后顺序保持 | `v15.png` / `v16.png` |

### L4 过程中发现并修掉的两个 bug（都是截图看出来的，单测抓不到）
1. **详情页标题错**：`MetricDetailActivity.titleOf` 没有 `body` 分支 ⇒ 体成分详情顶栏显示「心率」。
2. **体成分详情 tooltip 不显示**：`MetricDetailBinder.wireTip` 的 `when` 漏了 `BodyTrendView` 分支
   ⇒ 图表能画选中态却没有文字回调。同时把副文从「26 次测量」改成「26 天记录」
   （同一天多次称重只保留最后一次，说“次”不准确）。
