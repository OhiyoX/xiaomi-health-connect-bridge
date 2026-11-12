"""N1：``hcb.stats`` 的只读聚合测试。

覆盖：空库/缺表安全、覆盖计数与来源、三种日聚合、睡眠体检标记、
``sync_state`` 解析（含坏 JSON）、审计失败计数、TTL 缓存、分区隔离、
``EXPLAIN QUERY PLAN`` 走索引、格式化助手。
"""
from __future__ import annotations

import json
import os
import shutil
import sys
import tempfile
import time
import unittest

TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
NAS_DIR = os.path.dirname(TESTS_DIR)
for path in (TESTS_DIR, NAS_DIR):
    if path not in sys.path:
        sys.path.insert(0, path)

from hcb import auth  # noqa: E402
from hcb import db as hdb  # noqa: E402
from hcb import stats  # noqa: E402

NOW = 1_800_000_000  # 固定基准：2027-01-15 08:00 UTC = 2027-01-15 16:00 +08


class StatsBase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="hcb-stats-")
        self.saved_db = os.environ.get("HEALTH_DB")
        os.environ["HEALTH_DB"] = os.path.join(self.tmp, "health.db")
        stats.clear_cache()
        # 建库（含账号/审计表）。
        with hdb.db(0):
            pass

    def tearDown(self):
        stats.clear_cache()
        if self.saved_db is None:
            os.environ.pop("HEALTH_DB", None)
        else:
            os.environ["HEALTH_DB"] = self.saved_db
        shutil.rmtree(self.tmp, ignore_errors=True)

    def insert_heart(self, con, rows=10, *, start=None, step=60,
                     bpm=70, source="xiaomi-cloud"):
        base = int(time.time()) if start is None else int(start)
        for index in range(rows):
            con.execute(
                "INSERT OR REPLACE INTO heart_rate(ts, bpm, source) "
                "VALUES(?,?,?)", (base + index * step, bpm, source))


class EmptyAndMissingTests(StatsBase):
    def test_empty_partition_returns_empty_structures(self):
        with hdb.db(0) as con:
            coverage = stats.coverage(con)
            self.assertEqual(len(coverage), len(stats.METRICS))
            for item in coverage:
                self.assertEqual(item["rows"], 0)
                self.assertEqual(item["sources"], [])
                self.assertEqual(item["days30"], 0)
                self.assertEqual(item["pct"], 0.0)
                self.assertIsNone(item["last"])
            self.assertEqual(stats.rows(con),
                             {name: 0 for name, *_ in stats.METRICS})
            series = stats.series(con, "steps", 14)
            self.assertEqual(len(series["points"]), 14)
            self.assertEqual(series["values"], [None] * 14)
            self.assertIsNone(series["latest"])
            self.assertFalse(series["has_data"])
            gaps = stats.sleep_days(con, 7)
            self.assertEqual(len(gaps), 7)
            self.assertTrue(all(item["flags"] == ["missing"]
                                for item in gaps))
            summary = stats.sync_summary(con)
            self.assertIsNone(summary["last_sync"])
            self.assertIsNone(summary["lag_min"])
            self.assertEqual(summary["last_rows"], 0)
            self.assertIsNone(summary["ingest"])
            self.assertFalse(summary["backfill"]["running"])
            self.assertEqual(stats.audit_fail_count(con), 0)
            self.assertEqual(stats.recent(con, "steps"), [])
            self.assertEqual(stats.sources(con, "steps"), [])

    def test_missing_table_is_skipped(self):
        with hdb.db(0) as con:
            con.execute("DROP TABLE heart_rate")
        with hdb.db(0) as con:
            stats.clear_cache()
            names = [item["type"] for item in stats.coverage(con)]
            self.assertNotIn("heart_rate", names)
            self.assertIn("steps", names)
            self.assertNotIn("heart_rate", stats.rows(con))
            self.assertEqual(stats.series(con, "heart_rate", 7)["values"],
                             [None] * 7)
            self.assertEqual(len(stats.series(con, "heart_rate", 7)["points"]),
                             7)
            self.assertEqual(stats.recent(con, "heart_rate"), [])

    def test_unknown_metric_and_table_are_rejected(self):
        with hdb.db(0) as con:
            self.assertEqual(stats.series(con, "no_such", 7)["points"], [])
            self.assertEqual(stats.recent(con, "no_such"), [])
            self.assertEqual(stats.sources(con, "no_such_table"), [])
            self.assertEqual(stats.daily_counts(con, "no_such", 7)["points"], [])


