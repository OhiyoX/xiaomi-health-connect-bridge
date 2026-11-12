"""Task 3: OAuth 授权服务器核心的测试。只用标准库。

覆盖：动态注册（redirect 精确匹配、https/回环、公开/机密、200 上限）、
authorize 参数校验、PKCE(S256)、授权码 5 分钟一次性、client/redirect/resource
绑定、访问令牌 1 小时 / 刷新令牌 30 天、刷新轮换与重用吊销 family、
scope 上限与收窄、令牌只存 SHA-256、损坏的 policy/window fail-closed。
"""
from __future__ import annotations

import hashlib
import json
import os
import shutil
import sys
import tempfile
import unittest

NAS_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if NAS_DIR not in sys.path:
    sys.path.insert(0, NAS_DIR)

from hcb import auth  # noqa: E402
from hcb import context  # noqa: E402
from hcb import db as hdb  # noqa: E402
from hcb import oauth  # noqa: E402

CANON = "https://health.example.com/mcp"
REDIRECT = "https://app.example/callback"
VERIFIER = "a" * 43 + "B7._~-0123456789"  # 43–128 个 unreserved 字符
CHALLENGE = oauth.pkce_challenge(VERIFIER)
NOW = 1_700_000_000


def _sha(raw: str) -> str:
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


class OAuthTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="hcb-oauth-")
        self.health_db = os.path.join(self.tmp, "health.db")
        self._old_env = os.environ.get("HEALTH_DB")
        os.environ["HEALTH_DB"] = self.health_db
        context.set_owner(0)

    def tearDown(self):
        if self._old_env is None:
            os.environ.pop("HEALTH_DB", None)
        else:
            os.environ["HEALTH_DB"] = self._old_env
        context.set_owner(0)
        shutil.rmtree(self.tmp, ignore_errors=True)

    # ---------------------------------------------------------- helpers
    def client(self, con, name="App", redirects=(REDIRECT,), method="none", **kw):
        data = {
            "client_name": name,
            "redirect_uris": list(redirects),
            "token_endpoint_auth_method": method,
        }
        data.update(kw)
        return oauth.register_client(con, data, now=NOW)

    def user(self, con, scopes=("health.read", "health.write"), **kw):
        username = kw.pop("username", "alice")
        existing = auth.by_username(con, username)
        if existing is not None:
            return existing
        return auth.create_user(con, username, "pw-alice-1234",
                                scopes=list(scopes), now=NOW, **kw)

    def authorize(self, con, client=None, user=None, scopes=None, redirect=REDIRECT,
                  challenge=CHALLENGE, resource=CANON, state="st-1"):
        c = client or self.client(con)
        u = user or self.user(con)
        params = {
            "response_type": "code",
            "client_id": c["client_id"],
            "redirect_uri": redirect,
            "code_challenge": challenge,
            "code_challenge_method": "S256",
            "state": state,
            "resource": resource,
        }
        if scopes is not None:
            params["scope"] = " ".join(scopes)
        v = oauth.validate_authorize(con, params, CANON, allowed_scopes=u["scopes"])
        code = oauth.issue_code(con, {
            "client_id": c["client_id"],
            "redirect_uri": v["redirect_uri"],
            "code_challenge": v["code_challenge"],
            "scopes": v["scopes"],
            "resource": v["resource"],
            "session_sid": "session-sid-1",
            "user_id": u["id"],
            "username": u["username"],
            "user_revision": u["revision"],
        }, canonical_resource=CANON, now=NOW)
        return c, u, code, v

    def exchange(self, con, code, client, verifier=VERIFIER, redirect=REDIRECT,
                 resource=None, client_secret=None, now=NOW):
        data = {
            "code": code,
            "client_id": client["client_id"],
            "redirect_uri": redirect,
            "code_verifier": verifier,
        }
        if resource is not None:
            data["resource"] = resource
        if client_secret is not None:
            data["client_secret"] = client_secret
        return oauth.exchange_code(con, data, canonical_resource=CANON, now=now)


class ClientRegistrationTests(OAuthTestCase):
    def test_public_client_registration_and_lookup(self):
        with hdb.db(0) as con:
            c = self.client(con, name="  Claude  ")
            self.assertTrue(c["client_id"])
            self.assertEqual(c["client_name"], "Claude")
            self.assertEqual(c["token_endpoint_auth_method"], "none")
            self.assertEqual(c["redirect_uris"], [REDIRECT])
            self.assertNotIn("client_secret", c)
            self.assertFalse(c["confidential"])

            got = oauth.get_client(con, c["client_id"])
            self.assertEqual(got["client_id"], c["client_id"])
            self.assertNotIn("client_secret_hash", got)
            self.assertFalse(got["revoked"])
            self.assertIsNone(oauth.get_client(con, "nope"))
            self.assertIsNone(oauth.get_client(con, None))

            listed = oauth.list_clients(con)
            self.assertEqual([x["client_id"] for x in listed], [c["client_id"]])

    def test_confidential_client_secret_only_returned_once(self):
        with hdb.db(0) as con:
            c = self.client(con, method="client_secret_post")
            secret = c["client_secret"]
            self.assertTrue(secret)
            self.assertTrue(c["confidential"])
            self.assertNotIn("client_secret_hash", c)

            row = con.execute("SELECT * FROM oauth_clients WHERE client_id=?",
                              (c["client_id"],)).fetchone()
            self.assertEqual(row["client_secret_hash"], _sha(secret))
            self.assertNotIn(secret, json.dumps(dict(row)))
            self.assertNotIn("client_secret", oauth.get_client(con, c["client_id"]))

    def test_redirect_uri_rules(self):
        with hdb.db(0) as con:
            good = [
                "https://app.example/cb",
                "https://app.example:8443/cb?x=1",
                "http://localhost:6274/callback",
                "http://127.0.0.1:1234/cb",
                "http://[::1]:8080/cb",
            ]
            c = self.client(con, redirects=good)
            self.assertEqual(c["redirect_uris"], good)

            bad = [
                "http://app.example/cb",
                "ftp://app.example/cb",
                "/relative/cb",
                "https://app.example/cb#fragment",
                "https://app.example/cb*",
                "https://user:pw@app.example/cb",
                "https://",
                "",
            ]
            for uri in bad:
                with self.assertRaises(oauth.OAuthError, msg=uri) as ctx:
                    self.client(con, name="Bad", redirects=[uri])
                self.assertEqual(ctx.exception.error, "invalid_redirect_uri")

            with self.assertRaises(oauth.OAuthError):
                self.client(con, name="Empty", redirects=[])

    def test_redirect_uris_deduped_and_capped(self):
        with hdb.db(0) as con:
            c = self.client(con, redirects=[REDIRECT, REDIRECT, "https://b.example/cb"])
            self.assertEqual(c["redirect_uris"], [REDIRECT, "https://b.example/cb"])
            with self.assertRaises(oauth.OAuthError):
                self.client(con, name="TooMany", redirects=[
                    f"https://app.example/{i}" for i in range(6)])

    def test_active_client_cap_200(self):
        with hdb.db(0) as con:
            first = None
            for i in range(200):
                c = self.client(con, name=f"app-{i}", redirects=[f"https://app.example/{i}"])
                first = first or c
            with self.assertRaises(oauth.OAuthError) as ctx:
                self.client(con, name="one-too-many", redirects=["https://app.example/x"])
            self.assertEqual(ctx.exception.error, "invalid_client_metadata")
            self.assertTrue(oauth.revoke_client(con, first["client_id"], now=NOW))
            self.client(con, name="after-revoke", redirects=["https://app.example/y"])

    def test_bad_metadata(self):
        with hdb.db(0) as con:
            for data in (
                {"client_name": "", "redirect_uris": [REDIRECT]},
                {"client_name": "x" * 81, "redirect_uris": [REDIRECT]},
                {"client_name": "A", "redirect_uris": [REDIRECT],
                 "token_endpoint_auth_method": "client_secret_basic"},
                {"client_name": "A", "redirect_uris": REDIRECT},
                {"client_name": "A", "redirect_uris": [REDIRECT],
                 "tool_policy_json": '{"not": "a list"}'},
                {"client_name": "A", "redirect_uris": [REDIRECT],
                 "window_json": '["not", "a dict"]'},
            ):
                with self.assertRaises(oauth.OAuthError, msg=data):
                    oauth.register_client(con, data, now=NOW)

    def test_revoke_client(self):
        with hdb.db(0) as con:
            c, _, code, _ = self.authorize(con)
            self.assertTrue(oauth.revoke_client(con, c["client_id"], now=NOW))
            self.assertTrue(oauth.get_client(con, c["client_id"])["revoked"])
            self.assertFalse(oauth.revoke_client(con, c["client_id"], now=NOW))
            self.assertEqual(con.execute(
                "SELECT COUNT(*) FROM oauth_codes WHERE client_id=?",
                (c["client_id"],)).fetchone()[0], 0)
            with self.assertRaises(oauth.OAuthError) as ctx:
                oauth.validate_authorize(con, {
                    "response_type": "code", "client_id": c["client_id"],
                    "redirect_uri": REDIRECT, "code_challenge": CHALLENGE,
                    "code_challenge_method": "S256", "resource": CANON,
                }, CANON)
            self.assertEqual(ctx.exception.error, "invalid_client")
            with self.assertRaises(oauth.OAuthError):
                self.exchange(con, code, c)


