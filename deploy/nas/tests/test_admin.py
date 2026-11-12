"""Task 6: Web 管理后台的测试。

覆盖管理员门禁、用户 CRUD、最后管理员保护、静态 key/客户端的一次性显示与
撤销、会话吊销、账号自助改密保持登录、TOTP 确认后才生成恢复码、CSRF/同源、
64 KiB 表单上限与输出转义。
"""
from __future__ import annotations

import html
import json
import os
import re
import sys
import threading
import time
import types
import unittest
from unittest import mock

TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
if TESTS_DIR not in sys.path:
    sys.path.insert(0, TESTS_DIR)

from test_http import (  # noqa: E402
    ADMIN_PW,
    Browser,
    HTTPTestCase,
    hidden,
)

import server as server_module  # noqa: E402

from hcb import admin  # noqa: E402
from hcb import auth  # noqa: E402
from hcb import db as hdb  # noqa: E402
from hcb import oauth  # noqa: E402
from hcb import totp  # noqa: E402

ADMIN_SPA = "/admin"

#: 旧 GET 路由 → 共享 SPA 的 hash 路由（Track N5 回退）。
ADMIN_REDIRECTS = {
    "/admin/users": "/admin#/users",
    "/admin/keys": "/admin#/tokens",
    "/admin/tokens": "/admin#/tokens",
    "/admin/static-keys": "/admin#/tokens",
    "/admin/clients": "/admin#/oauth",
    "/admin/oauth": "/admin#/oauth",
    "/admin/oauth-clients": "/admin#/oauth",
    "/admin/sessions": "/admin#/sessions",
    "/admin/audit": "/admin#/audit",
    "/admin/security": "/admin#/security",
    "/admin/totp": "/admin#/security",
    "/admin/settings": "/admin#/security",
}

#: 旧 SSR 页面保留一个版本：/admin/legacy/<old-path>。
ADMIN_LEGACY_PAGES = (
    "/admin/legacy",
    "/admin/legacy/users",
    "/admin/legacy/keys",
    "/admin/legacy/clients",
    "/admin/legacy/sessions",
    "/admin/legacy/audit",
    "/admin/legacy/security",
    "/admin/legacy/settings",
)

ADMIN_PAGES = (ADMIN_SPA,) + tuple(ADMIN_REDIRECTS) + ADMIN_LEGACY_PAGES
ADMIN_SSR_PAGES = ADMIN_LEGACY_PAGES

def secrets_token() -> str:
    # 仅用于测试 multipart 边界，不参与安全判断。
    return str(int(time.time() * 1000000))


SECRET_RE = re.compile(
    r'<pre class="secret"><code>(.*?)</code></pre>', re.S)


def secrets_in(page: str) -> list[str]:
    return [html.unescape(value) for value in SECRET_RE.findall(page)]


class AdminBase(HTTPTestCase):
    def setUp(self):
        super().setUp()
        self._saved_creds = os.environ.get("HEALTH_CREDS")
        os.environ["HEALTH_CREDS"] = os.path.join(self.tmp, "creds.json")

    def tearDown(self):
        if self._saved_creds is None:
            os.environ.pop("HEALTH_CREDS", None)
        else:
            os.environ["HEALTH_CREDS"] = self._saved_creds
        super().tearDown()

    def write_creds(self, owner=0, user_id="123456",
                    pass_token="dummy-pass-token"):
        admin._write_creds(owner, {"userId": user_id,
                                   "passToken": pass_token})
        return admin._creds_path(owner)

    def post_multipart(self, path, fields: dict, *, files: dict | None = None,
                       browser=None):
        browser = browser or self.browser
        boundary = "----hcb-test-" + secrets_token()
        chunks = []
        for name, value in fields.items():
            chunks.append(
                f"--{boundary}\r\nContent-Disposition: form-data; "
                f'name="{name}"\r\n\r\n{value}\r\n')
        for name, (filename, content) in (files or {}).items():
            chunks.append(
                f"--{boundary}\r\nContent-Disposition: form-data; "
                f'name="{name}"; filename="{filename}"\r\n'
                "Content-Type: application/json\r\n\r\n"
                f"{content}\r\n")
        body = ("".join(chunks) + f"--{boundary}--\r\n").encode("utf-8")
        return browser.post(path, body, {
            "Content-Type": f"multipart/form-data; boundary={boundary}"})

    def admin_login(self, browser=None):
        browser = browser or self.browser
        status, page, csrf = self.login_page(browser)
        self.assertEqual(status, 200, page)
        status, text, headers = browser.post("/login", {
            "username": "admin", "password": ADMIN_PW, "_csrf": csrf})
        self.assertEqual(status, 303, text)
        self.assertEqual(headers.get("location"), "/admin")
        return browser

    def csrf_for(self, path: str, browser=None) -> str:
        """旧 SSR 页面的表单 token；页面已 302 到 SPA 时改从 state API 取。"""
        browser = browser or self.browser
        status, page, _ = browser.get(path)
        token = hidden(page, "_csrf") if status == 200 else None
        if token:
            return token
        status, text, _ = browser.get("/admin/api/state")
        self.assertEqual(status, 200, text)
        token = json.loads(text).get("csrf")
        self.assertTrue(token, text)
        return token

    def make_user(self, username="alice", password="alice-password-123",
                  **kwargs):
        with hdb.db(0) as con:
            return auth.create_user(con, username, password, **kwargs)

    def user_login(self, username, password, browser=None):
        browser = browser or Browser(self.base)
        status, page, csrf = self.login_page(browser)
        self.assertEqual(status, 200, page)
        status, text, headers = browser.post("/login", {
            "username": username, "password": password, "_csrf": csrf})
        return browser, status, text, headers

    def post_admin(self, path: str, data: dict, *, csrf_path: str | None = None,
                   browser=None):
        browser = browser or self.browser
        fields = dict(data)
        if "_csrf" not in fields:
            fields["_csrf"] = self.csrf_for(csrf_path or path, browser)
        return browser.post(path, fields)

    def wait_for(self, predicate, timeout: float = 3.0) -> bool:
        deadline = time.time() + timeout
        while time.time() < deadline:
            if predicate():
                return True
            time.sleep(0.02)
        return False