class CoverageTests(StatsBase):
    def test_rows_sources_days_and_last(self):
        now = int(time.time())
        day = 86400
        with hdb.db(0) as con:
            self.insert_heart(con, 5, start=now - 3 * day)
            self.insert_heart(con, 5, start=now - 1 * day, bpm=80)
            self.insert_heart(con, 3, start=now - 1 * day + 3600, bpm=90,
                              source="phone")
            con.execute("INSERT INTO sleep(bed, wake, minutes, deep_min, source) "
                        "VALUES(?,?,?,?,?)",
                        (now - 8 * 3600, now - 3600, 420, 60, "xiaomi-cloud"))
        with hdb.db(0) as con:
            coverage = {item["type"]: item for item in stats.coverage(con)}
            heart = coverage["heart_rate"]
            self.assertEqual(heart["rows"], 13)
            self.assertEqual(sorted(heart["sources"]), ["phone", "xiaomi-cloud"])
            source_rows = {item["source"]: item["rows"]
                           for item in heart["source_rows"]}
            self.assertEqual(source_rows["phone"], 3)
            self.assertEqual(source_rows["xiaomi-cloud"], 10)
            # 两天有数据（now-3d 与 now-1d）
            self.assertEqual(heart["days30"], 2)
            self.assertAlmostEqual(heart["pct"], 2 / 30)
            self.assertGreaterEqual(heart["last"], now - day)
            sleep = coverage["sleep"]
            self.assertEqual(sleep["rows"], 1)
            self.assertEqual(sleep["days30"], 1)
            self.assertEqual(stats.rows(con)["heart_rate"], 13)

    def test_days_parameter_is_clamped(self):
        with hdb.db(0) as con:
            self.assertEqual(len(stats.coverage(con, days=0)[0]["type"]) > 0,
                             True)
            self.assertEqual(len(stats.series(con, "steps", 0)["points"]), 14)
            self.assertEqual(len(stats.series(con, "steps", 400)["points"]), 366)


class SeriesTests(StatsBase):
    def test_steps_sum_heart_avg_sleep_hours(self):
        now = int(time.time())
        day = 86400
        with hdb.db(0) as con:
            for offset, steps_walked in ((0, 1000), (60, 2500)):
                con.execute(
                    "INSERT INTO steps(ts, steps, distance_m, source) "
                    "VALUES(?,?,?,?)", (now - offset, steps_walked, 0, "phone"))
            con.execute("INSERT INTO heart_rate(ts, bpm, source) VALUES(?,?,?)",
                        (now, 60, "xiaomi-cloud"))
            con.execute("INSERT INTO heart_rate(ts, bpm, source) VALUES(?,?,?)",
                        (now - 60, 80, "xiaomi-cloud"))
            con.execute("INSERT INTO sleep(bed, wake, minutes, deep_min, source) "
                        "VALUES(?,?,?,?,?)",
                        (now - day - 8 * 3600, now - day, 450, 90,
                         "xiaomi-cloud"))
        with hdb.db(0) as con:
            steps = stats.series(con, "steps", 14)
            self.assertEqual(steps["latest"], 3500)
            self.assertEqual(steps["unit"], "步")
            self.assertEqual(len(steps["values"]), 14)
            heart = stats.series(con, "heart_rate", 14)
            self.assertEqual(heart["latest"], 70)
            sleep = stats.series(con, "sleep", 14)
            self.assertEqual(len(sleep["points"]), 14)
            self.assertAlmostEqual(sleep["latest"] or 0, 7.5, places=2)
            counts = stats.daily_counts(con, "heart_rate", 14)
            self.assertEqual(counts["latest"], 2)
            self.assertEqual(counts["unit"], "行")

    def test_series_ignores_rows_outside_window(self):
        now = int(time.time())
        with hdb.db(0) as con:
            con.execute("INSERT INTO steps(ts, steps, distance_m, source) "
                        "VALUES(?,?,?,?)", (now - 40 * 86400, 9999, 0, "phone"))
            con.execute("INSERT INTO steps(ts, steps, distance_m, source) "
                        "VALUES(?,?,?,?)", (now, 100, 0, "phone"))
        with hdb.db(0) as con:
            series = stats.series(con, "steps", 14)
            self.assertEqual(series["latest"], 100)
            self.assertNotIn(9999.0, series["values"])


