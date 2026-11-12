"""Task 8: 管理 CLI（hcb_admin.py）测试。

全部用临时 owner 0 数据库；不连 NAS、不读真实 ``/data``。
覆盖：改密/撤销/清 TOTP/审计写、幂等 bootstrap、只读 verify/status、
缺库失败、以及密码不落 argv/输出。
"""
from __future__ import annotations

import contextlib
import io
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
import unittest
import uuid
from unittest import mock

NAS_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if NAS_DIR not in sys.path:
    sys.path.insert(0, NAS_DIR)

import hcb_admin  # noqa: E402
from hcb import audit, auth, context, totp  # noqa: E402
from hcb import db as hdb  # noqa: E402

OLD_PW = "old-admin-password"
NEW_PW = "new-admin-password"
USER_PW = "user-password-123"


class CliTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="hcb-cli-")
        self.health_db = os.path.join(self.tmp, "health.db")
        self._old_env = os.environ.get("HEALTH_DB")
        self._old_admin_pw = os.environ.pop("HCB_ADMIN_PASSWORD", None)
        os.environ["HEALTH_DB"] = self.health_db
        context.set_owner(0)

    def tearDown(self):
        if self._old_env is None:
            os.environ.pop("HEALTH_DB", None)
        else:
            os.environ["HEALTH_DB"] = self._old_env
        if self._old_admin_pw is None:
            os.environ.pop("HCB_ADMIN_PASSWORD", None)
        else:
            os.environ["HCB_ADMIN_PASSWORD"] = self._old_admin_pw
        context.set_owner(0)
        shutil.rmtree(self.tmp, ignore_errors=True)

    # ---------------------------------------------------------- helpers
    def run_cli(self, *args, stdin="", env=None):
        out, err = io.StringIO(), io.StringIO()
        old_stdin = sys.stdin
        try:
            sys.stdin = io.StringIO(stdin)
            with mock.patch.dict(os.environ, env or {}, clear=False):
                with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                    code = hcb_admin.main(list(args))
        finally:
            sys.stdin = old_stdin
        return code, out.getvalue(), err.getvalue()

    def seed_admin(self, password=OLD_PW):
        with hdb.db(0) as con:
            auth.admin_set_password(con, password)

    def seed_user(self, username="alice", password=USER_PW):
        with hdb.db(0) as con:
            user = auth.create_user(con, username, password)
            return user

    def seed_session(self, subject, revision=0):
        with hdb.db(0) as con:
            sid, _cookie = auth.create_session(con, subject, revision, ua="cli-test")
        return sid

    def seed_session_cookie(self, subject, revision=0):
        with hdb.db(0) as con:
            return auth.create_session(con, subject, revision, ua="cli-test")

    def seed_oauth(self, *, user_id=0, username="admin", revision=0,
                   kind="access", raw=None):
        raw = raw or f"oa_{uuid.uuid4().hex}"
        now = int(time.time())
        with hdb.db(0) as con:
            con.execute(
                "INSERT INTO oauth_tokens(token_hash, prefix, kind, client_id, "
                "family_id, scopes_json, tool_policy_json, window_json, user_id, "
                "username, user_revision, resource, created_at, expires_at, "
                "revoked_at, replaced_by_hash, last_used_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL,NULL,NULL)",
                (auth.hash_token(raw), raw[:12], kind, "client-test",
                 "family-test", '["health.read"]', None, None, user_id,
                 username, revision, "https://health.example.com/mcp",
                 now, now + 3600),
            )
        return raw

    def seed_code(self, *, user_id=0, username="admin"):
        now = int(time.time())
        raw = f"oc_{uuid.uuid4().hex}"
        with hdb.db(0) as con:
            con.execute(
                "INSERT INTO oauth_codes(code_hash, client_id, redirect_uri, "
                "code_challenge, scopes_json, resource, session_sid, user_id, "
                "username, user_revision, created_at, expires_at, consumed_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,NULL)",
                (auth.hash_token(raw), "client-test", "https://example.test/cb",
                 "challenge", '["health.read"]',
                 "https://health.example.com/mcp", "sid", user_id, username,
                 0, now, now + 300),
            )
        return raw

    def sessions(self):
        with hdb.db(0) as con:
            return {row["sid_hash"]: row for row in auth.list_sessions(
                con, include_revoked=True)}

    def audit_rows(self):
        with hdb.db(0) as con:
            return [dict(row) for row in con.execute(
                "SELECT id, actor, kind, action, result, detail FROM access_log "
                "ORDER BY id")]

    def audit_actions(self):
        return [row["action"] for row in self.audit_rows()]

    def connect(self):
        con = sqlite3.connect(self.health_db)
        con.row_factory = sqlite3.Row
        return con

    def count(self, table, where="", params=()):
        con = self.connect()
        try:
            sql = f"SELECT COUNT(*) FROM {table}"
            if where:
                sql += " WHERE " + where
            return int(con.execute(sql, params).fetchone()[0])
        finally:
            con.close()

    # ---------------------------------------------------------- reset-password
    def test_reset_password_changes_hash_and_revokes_admin_state(self):
        self.seed_admin()
        user = self.seed_user("alice")
        admin_sid = self.seed_session("admin")
        user_sid = self.seed_session(auth.subject_for_user(user["id"]),
                                     user["revision"])
        self.seed_oauth(user_id=0, raw="oa_admin_secret_access")
        self.seed_oauth(user_id=user["id"], username="alice",
                        revision=user["revision"], raw="oa_alice_secret_access")
        self.seed_code(user_id=0)
        self.seed_code(user_id=user["id"], username="alice")
        with hdb.db(0) as con:
            auth.preauth_create(con, auth.ADMIN_SUBJECT)
            con.execute("INSERT INTO heart_rate(ts,bpm,source) VALUES(?,?,?)",
                        (1000, 61, "test"))
        before_business = self.count("heart_rate")

        code, out, err = self.run_cli("reset-password", stdin=NEW_PW + "\n")

        self.assertEqual(code, 0, err)
        self.assertNotIn(NEW_PW, out + err)
        with hdb.db(0) as con:
            self.assertTrue(auth.admin_check_password(con, NEW_PW))
            self.assertFalse(auth.admin_check_password(con, OLD_PW))
            rows = {r["sid_hash"]: r for r in auth.list_sessions(
                con, include_revoked=True)}
            self.assertIsNotNone(rows[auth.hash_token(admin_sid)]["revoked_at"])
            self.assertIsNone(rows[auth.hash_token(user_sid)]["revoked_at"])
            admin_tokens = con.execute(
                "SELECT revoked_at FROM oauth_tokens WHERE username='admin'").fetchall()
            self.assertTrue(admin_tokens)
            self.assertTrue(all(r["revoked_at"] is not None for r in admin_tokens))
            alice = con.execute(
                "SELECT revoked_at FROM oauth_tokens WHERE username='alice'").fetchall()
            self.assertTrue(alice)
            self.assertTrue(all(r["revoked_at"] is None for r in alice))
            self.assertEqual(con.execute(
                "SELECT COUNT(*) FROM oauth_codes WHERE user_id=0").fetchone()[0], 0)
            self.assertEqual(con.execute(
                "SELECT COUNT(*) FROM oauth_codes WHERE username='alice'").fetchone()[0], 1)
            self.assertEqual(con.execute(
                "SELECT COUNT(*) FROM admin_preauth").fetchone()[0], 0)
        self.assertEqual(self.count("heart_rate"), before_business)
        self.assertIn("admin.password.reset_cli", self.audit_actions())

        # 新密码确实能过 admin 校验，旧密码不能。
        with hdb.db(0) as con:
            self.assertTrue(auth.admin_check_password(con, NEW_PW))
            self.assertFalse(auth.admin_check_password(con, OLD_PW))

    def test_reset_password_revoke_static_is_opt_in(self):
        self.seed_admin()
        alice = self.seed_user("alice")
        with hdb.db(0) as con:
            auth.static_token_create(con, "admin-key", token="static-admin-secret")
            auth.static_token_create(
                con, "alice-key", token="static-alice-secret",
                owner_user_id=alice["id"])

        code, _out, err = self.run_cli("reset-password", stdin=NEW_PW + "\n")
        self.assertEqual(code, 0, err)
        with hdb.db(0) as con:
            rows = con.execute(
                "SELECT revoked_at FROM agent_tokens ORDER BY label").fetchall()
            self.assertTrue(rows)
            self.assertTrue(all(r["revoked_at"] is None for r in rows))

        code, out, err = self.run_cli(
            "reset-password", "--revoke-static", stdin=NEW_PW + "\n")
        self.assertEqual(code, 0, err)
        self.assertIn("static token", out)
        with hdb.db(0) as con:
            rows = con.execute(
                "SELECT revoked_at FROM agent_tokens ORDER BY label").fetchall()
            self.assertTrue(rows)
            self.assertTrue(all(r["revoked_at"] is not None for r in rows))

    def test_reset_password_rejects_short_password_without_changes(self):
        self.seed_admin()
        before = len(self.audit_rows())
        code, out, err = self.run_cli("reset-password", stdin="short\n")
        self.assertEqual(code, 2)
        self.assertIn("at least 12", err)
        with hdb.db(0) as con:
            self.assertTrue(auth.admin_check_password(con, OLD_PW))
            self.assertFalse(auth.admin_check_password(con, "short"))
        self.assertEqual(len(self.audit_rows()), before)

    def test_reset_password_requires_stdin(self):
        self.seed_admin()
        code, _out, err = self.run_cli("reset-password", stdin="")
        self.assertEqual(code, 2)
        self.assertIn("stdin", err)
        with hdb.db(0) as con:
            self.assertTrue(auth.admin_check_password(con, OLD_PW))

    def test_reset_password_survives_broken_audit_chain(self):
        # 找回 CLI 不能因为审计链损坏而把管理员永久锁在外面；改变仍提交，
        # stderr 给出明确警告，audit-verify 仍退出 1。
        self.seed_admin()
        with hdb.db(0) as con:
            audit.append(con, "auth", "alice", "auth.login", {"n": 1}, "ok")
            con.execute("DELETE FROM sync_state WHERE key=?", (audit.HEAD_KEY,))
        code, out, err = self.run_cli("reset-password", stdin=NEW_PW + "\n")
        self.assertEqual(code, 0, err)
        self.assertIn("audit append failed", err)
        self.assertNotIn(NEW_PW, out + err)
        with hdb.db(0) as con:
            self.assertTrue(auth.admin_check_password(con, NEW_PW))
        code, _out, _err = self.run_cli("audit-verify")
        self.assertEqual(code, 1)

    # ---------------------------------------------------------- reset-totp
    def test_reset_totp_requires_yes_then_clears_all_state(self):
        self.seed_admin()
        _admin_sid, admin_cookie = self.seed_session_cookie("admin")
        self.seed_oauth(user_id=0, raw="oa_admin_totp_secret")
        self.seed_code(user_id=0)
        with hdb.db(0) as con:
            secret = auth.admin_totp_enable(con, secret=totp.generate_secret())
            self.assertTrue(auth.admin_totp_confirm(con, totp.code(secret)))
            auth.admin_recovery_generate(con, 10)
            auth.preauth_create(con, auth.ADMIN_SUBJECT)
        with hdb.db(0) as con:
            session_secret = auth.session_secret(con)
            self.assertIsNotNone(auth.read_session(con, admin_cookie, session_secret))
            self.assertTrue(auth.admin_totp_state(con)["enabled"])
            self.assertEqual(auth.admin_recovery_remaining(con), 10)
            self.assertIsNone(con.execute(
                "SELECT revoked_at FROM oauth_tokens WHERE token_hash=?",
                (auth.hash_token("oa_admin_totp_secret"),)).fetchone()["revoked_at"])

        code, _out, err = self.run_cli("reset-totp")
        self.assertEqual(code, 2)
        self.assertIn("--yes", err)
        with hdb.db(0) as con:
            self.assertTrue(auth.admin_totp_state(con)["enabled"])
            self.assertEqual(auth.admin_recovery_remaining(con), 10)
            self.assertEqual(con.execute(
                "SELECT COUNT(*) FROM admin_preauth").fetchone()[0], 1)

        code, out, err = self.run_cli("reset-totp", "--yes")
        self.assertEqual(code, 0, err)
        self.assertNotIn(secret, out + err)
        with hdb.db(0) as con:
            state = auth.admin_totp_state(con)
            self.assertFalse(state["enabled"])
            self.assertIsNone(state["secret"])
            self.assertEqual(auth.admin_recovery_remaining(con), 0)
            self.assertIsNone(auth.read_session(con, admin_cookie,
                                                auth.session_secret(con)))
            token = con.execute(
                "SELECT revoked_at FROM oauth_tokens WHERE token_hash=?",
                (auth.hash_token("oa_admin_totp_secret"),)).fetchone()
            self.assertIsNotNone(token["revoked_at"])
            self.assertEqual(con.execute(
                "SELECT COUNT(*) FROM oauth_codes WHERE user_id=0").fetchone()[0], 0)
            self.assertEqual(con.execute(
                "SELECT COUNT(*) FROM admin_preauth").fetchone()[0], 0)
            self.assertTrue(auth.admin_check_password(con, OLD_PW))
        self.assertIn("admin.totp.reset_cli", self.audit_actions())

    # ---------------------------------------------------------- revoke-sessions
    def test_revoke_sessions_admin_then_one_user(self):
        self.seed_admin()
        alice = self.seed_user("alice")
        bob = self.seed_user("bob", "bob-password-123")
        admin_sid = self.seed_session("admin")
        alice_sid = self.seed_session(auth.subject_for_user(alice["id"]),
                                      alice["revision"])
        bob_sid = self.seed_session(auth.subject_for_user(bob["id"]),
                                    bob["revision"])

        code, out, err = self.run_cli("revoke-sessions")
        self.assertEqual(code, 0, err)
        self.assertIn("1 session", out)
        rows = self.sessions()
        self.assertIsNotNone(rows[auth.hash_token(admin_sid)]["revoked_at"])
        self.assertIsNone(rows[auth.hash_token(alice_sid)]["revoked_at"])
        self.assertIsNone(rows[auth.hash_token(bob_sid)]["revoked_at"])

        code, out, err = self.run_cli("revoke-sessions", "--user", "alice")
        self.assertEqual(code, 0, err)
        self.assertIn("alice", out)
        rows = self.sessions()
        self.assertIsNotNone(rows[auth.hash_token(alice_sid)]["revoked_at"])
        self.assertIsNone(rows[auth.hash_token(bob_sid)]["revoked_at"])

        code, _out, err = self.run_cli("revoke-sessions", "--user", "nobody")
        self.assertEqual(code, 1)
        self.assertIn("unknown user", err)
        self.assertIsNone(self.sessions()[auth.hash_token(bob_sid)]["revoked_at"])
        self.assertIn("admin.session.revoke_cli", self.audit_actions())

    # ---------------------------------------------------------- bootstrap
    def test_bootstrap_from_env_is_idempotent(self):
        code, out, err = self.run_cli(
            "bootstrap", "--db", self.health_db,
            env={"HCB_ADMIN_PASSWORD": "env-admin-password"})
        self.assertEqual(code, 0, err)
        self.assertIn("created", out)
        with hdb.db(0) as con:
            self.assertTrue(auth.admin_check_password(con, "env-admin-password"))
        first_rows = self.audit_rows()
        self.assertEqual([r["action"] for r in first_rows], ["auth.bootstrap_cli"])

        code, out, err = self.run_cli(
            "bootstrap", "--db", self.health_db,
            env={"HCB_ADMIN_PASSWORD": "another-admin-password"})
        self.assertEqual(code, 0, err)
        self.assertIn("already configured", out)
        with hdb.db(0) as con:
            self.assertTrue(auth.admin_check_password(con, "env-admin-password"))
            self.assertFalse(auth.admin_check_password(con, "another-admin-password"))
        self.assertEqual(len(self.audit_rows()), len(first_rows))

    def test_bootstrap_from_stdin_and_db_after_subcommand(self):
        code, out, err = self.run_cli(
            "bootstrap", "--db", self.health_db,
            stdin="stdin-admin-password\n")
        self.assertEqual(code, 0, err)
        self.assertIn("created", out)
        with hdb.db(0) as con:
            self.assertTrue(auth.admin_check_password(con, "stdin-admin-password"))

    def test_bootstrap_existing_admin_does_not_wait_for_stdin(self):
        self.seed_admin()
        code, out, err = self.run_cli("bootstrap", stdin="")
        self.assertEqual(code, 0, err)
        self.assertIn("already configured", out)
        with hdb.db(0) as con:
            self.assertTrue(auth.admin_check_password(con, OLD_PW))

    def test_bootstrap_rejects_weak_env_password(self):
        code, _out, err = self.run_cli(
            "bootstrap", "--db", self.health_db,
            env={"HCB_ADMIN_PASSWORD": "short"})
        self.assertEqual(code, 2)
        self.assertIn("at least 12", err)
        with hdb.db(0) as con:
            self.assertFalse(auth.admin_has_password(con))

    def test_db_flag_before_subcommand(self):
        code, _out, err = self.run_cli(
            "--db", self.health_db, "bootstrap",
            env={"HCB_ADMIN_PASSWORD": "before-db-password"})
        self.assertEqual(code, 0, err)
        with hdb.db(0) as con:
            self.assertTrue(auth.admin_check_password(con, "before-db-password"))

    # ---------------------------------------------------------- audit/status
    def test_audit_verify_detects_tampering_and_exits_nonzero(self):
        self.seed_admin()
        with hdb.db(0) as con:
            audit.append(con, "auth", "alice", "auth.login", {"n": 1}, "ok")
            audit.append(con, "auth", "alice", "auth.login", {"n": 2}, "ok")
        code, out, _err = self.run_cli("audit-verify")
        self.assertEqual(code, 0)
        payload = json.loads(out)
        self.assertTrue(payload["ok"])
        self.assertEqual(payload["rows"], 2)

        con = self.connect()
        try:
            con.execute("UPDATE access_log SET detail='{\"n\":99}' WHERE id=2")
            con.commit()
        finally:
            con.close()
        code, out, _err = self.run_cli("audit-verify")
        self.assertEqual(code, 1)
        payload = json.loads(out)
        self.assertFalse(payload["ok"])
        self.assertIsNotNone(payload["first_bad_id"])

    def test_audit_verify_is_read_only(self):
        # 只有业务表的库：verify 不能顺手建 access_log/sync_state。
        con = sqlite3.connect(self.health_db)
        try:
            con.execute("CREATE TABLE heart_rate(ts INTEGER PRIMARY KEY, bpm INTEGER)")
            con.commit()
        finally:
            con.close()
        code, _out, _err = self.run_cli("audit-verify")
        self.assertEqual(code, 1)
        con = sqlite3.connect(self.health_db)
        try:
            names = {row[0] for row in con.execute(
                "SELECT name FROM sqlite_master WHERE type='table'")}
        finally:
            con.close()
        self.assertEqual(names, {"heart_rate"})

    def test_status_reports_counts_without_secrets(self):
        self.seed_admin()
        user = self.seed_user("alice")
        self.seed_session("admin")
        self.seed_session(auth.subject_for_user(user["id"]), user["revision"])
        with hdb.db(0) as con:
            auth.static_token_create(con, "phone", token="secret-static-token-123")
        self.seed_oauth(raw="oa_secret_token_456")
        self.seed_code()

        code, out, err = self.run_cli("status")
        self.assertEqual(code, 0, err)
        self.assertNotIn("secret-static-token-123", out + err)
        self.assertNotIn("oa_secret_token_456", out + err)
        self.assertNotIn(OLD_PW, out + err)
        data = json.loads(out)
        self.assertTrue(data["ok"])
        self.assertTrue(data["admin"]["password_set"])
        self.assertFalse(data["admin"]["totp_enabled"])
        self.assertEqual(data["users"]["total"], 1)
        self.assertEqual(data["users"]["enabled"], 1)
        self.assertEqual(data["static_tokens"]["active"], 1)
        self.assertEqual(data["oauth"]["active_tokens"], 1)
        self.assertEqual(data["oauth"]["pending_codes"], 1)
        self.assertEqual(data["sessions"]["active"], 2)
        self.assertTrue(data["audit"]["chain_ok"])
        self.assertNotIn("token_hash", out)
        self.assertNotIn("password_hash", out)

    def test_status_redacts_public_base_credentials(self):
        self.seed_admin()
        secret_url = "https://user:secret@example.test/?token=abc"
        code, out, err = self.run_cli(
            "status", env={"PUBLIC_BASE_URL": secret_url})
        self.assertEqual(code, 0, err)
        self.assertNotIn("secret", out)
        self.assertNotIn("token=abc", out)
        self.assertIn("[redacted", out)

    # ---------------------------------------------------------- missing DB
    def test_existing_commands_fail_when_db_is_missing(self):
        missing = os.path.join(self.tmp, "missing.db")
        cases = [
            (("reset-password", "--db", missing), "pw-1234567890\n"),
            (("reset-totp", "--yes", "--db", missing), ""),
            (("revoke-sessions", "--db", missing), ""),
            (("audit-verify", "--db", missing), ""),
            (("status", "--db", missing), ""),
        ]
        for args, stdin in cases:
            code, _out, err = self.run_cli(*args, stdin=stdin)
            self.assertEqual(code, 2, (args, err))
            self.assertIn("database not found", err)
            self.assertFalse(os.path.exists(missing), args)