class AuthorizeValidationTests(OAuthTestCase):
    def test_valid_request_and_scope(self):
        with hdb.db(0) as con:
            c = self.client(con)
            u = self.user(con)
            v = oauth.validate_authorize(con, {
                "response_type": "code",
                "client_id": c["client_id"],
                "redirect_uri": REDIRECT,
                "code_challenge": CHALLENGE,
                "code_challenge_method": "S256",
                "state": "abc",
                "scope": "health.read",
                "resource": CANON,
            }, CANON, allowed_scopes=u["scopes"])
            self.assertEqual(v["redirect_uri"], REDIRECT)
            self.assertEqual(v["scopes"], ["health.read"])
            self.assertEqual(v["scope"], "health.read")
            self.assertEqual(v["resource"], CANON)
            self.assertEqual(v["state"], "abc")
            self.assertEqual(v["client_id"], c["client_id"])
            self.assertEqual(v["code_challenge"], CHALLENGE)

    def test_scope_defaults_to_user_ceiling_and_rejects_expansion(self):
        with hdb.db(0) as con:
            c = self.client(con)
            u = self.user(con, scopes=["health.read", "health.write"])
            params = {
                "response_type": "code", "client_id": c["client_id"],
                "redirect_uri": REDIRECT, "code_challenge": CHALLENGE,
                "code_challenge_method": "S256", "resource": CANON, "state": "s",
            }
            v = oauth.validate_authorize(con, params, CANON, allowed_scopes=u["scopes"])
            self.assertEqual(v["scopes"], ["health.read", "health.write"])

            params["scope"] = "health.read health.write"
            with self.assertRaises(oauth.OAuthError) as ctx:
                oauth.validate_authorize(con, params, CANON, allowed_scopes=["health.read"])
            self.assertEqual(ctx.exception.error, "invalid_scope")

    def test_scope_over_ceiling_is_invalid_scope(self):
        with hdb.db(0) as con:
            c = self.client(con)
            u = self.user(con, scopes=["health.read"])
            with self.assertRaises(oauth.OAuthError) as ctx:
                oauth.validate_authorize(con, {
                    "response_type": "code", "client_id": c["client_id"],
                    "redirect_uri": REDIRECT, "code_challenge": CHALLENGE,
                    "code_challenge_method": "S256", "scope": "health.write",
                    "resource": CANON, "state": "s",
                }, CANON, allowed_scopes=u["scopes"])
            self.assertEqual(ctx.exception.error, "invalid_scope")
            # The redirect is already trusted, so the HTTP layer can send the error back.
            self.assertEqual(ctx.exception.redirect_uri, REDIRECT)
            self.assertEqual(ctx.exception.state, "s")

    def test_unknown_scope_is_invalid_scope(self):
        with hdb.db(0) as con:
            c = self.client(con)
            with self.assertRaises(oauth.OAuthError) as ctx:
                oauth.validate_authorize(con, {
                    "response_type": "code", "client_id": c["client_id"],
                    "redirect_uri": REDIRECT, "code_challenge": CHALLENGE,
                    "code_challenge_method": "S256", "scope": "health.admin",
                    "resource": CANON,
                }, CANON)
            self.assertEqual(ctx.exception.error, "invalid_scope")

    def test_code_challenge_rules(self):
        with hdb.db(0) as con:
            c = self.client(con)
            base = {
                "response_type": "code", "client_id": c["client_id"],
                "redirect_uri": REDIRECT, "resource": CANON,
                "code_challenge_method": "S256",
            }
            for challenge, method, expected in (
                (None, "S256", "invalid_request"),
                ("", "S256", "invalid_request"),
                ("short", "S256", "invalid_request"),
                (CHALLENGE, "plain", "invalid_request"),
                (CHALLENGE, None, "invalid_request"),
            ):
                p = dict(base, code_challenge=challenge)
                if method is not None:
                    p["code_challenge_method"] = method
                else:
                    p.pop("code_challenge_method")
                with self.assertRaises(oauth.OAuthError, msg=(challenge, method)) as ctx:
                    oauth.validate_authorize(con, p, CANON)
                self.assertEqual(ctx.exception.error, expected)

    def test_resource_binding(self):
        with hdb.db(0) as con:
            c = self.client(con)
            base = {
                "response_type": "code", "client_id": c["client_id"],
                "redirect_uri": REDIRECT, "code_challenge": CHALLENGE,
                "code_challenge_method": "S256",
            }
            with self.assertRaises(oauth.OAuthError) as ctx:
                oauth.validate_authorize(con, dict(base, resource="https://evil.example/mcp"), CANON)
            self.assertEqual(ctx.exception.error, "invalid_request")
            # Omitted resource means the canonical resource.
            self.assertEqual(
                oauth.validate_authorize(con, base, CANON)["resource"], CANON)
            with self.assertRaises(oauth.OAuthError):
                oauth.validate_authorize(con, base, None)

    def test_unknown_client_and_bad_redirect(self):
        with hdb.db(0) as con:
            with self.assertRaises(oauth.OAuthError) as ctx:
                oauth.validate_authorize(con, {
                    "response_type": "code", "client_id": "nope",
                    "redirect_uri": REDIRECT, "code_challenge": CHALLENGE,
                    "code_challenge_method": "S256", "resource": CANON,
                }, CANON)
            self.assertEqual(ctx.exception.error, "invalid_client")
            self.assertIsNone(ctx.exception.redirect_uri)

            c = self.client(con)
            with self.assertRaises(oauth.OAuthError) as ctx:
                oauth.validate_authorize(con, {
                    "response_type": "code", "client_id": c["client_id"],
                    "redirect_uri": "https://evil.example/cb", "code_challenge": CHALLENGE,
                    "code_challenge_method": "S256", "resource": CANON,
                }, CANON)
            self.assertEqual(ctx.exception.error, "invalid_request")
            self.assertIsNone(ctx.exception.redirect_uri)

    def test_response_type(self):
        with hdb.db(0) as con:
            c = self.client(con)
            with self.assertRaises(oauth.OAuthError) as ctx:
                oauth.validate_authorize(con, {
                    "response_type": "token", "client_id": c["client_id"],
                    "redirect_uri": REDIRECT, "code_challenge": CHALLENGE,
                    "code_challenge_method": "S256", "resource": CANON,
                }, CANON)
            self.assertEqual(ctx.exception.error, "unsupported_response_type")