class SleepTests(StatsBase):
    """睡眠体检：按本地日期（UTC+8）对齐，避免跨日偏移。"""

    def midnight(self, days_ago: int) -> int:
        return (stats.day_start(int(time.time())) or 0) - days_ago * 86400

    def insert_sleep(self, con, days_ago: int, hour: int, duration_h: float,
                     *, deep: int = 90, source: str = "xiaomi-cloud"):
        bed = self.midnight(days_ago) + hour * 3600
        minutes = int(duration_h * 60)
        con.execute(
            "INSERT INTO sleep(bed, wake, minutes, deep_min, source) "
            "VALUES(?,?,?,?,?)",
            (bed, bed + minutes * 60, minutes, deep, source))

    def test_flags_missing_short_late_fragile_long(self):
        with hdb.db(0) as con:
            self.insert_sleep(con, 1, 2, 4.0, deep=10)     # 昨天：短睡+晚睡+碎片
            self.insert_sleep(con, 2, 23, 8.0, deep=120)   # 前天：正常
            self.insert_sleep(con, 3, 22, 13.0, deep=200)  # 大前天：偏长
            self.insert_sleep(con, 4, 23, 8.0, source="phone")
        with hdb.db(0) as con:
            days = {item["date"]: item for item in stats.sleep_days(con, 6)}
            self.assertEqual(len(days), 6)
            dates = sorted(days)
            self.assertIn("missing", days[dates[-1]]["flags"])
            yesterday = days[dates[-2]]
            self.assertIn("short", yesterday["flags"])
            self.assertIn("late", yesterday["flags"])
            self.assertIn("fragile", yesterday["flags"])
            self.assertAlmostEqual(yesterday["hours"], 4.0)
            self.assertEqual(dates[-2], stats.fmt_date(
                self.midnight(1) + 2 * 3600))
            normal = days[dates[-3]]
            self.assertEqual(normal["flags"], [])
            self.assertEqual(normal["note"], "正常")
            self.assertIn("long", days[dates[-4]]["flags"])
            self.assertEqual(days[dates[-5]]["source"], "phone")
            for item in days.values():
                self.assertIn("flag_labels", item)
                self.assertIsNone(item["score"])

    def test_invalid_window_is_flagged(self):
        with hdb.db(0) as con:
            now = int(time.time())
            con.execute(
                "INSERT INTO sleep(bed, wake, minutes, deep_min, source) "
                "VALUES(?,?,?,?,?)", (now - 3600, now - 7200, 0, 0,
                                      "xiaomi-cloud"))
        with hdb.db(0) as con:
            item = stats.sleep_days(con, 1)[0]
            self.assertEqual(item["flags"], ["invalid"])

    def test_longest_record_wins_for_the_same_day(self):
        with hdb.db(0) as con:
            self.insert_sleep(con, 1, 3, 1.0, deep=10, source="a")
            self.insert_sleep(con, 1, 5, 8.0, deep=100, source="b")
        with hdb.db(0) as con:
            day = stats.sleep_days(con, 2)[-1]   # 新的在前，取昨天
            self.assertEqual(day["date"], stats.fmt_date(self.midnight(1)))
            self.assertEqual(day["source"], "b")
            self.assertAlmostEqual(day["hours"], 8.0)