class AdminAccessTests(AdminBase):
    def test_admin_gate_every_route(self):
        for path in ADMIN_PAGES:
            status, text, headers = self.browser.get(path)
            self.assertEqual(status, 302, path)
            self.assertTrue(headers["location"].startswith("/login?next="),
                            headers)

    def test_normal_user_redirected_to_account(self):
        self.make_user()
        browser, status, text, _ = self.user_login(
            "alice", "alice-password-123")
        self.assertEqual(status, 303, text)
        for path in ADMIN_PAGES:
            status, _text, headers = browser.get(path)
            self.assertEqual(status, 302, path)
            self.assertEqual(headers["location"], "/account", path)
        status, page, _ = browser.get("/account")
        self.assertEqual(status, 200, page)
        self.assertIn("alice", page)

    def test_normal_user_cannot_post_admin_action(self):
        bob = self.make_user("bob", "bob-password-1234")
        browser, status, _, _ = self.user_login("bob", "bob-password-1234")
        self.assertEqual(status, 303)
        status, _text, headers = browser.post("/admin/users", {
            "action": "create", "username": "evil",
            "password": "evil-password-123", "role": "user"})
        self.assertEqual(status, 302)
        self.assertEqual(headers["location"], "/account")
        with hdb.db(0) as con:
            self.assertIsNone(auth.by_username(con, "evil"))
        self.assertEqual(bob["username"], "bob")

    def test_dashboard_partition_selector(self):
        user = self.make_user(data_mode="own")
        with hdb.db(user["id"]) as con:
            con.execute("INSERT INTO steps(ts, steps) VALUES(?,?)", (1000, 7))
        self.admin_login()
        status, page, _ = self.browser.get(f"/admin/legacy?owner={user['id']}")
        self.assertEqual(status, 200, page)
        self.assertIn("alice", page)
        self.assertIn("steps", page)
        self.assertIn("查看分区", page)

    def test_spa_shell_served_with_security_headers(self):
        self.admin_login()
        status, page, headers = self.browser.get(ADMIN_SPA)
        self.assertEqual(status, 200, page)
        self.assertEqual(headers.get("cache-control"), "no-store")
        self.assertEqual(headers.get("x-frame-options"), "DENY")
        csp = headers.get("content-security-policy", "")
        self.assertIn("default-src 'none'", csp)
        self.assertIn("script-src 'self'", csp)
        self.assertIn("connect-src 'self'", csp)
        self.assertIn("frame-ancestors 'none'", csp)
        self.assertNotIn("unsafe-inline' 'self'", csp)
        self.assertIn("/admin/assets/admin.css", page)
        self.assertIn("/admin/assets/app.js", page)
        self.assertIn("/admin/assets/adapter.js", page)
        self.assertNotIn("Traceback", page)
        self.assertNotIn("password_hash", page)
        # 所有 <script> 都必须带 src：页面里没有内联脚本/内联事件。
        self.assertIsNone(re.search(r"<script(?![^>]*\bsrc=)", page, re.I), page)
        self.assertNotIn("onclick=", page.lower())
        self.assertNotIn("onload=", page.lower())

    def test_old_get_routes_redirect_to_spa(self):
        self.admin_login()
        for path, target in ADMIN_REDIRECTS.items():
            status, _text, headers = self.browser.get(path)
            self.assertEqual(status, 302, path)
            self.assertEqual(headers["location"], target, path)
        status, _text, headers = self.browser.get("/admin/users/7")
        self.assertEqual(status, 302)
        self.assertEqual(headers["location"], "/admin#/users/7")
        status, _text, headers = self.browser.get("/admin/clients/hc_demo")
        self.assertEqual(status, 302)
        self.assertEqual(headers["location"], "/admin#/oauth/hc_demo")

    def test_legacy_pages_render_with_security_headers(self):
        self.admin_login()
        for path in ADMIN_SSR_PAGES:
            status, page, headers = self.browser.get(path)
            self.assertEqual(status, 200, path)
            self.assertEqual(headers.get("cache-control"), "no-store", path)
            self.assertEqual(headers.get("x-frame-options"), "DENY", path)
            self.assertIn("content-security-policy", headers, path)
            self.assertNotIn("Traceback", page, path)
            self.assertNotIn("password_hash", page, path)
            self.assertIn("退出登录", page, path)
            # legacy 页面的链接与表单仍留在 legacy 命名空间。
            self.assertIn('href="/admin/legacy/users"', page, path)
            self.assertNotIn('href="/admin/users"', page, path)

    def test_legacy_pages_have_no_inline_script_or_handlers(self):
        self.admin_login()
        for path in ADMIN_SSR_PAGES:
            _status, page, _headers = self.browser.get(path)
            lowered = page.lower()
            self.assertNotIn("<script", lowered, path)
            self.assertNotIn("onclick=", lowered, path)
            self.assertNotIn("onload=", lowered, path)

    def test_account_error_page_uses_account_nav(self):
        self.make_user()
        browser, _, _, _ = self.user_login("alice", "alice-password-123")
        status, page, _ = browser.get("/account/nope")
        self.assertEqual(status, 404, page)
        self.assertIn('href="/account"', page)
        self.assertNotIn('href="/admin"', page)

    def test_shared_and_normal_user_account_pages(self):
        self.make_user()
        browser, _, _, _ = self.user_login("alice", "alice-password-123")
        status, page, _ = browser.get("/account/password")
        self.assertEqual(status, 200, page)
        self.assertIn("修改密码", page)


