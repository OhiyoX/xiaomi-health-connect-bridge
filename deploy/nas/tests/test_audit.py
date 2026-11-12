"""Task 7: 审计哈希链、保留与清理的测试。

覆盖创世行、链校验、篡改/断链/链头不一致、保留锚点、密钥与健康数据
脱敏、并发追加，以及管理后台审计钩子的签名。
"""
from __future__ import annotations

import json
import os
import shutil
import sqlite3
import sys
import tempfile
import threading
import time
import unittest
import urllib.parse
from unittest import mock

NAS_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
for path in (NAS_DIR, TESTS_DIR):
    if path not in sys.path:
        sys.path.insert(0, path)

import test_http  # noqa: E402
from test_http import ADMIN_PW, HTTPTestCase, VERIFIER, hidden, json_body  # noqa: E402

from hcb import admin  # noqa: E402
from hcb import audit  # noqa: E402
from hcb import auth  # noqa: E402
from hcb import context  # noqa: E402
from hcb import db as hdb  # noqa: E402

NOW = 1_800_000_000
DAY = 86400


class AuditTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="hcb-audit-")
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
    def fresh_db(self, name="fresh"):
        os.environ["HEALTH_DB"] = os.path.join(self.tmp, name + ".db")
        with hdb.db(0) as con:
            return con.execute("SELECT COUNT(*) FROM access_log").fetchone()[0]

    def seed(self, count=3, *, created_at=None, actor="alice"):
        ids = []
        with hdb.db(0) as con:
            for i in range(count):
                ts = created_at if created_at is not None else NOW + i
                row = audit.append(
                    con, "auth", actor, "auth.login",
                    {"username": actor, "n": i}, "ok", created_at=ts)
                ids.append(row["id"])
        return ids

    def rows(self):
        with hdb.db(0) as con:
            return [dict(r) for r in con.execute(
                "SELECT id, actor, kind, action, tool, result, detail, "
                "prev_hash, hash, created_at FROM access_log ORDER BY id")]

    # ---------------------------------------------------------- genesis/chain
    def test_genesis_row_has_no_prev_and_verifies(self):
        with hdb.db(0) as con:
            row = audit.append(con, "auth", "alice", "auth.login",
                               {"username": "alice"}, "ok",
                               created_at=NOW)
        stored = self.rows()
        self.assertEqual(len(stored), 1)
        self.assertIsNone(stored[0]["prev_hash"])
        self.assertEqual(len(stored[0]["hash"]), 64)
        int(stored[0]["hash"], 16)
        with hdb.db(0) as con:
            result = audit.verify(con)
            head = json.loads(con.execute(
                "SELECT value FROM sync_state WHERE key='audit_head'"
            ).fetchone()["value"])
        self.assertTrue(result["ok"], result)
        self.assertEqual(result["rows"], 1)
        self.assertEqual(head["hash"], stored[0]["hash"])
        self.assertEqual(head["count"], 1)
        self.assertEqual(head["id"], row["id"])

    def test_chain_links_and_verifies(self):
        ids = self.seed(4)
        stored = self.rows()
        self.assertEqual([r["id"] for r in stored], ids)
        for prev, row in zip(stored, stored[1:]):
            self.assertEqual(row["prev_hash"], prev["hash"])
        with hdb.db(0) as con:
            result = audit.verify(con)
        self.assertTrue(result["ok"], result)
        self.assertEqual(result["rows"], 4)
        self.assertEqual(result["head"], stored[-1]["hash"])

    def test_kind_and_tool_are_hashed_and_match_columns(self):
        with hdb.db(0) as con:
            audit.append(con, "mcp", "agent", "mcp.call",
                         {"tool": "query_heart_rate"}, "ok",
                         tool="query_heart_rate", created_at=NOW)
        row = self.rows()[0]
        payload = json.loads(row["detail"])
        self.assertEqual(payload["_kind"], "mcp")
        self.assertEqual(payload["_tool"], "query_heart_rate")
        self.assertEqual(payload["_kind"], row["kind"])
        self.assertEqual(payload["_tool"], row["tool"])
        with hdb.db(0) as con:
            self.assertTrue(audit.verify(con)["ok"])

    def test_column_only_tool_or_kind_change_is_detected(self):
        cases = {
            "tool": ("UPDATE access_log SET tool='other_tool' WHERE id=2", 2),
            "kind": ("UPDATE access_log SET kind='static' WHERE id=1", 1),
        }
        for name, (sql, bad_id) in cases.items():
            with self.subTest(column=name):
                self.fresh_db("column-" + name)
                with hdb.db(0) as con:
                    audit.append(con, "mcp", "agent", "mcp.call",
                                 {"tool": "query_heart_rate"}, "ok",
                                 tool="query_heart_rate", created_at=NOW)
                    audit.append(con, "mcp", "agent", "mcp.call",
                                 {"tool": "sync_now"}, "ok",
                                 tool="sync_now", created_at=NOW + 1)
                with hdb.db(0) as con:
                    con.execute(sql)
                with hdb.db(0) as con:
                    result = audit.verify(con)
                self.assertFalse(result["ok"], result)
                self.assertEqual(result["first_bad_id"], bad_id)
                self.assertIn("kind/tool", result["error"])

    def test_tamper_in_each_hashed_field_is_detected(self):
        cases = {
            "actor": ("UPDATE access_log SET actor='mallory' WHERE id=2", 2),
            "action": ("UPDATE access_log SET action='auth.nothing' WHERE id=2", 2),
            "result": ("UPDATE access_log SET result='denied' WHERE id=1", 1),
            "detail": ("UPDATE access_log SET detail='{\"x\":1}' WHERE id=3", 3),
            "created_at": ("UPDATE access_log SET created_at=created_at+1 WHERE id=2", 2),
            "prev_hash": ("UPDATE access_log SET prev_hash='deadbeef' WHERE id=3", 3),
            "hash": ("UPDATE access_log SET hash='deadbeef' WHERE id=1", 1),
        }
        for name, (sql, bad_id) in cases.items():
            with self.subTest(field=name):
                self.fresh_db(name)
                self.seed(3)
                with hdb.db(0) as con:
                    con.execute(sql)
                with hdb.db(0) as con:
                    result = audit.verify(con)
                self.assertFalse(result["ok"], result)
                self.assertEqual(result["first_bad_id"], bad_id)

    def test_deleted_middle_row_is_a_gap(self):
        self.seed(4)
        with hdb.db(0) as con:
            con.execute("DELETE FROM access_log WHERE id=2")
        with hdb.db(0) as con:
            result = audit.verify(con)
        self.assertFalse(result["ok"], result)
        self.assertIn("gap", result["error"])
        self.assertEqual(result["first_bad_id"], 2)

    def test_head_missing_with_rows_fails_closed(self):
        self.seed(2)
        with hdb.db(0) as con:
            con.execute("DELETE FROM sync_state WHERE key='audit_head'")
        with hdb.db(0) as con:
            result = audit.verify(con)
            with self.assertRaises(audit.AuditError):
                audit.append(con, "auth", "alice", "auth.login", {}, "ok")

    def test_head_mismatch_is_detected(self):
        self.seed(2)
        with hdb.db(0) as con:
            con.execute(
                "UPDATE sync_state SET value=? WHERE key='audit_head'",
                (json.dumps({"id": 2, "hash": "0" * 64, "count": 2}),),
            )
        with hdb.db(0) as con:
            result = audit.verify(con)
        self.assertFalse(result["ok"], result)
        self.assertEqual(result["first_bad_id"], 2)

    def test_append_refuses_to_reseal_after_tamper(self):
        self.seed(2)
        with hdb.db(0) as con:
            con.execute("UPDATE access_log SET result='denied' WHERE id=2")
        with hdb.db(0) as con:
            with self.assertRaises(audit.AuditError):
                audit.append(con, "auth", "alice", "auth.login", {}, "ok")
        self.assertEqual(len(self.rows()), 2)

    def test_failed_append_rolls_back_its_own_writes_only(self):
        self.seed(1)
        with hdb.db(0) as con:
            con.execute(
                "INSERT INTO sync_state(key, value) VALUES('business_tmp','1') "
                "ON CONFLICT(key) DO UPDATE SET value='1'")
            with mock.patch.object(audit, "_save_head",
                                   side_effect=RuntimeError("injected")):
                with self.assertRaises(RuntimeError):
                    audit.append(con, "auth", "alice", "auth.login",
                                 {"username": "alice"}, "ok")
        self.assertEqual(len(self.rows()), 1)
        with hdb.db(0) as con:
            self.assertEqual(con.execute(
                "SELECT value FROM sync_state WHERE key='business_tmp'"
            ).fetchone()["value"], "1")
            self.assertEqual(con.execute(
                "SELECT COUNT(*) FROM sync_state WHERE key='audit_head'"
            ).fetchone()[0], 1)
            self.assertTrue(audit.verify(con)["ok"])

    # ---------------------------------------------------------- retention
    def test_prune_keeps_anchor_and_only_deletes_expired(self):
        old = NOW - 100 * DAY
        ids = self.seed(3, created_at=old)
        with hdb.db(0) as con:
            new = audit.append(con, "auth", "bob", "auth.login",
                               {"username": "bob"}, "ok", created_at=NOW - DAY)
        with hdb.db(0) as con:
            result = audit.prune(con, days=90, now=NOW)
        self.assertEqual(result["deleted"], 2)
        self.assertEqual(result["anchor"]["id"], ids[2])
        stored = self.rows()
        self.assertEqual([r["id"] for r in stored], [ids[2], new["id"], ids[2] + 2])
        self.assertEqual(stored[0]["created_at"], old)
        self.assertEqual(stored[1]["id"], new["id"])
        retention = stored[-1]
        self.assertEqual(retention["action"], "audit.retention")
        payload = json.loads(retention["detail"])
        self.assertEqual(payload["cutoff"], NOW - 90 * DAY)
        self.assertEqual(payload["count"], 2)
        self.assertEqual(payload["anchor"]["id"], ids[2])
        self.assertEqual(payload["anchor"]["hash"], stored[0]["hash"])
        with hdb.db(0) as con:
            self.assertTrue(audit.verify(con)["ok"])
            anchor = json.loads(con.execute(
                "SELECT value FROM sync_state WHERE key='audit_anchor'"
            ).fetchone()["value"])
        self.assertEqual(anchor, {"id": ids[2], "hash": stored[0]["hash"]})

    def test_prune_count_includes_legacy_deleted_rows(self):
        with hdb.db(0) as con:
            con.execute(
                "INSERT INTO access_log(actor, kind, action, result, "
                "created_at) VALUES(?,?,?,?,?)",
                ("old", "static", "mcp.call", "ok", NOW - 200 * DAY))
        self.seed(2, created_at=NOW - 100 * DAY)
        with hdb.db(0) as con:
            result = audit.prune(con, days=90, now=NOW)
        self.assertEqual(result["deleted"], 2)
        with hdb.db(0) as con:
            retention = con.execute(
                "SELECT detail FROM access_log WHERE action='audit.retention'"
            ).fetchone()["detail"]
        self.assertEqual(json.loads(retention)["count"], 2)
        with hdb.db(0) as con:
            self.assertTrue(audit.verify(con)["ok"])

    def test_prune_with_nothing_expired_writes_nothing(self):
        self.seed(2, created_at=NOW)
        with hdb.db(0) as con:
            result = audit.prune(con, days=90, now=NOW)
            self.assertEqual(result["deleted"], 0)
            self.assertEqual(con.execute(
                "SELECT COUNT(*) FROM access_log").fetchone()[0], 2)

    def test_cleanup_reads_retention_days_from_env(self):
        self.seed(2, created_at=NOW - 10 * DAY)
        with mock.patch.dict(os.environ, {"AUDIT_RETENTION_DAYS": "1"}):
            with hdb.db(0) as con:
                result = audit.cleanup(con, now=NOW)
        self.assertGreaterEqual(result["deleted"], 1)
        with hdb.db(0) as con:
            self.assertTrue(audit.verify(con)["ok"])

    # ---------------------------------------------------------- redaction
    def test_secrets_and_health_data_are_redacted(self):
        payload = {
            "password": "hunter2",
            "passToken": "pass-token-value",
            "access_token": "oa_plain_access",
            "refresh_token": "or_plain_refresh",
            "client_secret": "client-secret-value",
            "code": "oc_plain_code",
            "code_verifier": "verifier-value",
            "cookie": "hcb_session=abc",
            "authorization": "Bearer hidden",
            "session_sid": "sid-value",
            "note": "ok",
            "heart_rate": [{"ts": 1, "bpm": 72}],
            "spo2": 98,
            "steps": 1234,
            "nested": {"totp_secret": "JBSWY3DPEHPK3PXP"},
        }
        with hdb.db(0) as con:
            audit.append(con, "auth", "alice", "auth.login", payload, "ok",
                         created_at=NOW)
        detail = self.rows()[0]["detail"]
        for secret in ("hunter2", "pass-token-value", "oa_plain_access",
                       "or_plain_refresh", "client-secret-value",
                       "oc_plain_code", "verifier-value", "hcb_session=abc",
                       "Bearer hidden", "sid-value", "JBSWY3DPEHPK3PXP",
                       "72", "1234"):
            self.assertNotIn(secret, detail)
        self.assertIn("[redacted]", detail)
        self.assertIn("ok", detail)
        with hdb.db(0) as con:
            self.assertTrue(audit.verify(con)["ok"])

    def test_long_values_are_truncated_before_hashing(self):
        with hdb.db(0) as con:
            audit.append(con, "auth", "alice", "auth.login",
                         {"note": "x" * 5000}, "ok", created_at=NOW)
        detail = self.rows()[0]["detail"]
        self.assertLess(len(detail), 1000)
        with hdb.db(0) as con:
            self.assertTrue(audit.verify(con)["ok"])

    # ---------------------------------------------------------- concurrency
    def test_concurrent_append_keeps_one_valid_chain(self):
        with hdb.db(0):
            pass
        errors = []

        def worker(n):
            try:
                for i in range(5):
                    with hdb.db(0) as con:
                        audit.append(con, "mcp", f"agent-{n}", "mcp.call",
                                     {"tool": "query_heart_rate", "i": i}, "ok")
            except Exception as exc:  # noqa: BLE001
                errors.append((n, type(exc).__name__, str(exc)))

        threads = [threading.Thread(target=worker, args=(i,))
                   for i in range(6)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=30)
        self.assertEqual(errors, [])
        rows = self.rows()
        self.assertEqual(len(rows), 30)
        self.assertEqual([r["id"] for r in rows], list(range(1, 31)))
        with hdb.db(0) as con:
            result = audit.verify(con)
        self.assertTrue(result["ok"], result)

    def test_verify_snapshot_survives_concurrent_append(self):
        self.seed(2)
        original = audit._load_anchor
        state = {"triggered": False}

        def hooked(conn):
            if not state["triggered"]:
                state["triggered"] = True
                with hdb.db(0) as other:
                    audit.append(other, "mcp", "agent", "mcp.call",
                                 {"tool": "query_heart_rate"}, "ok")
            return original(conn)

        with hdb.db(0) as con:
            with mock.patch.object(audit, "_load_anchor", side_effect=hooked):
                result = audit.verify(con)
        self.assertTrue(result["ok"], result)
        with hdb.db(0) as con:
            self.assertEqual(con.execute(
                "SELECT COUNT(*) FROM access_log").fetchone()[0], 3)
            self.assertTrue(audit.verify(con)["ok"])

    # ---------------------------------------------------------- legacy rows
    def test_legacy_start_append_sets_anchor(self):
        with hdb.db(0) as con:
            con.execute(
                "INSERT INTO access_log(actor, kind, action, result, "
                "created_at) VALUES(?,?,?,?,?)",
                ("old", "static", "mcp.call", "ok", NOW - DAY))
        with hdb.db(0) as con:
            row = audit.append(con, "auth", "alice", "auth.login",
                               {"username": "alice"}, "ok", created_at=NOW)
            result = audit.verify(con)
            anchor = json.loads(con.execute(
                "SELECT value FROM sync_state WHERE key='audit_anchor'"
            ).fetchone()["value"])
        self.assertTrue(result["ok"], result)
        self.assertEqual(anchor, {"id": row["id"], "hash": row["hash"]})
        self.assertEqual(len(self.rows()), 2)

    def test_unhashed_row_after_chain_is_detected(self):
        self.seed(2)
        with hdb.db(0) as con:
            con.execute(
                "INSERT INTO access_log(actor, kind, action, result, "
                "created_at) VALUES(?,?,?,?,?)",
                ("stray", "static", "mcp.call", "ok", NOW + DAY))
        with hdb.db(0) as con:
            result = audit.verify(con)
        self.assertFalse(result["ok"], result)
        self.assertEqual(result["first_bad_id"], 3)
        self.assertIn("unhashed", result["error"])

    # ---------------------------------------------------------- admin hook
    def test_admin_hook_signature_and_real_audit(self):
        with hdb.db(0) as con:
            audit.record_admin_action(
                con, "admin.test", actor="admin", result="ok",
                detail="id=1")
            audit.audit_event(
                con, "admin.test2", actor="admin", result="denied",
                detail="id=2", payload={"action": "x"})
        rows = self.rows()
        self.assertEqual([r["action"] for r in rows],
                         ["admin.test", "admin.test2"])
        self.assertTrue(all(r["kind"] == "admin" for r in rows))
        self.assertTrue(all(r["hash"] for r in rows))
        self.assertIn("id=1", rows[0]["detail"])
        self.assertEqual(rows[1]["result"], "denied")
        with hdb.db(0) as con:
            audit.audit_event(con, "admin.test3", detail="id=3",
                              payload={"marker": "payload-present"})
        detail = self.rows()[-1]["detail"]
        self.assertIn("payload-present", detail)
        self.assertIn("id=3", detail)
        with hdb.db(0) as con:
            self.assertTrue(audit.verify(con)["ok"])

    def test_admin_module_uses_audit_module(self):
        with hdb.db(0) as con:
            admin.record_admin_action(con, "admin.settings.update",
                                      detail="fetch_interval=600")
        rows = self.rows()
        self.assertEqual(rows[0]["kind"], "admin")
        self.assertEqual(rows[0]["action"], "admin.settings.update")
        self.assertIsNotNone(rows[0]["hash"])
        with hdb.db(0) as con:
            self.assertTrue(audit.verify(con)["ok"])

    def test_list_recent_filters_and_limit(self):
        self.seed(3, actor="alice")
        with hdb.db(0) as con:
            audit.append(con, "mcp", "bob", "mcp.call", {}, "denied")
        with hdb.db(0) as con:
            rows = audit.list_recent(con, limit=2)
            self.assertEqual([r["action"] for r in rows],
                             ["mcp.call", "auth.login"])
            filtered = audit.list_recent(con, limit=10, result="denied")
            self.assertEqual(len(filtered), 1)
            self.assertEqual(filtered[0]["actor"], "bob")