class CodeExchangeTests(OAuthTestCase):
    def test_issue_code_stores_only_hash(self):
        with hdb.db(0) as con:
            c, u, code, v = self.authorize(con)
            self.assertTrue(code.startswith("oc_"))
            row = con.execute("SELECT * FROM oauth_codes WHERE code_hash=?",
                              (_sha(code),)).fetchone()
            self.assertIsNotNone(row)
            self.assertEqual(row["client_id"], c["client_id"])
            self.assertEqual(row["redirect_uri"], REDIRECT)
            self.assertEqual(json.loads(row["scopes_json"]), ["health.read", "health.write"])
            self.assertEqual(row["resource"], CANON)
            self.assertEqual(row["user_id"], u["id"])
            self.assertEqual(row["user_revision"], u["revision"])
            self.assertEqual(row["expires_at"], NOW + oauth.CODE_TTL)
            self.assertIsNone(row["consumed_at"])
            self.assertEqual(row["session_sid"], _sha("session-sid-1"))
            self.assertNotIn(code, json.dumps(dict(row)))

    def test_exchange_returns_token_pair_and_stores_hashes(self):
        with hdb.db(0) as con:
            c, u, code, _ = self.authorize(con)
            res = self.exchange(con, code, c)
            self.assertTrue(res["access_token"].startswith("oa_"))
            self.assertTrue(res["refresh_token"].startswith("or_"))
            self.assertEqual(res["token_type"], "Bearer")
            self.assertEqual(res["expires_in"], 3600)
            self.assertEqual(res["scope"], "health.read health.write")
            self.assertEqual(set(res), {"access_token", "token_type", "expires_in",
                                        "refresh_token", "scope"})

            rows = con.execute("SELECT * FROM oauth_tokens ORDER BY kind").fetchall()
            self.assertEqual(len(rows), 2)
            by_kind = {r["kind"]: r for r in rows}
            access, refresh = by_kind["access"], by_kind["refresh"]
            self.assertEqual(access["token_hash"], _sha(res["access_token"]))
            self.assertEqual(refresh["token_hash"], _sha(res["refresh_token"]))
            self.assertEqual(access["expires_at"], NOW + oauth.ACCESS_TTL)
            self.assertEqual(refresh["expires_at"], NOW + oauth.REFRESH_TTL)
            self.assertEqual(access["prefix"], res["access_token"][:12])
            self.assertEqual(refresh["prefix"], res["refresh_token"][:12])
            self.assertEqual(access["family_id"], refresh["family_id"])
            self.assertEqual(access["user_id"], u["id"])
            self.assertEqual(access["resource"], CANON)
            self.assertEqual(json.loads(access["scopes_json"]), ["health.read", "health.write"])
            self.assertIsNone(access["tool_policy_json"])
            self.assertIsNone(access["window_json"])

            dump = json.dumps([dict(r) for r in rows])
            self.assertNotIn(res["access_token"], dump)
            self.assertNotIn(res["refresh_token"], dump)

            code_row = con.execute("SELECT * FROM oauth_codes WHERE code_hash=?",
                                   (_sha(code),)).fetchone()
            self.assertEqual(code_row["consumed_at"], NOW)

    def test_code_is_one_time(self):
        with hdb.db(0) as con:
            c, _, code, _ = self.authorize(con)
            self.exchange(con, code, c)
            with self.assertRaises(oauth.OAuthError) as ctx:
                self.exchange(con, code, c)
            self.assertEqual(ctx.exception.error, "invalid_grant")

    def test_wrong_verifier_does_not_consume_code_or_leak(self):
        with hdb.db(0) as con:
            c, _, code, _ = self.authorize(con)
            with self.assertRaises(oauth.OAuthError) as ctx:
                self.exchange(con, code, c, verifier="b" * 43)
            self.assertEqual(ctx.exception.error, "invalid_grant")
            self.assertEqual(con.execute("SELECT COUNT(*) FROM oauth_tokens").fetchone()[0], 0)
            self.assertIsNone(con.execute(
                "SELECT consumed_at FROM oauth_codes WHERE code_hash=?",
                (_sha(code),)).fetchone()[0])
            # A legitimate retry still works.
            self.exchange(con, code, c)

    def test_code_expires_after_five_minutes(self):
        with hdb.db(0) as con:
            c, _, code, _ = self.authorize(con)
            with self.assertRaises(oauth.OAuthError) as ctx:
                self.exchange(con, code, c, now=NOW + oauth.CODE_TTL)
            self.assertEqual(ctx.exception.error, "invalid_grant")
            self.assertEqual(con.execute("SELECT COUNT(*) FROM oauth_tokens").fetchone()[0], 0)
            self.exchange(con, code, c, now=NOW + oauth.CODE_TTL - 1)

    def test_code_bound_to_client_redirect_resource(self):
        with hdb.db(0) as con:
            c, _, code, _ = self.authorize(con)
            other = self.client(con, name="Other", redirects=["https://other.example/cb"])
            for kwargs in (
                {"client": other},
                {"client": c, "redirect": "https://other.example/cb"},
                {"client": c, "resource": "https://other.example/mcp"},
            ):
                with self.assertRaises(oauth.OAuthError, msg=kwargs) as ctx:
                    if "client" in kwargs:
                        cc = kwargs.pop("client")
                        self.exchange(con, code, cc, **kwargs)
                    else:
                        self.exchange(con, code, c, **kwargs)
                self.assertEqual(ctx.exception.error, "invalid_grant")
                self.assertEqual(con.execute(
                    "SELECT COUNT(*) FROM oauth_tokens").fetchone()[0], 0)
            # Omitted resource uses the code's stored resource.
            self.exchange(con, code, c)

    def test_exchange_rereads_user_ceiling(self):
        with hdb.db(0) as con:
            c, u, code, _ = self.authorize(con)
            auth.update_user(con, u["id"], enabled=False, now=NOW)
            with self.assertRaises(oauth.OAuthError) as ctx:
                self.exchange(con, code, c)
            self.assertEqual(ctx.exception.error, "invalid_grant")
            self.assertEqual(con.execute("SELECT COUNT(*) FROM oauth_tokens").fetchone()[0], 0)

    def test_exchange_rejects_narrowed_user_scope(self):
        with hdb.db(0) as con:
            c, u, code, _ = self.authorize(con)
            auth.update_user(con, u["id"], scopes=["health.read"], now=NOW)
            with self.assertRaises(oauth.OAuthError) as ctx:
                self.exchange(con, code, c)
            self.assertEqual(ctx.exception.error, "invalid_grant")
            self.assertEqual(con.execute("SELECT COUNT(*) FROM oauth_tokens").fetchone()[0], 0)

    def test_confidential_client_authentication(self):
        with hdb.db(0) as con:
            c, _, code, _ = self.authorize(con, client=self.client(con, method="client_secret_post"))
            secret = c["client_secret"]
            for bad in (None, "", "wrong-secret"):
                with self.assertRaises(oauth.OAuthError) as ctx:
                    self.exchange(con, code, c, client_secret=bad)
                self.assertEqual(ctx.exception.error, "invalid_client")
            self.exchange(con, code, c, client_secret=secret)

    def test_public_client_must_not_send_secret(self):
        with hdb.db(0) as con:
            c, _, code, _ = self.authorize(con)
            with self.assertRaises(oauth.OAuthError) as ctx:
                self.exchange(con, code, c, client_secret="surprise")
            self.assertEqual(ctx.exception.error, "invalid_client")
            self.exchange(con, code, c)

    def test_corrupt_code_scopes_fail_closed(self):
        with hdb.db(0) as con:
            c, _, code, _ = self.authorize(con)
            con.execute("UPDATE oauth_codes SET scopes_json='{bad' WHERE code_hash=?",
                        (_sha(code),))
            with self.assertRaises(oauth.OAuthError) as ctx:
                self.exchange(con, code, c)
            self.assertEqual(ctx.exception.error, "invalid_grant")

    def test_corrupt_client_or_user_policy_fails_closed(self):
        cases = (
            ("oauth_clients", "tool_policy_json", "{bad"),
            ("oauth_clients", "window_json", "[]"),
            ("users", "tool_policy_json", "{bad"),
            ("users", "window_json", "[]"),
        )
        with hdb.db(0) as con:
            for i, (table, field, bad) in enumerate(cases):
                c, u, code, _ = self.authorize(
                    con, client=self.client(con, name=f"corrupt-{i}"),
                    user=self.user(con, username=f"corruptuser{i}"))
                key = c["client_id"] if table == "oauth_clients" else u["id"]
                column = "client_id" if table == "oauth_clients" else "id"
                con.execute(f"UPDATE {table} SET {field}=? WHERE {column}=?", (bad, key))
                with self.assertRaises(oauth.OAuthError, msg=(table, field)) as ctx:
                    self.exchange(con, code, c)
                self.assertEqual(ctx.exception.error, "invalid_grant")
                self.assertEqual(
                    con.execute("SELECT COUNT(*) FROM oauth_tokens").fetchone()[0], 0)

    def test_corrupt_code_timestamp_fails_closed(self):
        with hdb.db(0) as con:
            c, _, code, _ = self.authorize(con)
            con.execute("UPDATE oauth_codes SET expires_at='not-a-time' WHERE code_hash=?",
                        (_sha(code),))
            with self.assertRaises(oauth.OAuthError) as ctx:
                self.exchange(con, code, c)
            self.assertEqual(ctx.exception.error, "invalid_grant")
            self.assertEqual(con.execute("SELECT COUNT(*) FROM oauth_tokens").fetchone()[0], 0)

    def test_issue_code_rechecks_identity(self):
        with hdb.db(0) as con:
            c = self.client(con)
            u = self.user(con, scopes=["health.read"])
            with self.assertRaises(oauth.OAuthError) as ctx:
                oauth.issue_code(con, {
                    "client_id": c["client_id"], "redirect_uri": REDIRECT,
                    "code_challenge": CHALLENGE, "scopes": ["health.write"],
                    "resource": CANON, "session_sid": "sid", "user_id": u["id"],
                }, canonical_resource=CANON, now=NOW)
            self.assertEqual(ctx.exception.error, "invalid_scope")
            with self.assertRaises(oauth.OAuthError):
                oauth.issue_code(con, {
                    "client_id": c["client_id"], "redirect_uri": REDIRECT,
                    "code_challenge": CHALLENGE, "scopes": ["health.read"],
                    "resource": CANON, "session_sid": "sid", "user_id": 999,
                }, canonical_resource=CANON, now=NOW)
            with self.assertRaises(oauth.OAuthError):
                oauth.issue_code(con, {
                    "client_id": c["client_id"], "redirect_uri": REDIRECT,
                    "code_challenge": CHALLENGE, "scopes": ["health.read"],
                    "resource": CANON, "session_sid": "", "user_id": 0,
                }, canonical_resource=CANON, now=NOW)


