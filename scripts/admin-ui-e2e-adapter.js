/*
 * 端到端测试用的**假 adapter**（只被 scripts/admin-ui-e2e.mjs 复制进临时目录使用）。
 *
 * 内存状态 + 记录 act 调用：验证共享 SPA 的路由、抽屉、确认框、一次性明文、
 * 主题持久化与失败提示，不碰任何真实后端、不连网、不含真实凭据。
 */
(function () {
  const minute = 60e3;
  const now = Date.now();
  const state = {
    me: { id: "1", username: "hiyo", display: "hiyo", role: "admin" },
    version: "0.9.0-e2e",
    now,
    // 这一份假后端同时打开全部 caps：既有 NAS 形状的列表，也有 App 专属的本机访问 / 策略段
    caps: { users: "full", sessions: "full", settings: true, localAccess: true, policies: true },
    overview: {
      sync: { lastSyncAt: now - 7 * minute, lastSyncSource: "xiaomi-cloud", lastSyncRows: 18420, lagMin: 7, pipeline: "ok" },
      backfill: { status: "idle", progress: 1, rangeFrom: "2024-01-01", rangeTo: "2026-11-02", lastRun: now - 2 * 24 * 60 * minute },
    },
    users: [
      { id: "1", username: "hiyo", display: "hiyo", role: "admin", status: "active", totp: true, lastLogin: now - 20 * minute, createdAt: now - 400 * 24 * 60 * minute, scopes: ["admin", "health.read"] },
      { id: "2", username: "ops", display: "运维只读", role: "viewer", status: "active", totp: false, lastLogin: now - 3 * 24 * 60 * minute, createdAt: now - 90 * 24 * 60 * minute, scopes: ["health.read"] },
      { id: "3", username: "xss", display: "<img src=x onerror=window.__xss=1>", role: "viewer", status: "active", totp: false, lastLogin: null, createdAt: null, scopes: ["health.read"] },
    ],
    sessions: [
      { id: "ses_a1", userId: "1", device: "Chrome · macOS", ip: "100.64.1.12", loc: "内网 · Tailscale", createdAt: now - 2 * 60 * minute, lastSeen: now - 3 * minute, current: true },
      { id: "ses_b1", userId: "2", device: "Firefox · Linux", ip: "10.0.0.8", loc: "内网", createdAt: now - 3 * 24 * 60 * minute, lastSeen: now - 3 * 24 * 60 * minute, current: false },
    ],
    tokens: [
      { id: "tok_01", name: "MCP Claude Desktop", prefix: "hch_live_8f2a…", scopes: ["health.read"], status: "active", userId: "1", createdAt: now - 40 * 24 * 60 * minute, lastUsed: now - 15 * minute, expires: null, policy: null, window: null },
    ],
    oauth: [
      { id: "hc-bridge", name: "HC Bridge", clientId: "hc-bridge", kind: "confidential", redirects: ["https://health.example.com/oauth/callback"], status: "active", createdAt: now - 300 * 24 * 60 * minute, lastAuth: now - 5 * minute, policy: null, window: null },
    ],
    localAccess: {
      requireAuth: true, allowLoopbackNoAuth: false, confirmNewClients: true,
      tunnelMode: "off", publicBase: null, hasToken: false, lastProbe: null,
    },
    pending: [
      { id: "req_1", client: "Obsidian MCP", clientId: "obsidian-mcp", redirect: "127.0.0.1", scopes: ["health.read"], createdAt: now - minute, expiresAt: now + 10 * minute },
    ],
    coverage: [
      { type: "heart_rate", label: "心率", rows: 1248356, sources: ["apple_health", "com.google.android.apps.fitness"], days30: 30, pct: 0.98, lastAt: now - 12 * minute },
      { type: "weight", label: "体重", rows: 612, sources: [], days30: 22, pct: 0.73, lastAt: null },
    ],
    sleep: [
      { id: "2026-11-02", date: "2026-11-02", bed: "23:42", wake: "07:08", hours: 7.43, score: null, source: "apple_health", flags: [], note: "" },
      { id: "2026-10-29", date: "2026-10-29", bed: "—", wake: "—", hours: null, score: null, source: "—", flags: ["missing"], note: "无样本" },
    ],
    metrics: {
      days: 90,
      steps: { label: "步数", unit: "步", latest: 9842, avg: 8750, series: Array.from({ length: 90 }, (_, i) => 7000 + (i % 7) * 300) },
      heart_rate: { label: "心率（日均）", unit: "bpm", latest: 68, avg: 71, series: Array.from({ length: 90 }, (_, i) => 68 + (i % 5)) },
      sleep: { label: "睡眠时长", unit: "h", decimals: 1, latest: 7.4, avg: 7.1, series: Array.from({ length: 90 }, (_, i) => 7 + (i % 4) / 10) },
    },
    audit: {
      rows: [
        { id: "1", at: now - 8 * minute, actor: "hiyo", action: "token.create", target: "tok_01", outcome: "ok", detail: "创建静态令牌" },
        { id: "2", at: now - 20 * 60 * minute, actor: "unknown", action: "login.fail", target: "", outcome: "fail", detail: "密码错误" },
      ],
      chainOk: true,
    },
    security: {
      totpEnabled: true, totpEnrolledAt: now - 380 * 24 * 60 * minute, passwordChangedAt: now - 45 * 24 * 60 * minute,
      passwordSet: true, sessionTtlHours: 0.083, allowPasswordLogin: true, requireTotp: true,
      maxFailedLogins: 5, lockoutMinutes: 15, settingsEditable: true,
    },
    csrf: "e2e-csrf",
  };

  window.__calls = [];
  window.__clip = [];
  window.__logout = false;

  window.AdminAPI = {
    async state() {
      return JSON.parse(JSON.stringify(state));
    },
    async act(name, payload) {
      window.__calls.push({ name, payload });
      if (name === "user.toggle") {
        const u = state.users.find((x) => x.id === payload.id);
        if (u) u.status = payload.enabled ? "active" : "disabled";
        return { ok: true, data: {} };
      }
      if (name === "session.revoke") {
        state.sessions = state.sessions.filter((s) => s.id !== payload.id);
        return { ok: true, data: {} };
      }
      if (name === "session.revoke-others") {
        const n = state.sessions.filter((s) => !s.current).length;
        state.sessions = state.sessions.filter((s) => s.current);
        return { ok: true, data: { revoked: n } };
      }
      if (name === "token.create") {
        const created = {
          id: "tok_new", name: payload.name, prefix: "hch_new_ab12…", scopes: payload.scopes || [],
          status: "active", userId: "1", createdAt: Date.now(), lastUsed: null, expires: null,
        };
        state.tokens.unshift(created);
        return { ok: true, data: { id: created.id, secret: "hch_new_ab12_SECRET_VALUE", prefix: created.prefix } };
      }
      if (name === "token.revoke") {
        const t = state.tokens.find((x) => x.id === payload.id);
        if (t) t.status = "revoked";
        return { ok: true, data: {} };
      }
      if (name === "oauth.create") {
        state.oauth.push({
          id: "new-cli", name: payload.name, clientId: "new-cli", kind: payload.kind,
          redirects: payload.redirects, status: "active", createdAt: Date.now(), lastAuth: null,
        });
        return { ok: true, data: { clientId: "new-cli", secret: "cli-secret-once" } };
      }
      if (name === "pending.decide") {
        state.pending = state.pending.filter((p) => p.id !== payload.id);
        return { ok: true, data: {} };
      }
      if (name === "audit.verify") return { ok: true, data: { chainOk: true, checked: 2 } };
      if (name === "coverage.backfill") return { ok: true, data: { queued: true } };
      if (name === "security.totp-begin" || name === "security.totp-rebind") {
        return { ok: true, data: { secret: "JBSWY3DPEHPK3PXP", uri: "otpauth://totp/HC?secret=JBSWY3DPEHPK3PXP" } };
      }
      if (name === "security.totp-confirm") {
        return { ok: true, data: { recoveryCodes: ["AAAA-BBBB", "CCCC-DDDD"] } };
      }
      if (name === "security.totp-disable") return { ok: true, data: {} };
      // ---- 本机访问 / 策略（App 专属）
      if (name === "local.save-public") {
        const local = state.localAccess;
        local.tunnelMode = payload.mode || "off";
        local.publicBase = payload.publicBase || null;
        if (payload.token) local.hasToken = true;
        local.lastProbe = null;
        return { ok: true, data: {} };
      }
      if (name === "local.clear-token") {
        state.localAccess.hasToken = false;
        return { ok: true, data: {} };
      }
      if (name === "local.require-auth" || name === "local.loopback" || name === "local.confirm-clients") {
        const local = state.localAccess;
        const on = payload.enabled === true;
        if (name === "local.require-auth") local.requireAuth = on;
        else if (name === "local.loopback") local.allowLoopbackNoAuth = on;
        else local.confirmNewClients = on;
        return { ok: true, data: {} };
      }
      if (name === "local.probe") {
        const at = Date.now();
        const detail = "GET /health → 200";
        state.localAccess.lastProbe = { ok: true, at, detail };
        return { ok: true, data: { ok: true, at, detail } };
      }
      if (name === "local.command") {
        // 只在 full 里出现 token（与原生同口径）；占位版永远可用
        const command = payload.full
          ? "cloudflared tunnel run --token tok-e2e-secret"
          : "cloudflared tunnel run --token \"$TUNNEL_TOKEN\"";
        return { ok: true, data: { command } };
      }
      if (name === "token.policy" || name === "token.window" || name === "oauth.policy" || name === "oauth.window") {
        const list = name.indexOf("token.") === 0 ? state.tokens : state.oauth;
        const row = list.find((x) => x.id === payload.id);
        if (row) {
          if (name.slice(-7) === ".policy") row.policy = payload.policy;
          else row.window = payload.window;
        }
        return { ok: true, data: {} };
      }
      if (name === "fail.always") return { ok: false, error: "后端拒绝了这个操作" };
      return { ok: true, data: {} };
    },
    async clipboard(text) {
      window.__clip.push(String(text));
      return { ok: true };
    },
    logout() {
      window.__logout = true;
    },
  };
})();