class AuditHTTPTests(HTTPTestCase):
    """HTTP 层接入：登录、同意、令牌、MCP 拒绝都要落审计。"""

    def audit_rows(self):
        with hdb.db(0) as con:
            return audit.list_recent(con, limit=50)

    def test_login_failure_and_success_are_audited_without_password(self):
        status, _text, _ = self.login("admin", "wrong-password-123")
        self.assertEqual(status, 401)
        status, _text, _ = self.login("admin", ADMIN_PW)
        self.assertEqual(status, 303)
        rows = self.audit_rows()
        pairs = {(row["action"], row["result"]) for row in rows}
        self.assertIn(("auth.login", "denied"), pairs)
        self.assertIn(("auth.login", "ok"), pairs)
        blob = json.dumps(rows, ensure_ascii=False)
        self.assertNotIn("wrong-password-123", blob)
        self.assertNotIn(ADMIN_PW, blob)
        with hdb.db(0) as con:
            self.assertTrue(audit.verify(con)["ok"])

    def test_consent_token_and_mcp_denial_are_audited(self):
        _client, _params, tokens = self.consent_and_token(scopes=("health.read",))
        rows = self.audit_rows()
        pairs = {(row["action"], row["result"]) for row in rows}
        self.assertIn(("oauth.consent", "approved"), pairs)
        self.assertIn(("oauth.token.issue", "ok"), pairs)
        status, text, _ = self.mcp(
            tokens["access_token"], method="tools/call",
            params={"name": "sync_now", "arguments": {}})
        self.assertEqual(status, 200, text)
        document = json.loads(text)
        self.assertIn("error", document)
        denied = [row for row in self.audit_rows()
                  if row["action"] == "mcp.call" and row["result"] == "denied"]
        self.assertEqual(len(denied), 1, denied)
        self.assertIn("scope_denied", denied[0]["detail"])
        with hdb.db(0) as con:
            self.assertTrue(audit.verify(con)["ok"])

    def test_ingest_allowed_and_denied_are_audited(self):
        with hdb.db(0) as con:
            auth.static_token_create(
                con, "blocked-ingest", token="blocked-ingest-key",
                tool_policy_json=[])
        ts = 1_700_000_021
        status, text, _ = self.browser.post(
            "/ingest", json_body({"items": [{"type": "steps", "ts": ts, "steps": 5}]}),
            {"Content-Type": "application/json",
             "Authorization": "Bearer blocked-ingest-key"})
        self.assertEqual(status, 403, text)
        status, text, _ = self.browser.post(
            "/ingest", json_body({"items": [{"type": "steps", "ts": ts + 1, "steps": 6}]}),
            {"Content-Type": "application/json",
             "Authorization": "Bearer " + test_http.STATIC_TOKEN})
        self.assertEqual(status, 200, text)
        rows = [row for row in self.audit_rows() if row["action"] == "ingest"]
        self.assertEqual(len(rows), 2, rows)
        by_result = {row["result"]: row for row in rows}
        self.assertEqual(set(by_result), {"denied", "ok"})
        self.assertEqual(by_result["denied"]["actor"], "blocked-ingest")
        self.assertEqual(by_result["ok"]["actor"], "phone")
        self.assertEqual(by_result["denied"]["tool"], "ingest")
        self.assertIn("tool_denied", by_result["denied"]["detail"])
        blob = json.dumps(rows, ensure_ascii=False)
        self.assertNotIn("blocked-ingest-key", blob)
        self.assertNotIn(test_http.STATIC_TOKEN, blob)
        self.assertNotIn('"steps"', blob)
        with hdb.db(0) as con:
            self.assertTrue(audit.verify(con)["ok"])

    def test_login_fails_closed_when_audit_chain_is_broken(self):
        with hdb.db(0) as con:
            before = con.execute("SELECT COUNT(*) FROM sessions").fetchone()[0]
            con.execute("DELETE FROM sync_state WHERE key='audit_head'")
        status, _text, _ = self.login("admin", ADMIN_PW)
        self.assertEqual(status, 500)
        with hdb.db(0) as con:
            after = con.execute("SELECT COUNT(*) FROM sessions").fetchone()[0]
            self.assertEqual(after, before)
            self.assertFalse(audit.verify(con)["ok"])

    def test_refresh_reuse_is_audited(self):
        client, _params, tokens = self.consent_and_token()
        form = {
            "grant_type": "refresh_token",
            "refresh_token": tokens["refresh_token"],
            "client_id": client["client_id"],
        }
        status, text, _ = self.browser.post("/token", form)
        self.assertEqual(status, 200, text)
        status, text, _ = self.browser.post("/token", form)
        self.assertEqual(status, 400, text)
        rows = self.audit_rows()
        pairs = {(row["action"], row["result"]) for row in rows}
        self.assertIn(("oauth.token.refresh", "ok"), pairs)
        self.assertIn(("oauth.token.reuse", "denied"), pairs)
        blob = json.dumps(rows, ensure_ascii=False)
        self.assertNotIn(tokens["refresh_token"], blob)
        with hdb.db(0) as con:
            self.assertTrue(audit.verify(con)["ok"])

    def test_token_issuance_fails_closed_when_audit_broken(self):
        client = self.register()
        params = self.authorize_params(client["client_id"])
        status, page, _ = self.login_and_authorize_page(params)
        self.assertEqual(status, 200, page)
        csrf = hidden(page, "_csrf")
        request = hidden(page, "request")
        status, _text, headers = self.browser.post("/authorize", {
            "_csrf": csrf, "request": request, "decision": "allow",
            "scope": ["health.read", "health.write"]})
        self.assertEqual(status, 302)
        code = urllib.parse.parse_qs(
            urllib.parse.urlsplit(headers["location"]).query)["code"][0]
        with hdb.db(0) as con:
            before = con.execute(
                "SELECT COUNT(*) FROM oauth_tokens").fetchone()[0]
            con.execute("DELETE FROM sync_state WHERE key='audit_head'")
        status, text, _ = self.browser.post("/token", {
            "grant_type": "authorization_code",
            "code": code,
            "client_id": client["client_id"],
            "redirect_uri": self.redirect,
            "code_verifier": VERIFIER,
        })
        self.assertEqual(status, 500, text)
        with hdb.db(0) as con:
            after = con.execute(
                "SELECT COUNT(*) FROM oauth_tokens").fetchone()[0]
        self.assertEqual(after, before)

    def test_consent_denial_is_audited(self):
        client = self.register()
        params = self.authorize_params(client["client_id"])
        status, page, _ = self.login_and_authorize_page(params)
        self.assertEqual(status, 200, page)
        csrf = hidden(page, "_csrf")
        request = hidden(page, "request")
        status, _text, _ = self.browser.post("/authorize", {
            "_csrf": csrf, "request": request, "decision": "deny"})
        self.assertEqual(status, 302)
        denied = [row for row in self.audit_rows()
                  if row["action"] == "oauth.consent"]
        self.assertEqual(len(denied), 1, denied)
        self.assertEqual(denied[0]["result"], "denied")
        with hdb.db(0) as con:
            self.assertTrue(audit.verify(con)["ok"])


if __name__ == "__main__":
    unittest.main()
