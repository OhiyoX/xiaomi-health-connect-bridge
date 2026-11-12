"""Task 4: HTTP 路由、发现、登录/同意与 MCP 鉴权的测试。

只跑标准库。覆盖：两份元数据、`/mcp` 401 挑战、注册→登录→同意→换码→
`tools/list`、CSRF 与签名请求（不能借 POST 放宽 scope）、16 KB 请求体、
限流、`/ingest` 只认静态 key（OAuth 403、按 owner 落分区）、`/admin` 门禁、
管理员 TOTP 两步登录、管理员引导失败不影响 `/mcp`。
"""
from __future__ import annotations

import html
import http.client
import json
import os
import re
import shutil
import sys
import tempfile
import threading
import time
import unittest
import urllib.parse
from http.server import ThreadingHTTPServer

NAS_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if NAS_DIR not in sys.path:
    sys.path.insert(0, NAS_DIR)

from hcb import auth  # noqa: E402
from hcb import context  # noqa: E402
from hcb import db as hdb  # noqa: E402
from hcb import oauth  # noqa: E402
from hcb import ratelimit  # noqa: E402
from hcb import totp  # noqa: E402
from hcb import web  # noqa: E402

import server  # noqa: E402

ADMIN_PW = "correct-horse-battery-staple"
VERIFIER = "a" * 43 + "B7._~-0123456789"
CHALLENGE = oauth.pkce_challenge(VERIFIER)
STATIC_TOKEN = "static-secret-for-tests"


class Browser:
    """极简 HTTP 客户端：保存 Cookie、不跟随重定向、手动记录响应头。"""

    def __init__(self, base: str, timeout: float = 10):
        self.base = base
        self.timeout = timeout
        self.cookies: dict[str, str] = {}

    def _url(self, path: str) -> str:
        if path.startswith("http://") or path.startswith("https://"):
            return path
        return self.base + path

    def request(self, method: str, path: str, body=None, headers=None):
        url = urllib.parse.urlsplit(self._url(path))
        conn = http.client.HTTPConnection(url.hostname, url.port or 80, timeout=self.timeout)
        target = url.path or "/"
        if url.query:
            target += "?" + url.query
        hdrs = dict(headers or {})
        if body is None:
            data = None
        elif isinstance(body, (bytes, bytearray)):
            data = bytes(body)
        elif isinstance(body, str):
            data = body.encode("utf-8")
        else:
            data = urllib.parse.urlencode(body, doseq=True).encode("utf-8")
            hdrs.setdefault("Content-Type", "application/x-www-form-urlencoded")
        if data is not None:
            hdrs.setdefault("Content-Length", str(len(data)))
        if self.cookies:
            hdrs["Cookie"] = "; ".join(f"{k}={v}" for k, v in self.cookies.items())
        conn.request(method, target, body=data, headers=hdrs)
        resp = conn.getresponse()
        raw = resp.read()
        resp_headers: dict[str, str] = {}
        for key, value in resp.getheaders():
            lk = key.lower()
            if lk == "set-cookie":
                name, _, rest = value.partition("=")
                cookie_value = rest.split(";", 1)[0]
                if "max-age=0" in value.lower():
                    self.cookies.pop(name.strip(), None)
                else:
                    self.cookies[name.strip()] = cookie_value
            elif lk in resp_headers:
                resp_headers[lk] += "," + value
            else:
                resp_headers[lk] = value
        status = resp.status
        conn.close()
        return status, raw.decode("utf-8", "replace"), resp_headers

    def get(self, path: str, headers=None):
        return self.request("GET", path, None, headers)

    def post(self, path: str, body=None, headers=None):
        return self.request("POST", path, body, headers)


def hidden(page: str, name: str) -> str | None:
    match = re.search(r'name="%s"[^>]*value="([^"]*)"' % re.escape(name), page)
    if match is None:
        match = re.search(r'value="([^"]*)"[^>]*name="%s"' % re.escape(name), page)
    return html.unescape(match.group(1)) if match else None


def json_body(value) -> bytes:
    return json.dumps(value).encode("utf-8")


class HTTPTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="hcb-http-")
        self.saved_env = {
            key: os.environ.get(key)
            for key in ("HEALTH_DB", "PUBLIC_BASE_URL", "HCB_ADMIN_PASSWORD",
                        "HEALTH_MCP_TOKENS", "HEALTH_MCP_TOKEN", "HCB_TRUST_PROXY",
                        "HCB_TRUSTED_PROXIES")
        }
        for key in ("PUBLIC_BASE_URL", "HEALTH_MCP_TOKENS", "HEALTH_MCP_TOKEN",
                    "HCB_TRUSTED_PROXIES"):
            os.environ.pop(key, None)
        os.environ["HEALTH_DB"] = os.path.join(self.tmp, "health.db")
        os.environ["HCB_ADMIN_PASSWORD"] = ADMIN_PW
        os.environ["HCB_TRUST_PROXY"] = "0"
        context.set_owner(0)
        ratelimit.clear()

        server.AGENT_TOKENS = []
        server.API_TOKEN = ""
        server.AUTH_ON = False
        server.startup()
        with hdb.db(0) as con:
            auth.static_token_create(con, "phone", token=STATIC_TOKEN, owner_user_id=0)

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        self.httpd.daemon_threads = True
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.httpd.server_address[1]}"
        os.environ["PUBLIC_BASE_URL"] = self.base
        self.redirect = self.base + "/callback"
        self.browser = Browser(self.base)

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=3)
        context.set_owner(0)
        ratelimit.clear()
        for key, value in self.saved_env.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value
        shutil.rmtree(self.tmp, ignore_errors=True)

    # ---------------------------------------------------------- helpers
    def static_auth(self):
        return {"Authorization": "Bearer " + STATIC_TOKEN}

    def register(self, name="Test App", redirect=None):
        body = {
            "client_name": name,
            "redirect_uris": [redirect or self.redirect],
            "token_endpoint_auth_method": "none",
        }
        status, text, _ = self.browser.post(
            "/register", json_body(body), {"Content-Type": "application/json"})
        self.assertEqual(status, 201, text)
        return json.loads(text)

    def authorize_params(self, client_id, scopes=("health.read", "health.write"),
                         state="st-1", redirect=None, resource=None, challenge=CHALLENGE):
        params = {
            "response_type": "code",
            "client_id": client_id,
            "redirect_uri": redirect or self.redirect,
            "code_challenge": challenge,
            "code_challenge_method": "S256",
            "state": state,
            "resource": resource or (self.base + "/mcp"),
            "scope": " ".join(scopes),
        }
        return params

    def authorize_path(self, params):
        return "/authorize?" + urllib.parse.urlencode(params)

    def login_page(self, browser=None):
        browser = browser or self.browser
        status, page, _ = browser.get("/login")
        return status, page, hidden(page, "_csrf")

    def login(self, username, password, next_path=None, browser=None):
        browser = browser or self.browser
        status, page, csrf = self.login_page(browser)
        self.assertEqual(status, 200, page)
        form = {"username": username, "password": password, "_csrf": csrf}
        if next_path:
            form["next"] = next_path
        return browser.post("/login", form)

    def login_and_authorize_page(self, params, username="admin", password=ADMIN_PW):
        browser = self.browser
        status, text, headers = browser.get(self.authorize_path(params))
        self.assertEqual(status, 302, text)
        self.assertTrue(headers.get("location", "").startswith("/login?next="), headers)
        status, text, headers = self.login(username, password, self.authorize_path(params))
        self.assertEqual(status, 303, text)
        self.assertEqual(headers.get("location"), self.authorize_path(params))
        return browser.get(self.authorize_path(params))

    def consent_and_token(self, scopes=("health.read", "health.write"), client=None):
        client = client or self.register()
        params = self.authorize_params(client["client_id"], scopes=scopes)
        status, page, _ = self.login_and_authorize_page(params)
        self.assertEqual(status, 200, page)
        csrf = hidden(page, "_csrf")
        request = hidden(page, "request")
        self.assertTrue(csrf and request)
        status, text, headers = self.browser.post("/authorize", {
            "_csrf": csrf, "request": request, "decision": "allow",
            "scope": list(scopes),
        })
        self.assertEqual(status, 302, text)
        location = headers.get("location", "")
        self.assertTrue(location.startswith(self.redirect), location)
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(location).query)
        self.assertIn("code", query)
        status, text, headers = self.browser.post("/token", {
            "grant_type": "authorization_code",
            "code": query["code"][0],
            "client_id": client["client_id"],
            "redirect_uri": self.redirect,
            "code_verifier": VERIFIER,
        })
        self.assertEqual(status, 200, text)
        return client, params, json.loads(text)

    def mcp(self, token: str, method="tools/list", params=None):
        body = {"jsonrpc": "2.0", "id": 1, "method": method}
        if params is not None:
            body["params"] = params
        return self.browser.post(
            "/mcp", json_body(body),
            {"Content-Type": "application/json", "Authorization": "Bearer " + token})