class RefreshTests(OAuthTestCase):
    def pair(self, con):
        c, u, code, _ = self.authorize(con)
        return c, u, self.exchange(con, code, c)

    def refresh(self, con, tokens, client, scope=None, client_secret=None,
                resource=None, now=NOW):
        data = {
            "refresh_token": tokens["refresh_token"],
            "client_id": client["client_id"],
        }
        if scope is not None:
            data["scope"] = " ".join(scope)
        if client_secret is not None:
            data["client_secret"] = client_secret
        if resource is not None:
            data["resource"] = resource
        return oauth.refresh(con, data, canonical_resource=CANON, now=now)

    def test_rotation_returns_new_pair_and_retires_old_refresh(self):
        with hdb.db(0) as con:
            c, _, first = self.pair(con)
            second = self.refresh(con, first, c)
            self.assertNotEqual(first["access_token"], second["access_token"])
            self.assertNotEqual(first["refresh_token"], second["refresh_token"])
            old = con.execute("SELECT * FROM oauth_tokens WHERE token_hash=?",
                              (_sha(first["refresh_token"]),)).fetchone()
            self.assertIsNotNone(old["revoked_at"])
            self.assertEqual(old["replaced_by_hash"], _sha(second["refresh_token"]))
            self.assertEqual(con.execute(
                "SELECT COUNT(*) FROM oauth_tokens WHERE family_id=?",
                (old["family_id"],)).fetchone()[0], 4)

            third = self.refresh(con, second, c)
            self.assertEqual(len(third["access_token"]), len(second["access_token"]))

    def test_refresh_reuse_revokes_whole_family(self):
        with hdb.db(0) as con:
            c, _, first = self.pair(con)
            second = self.refresh(con, first, c)
            with self.assertRaises(oauth.OAuthError) as ctx:
                self.refresh(con, first, c)
            self.assertEqual(ctx.exception.error, "invalid_grant")

            # The current refresh and access tokens are dead too.
            with self.assertRaises(oauth.OAuthError):
                self.refresh(con, second, c)
            self.assertIsNone(oauth.verify_access(con, second["access_token"], CANON, now=NOW))
            family = con.execute("SELECT DISTINCT family_id FROM oauth_tokens").fetchone()[0]
            rows = con.execute("SELECT revoked_at FROM oauth_tokens WHERE family_id=?",
                               (family,)).fetchall()
            self.assertTrue(all(r["revoked_at"] is not None for r in rows))

    def test_scope_narrowing_and_expansion(self):
        with hdb.db(0) as con:
            c, _, tokens = self.pair(con)
            narrowed = self.refresh(con, tokens, c, scope=["health.read"])
            self.assertEqual(narrowed["scope"], "health.read")
            rows = con.execute(
                "SELECT * FROM oauth_tokens WHERE token_hash IN (?,?)",
                (_sha(narrowed["access_token"]), _sha(narrowed["refresh_token"]))).fetchall()
            self.assertEqual(len(rows), 2)
            self.assertTrue(all(json.loads(r["scopes_json"]) == ["health.read"] for r in rows))

            with self.assertRaises(oauth.OAuthError) as ctx:
                self.refresh(con, narrowed, c, scope=["health.read", "health.write"])
            self.assertEqual(ctx.exception.error, "invalid_scope")
            # invalid_scope must not kill the family.
            self.refresh(con, narrowed, c, scope=["health.read"])

    def test_refresh_binding_and_token_kind(self):
        with hdb.db(0) as con:
            c, _, tokens = self.pair(con)
            other = self.client(con, name="Other", redirects=["https://other.example/cb"])
            with self.assertRaises(oauth.OAuthError) as ctx:
                self.refresh(con, tokens, other)
            self.assertEqual(ctx.exception.error, "invalid_grant")
            with self.assertRaises(oauth.OAuthError):
                oauth.refresh(con, {"refresh_token": tokens["access_token"],
                                    "client_id": c["client_id"]},
                              canonical_resource=CANON, now=NOW)
            with self.assertRaises(oauth.OAuthError) as ctx:
                self.refresh(con, tokens, c, resource="https://other.example/mcp")
            self.assertEqual(ctx.exception.error, "invalid_grant")
            # No family revocation for simple binding mismatches.
            self.refresh(con, tokens, c)

    def test_refresh_expiry(self):
        with hdb.db(0) as con:
            c, _, tokens = self.pair(con)
            with self.assertRaises(oauth.OAuthError) as ctx:
                self.refresh(con, tokens, c, now=NOW + oauth.REFRESH_TTL)
            self.assertEqual(ctx.exception.error, "invalid_grant")

    def test_refresh_rereads_user(self):
        with hdb.db(0) as con:
            c, u, tokens = self.pair(con)
            auth.update_user(con, u["id"], scopes=["health.read"], now=NOW)
            with self.assertRaises(oauth.OAuthError) as ctx:
                self.refresh(con, tokens, c)
            self.assertEqual(ctx.exception.error, "invalid_grant")
            family = con.execute("SELECT DISTINCT family_id FROM oauth_tokens").fetchone()[0]
            rows = con.execute("SELECT revoked_at FROM oauth_tokens WHERE family_id=?",
                               (family,)).fetchall()
            self.assertTrue(all(r["revoked_at"] is not None for r in rows))

    def test_refresh_inherits_token_policy_and_window(self):
        with hdb.db(0) as con:
            c, _, tokens = self.pair(con)
            policy = '["query_sleep"]'
            window = '{"tz":"Asia/Shanghai","days":[1,2,3]}'
            con.execute("UPDATE oauth_tokens SET tool_policy_json=?, window_json=? "
                        "WHERE token_hash=?",
                        (policy, window, _sha(tokens["refresh_token"])))
            new = self.refresh(con, tokens, c)
            for raw in (new["access_token"], new["refresh_token"]):
                row = con.execute("SELECT * FROM oauth_tokens WHERE token_hash=?",
                                  (_sha(raw),)).fetchone()
                self.assertEqual(row["tool_policy_json"], policy)
                self.assertEqual(row["window_json"], window)
            principal = oauth.verify_access(con, new["access_token"], CANON, now=NOW)
            self.assertEqual(principal["tool_policy"], json.loads(policy))
            self.assertEqual(principal["window"], json.loads(window))

    def test_corrupt_policy_or_window_fails_closed(self):
        with hdb.db(0) as con:
            for field, bad in (("tool_policy_json", "{bad"),
                               ("tool_policy_json", "null"),
                               ("tool_policy_json", '{"tool":"query_sleep"}'),
                               ("window_json", '["not-a-dict"]'),
                               ("window_json", "null"),
                               ("window_json", "{bad")):
                c, _, code, _ = self.authorize(con)
                tokens = self.exchange(con, code, c)
                con.execute(f"UPDATE oauth_tokens SET {field}=? WHERE token_hash=?",
                            (bad, _sha(tokens["refresh_token"])))
                with self.assertRaises(oauth.OAuthError, msg=(field, bad)) as ctx:
                    self.refresh(con, tokens, c)
                self.assertEqual(ctx.exception.error, "invalid_grant")
                family = con.execute(
                    "SELECT family_id FROM oauth_tokens WHERE token_hash=?",
                    (_sha(tokens["refresh_token"]),)).fetchone()[0]
                rows = con.execute("SELECT revoked_at FROM oauth_tokens WHERE family_id=?",
                                   (family,)).fetchall()
                self.assertTrue(all(r["revoked_at"] is not None for r in rows))

    def test_corrupt_refresh_scopes_fail_closed(self):
        with hdb.db(0) as con:
            c, _, tokens = self.pair(con)
            con.execute("UPDATE oauth_tokens SET scopes_json='{bad' WHERE token_hash=?",
                        (_sha(tokens["refresh_token"]),))
            with self.assertRaises(oauth.OAuthError):
                self.refresh(con, tokens, c)

    def test_corrupt_client_or_user_policy_revokes_family(self):
        cases = (
            ("oauth_clients", "tool_policy_json", "{bad"),
            ("oauth_clients", "window_json", "[]"),
            ("users", "tool_policy_json", "{bad"),
            ("users", "window_json", "[]"),
        )
        with hdb.db(0) as con:
            for i, (table, field, bad) in enumerate(cases):
                c, u, code, _ = self.authorize(
                    con, client=self.client(con, name=f"corrupt-{i}"),
                    user=self.user(con, username=f"corruptuser{i}"))
                tokens = self.exchange(con, code, c)
                key = c["client_id"] if table == "oauth_clients" else u["id"]
                column = "client_id" if table == "oauth_clients" else "id"
                con.execute(f"UPDATE {table} SET {field}=? WHERE {column}=?", (bad, key))
                with self.assertRaises(oauth.OAuthError, msg=(table, field)) as ctx:
                    self.refresh(con, tokens, c)
                self.assertEqual(ctx.exception.error, "invalid_grant")
                family = con.execute(
                    "SELECT family_id FROM oauth_tokens WHERE token_hash=?",
                    (_sha(tokens["access_token"]),)).fetchone()[0]
                rows = con.execute("SELECT revoked_at FROM oauth_tokens WHERE family_id=?",
                                   (family,)).fetchall()
                self.assertTrue(all(r["revoked_at"] is not None for r in rows))

    def test_corrupt_refresh_timestamp_revokes_family(self):
        with hdb.db(0) as con:
            c, _, tokens = self.pair(con)
            con.execute("UPDATE oauth_tokens SET expires_at='not-a-time' WHERE token_hash=?",
                        (_sha(tokens["refresh_token"]),))
            with self.assertRaises(oauth.OAuthError) as ctx:
                self.refresh(con, tokens, c)
            self.assertEqual(ctx.exception.error, "invalid_grant")
            family = con.execute(
                "SELECT family_id FROM oauth_tokens WHERE token_hash=?",
                (_sha(tokens["access_token"]),)).fetchone()[0]
            rows = con.execute("SELECT revoked_at FROM oauth_tokens WHERE family_id=?",
                               (family,)).fetchall()
            self.assertTrue(all(r["revoked_at"] is not None for r in rows))

    def test_confidential_refresh_requires_secret(self):
        with hdb.db(0) as con:
            c, _, code, _ = self.authorize(con, client=self.client(con, method="client_secret_post"))
            secret = c["client_secret"]
            tokens = self.exchange(con, code, c, client_secret=secret)
            with self.assertRaises(oauth.OAuthError) as ctx:
                self.refresh(con, tokens, c)
            self.assertEqual(ctx.exception.error, "invalid_client")
            self.refresh(con, tokens, c, client_secret=secret)


