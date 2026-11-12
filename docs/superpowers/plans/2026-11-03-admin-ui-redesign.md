# 管理后台 UI 重设计：实施计划

日期：2026-11-03
设计：`docs/superpowers/specs/2026-11-03-admin-ui-redesign.md`
原型：`docs/admin-ui-proto/`
策略：NAS 与 App 两个 worktree 并行；各自任务完成即提交；最后合并、部署、装机。

## 共享前置（main）

- [x] `scripts/build-admin-ui.py`：从原型生成 `admin.css` / `sprite.svg`。
- [ ] **S1 共享 SPA**：`admin-ui/` 从原型收敛；`AdminAPI` 接口；NAS/App 两个 adapter；
      `data.js`/`render.js`/`app.js` 去掉 mock；路由/动作统一。
- [ ] **S2 构建**：`scripts/build-admin-ui.py` 生成
      `deploy/nas/hcb/admin_ui/` 与 `app/src/main/assets/admin/`；注入对应 adapter。

## Track N：NAS（API + 页面）

- [ ] **N1 stats**：`hcb/stats.py`（已起步，保留/cherry-pick）。
- [ ] **N2 API**：`GET /admin/api/state`、`POST /admin/api/act`、CSRF、401 JSON、审计。
- [ ] **N3 静态资源**：`/admin` SPA、`/admin/assets/*`、install.sh 复制 `admin_ui/`。
- [ ] **N4 动作**：users/sessions/tokens/oauth/pending/security/coverage/audit 全量。
- [ ] **N5 旧路由兼容**：`/admin/users` 等 302 到 hash；legacy SSR 保留。
- [ ] **N6 测试**：state/act/CSRF/401、聚合、页面 smoke。
- [ ] **N7 本地全量** + **N8 部署**线上验证。

## Track A：App（bridge + 宿主）

- [ ] **A1 状态/动作层**：`AdminStateJson`、`AdminActions`。
- [ ] **A2 bridge/宿主**：`AdminBridge`、`McpWebAdminActivity`、门禁/超时/FLAG_SECURE。
- [ ] **A3 共享资产**：构建时注入 app adapter；隐藏/改造 users、sessions。
- [ ] **A4 健康桥**：coverage/sleep/metrics/sync + 回填。
- [ ] **A5 安全**：TOTP/改密/key/tunnel/一次性明文。
- [ ] **A6 测试** + **A7 构建装机**。

## 合并与验收

- [ ] PR：`feat/nas-admin-ui`、`feat/app-admin-ui` 分别合并回 main。
- [ ] NAS 线上 smoke：`/admin`、`/admin/coverage`、`/admin/sleep`、`/admin/metrics`、
      `/login`、`/admin/security`；移动端 390px。
- [ ] App 真机 smoke：解锁、导航、一次性 key、覆盖/睡眠/指标、锁定、`/admin` 404。
- [ ] 更新 Obsidian NAS/phone 记录；版本 `0.9.0` / versionCode 19。
- [ ] 残留：DeepSeek 余额、真机性能、覆盖率聚合耗时记录。

## 完成定义

- 两端都按原型四组导航与视觉落地，数据为真实后端。
- 所有现有测试 + 新测试通过；NAS 线上可用；App APK 已装并验收。
- 无新依赖；无 `/admin` HTTP 面进入 App。