class MetadataTests(HTTPTestCase):
    def test_protected_resource_metadata_both_paths(self):
        for path in ("/.well-known/oauth-protected-resource",
                     "/.well-known/oauth-protected-resource/mcp"):
            status, text, headers = self.browser.get(path)
            self.assertEqual(status, 200, text)
            doc = json.loads(text)
            self.assertEqual(doc["resource"], self.base + "/mcp")
            self.assertEqual(doc["authorization_servers"], [self.base])
            self.assertEqual(doc["scopes_supported"], ["health.read", "health.write"])
            self.assertIn("resource_name", doc)
            self.assertIn("application/json", headers["content-type"])

    def test_authorization_server_metadata(self):
        status, text, _ = self.browser.get("/.well-known/oauth-authorization-server")
        self.assertEqual(status, 200, text)
        doc = json.loads(text)
        self.assertEqual(doc["issuer"], self.base)
        self.assertEqual(doc["authorization_endpoint"], self.base + "/authorize")
        self.assertEqual(doc["token_endpoint"], self.base + "/token")
        self.assertEqual(doc["registration_endpoint"], self.base + "/register")
        self.assertEqual(doc["revocation_endpoint"], self.base + "/revoke")
        self.assertEqual(doc["response_types_supported"], ["code"])
        self.assertEqual(doc["grant_types_supported"],
                         ["authorization_code", "refresh_token"])
        self.assertEqual(doc["code_challenge_methods_supported"], ["S256"])
        self.assertEqual(doc["scopes_supported"], ["health.read", "health.write"])

    def test_default_base_url(self):
        os.environ.pop("PUBLIC_BASE_URL", None)
        self.assertEqual(web.base_url(), "https://health.example.com")
        self.assertEqual(web.resource_url(), "https://health.example.com/mcp")
        doc = web.metadata("https://health.example.com/mcp")
        self.assertEqual(doc["resource"], "https://health.example.com/mcp")
        self.assertEqual(doc["authorization_servers"], ["https://health.example.com"])

    def test_derived_base_only_behind_trusted_proxy(self):
        os.environ.pop("PUBLIC_BASE_URL", None)
        os.environ["HCB_TRUST_PROXY"] = "1"
        os.environ["HCB_TRUSTED_PROXIES"] = "127.0.0.1,::1"
        proxy_headers = {"Host": "health.example.test", "X-Forwarded-Proto": "https"}

        class Fake:
            client_address = ("127.0.0.1", 1234)
            headers = dict(proxy_headers)

        class FakePublic:
            client_address = ("8.8.8.8", 1234)
            headers = dict(proxy_headers)

        class FakePrivate:
            client_address = ("192.0.2.5", 1234)
            headers = dict(proxy_headers)

        self.assertEqual(web.base_url(Fake()), "https://health.example.test")
        self.assertEqual(web.base_url(FakePublic()), "https://health.example.com")
        # 未列入 HCB_TRUSTED_PROXIES 的 RFC1918 peer 也不可信。
        self.assertEqual(web.base_url(FakePrivate()), "https://health.example.com")
        os.environ["HCB_TRUST_PROXY"] = "0"
        self.assertEqual(web.base_url(Fake()), "https://health.example.com")

    def test_client_ip_trusts_only_listed_proxy(self):
        os.environ["HCB_TRUST_PROXY"] = "1"
        os.environ["HCB_TRUSTED_PROXIES"] = "127.0.0.1"

        class Fake:
            client_address = ("127.0.0.1", 1234)
            headers = {"X-Real-IP": "203.0.113.9",
                       "X-Forwarded-For": "198.51.100.7"}

        class FakePrivate:
            client_address = ("192.0.2.5", 1234)
            headers = {"X-Real-IP": "203.0.113.9"}

        self.assertEqual(web.client_ip(Fake()), "203.0.113.9")
        self.assertEqual(web.client_ip(FakePrivate()), "192.0.2.5")
        # 只读 X-Real-IP；X-Forwarded-For 永不采信。
        self.assertNotEqual(web.client_ip(Fake()), "198.51.100.7")
        os.environ["HCB_TRUST_PROXY"] = "0"
        self.assertEqual(web.client_ip(Fake()), "127.0.0.1")

    def test_non_https_production_base_is_refused(self):
        os.environ["PUBLIC_BASE_URL"] = "http://health.evil.test"
        self.assertIsNone(web.canonical_base())
        status, text, _ = self.browser.get("/.well-known/oauth-protected-resource")
        self.assertEqual(status, 503, text)

    def test_metadata_url_does_not_swap_host(self):
        os.environ["PUBLIC_BASE_URL"] = "http://health.evil.test"
        self.assertIsNone(web.canonical_base())
        self.assertEqual(
            web.metadata_url(),
            "http://health.evil.test/.well-known/oauth-protected-resource/mcp")
        os.environ["PUBLIC_BASE_URL"] = "not-a-url"
        self.assertIsNone(web.metadata_url())
        os.environ["PUBLIC_BASE_URL"] = self.base
        status, text, headers = self.browser.post(
            "/mcp", json_body({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}),
            {"Content-Type": "application/json"})
        self.assertEqual(status, 401, text)
        self.assertIn("www-authenticate", headers)

    def test_bad_base_url_is_ignored_not_used(self):
        for bad in ("not-a-url", "https://user:pw@host.test", "https://host.test/path",
                    "javascript:alert(1)", "https://host.test?x=1"):
            os.environ["PUBLIC_BASE_URL"] = bad
            self.assertIsNone(web.base_url(), bad)


