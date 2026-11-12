"""共享管理 SPA 的 NAS 端测试：状态形状、静态资源、CSRF/同源/401、19 个动作与审计。

契约：``admin-ui/API.md``；旧 SSR 路由的回退行为在 ``test_admin.py`` 里覆盖。
"""
from __future__ import annotations

import json
import os
import re
import sqlite3
import sys
import time
import unittest
from unittest import mock

TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
if TESTS_DIR not in sys.path:
    sys.path.insert(0, TESTS_DIR)

from test_admin import AdminBase  # noqa: E402
from test_http import ADMIN_PW, Browser, STATIC_TOKEN, hidden  # noqa: E402

from hcb import admin_api  # noqa: E402
from hcb import auth  # noqa: E402
from hcb import db as hdb  # noqa: E402
from hcb import oauth  # noqa: E402
from hcb import stats  # noqa: E402
from hcb import totp  # noqa: E402

SPA_CSP = ("default-src 'none'; script-src 'self'; "
           "style-src 'self' 'unsafe-inline'; connect-src 'self'; "
           "img-src 'self' data:; base-uri 'none'; frame-ancestors 'none'")
ASSET_TYPES = {
    "admin.css": "text/css",
    "data.js": "text/javascript",
    "render.js": "text/javascript",
    "app.js": "text/javascript",
    "adapter.js": "text/javascript",
    "sprite.svg": "image/svg+xml",
}


class ApiBase(AdminBase):
    def setUp(self):
        super().setUp()
        stats.clear_cache()
        admin_api.clear_cache()

    def state(self, browser=None):
        browser = browser or self.browser
        status, text, headers = browser.get("/admin/api/state")
        self.assertEqual(status, 200, text[:500])
        return json.loads(text), headers

    def act(self, name: str, payload: dict | None = None, *, browser=None,
            csrf: str | None = None, headers: dict | None = None,
            body: str | None = None):
        browser = browser or self.browser
        token = csrf
        if token is None:
            data, _headers = self.state(browser)
            token = data["csrf"]
        request_headers = {"Content-Type": "application/json"}
        if token:
            request_headers["X-CSRF-Token"] = token
        request_headers.update(headers or {})
        if body is None:
            body = json.dumps({"name": name, "payload": payload or {}})
        return browser.post("/admin/api/act", body, request_headers)

    def act_json(self, name: str, payload: dict | None = None, **kwargs):
        status, text, headers = self.act(name, payload, **kwargs)
        self.assertEqual(status, 200, text[:500])
        return json.loads(text), headers

    def audit_rows(self, action: str | None = None) -> list[dict]:
        with hdb.db(0) as con:
            sql = "SELECT id, actor, action, result, detail FROM access_log"
            params: tuple = ()
            if action:
                sql += " WHERE action=?"
                params = (action,)
            sql += " ORDER BY id"
            return [dict(row) for row in con.execute(sql, params).fetchall()]


