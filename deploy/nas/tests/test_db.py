"""Task 1: 分区数据库与 schema 的测试。只用标准库。"""
from __future__ import annotations

import io
import json
import os
import shutil
import sqlite3
import sys
import tempfile
import threading
import unittest
import urllib.request
from contextlib import redirect_stdout
from http.server import ThreadingHTTPServer
from unittest import mock

NAS_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if NAS_DIR not in sys.path:
    sys.path.insert(0, NAS_DIR)

from hcb import context  # noqa: E402
from hcb import db as hdb  # noqa: E402
from hcb import schema  # noqa: E402

BUSINESS_TABLES = {
    "heart_rate", "spo2", "steps", "sleep", "calories", "workout", "stress",
    "weight", "sync_state", "raw",
}
GLOBAL_TABLES = {
    # 账号 / 会话 / 认证
    "users", "sessions", "admin_preauth", "agent_tokens",
    # OAuth
    "oauth_clients", "oauth_codes", "oauth_tokens",
    # 审计 / 密钥
    "access_log", "service_secrets",
}
EXPECTED_TABLES = BUSINESS_TABLES | GLOBAL_TABLES


def _tables(path: str) -> set[str]:
    con = sqlite3.connect(path)
    try:
        return {r[0] for r in con.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")}
    finally:
        con.close()


class DBTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="hcb-db-")
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


class TestOwnerContext(DBTestCase):
    def test_default_owner_is_zero(self):
        self.assertEqual(context.owner.get(), 0)

    def test_set_owner_and_reset(self):
        token = context.set_owner(7)
        try:
            self.assertEqual(context.owner.get(), 7)
        finally:
            context.owner.reset(token)
        self.assertEqual(context.owner.get(), 0)

    def test_owner_is_per_thread(self):
        seen = {}

        def worker():
            seen["before"] = context.owner.get()
            context.set_owner(9)
            seen["after"] = context.owner.get()

        t = threading.Thread(target=worker)
        t.start()
        t.join()
        self.assertEqual(seen, {"before": 0, "after": 9})
        self.assertEqual(context.owner.get(), 0)


class TestPartitions(DBTestCase):
    def test_owner_zero_uses_health_db(self):
        with hdb.db(0) as con:
            self.assertEqual(con.execute("PRAGMA database_list").fetchone()["file"], self.health_db)
        self.assertTrue(os.path.isfile(self.health_db))

    def test_user_partition_path_and_dir(self):
        path = hdb.user_partition(7)
        self.assertEqual(path, os.path.join(self.tmp, "data", "users", "7.db"))
        self.assertTrue(os.path.isdir(os.path.dirname(path)))
        self.assertFalse(os.path.exists(path), "user_partition 只建目录，不建库")

    def test_db_creates_user_partition(self):
        with hdb.db(7) as con:
            self.assertEqual(con.execute("PRAGMA database_list").fetchone()["file"],
                             os.path.join(self.tmp, "data", "users", "7.db"))
        self.assertTrue(os.path.isfile(os.path.join(self.tmp, "data", "users", "7.db")))

    def test_db_uses_context_owner_when_omitted(self):
        context.set_owner(7)
        with hdb.db() as con:
            self.assertEqual(con.execute("PRAGMA database_list").fetchone()["file"],
                             os.path.join(self.tmp, "data", "users", "7.db"))

    def test_invalid_owner_rejected(self):
        with self.assertRaises(ValueError):
            hdb.db_path(-1)
        with self.assertRaises(ValueError):
            hdb.user_partition(0)

    def test_owner_isolation(self):
        with hdb.db(0) as con:
            con.execute("INSERT INTO heart_rate(ts, bpm, source) VALUES(1, 60, 'phone')")
        with hdb.db(7) as con:
            con.execute("INSERT INTO heart_rate(ts, bpm, source) VALUES(2, 70, 'phone')")
        with hdb.db(0) as con:
            self.assertEqual([r["ts"] for r in con.execute("SELECT ts FROM heart_rate")], [1])
        with hdb.db(7) as con:
            self.assertEqual([r["ts"] for r in con.execute("SELECT ts FROM heart_rate")], [2])