class UserManagementTests(AdminBase):
    def test_create_user_then_login_and_only_own_account(self):
        self.admin_login()
        status, text, headers = self.post_admin("/admin/users", {
            "action": "create",
            "username": "carol",
            "display_name": "Carol",
            "role": "user",
            "password": "carol-password-123",
            "scope": ["health.read"],
            "data_mode": "shared",
            "enabled": "1",
        })
        self.assertEqual(status, 303, text)
        self.assertTrue(headers["location"].startswith("/admin/legacy/users?ok="),
                        headers)
        status, page, _ = self.browser.get(headers["location"])
        self.assertEqual(status, 200, page)
        self.assertIn("用户已创建", page)
        self.assertIn("carol", page)
        with hdb.db(0) as con:
            user = auth.by_username(con, "carol")
        self.assertIsNotNone(user)
        self.assertEqual(user["scopes"], ["health.read"])

        carol = Browser(self.base)
        _carol, status, text, headers = self.user_login(
            "carol", "carol-password-123", browser=carol)
        self.assertEqual(status, 303, text)
        self.assertEqual(headers["location"], "/account")
        status, page, _ = carol.get("/account")
        self.assertEqual(status, 200, page)
        self.assertIn("carol", page)
        self.assertIn("health.read", page)
        status, _, headers = carol.get("/admin/users")
        self.assertEqual(status, 302)
        self.assertEqual(headers["location"], "/account")

    def test_flash_token_one_time_and_not_forgeable(self):
        self.admin_login()
        status, page, _ = self.browser.get("/admin/legacy/users?ok=user_created")
        self.assertEqual(status, 200)
        self.assertNotIn("用户已创建", page)
        status, page, _ = self.browser.get(
            "/admin/legacy/users?err=err_policy")
        self.assertNotIn("工具权限 JSON 无效", page)
        csrf = self.csrf_for("/admin/users")
        status, _text, headers = self.browser.post("/admin/users", {
            "_csrf": csrf, "action": "create", "username": "frank",
            "role": "user", "password": "frank-password-123",
            "scope": ["health.read"], "enabled": "1"})
        self.assertEqual(status, 303)
        location = headers["location"]
        status, page, _ = self.browser.get(location)
        self.assertIn("用户已创建", page)
        status, page, _ = self.browser.get(location)
        self.assertNotIn("用户已创建", page)

    def test_create_user_validation_and_empty_scope(self):
        self.admin_login()
        for fields in (
            {"password": "short", "scope": ["health.read"]},
            {"password": "long-enough-password", "scope": ["health.read"],
             "role": "admin"},
            {"password": "dave-password-123", "scope": []},
        ):
            data = {"action": "create", "username": "dave", "role": "user",
                    "data_mode": "shared", "enabled": "1"}
            data.update(fields)
            status, _text, headers = self.post_admin("/admin/users", data)
            self.assertEqual(status, 303)
            self.assertIn("err=", headers["location"], headers)
            status, page, _ = self.browser.get(headers["location"])
            self.assertEqual(status, 200, page)
            self.assertIn('class="err"', page)
        with hdb.db(0) as con:
            self.assertIsNone(auth.by_username(con, "dave"))

    def test_edit_user_policy_window_and_validation(self):
        user = self.make_user()
        self.admin_login()
        csrf = self.csrf_for(f"/admin/users/{user['id']}")
        # 切到独立前必须先有凭据。
        status, text, headers = self.browser.post(f"/admin/users/{user['id']}", {
            "_csrf": csrf, "action": "update", "display_name": "Alice A",
            "scope": ["health.read"], "data_mode": "own", "enabled": "1",
            "tool_policy": "", "window": "",
        })
        self.assertEqual(status, 303, text)
        self.assertIn("err=", headers["location"])
        with hdb.db(0) as con:
            self.assertEqual(auth.get_user(con, user["id"])["data_mode"],
                             "shared")
        admin._write_creds(user["id"], {
            "userId": "123456", "passToken": "dummy-token"})
        status, text, headers = self.browser.post(f"/admin/users/{user['id']}", {
            "_csrf": csrf,
            "action": "update",
            "display_name": "Alice A",
            "scope": ["health.read", "health.write"],
            "data_mode": "own",
            "enabled": "1",
            "tool_policy": '["query_heart_rate"]',
            "window": '{"tz":"Asia/Shanghai","start":"08:00","end":"22:00"}',
        })
        self.assertEqual(status, 303, text)
        self.assertIn("ok=", headers["location"])
        with hdb.db(0) as con:
            stored = auth.get_user(con, user["id"])
        self.assertEqual(stored["display_name"], "Alice A")
        self.assertEqual(stored["data_mode"], "own")
        self.assertEqual(stored["scopes"], ["health.read", "health.write"])
        self.assertIn("query_heart_rate", stored["tool_policy_json"])
        self.assertIn("Asia/Shanghai", stored["window_json"])

        status, _text, headers = self.browser.post(f"/admin/users/{user['id']}", {
            "_csrf": csrf, "action": "update", "display_name": "Alice A",
            "scope": ["health.read"], "data_mode": "own", "enabled": "1",
            "tool_policy": "{not json", "window": "",
        })
        self.assertEqual(status, 303)
        self.assertIn("err=", headers["location"])
        status, _text, headers = self.browser.post(f"/admin/users/{user['id']}", {
            "_csrf": csrf, "action": "update", "display_name": "Alice A",
            "scope": ["health.read"], "data_mode": "own", "enabled": "1",
            "tool_policy": "", "window": '{"tz":"No/Such"}',
        })
        self.assertEqual(status, 303)
        self.assertIn("err=", headers["location"])

    def test_partial_update_preserves_unsubmitted_fields(self):
        user = self.make_user(
            scopes=["health.read"], data_mode="own",
            tool_policy_json='["query_heart_rate"]',
            window_json='{"tz":"Asia/Shanghai"}')
        self.admin_login()
        csrf = self.csrf_for(f"/admin/users/{user['id']}")
        status, _text, headers = self.browser.post(
            f"/admin/users/{user['id']}", {
                "_csrf": csrf, "action": "update", "display_name": "New Name"})
        self.assertEqual(status, 303, headers)
        self.assertIn("ok=", headers["location"])
        with hdb.db(0) as con:
            stored = auth.get_user(con, user["id"])
        self.assertEqual(stored["display_name"], "New Name")
        self.assertEqual(stored["scopes"], ["health.read"])
        self.assertTrue(stored["enabled"])
        self.assertEqual(stored["data_mode"], "own")
        self.assertIn("query_heart_rate", stored["tool_policy_json"])
        self.assertIn("Asia/Shanghai", stored["window_json"])

    def test_last_admin_guard_and_admin_cannot_be_targeted(self):
        user = self.make_user()
        with hdb.db(0) as con:
            self.assertFalse(admin._guard_admin_change(
                con, {"is_admin": True, "user_id": 0}, 0))
            self.assertFalse(admin._guard_admin_change(
                con, {"is_admin": True, "user_id": user["id"]}, user["id"]))
            self.assertTrue(admin._guard_admin_change(
                con, {"is_admin": True, "user_id": 0}, user["id"]))
        self.admin_login()
        csrf = self.csrf_for("/admin/users")
        status, _text, headers = self.browser.post("/admin/users/0", {
            "_csrf": csrf, "action": "delete", "confirm": "admin"})
        self.assertEqual(status, 303)
        self.assertIn("err=", headers["location"])
        status, page, _ = self.browser.get("/admin/legacy")
        self.assertEqual(status, 200, page)
        with hdb.db(0) as con:
            self.assertIsNotNone(auth.get_user(con, user["id"]))

    def test_delete_requires_confirm_and_revokes_user_credentials(self):
        user = self.make_user()
        with hdb.db(0) as con:
            key = auth.static_token_create(
                con, "alice-phone", owner_user_id=user["id"])
            _sid, cookie = auth.create_session(
                con, auth.subject_for_user(user["id"]), user["revision"])
            secret = auth.session_secret(con)
            self.assertIsNotNone(auth.read_session(con, cookie, secret))
        self.admin_login()
        csrf = self.csrf_for(f"/admin/users/{user['id']}")
        status, _text, headers = self.browser.post(
            f"/admin/users/{user['id']}", {
                "_csrf": csrf, "action": "delete", "confirm": "wrong"})
        self.assertEqual(status, 303)
        self.assertIn("err=", headers["location"])
        with hdb.db(0) as con:
            self.assertIsNotNone(auth.get_user(con, user["id"]))
        status, _text, headers = self.browser.post(
            f"/admin/users/{user['id']}", {
                "_csrf": csrf, "action": "delete", "confirm": user["username"]})
        self.assertEqual(status, 303)
        self.assertIn("ok=", headers["location"])
        with hdb.db(0) as con:
            self.assertIsNone(auth.get_user(con, user["id"]))
            self.assertIsNone(auth.static_token_lookup(con, key["token"]))
            self.assertIsNone(auth.read_session(con, cookie, secret))

    def test_delete_own_user_archives_partition(self):
        user = self.make_user(data_mode="own")
        with hdb.db(user["id"]) as con:
            con.execute("INSERT INTO steps(ts, steps) VALUES(?,?)", (1000, 42))
        path = hdb.db_path(user["id"])
        self.assertTrue(os.path.exists(path))
        self.admin_login()
        csrf = self.csrf_for(f"/admin/users/{user['id']}")
        status, _text, headers = self.browser.post(
            f"/admin/users/{user['id']}", {
                "_csrf": csrf, "action": "delete",
                "confirm": user["username"]})
        self.assertEqual(status, 303)
        self.assertIn("ok=", headers["location"])
        self.assertFalse(os.path.exists(path))
        archive = os.path.join(hdb.data_dir(), "archive")
        folders = [name for name in os.listdir(archive)
                   if name.startswith(f"{user['id']}-")]
        self.assertEqual(len(folders), 1, folders)
        self.assertTrue(os.path.exists(
            os.path.join(archive, folders[0], "health.db")))

    def test_rest_style_member_actions(self):
        user = self.make_user()
        self.admin_login()
        csrf = self.csrf_for(f"/admin/users/{user['id']}")
        status, _text, headers = self.browser.post(
            f"/admin/users/{user['id']}/disable", {"_csrf": csrf})
        self.assertEqual(status, 303)
        self.assertIn("ok=", headers["location"])
        with hdb.db(0) as con:
            self.assertFalse(auth.get_user(con, user["id"])["enabled"])

    def test_archive_failure_rolls_back_and_keeps_user(self):
        user = self.make_user(data_mode="own")
        with hdb.db(user["id"]) as con:
            con.execute("INSERT INTO steps(ts, steps) VALUES(?,?)", (1000, 7))
        path = hdb.db_path(user["id"])
        self.admin_login()
        csrf = self.csrf_for(f"/admin/users/{user['id']}")
        sidecars = [path + suffix for suffix in ("-wal", "-shm", "-journal")]
        for sidecar in sidecars:
            with open(sidecar, "wb") as handle:
                handle.write(b"sidecar")
        with mock.patch.object(admin.os, "replace",
                               side_effect=OSError("injected")):
            status, _text, headers = self.browser.post(
                f"/admin/users/{user['id']}", {
                    "_csrf": csrf, "action": "delete",
                    "confirm": user["username"]})
        self.assertEqual(status, 303)
        self.assertIn("err=", headers["location"])
        with hdb.db(0) as con:
            self.assertIsNotNone(auth.get_user(con, user["id"]))
        self.assertTrue(os.path.exists(path))
        for sidecar in sidecars:
            self.assertTrue(os.path.exists(sidecar), sidecar)
        archive = os.path.join(hdb.data_dir(), "archive")
        if os.path.isdir(archive):
            leftovers = [name for name in os.listdir(archive)
                         if name.startswith(f".{user['id']}-")]
            self.assertEqual(leftovers, [])

        status, _text, headers = self.browser.post(
            f"/admin/users/{user['id']}", {
                "_csrf": csrf, "action": "delete",
                "confirm": user["username"]})
        self.assertEqual(status, 303)
        self.assertIn("ok=", headers["location"])
        with hdb.db(0) as con:
            self.assertIsNone(auth.get_user(con, user["id"]))
        self.assertFalse(os.path.exists(path))
        folders = [name for name in os.listdir(archive)
                   if name.startswith(f"{user['id']}-")]
        self.assertEqual(len(folders), 1, folders)
        archived = os.listdir(os.path.join(archive, folders[0]))
        for name in ("health.db", "health.db-wal", "health.db-shm",
                     "health.db-journal"):
            self.assertIn(name, archived)

    def test_disable_user_revokes_keys_and_sessions(self):
        user = self.make_user()
        with hdb.db(0) as con:
            key = auth.static_token_create(
                con, "alice-phone", owner_user_id=user["id"])
            _sid, cookie = auth.create_session(
                con, auth.subject_for_user(user["id"]), user["revision"])
            secret = auth.session_secret(con)
        self.admin_login()
        status, _text, headers = self.post_admin(
            f"/admin/users/{user['id']}", {"action": "disable"},
            csrf_path=f"/admin/users/{user['id']}")
        self.assertEqual(status, 303)
        self.assertIn("ok=", headers["location"])
        with hdb.db(0) as con:
            self.assertFalse(auth.get_user(con, user["id"])["enabled"])
            self.assertIsNone(auth.static_token_lookup(con, key["token"]))
            self.assertIsNone(auth.read_session(con, cookie, secret))