# ------------------------------------------------------------------ 状态
class StateTests(ApiBase):
    def test_state_shape_matches_the_contract(self):
        self.admin_login()
        data, headers = self.state()
        self.assertEqual(headers.get("cache-control"), "no-store")
        self.assertTrue({"version", "now", "me", "overview", "users", "sessions",
                         "tokens", "oauth", "pending", "coverage", "sleep",
                         "metrics", "audit", "security", "caps", "csrf"} <= set(data))
        self.assertEqual(data["me"]["username"], "admin")
        self.assertEqual(data["me"]["role"], "admin")
        self.assertRegex(data["csrf"], r"^[0-9a-f]{64}$")
        self.assertIsInstance(data["now"], int)
        self.assertGreater(data["now"], 1_000_000_000_000)

        overview = data["overview"]
        self.assertEqual(set(overview), {"sync", "backfill", "kpis"})
        self.assertEqual(set(overview["sync"]),
                         {"lastSyncAt", "lastSyncSource", "lastSyncRows",
                          "lagMin", "pipeline"})
        self.assertIn(overview["sync"]["pipeline"], ("ok", "warn", "unknown"))
        self.assertEqual(set(overview["backfill"]),
                         {"status", "progress", "rangeFrom", "rangeTo",
                          "lastRun", "note"})
        self.assertEqual(overview["backfill"]["status"], "idle")
        self.assertGreaterEqual(overview["kpis"]["activeUsers"], 1)

        self.assertTrue(data["users"])
        admin_row = data["users"][0]
        self.assertEqual(admin_row["id"], "0")
        self.assertEqual(admin_row["role"], "admin")
        self.assertIn("admin", admin_row["scopes"])
        current = [row for row in data["sessions"] if row["current"]]
        self.assertEqual(len(current), 1)
        self.assertEqual(current[0]["userId"], "0")
        self.assertRegex(current[0]["id"], r"^[0-9a-f]{64}$")

        self.assertEqual([row["name"] for row in data["tokens"]], ["phone"])
        self.assertEqual(data["tokens"][0]["status"], "active")
        self.assertEqual(data["tokens"][0]["prefix"], "key_1")
        self.assertNotIn(STATIC_TOKEN, json.dumps(data))
        self.assertEqual(data["oauth"], [])
        self.assertEqual(data["pending"], [])
        self.assertEqual(len(data["sleep"]), 30)
        self.assertEqual(data["metrics"]["days"], 90)
        for key in ("steps", "heart_rate", "sleep"):
            self.assertEqual(len(data["metrics"][key]["series"]), 90)
        self.assertEqual(set(data["audit"]), {"rows", "chainOk"})
        self.assertIs(data["audit"]["chainOk"], True)
        self.assertTrue(data["security"]["passwordSet"])
        self.assertFalse(data["security"]["settingsEditable"])
        self.assertEqual(data["caps"], {"users": "full", "sessions": "full",
                                        "settings": False})

    def test_state_survives_a_broken_section(self):
        self.admin_login()
        with mock.patch.object(stats, "coverage",
                               side_effect=RuntimeError("boom")):
            data, _ = self.state()
        self.assertEqual(data["coverage"], [])
        self.assertEqual(data["users"][0]["username"], "admin")
        self.assertTrue(data["csrf"])

    def test_state_reflects_health_data(self):
        now = int(time.time())
        with hdb.db(0) as con:
            con.execute("INSERT INTO heart_rate(ts, bpm, source) VALUES(?,?,?)",
                        (now - 3600, 66, "xiaomi-cloud"))
            con.execute("INSERT INTO steps(ts, steps, source) VALUES(?,?,?)",
                        (now - 7200, 8000, "phone"))
            con.execute("INSERT INTO sleep(bed, wake, minutes, source) "
                        "VALUES(?,?,?,?)",
                        (now - 8 * 3600, now - 3600, 420, "phone"))
            auth.settings_set(con, "last_sync", str(now - 600))
            auth.settings_set(con, "last_sync_stats",
                              json.dumps({"heart_rate": 1, "steps": 1}))
            auth.settings_set(con, "backfill_state", json.dumps(
                {"running": False, "days": 30, "ok": True, "slice": 3,
                 "slices": 4, "done_at": now - 120}))
        self.admin_login()
        data, _ = self.state()
        coverage = {row["type"]: row for row in data["coverage"]}
        self.assertEqual(coverage["heart_rate"]["rows"], 1)
        self.assertEqual(coverage["heart_rate"]["sources"], ["xiaomi-cloud"])
        self.assertEqual(coverage["steps"]["sources"], ["phone"])
        self.assertAlmostEqual(coverage["heart_rate"]["pct"], 1 / 30, places=4)
        self.assertGreaterEqual(coverage["heart_rate"]["days30"], 1)
        self.assertEqual(data["overview"]["sync"]["lastSyncAt"], now - 600)
        self.assertEqual(data["overview"]["sync"]["lastSyncRows"], 2)
        self.assertEqual(data["overview"]["sync"]["lastSyncSource"],
                         "xiaomi-cloud")
        self.assertEqual(data["overview"]["backfill"]["status"], "idle")
        self.assertAlmostEqual(data["overview"]["backfill"]["progress"], 0.75)
        self.assertEqual(data["overview"]["kpis"]["heartRows"], 1)
        today = [row for row in data["sleep"] if row["hours"] is not None]
        self.assertEqual(len(today), 1)
        self.assertEqual(today[0]["bed"], stats.fmt_hhmm(now - 8 * 3600))
        self.assertIsInstance(today[0]["flags"], list)
        self.assertEqual(data["metrics"]["heart_rate"]["latest"], 66)
        self.assertEqual(data["metrics"]["steps"]["latest"], 8000)

    def test_state_requires_admin_session(self):
        status, text, headers = Browser(self.base).get("/admin/api/state")
        self.assertEqual(status, 401, text)
        self.assertEqual(headers.get("cache-control"), "no-store")
        self.assertFalse(json.loads(text)["ok"])

        self.make_user()
        browser, status, _text, _headers = self.user_login(
            "alice", "alice-password-123")
        self.assertEqual(status, 303)
        status, text, _headers = browser.get("/admin/api/state")
        self.assertEqual(status, 403, text)
        self.assertFalse(json.loads(text)["ok"])

    def test_state_never_contains_secrets(self):
        self.admin_login()
        data, _ = self.state()
        csrf = data["csrf"]
        secret_key, _ = self.act_json(
            "token.create", {"name": "laptop", "scopes": ["health.read"]},
            csrf=csrf)
        client, _ = self.act_json(
            "oauth.create", {"name": "Conf", "kind": "confidential",
                             "redirects": ["https://example.test/cb"]},
            csrf=csrf)
        self.act_json("user.create", {
            "username": "alice", "display": "Alice",
            "password": "alice-password-123", "scopes": ["health.read"]},
            csrf=csrf)
        self.assertNotIn(secret_key["data"]["secret"],
                         json.dumps(self.state()[0]))
        self.assertNotIn(client["data"]["secret"], json.dumps(self.state()[0]))
        self.assertNotIn("alice-password-123", json.dumps(self.state()[0]))
        with hdb.db(0) as con:
            blob = json.dumps([dict(row) for row in con.execute(
                "SELECT detail, hash FROM access_log")])
        self.assertNotIn(secret_key["data"]["secret"], blob)
        self.assertNotIn(client["data"]["secret"], blob)
        self.assertNotIn("alice-password-123", blob)