class SyncTests(StatsBase):
    def test_sync_backfill_and_ingest(self):
        now = int(time.time())
        with hdb.db(0) as con:
            auth.settings_set(con, "last_sync", str(now - 120))
            auth.settings_set(con, "last_sync_stats",
                              json.dumps({"raw": 10, "heart_rate": 5,
                                          "steps": 2}))
            auth.settings_set(con, "backfill_state", json.dumps({
                "running": True, "days": 30, "slice": 3, "slices": 4,
                "ok": None, "at": "2026-11-03 08:00:00"}))
            auth.settings_set(con, "last_ingest", json.dumps({
                "at": "2026-11-03 07:30:00",
                "accepted": {"workout": 4, "weight": 2}, "rejected": 1}))
            auth.settings_set(con, "fetch_interval_override", "600")
        with hdb.db(0) as con:
            summary = stats.sync_summary(con)
            self.assertEqual(summary["last_sync"], now - 120)
            self.assertEqual(summary["lag_min"], 2)
            self.assertEqual(summary["last_rows"], 17)
            self.assertEqual(summary["interval"], 600)
            self.assertEqual(summary["next_sync"], now - 120 + 600)
            self.assertIs(summary["stale"], False)
            self.assertTrue(summary["healthy"])
            self.assertEqual(summary["ingest"]["accepted_rows"], 6)
            self.assertEqual(summary["ingest"]["rejected"], 1)
            self.assertAlmostEqual(summary["backfill"]["progress"], 0.75)
            self.assertTrue(summary["backfill"]["running"])
            self.assertEqual(summary["backfill"]["days"], 30)
            self.assertEqual(summary["backfill"]["range_to"], stats.fmt_date(now))
            self.assertIsNotNone(summary["backfill"]["started_at"])

    def test_bad_json_and_stale_pipeline(self):
        now = int(time.time())
        with hdb.db(0) as con:
            auth.settings_set(con, "last_sync", str(now - 10 * 3600))
            auth.settings_set(con, "last_sync_stats", "{not json")
            auth.settings_set(con, "backfill_state", "[]")
            auth.settings_set(con, "last_ingest", "null")
        with hdb.db(0) as con:
            summary = stats.sync_summary(con)
            self.assertEqual(summary["last_rows"], 0)
            self.assertEqual(summary["last_stats"], {})
            self.assertIsNone(summary["ingest"])
            self.assertFalse(summary["backfill"]["running"])
            self.assertIs(summary["stale"], True)
            self.assertFalse(summary["healthy"])

    def test_non_numeric_last_sync_is_ignored(self):
        with hdb.db(0) as con:
            auth.settings_set(con, "last_sync", "yesterday")
        with hdb.db(0) as con:
            summary = stats.sync_summary(con)
            self.assertIsNone(summary["last_sync"])
            self.assertIsNone(summary["lag_min"])


class AuditTests(StatsBase):
    def test_counts_only_non_ok_results_in_window(self):
        now = int(time.time())
        with hdb.db(0) as con:
            for result, age in (("ok", 60), ("approved", 60), ("denied", 60),
                                ("error", 3600), ("denied", 3 * 86400),
                                (None, 60)):
                con.execute(
                    "INSERT INTO access_log(actor, kind, action, tool, result, "
                    "detail, created_at) VALUES(?,?,?,?,?,?,?)",
                    ("admin", "admin", "admin.test", None, result, None,
                     now - age))
        with hdb.db(0) as con:
            self.assertEqual(stats.audit_fail_count(con), 3)
            self.assertEqual(stats.audit_fail_count(con, hours=72), 4)
            self.assertEqual(stats.audit_fail_count(con, hours=0), 3)