class VerifyAccessTests(OAuthTestCase):
    def test_valid_principal(self):
        with hdb.db(0) as con:
            c, u, code, _ = self.authorize(con)
            tokens = self.exchange(con, code, c)
            principal = oauth.verify_access(con, tokens["access_token"], CANON, now=NOW)
            self.assertIsNotNone(principal)
            self.assertEqual(principal["kind"], "oauth")
            self.assertEqual(principal["user_id"], u["id"])
            self.assertEqual(principal["username"], "alice")
            self.assertEqual(principal["subject"], f"user:{u['id']}")
            self.assertFalse(principal["is_admin"])
            self.assertEqual(principal["client_id"], c["client_id"])
            self.assertEqual(principal["scopes"], ["health.read", "health.write"])
            self.assertEqual(principal["resource"], CANON)
            self.assertIsNone(principal["tool_policy"])
            self.assertIsNone(principal["window"])
            self.assertEqual(principal["tool_policies"], [None, None, None])
            self.assertEqual(principal["windows"], [None, None, None])
            self.assertEqual(principal["user"]["id"], u["id"])
            self.assertNotIn("tool_policy_json", principal["user"])
            self.assertNotIn("window_json", principal["user"])

    def test_admin_principal(self):
        with hdb.db(0) as con:
            c = self.client(con)
            code = oauth.issue_code(con, {
                "client_id": c["client_id"], "redirect_uri": REDIRECT,
                "code_challenge": CHALLENGE, "scopes": ["health.read"],
                "resource": CANON, "session_sid": "admin-sid", "user_id": 0,
                "username": "admin",
            }, canonical_resource=CANON, now=NOW)
            tokens = self.exchange(con, code, c)
            principal = oauth.verify_access(con, tokens["access_token"], CANON, now=NOW)
            self.assertTrue(principal["is_admin"])
            self.assertEqual(principal["user_id"], 0)
            self.assertEqual(principal["subject"], "admin")
            self.assertIsNone(principal["user"])
            self.assertEqual(principal["scopes"], ["health.read"])
            self.assertEqual(principal["tool_policy"], list(oauth.READ_TOOLS))

    def test_wrong_resource_revokes_family(self):
        with hdb.db(0) as con:
            c, _, code, _ = self.authorize(con)
            tokens = self.exchange(con, code, c)
            self.assertIsNone(oauth.verify_access(con, tokens["access_token"],
                                                  "https://other.example/mcp", now=NOW))
            self.assertIsNone(oauth.verify_access(con, tokens["access_token"], CANON, now=NOW))
            self.assertIsNone(oauth.verify_access(con, tokens["access_token"], "", now=NOW))

    def test_revoked_expired_and_wrong_kind(self):
        with hdb.db(0) as con:
            c, _, code, _ = self.authorize(con)
            tokens = self.exchange(con, code, c)
            self.assertIsNone(oauth.verify_access(con, tokens["refresh_token"], CANON, now=NOW))
            self.assertIsNone(oauth.verify_access(con, "oa_nope", CANON, now=NOW))
            self.assertIsNone(oauth.verify_access(con, None, CANON, now=NOW))
            self.assertIsNone(oauth.verify_access(con, tokens["access_token"], CANON,
                                                  now=NOW + oauth.ACCESS_TTL))
            self.assertIsNotNone(oauth.verify_access(con, tokens["access_token"], CANON,
                                                     now=NOW + oauth.ACCESS_TTL - 1))

    def test_expired_access_does_not_kill_refresh(self):
        with hdb.db(0) as con:
            c, _, code, _ = self.authorize(con)
            tokens = self.exchange(con, code, c)
            self.assertIsNone(oauth.verify_access(con, tokens["access_token"], CANON,
                                                  now=NOW + oauth.ACCESS_TTL))
            refreshed = oauth.refresh(con, {
                "refresh_token": tokens["refresh_token"],
                "client_id": c["client_id"],
            }, canonical_resource=CANON, now=NOW + oauth.ACCESS_TTL)
            self.assertIsNotNone(oauth.verify_access(
                con, refreshed["access_token"], CANON, now=NOW + oauth.ACCESS_TTL))

    def test_disabled_user_or_changed_revision_revokes_family(self):
        with hdb.db(0) as con:
            for i, change in enumerate(({"enabled": False}, {"scopes": ["health.read"]},
                                        {"password": "new-password-123"})):
                c, u, code, _ = self.authorize(con, user=self.user(con, username=f"alice{i}"))
                tokens = self.exchange(con, code, c)
                auth.update_user(con, u["id"], now=NOW, **change)
                self.assertIsNone(oauth.verify_access(con, tokens["access_token"], CANON, now=NOW),
                                  change)
                family = con.execute(
                    "SELECT family_id FROM oauth_tokens WHERE token_hash=?",
                    (_sha(tokens["access_token"]),)).fetchone()[0]
                rows = con.execute("SELECT revoked_at FROM oauth_tokens WHERE family_id=?",
                                   (family,)).fetchall()
                self.assertTrue(all(r["revoked_at"] is not None for r in rows))

    def test_corrupt_token_policy_or_window_revokes_family(self):
        with hdb.db(0) as con:
            for field, bad in (("tool_policy_json", "{bad"),
                               ("tool_policy_json", "null"),
                               ("tool_policy_json", '{"tool":"x"}'),
                               ("window_json", "null"),
                               ("window_json", "[]")):
                c, _, code, _ = self.authorize(con)
                tokens = self.exchange(con, code, c)
                con.execute(f"UPDATE oauth_tokens SET {field}=? WHERE token_hash=?",
                            (bad, _sha(tokens["access_token"])))
                self.assertIsNone(oauth.verify_access(con, tokens["access_token"], CANON, now=NOW))
                family = con.execute(
                    "SELECT family_id FROM oauth_tokens WHERE token_hash=?",
                    (_sha(tokens["access_token"]),)).fetchone()[0]
                rows = con.execute("SELECT revoked_at FROM oauth_tokens WHERE family_id=?",
                                   (family,)).fetchall()
                self.assertTrue(all(r["revoked_at"] is not None for r in rows))

    def test_corrupt_client_or_user_policy_revokes_family(self):
        cases = (
            ("oauth_clients", "tool_policy_json", "{bad"),
            ("oauth_clients", "window_json", "[]"),
            ("users", "tool_policy_json", "{bad"),
            ("users", "window_json", "[]"),
        )
        with hdb.db(0) as con:
            for i, (table, field, bad) in enumerate(cases):
                c, u, code, _ = self.authorize(
                    con, client=self.client(con, name=f"corrupt-{i}"),
                    user=self.user(con, username=f"corruptuser{i}"))
                tokens = self.exchange(con, code, c)
                key = c["client_id"] if table == "oauth_clients" else u["id"]
                column = "client_id" if table == "oauth_clients" else "id"
                con.execute(f"UPDATE {table} SET {field}=? WHERE {column}=?", (bad, key))
                self.assertIsNone(oauth.verify_access(con, tokens["access_token"], CANON, now=NOW))
                family = con.execute(
                    "SELECT family_id FROM oauth_tokens WHERE token_hash=?",
                    (_sha(tokens["access_token"]),)).fetchone()[0]
                rows = con.execute("SELECT revoked_at FROM oauth_tokens WHERE family_id=?",
                                   (family,)).fetchall()
                self.assertTrue(all(r["revoked_at"] is not None for r in rows))

    def test_corrupt_token_timestamp_fails_closed(self):
        with hdb.db(0) as con:
            c, _, code, _ = self.authorize(con)
            tokens = self.exchange(con, code, c)
            con.execute("UPDATE oauth_tokens SET expires_at='not-a-time' WHERE token_hash=?",
                        (_sha(tokens["access_token"]),))
            self.assertIsNone(oauth.verify_access(con, tokens["access_token"], CANON, now=NOW))
            family = con.execute(
                "SELECT family_id FROM oauth_tokens WHERE token_hash=?",
                (_sha(tokens["access_token"]),)).fetchone()[0]
            rows = con.execute("SELECT revoked_at FROM oauth_tokens WHERE family_id=?",
                               (family,)).fetchall()
            self.assertTrue(all(r["revoked_at"] is not None for r in rows))

    def test_corrupt_last_used_does_not_raise(self):
        with hdb.db(0) as con:
            c, _, code, _ = self.authorize(con)
            tokens = self.exchange(con, code, c)
            con.execute("UPDATE oauth_tokens SET last_used_at='not-a-time' WHERE token_hash=?",
                        (_sha(tokens["access_token"]),))
            self.assertIsNotNone(oauth.verify_access(con, tokens["access_token"], CANON, now=NOW))

    def test_admin_password_change_revokes_admin_oauth(self):
        with hdb.db(0) as con:
            auth.bootstrap_admin(con, env={"HCB_ADMIN_PASSWORD": "x" * 12}, now=NOW)
            c = self.client(con)
            code = oauth.issue_code(con, {
                "client_id": c["client_id"], "redirect_uri": REDIRECT,
                "code_challenge": CHALLENGE, "scopes": ["health.read"],
                "resource": CANON, "session_sid": "admin-sid", "user_id": 0,
                "username": "admin",
            }, canonical_resource=CANON, now=NOW)
            admin_tokens = self.exchange(con, code, c)
            pending = oauth.issue_code(con, {
                "client_id": c["client_id"], "redirect_uri": REDIRECT,
                "code_challenge": CHALLENGE, "scopes": ["health.read"],
                "resource": CANON, "session_sid": "admin-sid", "user_id": 0,
                "username": "admin",
            }, canonical_resource=CANON, now=NOW)

            # A normal user's family must survive an admin password change.
            uc, _, ucode, _ = self.authorize(con, user=self.user(con, username="normal"))
            user_tokens = self.exchange(con, ucode, uc)

            auth.admin_set_password(con, "new-admin-password-123", now=NOW + 10)
            self.assertIsNone(oauth.verify_access(con, admin_tokens["access_token"],
                                                  CANON, now=NOW + 11))
            with self.assertRaises(oauth.OAuthError) as ctx:
                oauth.refresh(con, {"refresh_token": admin_tokens["refresh_token"],
                                    "client_id": c["client_id"]},
                              canonical_resource=CANON, now=NOW + 11)
            self.assertEqual(ctx.exception.error, "invalid_grant")
            # Pending admin authorization codes are discarded too.
            with self.assertRaises(oauth.OAuthError):
                self.exchange(con, pending, c, now=NOW + 11)
            self.assertIsNotNone(oauth.verify_access(con, user_tokens["access_token"],
                                                     CANON, now=NOW + 11))

    def test_revoked_client_invalidates_token(self):
        with hdb.db(0) as con:
            c, _, code, _ = self.authorize(con)
            tokens = self.exchange(con, code, c)
            oauth.revoke_client(con, c["client_id"], now=NOW)
            self.assertIsNone(oauth.verify_access(con, tokens["access_token"], CANON, now=NOW))

    def test_last_used_is_touched(self):
        with hdb.db(0) as con:
            c, _, code, _ = self.authorize(con)
            tokens = self.exchange(con, code, c)
            self.assertIsNone(con.execute(
                "SELECT last_used_at FROM oauth_tokens WHERE token_hash=?",
                (_sha(tokens["access_token"]),)).fetchone()[0])
            oauth.verify_access(con, tokens["access_token"], CANON, now=NOW + 120)
            row = con.execute("SELECT last_used_at FROM oauth_tokens WHERE token_hash=?",
                              (_sha(tokens["access_token"]),)).fetchone()
            self.assertEqual(row["last_used_at"], NOW + 120)
            client_row = con.execute("SELECT last_used_at FROM oauth_clients WHERE client_id=?",
                                     (c["client_id"],)).fetchone()
            self.assertEqual(client_row["last_used_at"], NOW + 120)