class SessionEnvTests(unittest.TestCase):
    """env.example 里的会话/TOTP 旋钮确实被读取；非法值回退默认。"""

    def _read(self, extra):
        env = {**os.environ, **extra}
        proc = subprocess.run(
            [sys.executable, "-c",
             "import json; from hcb import auth; "
             "print(json.dumps([auth.ADMIN_SESSION_TTL, auth.USER_SESSION_TTL, "
             "auth.PREAUTH_TTL, auth.TOTP_WINDOW]))"],
            cwd=NAS_DIR, env=env, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        return json.loads(proc.stdout)

    def test_env_knobs_are_read(self):
        got = self._read({
            "HCB_ADMIN_SESSION_TTL": "60",
            "HCB_USER_SESSION_TTL": "120",
            "HCB_PREAUTH_TTL": "30",
            "HCB_TOTP_WINDOW": "2",
        })
        self.assertEqual(got, [60, 120, 30, 2])

    def test_bad_env_values_fall_back(self):
        got = self._read({
            "HCB_ADMIN_SESSION_TTL": "nope",
            "HCB_USER_SESSION_TTL": "-1",
            "HCB_PREAUTH_TTL": "0",
            "HCB_TOTP_WINDOW": "-3",
        })
        self.assertEqual(got, [4 * 3600, 12 * 3600, 300, 1])


if __name__ == "__main__":
    unittest.main()