class TestSchema(DBTestCase):
    def test_global_and_partition_table_sets(self):
        with hdb.db(0) as con0:
            names0 = {r[0] for r in con0.execute(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")}
        with hdb.db(7) as con7:
            names7 = {r[0] for r in con7.execute(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")}
        self.assertEqual(names0, EXPECTED_TABLES)
        self.assertEqual(names7, BUSINESS_TABLES)

    def test_user_version_bits(self):
        with hdb.db(0) as con:
            self.assertEqual(con.execute("PRAGMA user_version").fetchone()[0], 3)
        with hdb.db(7) as con:
            self.assertEqual(con.execute("PRAGMA user_version").fetchone()[0], 1)

    def test_schema_is_memoized_after_first_open(self):
        with hdb.db(0):
            pass
        # 已建好：第二次打开不应再执行 DDL（用坏 DDL 证明它没被执行）
        with mock.patch.object(schema, "BUSINESS_TABLES", "NOT VALID SQL"), \
                mock.patch.object(schema, "GLOBAL_TABLES", "NOT VALID SQL"):
            with hdb.db(0) as con:
                self.assertEqual(con.execute("SELECT COUNT(*) FROM users").fetchone()[0], 0)

    def test_connection_pragmas(self):
        with hdb.db(0) as con:
            self.assertIs(con.row_factory, sqlite3.Row)
            self.assertEqual(con.execute("PRAGMA journal_mode").fetchone()[0].lower(), "wal")
            self.assertEqual(con.execute("PRAGMA foreign_keys").fetchone()[0], 1)
            self.assertEqual(con.execute("PRAGMA busy_timeout").fetchone()[0], 5000)

    def test_commit_on_exit_and_close(self):
        with hdb.db(0) as con:
            con.execute("INSERT INTO heart_rate(ts, bpm, source) VALUES(3, 80, 'phone')")
        con2 = sqlite3.connect(self.health_db)
        try:
            self.assertEqual(con2.execute("SELECT bpm FROM heart_rate WHERE ts=3").fetchone(), (80,))
        finally:
            con2.close()
        with self.assertRaises(sqlite3.ProgrammingError):
            con.execute("SELECT 1")

    def test_ensure_all_covers_existing_user_partitions(self):
        os.makedirs(os.path.join(self.tmp, "data", "users"))
        orphan = os.path.join(self.tmp, "data", "users", "7.db")
        sqlite3.connect(orphan).close()
        with hdb.db(0):
            pass
        schema.ensure_all()
        self.assertEqual(_tables(orphan), BUSINESS_TABLES)

    def test_ensure_all_continues_after_corrupt_partition(self):
        udir = os.path.join(self.tmp, "data", "users")
        os.makedirs(udir)
        with open(os.path.join(udir, "3.db"), "wb") as f:
            f.write(b"this is not a sqlite database")
        sqlite3.connect(os.path.join(udir, "7.db")).close()
        out = io.StringIO()
        with redirect_stdout(out):
            schema.ensure_all()
        self.assertIn("[schema] users/3.db:", out.getvalue())
        self.assertEqual(_tables(os.path.join(udir, "7.db")), BUSINESS_TABLES)


class TestLegacyMigration(DBTestCase):
    def _make_legacy_db(self):
        os.makedirs(os.path.dirname(self.health_db))
        con = sqlite3.connect(self.health_db)
        con.executescript("""
            CREATE TABLE heart_rate (ts INTEGER PRIMARY KEY, bpm INTEGER);
            CREATE TABLE weight (ts INTEGER PRIMARY KEY, kg REAL, origin TEXT);
            CREATE TABLE sync_state (key TEXT PRIMARY KEY, value TEXT);
            INSERT INTO heart_rate(ts, bpm) VALUES(1000, 72);
            INSERT INTO weight(ts, kg, origin) VALUES(2000, 70.5, 'mi');
        """)
        con.commit()
        con.close()

    def test_legacy_rows_preserved_and_tagged(self):
        self._make_legacy_db()
        with hdb.db(0) as con:
            hr = con.execute("SELECT ts, bpm, source FROM heart_rate").fetchall()
            wt = con.execute("SELECT ts, kg, origin, source FROM weight").fetchall()
            pk = [r[1] for r in con.execute("PRAGMA table_info(heart_rate)") if r[5]]
        self.assertEqual([tuple(r) for r in hr], [(1000, 72, "xiaomi-cloud")])
        self.assertEqual([tuple(r) for r in wt], [(2000, 70.5, "mi", "xiaomi-cloud")])
        self.assertEqual(pk, ["ts", "source"])

    def test_legacy_rows_survive_reopen(self):
        self._make_legacy_db()
        with hdb.db(0) as con:
            con.execute("INSERT INTO heart_rate(ts, bpm, source) VALUES(3000, 66, 'phone')")
        with hdb.db(0) as con:
            rows = [tuple(r) for r in con.execute("SELECT ts, bpm FROM heart_rate ORDER BY ts")]
        self.assertEqual(rows, [(1000, 72), (3000, 66)])

    def test_preexisting_old_table_blocks_migration_without_data_loss(self):
        self._make_legacy_db()
        con = sqlite3.connect(self.health_db)
        con.executescript("""
            CREATE TABLE heart_rate_old (marker TEXT);
            INSERT INTO heart_rate_old(marker) VALUES ('keep-me');
        """)
        con.commit()
        con.close()
        with hdb.db(0) as con:
            rows = [tuple(r) for r in con.execute("SELECT ts, bpm FROM heart_rate")]
            cols = [r[1] for r in con.execute("PRAGMA table_info(heart_rate)")]
            tables = {r[0] for r in con.execute(
                "SELECT name FROM sqlite_master WHERE type='table'")}
        self.assertEqual(rows, [(1000, 72)])
        self.assertNotIn("source", cols)
        self.assertIn("heart_rate_old", tables)

    def test_failed_rebuild_rolls_back_after_rename(self):
        self._make_legacy_db()
        bad = ("ts INTEGER NOT NULL, bpm INTEGER, "
               "source TEXT NOT NULL DEFAULT 'xiaomi-cloud', "
               "PRIMARY KEY(ts, source), THIS IS INVALID SQL")
        with mock.patch.dict(schema._LEGACY_MULTI_SOURCE, {"heart_rate": bad}):
            with hdb.db(0) as con:
                rows = [tuple(r) for r in con.execute("SELECT ts, bpm FROM heart_rate")]
                cols = [r[1] for r in con.execute("PRAGMA table_info(heart_rate)")]
                tables = {r[0] for r in con.execute(
                    "SELECT name FROM sqlite_master WHERE type='table'")}
                version = con.execute("PRAGMA user_version").fetchone()[0]
        self.assertEqual(rows, [(1000, 72)], "失败迁移不能丢数据")
        self.assertNotIn("source", cols, "失败迁移要回到迁移前的表结构")
        self.assertNotIn("heart_rate_old", tables, "回滚后不能留下半截 _old 表")
        self.assertEqual(version & 1, 0, "迁移失败不能标记业务 schema 已完成")

    def test_old_sync_state_is_readable(self):
        self._make_legacy_db()
        con = sqlite3.connect(self.health_db)
        con.execute("INSERT INTO sync_state(key, value) VALUES('last_sync', '123')")
        con.commit()
        con.close()
        with hdb.db(0) as con:
            self.assertEqual(con.execute(
                "SELECT value FROM sync_state WHERE key='last_sync'").fetchone()[0], "123")


class TestHealthEndpoint(DBTestCase):
    def test_health_returns_ok_with_temp_db(self):
        import server  # noqa: E402  (导入时不会启动线程)

        srv = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        thread = threading.Thread(target=srv.serve_forever, daemon=True)
        thread.start()
        try:
            url = "http://127.0.0.1:%d/health" % srv.server_address[1]
            with urllib.request.urlopen(url, timeout=5) as resp:
                body = json.loads(resp.read().decode())
                self.assertEqual(resp.status, 200)
        finally:
            srv.shutdown()
            srv.server_close()
        self.assertEqual(body.get("ok"), True)
        self.assertIn("last_sync", body)
        self.assertTrue(os.path.isfile(self.health_db))


if __name__ == "__main__":
    unittest.main()