# ------------------------------------------------------------------ 静态资源
class AssetTests(ApiBase):
    def test_spa_shell_and_assets(self):
        self.admin_login()
        status, page, headers = self.browser.get("/admin")
        self.assertEqual(status, 200)
        self.assertEqual(headers.get("content-type"), "text/html; charset=utf-8")
        self.assertEqual(headers.get("cache-control"), "no-store")
        self.assertEqual(headers.get("x-frame-options"), "DENY")
        self.assertEqual(headers.get("content-security-policy"), SPA_CSP)
        self.assertIn("/admin/assets/admin.css", page)
        self.assertIn("/admin/assets/adapter.js", page)
        self.assertIsNone(re.search(r"<script(?![^>]*\bsrc=)", page, re.I))
        for name, mime in ASSET_TYPES.items():
            status, body, asset_headers = self.browser.get(
                "/admin/assets/" + name)
            self.assertEqual(status, 200, name)
            self.assertTrue(asset_headers.get("content-type", "").startswith(mime),
                            (name, asset_headers))
            self.assertEqual(asset_headers.get("cache-control"), "no-store", name)
            self.assertTrue(body, name)

    def test_asset_method_limit(self):
        self.admin_login()
        status, _text, headers = self.browser.post(
            "/admin/assets/app.js", b"", {"Content-Type": "application/json"})
        self.assertEqual(status, 405)
        self.assertEqual(headers.get("allow"), "GET, HEAD")
        status, text, headers = self.browser.post(
            "/admin/api/state", b"", {"Content-Type": "application/json"})
        self.assertEqual(status, 405)
        self.assertEqual(headers.get("allow"), "GET")

    def test_traversal_and_unknown_files_are_404(self):
        self.admin_login()
        for path in ("/admin/assets/../admin.py",
                     "/admin/assets/%2e%2e/admin.py",
                     "/admin/assets/..%2Fadmin.py",
                     "/admin/assets/index.html",
                     "/admin/assets/__init__.py",
                     "/admin/assets/missing.js"):
            status, _text, _headers = self.browser.get(path)
            self.assertEqual(status, 404, path)

    def test_api_unknown_endpoint_is_json_404(self):
        self.admin_login()
        status, text, headers = self.browser.get("/admin/api/nope")
        self.assertEqual(status, 404)
        self.assertEqual(headers.get("content-type"), "application/json; charset=utf-8")
        self.assertFalse(json.loads(text)["ok"])