class PartitionActionTests(AdminBase):
    def test_creds_paste_file_and_invalid(self):
        user = self.make_user(data_mode="own")
        self.admin_login()
        csrf = self.csrf_for(f"/admin/users/{user['id']}")
        page_csrf = csrf
        status, page, _ = self.browser.get(f"/admin/legacy/users/{user['id']}")
        self.assertIn("小米凭据", page)
        self.assertIn("立即同步", page)
        status, _text, headers = self.browser.post(
            f"/admin/users/{user['id']}", {
                "_csrf": page_csrf, "action": "creds",
                "owner": str(user["id"]),
                "creds": '{"userId": "u-1", "passToken": "token-1"}',
            })
        self.assertEqual(status, 303)
        self.assertIn("ok=", headers["location"])
        status, page, _ = self.browser.get(headers["location"])
        self.assertIn("凭据已保存", page)
        self.assertNotIn("token-1", page)
        path = admin._creds_path(user["id"])
        self.assertTrue(os.path.isfile(path))
        self.assertEqual(os.stat(path).st_mode & 0o777, 0o600)
        with open(path, encoding="utf-8") as handle:
            self.assertEqual(json.load(handle), {
                "userId": "u-1", "passToken": "token-1"})

        status, _text, headers = self.post_multipart(
            f"/admin/users/{user['id']}",
            {"_csrf": page_csrf, "action": "creds",
             "owner": str(user["id"]), "creds": ""},
            files={"creds_file": (
                "creds.json",
                '{"userId": "u-2", "passToken": "token-2"}')})
        self.assertEqual(status, 303)
        with open(path, encoding="utf-8") as handle:
            self.assertEqual(json.load(handle)["passToken"], "token-2")

        status, _text, headers = self.browser.post(
            f"/admin/users/{user['id']}", {
                "_csrf": page_csrf, "action": "creds",
                "owner": str(user["id"]), "creds": "{bad json"})
        self.assertEqual(status, 303)
        self.assertIn("err=", headers["location"])
        with open(path, encoding="utf-8") as handle:
            self.assertEqual(json.load(handle)["passToken"], "token-2")

    def test_shared_user_creds_allowed_but_sync_rejected(self):
        user = self.make_user(data_mode="shared")
        self.admin_login()
        status, page, _ = self.browser.get(f"/admin/legacy/users/{user['id']}")
        self.assertEqual(status, 200, page)
        self.assertIn("小米凭据", page)
        self.assertNotIn("立即同步", page)
        csrf = self.csrf_for(f"/admin/users/{user['id']}")
        status, _text, headers = self.browser.post(
            f"/admin/users/{user['id']}", {
                "_csrf": csrf, "action": "creds",
                "owner": str(user["id"]),
                "creds": '{"userId": "u-3", "passToken": "token-3"}'})
        self.assertEqual(status, 303)
        self.assertIn("ok=", headers["location"])
        self.assertTrue(os.path.isfile(admin._creds_path(user["id"])))
        status, _text, headers = self.browser.post(
            f"/admin/users/{user['id']}", {
                "_csrf": csrf, "action": "sync_now",
                "owner": str(user["id"])})
        self.assertEqual(status, 303)
        self.assertIn("err=", headers["location"])

    def test_sync_now_passes_explicit_owner(self):
        user = self.make_user(data_mode="own")
        self.write_creds(user["id"])
        calls = []
        done = threading.Event()

        def fake_sync(days=3, owner=None):
            calls.append((days, owner))
            done.set()
            return {"heart_rate": 1}

        self.admin_login()
        csrf = self.csrf_for(f"/admin/users/{user['id']}")
        with mock.patch.object(server_module, "sync_once", fake_sync):
            status, _text, headers = self.browser.post(
                f"/admin/users/{user['id']}", {
                    "_csrf": csrf, "action": "sync_now",
                    "owner": str(user["id"])})
        self.assertEqual(status, 303)
        self.assertTrue(done.wait(3))
        self.assertEqual(calls, [(3, user["id"])])
        started = []
        self.assertTrue(self.wait_for(lambda: (
            self._audit_row("admin.sync", "ok", started) is not None)))
        self.assertTrue(started)

    def test_dashboard_creds_owner_zero(self):
        self.admin_login()
        csrf = self.csrf_for("/admin")
        status, _text, headers = self.browser.post("/admin", {
            "_csrf": csrf, "action": "creds", "owner": "0",
            "creds": '{"userId": "admin-user", "passToken": "admin-token"}'})
        self.assertEqual(status, 303)
        self.assertIn("ok=", headers["location"])
        path = admin._creds_path(0)
        self.assertTrue(os.path.isfile(path))
        self.assertEqual(os.stat(path).st_mode & 0o777, 0o600)
        status, page, _ = self.browser.get(headers["location"])
        self.assertIn("凭据已保存", page)
        self.assertNotIn("admin-token", page)

    def test_dashboard_sync_passes_owner_zero(self):
        self.write_creds(0)
        calls = []
        done = threading.Event()

        def fake_sync(days=3, owner=None):
            calls.append((days, owner))
            done.set()
            return {}

        self.admin_login()
        csrf = self.csrf_for("/admin")
        with mock.patch.object(server_module, "sync_once", fake_sync):
            status, _text, _headers = self.browser.post("/admin", {
                "_csrf": csrf, "action": "sync_now", "owner": "0"})
        self.assertEqual(status, 303)
        self.assertTrue(done.wait(3))
        self.assertEqual(calls, [(3, 0)])
        self.assertTrue(self.wait_for(lambda: (
            self._audit_row("admin.sync", "ok", []) is not None)))

    def test_backfill_validates_and_passes_owner(self):
        user = self.make_user(data_mode="own")
        self.write_creds(user["id"])
        calls = []

        def fake_backfill(days, resume=True, owner=None):
            calls.append((days, resume, owner))
            return True

        self.admin_login()
        csrf = self.csrf_for(f"/admin/users/{user['id']}")
        with mock.patch.object(server_module, "backfill_async", fake_backfill):
            status, _text, headers = self.browser.post(
                f"/admin/users/{user['id']}", {
                    "_csrf": csrf, "action": "backfill",
                    "owner": str(user["id"]), "days": "30"})
            self.assertEqual(status, 303)
            self.assertIn("ok=", headers["location"])
            status, _text, headers = self.browser.post(
                f"/admin/users/{user['id']}", {
                    "_csrf": csrf, "action": "backfill",
                    "owner": str(user["id"]), "days": "0"})
            self.assertEqual(status, 303)
            self.assertIn("err=", headers["location"])
        self.assertEqual(calls, [(30, True, user["id"])])

    def test_backfill_running_reports_error(self):
        user = self.make_user(data_mode="own")
        self.write_creds(user["id"])
        self.admin_login()
        csrf = self.csrf_for(f"/admin/users/{user['id']}")
        with mock.patch.object(server_module, "backfill_async",
                               return_value=False):
            status, _text, headers = self.browser.post(
                f"/admin/users/{user['id']}", {
                    "_csrf": csrf, "action": "backfill",
                    "owner": str(user["id"]), "days": "7"})
        self.assertEqual(status, 303)
        self.assertIn("err=", headers["location"])

    def test_sync_without_creds_rejected(self):
        user = self.make_user(data_mode="own")
        self.admin_login()
        csrf = self.csrf_for(f"/admin/users/{user['id']}")
        status, _text, headers = self.browser.post(
            f"/admin/users/{user['id']}", {
                "_csrf": csrf, "action": "sync_now",
                "owner": str(user["id"])})
        self.assertEqual(status, 303)
        self.assertIn("err=", headers["location"])

    def _audit_row(self, action, result, sink):
        with hdb.db(0) as con:
            row = con.execute(
                "SELECT id FROM access_log WHERE action=? AND result=? "
                "ORDER BY id DESC LIMIT 1", (action, result)).fetchone()
        if row is not None:
            sink.append(row["id"])
        return row