class ChallengeTests(HTTPTestCase):
    def test_mcp_without_token_returns_401_with_resource_metadata(self):
        status, text, headers = self.browser.post(
            "/mcp", json_body({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}),
            {"Content-Type": "application/json"})
        self.assertEqual(status, 401, text)
        self.assertEqual(
            headers.get("www-authenticate"),
            'Bearer resource_metadata="%s/.well-known/oauth-protected-resource/mcp"'
            % self.base)

    def test_mcp_bad_token_401(self):
        status, _, headers = self.mcp("not-a-real-token")
        self.assertEqual(status, 401)
        self.assertIn("www-authenticate", headers)

    def test_static_bearer_still_works(self):
        status, text, _ = self.mcp(STATIC_TOKEN)
        self.assertEqual(status, 200, text)
        doc = json.loads(text)
        self.assertTrue(doc["result"]["tools"])

    def test_health_stays_public(self):
        status, text, _ = self.browser.get("/health")
        self.assertEqual(status, 200)
        self.assertTrue(json.loads(text)["ok"])

    def test_revoked_all_still_requires_auth(self):
        with hdb.db(0) as con:
            self.assertTrue(auth.static_token_revoke(con, "phone"))
            con.execute("DELETE FROM oauth_clients")
        status, text, _ = self.browser.post(
            "/mcp", json_body({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}),
            {"Content-Type": "application/json"})
        self.assertEqual(status, 401, text)
        status, text, _ = self.browser.post(
            "/ingest", json_body({"items": []}), {"Content-Type": "application/json"})
        self.assertEqual(status, 401, text)

    def test_db_error_fails_closed(self):
        original = server.db

        def broken(owner=None):
            raise RuntimeError("db down")

        server.db = broken
        try:
            status, text, _ = self.browser.post(
                "/mcp", json_body({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}),
                {"Content-Type": "application/json"})
            self.assertEqual(status, 401, text)
            status, text, _ = self.browser.post(
                "/ingest", json_body({"items": []}), {"Content-Type": "application/json"})
            self.assertEqual(status, 401, text)
        finally:
            server.db = original

    def test_never_configured_stays_open(self):
        with hdb.db(0) as con:
            con.execute("DELETE FROM agent_tokens")
            con.execute("DELETE FROM oauth_clients")
            con.execute("DELETE FROM users")
            con.execute("DELETE FROM sync_state "
                        "WHERE key IN ('auth_configured','admin_password_hash')")
        self.assertFalse(server.auth_required())
        status, text, _ = self.browser.post(
            "/mcp", json_body({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}),
            {"Content-Type": "application/json"})
        self.assertEqual(status, 200, text)
        status, text, _ = self.browser.post(
            "/ingest", json_body({"items": []}), {"Content-Type": "application/json"})
        self.assertEqual(status, 401, text)

    def test_static_oa_prefix_falls_through_to_static(self):
        with hdb.db(0) as con:
            auth.static_token_create(con, "odd-static", token="oa_static-key")
        status, text, _ = self.mcp("oa_static-key")
        self.assertEqual(status, 200, text)


class OAuthFlowTests(HTTPTestCase):
    def test_consent_page_csp_allows_client_origin(self):
        """同意页 form-action 要含客户端来源，否则浏览器静默拦掉回跳（点了没反应）。"""
        client = self.register()
        params = self.authorize_params(client["client_id"])
        status, page, headers = self.login_and_authorize_page(params)
        self.assertEqual(status, 200, page)
        csp = headers.get("content-security-policy", "")
        self.assertIn("form-action 'self' " + self.base, csp)
        self.assertIn("/admin/assets/admin.css", page)

    def test_register_login_consent_token_and_tools_list(self):
        client, params, tokens = self.consent_and_token()
        self.assertRegex(tokens["access_token"], r"^oa_")
        self.assertRegex(tokens["refresh_token"], r"^or_")
        self.assertEqual(tokens["token_type"], "Bearer")
        self.assertEqual(tokens["scope"], "health.read health.write")
        status, text, _ = self.mcp(tokens["access_token"])
        self.assertEqual(status, 200, text)
        doc = json.loads(text)
        names = {tool["name"] for tool in doc["result"]["tools"]}
        self.assertIn("health_status", names)
        self.assertIn("query_heart_rate", names)

    def test_oauth_ingest_is_forbidden(self):
        _client, _params, tokens = self.consent_and_token()
        status, text, _ = self.mcp(
            tokens["access_token"], method="tools/call",
            params={"name": "ingest", "arguments": {"items": []}})
        self.assertEqual(status, 200, text)
        doc = json.loads(text)
        self.assertEqual(doc["error"]["code"], -32003)
        self.assertIn("static", doc["error"]["message"].lower())

    def test_ingest_endpoint_rejects_oauth(self):
        _client, _params, tokens = self.consent_and_token()
        status, text, _ = self.browser.post(
            "/ingest", json_body({"items": []}),
            {"Content-Type": "application/json",
             "Authorization": "Bearer " + tokens["access_token"]})
        self.assertEqual(status, 403, text)

    def test_authorize_without_session_redirects_to_login(self):
        client = self.register()
        params = self.authorize_params(client["client_id"])
        status, text, headers = self.browser.get(self.authorize_path(params))
        self.assertEqual(status, 302, text)
        location = headers["location"]
        self.assertTrue(location.startswith("/login?next="), location)
        nxt = urllib.parse.parse_qs(urllib.parse.urlsplit(location).query)["next"][0]
        self.assertTrue(nxt.startswith("/authorize?"))

    def test_refresh_token_rotation(self):
        client = self.register()
        params = self.authorize_params(client["client_id"], scopes=("health.read",))
        _status, page = self.login_and_authorize_page(params)[:2]
        csrf = hidden(page, "_csrf")
        request = hidden(page, "request")
        status, _, headers = self.browser.post("/authorize", {
            "_csrf": csrf, "request": request, "decision": "allow",
            "scope": "health.read"})
        self.assertEqual(status, 302)
        code = urllib.parse.parse_qs(
            urllib.parse.urlsplit(headers["location"]).query)["code"][0]
        status, text, _ = self.browser.post("/token", {
            "grant_type": "authorization_code", "code": code,
            "client_id": client["client_id"], "redirect_uri": self.redirect,
            "code_verifier": VERIFIER})
        self.assertEqual(status, 200, text)
        first = json.loads(text)
        status, text, _ = self.browser.post("/token", {
            "grant_type": "refresh_token",
            "refresh_token": first["refresh_token"],
            "client_id": client["client_id"]})
        self.assertEqual(status, 200, text)
        second = json.loads(text)
        self.assertNotEqual(first["refresh_token"], second["refresh_token"])
        status, text, _ = self.browser.post("/token", {
            "grant_type": "refresh_token",
            "refresh_token": first["refresh_token"],
            "client_id": client["client_id"]})
        self.assertEqual(status, 400)
        self.assertEqual(json.loads(text)["error"], "invalid_grant")
        # 重用旧刷新令牌后整族失效。
        status, _, _ = self.mcp(second["access_token"])
        self.assertEqual(status, 401)


