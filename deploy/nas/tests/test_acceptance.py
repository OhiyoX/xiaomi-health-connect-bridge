"""Task 9: 本地端到端验收（临时数据库 + 真实 ThreadingHTTPServer）。

一个不用 Docker、不碰 NAS 的完整验收脚本化版本：引导管理员 → 两步登录
（密码 + TOTP）→ 动态注册 → 同意 → 换码 → `/mcp` `tools/list` → 写工具
允许/拒绝 → 刷新轮换 → 旧刷新令牌重用吊销整族；静态 key 的 `/ingest`
策略/时间窗/分区；后台用户 CRUD 与隔离；401 挑战、`/ingest` 只认静态、
CSRF、16k/64k/1m/4m 请求体上限；审计链校验与保留；页面/日志/库里不出现
明文秘密。

真实 NAS 部署、nginx、手机和真实 MCP 客户端仍需人工验收，见
`deploy/nas/ACCEPTANCE.md`。
"""
from __future__ import annotations

import contextlib
import http.client
import io
import json
import os
import subprocess
import sys
import time
import unittest
import urllib.parse
from unittest import mock

TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
if TESTS_DIR not in sys.path:
    sys.path.insert(0, TESTS_DIR)

from test_admin import AdminBase, secrets_in  # noqa: E402
from test_http import (  # noqa: E402
    ADMIN_PW,
    CHALLENGE,
    STATIC_TOKEN,
    VERIFIER,
    Browser,
    hidden,
    json_body,
)

import server as server_module  # noqa: E402

from hcb import audit  # noqa: E402
from hcb import auth  # noqa: E402
from hcb import db as hdb  # noqa: E402
from hcb import policy  # noqa: E402
from hcb import totp  # noqa: E402

NAS_DIR = os.path.dirname(TESTS_DIR)
CLI = os.path.join(NAS_DIR, "hcb_admin.py")

ALICE_PW = "alice-password-123"
BOB_PW = "bob-password-12345"
OLGA_PW = "olga-password-1234"

CLOSED_WINDOW = {
    "tz": "UTC",
    "from": "2020-01-01T00:00:00+00:00",
    "until": "2020-01-02T00:00:00+00:00",
}
OPEN_WINDOW = {
    "tz": "UTC",
    "from": "2020-01-01T00:00:00+00:00",
    "until": "2035-01-01T00:00:00+00:00",
}