class StaticKeyTests(AdminBase):
    def test_key_shown_once_then_revoked(self):
        self.admin_login()
        status, page, _headers = self.post_admin("/admin/keys", {
            "action": "create", "label": "laptop", "owner_user_id": "0",
            "tool_policy": "", "window": ""})
        self.assertEqual(status, 200, page)
        found = secrets_in(page)
        self.assertEqual(len(found), 1, page)
        raw = found[0]
        self.assertIn(raw, page)
        self.assertIn("只显示一次", page)

        status, text, _ = self.browser.post(
            "/mcp", b'{"jsonrpc":"2.0","id":1,"method":"tools/list"}',
            {"Content-Type": "application/json",
             "Authorization": "Bearer " + raw})
        self.assertEqual(status, 200, text)

        status, listing, _ = self.browser.get("/admin/legacy/keys")
        self.assertEqual(status, 200, listing)
        self.assertIn("laptop", listing)
        self.assertNotIn(raw, listing)

        with hdb.db(0) as con:
            token = [t for t in auth.static_token_list(con)
                     if t["label"] == "laptop"][0]
        status, _text, headers = self.post_admin(
            "/admin/keys", {"action": "revoke", "token_id": token["id"]})
        self.assertEqual(status, 303)
        self.assertIn("ok=", headers["location"])
        status, _text, _ = self.browser.post(
            "/mcp", b'{"jsonrpc":"2.0","id":1,"method":"tools/list"}',
            {"Content-Type": "application/json",
             "Authorization": "Bearer " + raw})
        self.assertEqual(status, 401)

    def test_key_policy_window_and_invalid_policy(self):
        user = self.make_user()
        self.admin_login()
        status, page, _ = self.post_admin("/admin/keys", {
            "action": "create", "label": "alice-termux",
            "owner_user_id": str(user["id"]),
            "tool_policy": '["query_heart_rate"]',
            "window": '{"tz":"Asia/Shanghai","start":"08:00","end":"22:00"}',
        })
        self.assertEqual(status, 200, page)
        with hdb.db(0) as con:
            token = [t for t in auth.static_token_list(con)
                     if t["label"] == "alice-termux"][0]
        self.assertIn("query_heart_rate", token["tool_policy_json"])
        self.assertIn("Asia/Shanghai", token["window_json"])
        status, listing, _ = self.browser.get("/admin/legacy/keys")
        self.assertIn("alice", listing)
        self.assertIn("query_heart_rate", listing)

        status, _text, headers = self.post_admin("/admin/keys", {
            "action": "create", "label": "bad", "owner_user_id": "0",
            "tool_policy": "{bad", "window": ""})
        self.assertEqual(status, 303)
        self.assertIn("err=", headers["location"])
        with hdb.db(0) as con:
            labels = [t["label"] for t in auth.static_token_list(con)]
        self.assertNotIn("bad", labels)


class OAuthClientTests(AdminBase):
    def test_confidential_client_secret_once_and_revoke(self):
        self.admin_login()
        status, page, _ = self.post_admin("/admin/clients", {
            "action": "create",
            "client_name": "Test Client",
            "redirect_uris": "https://client.example/callback",
            "confidential": "1",
            "tool_policy": '["query_heart_rate"]',
            "window": "",
        })
        self.assertEqual(status, 200, page)
        client_id = re.search(r"hc_[A-Za-z0-9_-]{10,}", page).group(0)
        found = secrets_in(page)
        self.assertEqual(len(found), 1, page)
        secret = found[0]
        self.assertIn(secret, page)
        self.assertNotIn("client_secret_hash", page)

        status, listing, _ = self.browser.get("/admin/legacy/clients")
        self.assertEqual(status, 200, listing)
        self.assertIn("Test Client", listing)
        self.assertIn(client_id, listing)
        self.assertNotIn(secret, listing)

        with hdb.db(0) as con:
            con.execute(
                "INSERT INTO oauth_tokens(token_hash, prefix, kind, client_id, "
                "family_id, scopes_json, user_id, username, user_revision, "
                "resource, created_at, expires_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                (auth.hash_token("oauth-test-access"), "hc_acc_test", "access",
                 client_id, "fam-1", '["health.read"]', 0, "admin", 0,
                 self.base + "/mcp", int(time.time()), int(time.time()) + 3600))
        status, detail, _ = self.browser.get(f"/admin/legacy/clients/{client_id}")
        self.assertEqual(status, 200, detail)
        self.assertIn("health.read", detail)
        self.assertIn("hc_acc_test", detail)

        status, _text, headers = self.post_admin("/admin/clients", {
            "action": "revoke", "client_id": client_id,
            "confirm": "Test Client"})
        self.assertEqual(status, 303)
        self.assertIn("ok=", headers["location"])
        with hdb.db(0) as con:
            self.assertTrue(oauth.get_client(con, client_id)["revoked"])
            row = con.execute(
                "SELECT revoked_at FROM oauth_tokens WHERE client_id=?",
                (client_id,)).fetchone()
        self.assertIsNotNone(row["revoked_at"])

    def test_expired_client_token_labeled_and_not_counted(self):
        self.admin_login()
        status, page, _ = self.post_admin("/admin/clients", {
            "action": "create", "client_name": "Expired Token Client",
            "redirect_uris": "https://expired.example/cb"})
        self.assertEqual(status, 200, page)
        client_id = re.search(r"hc_[A-Za-z0-9_-]{10,}", page).group(0)
        with hdb.db(0) as con:
            con.execute(
                "INSERT INTO oauth_tokens(token_hash, prefix, kind, client_id, "
                "family_id, scopes_json, user_id, username, user_revision, "
                "resource, created_at, expires_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                (auth.hash_token("expired-access"), "hc_expired", "access",
                 client_id, "fam-exp", '["health.read"]', 0, "admin", 0,
                 self.base + "/mcp", int(time.time()) - 7200,
                 int(time.time()) - 3600))
            counts, _scopes = admin._client_rows(con)
        self.assertEqual(counts.get(client_id, 0), 0)
        status, detail, _ = self.browser.get(f"/admin/legacy/clients/{client_id}")
        self.assertEqual(status, 200, detail)
        self.assertIn("已过期", detail)

    def test_client_revoke_requires_name_and_valid_id(self):
        self.admin_login()
        status, page, _ = self.post_admin("/admin/clients", {
            "action": "create", "client_name": "Confirm Client",
            "redirect_uris": "https://confirm.example/cb"})
        self.assertEqual(status, 200, page)
        client_id = re.search(r"hc_[A-Za-z0-9_-]{10,}", page).group(0)
        csrf = self.csrf_for("/admin/clients")
        status, _text, headers = self.browser.post("/admin/clients", {
            "_csrf": csrf, "action": "revoke", "client_id": client_id,
            "confirm": "wrong name"})
        self.assertEqual(status, 303)
        self.assertIn("err=", headers["location"])
        with hdb.db(0) as con:
            self.assertFalse(oauth.get_client(con, client_id)["revoked"])
        status, _text, headers = self.browser.post("/admin/clients", {
            "_csrf": csrf, "action": "revoke", "client_id": "bad",
            "confirm": "Confirm Client"})
        self.assertEqual(status, 303)
        self.assertIn("err=", headers["location"])
        status, _text, headers = self.browser.post("/admin/clients", {
            "_csrf": csrf, "action": "revoke", "client_id": "hc_doesnotexist",
            "confirm": "Confirm Client"})
        self.assertEqual(status, 303)
        self.assertIn("err=", headers["location"])

    def test_public_client_has_no_secret_and_bad_redirect_rejected(self):
        self.admin_login()
        status, page, _ = self.post_admin("/admin/clients", {
            "action": "create", "client_name": "Public Client",
            "redirect_uris": "https://public.example/cb"})
        self.assertEqual(status, 200, page)
        self.assertEqual(secrets_in(page), [])
        self.assertIn("公开", page)

        status, _text, headers = self.post_admin("/admin/clients", {
            "action": "create", "client_name": "Bad",
            "redirect_uris": "http://evil.example/cb"})
        self.assertEqual(status, 303)
        self.assertIn("err=", headers["location"])
        with hdb.db(0) as con:
            names = [c["client_name"] for c in oauth.list_clients(con)]
        self.assertNotIn("Bad", names)