# ------------------------------------------------------------------ 门禁
class ActGateTests(ApiBase):
    def test_act_requires_admin_session(self):
        status, text, _ = Browser(self.base).post(
            "/admin/api/act", json.dumps({"name": "audit.verify"}),
            {"Content-Type": "application/json"})
        self.assertEqual(status, 401, text)
        self.assertFalse(json.loads(text)["ok"])

    def test_act_rejects_cross_origin(self):
        self.admin_login()
        status, text, _ = self.act(
            "audit.verify", headers={"Origin": "https://evil.example"})
        self.assertEqual(status, 403, text)

    def test_act_requires_csrf_header(self):
        self.admin_login()
        status, text, _ = self.act("audit.verify", csrf="")
        self.assertEqual(status, 400, text)
        status, text, _ = self.act("audit.verify", csrf="deadbeef")
        self.assertEqual(status, 400, text)
        data, _headers = self.state()
        status, text, _ = self.act("audit.verify", csrf=data["csrf"][::-1])
        self.assertEqual(status, 400, text)

    def test_act_rejects_bad_json(self):
        self.admin_login()
        status, text, _ = self.act("audit.verify", body="{not json")
        self.assertEqual(status, 400, text)
        status, text, _ = self.act("audit.verify", body="[]")
        self.assertEqual(status, 400, text)
        status, text, _ = self.act("audit.verify", body=json.dumps({
            "name": 7, "payload": {}}))
        self.assertEqual(status, 400, text)

    def test_normal_user_cannot_act(self):
        self.make_user()
        browser, status, _text, _headers = self.user_login(
            "alice", "alice-password-123")
        self.assertEqual(status, 303)
        status, text, _ = browser.post(
            "/admin/api/act", json.dumps({"name": "audit.verify"}),
            {"Content-Type": "application/json"})
        self.assertEqual(status, 403, text)

    def test_act_body_limit(self):
        self.admin_login()
        data, _ = self.state()
        body = json.dumps({"name": "audit.verify",
                           "payload": {"pad": "x" * (70 * 1024)}})
        status, text, _ = self.act("audit.verify", body=body, csrf=data["csrf"])
        self.assertEqual(status, 413, text)
        self.assertFalse(json.loads(text)["ok"])

    def test_unknown_action_is_ok_false(self):
        self.admin_login()
        data, _ = self.act_json("nope.nope")
        self.assertFalse(data["ok"])
        self.assertIn("未知操作", data["error"])