class CacheTests(StatsBase):
    def test_second_call_is_served_from_cache(self):
        with hdb.db(0) as con:
            self.insert_heart(con, 2)
        with hdb.db(0) as con:
            first = stats.coverage(con)
            self.insert_heart(con, 2, start=int(time.time()) - 7200)
            second = stats.coverage(con)
            self.assertEqual(second, first)  # 还在 TTL 内
            self.assertGreaterEqual(stats.cache_size(), 1)
            stats.clear_cache()
            self.assertEqual(stats.cache_size(), 0)
            third = stats.coverage(con)
            rows = {item["type"]: item["rows"] for item in third}
            self.assertEqual(rows["heart_rate"], 4)

    def test_days_argument_is_part_of_the_key(self):
        with hdb.db(0) as con:
            self.insert_heart(con, 1)
        with hdb.db(0) as con:
            self.assertNotEqual(stats.coverage(con, days=30),
                                stats.coverage(con, days=7))

    def test_separate_partitions_do_not_share_cache(self):
        with hdb.db(0) as con:
            self.insert_heart(con, 3)
        with hdb.db(1) as con:
            pass
        with hdb.db(1) as con:
            self.insert_heart(con, 5, source="phone")
        with hdb.db(0) as con:
            zero = {item["type"]: item["rows"]
                    for item in stats.coverage(con)}
        with hdb.db(1) as con:
            one = {item["type"]: item["rows"]
                   for item in stats.coverage(con)}
        self.assertEqual(zero["heart_rate"], 3)
        self.assertEqual(one["heart_rate"], 5)

    def test_cached_results_are_defensive_copies(self):
        with hdb.db(0) as con:
            self.insert_heart(con, 2)
        with hdb.db(0) as con:
            first = stats.coverage(con)
            first[0]["rows"] = 999999
            second = stats.coverage(con)
            self.assertNotEqual(second[0]["rows"], 999999)


class QueryPlanTests(StatsBase):
    """1.2M 行的表：窗口查询必须走主键索引，不能全表扫。"""

    def plan(self, con, sql, params=()):
        rows = con.execute("EXPLAIN QUERY PLAN " + sql, params).fetchall()
        return " | ".join(str(row[-1]) for row in rows)

    def test_window_queries_use_index(self):
        with hdb.db(0) as con:
            days30 = ("SELECT COUNT(DISTINCT date(ts, 'unixepoch', "
                      "'+8 hours')) FROM heart_rate WHERE ts >= ?")
            detail = self.plan(con, days30, (0,))
            self.assertIn("INDEX", detail.upper())
            self.assertNotIn("SCAN heart_rate\n", detail)
            series = ("SELECT date(ts, 'unixepoch', '+8 hours') AS d, "
                      "AVG(bpm) FROM heart_rate WHERE ts >= ? GROUP BY d")
            self.assertIn("INDEX", self.plan(con, series, (0,)).upper())
            recent = ("SELECT ts, bpm FROM heart_rate ORDER BY ts DESC "
                      "LIMIT 10")
            detail = self.plan(con, recent)
            self.assertTrue("INDEX" in detail.upper() or "PRIMARY" in
                            detail.upper(), detail)

    def test_group_by_source_uses_covering_index(self):
        with hdb.db(0) as con:
            detail = self.plan(
                con,
                "SELECT source, COUNT(*), MIN(ts), MAX(ts) FROM heart_rate "
                "GROUP BY source")
            self.assertIn("INDEX", detail.upper())