class SessionTests(AdminBase):
    def test_list_and_revoke_user_session(self):
        user = self.make_user()
        user_browser, status, _text, _ = self.user_login(
            "alice", "alice-password-123")
        self.assertEqual(status, 303)
        self.admin_login()
        status, page, _ = self.browser.get("/admin/legacy/sessions")
        self.assertEqual(status, 200, page)
        self.assertIn("admin（管理员）", page)
        self.assertIn("alice", page)
        with hdb.db(0) as con:
            rows = auth.list_sessions(con, subject=auth.subject_for_user(user["id"]))
        self.assertEqual(len(rows), 1)
        sid_hash = rows[0]["sid_hash"]
        status, _text, headers = self.post_admin("/admin/sessions", {
            "action": "revoke", "sid_hash": sid_hash})
        self.assertEqual(status, 303)
        self.assertIn("ok=", headers["location"])
        # 用户的 Cookie 还在，但服务端会话已吊销。
        status, _page, headers = user_browser.get("/account")
        self.assertEqual(status, 302)
        self.assertTrue(headers["location"].startswith("/login?next="))

    def test_expired_session_is_labeled_and_not_revocable(self):
        user = self.make_user()
        with hdb.db(0) as con:
            sid, _cookie = auth.create_session(
                con, auth.subject_for_user(user["id"]), user["revision"],
                ttl=1, now=int(time.time()) - 10)
            sid_hash = auth.hash_token(sid)
        self.admin_login()
        status, page, _ = self.browser.get("/admin/legacy/sessions")
        self.assertEqual(status, 200, page)
        self.assertIn("已过期", page)
        self.assertNotIn(f'name="sid_hash" value="{sid_hash}"', page)

    def test_current_session_cannot_be_revoked(self):
        self.admin_login()
        with hdb.db(0) as con:
            rows = auth.list_sessions(con, subject=auth.ADMIN_SUBJECT)
        current_hash = rows[0]["sid_hash"]
        status, _text, headers = self.post_admin("/admin/sessions", {
            "action": "revoke", "sid_hash": current_hash})
        self.assertEqual(status, 303)
        self.assertIn("err=", headers["location"])
        status, page, _ = self.browser.get("/admin/legacy")
        self.assertEqual(status, 200, page)

    def test_revoke_others_keeps_current(self):
        self.admin_login()
        with hdb.db(0) as con:
            _sid2, cookie2 = auth.create_session(
                con, auth.ADMIN_SUBJECT, 0)
            secret = auth.session_secret(con)
        status, _text, headers = self.post_admin("/admin/sessions", {
            "action": "revoke_others"})
        self.assertEqual(status, 303)
        self.assertIn("ok=", headers["location"])
        with hdb.db(0) as con:
            self.assertIsNone(auth.read_session(con, cookie2, secret))
            self.assertEqual(len(auth.list_sessions(
                con, subject=auth.ADMIN_SUBJECT)), 1)
        status, page, _ = self.browser.get("/admin/legacy")
        self.assertEqual(status, 200, page)

    def test_account_revoke_rejects_other_users_session(self):
        alice = self.make_user()
        self.make_user("bob", "bob-password-1234")
        with hdb.db(0) as con:
            _sid, cookie = auth.create_session(
                con, auth.subject_for_user(alice["id"]), alice["revision"])
            secret = auth.session_secret(con)
            alice_sid_hash = auth.hash_token(_sid)
        browser, status, _text, _ = self.user_login(
            "bob", "bob-password-1234")
        self.assertEqual(status, 303)
        csrf = self.csrf_for("/account", browser)
        status, _text, headers = browser.post("/account/password", {
            "_csrf": csrf, "action": "revoke_session",
            "sid_hash": alice_sid_hash})
        self.assertEqual(status, 303)
        self.assertIn("err=", headers["location"])
        with hdb.db(0) as con:
            self.assertIsNotNone(auth.read_session(con, cookie, secret))


class PasswordTests(AdminBase):
    def test_user_password_change_keeps_current_session_and_reissues_cookie(self):
        self.make_user()
        browser, status, _text, _ = self.user_login(
            "alice", "alice-password-123")
        self.assertEqual(status, 303)
        old_cookie = browser.cookies[auth.SESSION_COOKIE]
        csrf = self.csrf_for("/account", browser)
        status, _text, headers = browser.post("/account/password", {
            "_csrf": csrf,
            "action": "change_password",
            "current_password": "alice-password-123",
            "new_password": "alice-password-456",
            "confirm_password": "alice-password-456",
        })
        self.assertEqual(status, 303)
        self.assertIn("ok=", headers["location"])
        new_cookie = browser.cookies.get(auth.SESSION_COOKIE)
        self.assertIsNotNone(new_cookie)
        self.assertNotEqual(old_cookie, new_cookie)
        status, page, _ = browser.get("/account")
        self.assertEqual(status, 200, page)
        self.assertIn("alice", page)

        stale = Browser(self.base)
        stale.cookies[auth.SESSION_COOKIE] = old_cookie
        status, _page, headers = stale.get("/account")
        self.assertEqual(status, 302)
        self.assertTrue(headers["location"].startswith("/login?next="))

        fresh, status, _text, headers = self.user_login(
            "alice", "alice-password-456", browser=Browser(self.base))
        self.assertEqual(status, 303)
        self.assertEqual(headers["location"], "/account")

    def test_user_password_change_requires_current_and_match(self):
        self.make_user()
        browser, _, _, _ = self.user_login("alice", "alice-password-123")
        csrf = self.csrf_for("/account", browser)
        base = {"_csrf": csrf, "action": "change_password"}
        status, _text, headers = browser.post("/account/password", {
            **base, "current_password": "wrong-password",
            "new_password": "alice-password-456",
            "confirm_password": "alice-password-456"})
        self.assertEqual(status, 303)
        self.assertIn("err=", headers["location"])
        status, _text, headers = browser.post("/account/password", {
            **base, "current_password": "alice-password-123",
            "new_password": "alice-password-456",
            "confirm_password": "different-password"})
        self.assertEqual(status, 303)
        self.assertIn("err=", headers["location"])
        status, page, _ = browser.get("/account")
        self.assertEqual(status, 200, page)

    def test_admin_password_change_reissues_session(self):
        self.admin_login()
        old_cookie = self.browser.cookies[auth.SESSION_COOKIE]
        csrf = self.csrf_for("/admin/settings")
        status, _text, headers = self.browser.post("/admin/settings", {
            "_csrf": csrf, "action": "change_password",
            "current_password": ADMIN_PW,
            "new_password": "new-admin-password-123",
            "confirm_password": "new-admin-password-123"})
        self.assertEqual(status, 303)
        self.assertIn("ok=", headers["location"])
        new_cookie = self.browser.cookies.get(auth.SESSION_COOKIE)
        self.assertIsNotNone(new_cookie)
        self.assertNotEqual(old_cookie, new_cookie)
        status, page, _ = self.browser.get("/admin/legacy")
        self.assertEqual(status, 200, page)
        stale = Browser(self.base)
        stale.cookies[auth.SESSION_COOKIE] = old_cookie
        status, _page, headers = stale.get("/admin")
        self.assertEqual(status, 302)
        self.assertTrue(headers["location"].startswith("/login?next="))
        _browser, status, text, headers = self.user_login(
            "admin", "new-admin-password-123", browser=Browser(self.base))
        self.assertEqual(status, 303, text)
        self.assertEqual(headers["location"], "/admin")