# ------------------------------------------------------------------ 用户
class UserActionTests(ApiBase):
    def test_user_lifecycle_and_audit(self):
        self.admin_login()
        data, _ = self.act_json("user.create", {
            "username": "alice", "display": "Alice",
            "password": "alice-password-123", "scopes": ["health.read"]})
        self.assertTrue(data["ok"], data)
        user_id = data["data"]["id"]
        with hdb.db(0) as con:
            user = auth.get_user(con, int(user_id))
        self.assertEqual(user["display_name"], "Alice")

        data, _ = self.act_json("user.update", {
            "id": user_id, "display": "Alice 2", "scopes": ["health.read"]})
        self.assertTrue(data["ok"], data)
        with hdb.db(0) as con:
            self.assertEqual(auth.get_user(con, int(user_id))["display_name"],
                             "Alice 2")

        data, _ = self.act_json("user.update", {
            "id": user_id, "password": "another-password-456"})
        self.assertTrue(data["ok"], data)
        with hdb.db(0) as con:
            self.assertTrue(auth.check_user_password(
                con, "alice", "another-password-456"))

        data, _ = self.act_json("user.toggle", {"id": user_id, "enabled": False})
        self.assertTrue(data["ok"], data)
        with hdb.db(0) as con:
            self.assertFalse(auth.get_user(con, int(user_id))["enabled"])

        data, _ = self.act_json("user.delete", {
            "id": user_id, "username": "alice"})
        self.assertTrue(data["ok"], data)
        with hdb.db(0) as con:
            self.assertIsNone(auth.get_user(con, int(user_id)))

        actions = [row["action"] for row in self.audit_rows()]
        for expected in ("admin.user.create", "admin.user.update",
                         "admin.user.enable" if False else "admin.user.disable",
                         "admin.user.delete"):
            self.assertIn(expected, actions)
        self.assertEqual(actions.count("admin.user.update"), 2)
        for row in self.audit_rows():
            self.assertNotIn("alice-password-123", row["detail"] or "")
            self.assertNotIn("another-password-456", row["detail"] or "")

    def test_user_validation_errors(self):
        self.admin_login()
        cases = (
            ("user.create", {"username": "bob", "password": "short",
                             "scopes": ["health.read"]}, "密码至少"),
            ("user.create", {"username": "bob",
                             "password": "bob-password-1234", "scopes": []},
             "至少选择一个权限范围"),
            ("user.create", {"username": "bob",
                             "password": "bob-password-1234",
                             "scopes": ["admin"]}, "至少选择一个权限范围"),
        )
        for name, payload, message in cases:
            data, _ = self.act_json(name, payload)
            self.assertFalse(data["ok"], (name, payload, data))
            self.assertIn(message, data["error"])
        data, _ = self.act_json("user.create", {
            "username": "bob", "password": "bob-password-1234",
            "scopes": ["health.read"]})
        self.assertTrue(data["ok"], data)
        data, _ = self.act_json("user.create", {
            "username": "bob", "password": "bob-password-1234",
            "scopes": ["health.read"]})
        self.assertFalse(data["ok"])
        self.assertIn("已存在", data["error"])

    def test_delete_requires_matching_username(self):
        self.admin_login()
        data, _ = self.act_json("user.create", {
            "username": "carol", "password": "carol-password-123",
            "scopes": ["health.read"]})
        user_id = data["data"]["id"]
        data, _ = self.act_json("user.delete", {"id": user_id,
                                                "username": "nope"})
        self.assertFalse(data["ok"])
        self.assertIn("确认文本", data["error"])
        with hdb.db(0) as con:
            self.assertIsNotNone(auth.get_user(con, int(user_id)))


# ------------------------------------------------------------------ 会话
class SessionActionTests(ApiBase):
    def test_revoke_other_session_and_current_guard(self):
        self.admin_login()
        ua = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
              "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
        with hdb.db(0) as con:
            _sid, _cookie = auth.create_session(
                con, auth.ADMIN_SUBJECT, 0, ttl=auth.ADMIN_SESSION_TTL,
                ua=ua)
        data, _ = self.state()
        self.assertEqual(len(data["sessions"]), 2)
        current = [row for row in data["sessions"] if row["current"]][0]
        other = [row for row in data["sessions"] if not row["current"]][0]
        self.assertEqual(other["device"], "Chrome · macOS")

        data, _ = self.act_json("session.revoke", {"id": other["id"]})
        self.assertTrue(data["ok"], data)
        data, _ = self.state()
        self.assertEqual(len(data["sessions"]), 1)

        data, _ = self.act_json("session.revoke", {"id": current["id"]})
        self.assertFalse(data["ok"])
        self.assertIn("当前会话", data["error"])
        self.assertTrue(self.audit_rows("admin.session.revoke"))

    def test_revoke_others_keeps_current(self):
        self.admin_login()
        with hdb.db(0) as con:
            auth.create_session(con, auth.ADMIN_SUBJECT, 0,
                                ttl=auth.ADMIN_SESSION_TTL, ua="a")
            auth.create_session(con, auth.ADMIN_SUBJECT, 0,
                                ttl=auth.ADMIN_SESSION_TTL, ua="b")
        data, _ = self.act_json("session.revoke-others")
        self.assertTrue(data["ok"], data)
        self.assertEqual(data["data"]["revoked"], 2)
        data, _ = self.state()
        self.assertEqual(len(data["sessions"]), 1)
        self.assertTrue(data["sessions"][0]["current"])