class ConsentSecurityTests(HTTPTestCase):
    def test_consent_post_requires_csrf(self):
        client = self.register()
        params = self.authorize_params(client["client_id"])
        _status, page = self.login_and_authorize_page(params)[:2]
        request = hidden(page, "request")
        status, text, _ = self.browser.post("/authorize", {
            "request": request, "decision": "allow", "scope": "health.read"})
        self.assertEqual(status, 403, text)

    def test_tampered_signed_request_is_rejected(self):
        client = self.register()
        params = self.authorize_params(client["client_id"])
        _status, page = self.login_and_authorize_page(params)[:2]
        csrf = hidden(page, "_csrf")
        request = hidden(page, "request")
        tampered = request[:-2] + ("aa" if request[-2:] != "aa" else "bb")
        status, text, _ = self.browser.post("/authorize", {
            "_csrf": csrf, "request": tampered, "decision": "allow",
            "scope": "health.read"})
        self.assertEqual(status, 403, text)

    def test_user_ceiling_is_shown_and_post_cannot_widen(self):
        with hdb.db(0) as con:
            auth.create_user(con, "bob", "pw-bob-123456", scopes=["health.read"])
        client = self.register()
        params = self.authorize_params(client["client_id"], scopes=("health.read",))
        status, page = self.login_and_authorize_page(
            params, username="bob", password="pw-bob-123456")[:2]
        self.assertEqual(status, 200, page)
        self.assertIn('value="health.read"', page)
        self.assertNotIn('value="health.write"', page)
        self.assertIn("bob", page)
        csrf = hidden(page, "_csrf")
        request = hidden(page, "request")
        status, text, headers = self.browser.post("/authorize", {
            "_csrf": csrf, "request": request, "decision": "allow",
            "scope": "health.write"})
        self.assertEqual(status, 302, text)
        location = headers["location"]
        self.assertIn("error=invalid_scope", location)
        self.assertNotIn("code=", location)

    def test_deny_returns_access_denied(self):
        client = self.register()
        params = self.authorize_params(client["client_id"])
        _status, page = self.login_and_authorize_page(params)[:2]
        csrf = hidden(page, "_csrf")
        request = hidden(page, "request")
        status, _, headers = self.browser.post("/authorize", {
            "_csrf": csrf, "request": request, "decision": "deny"})
        self.assertEqual(status, 302)
        self.assertIn("error=access_denied", headers["location"])
        self.assertIn("state=st-1", headers["location"])

    def test_authorize_rejects_unregistered_redirect_without_redirecting(self):
        client = self.register()
        params = self.authorize_params(client["client_id"],
                                       redirect=self.base + "/not-registered")
        status, text, headers = self.browser.get(self.authorize_path(params))
        self.assertEqual(status, 400, text)
        self.assertNotIn("location", headers)


