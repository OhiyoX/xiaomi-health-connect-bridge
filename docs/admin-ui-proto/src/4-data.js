<script>
/* HC Health MCP Admin — UI prototype
   Mock data only. Auth admin + health data ops under one shell.
   Scale mimics live: heart_rate ~1.2M rows, multi-source weight, recent sync. */
"use strict";
const NOW = new Date("2026-11-03T08:16:00+08:00");
const VERSION = "0.1.0-proto";
const H = 3600e3, D = 24 * H, M = 60e3;
const ago = (ms) => new Date(NOW - ms).toISOString();

const SYNC = {
  lastSyncAt: ago(7 * M),
  lastSyncSource: "apple_health",
  lastSyncRows: 18420,
  backfill: { status: "idle", progress: 1, rangeFrom: "2024-01-01", rangeTo: "2026-11-02", lastRun: ago(2 * D) },
  pipeline: "ok",
  lagMin: 7,
};

const COVERAGE = [
  { type: "heart_rate", label: "心率", rows: 1248356, sources: ["apple_health", "huawei_health"], days30: 30, lastAt: ago(12 * M), pct: 0.98 },
  { type: "steps", label: "步数", rows: 982441, sources: ["apple_health", "huawei_health"], days30: 30, lastAt: ago(18 * M), pct: 1 },
  { type: "sleep", label: "睡眠", rows: 1482, sources: ["apple_health"], days30: 29, lastAt: ago(6 * H), pct: 0.97 },
  { type: "weight", label: "体重", rows: 612, sources: ["apple_health", "manual", "huawei_health"], days30: 22, lastAt: ago(1 * D), pct: 0.73 },
  { type: "blood_oxygen", label: "血氧", rows: 210445, sources: ["apple_health"], days30: 28, lastAt: ago(40 * M), pct: 0.93 },
  { type: "resting_hr", label: "静息心率", rows: 1098, sources: ["apple_health"], days30: 30, lastAt: ago(8 * H), pct: 1 },
  { type: "active_energy", label: "活动能量", rows: 874220, sources: ["apple_health", "huawei_health"], days30: 30, lastAt: ago(25 * M), pct: 0.99 },
  { type: "workout", label: "锻炼", rows: 1266, sources: ["apple_health", "garmin"], days30: 18, lastAt: ago(2 * D), pct: 0.6 },
];

const USERS = [
  { id: "usr_01", username: "hiyo", display: "hiyo", role: "admin", status: "active", totp: true, lastLogin: ago(20 * M), createdAt: ago(400 * D), sessions: 2, note: "主管理员" },
  { id: "usr_02", username: "ops", display: "运维只读", role: "viewer", status: "active", totp: true, lastLogin: ago(3 * D), createdAt: ago(90 * D), sessions: 1, note: "健康只读" },
  { id: "usr_03", username: "bridge", display: "hc-bridge", role: "service", status: "active", totp: false, lastLogin: ago(5 * M), createdAt: ago(200 * D), sessions: 1, note: "服务账号 · 同步写入" },
  { id: "usr_04", username: "guest", display: "访客演示", role: "viewer", status: "disabled", totp: false, lastLogin: ago(45 * D), createdAt: ago(60 * D), sessions: 0, note: "已停用" },
];

const SESSIONS = [
  { id: "ses_a1", userId: "usr_01", device: "Chrome · macOS", ip: "100.64.1.12", loc: "内网 · Tailscale", createdAt: ago(2 * H), lastSeen: ago(3 * M), current: true },
  { id: "ses_a2", userId: "usr_01", device: "Safari · iPhone", ip: "183.xx.xx.41", loc: "公网 · 上海", createdAt: ago(1 * D), lastSeen: ago(6 * H), current: false },
  { id: "ses_b1", userId: "usr_02", device: "Firefox · Linux", ip: "10.0.0.8", loc: "内网 · NAS", createdAt: ago(3 * D), lastSeen: ago(3 * D), current: false },
  { id: "ses_c1", userId: "usr_03", device: "hc-bridge · systemd", ip: "127.0.0.1", loc: "本机", createdAt: ago(14 * D), lastSeen: ago(5 * M), current: false },
];

const TOKENS = [
  { id: "tok_01", name: "MCP Claude Desktop", prefix: "hch_live_8f2a…", scopes: ["health.read", "health.write"], status: "active", userId: "usr_01", createdAt: ago(40 * D), lastUsed: ago(15 * M), expires: null },
  { id: "tok_02", name: "CI 只读探针", prefix: "hch_ro_91bc…", scopes: ["health.read"], status: "active", userId: "usr_03", createdAt: ago(20 * D), lastUsed: ago(1 * H), expires: ago(-90 * D) },
  { id: "tok_03", name: "旧版调试", prefix: "hch_dbg_00ee…", scopes: ["health.read", "admin"], status: "revoked", userId: "usr_01", createdAt: ago(120 * D), lastUsed: ago(100 * D), expires: null },
];

const OAUTH = [
  { id: "cli_01", name: "HC Bridge", clientId: "hc-bridge", kind: "confidential", redirects: ["https://health.example.com/oauth/callback"], status: "active", createdAt: ago(300 * D), lastAuth: ago(5 * M) },
  { id: "cli_02", name: "Obsidian MCP", clientId: "obsidian-mcp", kind: "public", redirects: ["http://127.0.0.1:22360/callback"], status: "active", createdAt: ago(180 * D), lastAuth: ago(2 * D) },
  { id: "cli_03", name: "Legacy iOS", clientId: "hc-ios-legacy", kind: "public", redirects: ["hchealth://oauth"], status: "disabled", createdAt: ago(500 * D), lastAuth: ago(200 * D) },
];

