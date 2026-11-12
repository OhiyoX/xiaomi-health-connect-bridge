# HC Bridge — UI / 设计规范与实现说明

> 2026-10-18 重做。记录**参考了什么规范**、**照它做了什么决定**、以及**实测数据**。
> 变更前是一屏到底的控制台（卡片堆叠 + 运行时代码拼样式），现在改为 M3 分层导航 + 各指标专属可视化。

## 1. 参考来源（一手）

| 来源 | 用途 |
|---|---|
| [Material Design 3 · Navigation bar](https://m3.material.io/components/navigation-bar/guidelines) | 顶级目的地 3–5 个、等重要性；用 navigation bar 切换 |
| [android/nowinandroid](https://github.com/android/nowinandroid) | 单 Activity + 顶级目的地切换、每屏一个职责的样例做法 |
| [android/compose-samples](https://github.com/android/compose-samples)（Jetchat 等） | M3 组件语义（AppBar / Chip / Card / Switch 的使用边界） |
| [MDC-Android 1.14.0（Material 3 Expressive）](https://github.com/material-components/material-components-android/releases) | 视图体系的最新组件与 `Widget.Material3.*` 样式 |
| [M3 · Canonical layouts](https://m3.material.io/foundations/layout/canonical-layouts) / [Usability](https://m3.material.io/foundations/usability) | 层级、留白、避免信息过载 |
| [M3 tooltips](https://m3.material.io/components/tooltips/guidelines) | 图表触摸浮层样式（surfaceContainerHighest + onSurface，非彩色气泡）|
| [Palette 17 项目](https://git.example.com/ai-chatgpt/palette17) | 系统 Material You 配色的来源（`theme_customization_overlay_packages`），本 app 用 `DynamicColors` 跟随 |

## 2. 结构（分层，不再是一页）

```
MainActivity (shell, 单 Activity)
├── MaterialToolbar  标题随 tab；右侧 action = 刷新（图标，不塞文字按钮）
├── FrameLayout      三视图切换（setVisibility，不重建窗口）
│   ├── view_overview  概览：状态行 → 24h 心率卡 → 今日 → 需要处理 → 主操作
│   ├── view_data      数据：心率 / 睡眠 / 血氧 / 步数（各带专属图形）
│   └── view_settings  设置：同步 · 远端 · 系统与权限 · 日志
└── BottomNavigationView（概览 / 数据 / 设置）
MetricDetailActivity  二级页：MaterialToolbar + 返回箭头 + 大数值 + 图形 + 统计 + 明细
```

要点：
- **切 tab 不新建 Activity**（旧做法每切一次都起新窗口，底栏图标会闪一下，返回栈也乱）。
- 状态不再占用顶栏右上角：改为顶栏下方的状态行（chip + 同步摘要）。**chip 无描边**——只有它带边框会显得突兀。
- 卡片统一 `?attr/colorSurfaceContainer` + 12dp 圆角 + 0 描边；间距一律 4dp 网格。

## 3. 指标差异化（同一套外壳，不同图形语义）

| 指标 | 语义 | 图形 | 交互 |
|---|---|---|---|
| 心率 | 连续趋势 | 折线 + 面积 | 按住/拖动 → 十字线 + `HH:mm · bpm` tooltip |
| 睡眠 | 每晚一段 | actogram：纵轴 18:00→12:00，柱 = 入睡→起床 | 点某晚 → 高亮 + `MM-dd 23:14–07:30 8h16m` |
| 血氧 | 贴顶、偶发掉点 | 点阵 + 90% 阈值线（<90 变红） | 点某点 → 该样本百分比 |
| 步数 | 配额型 | 目标圆环 + 中心数字 | —（数值本身已在环心） |

统一文字规格：图形内刻度按 **labelSmall→bodyMedium** 量级绘制（最终 14sp + 0.01 letterSpacing）；
tooltip 固定在**卡片标题行右侧**、4dp 圆角，**配色跟随所在卡片**——底 = 该卡 `onXxxContainer`、文字 = 该卡 `XxxContainer`；
图面只保留竖向虚线 + 吸附圆环，不画气泡。

## 4. 颜色（按官方角色，不做装饰性用色）

一度试过"每个指标一个容器色块"，但那属于自创用色。**现行规则**：
- 卡片一律 `?attr/colorSurfaceContainer`（M3 filled card），圆角 12dp，无描边；
- 图表 accent 跟随所在容器的配对角色（例：卡片 `primaryContainer` → 图线 `onPrimaryContainer`），
  仍全部是官方角色，不出现自造色值；异常值（血氧 < 90%）用 `colorError`；
- 数据页四类指标各用一组官方容器角色（primary / tertiary / secondary / surfaceContainerHighest）做"类别色"，
  文字取对应 `onXxxContainer`，保证配对对比度；
- 文字只用角色：`colorOnSurface` / `colorOnSurfaceVariant` / `colorOnXxxContainer`（仅 chip 等容器组件）；
- 字号用 M3 type scale 的角色，不自定义：卡片数值 `titleLarge`、副文 `bodySmall`、分组标题 `titleMedium`、
  详情大数 `displaySmall`、图表刻度与浮层 `labelSmall`（11sp）。

## 5. 颜色引擎

- 默认 `DynamicColors.applyToActivitiesIfAvailable`（`App.kt`），跟随系统 Material You；开关在 设置 → 系统与权限（关掉回落到 `values/colors.xml` 自带青色谱）。
- 取色统一走 `View.m3("colorPrimary")`：运行时按 attr 名解析，**不绑定库的 R 符号**（升级 material 1.12→1.14 时 `com.google.android.material.R.attr.*` 直接编不过，这条规避掉了）。
- 语义色只用角色：primary / primaryContainer / onXxx / errorContainer / onSurfaceVariant / outline。

## 6. 性能与效率（实测）

| 项目 | 数值 | 说明 |
|---|---|---|
| 冷启动 | 311–362 ms（`am start -W` WaitTime） | 一帧未掉帧 |
| 首屏数据 | 约 0.7–4.1 s | 首次含 `coverage` 全表扫描（3.4 万条）；结果持久化 6h |
| 切 tab 首次 | 365 ms | 只补缺失档（血氧/睡眠） |
| 切 tab 复访 | 2 ms | 命中内存缓存 |
| 帧数据 | 43 帧 / jank 0 / p50 5ms / p90 5ms / p99 8ms | `dumpsys gfxinfo`（滚动 + 切 tab） |

做法：
- `Snapshot` 两档内存缓存（快档 45s：状态/24h 心率/今日汇总；详档 180s：睡眠/血氧）+ 覆盖率持久缓存 6h。
- 四个 HC 查询用 `async` **并行**（原来串行）。
- 切 tab 先用 `Snapshot.cached()` 立刻铺一次，再后台刷新。
- 自绘 view 复用 `Paint`/`Path`/`RectF`，`onDraw` 内不分配对象；触摸只做最近点选择。

## 7. 自查与验证（可复跑）

- 布局自查脚本（spacing 4dp 网格、硬编码 textSize、卡片圆角/描边、组件样式、死布局）：
  `python tools/audit_layouts.py`（脚本在 PC 临时目录，规则见本节描述，可按需入仓）。
- 交互验证：按 resource-id 定位点击 + 逐步断言（14 项：冷启动、tab 切换不重建 Activity、四条图形各自渲染、详情页、chip 持久化、顶栏刷新、今日卡跳转）。
- 颜色审计：从截图取真实像素算角色配对对比度（onSurface/surface 13.9、onSurfaceVariant/surfaceContainer 7.6，均过 WCAG AA），并扫描是否残留静态配色（曾抓到 sparkline 误用静态 `#4CDADD`）。

## 8. 已知取舍

- 图表消费触摸，所以点卡片正中不再跳详情；详情入口是卡内文字行与"查看心率详情 ›"。
- 设置页的 Bearer token 默认遮罩（`endIconMode=password_toggle`），服务地址为可编辑输入框（不写死默认值以外的假设）。
- `coverage` 首次仍慢（要扫全表）；只在缓存过期（6h）时补一次。
- 睡眠只取"每晚最长一段"，不累加多段/多来源（早期累加出过 18h32m 的假值）。

## 9. 规范对照（2026-10-18 复读后自查）

回读来源：M3 Navigation bar（3–5 个同权顶级目的地、跨屏一致）、M3 Data visualization（准确性优先；给出图表"想表达什么"的摘要，
随交互刷新；用高亮让人知道自己处在哪）、M3 Data accessibility 博客、Now in Android 导航结构、Google Fit 交互记录、
min–max / LTTB 降采样对比、ODI3/T90 临床口径。

| 规范点 | 现状 | 证据 |
|---|---|---|
| 3–5 个同权顶级目的地、跨屏一致 | 3 个（概览/数据/设置），detail 页按二级处理带返回 | `activity_shell.xml` + `BottomNavigationView`；`MetricDetailActivity` 顶栏返回 |
| 切 tab 不重建窗口 | 单 Activity 切视图 | 早前验证 `before == after`（Activity 名不变） |
| 图表要有"想表达什么"的摘要 | 卡内标题 + 副标题摘要；触及时摘要位置显示选中值 | `hrSub`/`spo2Sub`/`sleepSub` + 标题行 tooltip |
| 用高亮让人知道处在哪 | 触及时竖向虚线 + 吸附圆环 | `HrSparkView`/`Spo2RangeView`/`SleepBarsView` |
| 图表要有文本替代 | 四个图表均设 `contentDescription`（含最新值/区间） | `MainActivity.renderData()` |
| 降采样不能吃掉极值 | 血氧 min–max 包络；心率桶均值（趋势用途） | `Spo2RangeView.downsampleMin` → min/max 桶；`HrSparkView.downsample` |
| 血氧掉点用临床口径 | 相对中位基线下降 ≥3% 标 `colorError`，详情给 ODI3 口径计数 | `Spo2RangeView.isDip`、`MetricDetailActivity` 血氧统计 |
| Fit 式交互（虚线跟随/吸附/浮层/淡出） | 已实现；浮层固定在卡片标题行（业主确定） | 三个图表视图 `onTouchEvent` |

## 10. 自适应（平板 / expanded 窗口）

依据：Android「Build adaptive navigation」+ M3「Navigation rail」+ window size classes（compact <600dp / medium 600–839 / expanded ≥840）。
实测目标机 Xiaomi Pad 6 Pro：1800×2880 @400dpi → **720dp 宽 = expanded**。

实现（资源限定符 `layout-sw600dp`，与手机共用同一套代码与 id）：
- `activity_shell.xml`：左侧 **NavigationRailView**（expanded 用 rail，compact 仍用底部 NavigationBar），内容区在右；代码里 nav 类型统一为 `NavigationBarView` 基类，两套布局都能跑。
- `view_overview.xml`：**双栏**——左栏 24h 心率 hero（图高 200dp），右栏 今日 / 需要处理 / 主操作（M3 supporting-pane 思路）。
- `view_data.xml`：**两列栅格**——左列 步数 + 血氧，右列 心率 + 睡眠；图高 110/140dp。
- 平板不启用长按拖拽排序（栅格下拖拽语义不成立），手机仍保留。

## 10.5 体成分与睡眠分期（2026-10-21 增补）

数据侧补齐后（见 `OPERATIONS.md` §十·补四~七），UI 增加两块，仍守本文件的规则：

| 新增 | 语义 | 图形 | 颜色 |
| --- | --- | --- | --- |
| 数据页「体成分」卡 | **稀疏时间序列**（5 年约 30 次称重） | `BodyTrendView`：点 + 连线；x 轴按**真实时间比例**；只标 min/max；触摸吸附最近点 | `colorSurfaceContainerHigh` / `colorOnSurface`（官方角色，与步数的 Highest 区分） |
| 睡眠详情「分期」 | 一晚的构成比 | `StageBarView`：横向堆叠条 深→浅→REM→清醒，宽度=占比 | 睡眠容器 on-color 的 100/70/45/25% 透明度层次，不自造 RGB |
| 睡眠详情「夜间生理」 | 两个标量 + 近 7 晚 | 纯文本（`呼吸率 17/min · HRV 33ms`），不抢主图 | 角色文字色 |

为什么体成分用**真实时间比例**而不是等距下标：等距会把"相隔半年"和"相隔一天"画成一样宽，
两点的斜率就没有意义了；`logic/BodyMathTest` 专门钉了这条（`xAxisIsRealTimeProportional`）。

数据缺失的处理：体重/体脂/BMI 任一项缺失 → 副文自动省略该项（`BodyMath.summaryLine`）；
睡眠分期缺失 → 显示「最近一晚无分期数据」；呼吸率/HRV 缺失 → 显示「—」并注明「设备未提供」，
**绝不显示 0**（0 会被误读成真实测量值）。

卡片顺序：`Prefs.cardOrder` 从 4 卡扩到 5 卡，老版本存下的 4 卡顺序会**自动补上 body 到末尾**，
不整份丢弃（丢弃会让用户调过的顺序突然失效）。

## 11. 单一来源（避免重复实现）

- `MetricStyle`：每个指标的「卡片容器色 / on-container / 图上 accent / 刻度色」只在这里定义一次；
  数据页卡片、详情页、平板右栏全都调它（accent 会校验与容器对比度，<3:1 时按底色亮度兜底）。
- `styleTooltip(tip)`：全 app 唯一的 tooltip 样式（`md_inverse_surface` 底 + `md_inverse_on_surface` 字 + 4dp 圆角 + labelMedium）。
  注意**先 setTextAppearance 再 setTextColor**，否则颜色会被外观覆盖（踩过）。
- `MetricDetailBinder`：详情渲染唯一实现，手机二级页与平板右栏共用。

## 12. 交互细节

- 长按拖拽：**「长按成立 + 出现位移」才抬卡**（`cardElevation=12dp` 真实阴影 + 1.02 缩放），
  `getChildDrawingOrder` 保证画在最上层，松手恢复 0dp。
  - 为什么要位移才抬：只看长按会和「长按图表看 tooltip / 点图表进详情」撞在一起（2026-10-18 实测报障）。
  - 按下瞬间**谁也不抢手势**：图表在 `ACTION_DOWN` 抢拦截会让排序容器的 `onInterceptTouchEvent` 收不到事件，
    长按永远检测不到（这是「拖动完全不动」的根因）；图表只在**明显横向**拖动时才抢，抬手释放。
- 顺序持久化：业务键存在**资源键 tag**（`R.id.tag_reorder_key`）上，不能用 `View.tag`
  （卡片着色写过 `card.tag = fg`，会把它覆盖掉 → 松手读不到键、顺序静默丢失）；启动时 `applyCardOrder()` 按 Prefs 重排。
- 血氧点阵：均值点半径 5px、选中 10px，min–max 带宽 7px（之前偏小）。
- 图表 tooltip 统一：卡片标题行右侧一个 `TextView`，抬手后自动消除（血氧 1.5s、心率 140ms 淡出、睡眠 1.8s）；
  三个图表都必须回调 `onSelect(null)`，否则会留下一个永不消失的值（血氧曾如此）。
- 副设备（secondary）不再提示「小米账号未配置」——它从自建服务拉数据。