class TOTPTests(AdminBase):
    def _prepare(self) -> tuple[str, str]:
        self.admin_login()
        status, _text, headers = self.post_admin(
            "/admin/security", {"action": "totp_prepare",
                                "current_password": ADMIN_PW})
        self.assertEqual(status, 303)
        self.assertIn("ok=", headers["location"])
        status, page, _ = self.browser.get("/admin/legacy/security")
        self.assertEqual(status, 200, page)
        found = secrets_in(page)
        self.assertTrue(found, page)
        secret = found[0]
        self.assertIn("otpauth://totp/", page)
        self.assertIn(secret, page)
        return secret, page

    def test_totp_confirm_generates_recovery_codes_then_disable(self):
        secret, _page = self._prepare()
        csrf = self.csrf_for("/admin/security")
        correct = totp.code(secret)
        wrong = totp.code(secret, at=time.time() + 3600)
        status, _text, headers = self.browser.post("/admin/security", {
            "_csrf": csrf, "action": "totp_confirm",
            "current_password": ADMIN_PW, "code": wrong})
        self.assertEqual(status, 303)
        self.assertIn("err=", headers["location"])
        with hdb.db(0) as con:
            self.assertFalse(auth.admin_totp_state(con)["enabled"])

        status, page, _ = self.browser.post("/admin/security", {
            "_csrf": csrf, "action": "totp_confirm",
            "current_password": ADMIN_PW, "code": correct})
        self.assertEqual(status, 200, page)
        self.assertIn("两步验证已启用", page)
        codes = [line for block in secrets_in(page)
                 for line in block.splitlines() if line]
        self.assertEqual(len(codes), 10, page)
        with hdb.db(0) as con:
            self.assertTrue(auth.admin_totp_state(con)["enabled"])
            self.assertEqual(auth.admin_recovery_remaining(con), 10)
        status, page, _ = self.browser.get("/admin/legacy/security")
        self.assertEqual(status, 200, page)
        self.assertIn("已启用", page)
        self.assertNotIn(secret, page)

        otp_browser, status, text, headers = self.user_login(
            "admin", ADMIN_PW, browser=Browser(self.base))
        self.assertEqual(status, 303, text)
        self.assertTrue(headers["location"].startswith("/login/otp"))
        status, page, _ = otp_browser.get(headers["location"])
        self.assertEqual(status, 200, page)
        otp_csrf = hidden(page, "_csrf")
        # 确认时已消费当前 step，用下一个 step 的码。
        status, text, headers = otp_browser.post("/login/otp", {
            "_csrf": otp_csrf,
            "code": totp.code(secret, at=time.time() + 30)})
        self.assertEqual(status, 303, text)
        self.assertEqual(headers["location"], "/admin")

        # 关闭需要密码 + 动态码/恢复码；这里用恢复码，避免 step 重放。
        status, _text, headers = self.browser.post("/admin/security", {
            "_csrf": csrf, "action": "totp_disable",
            "current_password": ADMIN_PW, "code": codes[0]})
        self.assertEqual(status, 303)
        self.assertIn("ok=", headers["location"])
        with hdb.db(0) as con:
            self.assertFalse(auth.admin_totp_state(con)["enabled"])
        _browser, status, _text, headers = self.user_login(
            "admin", ADMIN_PW, browser=Browser(self.base))
        self.assertEqual(status, 303)
        self.assertEqual(headers["location"], "/admin")

    def test_totp_enrollment_requires_password(self):
        self.admin_login()
        csrf = self.csrf_for("/admin/security")
        status, _text, headers = self.browser.post("/admin/security", {
            "_csrf": csrf, "action": "totp_prepare",
            "current_password": "wrong-password"})
        self.assertEqual(status, 303)
        self.assertIn("err=", headers["location"])
        with hdb.db(0) as con:
            state = auth.admin_totp_state(con)
            self.assertFalse(state["enabled"])
            self.assertIsNone(state["secret"])
            self.assertEqual(auth.admin_recovery_remaining(con), 0)

        status, _text, headers = self.browser.post("/admin/security", {
            "_csrf": csrf, "action": "totp_prepare",
            "current_password": ADMIN_PW})
        self.assertEqual(status, 303)
        with hdb.db(0) as con:
            state = auth.admin_totp_state(con)
            self.assertFalse(state["enabled"])
            self.assertTrue(state["secret"])
            self.assertEqual(auth.admin_recovery_remaining(con), 0)
        secret = state["secret"]

        status, _text, headers = self.browser.post("/admin/security", {
            "_csrf": csrf, "action": "totp_confirm",
            "current_password": "wrong-password",
            "code": totp.code(secret)})
        self.assertEqual(status, 303)
        self.assertIn("err=", headers["location"])
        with hdb.db(0) as con:
            self.assertFalse(auth.admin_totp_state(con)["enabled"])
            self.assertEqual(auth.admin_recovery_remaining(con), 0)

        status, page, _ = self.browser.post("/admin/security", {
            "_csrf": csrf, "action": "totp_confirm",
            "current_password": ADMIN_PW, "code": totp.code(secret)})
        self.assertEqual(status, 200, page)
        self.assertIn("两步验证已启用", page)

    def test_totp_pending_secret_can_be_regenerated(self):
        secret1, _page = self._prepare()
        csrf = self.csrf_for("/admin/security")
        status, _text, headers = self.browser.post("/admin/security", {
            "_csrf": csrf, "action": "totp_prepare",
            "current_password": ADMIN_PW})
        self.assertEqual(status, 303)
        status, page, _ = self.browser.get("/admin/legacy/security")
        found = secrets_in(page)
        self.assertTrue(found)
        self.assertNotEqual(found[0], secret1)
        with hdb.db(0) as con:
            self.assertFalse(auth.admin_totp_state(con)["enabled"])

    def test_recovery_regeneration_requires_code(self):
        secret, _page = self._prepare()
        csrf = self.csrf_for("/admin/security")
        status, page, _ = self.browser.post("/admin/security", {
            "_csrf": csrf, "action": "totp_confirm",
            "current_password": ADMIN_PW, "code": totp.code(secret)})
        self.assertEqual(status, 200, page)
        codes = [line for block in secrets_in(page)
                 for line in block.splitlines() if line]
        self.assertEqual(len(codes), 10)
        status, _text, headers = self.browser.post("/admin/security", {
            "_csrf": csrf, "action": "recovery_generate",
            "current_password": ADMIN_PW})
        self.assertEqual(status, 303)
        self.assertIn("err=", headers["location"])
        with hdb.db(0) as con:
            self.assertEqual(auth.admin_recovery_remaining(con), 10)
        status, page, _ = self.browser.post("/admin/security", {
            "_csrf": csrf, "action": "recovery_generate",
            "current_password": ADMIN_PW, "code": codes[0]})
        self.assertEqual(status, 200, page)
        new_codes = [line for block in secrets_in(page)
                     for line in block.splitlines() if line]
        self.assertEqual(len(new_codes), 10)
        self.assertNotIn(codes[0], new_codes)
        with hdb.db(0) as con:
            self.assertEqual(auth.admin_recovery_remaining(con), 10)

    def test_prepare_refused_when_enabled_and_bad_disable(self):
        secret, _page = self._prepare()
        csrf = self.csrf_for("/admin/security")
        status, page, _ = self.browser.post("/admin/security", {
            "_csrf": csrf, "action": "totp_confirm",
            "current_password": ADMIN_PW, "code": totp.code(secret)})
        self.assertEqual(status, 200, page)
        codes = [line for block in secrets_in(page)
                 for line in block.splitlines() if line]
        status, _text, headers = self.browser.post("/admin/security", {
            "_csrf": csrf, "action": "totp_prepare",
            "current_password": ADMIN_PW})
        self.assertEqual(status, 303)
        self.assertIn("err=", headers["location"])
        status, _text, headers = self.browser.post("/admin/security", {
            "_csrf": csrf, "action": "totp_disable",
            "current_password": "wrong-password", "code": codes[0]})
        self.assertEqual(status, 303)
        self.assertIn("err=", headers["location"])
        with hdb.db(0) as con:
            self.assertTrue(auth.admin_totp_state(con)["enabled"])