class LoginTests(HTTPTestCase):
    def test_admin_login_defaults_to_admin(self):
        status, text, headers = self.login("admin", ADMIN_PW)
        self.assertEqual(status, 303, text)
        self.assertEqual(headers.get("location"), "/admin")
        status, page, headers = self.browser.get("/admin")
        self.assertEqual(status, 200, page)
        self.assertEqual(headers.get("cache-control"), "no-store")
        self.assertEqual(headers.get("x-frame-options"), "DENY")
        self.assertIn("content-security-policy", headers)

    def test_wrong_password_same_message(self):
        status, text, _ = self.login("admin", "wrong-password")
        self.assertEqual(status, 401)
        self.assertIn("用户名或密码错误", text)
        status2, text2, _ = self.login("nobody", "wrong-password")
        self.assertEqual(status2, 401)
        self.assertIn("用户名或密码错误", text2)

    def test_user_login_and_admin_gate(self):
        with hdb.db(0) as con:
            auth.create_user(con, "bob", "pw-bob-123456")
        status, text, headers = self.login("bob", "pw-bob-123456")
        self.assertEqual(status, 303, text)
        self.assertEqual(headers.get("location"), "/account")
        status, page, _ = self.browser.get("/account")
        self.assertEqual(status, 200, page)
        self.assertIn("bob", page)
        status, _, headers = self.browser.get("/admin")
        self.assertEqual(status, 302)
        self.assertEqual(headers["location"], "/account")

    def test_unauthenticated_admin_redirects_to_login(self):
        status, _, headers = self.browser.get("/admin/users")
        self.assertEqual(status, 302)
        self.assertTrue(headers["location"].startswith("/login?next="))

    def test_logout_clears_session(self):
        self.login("admin", ADMIN_PW)
        status, _, headers = self.browser.get("/logout")
        self.assertEqual(status, 302)
        self.assertEqual(headers["location"], "/login")
        status, _, headers = self.browser.get("/admin")
        self.assertEqual(status, 302)

    def test_login_csrf_cookie_must_match(self):
        _status, _page, csrf = self.login_page()
        status, text, _ = self.browser.post("/login", {
            "username": "admin", "password": ADMIN_PW, "_csrf": "tampered"})
        self.assertEqual(status, 403, text)

    def test_login_post_without_cookie_is_rejected(self):
        status, text, _ = self.browser.post("/login", {
            "username": "admin", "password": ADMIN_PW, "_csrf": "anything"})
        self.assertEqual(status, 403, text)

    def test_logout_post_requires_csrf(self):
        self.login("admin", ADMIN_PW)
        status, page, _ = self.browser.get("/admin/legacy")
        self.assertEqual(status, 200, page)
        csrf = hidden(page, "_csrf")
        self.assertIsNotNone(csrf)
        status, text, _ = self.browser.post("/logout", {"_csrf": "bad"})
        self.assertEqual(status, 403, text)
        status, text, headers = self.browser.post("/logout", {"_csrf": csrf})
        self.assertEqual(status, 302, text)
        self.assertEqual(headers["location"], "/login")

    def test_trailing_slash_redirects(self):
        status, text, headers = self.browser.get("/admin/")
        self.assertEqual(status, 301, text)
        self.assertEqual(headers["location"], "/admin")
        status, text, headers = self.browser.get("/account/")
        self.assertEqual(status, 301, text)
        self.assertEqual(headers["location"], "/account")

    def test_login_rate_limit(self):
        _status, _page, csrf = self.login_page()
        codes = []
        for _ in range(6):
            status, _text, _ = self.browser.post("/login", {
                "username": "admin", "password": "wrong-password", "_csrf": csrf})
            codes.append(status)
        self.assertEqual(codes[:5], [401] * 5, codes)
        self.assertEqual(codes[5], 429, codes)

    def test_successful_login_resets_rate_limit(self):
        _status, _page, csrf = self.login_page()
        for _ in range(3):
            self.browser.post("/login", {
                "username": "admin", "password": "wrong-password", "_csrf": csrf})
        status, text, _ = self.browser.post("/login", {
            "username": "admin", "password": ADMIN_PW, "_csrf": csrf})
        self.assertEqual(status, 303, text)
        self.browser.cookies.pop(auth.SESSION_COOKIE, None)
        status, _page, csrf = self.login_page()
        for _ in range(5):
            status, _text, _ = self.browser.post("/login", {
                "username": "admin", "password": "wrong-password", "_csrf": csrf})
            self.assertEqual(status, 401)

    def test_totp_two_step_login(self):
        secret = totp.generate_secret()
        with hdb.db(0) as con:
            auth.admin_totp_enable(
                con, secret, confirm_code=totp.code(secret, at=time.time() - 60),
                now=time.time() - 60)
        status, _page, csrf = self.login_page()
        status, text, headers = self.browser.post("/login", {
            "username": "admin", "password": ADMIN_PW, "_csrf": csrf})
        self.assertEqual(status, 303, text)
        self.assertTrue(headers["location"].startswith("/login/otp"), headers)
        self.assertNotIn(auth.SESSION_COOKIE, self.browser.cookies)
        self.assertIn(auth.PREAUTH_COOKIE, self.browser.cookies)
        status, page, _ = self.browser.get("/login/otp")
        self.assertEqual(status, 200, page)
        otp_csrf = hidden(page, "_csrf")
        status, text, headers = self.browser.post("/login/otp", {
            "_csrf": otp_csrf, "code": totp.code(secret)})
        self.assertEqual(status, 303, text)
        self.assertEqual(headers["location"], "/admin")
        self.assertIn(auth.SESSION_COOKIE, self.browser.cookies)
        status, page, _ = self.browser.get("/admin")
        self.assertEqual(status, 200, page)

    def test_otp_post_without_login_cookie_is_rejected(self):
        secret = totp.generate_secret()
        with hdb.db(0) as con:
            auth.admin_totp_enable(
                con, secret, confirm_code=totp.code(secret, at=time.time() - 60),
                now=time.time() - 60)
        _status, _page, csrf = self.login_page()
        self.browser.post("/login", {
            "username": "admin", "password": ADMIN_PW, "_csrf": csrf})
        _status, page, _ = self.browser.get("/login/otp")
        self.browser.cookies.pop(web.LOGIN_COOKIE, None)
        status, text, _ = self.browser.post("/login/otp", {
            "_csrf": hidden(page, "_csrf"), "code": totp.code(secret)})
        self.assertEqual(status, 403, text)
        self.assertNotIn(auth.SESSION_COOKIE, self.browser.cookies)

    def test_totp_wrong_code_no_session(self):
        secret = totp.generate_secret()
        with hdb.db(0) as con:
            auth.admin_totp_enable(
                con, secret, confirm_code=totp.code(secret, at=time.time() - 60),
                now=time.time() - 60)
        _status, _page, csrf = self.login_page()
        self.browser.post("/login", {
            "username": "admin", "password": ADMIN_PW, "_csrf": csrf})
        _status, page, _ = self.browser.get("/login/otp")
        otp_csrf = hidden(page, "_csrf")
        status, text, _ = self.browser.post("/login/otp", {
            "_csrf": otp_csrf, "code": "000000"})
        self.assertEqual(status, 401, text)
        self.assertNotIn(auth.SESSION_COOKIE, self.browser.cookies)