# ------------------------------------------------------------------ 令牌
class TokenActionTests(ApiBase):
    def test_token_secret_is_returned_once(self):
        self.admin_login()
        data, _ = self.act_json("token.create", {
            "name": "laptop", "scopes": ["health.read", "health.write"]})
        self.assertTrue(data["ok"], data)
        secret = data["data"]["secret"]
        token_id = data["data"]["id"]
        self.assertTrue(secret)
        self.assertEqual(data["data"]["prefix"], f"key_{token_id}")
        self.assertNotIn(secret, json.dumps(self.state()[0]))

        status, _text, _headers = self.act(
            "token.create", {"name": "laptop", "scopes": ["health.read"]})
        self.assertEqual(status, 200)
        with hdb.db(0) as con:
            self.assertIsNotNone(auth.static_token_lookup(con, secret))

        data, _ = self.act_json("token.revoke", {"id": token_id})
        self.assertTrue(data["ok"], data)
        with hdb.db(0) as con:
            self.assertIsNone(auth.static_token_lookup(con, secret))
        data, _ = self.act_json("token.revoke", {"id": token_id})
        self.assertFalse(data["ok"])
        self.assertIn("已撤销", data["error"])
        rows = self.audit_rows()
        self.assertIn("admin.key.create", [row["action"] for row in rows])
        self.assertIn("admin.key.revoke", [row["action"] for row in rows])
        blob = json.dumps(rows)
        self.assertNotIn(secret, blob)


# ------------------------------------------------------------------ OAuth
class OAuthActionTests(ApiBase):
    def test_confidential_client_secret_once_and_toggle(self):
        self.admin_login()
        data, _ = self.act_json("oauth.create", {
            "name": "Obsidian MCP", "kind": "confidential",
            "redirects": ["http://127.0.0.1:22360/callback"]})
        self.assertTrue(data["ok"], data)
        client_id = data["data"]["clientId"]
        secret = data["data"]["secret"]
        self.assertTrue(secret.startswith("cs_"))
        self.assertNotIn(secret, json.dumps(self.state()[0]))
        row = [item for item in self.state()[0]["oauth"]
               if item["clientId"] == client_id][0]
        self.assertEqual(row["kind"], "confidential")
        self.assertEqual(row["status"], "active")

        data, _ = self.act_json("oauth.toggle", {"id": client_id,
                                                 "enabled": False})
        self.assertTrue(data["ok"], data)
        row = [item for item in self.state()[0]["oauth"]
               if item["clientId"] == client_id][0]
        self.assertEqual(row["status"], "revoked")

        data, _ = self.act_json("oauth.toggle", {"id": client_id,
                                                 "enabled": True})
        self.assertTrue(data["ok"], data)
        row = [item for item in self.state()[0]["oauth"]
               if item["clientId"] == client_id][0]
        self.assertEqual(row["status"], "active")

        data, _ = self.act_json("oauth.delete", {"id": client_id})
        self.assertFalse(data["ok"])
        self.assertIn("已停用", data["error"])
        self.act_json("oauth.toggle", {"id": client_id, "enabled": False})
        data, _ = self.act_json("oauth.delete", {"id": client_id})
        self.assertTrue(data["ok"], data)
        with hdb.db(0) as con:
            self.assertIsNone(oauth.get_client(con, client_id))
        actions = [row["action"] for row in self.audit_rows()]
        self.assertIn("admin.client.create", actions)
        self.assertIn("admin.client.delete", actions)
        self.assertNotIn(secret, json.dumps(self.audit_rows()))

    def test_public_client_and_bad_redirect(self):
        self.admin_login()
        data, _ = self.act_json("oauth.create", {
            "name": "Public", "kind": "public",
            "redirects": ["https://example.test/cb"]})
        self.assertTrue(data["ok"], data)
        self.assertNotIn("secret", data["data"])
        data, _ = self.act_json("oauth.create", {
            "name": "Bad", "kind": "public",
            "redirects": ["http://example.test/cb"]})
        self.assertFalse(data["ok"])
        self.assertIn("回调地址无效", data["error"])

    def test_pending_decide_reports_no_queue(self):
        self.admin_login()
        data, _ = self.act_json("pending.decide", {"id": "req_1",
                                                   "approve": True})
        self.assertFalse(data["ok"])
        self.assertIn("待确认", data["error"])