class ValidationTests(AdminBase):
    def test_csrf_and_same_origin_required(self):
        self.admin_login()
        status, text, _ = self.browser.post("/admin/users", {
            "action": "create", "username": "eve",
            "password": "eve-password-123", "role": "user"})
        self.assertEqual(status, 403, text)
        csrf = self.csrf_for("/admin/users")
        status, text, _ = self.browser.post("/admin/users", {
            "_csrf": "tampered", "action": "create", "username": "eve",
            "password": "eve-password-123", "role": "user"})
        self.assertEqual(status, 403, text)
        status, text, _ = self.browser.post("/admin/users", {
            "_csrf": csrf, "action": "create", "username": "eve",
            "password": "eve-password-123", "role": "user",
        }, {"Origin": "https://evil.example"})
        self.assertEqual(status, 403, text)
        with hdb.db(0) as con:
            self.assertIsNone(auth.by_username(con, "eve"))

    def test_user_fields_are_escaped(self):
        user = self.make_user(
            display_name='<img src=x onerror=alert(1)>')
        self.admin_login()
        status, page, _ = self.browser.get(f"/admin/legacy/users/{user['id']}")
        self.assertEqual(status, 200, page)
        self.assertIn("&lt;img", page)
        self.assertNotIn("<img src=x", page)
        status, page, _ = self.browser.get("/admin/legacy/users")
        self.assertIn("&lt;img", page)
        self.assertNotIn("<img src=x", page)

    def test_admin_form_limit_is_64kb(self):
        self.admin_login()
        csrf = self.csrf_for("/admin/settings")
        status, _text, _ = self.browser.post("/admin/settings", {
            "_csrf": csrf, "action": "set_interval",
            "fetch_interval": "900", "pad": "x" * (20 * 1024)})
        self.assertEqual(status, 303)
        status, text, _ = self.browser.post("/admin/settings", {
            "_csrf": csrf, "action": "set_interval",
            "fetch_interval": "900", "pad": "x" * (70 * 1024)})
        self.assertEqual(status, 413, text)

    def test_settings_interval_persisted_and_validated(self):
        self.admin_login()
        status, _text, headers = self.post_admin("/admin/settings", {
            "action": "set_interval", "fetch_interval": "600"})
        self.assertEqual(status, 303)
        self.assertIn("ok=", headers["location"])
        with hdb.db(0) as con:
            self.assertEqual(
                auth.settings_get(con, "fetch_interval_override"), "600")
        status, _text, headers = self.post_admin("/admin/settings", {
            "action": "set_interval", "fetch_interval": "60"})
        self.assertEqual(status, 303)
        self.assertIn("err=", headers["location"])

    def test_admin_action_writes_fallback_audit_row(self):
        self.admin_login()
        status, _text, _headers = self.post_admin("/admin/settings", {
            "action": "set_interval", "fetch_interval": "1200"})
        self.assertEqual(status, 303)
        with hdb.db(0) as con:
            row = con.execute(
                "SELECT actor, kind, action, result FROM access_log "
                "WHERE action='admin.settings.update'").fetchone()
        self.assertIsNotNone(row)
        self.assertEqual(row["actor"], "admin")
        self.assertEqual(row["kind"], "admin")
        self.assertEqual(row["result"], "ok")
        status, page, _ = self.browser.get("/admin/legacy/audit?action=admin.settings.update")
        self.assertEqual(status, 200, page)
        self.assertIn("admin.settings.update", page)

    def test_audit_sink_errors_are_not_swallowed(self):
        calls = []

        def bad_sink(con, action, **kwargs):
            calls.append(action)
            raise TypeError("bad signature")

        sys.modules["hcb.audit"] = types.SimpleNamespace(audit_event=bad_sink)
        try:
            with hdb.db(0) as con:
                with self.assertRaises(TypeError):
                    admin.record_admin_action(con, "admin.test")
        finally:
            sys.modules.pop("hcb.audit", None)
        self.assertEqual(calls, ["admin.test"])

    def test_audit_hook_forwards_to_audit_module(self):
        calls = []

        def fake_audit_event(con, action, **kwargs):
            calls.append((action, kwargs))

        fake = types.SimpleNamespace(audit_event=fake_audit_event)
        sys.modules["hcb.audit"] = fake
        try:
            with hdb.db(0) as con:
                admin.record_admin_action(
                    con, "admin.test", detail="id=1")
        finally:
            sys.modules.pop("hcb.audit", None)
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0][0], "admin.test")
        self.assertEqual(calls[0][1]["detail"], "id=1")
        self.assertEqual(calls[0][1]["actor"], "admin")

    def test_fetch_interval_override_helper(self):
        with hdb.db(0) as con:
            auth.settings_set(con, "fetch_interval_override", "1200")
        self.assertEqual(server_module.fetch_interval(), 1200)
        with hdb.db(0) as con:
            auth.settings_set(con, "fetch_interval_override", "10")
        self.assertEqual(server_module.fetch_interval(),
                         server_module.FETCH_INTERVAL)

    def test_audit_page_filters_and_escapes(self):
        with hdb.db(0) as con:
            con.execute(
                "INSERT INTO access_log(actor, kind, action, tool, result, "
                "detail, created_at) VALUES(?,?,?,?,?,?,?)",
                ("<b>bad</b>", "static", "mcp.call", "query_heart_rate",
                 "denied", "x", int(time.time())))
        self.admin_login()
        status, page, _ = self.browser.get("/admin/legacy/audit")
        self.assertEqual(status, 200, page)
        self.assertIn("&lt;b&gt;bad&lt;/b&gt;", page)
        self.assertNotIn("<b>bad</b>", page)
        self.assertIn("query_heart_rate", page)
        status, page, _ = self.browser.get(
            "/admin/legacy/audit?action=mcp.call&result=denied")
        self.assertEqual(status, 200, page)
        self.assertIn("query_heart_rate", page)
        status, page, _ = self.browser.get("/admin/legacy/audit?result=allowed")
        self.assertNotIn("query_heart_rate", page)

    def test_legacy_fallback_posts_and_404s(self):
        """旧 SSR 表单在 /admin/legacy/... 下仍可用一个版本（含 flash 与链接）。"""
        self.admin_login()
        status, page, _ = self.browser.get("/admin/legacy/users")
        self.assertEqual(status, 200, page)
        csrf = hidden(page, "_csrf")
        self.assertTrue(csrf)
        status, _text, headers = self.browser.post("/admin/legacy/users", {
            "_csrf": csrf, "action": "create", "username": "legacy",
            "display_name": "Legacy", "password": "legacy-password-123",
            "scope": ["health.read"], "data_mode": "shared",
            "enabled": "1"})
        self.assertEqual(status, 303, _text)
        self.assertTrue(headers["location"].startswith(
            "/admin/legacy/users?ok="), headers)
        status, page, _ = self.browser.get(headers["location"])
        self.assertEqual(status, 200, page)
        self.assertIn("用户已创建", page)
        with hdb.db(0) as con:
            user = auth.by_username(con, "legacy")
        self.assertIsNotNone(user)
        self.assertIn(f'href="/admin/legacy/users/{user["id"]}"', page)
        status, _text, headers = self.browser.get("/admin/legacy/")
        self.assertEqual(status, 301)
        self.assertEqual(headers["location"], "/admin/legacy")
        status, page, _ = self.browser.get("/admin/legacy/nope")
        self.assertEqual(status, 404, page)
        status, page, _ = self.browser.get("/admin/legacy/users/999")
        self.assertEqual(status, 404, page)

    def test_trailing_slash_and_aliases(self):
        status, _text, headers = self.browser.get("/account/")
        self.assertEqual(status, 301)
        self.assertEqual(headers["location"], "/account")
        status, _text, headers = self.browser.get("/account")
        self.assertEqual(status, 302)
        self.assertIn("/login", headers["location"])
        self.admin_login()
        for alias in ("/admin/tokens", "/admin/static-keys",
                      "/admin/oauth", "/admin/oauth-clients"):
            status, _text, headers = self.browser.get(alias)
            self.assertEqual(status, 302, alias)
            self.assertTrue(headers["location"].startswith("/admin#/"), alias)
        for alias in ("/admin/legacy/tokens", "/admin/legacy/static-keys",
                      "/admin/legacy/oauth", "/admin/legacy/oauth-clients"):
            status, page, _ = self.browser.get(alias)
            self.assertEqual(status, 200, alias)
            self.assertIn("退出登录", page)


if __name__ == "__main__":
    unittest.main()