class LimitsTests(HTTPTestCase):
    def test_oauth_body_limit_is_16kb(self):
        payload = b"{" + b" " * (17 * 1024) + b"}"
        status, text, _ = self.browser.post(
            "/register", payload, {"Content-Type": "application/json"})
        self.assertEqual(status, 413, text)

    def test_mcp_body_limit_is_larger(self):
        msg = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}).encode()
        payload = msg + b" " * (40 * 1024)
        self.assertGreater(len(payload), 16 * 1024)
        status, text, _ = self.browser.post(
            "/mcp", payload,
            {"Content-Type": "application/json",
             "Authorization": "Bearer " + STATIC_TOKEN})
        self.assertEqual(status, 200, text)

    def test_rate_limit_function(self):
        ratelimit.clear()
        self.assertTrue(ratelimit.take("k", 2, 60))
        self.assertTrue(ratelimit.take("k", 2, 60))
        self.assertFalse(ratelimit.take("k", 2, 60))
        self.assertEqual(ratelimit.remaining("k", 2, 60), 0)
        ratelimit.reset("k")
        self.assertTrue(ratelimit.take("k", 2, 60))

    def test_rate_limit_bucket_map_is_hard_capped(self):
        ratelimit.clear()
        cap = ratelimit.capacity()
        for index in range(cap + 50):
            self.assertTrue(ratelimit.take(f"cap-{index}", 1, 60))
        self.assertLessEqual(ratelimit.bucket_count(), cap)


class IngestOwnerTests(HTTPTestCase):
    def test_static_ingest_writes_owner_partition(self):
        with hdb.db(0) as con:
            user = auth.create_user(con, "carol", "pw-carol-123456", data_mode="own")
            auth.static_token_create(con, "carol-phone", token="carol-static",
                                     owner_user_id=user["id"])
        ts = 1_700_000_001
        status, text, _ = self.browser.post(
            "/ingest", json_body({"items": [{"type": "heart_rate", "ts": ts, "bpm": 66}]}),
            {"Content-Type": "application/json",
             "Authorization": "Bearer carol-static"})
        self.assertEqual(status, 200, text)
        with hdb.db(user["id"]) as con:
            row = con.execute("SELECT bpm FROM heart_rate WHERE ts=?", (ts,)).fetchone()
            self.assertIsNotNone(row)
            self.assertEqual(row["bpm"], 66)
        with hdb.db(0) as con:
            self.assertIsNone(
                con.execute("SELECT 1 FROM heart_rate WHERE ts=?", (ts,)).fetchone())

    def test_shared_user_static_key_writes_owner_zero(self):
        with hdb.db(0) as con:
            user = auth.create_user(con, "sharon", "pw-sharon-123456")
            auth.static_token_create(con, "sharon-phone", token="sharon-static",
                                     owner_user_id=user["id"])
        ts = 1_700_000_003
        status, text, _ = self.browser.post(
            "/ingest", json_body({"items": [{"type": "heart_rate", "ts": ts, "bpm": 70}]}),
            {"Content-Type": "application/json",
             "Authorization": "Bearer sharon-static"})
        self.assertEqual(status, 200, text)
        with hdb.db(0) as con:
            self.assertIsNotNone(
                con.execute("SELECT 1 FROM heart_rate WHERE ts=?", (ts,)).fetchone())
        with hdb.db(user["id"]) as con:
            self.assertIsNone(
                con.execute("SELECT 1 FROM heart_rate WHERE ts=?", (ts,)).fetchone())

    def test_static_key_owner_and_data_mode(self):
        with hdb.db(0) as con:
            shared = auth.create_user(con, "sam", "pw-sam-123456")
            own = auth.create_user(con, "olga", "pw-olga-123456", data_mode="own")
            auth.static_token_create(con, "sam-key", token="sam-static",
                                     owner_user_id=shared["id"])
            auth.static_token_create(con, "olga-key", token="olga-static",
                                     owner_user_id=own["id"])
        shared_principal = server.resolve_principal("Bearer sam-static")
        self.assertEqual(shared_principal["owner"], 0)
        self.assertEqual(shared_principal["user_id"], shared["id"])
        self.assertEqual(shared_principal["data_mode"], "shared")
        self.assertFalse(shared_principal["is_admin"])
        own_principal = server.resolve_principal("Bearer olga-static")
        self.assertEqual(own_principal["owner"], own["id"])
        self.assertEqual(own_principal["user_id"], own["id"])
        self.assertEqual(own_principal["data_mode"], "own")

    def test_static_ingest_owner_zero(self):
        ts = 1_700_000_002
        status, text, _ = self.browser.post(
            "/ingest", json_body({"items": [{"type": "steps", "ts": ts, "steps": 10}]}),
            {"Content-Type": "application/json",
             "Authorization": "Bearer " + STATIC_TOKEN})
        self.assertEqual(status, 200, text)
        with hdb.db(0) as con:
            self.assertIsNotNone(con.execute("SELECT 1 FROM steps WHERE ts=?", (ts,)).fetchone())

    def test_ingest_requires_auth(self):
        status, text, _ = self.browser.post(
            "/ingest", json_body({"items": []}), {"Content-Type": "application/json"})
        self.assertEqual(status, 401, text)

    def test_ingest_denied_by_empty_tool_policy(self):
        with hdb.db(0) as con:
            auth.static_token_create(
                con, "no-ingest", token="no-ingest-key", tool_policy_json=[])
        ts = 1_700_000_011
        status, text, _ = self.browser.post(
            "/ingest", json_body({"items": [{"type": "steps", "ts": ts, "steps": 1}]}),
            {"Content-Type": "application/json",
             "Authorization": "Bearer no-ingest-key"})
        self.assertEqual(status, 403, text)
        self.assertEqual(json.loads(text)["reason"], "tool_denied")
        with hdb.db(0) as con:
            self.assertIsNone(
                con.execute("SELECT 1 FROM steps WHERE ts=?", (ts,)).fetchone())

    def test_ownerless_key_closed_window_denies_ingest(self):
        with hdb.db(0) as con:
            # 无 owner 的 key 不是人类管理员；它自己的 window_json 必须生效。
            auth.static_token_create(
                con, "closed-window", token="closed-window-key",
                window_json={"tz": "UTC",
                             "from": "2020-01-01T00:00:00+00:00",
                             "until": "2020-01-02T00:00:00+00:00"})
        ts = 1_700_000_012
        status, text, _ = self.browser.post(
            "/ingest", json_body({"items": [{"type": "steps", "ts": ts, "steps": 1}]}),
            {"Content-Type": "application/json",
             "Authorization": "Bearer closed-window-key"})
        self.assertEqual(status, 403, text)
        self.assertEqual(json.loads(text)["reason"], "window_closed")
        with hdb.db(0) as con:
            self.assertIsNone(
                con.execute("SELECT 1 FROM steps WHERE ts=?", (ts,)).fetchone())

    def test_ownerless_key_open_window_allows_ingest(self):
        with hdb.db(0) as con:
            auth.static_token_create(
                con, "open-window", token="open-window-key",
                window_json={"tz": "UTC", "start": "00:00", "end": "23:59"})
        ts = 1_700_000_013
        status, text, _ = self.browser.post(
            "/ingest", json_body({"items": [{"type": "steps", "ts": ts, "steps": 2}]}),
            {"Content-Type": "application/json",
             "Authorization": "Bearer open-window-key"})
        self.assertEqual(status, 200, text)
        with hdb.db(0) as con:
            self.assertIsNotNone(
                con.execute("SELECT 1 FROM steps WHERE ts=?", (ts,)).fetchone())

    def test_ingest_denied_by_owner_user_policy(self):
        with hdb.db(0) as con:
            user = auth.create_user(
                con, "olga", "pw-olga-123456", data_mode="own",
                tool_policy_json=[])
            auth.static_token_create(
                con, "olga-key", token="olga-policy-key",
                owner_user_id=user["id"])
        status, text, _ = self.browser.post(
            "/ingest", json_body({"items": []}),
            {"Content-Type": "application/json",
             "Authorization": "Bearer olga-policy-key"})
        self.assertEqual(status, 403, text)
        self.assertEqual(json.loads(text)["reason"], "tool_denied")


