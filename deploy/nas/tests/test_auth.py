"""Task 2: 身份、会话、TOTP 与静态 key 的测试。只用标准库。

覆盖：scrypt 往返、TOTP RFC 6238 向量与窗口、会话签名/篡改/过期/吊销、
用户 revision、CSRF、静态 key 哈希与吊销、环境变量导入幂等、管理员引导。
"""
from __future__ import annotations

import base64
import hashlib
import io
import json
import os
import shutil
import sys
import tempfile
import unittest
from contextlib import redirect_stdout

NAS_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if NAS_DIR not in sys.path:
    sys.path.insert(0, NAS_DIR)

from hcb import auth  # noqa: E402
from hcb import context  # noqa: E402
from hcb import db as hdb  # noqa: E402
from hcb import totp  # noqa: E402

# RFC 6238 的 SHA-1 测试密钥（ASCII "12345678901234567890"）
RFC_SECRET = base64.b32encode(b"12345678901234567890").decode().rstrip("=")
# RFC 6238 的 8 位结果 → 本服务 6 位（取模 10**6）
RFC_VECTORS = [
    (59, "287082"),
    (1111111109, "081804"),
    (1111111111, "050471"),
    (1234567890, "005924"),
    (2000000000, "279037"),
    (20000000000, "353130"),
]


class PasswordTests(unittest.TestCase):
    def test_format_and_roundtrip(self):
        stored = auth.hash_password("s3cret-password")
        self.assertTrue(stored.startswith("scrypt$16384$8$1$"))
        self.assertEqual(len(stored.split("$")), 6)
        self.assertTrue(auth.verify_password("s3cret-password", stored))
        self.assertFalse(auth.verify_password("S3cret-password", stored))
        self.assertFalse(auth.verify_password("wrong", stored))

    def test_salt_is_random(self):
        a = auth.hash_password("same-password")
        b = auth.hash_password("same-password")
        self.assertNotEqual(a, b)
        self.assertTrue(auth.verify_password("same-password", a))
        self.assertTrue(auth.verify_password("same-password", b))

    def test_unicode_password(self):
        stored = auth.hash_password("密码-🔐-ok")
        self.assertTrue(auth.verify_password("密码-🔐-ok", stored))

    def test_bad_input_never_raises_on_verify(self):
        stored = auth.hash_password("secret")
        for bad in (None, "", 0, b"secret", "x" * 1000):
            self.assertFalse(auth.verify_password(bad, stored))
        for bad in ("", "garbage", "scrypt$1$2$3$4$5", "scrypt$x$8$1$YQ$YQ",
                    "bcrypt$16384$8$1$YQ$YQ", "scrypt$16384$8$1$YQ"):
            self.assertFalse(auth.verify_password("secret", bad))

    def test_hash_rejects_empty(self):
        with self.assertRaises(ValueError):
            auth.hash_password("")
        with self.assertRaises(ValueError):
            auth.hash_password(None)

    def test_absurd_cost_is_rejected_not_run(self):
        salt = base64.b64encode(b"s" * 16).decode()
        stored = f"scrypt$1073741824$8$1${salt}${base64.b64encode(b'x' * 32).decode()}"
        self.assertFalse(auth.verify_password("secret", stored))


class TokenHashTests(unittest.TestCase):
    def test_hash_token(self):
        h = auth.hash_token("raw-token")
        self.assertEqual(len(h), 64)
        self.assertEqual(h, hashlib.sha256(b"raw-token").hexdigest())
        self.assertNotEqual(h, auth.hash_token("raw-token2"))

    def test_constant_eq(self):
        self.assertTrue(auth.constant_eq("abc", "abc"))
        self.assertTrue(auth.constant_eq(b"abc", "abc"))
        self.assertFalse(auth.constant_eq("abc", "abd"))
        self.assertFalse(auth.constant_eq(None, "abc"))
        self.assertFalse(auth.constant_eq("abc", 123))