class FormatTests(unittest.TestCase):
    def test_format_helpers(self):
        stamp = 1_800_000_000
        self.assertEqual(stats.fmt_date(stamp), "2027-01-15")
        self.assertTrue(stats.fmt_ts(stamp).startswith("2027-01-15 "))
        self.assertEqual(len(stats.fmt_hhmm(stamp)), 5)
        self.assertEqual(stats.fmt_ts(None), "—")
        self.assertEqual(stats.fmt_ts("nope"), "—")
        self.assertEqual(stats.num(1248356), "1,248,356")
        self.assertEqual(stats.num(None), "—")
        self.assertEqual(stats.rel(None), "—")
        self.assertEqual(stats.rel(stamp, now=stamp - 180), "3 分钟后")
        self.assertEqual(stats.rel(stamp, now=stamp + 180), "3 分钟前")
        self.assertEqual(stats.rel(stamp, now=stamp + 5), "刚刚")
        self.assertEqual(stats.rel(stamp, now=stamp + 3 * 3600), "3 小时前")
        self.assertEqual(stats.rel(stamp, now=stamp + 5 * 86400), "5 天前")
        midnight = stats.day_start(stamp)
        self.assertEqual((midnight + 8 * 3600) % 86400, 0)  # UTC+8 的 00:00
        self.assertEqual(stats.fmt_ts(midnight)[-5:], "00:00")
        self.assertEqual(stats.window_start(30, now=stamp),
                         stats.day_start(stamp) - 29 * 86400)

    def test_source_labels(self):
        self.assertEqual(stats.SOURCE_LABELS["xiaomi-cloud"], "小米云")
        for _name, label, _column, _value in stats.METRICS:
            self.assertTrue(label)


class AdminApiContractTests(StatsBase):
    """/admin/api/state 与 stats 的口径不能漂移（契约：admin-ui/API.md §5）。"""

    def test_metric_specs_cover_the_three_api_metrics(self):
        from hcb import admin_api  # 延迟导入：admin 会拉起 web/oauth
        self.assertEqual(set(admin_api._METRIC_DECIMALS),
                         {"steps", "heart_rate", "sleep"})
        for metric in admin_api._METRIC_DECIMALS:
            self.assertIn(metric, stats.SERIES_METRICS)

    def test_state_is_empty_safe_without_health_tables(self):
        from hcb import admin_api
        with hdb.db(0) as con:
            for table in ("heart_rate", "spo2", "steps", "sleep", "calories",
                          "stress", "weight", "workout"):
                con.execute(f"DROP TABLE {table}")
            session = {"sid": "sid-empty", "is_admin": True,
                       "sid_hash": auth.hash_token("sid-empty")}
            state = admin_api.build_state(con, session, "secret")
        self.assertEqual(state["coverage"], [])
        for metric in ("steps", "heart_rate", "sleep"):
            self.assertIsNone(state["metrics"][metric]["latest"])
            self.assertEqual(state["metrics"][metric]["series"],
                             [None] * 90)
        self.assertEqual(state["sleep"], [])
        self.assertEqual(state["overview"]["sync"]["pipeline"], "unknown")

    def test_state_sections_map_stats_output(self):
        from hcb import admin_api
        now = int(time.time())
        with hdb.db(0) as con:
            con.execute("INSERT INTO heart_rate(ts, bpm, source) VALUES(?,?,?)",
                        (now - 60, 70, "apple_health"))
            con.execute("INSERT INTO sleep(bed, wake, minutes, source) "
                        "VALUES(?,?,?,?)",
                        (now - 7 * 3600, now - 3600, 360, "phone"))
            session = {"sid": "sid-for-state", "is_admin": True}
            session["sid_hash"] = auth.hash_token(session["sid"])
            state = admin_api.build_state(con, session, "secret", now=now)
        coverage = {row["type"]: row for row in state["coverage"]}
        self.assertEqual(coverage["heart_rate"]["rows"], 1)
        self.assertEqual(coverage["heart_rate"]["sources"], ["apple_health"])
        self.assertEqual(state["coverage"][0]["lastAt"], now - 60)
        self.assertEqual(state["metrics"]["heart_rate"]["series"][-1], 70)
        self.assertEqual(state["metrics"]["days"], 90)
        self.assertTrue(state["sleep"])
        self.assertEqual(set(state["sleep"][0]),
                         {"date", "bed", "wake", "hours", "score", "source",
                          "flags", "note"})
        self.assertFalse(state["audit"]["rows"])
        self.assertIs(state["audit"]["chainOk"], True)


if __name__ == "__main__":
    unittest.main()