class RevocationTests(OAuthTestCase):
    def pair(self, con, method="none"):
        c, _, code, _ = self.authorize(con, client=self.client(con, method=method))
        return c, self.exchange(con, code, c, client_secret=c.get("client_secret"))

    def test_refresh_revocation_kills_family(self):
        with hdb.db(0) as con:
            c, tokens = self.pair(con)
            self.assertTrue(oauth.revoke_token(con, tokens["refresh_token"], now=NOW))
            self.assertIsNone(oauth.verify_access(con, tokens["access_token"], CANON, now=NOW))
            with self.assertRaises(oauth.OAuthError):
                oauth.refresh(con, {"refresh_token": tokens["refresh_token"],
                                    "client_id": c["client_id"]},
                              canonical_resource=CANON, now=NOW)

    def test_access_revocation_keeps_refresh_alive(self):
        with hdb.db(0) as con:
            c, tokens = self.pair(con)
            self.assertTrue(oauth.revoke_token(con, tokens["access_token"], now=NOW))
            self.assertIsNone(oauth.verify_access(con, tokens["access_token"], CANON, now=NOW))
            oauth.refresh(con, {"refresh_token": tokens["refresh_token"],
                                "client_id": c["client_id"]},
                          canonical_resource=CANON, now=NOW)

    def test_unknown_and_client_mismatch(self):
        with hdb.db(0) as con:
            c, tokens = self.pair(con)
            other = self.client(con, name="Other", redirects=["https://other.example/cb"])
            self.assertFalse(oauth.revoke_token(con, "oa_missing", now=NOW))
            self.assertFalse(oauth.revoke_token(con, None, now=NOW))
            self.assertFalse(oauth.revoke_token(con, tokens["refresh_token"],
                                                client_id=other["client_id"], now=NOW))
            self.assertIsNotNone(oauth.verify_access(con, tokens["access_token"], CANON, now=NOW))
            # A public client authenticates without a secret; the token binding still holds.
            self.assertTrue(oauth.revoke_token(con, tokens["refresh_token"],
                                              client_id=c["client_id"], now=NOW))
            self.assertIsNone(oauth.verify_access(con, tokens["access_token"], CANON, now=NOW))

    def test_confidential_client_revocation_auth(self):
        with hdb.db(0) as con:
            c, tokens = self.pair(con, method="client_secret_post")
            with self.assertRaises(oauth.OAuthError) as ctx:
                oauth.revoke_token(con, tokens["refresh_token"], client_id=c["client_id"],
                                   client_secret="wrong", now=NOW)
            self.assertEqual(ctx.exception.error, "invalid_client")
            self.assertTrue(oauth.revoke_token(con, tokens["refresh_token"],
                                               client_id=c["client_id"],
                                               client_secret=c["client_secret"], now=NOW))