class TOTPTests(unittest.TestCase):
    def test_rfc_6238_vectors(self):
        for at, expected in RFC_VECTORS:
            self.assertEqual(totp.code(RFC_SECRET, at=at), expected, at)
            self.assertEqual(totp.verify(RFC_SECRET, expected, at=at), at // 30)

    def test_generate_secret(self):
        a = totp.generate_secret()
        b = totp.generate_secret()
        self.assertRegex(a, r"^[A-Z2-7]{32}$")
        self.assertNotEqual(a, b)
        self.assertEqual(len(base64.b32decode(a + "=" * (-len(a) % 8))), 20)
        # 生成的密钥可直接用于签发/校验
        self.assertIsNotNone(totp.verify(a, totp.code(a, at=1000), at=1000))

    def test_case_spaces_and_padding(self):
        lower = RFC_SECRET.lower()
        spaced = " ".join(RFC_SECRET[i:i + 4] for i in range(0, len(RFC_SECRET), 4))
        padded = RFC_SECRET + "=" * (-len(RFC_SECRET) % 8)
        for secret in (lower, spaced, padded):
            self.assertEqual(totp.code(secret, at=59), "287082")

    def test_window(self):
        at = 1234567890
        good = totp.code(RFC_SECRET, at=at)
        prev = totp.code(RFC_SECRET, at=at - 30)
        nxt = totp.code(RFC_SECRET, at=at + 30)
        far = totp.code(RFC_SECRET, at=at + 60)
        self.assertEqual(totp.verify(RFC_SECRET, good, at=at), at // 30)
        self.assertEqual(totp.verify(RFC_SECRET, prev, at=at), at // 30 - 1)
        self.assertEqual(totp.verify(RFC_SECRET, nxt, at=at), at // 30 + 1)
        self.assertIsNone(totp.verify(RFC_SECRET, far, at=at))
        self.assertIsNone(totp.verify(RFC_SECRET, prev, at=at, window=0))
        self.assertEqual(totp.verify(RFC_SECRET, far, at=at, window=2), at // 30 + 2)

    def test_wrong_code(self):
        at = 1234567890
        good = totp.code(RFC_SECRET, at=at)
        bad = f"{(int(good) + 1) % 1000000:06d}"
        self.assertIsNone(totp.verify(RFC_SECRET, bad, at=at))
        self.assertIsNone(totp.verify(RFC_SECRET, "12345", at=at))
        self.assertIsNone(totp.verify(RFC_SECRET, "abcdef", at=at))
        self.assertIsNone(totp.verify(RFC_SECRET, None, at=at))

    def test_bad_secret(self):
        for bad in (None, "", "!!!!", "abc", "1234", "not-base32!"):
            self.assertIsNone(totp.code(bad, at=59))
            self.assertIsNone(totp.verify(bad, "287082", at=59))

    def test_int_code(self):
        self.assertEqual(totp.code(RFC_SECRET, at=59), "287082")
        self.assertIsNotNone(totp.verify(RFC_SECRET, 287082, at=59))

    def test_plan_aliases(self):
        self.assertEqual(totp.totp_code(RFC_SECRET, at=59), "287082")
        self.assertEqual(totp.verify_totp(RFC_SECRET, "287082", at=59), 59 // 30)
        self.assertRegex(totp.totp_secret(), r"^[A-Z2-7]{32}$")


class AuthDBTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="hcb-auth-")
        self.health_db = os.path.join(self.tmp, "data", "health.db")
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

    def user(self, con, username="alice", password="correct horse battery"):
        return auth.create_user(con, username, password)

    def enable_totp(self, con, at=1000):
        """建一个已确认的管理员 TOTP；``at`` 的 step 会被记为已用。"""
        secret = totp.generate_secret()
        auth.admin_totp_enable(con, secret, confirm_code=totp.code(secret, at=at), now=at)
        return secret


class UserTests(AuthDBTestCase):
    def test_create_get_by_username(self):
        with hdb.db(0) as con:
            u = self.user(con)
            self.assertEqual(u["username"], "alice")
            self.assertEqual(u["display_name"], "alice")
            self.assertEqual(u["scopes"], ["health.read"])
            self.assertTrue(u["enabled"])
            self.assertEqual(u["revision"], 1)
            self.assertEqual(u["data_mode"], "shared")
            self.assertNotIn("password_hash", u)
            self.assertEqual(auth.get_user(con, u["id"])["username"], "alice")
            self.assertEqual(auth.by_username(con, "ALICE")["id"], u["id"])
            self.assertIsNone(auth.by_username(con, "nobody"))
            self.assertIsNone(auth.get_user(con, 999))

    def test_username_rules(self):
        with hdb.db(0) as con:
            self.assertEqual(self.user(con, "Alice.01-x_y")["username"], "alice.01-x_y")
            for bad in ("ab", "a" * 33, "has space", "üser", "", "ad min"):
                with self.assertRaises(ValueError, msg=bad):
                    self.user(con, bad)
            for reserved in ("admin", "ADMIN", "Admin"):
                with self.assertRaises(ValueError):
                    self.user(con, reserved)
            with self.assertRaises(ValueError):
                self.user(con, "alice2", "")

    def test_duplicate_username_case_insensitive(self):
        with hdb.db(0) as con:
            self.user(con, "alice")
            with self.assertRaises(ValueError):
                self.user(con, "ALICE")

    def test_scopes_validation(self):
        with hdb.db(0) as con:
            u = self.user(con)
            u2 = auth.create_user(con, "bob", "pw-bob", scopes=["health.write", "health.read"])
            self.assertEqual(u2["scopes"], ["health.read", "health.write"])
            u3 = auth.create_user(con, "carol", "pw-carol", scopes=["health.read", "health.read"])
            self.assertEqual(u3["scopes"], ["health.read"])
            with self.assertRaises(ValueError):
                auth.create_user(con, "dave", "pw-dave", scopes=["health.admin"])
            with self.assertRaises(ValueError):
                auth.create_user(con, "eve", "pw-eve", data_mode="cloud")
            self.assertEqual(u["scopes"], ["health.read"])

    def test_update_revision_bumps_only_on_security_changes(self):
        with hdb.db(0) as con:
            u = self.user(con)
            uid = u["id"]

            u = auth.update_user(con, uid, display_name="Alice A.")
            self.assertEqual(u["display_name"], "Alice A.")
            self.assertEqual(u["revision"], 1)

            u = auth.update_user(con, uid, tool_policy_json=["query_sleep"], window_json={"tz": "Asia/Shanghai"})
            self.assertEqual(u["revision"], 1)
            self.assertEqual(json.loads(u["tool_policy_json"]), ["query_sleep"])
            self.assertEqual(json.loads(u["window_json"]), {"tz": "Asia/Shanghai"})

            u = auth.update_user(con, uid, data_mode="own")
            self.assertEqual(u["data_mode"], "own")
            self.assertEqual(u["revision"], 1)

            u = auth.update_user(con, uid, scopes=["health.read", "health.write"])
            self.assertEqual(u["revision"], 2)
            u = auth.update_user(con, uid, scopes=["health.write", "health.read"])
            self.assertEqual(u["revision"], 2)  # 语义不变，不重复 bump

            u = auth.update_user(con, uid, enabled=False)
            self.assertFalse(u["enabled"])
            self.assertEqual(u["revision"], 3)
            u = auth.update_user(con, uid, enabled=True)
            self.assertEqual(u["revision"], 4)

            u = auth.update_user(con, uid, password="new-password")
            self.assertEqual(u["revision"], 5)
            row = con.execute("SELECT password_hash FROM users WHERE id=?", (uid,)).fetchone()
            self.assertTrue(auth.verify_password("new-password", row["password_hash"]))
            self.assertFalse(auth.verify_password("correct horse battery", row["password_hash"]))

    def test_update_unknown_field_and_bad_values(self):
        with hdb.db(0) as con:
            uid = self.user(con)["id"]
            with self.assertRaises(TypeError):
                auth.update_user(con, uid, username="mallory")
            with self.assertRaises(ValueError):
                auth.update_user(con, uid, enabled="yes")
            with self.assertRaises(ValueError):
                auth.update_user(con, uid, scopes=["root"])
            with self.assertRaises(ValueError):
                auth.update_user(con, uid, tool_policy_json="{not json")
            self.assertIsNone(auth.update_user(con, 999, display_name="nobody"))

    def test_list_users(self):
        with hdb.db(0) as con:
            self.user(con, "alice")
            self.user(con, "bob")
            users = auth.list_users(con)
            self.assertEqual([u["username"] for u in users], ["alice", "bob"])
            self.assertTrue(all("password_hash" not in u for u in users))

    def test_check_user_password(self):
        with hdb.db(0) as con:
            self.user(con)
            self.assertEqual(auth.check_user_password(con, "ALICE", "correct horse battery")["username"], "alice")
            self.assertIsNone(auth.check_user_password(con, "alice", "wrong"))
            self.assertIsNone(auth.check_user_password(con, "nobody", "correct horse battery"))


class SessionTests(AuthDBTestCase):
    def _session(self, con, ttl=3600, ua="pytest", now=1000):
        u = self.user(con)
        sid, cookie = auth.create_session(con, auth.subject_for_user(u["id"]), u["revision"], ttl, ua, now=now)
        return u, sid, cookie

    def test_cookie_shape_and_db_stores_hash(self):
        with hdb.db(0) as con:
            u, sid, cookie = self._session(con)
            self.assertEqual(cookie.count("."), 1)
            payload_b64 = cookie.split(".", 1)[0]
            payload = json.loads(base64.urlsafe_b64decode(payload_b64 + "=" * (-len(payload_b64) % 4)))
            self.assertEqual(payload["sub"], f"user:{u['id']}")
            self.assertEqual(payload["rev"], u["revision"])
            self.assertEqual(payload["sid"], sid)
            self.assertEqual(payload["exp"], 1000 + 3600)

            row = con.execute("SELECT * FROM sessions").fetchone()
            self.assertEqual(row["sid_hash"], auth.hash_token(sid))
            self.assertNotEqual(row["sid_hash"], sid)
            self.assertNotIn(sid, row["sid_hash"])
            self.assertEqual(row["revision"], 1)

            secret = auth.session_secret(con)
            got = auth.read_session(con, cookie, secret, now=1001)
            self.assertEqual(got["subject"], f"user:{u['id']}")
            self.assertEqual(got["user_id"], u["id"])
            self.assertEqual(got["sid"], sid)
            self.assertFalse(got["is_admin"])

    def test_tampered_cookie_rejected(self):
        with hdb.db(0) as con:
            u, sid, cookie = self._session(con)
            secret = auth.session_secret(con)
            payload_b64, sig = cookie.split(".", 1)

            raw = json.loads(base64.urlsafe_b64decode(payload_b64 + "=" * (-len(payload_b64) % 4)))
            raw["rev"] = 999
            forged_payload = base64.urlsafe_b64encode(
                json.dumps(raw, separators=(",", ":")).encode()).decode().rstrip("=")
            self.assertIsNone(auth.read_session(con, f"{forged_payload}.{sig}", secret, now=1001))

            flipped = sig[:-1] + ("A" if sig[-1] != "A" else "B")
            self.assertIsNone(auth.read_session(con, f"{payload_b64}.{flipped}", secret, now=1001))
            self.assertIsNone(auth.read_session(con, cookie, "wrong-secret", now=1001))
            self.assertIsNone(auth.read_session(con, "garbage", secret, now=1001))
            self.assertIsNone(auth.read_session(con, "", secret, now=1001))

    def test_expiry(self):
        with hdb.db(0) as con:
            _, _, cookie = self._session(con, ttl=60, now=1000)
            secret = auth.session_secret(con)
            self.assertIsNotNone(auth.read_session(con, cookie, secret, now=1059))
            self.assertIsNone(auth.read_session(con, cookie, secret, now=1060))

    def test_default_ttls_by_subject(self):
        with hdb.db(0) as con:
            u = self.user(con)
            auth.create_session(con, "admin", 1, ua="a", now=1000)
            auth.create_session(con, auth.subject_for_user(u["id"]), u["revision"],
                                ua="u", now=1000)
            rows = con.execute("SELECT subject, expires_at FROM sessions").fetchall()
            by_subject = {row["subject"]: row["expires_at"] for row in rows}
            self.assertEqual(by_subject["admin"], 1000 + auth.ADMIN_SESSION_TTL)
            self.assertEqual(by_subject[f"user:{u['id']}"], 1000 + auth.USER_SESSION_TTL)

    def test_revoke_session_accepts_uppercase_sid_hash(self):
        with hdb.db(0) as con:
            _, sid, cookie = self._session(con)
            secret = auth.session_secret(con)
            sid_hash = con.execute(
                "SELECT sid_hash FROM sessions WHERE sid_hash=?", (auth.hash_token(sid),)
            ).fetchone()["sid_hash"]
            upper = sid_hash.upper()
            self.assertNotEqual(upper, sid_hash)
            self.assertTrue(auth.revoke_session(con, upper, now=1001))
            self.assertIsNone(auth.read_session(con, cookie, secret, now=1002))

    def test_revoke_subject_keep_sid_accepts_uppercase_hash(self):
        with hdb.db(0) as con:
            u = self.user(con)
            sub = auth.subject_for_user(u["id"])
            sid1, c1 = auth.create_session(con, sub, u["revision"], 3600, "a", now=1000)
            sid2, c2 = auth.create_session(con, sub, u["revision"], 3600, "b", now=1000)
            secret = auth.session_secret(con)
            self.assertEqual(
                auth.revoke_subject(con, sub, keep_sid=auth.hash_token(sid2).upper(), now=1001),
                1,
            )
            self.assertIsNone(auth.read_session(con, c1, secret, now=1002))
            self.assertIsNotNone(auth.read_session(con, c2, secret, now=1002))

    def test_revoke_session(self):
        with hdb.db(0) as con:
            _, sid, cookie = self._session(con)
            secret = auth.session_secret(con)
            self.assertTrue(auth.revoke_session(con, sid, now=1001))
            self.assertIsNone(auth.read_session(con, cookie, secret, now=1002))
            self.assertFalse(auth.revoke_session(con, sid, now=1003))

    def test_revoke_subject_keeps_current(self):
        with hdb.db(0) as con:
            u = self.user(con)
            sub = auth.subject_for_user(u["id"])
            sid1, c1 = auth.create_session(con, sub, u["revision"], 3600, "a", now=1000)
            sid2, c2 = auth.create_session(con, sub, u["revision"], 3600, "b", now=1000)
            secret = auth.session_secret(con)
            self.assertEqual(auth.revoke_subject(con, sub, keep_sid=sid2, now=1001), 1)
            self.assertIsNone(auth.read_session(con, c1, secret, now=1002))
            self.assertIsNotNone(auth.read_session(con, c2, secret, now=1002))
            self.assertEqual(auth.revoke_subject(con, sub, now=1003), 1)
            self.assertIsNone(auth.read_session(con, c2, secret, now=1004))

    def test_revision_change_invalidates_session(self):
        with hdb.db(0) as con:
            u, _, cookie = self._session(con)
            secret = auth.session_secret(con)
            auth.update_user(con, u["id"], password="another-password")
            self.assertIsNone(auth.read_session(con, cookie, secret, now=1001))

    def test_disable_and_delete_invalidate_session(self):
        with hdb.db(0) as con:
            u, _, cookie = self._session(con)
            secret = auth.session_secret(con)
            auth.update_user(con, u["id"], enabled=False)
            self.assertIsNone(auth.read_session(con, cookie, secret, now=1001))
            # 重新启用会再 bump revision；老会话仍失效，新会话才有效。
            u = auth.update_user(con, u["id"], enabled=True)
            self.assertIsNone(auth.read_session(con, cookie, secret, now=1002))
            _, fresh = auth.create_session(con, auth.subject_for_user(u["id"]), u["revision"], 3600, "new", now=1002)
            self.assertIsNotNone(auth.read_session(con, fresh, secret, now=1003))
            con.execute("DELETE FROM users WHERE id=?", (u["id"],))
            self.assertIsNone(auth.read_session(con, fresh, secret, now=1004))

    def test_missing_session_row_rejected(self):
        with hdb.db(0) as con:
            _, _, cookie = self._session(con)
            secret = auth.session_secret(con)
            con.execute("DELETE FROM sessions")
            self.assertIsNone(auth.read_session(con, cookie, secret, now=1001))

    def test_admin_session(self):
        with hdb.db(0) as con:
            sid, cookie = auth.create_session(con, "admin", 1, 600, "cli", now=1000)
            got = auth.read_session(con, cookie, auth.session_secret(con), now=1001)
            self.assertEqual(got["subject"], "admin")
            self.assertEqual(got["user_id"], 0)
            self.assertTrue(got["is_admin"])
            self.assertEqual(got["sid"], sid)

    def test_ua_truncated_to_120(self):
        with hdb.db(0) as con:
            u = self.user(con)
            sid, _ = auth.create_session(con, auth.subject_for_user(u["id"]), 1, 60, "u" * 500, now=1000)
            row = con.execute("SELECT user_agent FROM sessions WHERE sid_hash=?", (auth.hash_token(sid),)).fetchone()
            self.assertEqual(len(row["user_agent"]), 120)
            self.assertEqual(row["user_agent"], "u" * 120)

    def test_bad_subject_and_ttl(self):
        with hdb.db(0) as con:
            self.user(con)
            for sub in ("root", "user:999", "user:x", "", None):
                with self.assertRaises(ValueError, msg=str(sub)):
                    auth.create_session(con, sub, 1, 60, "ua", now=1000)
            with self.assertRaises(ValueError):
                auth.create_session(con, "admin", 1, 0, "ua", now=1000)
            with self.assertRaises(ValueError):
                auth.create_session(con, "admin", 1, -5, "ua", now=1000)

    def test_session_for_stale_revision_rejected_at_creation(self):
        with hdb.db(0) as con:
            u = self.user(con)
            with self.assertRaises(ValueError):
                auth.create_session(con, auth.subject_for_user(u["id"]), u["revision"] + 1, 60, "ua", now=1000)

    def test_secret_is_stable_and_persisted(self):
        with hdb.db(0) as con:
            a = auth.session_secret(con)
            b = auth.session_secret(con)
            self.assertEqual(a, b)
            self.assertGreaterEqual(len(a), 40)
        with hdb.db(0) as con:
            self.assertEqual(auth.session_secret(con), a)

    def test_csrf_bound_to_sid(self):
        with hdb.db(0) as con:
            secret = auth.session_secret(con)
            token = auth.csrf_token(secret, "sid-1")
            self.assertTrue(auth.csrf_verify(secret, "sid-1", token))
            self.assertFalse(auth.csrf_verify(secret, "sid-2", token))
            self.assertFalse(auth.csrf_verify(secret, "sid-1", token + "x"))
            self.assertFalse(auth.csrf_verify("other", "sid-1", token))

    def test_sign_payload_roundtrip(self):
        value = auth.sign_payload("key", {"a": 1, "b": "x"})
        self.assertEqual(auth.verify_payload("key", value), {"a": 1, "b": "x"})
        self.assertIsNone(auth.verify_payload("other", value))
        self.assertIsNone(auth.verify_payload("key", value + "x"))
        self.assertIsNone(auth.verify_payload("key", auth.sign_payload("key", ["not", "dict"])))


class ChangePasswordTests(AuthDBTestCase):
    def test_old_cookie_rejected_new_cookie_works_others_dead(self):
        with hdb.db(0) as con:
            u = self.user(con)
            sub = auth.subject_for_user(u["id"])
            sid_a, cookie_a = auth.create_session(con, sub, u["revision"], 3600, "ua-a", now=1000)
            sid_b, cookie_b = auth.create_session(con, sub, u["revision"], 3600, "ua-b", now=1000)
            secret = auth.session_secret(con)

            new_sid, new_cookie = auth.change_password(
                con, u["id"], "brand-new-password", current_sid=sid_a, now=1001,
            )
            self.assertNotEqual(new_sid, sid_a)
            self.assertIsNone(auth.read_session(con, cookie_a, secret, now=1002))
            self.assertIsNone(auth.read_session(con, cookie_b, secret, now=1002))

            got = auth.read_session(con, new_cookie, secret, now=1002)
            self.assertIsNotNone(got)
            self.assertEqual(got["subject"], sub)
            self.assertEqual(got["revision"], 2)
            self.assertEqual(got["user_agent"], "ua-a")
            self.assertEqual(got["expires_at"], 1001 + auth.USER_SESSION_TTL)

            self.assertIsNotNone(auth.check_user_password(con, "alice", "brand-new-password"))
            self.assertIsNone(auth.check_user_password(con, "alice", "correct horse battery"))
            active = con.execute(
                "SELECT COUNT(*) FROM sessions WHERE subject=? AND revoked_at IS NULL", (sub,)
            ).fetchone()[0]
            self.assertEqual(active, 1)

    def test_change_password_requires_current_session(self):
        with hdb.db(0) as con:
            u = self.user(con)
            sub = auth.subject_for_user(u["id"])
            _, cookie = auth.create_session(con, sub, u["revision"], 3600, "ua", now=1000)
            with self.assertRaises(ValueError):
                auth.change_password(con, u["id"], "new-password", current_sid="missing", now=1001)
            self.assertIsNotNone(auth.check_user_password(con, "alice", "correct horse battery"))

            bob = self.user(con, "bob", "bob-password")
            bob_sid, _ = auth.create_session(
                con, auth.subject_for_user(bob["id"]), bob["revision"], 3600, "bob", now=1000,
            )
            with self.assertRaises(ValueError):
                auth.change_password(con, u["id"], "new-password", current_sid=bob_sid, now=1001)
            with self.assertRaises(ValueError):
                auth.change_password(con, u["id"], "new-password", current_sid=cookie, ttl=0, now=1001)
            self.assertIsNotNone(auth.check_user_password(con, "alice", "correct horse battery"))

            auth.update_user(con, u["id"], enabled=False)
            with self.assertRaises(ValueError):
                auth.change_password(con, u["id"], "new-password", current_sid=cookie, now=1001)


class SettingsTests(AuthDBTestCase):
    def test_get_set_delete(self):
        with hdb.db(0) as con:
            self.assertIsNone(auth.settings_get(con, "missing"))
            self.assertEqual(auth.settings_get(con, "missing", "fallback"), "fallback")
            auth.settings_set(con, "k", "v")
            self.assertEqual(auth.settings_get(con, "k"), "v")
            auth.settings_set(con, "k", "v2")
            self.assertEqual(auth.settings_get(con, "k"), "v2")
            auth.settings_set(con, "k", None)
            self.assertIsNone(auth.settings_get(con, "k"))


class StaticTokenTests(AuthDBTestCase):
    def test_create_lookup_revoke(self):
        with hdb.db(0) as con:
            rec = auth.static_token_create(con, "phone", now=1000)
            raw = rec["token"]
            self.assertTrue(raw)
            self.assertEqual(rec["label"], "phone")
            self.assertEqual(rec["owner_user_id"], 0)
            self.assertEqual(rec["origin"], "ui")
            self.assertNotIn("hash", rec)

            row = con.execute("SELECT * FROM agent_tokens").fetchone()
            self.assertEqual(row["hash"], auth.hash_token(raw))
            self.assertNotIn(raw, row["hash"])
            self.assertNotIn(raw, row["label"])

            got = auth.static_token_lookup(con, raw, now=1001)
            self.assertEqual(got["id"], rec["id"])
            self.assertEqual(got["label"], "phone")
            self.assertEqual(got["owner_user_id"], 0)
            self.assertEqual(got["last_used_at"], 1001)
            self.assertNotIn("hash", got)

            self.assertIsNone(auth.static_token_lookup(con, "wrong", now=1002))
            self.assertIsNone(auth.static_token_lookup(con, "", now=1002))
            self.assertIsNone(auth.static_token_lookup(con, None, now=1002))

            self.assertTrue(auth.static_token_revoke(con, rec["id"], now=1003))
            self.assertIsNone(auth.static_token_lookup(con, raw, now=1004))
            self.assertFalse(auth.static_token_revoke(con, rec["id"], now=1005))
            listed = auth.static_token_list(con, include_revoked=True)
            self.assertEqual([t["label"] for t in listed], ["phone"])
            self.assertNotIn("hash", listed[0])

    def test_revoke_by_label(self):
        with hdb.db(0) as con:
            auth.static_token_create(con, "laptop")
            self.assertTrue(auth.static_token_revoke(con, "laptop"))
            self.assertFalse(auth.static_token_revoke(con, "missing"))
            self.assertEqual(auth.static_token_list(con), [])

    def test_duplicates_rejected(self):
        with hdb.db(0) as con:
            auth.static_token_create(con, "phone")
            with self.assertRaises(ValueError):
                auth.static_token_create(con, "phone")
            auth.static_token_create(con, "phone2", token="fixed-token")
            with self.assertRaises(ValueError):
                auth.static_token_create(con, "phone3", token="fixed-token")

    def test_owner_policy_and_window(self):
        with hdb.db(0) as con:
            u = self.user(con)
            rec = auth.static_token_create(
                con, "phone", owner_user_id=u["id"],
                tool_policy_json=["query_sleep"],
                window_json={"tz": "Asia/Shanghai", "days": [1, 2, 3]},
            )
            got = auth.static_token_lookup(con, rec["token"], now=1000)
            self.assertEqual(got["owner_user_id"], u["id"])
            self.assertEqual(json.loads(got["tool_policy_json"]), ["query_sleep"])
            self.assertEqual(json.loads(got["window_json"])["tz"], "Asia/Shanghai")
            auth.update_user(con, u["id"], enabled=False)
            self.assertIsNone(auth.static_token_lookup(con, rec["token"], now=1001))

    def test_invalid_owner(self):
        with hdb.db(0) as con:
            with self.assertRaises(ValueError):
                auth.static_token_create(con, "phone", owner_user_id=999)
            with self.assertRaises(ValueError):
                auth.static_token_create(con, "phone", owner_user_id=-1)
            with self.assertRaises(ValueError):
                auth.static_token_create(con, "")


class EnvImportTests(AuthDBTestCase):
    ENV = {"HEALTH_MCP_TOKENS": "phone:tok-phone, bare-token", "HEALTH_MCP_TOKEN": "legacy-token"}

    def test_import_and_idempotent(self):
        with hdb.db(0) as con:
            self.assertEqual(auth.import_env_tokens(con, env=self.ENV, now=1000), 3)
            self.assertEqual(auth.settings_get(con, "tokens_imported"), "1")
            labels = [t["label"] for t in auth.static_token_list(con)]
            self.assertEqual(labels, ["phone", "unnamed", "legacy"])
            self.assertEqual(auth.static_token_lookup(con, "tok-phone", now=1001)["label"], "phone")
            self.assertEqual(auth.static_token_lookup(con, "bare-token", now=1001)["label"], "unnamed")
            self.assertEqual(auth.static_token_lookup(con, "legacy-token", now=1001)["label"], "legacy")
            for t in auth.static_token_list(con):
                self.assertEqual(t["origin"], "env")

        with hdb.db(0) as con:
            self.assertEqual(auth.import_env_tokens(con, env=self.ENV, now=2000), 0)
            self.assertEqual(len(auth.static_token_list(con)), 3)

    def test_env_change_after_import_ignored(self):
        with hdb.db(0) as con:
            auth.import_env_tokens(con, env={"HEALTH_MCP_TOKEN": "one"}, now=1000)
        changed = {"HEALTH_MCP_TOKENS": "new:two", "HEALTH_MCP_TOKEN": "one"}
        with hdb.db(0) as con:
            self.assertEqual(auth.import_env_tokens(con, env=changed, now=2000), 0)
            self.assertIsNone(auth.static_token_lookup(con, "two", now=2001))
            self.assertIsNotNone(auth.static_token_lookup(con, "one", now=2001))

    def test_revoked_token_not_resurrected(self):
        with hdb.db(0) as con:
            auth.import_env_tokens(con, env={"HEALTH_MCP_TOKEN": "one"}, now=1000)
            rec = auth.static_token_list(con)[0]
            auth.static_token_revoke(con, rec["id"], now=1001)
        with hdb.db(0) as con:
            auth.import_env_tokens(con, env={"HEALTH_MCP_TOKEN": "one"}, now=2000)
            self.assertIsNone(auth.static_token_lookup(con, "one", now=2001))

    def test_empty_env_does_not_mark_imported(self):
        with hdb.db(0) as con:
            self.assertEqual(auth.import_env_tokens(con, env={}, now=1000), 0)
            self.assertIsNone(auth.settings_get(con, "tokens_imported"))
        with hdb.db(0) as con:
            self.assertEqual(auth.import_env_tokens(con, env={"HEALTH_MCP_TOKEN": "later"}, now=2000), 1)

    def test_failed_labels_are_reported_not_silent(self):
        long_label = "l" * 65
        ctrl_label = "bad\x01label"
        env = {"HEALTH_MCP_TOKENS": f"{long_label}:tok-long,{ctrl_label}:tok-ctrl,ok:tok-ok"}
        failures = []
        buf = io.StringIO()
        with hdb.db(0) as con:
            with redirect_stdout(buf):
                inserted = auth.import_env_tokens(con, env=env, now=1000, failures=failures)
            self.assertEqual(inserted, 1)
            self.assertEqual(failures, [long_label, "bad?label"])
            self.assertIsNone(auth.settings_get(con, "tokens_imported"))
            self.assertIsNotNone(auth.static_token_lookup(con, "tok-ok", now=1001))
            self.assertIsNone(auth.static_token_lookup(con, "tok-long", now=1001))
            self.assertIsNone(auth.static_token_lookup(con, "tok-ctrl", now=1001))
        output = buf.getvalue()
        self.assertIn(long_label, output)
        self.assertIn("bad?label", output)
        for token in ("tok-long", "tok-ctrl", "tok-ok"):
            self.assertNotIn(token, output)

    def test_failed_label_containing_token_is_redacted(self):
        token = "tok-secret"
        label = token + "x" * 60  # >64 字符 → 导入失败
        self.assertGreater(len(label), 64)
        failures = []
        buf = io.StringIO()
        with hdb.db(0) as con:
            with redirect_stdout(buf):
                inserted = auth.import_env_tokens(
                    con, env={"HEALTH_MCP_TOKENS": f"{label}:{token}"},
                    now=1000, failures=failures,
                )
            self.assertEqual(inserted, 0)
            self.assertEqual(failures, ["[redacted-label]"])
        output = buf.getvalue()
        self.assertIn("[redacted-label]", output)
        self.assertNotIn(token, output)

    def test_no_plaintext_tokens_in_db(self):
        with hdb.db(0) as con:
            auth.import_env_tokens(con, env=self.ENV, now=1000)
            rows = con.execute("SELECT label, hash, tool_policy_json, window_json, origin FROM agent_tokens").fetchall()
            for row in rows:
                for value in row:
                    if value:
                        self.assertNotIn("tok-phone", str(value))
                        self.assertNotIn("bare-token", str(value))
                        self.assertNotIn("legacy-token", str(value))

    def test_duplicate_token_in_env_skipped(self):
        env = {"HEALTH_MCP_TOKENS": "a:same,b:same", "HEALTH_MCP_TOKEN": ""}
        with hdb.db(0) as con:
            self.assertEqual(auth.import_env_tokens(con, env=env, now=1000), 1)
            self.assertEqual(len(auth.static_token_list(con)), 1)
            self.assertIsNotNone(auth.static_token_lookup(con, "same", now=1001))


class AdminBootstrapTests(AuthDBTestCase):
    def test_bootstrap_only_when_absent(self):
        with hdb.db(0) as con:
            self.assertFalse(auth.admin_has_password(con))
            missing = auth.bootstrap_admin(con, env={})
            self.assertFalse(missing)
            self.assertEqual(missing.status, "missing")
            self.assertFalse(auth.admin_has_password(con))
        with hdb.db(0) as con:
            created = auth.bootstrap_admin(con, env={"HCB_ADMIN_PASSWORD": "x" * 12})
            self.assertTrue(created)
            self.assertEqual(created.status, "created")
            self.assertTrue(auth.admin_has_password(con))
            self.assertTrue(auth.admin_check_password(con, "x" * 12))
            self.assertFalse(auth.admin_check_password(con, "y" * 12))
            self.assertFalse(auth.admin_check_password(con, None))
            row = con.execute("SELECT value FROM sync_state WHERE key='admin_password_hash'").fetchone()
            self.assertTrue(row["value"].startswith("scrypt$"))
            self.assertNotIn("x" * 12, row["value"])
        with hdb.db(0) as con:
            # 改环境变量不重置
            again = auth.bootstrap_admin(con, env={"HCB_ADMIN_PASSWORD": "y" * 12})
            self.assertFalse(again)
            self.assertEqual(again.status, "already_configured")
            self.assertTrue(auth.admin_check_password(con, "x" * 12))
            self.assertFalse(auth.admin_check_password(con, "y" * 12))

    def test_short_bootstrap_password_does_not_raise(self):
        with hdb.db(0) as con:
            result = auth.bootstrap_admin(con, env={"HCB_ADMIN_PASSWORD": "short"})
            self.assertFalse(result)
            self.assertEqual(result.status, "weak_password")
            self.assertIn("12", result.message)
            self.assertFalse(auth.admin_has_password(con))
            blank = auth.bootstrap_admin(con, env={"HCB_ADMIN_PASSWORD": " " * 12})
            self.assertFalse(blank)
            self.assertEqual(blank.status, "weak_password")
            self.assertFalse(auth.admin_has_password(con))

    def test_admin_set_password_revokes_sessions(self):
        with hdb.db(0) as con:
            auth.bootstrap_admin(con, env={"HCB_ADMIN_PASSWORD": "x" * 12})
            _, cookie = auth.create_session(con, "admin", 1, 3600, "ua", now=1000)
            secret = auth.session_secret(con)
            self.assertIsNotNone(auth.read_session(con, cookie, secret, now=1001))
            auth.admin_set_password(con, "z" * 12, now=1002)
            self.assertIsNone(auth.read_session(con, cookie, secret, now=1003))
            self.assertTrue(auth.admin_check_password(con, "z" * 12))
            self.assertFalse(auth.admin_check_password(con, "x" * 12))
            with self.assertRaises(ValueError):
                auth.admin_set_password(con, "short", now=1004)

    def test_totp_secret_encrypted_at_rest(self):
        with hdb.db(0) as con:
            state = auth.admin_totp_state(con)
            self.assertFalse(state["enabled"])
            self.assertIsNone(state["secret"])

            secret = auth.admin_totp_enable(con)
            self.assertRegex(secret, r"^[A-Z2-7]{32}$")
            raw = con.execute("SELECT value FROM sync_state WHERE key='admin_totp_secret'").fetchone()["value"]
            self.assertNotIn(secret, raw)
            self.assertFalse(raw.startswith(secret))
            self.assertTrue(raw.startswith("v1:"))

            state = auth.admin_totp_state(con)
            self.assertFalse(state["enabled"])
            self.assertEqual(state["secret"], secret)
            self.assertTrue(auth.admin_totp_confirm(con, totp.code(secret, at=1000), at=1000))
            state = auth.admin_totp_state(con)
            self.assertTrue(state["enabled"])
            self.assertTrue(auth.admin_totp_verify(con, totp.code(secret, at=1030), at=1030))
            self.assertFalse(auth.admin_totp_verify(con, "000000", at=1030))
            self.assertFalse(auth.admin_totp_verify(con, "", at=1030))

            auth.session_secret(con)
            keys = [r["key"] for r in con.execute("SELECT key FROM service_secrets").fetchall()]
            self.assertIn("session_secret", keys)
            self.assertIn("totp_key", keys)

    def test_tampered_totp_ciphertext_fails_closed(self):
        with hdb.db(0) as con:
            secret = self.enable_totp(con)
            raw = con.execute("SELECT value FROM sync_state WHERE key='admin_totp_secret'").fetchone()["value"]
            parts = raw.split(":")
            ct = bytearray(base64.urlsafe_b64decode(parts[2] + "=" * (-len(parts[2]) % 4)))
            ct[0] ^= 1
            parts[2] = base64.urlsafe_b64encode(bytes(ct)).decode().rstrip("=")
            con.execute("UPDATE sync_state SET value=? WHERE key='admin_totp_secret'", (":".join(parts),))
            state = auth.admin_totp_state(con)
            self.assertTrue(state["enabled"])
            self.assertIsNone(state["secret"])
            self.assertFalse(auth.admin_totp_verify(con, totp.code(secret, at=1030), at=1030))

    def test_totp_disable(self):
        with hdb.db(0) as con:
            secret = self.enable_totp(con)
            codes = auth.admin_recovery_generate(con)
            self.assertTrue(auth.admin_totp_verify(con, totp.code(secret, at=1030), at=1030))
            auth.admin_totp_disable(con)
            state = auth.admin_totp_state(con)
            self.assertFalse(state["enabled"])
            self.assertIsNone(state["secret"])
            self.assertFalse(auth.admin_totp_verify(con, totp.code(secret, at=1060), at=1060))
            self.assertEqual(auth.admin_recovery_remaining(con), 0)
            self.assertFalse(auth.admin_recovery_consume(con, codes[0]))

    def test_enable_does_not_disable_active_totp_on_bad_confirm(self):
        with hdb.db(0) as con:
            secret = self.enable_totp(con)
            codes = auth.admin_recovery_generate(con)
            with self.assertRaises(ValueError):
                auth.admin_totp_enable(
                    con, totp.generate_secret(), confirm_code="000000", now=1030,
                )
            state = auth.admin_totp_state(con)
            self.assertTrue(state["enabled"])
            self.assertEqual(state["secret"], secret)
            self.assertEqual(auth.admin_recovery_remaining(con), 10)
            self.assertTrue(auth.admin_totp_verify(con, totp.code(secret, at=1060), at=1060))
            self.assertTrue(auth.admin_recovery_consume(con, codes[0]))

    def test_totp_enable_with_given_secret(self):
        with hdb.db(0) as con:
            self.assertEqual(
                auth.admin_totp_enable(con, RFC_SECRET, confirm_code="287082", now=59),
                RFC_SECRET,
            )
            self.assertTrue(auth.admin_totp_state(con)["enabled"])
            self.assertTrue(auth.admin_totp_verify(con, totp.code(RFC_SECRET, at=89), at=89))
            with self.assertRaises(ValueError):
                auth.admin_totp_enable(con, "not-base32!")

    def test_totp_requires_confirmation_and_rejects_replay(self):
        with hdb.db(0) as con:
            secret = auth.admin_totp_enable(con)
            self.assertFalse(auth.admin_totp_state(con)["enabled"])
            self.assertFalse(auth.admin_totp_verify(con, totp.code(secret, at=1000), at=1000))
            with self.assertRaises(ValueError):
                auth.admin_totp_enable(con, secret, confirm_code="000000", now=1000)
            self.assertFalse(auth.admin_totp_state(con)["enabled"])
            self.assertTrue(auth.admin_totp_confirm(con, totp.code(secret, at=1000), at=1000))
            self.assertTrue(auth.admin_totp_state(con)["enabled"])
            # 同一 step 与更早 step 都不能重放
            self.assertFalse(auth.admin_totp_verify(con, totp.code(secret, at=1000), at=1000))
            self.assertFalse(auth.admin_totp_verify(con, totp.code(secret, at=970), at=1000))
            self.assertTrue(auth.admin_totp_verify(con, totp.code(secret, at=1030), at=1030))
            self.assertFalse(auth.admin_totp_verify(con, totp.code(secret, at=1030), at=1030))
            # last step 损坏时 fail-closed
            con.execute("UPDATE sync_state SET value='bad' WHERE key='admin_totp_last_step'")
            self.assertFalse(auth.admin_totp_verify(con, totp.code(secret, at=1060), at=1060))


class RecoveryCodeTests(AuthDBTestCase):
    def test_generate_and_consume_once(self):
        with hdb.db(0) as con:
            self.enable_totp(con)
            codes = auth.admin_recovery_generate(con)
            self.assertEqual(len(codes), 10)
            self.assertEqual(len(set(codes)), 10)
            self.assertTrue(all(len(c) == 19 and c.count("-") == 3 for c in codes))
            self.assertEqual(auth.admin_recovery_remaining(con), 10)
            raw = con.execute("SELECT value FROM sync_state WHERE key='admin_recovery_codes'").fetchone()["value"]
            for code in codes:
                self.assertNotIn(code, raw)
                self.assertNotIn(code.replace("-", ""), raw)
            self.assertTrue(auth.admin_recovery_consume(con, codes[0]))
            self.assertFalse(auth.admin_recovery_consume(con, codes[0]))
            self.assertEqual(auth.admin_recovery_remaining(con), 9)
            # 大小写/分隔符不敏感
            self.assertTrue(auth.admin_recovery_consume(con, codes[1].upper().replace("-", " ")))
            self.assertFalse(auth.admin_recovery_consume(con, "not-a-code"))
            self.assertFalse(auth.admin_recovery_consume(con, None))

    def test_enable_clears_old_codes(self):
        with hdb.db(0) as con:
            secret = self.enable_totp(con)
            codes = auth.admin_recovery_generate(con)
            self.assertEqual(auth.admin_recovery_remaining(con), 10)
            auth.admin_totp_enable(
                con, secret, confirm_code=totp.code(secret, at=1030), now=1030,
            )
            self.assertEqual(auth.admin_recovery_remaining(con), 0)
            self.assertFalse(auth.admin_recovery_consume(con, codes[0]))

    def test_bad_count_and_tampered_store(self):
        with hdb.db(0) as con:
            for count in (0, -1, 21, True, 1.5):
                with self.assertRaises(ValueError):
                    auth.admin_recovery_generate(con, count)
            con.execute("INSERT OR REPLACE INTO sync_state(key,value) VALUES('admin_recovery_codes','not-json')")
            self.assertEqual(auth.admin_recovery_remaining(con), 0)
            self.assertFalse(auth.admin_recovery_consume(con, "a" * 16))
            self.enable_totp(con)
            con.execute("INSERT OR REPLACE INTO sync_state(key,value) VALUES('admin_recovery_codes','not-json')")
            self.assertFalse(auth.admin_recovery_consume(con, "a" * 16))


class PreauthTests(AuthDBTestCase):
    def test_preauth_one_time(self):
        with hdb.db(0) as con:
            nonce, exp = auth.preauth_create(con, "admin", ttl=60, now=1000)
            self.assertEqual(exp, 1060)
            row = con.execute("SELECT nonce_hash FROM admin_preauth").fetchone()
            self.assertEqual(row["nonce_hash"], auth.hash_token(nonce))
            self.assertNotEqual(row["nonce_hash"], nonce)
            self.assertEqual(auth.preauth_consume(con, nonce, now=1001), "admin")
            self.assertIsNone(auth.preauth_consume(con, nonce, now=1002))
            self.assertIsNone(auth.preauth_consume(con, "unknown", now=1002))

    def test_preauth_expiry(self):
        with hdb.db(0) as con:
            u = self.user(con)
            nonce, _ = auth.preauth_create(
                con, auth.subject_for_user(u["id"]), ttl=60, now=1000,
            )
            self.assertIsNone(auth.preauth_consume(con, nonce, now=1060))
        with hdb.db(0) as con:
            with self.assertRaises(ValueError):
                auth.preauth_create(con, "admin", ttl=0)
            with self.assertRaises(ValueError):
                auth.preauth_create(con, "root", ttl=60)

    def test_preauth_rejects_unknown_and_disabled_users(self):
        with hdb.db(0) as con:
            with self.assertRaises(ValueError):
                auth.preauth_create(con, "user:999", ttl=60, now=1000)
            u = self.user(con)
            auth.update_user(con, u["id"], enabled=False)
            with self.assertRaises(ValueError):
                auth.preauth_create(con, auth.subject_for_user(u["id"]), ttl=60, now=1000)


if __name__ == "__main__":
    unittest.main()
