# HC Health 管理后台 · UI 原型

这是 **hc-health-mcp**（现网 `https://health.example.com/admin`）管理端的高保真**视觉重设计**静态原型：把 **鉴权管理** 与 **健康数据运维** 收进同一套设计系统。

> **仅设计原型，未接生产。** 全部为 mock 数据；不要把令牌前缀、会话 IP 当成真实密钥。现网仍是 Python SSR，本目录不会也不应部署为生产 UI。

视觉对齐已认可的 Admin / pan-scheduler 体系：

- 4px 栅格 · 间距 4/8/12/16/24/32
- 字号只用 **13 / 14 / 16 / 20**，字重只用 **400 / 600**；**禁止 serif**（`--font` 仅 sans-serif，`--mono` 仅等宽）
- 页头 / 侧栏品牌条 / 抽屉头：**52px**
- `data-theme` 深浅两套 token（accent / amber / danger）
- 列表整页；详情 / 创建用**右抽屉**（桌面）/ **底 sheet**（移动 ≤720px）
- 危险操作分层：普通撤销 / 停用 → 确认框；**仅高爆操作**（删除用户、重新绑定 TOTP）→ **type-to-confirm**
- Toast；中文文案；技术 key 仅 mono 次要文字
- Hash 路由 `#/...`；单文件 `index.html`；无 Vue/React/构建/外链 CDN

## 怎么打开

```bash
# 直接用浏览器打开（推荐）
xdg-open file:///workspace/hc-health-admin-proto/index.html
# 或任意静态服务器
python3 -m http.server -d /workspace/hc-health-admin-proto 8766
```

然后访问 `http://127.0.0.1:8766/`。默认进入 `#/overview`（已登录态，方便评设计）。看登录 / TOTP：打开 `#/login`。

窗口宽度 **≤720px** 自动切移动端：底栏四键 + 表格改卡片 + 抽屉改底 sheet。深浅色：侧栏 /「更多」/ 安全设置 / 登录页主题按钮，或快捷键 `t`。

重新拼接单文件：

```bash
cd /workspace/hc-health-admin-proto
cat src/1-head.html src/2-css.html src/3-body.html src/4-data.js src/5-render.js src/6-app.js > index.html
```

## 路由表

| Hash | 说明 |
| --- | --- |
| `#/login` | 登录（壳外） |
| `#/login/otp` | TOTP 六位码（壳外） |
| `#/overview` | 同步 / 回填状态 + 覆盖 KPI |
| `#/users` · `#/users/<id>` | 用户列表；id 开抽屉 |
| `#/sessions` · `#/sessions/<id>` | 会话；可撤销（普通确认） |
| `#/tokens` · `#/tokens/<id>` | 静态令牌；新建明文一次；撤销用普通确认 |
| `#/oauth` · `#/oauth/<id>` | OAuth 客户端 |
| `#/coverage` · `#/coverage/<type>` | 按类型/来源覆盖表 |
| `#/sleep` · `#/sleep/<id>` | 睡眠体检列表 |
| `#/metrics` | 步数 / 心率 / 睡眠 SVG sparkline |
| `#/audit` | 审计列表（成功/失败筛选） |
| `#/security` | TOTP 状态、改密表单 mock、主题 |

### Dev 参数（需 `?dev=1`）

| 参数 | 作用 |
| --- | --- |
| `theme=light\|dark` | 强制主题 |
| `empty=<route>` | 指定路由空状态 |
| `lock=1` | 登录页锁定态 |
| `kbd=1` | 启动时打开快捷键面板 |

例：`index.html?dev=1&theme=light#/overview`

## 信息架构（侧栏四组）

1. **工作台** — 概览  
2. **身份与接入** — 用户、会话、静态令牌、OAuth 客户端  
3. **健康数据** — 覆盖、睡眠体检、指标趋势  
4. **系统** — 审计、安全设置  

### 移动端底栏（≤720px）

| 键 | 路由 / 行为 |
| --- | --- |
| 概览 | `#/overview` |
| 身份 | `#/users` |
| 健康 | `#/coverage` |
| 更多 | 弹出全部导航 + 主题切换 + 退出 |

断点：`max-width: 1100px`（KPI / 图表折列）；`max-width: 720px`（壳 → 底栏、抽屉 → sheet、表 → 卡片）。触控目标在移动样式下抬到约 **40–44px+**。

## 确认层级（UX）

| 操作 | 交互 |
| --- | --- |
| 撤销单个会话 / 单个令牌、停用用户、停用 OAuth、退出 | 普通确认框或主按钮 + toast |
| 删除用户、重新绑定 TOTP | **type-to-confirm**（输入用户名 /「重新绑定」） |
| 新建令牌 | 模态展示明文一次 + 复制 |

## Mock 说明

- 时间锚点：`2026-11-03T08:16+08:00`（Asia/Shanghai）
- `heart_rate` ≈ **1,248,356** 行；步数 / 活动能量近百万级；体重多来源（Apple / 华为 / 手动）
- 最近同步约数分钟前；回填状态空闲
- 登录：任意非空密码 → OTP；OTP：任意 6 位数字
- `localStorage` 键：`hc-health-admin.theme`

## 目录

```
hc-health-admin-proto/
  index.html          # 拼接后的单文件
  README.md
  src/
    1-head.html       # doctype + tokens + CSS
    2-css.html        # </style></head>
    3-body.html       # 图标 sprite + 壳挂载点
    4-data.js         # mock
    5-render.js       # 页面 / 抽屉渲染
    6-app.js          # 路由 / 交互
  screenshots/        # 可选截图
```