# ------------------------------------------------------------------ 健康数据 / 审计
class DataActionTests(ApiBase):
    def test_backfill_requires_creds_and_reports_queue(self):
        self.admin_login()
        data, _ = self.act_json("coverage.backfill", {})
        self.assertFalse(data["ok"])
        self.assertIn("凭据", data["error"])

        self.write_creds()
        with mock.patch("server.backfill_async", return_value=True) as patched:
            data, _ = self.act_json("coverage.backfill", {"type": "heart_rate"})
        self.assertTrue(data["ok"], data)
        self.assertTrue(data["data"]["queued"])
        patched.assert_called_once_with(30, owner=0)
        self.assertTrue(self.audit_rows("admin.backfill.start"))

        with mock.patch("server.backfill_async", return_value=False):
            data, _ = self.act_json("coverage.backfill", {})
        self.assertFalse(data["ok"])
        self.assertIn("已有回补", data["error"])

    def test_backfill_rejects_unknown_type(self):
        self.admin_login()
        self.write_creds()
        data, _ = self.act_json("coverage.backfill", {"type": "nope"})
        self.assertFalse(data["ok"])
        self.assertIn("未知的数据类型", data["error"])

    def test_audit_verify_detects_tampering(self):
        self.admin_login()
        self.act_json("user.create", {
            "username": "alice", "password": "alice-password-123",
            "scopes": ["health.read"]})
        data, _ = self.act_json("audit.verify")
        self.assertTrue(data["ok"], data)
        self.assertIs(data["data"]["chainOk"], True)
        self.assertGreaterEqual(data["data"]["checked"], 1)
        with hdb.db(0) as con:
            con.execute("UPDATE access_log SET actor='tampered' WHERE id=1")
        data, _ = self.act_json("audit.verify")
        self.assertTrue(data["ok"], data)
        self.assertIs(data["data"]["chainOk"], False)

    def test_state_audit_rows_are_rendered_metadata(self):
        self.admin_login()
        self.act_json("user.create", {
            "username": "alice", "password": "alice-password-123",
            "scopes": ["health.read"]})
        rows = self.state()[0]["audit"]["rows"]
        self.assertTrue(rows)
        row = [item for item in rows if item["action"] == "admin.user.create"][0]
        self.assertEqual(row["outcome"], "ok")
        self.assertEqual(row["actor"], "admin")
        self.assertIsInstance(row["at"], int)
        self.assertIn("alice", row["detail"])