class CapabilityTests(unittest.TestCase):
    def test_both_scopes_means_no_extra_restriction(self):
        self.assertIsNone(oauth.capabilities_for_scopes(["health.read", "health.write"]))
        self.assertIsNone(oauth.capabilities_for_scopes("health.write health.read"))

    def test_read_only(self):
        caps = oauth.capabilities_for_scopes(["health.read"])
        self.assertEqual(caps, list(oauth.READ_TOOLS))
        self.assertNotIn("ingest", caps)
        for name in ("sync_now", "backfill", "sleep_audit"):
            self.assertNotIn(name, caps)
        for name in ("health_status", "query_heart_rate", "query_sleep",
                     "get_daily_summary", "health_export_csv"):
            self.assertIn(name, caps)

    def test_write_only(self):
        caps = oauth.capabilities_for_scopes(["health.write"])
        self.assertEqual(sorted(caps), sorted(oauth.WRITE_TOOLS))
        self.assertNotIn("ingest", caps)
        self.assertEqual(set(caps), {"sync_now", "backfill", "sleep_audit"})

    def test_no_scope_or_unknown_scope_fails(self):
        for value in ([], "", None, ["health.admin"]):
            with self.assertRaises(oauth.OAuthError, msg=value) as ctx:
                oauth.capabilities_for_scopes(value)
            self.assertEqual(ctx.exception.error, "invalid_scope")

    def test_every_known_tool_is_classified(self):
        # ingest is the only tool deliberately outside the OAuth whitelist.
        known = set(oauth.READ_TOOLS) | set(oauth.WRITE_TOOLS)
        self.assertNotIn("ingest", known)
        self.assertEqual(len(known), len(oauth.READ_TOOLS) + len(oauth.WRITE_TOOLS))
        self.assertGreaterEqual(len(oauth.READ_TOOLS), 15)


class HashingTests(OAuthTestCase):
    def test_every_secret_column_is_hashed(self):
        with hdb.db(0) as con:
            c, _, code, _ = self.authorize(con)
            tokens = self.exchange(con, code, c)
            raw_values = [code, tokens["access_token"], tokens["refresh_token"],
                          "session-sid-1"]
            tables = ["oauth_clients", "oauth_codes", "oauth_tokens"]
            for table in tables:
                for row in con.execute(f"SELECT * FROM {table}").fetchall():
                    text = json.dumps(dict(row))
                    for raw in raw_values:
                        self.assertNotIn(raw, text, table)
            # client_id is public; only the secret hash would be private.
            row = con.execute("SELECT client_secret_hash FROM oauth_clients").fetchone()
            self.assertIsNone(row["client_secret_hash"])


if __name__ == "__main__":
    unittest.main()