class BootstrapTests(HTTPTestCase):
    def test_weak_admin_password_does_not_break_mcp(self):
        with hdb.db(0) as con:
            con.execute("DELETE FROM sync_state WHERE key='admin_password_hash'")
        os.environ["HCB_ADMIN_PASSWORD"] = "short"
        server.startup()
        status, text, _ = self.mcp(STATIC_TOKEN)
        self.assertEqual(status, 200, text)
        status, text, _ = self.browser.get("/login")
        self.assertEqual(status, 503, text)
        status, text, _ = self.browser.get("/admin")
        self.assertEqual(status, 503, text)

    def test_revoked_env_token_does_not_come_back(self):
        with hdb.db(0) as con:
            con.execute("DELETE FROM agent_tokens")
            con.execute("DELETE FROM sync_state WHERE key='tokens_imported'")
        os.environ["HEALTH_MCP_TOKENS"] = "env-agent:env-token-value"
        server.AGENT_TOKENS = [("env-agent", "env-token-value")]
        server.AUTH_ON = True
        server.startup()
        status, text, _ = self.mcp("env-token-value")
        self.assertEqual(status, 200, text)
        with hdb.db(0) as con:
            self.assertTrue(auth.static_token_revoke(con, "env-agent"))
        status, text, _ = self.mcp("env-token-value")
        self.assertEqual(status, 401, text)

    def test_startup_imports_env_tokens_once(self):
        with hdb.db(0) as con:
            con.execute("DELETE FROM agent_tokens")
            con.execute("DELETE FROM sync_state WHERE key='tokens_imported'")
        os.environ["HEALTH_MCP_TOKENS"] = "env-agent:env-token-value"
        server.startup()
        with hdb.db(0) as con:
            row = con.execute("SELECT label FROM agent_tokens WHERE label='env-agent'").fetchone()
            self.assertIsNotNone(row)
            self.assertEqual(auth.settings_get(con, "tokens_imported"), "1")
        os.environ.pop("HEALTH_MCP_TOKENS", None)
        server.startup()
        with hdb.db(0) as con:
            rows = con.execute("SELECT label FROM agent_tokens WHERE label='env-agent'").fetchall()
            self.assertEqual(len(rows), 1)


if __name__ == "__main__":
    unittest.main()