const SLEEP_AUDIT = [
  { id: "slp_01", date: "2026-11-02", bed: "23:42", wake: "07:08", hours: 7.43, score: 86, source: "apple_health", flags: [], note: "" },
  { id: "slp_02", date: "2026-11-01", bed: "00:18", wake: "07:02", hours: 6.73, score: 72, source: "apple_health", flags: ["short"], note: "不足 7h" },
  { id: "slp_03", date: "2026-10-31", bed: "23:10", wake: "06:55", hours: 7.75, score: 91, source: "apple_health", flags: [], note: "" },
  { id: "slp_04", date: "2026-10-30", bed: "01:05", wake: "08:40", hours: 7.58, score: 68, source: "apple_health", flags: ["late_bed"], note: "入睡偏晚" },
  { id: "slp_05", date: "2026-10-29", bed: "—", wake: "—", hours: null, score: null, source: "—", flags: ["missing"], note: "当日无睡眠样本" },
  { id: "slp_06", date: "2026-10-28", bed: "22:50", wake: "06:30", hours: 7.67, score: 88, source: "apple_health", flags: [], note: "" },
  { id: "slp_07", date: "2026-10-27", bed: "23:55", wake: "07:20", hours: 7.42, score: 80, source: "apple_health", flags: ["fragmented"], note: "中途醒来 2 次" },
];

/* 14-day series for sparklines */
function series(seed, n, base, amp) {
  const out = [];
  let v = base;
  for (let i = 0; i < n; i++) {
    v = Math.max(0, v + Math.sin((i + seed) * 0.9) * amp * 0.35 + ((i * 17 + seed * 3) % 7 - 3) * amp * 0.08);
    out.push(Math.round(v * 10) / 10);
  }
  return out;
}
const METRICS = {
  steps: { label: "步数", unit: "步", latest: 9842, avg14: 8750, series: series(1, 14, 8200, 2200).map(Math.round) },
  heart_rate: { label: "心率（日均）", unit: "bpm", latest: 68, avg14: 71, series: series(2, 14, 70, 8).map(Math.round) },
  sleep: { label: "睡眠时长", unit: "h", latest: 7.4, avg14: 7.1, series: series(3, 14, 7.0, 1.2).map((x) => Math.round(x * 10) / 10) },
};

const AUDIT = [
  { id: "aud_01", at: ago(8 * M), actor: "hiyo", action: "token.create", target: "tok_01", outcome: "ok", ip: "100.64.1.12", detail: "创建静态令牌 MCP Claude Desktop" },
  { id: "aud_02", at: ago(20 * M), actor: "hiyo", action: "login.success", target: "usr_01", outcome: "ok", ip: "100.64.1.12", detail: "TOTP 通过" },
  { id: "aud_03", at: ago(3 * H), actor: "bridge", action: "sync.ingest", target: "heart_rate", outcome: "ok", ip: "127.0.0.1", detail: "写入 12,408 行" },
  { id: "aud_04", at: ago(5 * H), actor: "ops", action: "user.view", target: "usr_01", outcome: "ok", ip: "10.0.0.8", detail: "查看用户详情" },
  { id: "aud_05", at: ago(1 * D), actor: "hiyo", action: "token.revoke", target: "tok_03", outcome: "ok", ip: "183.xx.xx.41", detail: "撤销旧版调试令牌" },
  { id: "aud_06", at: ago(1 * D), actor: "unknown", action: "login.fail", target: "hiyo", outcome: "fail", ip: "45.xx.xx.9", detail: "密码错误 · 第 2 次" },
  { id: "aud_07", at: ago(2 * D), actor: "hiyo", action: "oauth.disable", target: "cli_03", outcome: "ok", ip: "100.64.1.12", detail: "停用 Legacy iOS 客户端" },
  { id: "aud_08", at: ago(2 * D), actor: "bridge", action: "backfill.run", target: "steps", outcome: "ok", ip: "127.0.0.1", detail: "回填 2024-01 → 2025-06" },
  { id: "aud_09", at: ago(4 * D), actor: "hiyo", action: "security.totp", target: "usr_01", outcome: "ok", ip: "100.64.1.12", detail: "确认 TOTP 已启用" },
  { id: "aud_10", at: ago(6 * D), actor: "ops", action: "login.fail", target: "ops", outcome: "fail", ip: "10.0.0.8", detail: "TOTP 错误" },
];

const SECURITY = {
  totpEnabled: true,
  totpEnrolledAt: ago(380 * D),
  passwordChangedAt: ago(45 * D),
  sessionTtlHours: 168,
  requireTotp: true,
  allowPasswordLogin: true,
  maxFailedLogins: 5,
  lockoutMinutes: 15,
};

const SRC_LABEL = { apple_health: "Apple", huawei_health: "华为", manual: "手动", garmin: "Garmin" };
const ROLE_LABEL = { admin: "管理员", viewer: "只读", service: "服务" };
const USER_ST = { active: ["有效", "ok"], disabled: ["已停用", "off"] };
const TOK_ST = { active: ["有效", "ok"], revoked: ["已撤销", "danger"], expired: ["已过期", "amber"] };
const OAUTH_ST = { active: ["有效", "ok"], disabled: ["已停用", "off"] };
const OUT_ST = { ok: ["成功", "ok"], fail: ["失败", "danger"] };
</script>