class LocalAcceptanceTests(AdminBase):
    """本地端到端验收；每个测试一个临时库 + 真实 HTTP 服务。"""

    # ------------------------------------------------------------ helpers
    def health_db_path(self) -> str:
        return os.environ["HEALTH_DB"]

    def register_client(self, browser=None, name="Acceptance Client") -> dict:
        browser = browser or self.browser
        body = {
            "client_name": name,
            "redirect_uris": [self.redirect],
            "token_endpoint_auth_method": "none",
        }
        status, text, _ = browser.post(
            "/register", json_body(body), {"Content-Type": "application/json"})
        self.assertEqual(status, 201, text)
        return json.loads(text)

    def authorize_allow(self, browser, client_id: str,
                        scopes=("health.read", "health.write"),
                        state: str = "e2e") -> str:
        """已登录的浏览器直接走同意页 → 返回授权码。"""
        params = {
            "response_type": "code",
            "client_id": client_id,
            "redirect_uri": self.redirect,
            "code_challenge": CHALLENGE,
            "code_challenge_method": "S256",
            "state": state,
            "resource": self.base + "/mcp",
            "scope": " ".join(scopes),
        }
        status, page, _ = browser.get(
            "/authorize?" + urllib.parse.urlencode(params))
        self.assertEqual(status, 200, page)
        request = hidden(page, "request")
        csrf = hidden(page, "_csrf")
        self.assertTrue(request and csrf, page[:500])
        status, text, headers = browser.post("/authorize", {
            "_csrf": csrf, "request": request, "decision": "allow",
            "scope": list(scopes)})
        self.assertEqual(status, 302, text)
        location = headers.get("location", "")
        self.assertTrue(location.startswith(self.redirect), location)
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(location).query)
        self.assertIn("code", query)
        self.assertEqual(query.get("state"), [state])
        return query["code"][0]

    def exchange_code(self, browser, client_id: str, code: str) -> dict:
        status, text, _ = browser.post("/token", {
            "grant_type": "authorization_code",
            "code": code,
            "client_id": client_id,
            "redirect_uri": self.redirect,
            "code_verifier": VERIFIER,
        })
        self.assertEqual(status, 200, text)
        return json.loads(text)

    def consent_and_token(self, browser=None,
                          scopes=("health.read", "health.write"),
                          client: dict | None = None):
        """已登录浏览器 → (client, tokens)。"""
        browser = browser or self.browser
        client = client or self.register_client(browser)
        code = self.authorize_allow(browser, client["client_id"], scopes)
        return client, self.exchange_code(browser, client["client_id"], code)

    def tool_names(self, token: str) -> set[str]:
        status, text, _ = self.mcp(token, method="tools/list")
        self.assertEqual(status, 200, text)
        doc = json.loads(text)
        return {tool["name"] for tool in doc["result"]["tools"]}

    def call_tool(self, token: str, name: str, arguments=None) -> dict:
        status, text, _ = self.mcp(token, method="tools/call", params={
            "name": name, "arguments": arguments or {}})
        self.assertEqual(status, 200, text)
        return json.loads(text)

    def login_user(self, browser, username: str, password: str):
        status, page, csrf = self.login_page(browser)
        self.assertEqual(status, 200, page)
        return browser.post("/login", {
            "username": username, "password": password, "_csrf": csrf})

    def admin_post(self, path: str, data: dict, csrf_path: str | None = None):
        """带 CSRF 的后台 POST；CSRF 从 csrf_path（默认同一页）取。"""
        fields = {"_csrf": self.csrf_for(csrf_path or path), **data}
        return self.browser.post(path, fields)

    def create_user_via_ui(self, username: str, password: str,
                           scopes=("health.read",), data_mode="shared",
                           display: str | None = None) -> dict:
        status, text, headers = self.admin_post("/admin/users", {
            "action": "create",
            "username": username,
            "display_name": display or username,
            "password": password,
            "scope": list(scopes),
            "data_mode": data_mode,
        })
        self.assertEqual(status, 303, text)
        self.assertIn("/admin/legacy/users?ok=", headers.get("location", ""))
        with hdb.db(0) as con:
            user = auth.by_username(con, username)
        self.assertIsNotNone(user)
        return user

    def create_key_via_ui(self, label: str, *, owner: int = 0,
                          tool_policy=None, window=None) -> str:
        """后台建静态 key → 返回只显示一次的完整令牌。"""
        form = {
            "action": "create",
            "label": label,
            "owner_user_id": str(owner),
        }
        if tool_policy is not None:
            form["tool_policy"] = json.dumps(tool_policy)
        if window is not None:
            form["window"] = json.dumps(window)
        status, page, _ = self.admin_post("/admin/keys", form)
        self.assertEqual(status, 200, page[:500])
        found = secrets_in(page)
        self.assertEqual(len(found), 1, page[:500])
        return found[0]

    def ingest(self, raw: str, items: list):
        return self.browser.post(
            "/ingest", json_body({"items": items}),
            {"Content-Type": "application/json",
             "Authorization": "Bearer " + raw})

    def next_totp_code(self, secret: str) -> str:
        """每次前进一个时间步，避免 TOTP 重放（确认码已被消费）。"""
        self._totp_cursor = getattr(self, "_totp_cursor", 0) + 1
        return totp.code(secret, at=time.time() + 30 * self._totp_cursor)

    def enable_admin_totp(self):
        """后台启用 TOTP；返回 (secret, 10 个恢复码)。"""
        self._totp_cursor = 0
        self.admin_login()
        status, text, headers = self.admin_post("/admin/security", {
            "action": "totp_prepare", "current_password": ADMIN_PW})
        self.assertEqual(status, 303, text)
        self.assertIn("ok=", headers.get("location", ""))
        status, page, _ = self.browser.get("/admin/legacy/security")
        self.assertEqual(status, 200, page)
        found = secrets_in(page)
        self.assertTrue(found, page[:500])
        secret = found[0]
        status, page, _ = self.admin_post("/admin/security", {
            "action": "totp_confirm",
            "current_password": ADMIN_PW,
            "code": totp.code(secret),
        })
        self.assertEqual(status, 200, page[:500])
        blocks = secrets_in(page)
        self.assertEqual(len(blocks), 1, page[:500])
        codes = [line.strip() for line in blocks[0].splitlines()
                 if line.strip()]
        self.assertEqual(len(codes), 10, page[:500])
        self.assertEqual(len(set(codes)), 10)
        return secret, codes

    def login_with_totp(self, secret: str, browser=None):
        """密码 → /login/otp → 后台；断言中间状态没有正式会话。"""
        browser = browser or self.browser
        status, page, csrf = self.login_page(browser)
        self.assertEqual(status, 200, page)
        status, text, headers = browser.post("/login", {
            "username": "admin", "password": ADMIN_PW, "_csrf": csrf})
        self.assertEqual(status, 303, text)
        self.assertTrue(headers.get("location", "").startswith("/login/otp"),
                        headers)
        self.assertNotIn(auth.SESSION_COOKIE, browser.cookies)
        status, page, _ = browser.get("/login/otp")
        self.assertEqual(status, 200, page)
        status, text, headers = browser.post("/login/otp", {
            "_csrf": hidden(page, "_csrf"), "code": self.next_totp_code(secret)})
        self.assertEqual(status, 303, text)
        self.assertEqual(headers.get("location"), "/admin")
        self.assertIn(auth.SESSION_COOKIE, browser.cookies)
        return browser

    def post_declared_length(self, path: str, length: int,
                             token: str | None = None) -> int:
        """只发请求头（Content-Length 超限），不真的传大包。"""
        url = urllib.parse.urlsplit(self.base)
        conn = http.client.HTTPConnection(url.hostname, url.port, timeout=10)
        try:
            conn.putrequest("POST", path)
            conn.putheader("Content-Type", "application/json")
            conn.putheader("Content-Length", str(length))
            conn.putheader("Connection", "close")
            if token:
                conn.putheader("Authorization", "Bearer " + token)
            conn.endheaders()
            resp = conn.getresponse()
            resp.read()
            return resp.status
        finally:
            conn.close()

    # ------------------------------------------------- 1. 完整 OAuth 生命周期
    def test_oauth_lifecycle_with_totp_and_write_tools(self):
        secret, _codes = self.enable_admin_totp()
        self.browser.cookies.clear()
        self.login_with_totp(secret)

        client = self.register_client()
        code = self.authorize_allow(self.browser, client["client_id"])
        tokens = self.exchange_code(self.browser, client["client_id"], code)
        access = tokens["access_token"]
        refresh = tokens["refresh_token"]
        self.assertRegex(access, r"^oa_")
        self.assertRegex(refresh, r"^or_")
        self.assertEqual(tokens["token_type"], "Bearer")

        names = self.tool_names(access)
        self.assertIn("query_heart_rate", names)
        self.assertIn("sync_now", names)          # 写工具：scope 允许
        self.assertNotIn("ingest", names)          # OAuth 永不拿到 ingest

        # 写工具放行：用 mock 顶替云端同步，验收只看授权链。
        with mock.patch.object(server_module, "sync_once",
                               return_value={"ok": True, "pulled": 0}):
            doc = self.call_tool(access, "sync_now")
        self.assertIn("result", doc, doc)
        self.assertNotIn("error", doc, doc)
        self.assertIn("pulled", doc["result"]["content"][0]["text"])

        # 读工具也真的能走完（空库返回 0 行也算通过）。
        doc = self.call_tool(access, "query_heart_rate",
                             {"limit": 5, "days": 1})
        self.assertIn("result", doc, doc)

        # 刷新严格轮换。
        status, text, _ = self.browser.post("/token", {
            "grant_type": "refresh_token",
            "refresh_token": refresh,
            "client_id": client["client_id"],
        })
        self.assertEqual(status, 200, text)
        rotated = json.loads(text)
        self.assertNotEqual(rotated["access_token"], access)
        self.assertNotEqual(rotated["refresh_token"], refresh)

        # 旧刷新令牌重用 → invalid_grant，并吊销整族。
        status, text, _ = self.browser.post("/token", {
            "grant_type": "refresh_token",
            "refresh_token": refresh,
            "client_id": client["client_id"],
        })
        self.assertEqual(status, 400, text)
        self.assertEqual(json.loads(text)["error"], "invalid_grant")
        status, text, _ = self.mcp(rotated["access_token"])
        self.assertEqual(status, 401, text)
        status, text, _ = self.mcp(access)
        self.assertEqual(status, 401, text)

        # 只读令牌：tools/list 不含写工具，调用写工具 -32003。
        _client2, read_tokens = self.consent_and_token(
            self.browser, scopes=("health.read",),
            client=self.register_client(name="Read Only Client"))
        read_names = self.tool_names(read_tokens["access_token"])
        self.assertIn("query_heart_rate", read_names)
        self.assertNotIn("sync_now", read_names)
        doc = self.call_tool(read_tokens["access_token"], "sync_now")
        self.assertEqual(doc["error"]["code"], -32003, doc)
        self.assertIn("insufficient scope", doc["error"]["message"])

        # 令牌只在库里留 SHA-256；撤销端点始终 200。
        with hdb.db(0) as con:
            rows = [dict(row) for row in con.execute(
                "SELECT token_hash, prefix, kind FROM oauth_tokens")]
        hashes = {row["token_hash"] for row in rows}
        self.assertTrue(all(len(value) == 64 for value in hashes), hashes)
        self.assertNotIn(access, hashes)
        self.assertNotIn(rotated["access_token"], hashes)
        status, _text, _ = self.browser.post("/revoke", {
            "token": read_tokens["access_token"],
            "client_id": _client2["client_id"]})
        self.assertEqual(status, 200)
        status, _text, _ = self.mcp(read_tokens["access_token"])
        self.assertEqual(status, 401)

    # --------------------------------------- 2. 静态 key /ingest 策略与分区
    def test_static_ingest_policy_window_and_partition(self):
        self.admin_login()

        denied = self.create_key_via_ui("e2e-denied", tool_policy=[])
        ts = 1_700_100_001
        status, text, _ = self.ingest(denied, [
            {"type": "steps", "ts": ts, "steps": 1}])
        self.assertEqual(status, 403, text)
        self.assertEqual(json.loads(text)["reason"], "tool_denied")

        closed = self.create_key_via_ui("e2e-closed", window=CLOSED_WINDOW)
        status, text, _ = self.ingest(closed, [
            {"type": "steps", "ts": ts + 1, "steps": 1}])
        self.assertEqual(status, 403, text)
        self.assertEqual(json.loads(text)["reason"], "window_closed")

        opened = self.create_key_via_ui("e2e-open", window=OPEN_WINDOW)
        status, text, _ = self.ingest(opened, [
            {"type": "steps", "ts": ts + 2, "steps": 2}])
        self.assertEqual(status, 200, text)
        with hdb.db(0) as con:
            row = con.execute("SELECT steps FROM steps WHERE ts=?",
                              (ts + 2,)).fetchone()
        self.assertIsNotNone(row)
        self.assertEqual(row["steps"], 2)
        for ts_denied in (ts, ts + 1):
            with hdb.db(0) as con:
                self.assertIsNone(con.execute(
                    "SELECT 1 FROM steps WHERE ts=?", (ts_denied,)).fetchone())

        # own 用户的分区：key 的 owner 决定落哪个库，绝不串到 admin 分区。
        olga = self.create_user_via_ui(
            "olga", OLGA_PW, scopes=("health.read",), data_mode="own")
        olga_key = self.create_key_via_ui("olga-phone", owner=olga["id"],
                                          window=OPEN_WINDOW)
        ts_own = ts + 3
        status, text, _ = self.ingest(olga_key, [
            {"type": "heart_rate", "ts": ts_own, "bpm": 61}])
        self.assertEqual(status, 200, text)
        with hdb.db(olga["id"]) as con:
            own = con.execute("SELECT bpm FROM heart_rate WHERE ts=?",
                              (ts_own,)).fetchone()
        self.assertIsNotNone(own)
        self.assertEqual(own["bpm"], 61)
        with hdb.db(0) as con:
            self.assertIsNone(con.execute(
                "SELECT 1 FROM heart_rate WHERE ts=?", (ts_own,)).fetchone())

    # ------------------------------------------------- 3. 后台用户 CRUD/隔离
    def test_admin_user_crud_and_isolation(self):
        self.admin_login()
        alice = self.create_user_via_ui("alice", ALICE_PW)
        bob = self.create_user_via_ui("bob", BOB_PW)
        self.assertNotEqual(alice["id"], bob["id"])

        # 普通用户只能进 /account；看不到别人的账号页。
        alice_browser = Browser(self.base)
        status, text, headers = self.login_user(
            alice_browser, "alice", ALICE_PW)
        self.assertEqual(status, 303, text)
        self.assertEqual(headers.get("location"), "/account")
        status, page, _ = alice_browser.get("/account")
        self.assertEqual(status, 200, page)
        self.assertIn("alice", page)
        self.assertNotIn("bob", page)
        status, _text, headers = alice_browser.get("/admin")
        self.assertEqual(status, 302)
        self.assertEqual(headers.get("location"), "/account")

        # alice 只有 health.read：写工具被 scope 挡住。
        _client, tokens = self.consent_and_token(
            alice_browser, scopes=("health.read",))
        names = self.tool_names(tokens["access_token"])
        self.assertNotIn("sync_now", names)
        doc = self.call_tool(tokens["access_token"], "sync_now")
        self.assertEqual(doc["error"]["code"], -32003, doc)

        # 管理员加 health.write → revision 变化，旧会话与旧令牌立即失效。
        status, text, headers = self.admin_post(f"/admin/users/{alice['id']}", {
            "action": "update",
            "scope_present": "1",
            "enabled_present": "1",
            "display_name": "alice",
            "data_mode": "shared",
            "scope": ["health.read", "health.write"],
        })
        self.assertEqual(status, 303, text)
        self.assertIn("ok=", headers.get("location", ""))
        status, _text, headers = alice_browser.get("/account")
        self.assertEqual(status, 302)
        self.assertTrue(headers.get("location", "").startswith("/login?next="),
                        headers)
        status, text, _ = self.mcp(tokens["access_token"])
        self.assertEqual(status, 401, text)

        # 停用：登录失败，令牌继续 401。
        status, text, _ = self.admin_post(
            f"/admin/users/{alice['id']}/disable", {},
            csrf_path=f"/admin/users/{alice['id']}")
        self.assertEqual(status, 303, text)
        fresh = Browser(self.base)
        status, text, _ = self.login_user(fresh, "alice", ALICE_PW)
        self.assertEqual(status, 401, text)
        status, text, _ = self.mcp(tokens["access_token"])
        self.assertEqual(status, 401, text)

        # 删除：需要用户名确认；用户行消失，bob 不受影响。
        status, text, _ = self.admin_post(
            f"/admin/users/{alice['id']}/delete", {"confirm": "alice"},
            csrf_path=f"/admin/users/{alice['id']}")
        self.assertEqual(status, 303, text)
        with hdb.db(0) as con:
            self.assertIsNone(auth.by_username(con, "alice"))
            self.assertIsNotNone(auth.by_username(con, "bob"))
        bob_browser = Browser(self.base)
        status, text, headers = self.login_user(bob_browser, "bob", BOB_PW)
        self.assertEqual(status, 303, text)
        self.assertEqual(headers.get("location"), "/account")

    # ------------------------------- 4. 401/CSRF/只认静态/请求体上限
    def test_unauthorized_surfaces_and_body_limits(self):
        self.admin_login()
        # /mcp 无令牌 → 401 挑战；坏令牌 → 401；GET → 405。
        status, text, headers = self.browser.post(
            "/mcp", json_body({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}),
            {"Content-Type": "application/json"})
        self.assertEqual(status, 401, text)
        self.assertEqual(
            headers.get("www-authenticate"),
            'Bearer resource_metadata="%s/.well-known/oauth-protected-resource/mcp"'
            % self.base)
        status, text, _ = self.mcp("not-a-real-token")
        self.assertEqual(status, 401, text)
        status, text, _ = self.browser.get("/mcp")
        self.assertEqual(status, 405, text)

        # /ingest 只认静态 Bearer。
        status, text, _ = self.browser.post(
            "/ingest", json_body({"items": []}),
            {"Content-Type": "application/json"})
        self.assertEqual(status, 401, text)
        _client, oauth_tokens = self.consent_and_token(self.browser)
        status, text, _ = self.browser.post(
            "/ingest", json_body({"items": []}),
            {"Content-Type": "application/json",
             "Authorization": "Bearer " + oauth_tokens["access_token"]})
        self.assertEqual(status, 403, text)
        status, text, _ = self.ingest(STATIC_TOKEN, [])
        self.assertEqual(status, 200, text)

        # CSRF：同意页与后台 POST 都必须带签名令牌。
        client = self.register_client()
        params = {
            "response_type": "code", "client_id": client["client_id"],
            "redirect_uri": self.redirect, "code_challenge": CHALLENGE,
            "code_challenge_method": "S256", "state": "csrf",
            "resource": self.base + "/mcp", "scope": "health.read",
        }
        status, page, _ = self.browser.get(
            "/authorize?" + urllib.parse.urlencode(params))
        self.assertEqual(status, 200, page)
        request = hidden(page, "request")
        status, text, _ = self.browser.post("/authorize", {
            "request": request, "decision": "allow", "scope": "health.read"})
        self.assertEqual(status, 403, text)
        status, text, _ = self.admin_post("/admin/settings", {
            "action": "set_interval", "fetch_interval": "900",
        }, csrf_path="/admin/settings")
        self.assertEqual(status, 303, text)
        status, text, _ = self.browser.post("/admin/settings", {
            "action": "set_interval", "fetch_interval": "900"})
        self.assertEqual(status, 403, text)
        status, text, _ = self.browser.post("/login", {
            "username": "admin", "password": ADMIN_PW, "_csrf": "bogus"})
        self.assertEqual(status, 403, text)

        # 请求体上限：OAuth 16 KB、后台表单 64 KB、/mcp 1 MB、/ingest 4 MB。
        status, text, _ = self.browser.post(
            "/register", b"{" + b" " * (17 * 1024) + b"}",
            {"Content-Type": "application/json"})
        self.assertEqual(status, 413, text)
        csrf = self.csrf_for("/admin/settings")
        status, text, _ = self.browser.post("/admin/settings", {
            "_csrf": csrf, "action": "set_interval",
            "fetch_interval": "900", "pad": "x" * (20 * 1024)})
        self.assertEqual(status, 303, text)
        status, text, _ = self.browser.post("/admin/settings", {
            "_csrf": csrf, "action": "set_interval",
            "fetch_interval": "900", "pad": "x" * (70 * 1024)})
        self.assertEqual(status, 413, text)
        self.assertEqual(
            self.post_declared_length("/mcp", 1024 * 1024 + 1, STATIC_TOKEN),
            413)
        self.assertEqual(
            self.post_declared_length("/ingest", 4 * 1024 * 1024 + 1,
                                      STATIC_TOKEN),
            413)

    # --------------------------------------------- 5. 审计链校验与保留
    def test_audit_verify_and_retention(self):
        self.admin_login()
        client, tokens = self.consent_and_token(self.browser)
        self.assertIn("health_status",
                      self.tool_names(tokens["access_token"]))
        self.call_tool(tokens["access_token"], "health_status")

        with hdb.db(0) as con:
            result = audit.verify(con)
        self.assertTrue(result["ok"], result)
        self.assertGreaterEqual(result["rows"], 3, result)
        rows_before = result["rows"]

        cli = subprocess.run(
            [sys.executable, CLI, "audit-verify", "--db", self.health_db_path()],
            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            timeout=60)
        self.assertEqual(cli.returncode, 0, cli.stdout)
        payload = json.loads(cli.stdout)
        self.assertTrue(payload["ok"], payload)
        for value in (tokens["access_token"], tokens["refresh_token"],
                      ADMIN_PW, STATIC_TOKEN):
            self.assertNotIn(value, cli.stdout)

        # 保留：把“现在”推到 400 天后，默认 90 天策略只留最后一行 + 锚点。
        with hdb.db(0) as con:
            pruned = audit.cleanup(con, now=int(time.time()) + 400 * 86400)
            self.assertTrue(pruned["verified"], pruned)
            self.assertGreaterEqual(pruned["deleted"], 1, pruned)
            after = audit.verify(con)
            self.assertTrue(after["ok"], after)
            self.assertLess(after["rows"], rows_before)
            # 清理后仍可继续接链。
            audit.append(con, "system", "acceptance", "test.after_prune",
                         {"n": 1}, "ok")
            self.assertTrue(audit.verify(con)["ok"])

        cli = subprocess.run(
            [sys.executable, CLI, "audit-verify", "--db", self.health_db_path()],
            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            timeout=60)
        self.assertEqual(cli.returncode, 0, cli.stdout)

        # 篡改一行 → 校验必须失败并指出第一条坏行。
        with hdb.db(0) as con:
            last_id = con.execute(
                "SELECT id FROM access_log WHERE hash IS NOT NULL "
                "ORDER BY id DESC LIMIT 1").fetchone()[0]
            con.execute("UPDATE access_log SET result='tampered' WHERE id=?",
                        (last_id,))
        with hdb.db(0) as con:
            broken = audit.verify(con)
        self.assertFalse(broken["ok"], broken)
        self.assertEqual(broken["first_bad_id"], last_id, broken)

    # ------------------------------------- 6. 页面/日志/库里不出现明文秘密
    def test_no_secrets_in_pages_logs_or_hashes(self):
        secret, codes = self.enable_admin_totp()
        self.browser.cookies.clear()

        captured = io.StringIO()
        with contextlib.redirect_stdout(captured), \
                contextlib.redirect_stderr(captured):
            self.login_with_totp(secret)
            client, tokens = self.consent_and_token(self.browser)
            self.tool_names(tokens["access_token"])
            static_key = self.create_key_via_ui("e2e-secret-scan")
            self.ingest(static_key, [{"type": "steps",
                                      "ts": 1_700_200_001, "steps": 3}])

        session_cookie = self.browser.cookies.get(auth.SESSION_COOKIE)
        self.assertTrue(session_cookie)
        secrets = [
            tokens["access_token"], tokens["refresh_token"], static_key,
            secret, ADMIN_PW, codes[0], session_cookie,
        ]

        logs = captured.getvalue()
        self.assertTrue(logs.strip(), "expected the server to log requests")
        for value in secrets:
            self.assertNotIn(value, logs, "request log leaks a secret")

        pages = [
            "/health",
            "/.well-known/oauth-protected-resource",
            "/.well-known/oauth-protected-resource/mcp",
            "/.well-known/oauth-authorization-server",
            "/login",
            "/account",
            "/admin",
            "/admin/legacy/users",
            "/admin/legacy/keys",
            "/admin/legacy/clients",
            "/admin/legacy/audit",
            "/admin/legacy/sessions",
            "/admin/legacy/security",
            "/admin/legacy/settings",
        ]
        for path in pages:
            status, page, headers = self.browser.get(path)
            self.assertIn(status, (200,), (path, status))
            if path.startswith(("/admin", "/account", "/login")):
                self.assertEqual(headers.get("cache-control"), "no-store", path)
            for value in secrets:
                self.assertNotIn(value, page, f"{path} leaks a secret")
        for path in ("/admin/users", "/admin/keys", "/admin/clients",
                     "/admin/audit", "/admin/sessions", "/admin/security",
                     "/admin/settings"):
            status, _text, headers = self.browser.get(path)
            self.assertEqual(status, 302, path)
            self.assertTrue(headers.get("location", "").startswith("/admin#"),
                            (path, headers))

        with hdb.db(0) as con:
            audit_rows = [dict(row) for row in con.execute(
                "SELECT actor, action, tool, result, detail FROM access_log")]
            db_rows = [dict(row) for row in con.execute(
                "SELECT token_hash, prefix, kind FROM oauth_tokens")]
            sessions = [dict(row) for row in con.execute(
                "SELECT sid_hash FROM sessions")]
            password_row = con.execute(
                "SELECT value FROM sync_state WHERE key='admin_password_hash'"
            ).fetchone()
        audit_blob = json.dumps(audit_rows, ensure_ascii=False)
        for value in secrets:
            self.assertNotIn(value, audit_blob, "audit payload leaks a secret")
        for row in db_rows:
            self.assertRegex(row["token_hash"], r"^[0-9a-f]{64}$")
            self.assertNotIn(row["token_hash"], secrets)
        for row in sessions:
            self.assertRegex(row["sid_hash"], r"^[0-9a-f]{64}$")
            self.assertNotIn(row["sid_hash"], [session_cookie])
        stored = password_row[0] if password_row is not None else ""
        self.assertTrue(stored.startswith("scrypt$"), stored[:16])
        self.assertNotIn(ADMIN_PW, stored)


class PolicyCatalogueTests(unittest.TestCase):
    """验收前提：每个工具都被分类，写工具集合非空（tools/list 才能过滤）。"""

    def test_every_tool_has_a_kind(self):
        names = [tool["name"] for tool in server_module.tool_list()]
        for name in names:
            self.assertIn(policy.tool_kind(name), ("read", "write", "ingest"),
                          name)
        self.assertTrue({"sync_now", "backfill", "sleep_audit"} <= policy.write_tools())
        self.assertIn("ingest", policy.ingest_tools())
        self.assertNotIn("ingest", policy.read_tools())


if __name__ == "__main__":
    unittest.main()