# ------------------------------------------------------------------ 安全
class SecurityActionTests(ApiBase):
    def test_change_password_reissues_session(self):
        self.admin_login()
        old_cookie = self.browser.cookies.get("hcb_session")
        data, _ = self.act_json("security.change-password", {
            "current": "wrong-password", "password": "new-admin-password-1",
            "password2": "new-admin-password-1"})
        self.assertFalse(data["ok"])
        self.assertIn("当前密码", data["error"])

        data, _headers = self.act_json("security.change-password", {
            "current": ADMIN_PW, "password": "new-admin-password-1",
            "password2": "new-admin-password-1"})
        self.assertTrue(data["ok"], data)
        # 换密后旧 Cookie 全部失效；响应里下发的新 Cookie 由浏览器保存并生效。
        self.assertNotEqual(self.browser.cookies.get("hcb_session"), old_cookie)
        with hdb.db(0) as con:
            self.assertTrue(auth.admin_check_password(con, "new-admin-password-1"))
        # 响应里带的新 Cookie 已生效：state 仍可用。
        self.state()
        self.assertTrue(self.audit_rows("admin.password.change"))

    def test_totp_begin_confirm_disable(self):
        self.admin_login()
        data, _ = self.act_json("security.totp-begin")
        self.assertTrue(data["ok"], data)
        secret = data["data"]["secret"]
        self.assertTrue(data["data"]["uri"].startswith("otpauth://totp/"))
        self.assertFalse(self.state()[0]["security"]["totpEnabled"])
        self.assertNotIn(secret, json.dumps(self.state()[0]))

        data, _ = self.act_json("security.totp-confirm", {"code": "000000"})
        self.assertFalse(data["ok"])
        self.assertFalse(self.state()[0]["security"]["totpEnabled"])

        data, _ = self.act_json("security.totp-confirm",
                                {"code": totp.code(secret)})
        self.assertTrue(data["ok"], data)
        codes = data["data"]["recoveryCodes"]
        self.assertEqual(len(codes), 10)
        security = self.state()[0]["security"]
        self.assertTrue(security["totpEnabled"])
        self.assertIsNotNone(security["totpEnrolledAt"])

        data, _ = self.act_json("security.totp-begin")
        self.assertFalse(data["ok"])
        self.assertIn("已启用", data["error"])

        data, _ = self.act_json("security.totp-disable")
        self.assertFalse(data["ok"])
        data, _ = self.act_json(
            "security.totp-disable",
            {"code": totp.code(secret, at=time.time() + 30)})
        self.assertTrue(data["ok"], data)
        self.assertFalse(self.state()[0]["security"]["totpEnabled"])
        data, _ = self.act_json("security.totp-disable")
        self.assertFalse(data["ok"])
        with hdb.db(0) as con:
            self.assertFalse(auth.admin_totp_state(con)["enabled"])
        blob = json.dumps([row["detail"] for row in self.audit_rows()])
        self.assertNotIn(secret, blob)
        for code in codes:
            self.assertNotIn(code, blob)

    def test_totp_rebind_requires_current_code(self):
        self.admin_login()
        data, _ = self.act_json("security.totp-begin")
        self.assertTrue(data["ok"], data)
        old = data["data"]["secret"]
        data, _ = self.act_json("security.totp-confirm", {"code": totp.code(old)})
        self.assertTrue(data["ok"], data)

        data, _ = self.act_json("security.totp-rebind", {"code": "000000"})
        self.assertFalse(data["ok"])
        data, _ = self.act_json("security.totp-rebind", {"code": totp.code(old, at=time.time() + 30)})
        self.assertTrue(data["ok"], data)
        new = data["data"]["secret"]
        self.assertNotEqual(old, new)
        self.assertTrue(data["data"]["uri"].startswith("otpauth://totp/"))
        self.assertFalse(self.state()[0]["security"]["totpEnabled"])
        data, _ = self.act_json("security.totp-confirm", {"code": totp.code(new)})
        self.assertTrue(data["ok"], data)
        self.assertTrue(self.state()[0]["security"]["totpEnabled"])

        data, _ = self.act_json("security.totp-disable", {"code": totp.code(old, at=time.time() + 60)})
        self.assertFalse(data["ok"])
        data, _ = self.act_json("security.totp-disable", {"code": totp.code(new, at=time.time() + 30)})
        self.assertTrue(data["ok"], data)
        self.assertFalse(self.state()[0]["security"]["totpEnabled"])

    def test_settings_update_only_existing_settings(self):
        self.admin_login()
        data, _ = self.act_json("settings.update", {
            "sessionTtlHours": 24, "requireTotp": True})
        self.assertFalse(data["ok"])
        self.assertIn("环境变量", data["error"])
        data, _ = self.act_json("settings.update", {})
        self.assertFalse(data["ok"])
        data, _ = self.act_json("settings.update", {"fetch_interval": 60})
        self.assertFalse(data["ok"])
        data, _ = self.act_json("settings.update", {"fetch_interval": 600})
        self.assertTrue(data["ok"], data)
        self.assertEqual(data["data"]["changed"], 1)
        with hdb.db(0) as con:
            self.assertEqual(auth.settings_get(con, "fetch_interval_override"),
                             "600")
        self.assertTrue(self.audit_rows("admin.settings.update"))


if __name__ == "__main__":
    unittest.main()
