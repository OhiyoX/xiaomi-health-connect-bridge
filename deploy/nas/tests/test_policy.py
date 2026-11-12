"""Task 5: 四层工具权限、时间窗与数据分区测试。只用标准库。

覆盖：每个工具都有 read/write/ingest 分类；scope 层；四层 policy 的交集；
``NULL`` / ``[]`` / 损坏 JSON/形状 fail-closed；参数级规则；四层时间窗
（时区、跨午夜、星期、绝对起止）；admin 只豁免时间窗；``tools/list`` 过滤；
unknown tool；OAuth ``ingest``；owner 0/own 分区。
"""
from __future__ import annotations

import json
import os
import shutil
import sys
import tempfile
import time
import unittest
from datetime import datetime, timedelta, timezone
from unittest import mock
from zoneinfo import ZoneInfo

NAS_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if NAS_DIR not in sys.path:
    sys.path.insert(0, NAS_DIR)

from hcb import auth  # noqa: E402
from hcb import context  # noqa: E402
from hcb import db as hdb  # noqa: E402
from hcb import oauth  # noqa: E402
from hcb import policy  # noqa: E402

import server  # noqa: E402

NOW = 1_700_000_000  # 2023-11-14T22:13:20Z


def ts_at(text: str, tz: str = "Asia/Shanghai") -> int:
    return int(datetime.fromisoformat(text).replace(tzinfo=ZoneInfo(tz)).timestamp())


def oauth_principal(scopes=("health.read", "health.write"), **kw):
    p = {
        "kind": "oauth",
        "auth": "oauth",
        "user_id": 7,
        "username": "alice",
        "owner": 0,
        "data_mode": "shared",
        "is_admin": False,
        "scopes": list(scopes),
        "user_tool_policy": None,
        "client_tool_policy": None,
        "tool_policy": None,
        "user_window": None,
        "client_window": None,
        "window": None,
    }
    p.update(kw)
    p["tool_policies"] = [
        p.get("user_tool_policy"), p.get("client_tool_policy"), p.get("tool_policy")]
    p["windows"] = [
        p.get("user_window"), p.get("client_window"), p.get("window")]
    return p


def static_principal(**kw):
    p = {
        "kind": "static",
        "auth": "static",
        "user_id": 0,
        "label": "phone",
        "owner": 0,
        "data_mode": "admin",
        "is_admin": True,
        "scopes": None,
        "tool_policy_json": None,
        "window_json": None,
    }
    p.update(kw)
    return p


class ToolClassificationTests(unittest.TestCase):
    def test_every_server_tool_is_classified(self):
        names = {tool["name"] for tool in server.tool_list()}
        self.assertTrue(names)
        for name in names:
            self.assertIn(policy.tool_kind(name), {"read", "write", "ingest"}, name)
            self.assertIn(policy.tool_scopes.get(name), {"health.read", "health.write", None})

    def test_exact_read_write_ingest_sets(self):
        names = {tool["name"] for tool in server.tool_list()}
        self.assertEqual(policy.read_tools(), set(oauth.READ_TOOLS))
        self.assertEqual(policy.write_tools(), set(oauth.WRITE_TOOLS))
        self.assertEqual(policy.ingest_tools(), {"ingest"})
        self.assertEqual(
            policy.read_tools() | policy.write_tools() | policy.ingest_tools(), names)

    def test_unknown_tool_kind_is_unknown(self):
        self.assertEqual(policy.tool_kind("no_such_tool"), "unknown")


class ScopeTests(unittest.TestCase):
    def test_read_only_scope_denies_write_tools(self):
        principal = oauth_principal(scopes=("health.read",))
        for name in ("sync_now", "backfill", "sleep_audit"):
            allowed, reason = policy.authorize(principal, name, NOW)
            self.assertFalse(allowed, name)
            self.assertEqual(reason, policy.Reason.SCOPE_DENIED)
        for name in ("health_status", "query_heart_rate", "health_export_csv"):
            allowed, reason = policy.authorize(principal, name, NOW)
            self.assertTrue(allowed, (name, reason))

    def test_health_write_allows_write_tools_only(self):
        principal = oauth_principal(scopes=("health.write",))
        for name in ("sync_now", "backfill", "sleep_audit"):
            allowed, reason = policy.authorize(principal, name, NOW)
            self.assertTrue(allowed, (name, reason))
        for name in ("health_status", "query_heart_rate", "query_sleep"):
            allowed, reason = policy.authorize(principal, name, NOW)
            self.assertFalse(allowed, name)
            self.assertEqual(reason, policy.Reason.SCOPE_DENIED)

    def test_both_scopes_allow_all_non_ingest(self):
        principal = oauth_principal(scopes=("health.read", "health.write"))
        for name in ("health_status", "sync_now", "query_sleep"):
            self.assertTrue(policy.authorize(principal, name, NOW)[0], name)

    def test_oauth_without_scopes_denied(self):
        principal = oauth_principal(scopes=())
        allowed, reason = policy.authorize(principal, "health_status", NOW)
        self.assertFalse(allowed)
        self.assertEqual(reason, policy.Reason.SCOPE_DENIED)


class OAuthIngestTests(unittest.TestCase):
    def test_ingest_always_denied_for_oauth_even_with_null_capabilities(self):
        principal = oauth_principal(scopes=("health.read", "health.write"))
        self.assertIsNone(principal["tool_policies"][2])
        allowed, reason = policy.authorize(principal, "ingest", NOW)
        self.assertFalse(allowed)
        self.assertEqual(reason, policy.Reason.OAUTH_INGEST)

    def test_ingest_denied_for_read_only_and_write_only(self):
        for scopes in (("health.read",), ("health.write",)):
            allowed, reason = policy.authorize(
                oauth_principal(scopes=scopes), "ingest", NOW)
            self.assertFalse(allowed)
            self.assertEqual(reason, policy.Reason.OAUTH_INGEST)

    def test_static_key_can_use_ingest(self):
        allowed, reason = policy.authorize(static_principal(), "ingest", NOW)
        self.assertTrue(allowed, reason)

    def test_non_object_arguments_fail_closed(self):
        principal = static_principal(
            tool_policy_json=json.dumps(["query_heart_rate"]))
        for value in ("not-an-object", [], 3):
            allowed, reason = policy.authorize(
                principal, "query_heart_rate", NOW, args=value)
            self.assertFalse(allowed, value)
            self.assertEqual(reason, policy.Reason.ARGS_DENIED)


class StaticCapabilitiesTests(unittest.TestCase):
    def test_absent_none_and_empty_object(self):
        for value in (None,):
            principal = static_principal(tool_policy_json=value)
            self.assertTrue(policy.authorize(principal, "sync_now", NOW)[0], value)
            self.assertTrue(policy.authorize(principal, "ingest", NOW)[0], value)

    def test_explicit_list_filters(self):
        principal = static_principal(
            tool_policy_json=json.dumps(["health_status", "ingest"]))
        self.assertTrue(policy.authorize(principal, "health_status", NOW)[0])
        self.assertTrue(policy.authorize(principal, "ingest", NOW)[0])
        allowed, reason = policy.authorize(principal, "query_sleep", NOW)
        self.assertFalse(allowed)
        self.assertEqual(reason, policy.Reason.TOOL_DENIED)

    def test_parsed_capabilities_key_filters(self):
        principal = static_principal(capabilities=["health_status"])
        self.assertTrue(policy.authorize(principal, "health_status", NOW)[0])
        self.assertFalse(policy.authorize(principal, "query_sleep", NOW)[0])

    def test_empty_list_denies_all(self):
        principal = static_principal(tool_policy_json="[]")
        for name in ("health_status", "sync_now", "ingest"):
            allowed, reason = policy.authorize(principal, name, NOW)
            self.assertFalse(allowed, name)
            self.assertEqual(reason, policy.Reason.TOOL_DENIED)


class MalformedPolicyTests(unittest.TestCase):
    def test_malformed_json_denies_even_allowed_tool(self):
        for raw in ("{", '{"tool":"health_status"}', '"health_status"', "42"):
            principal = static_principal(tool_policy_json=raw)
            allowed, reason = policy.authorize(principal, "health_status", NOW)
            self.assertFalse(allowed, raw)
            self.assertEqual(reason, policy.Reason.POLICY_MALFORMED, raw)

    def test_bad_rule_shapes_deny(self):
        bad = [
            [1],
            [""],
            [{}],
            [{"tool": ""}],
            [{"tool": "health_status", "args": []}],
            [{"tool": "health_status", "args": "x"}],
            [{"tool": 3}],
        ]
        for value in bad:
            principal = oauth_principal(client_tool_policy=value)
            allowed, reason = policy.authorize(
                principal, "health_status", NOW, args={})
            self.assertFalse(allowed, value)
            self.assertEqual(reason, policy.Reason.POLICY_MALFORMED, value)

    def test_one_malformed_layer_denies_even_if_others_allow(self):
        principal = oauth_principal(
            user_tool_policy=["health_status"],
            client_tool_policy="not-json",
            tool_policy=["health_status"])
        allowed, reason = policy.authorize(principal, "health_status", NOW)
        self.assertFalse(allowed)
        self.assertEqual(reason, policy.Reason.POLICY_MALFORMED)


class PolicyIntersectionTests(unittest.TestCase):
    def test_user_client_token_intersection(self):
        principal = oauth_principal(
            user_tool_policy=["health_status", "query_sleep", "sync_now"],
            client_tool_policy=["health_status", "query_sleep"],
            tool_policy=["health_status", "query_sleep"],
        )
        self.assertTrue(policy.authorize(principal, "health_status", NOW)[0])
        self.assertTrue(policy.authorize(principal, "query_sleep", NOW)[0])

        principal["tool_policy"] = ["health_status"]
        allowed, reason = policy.authorize(principal, "query_sleep", NOW)
        self.assertFalse(allowed)
        self.assertEqual(reason, policy.Reason.TOOL_DENIED)

    def test_any_layer_deny_wins(self):
        for field in ("user_tool_policy", "client_tool_policy", "tool_policy"):
            principal = oauth_principal()
            principal[field] = []
            principal["tool_policies"] = [
                principal[c] if c == field else None
                for c in ("user_tool_policy", "client_tool_policy", "tool_policy")]
            allowed, reason = policy.authorize(principal, "health_status", NOW)
            self.assertFalse(allowed, field)
            self.assertEqual(reason, policy.Reason.TOOL_DENIED, field)

    def test_static_capabilities_and_raw_column_both_apply(self):
        principal = static_principal(
            capabilities=["health_status", "query_sleep"],
            tool_policy_json=json.dumps(["health_status"]))
        self.assertTrue(policy.authorize(principal, "health_status", NOW)[0])
        self.assertFalse(policy.authorize(principal, "query_sleep", NOW)[0])

    def test_static_owner_user_policy_intersects_key_policy(self):
        principal = static_principal(
            user_id=9, owner=9, data_mode="own", is_admin=False,
            user_tool_policy=["health_status", "query_sleep"],
            tool_policy_json=json.dumps(["health_status", "sync_now"]))
        self.assertTrue(policy.authorize(principal, "health_status", NOW)[0])
        self.assertFalse(policy.authorize(principal, "query_sleep", NOW)[0])
        self.assertFalse(policy.authorize(principal, "sync_now", NOW)[0])


class ArgumentPolicyTests(unittest.TestCase):
    def test_argument_whitelist_any_match(self):
        principal = oauth_principal(
            user_tool_policy=[{
                "tool": "query_metric_series",
                "args": {"metric": ["steps", "distance_km"]},
            }])
        self.assertTrue(policy.authorize(
            principal, "query_metric_series", NOW,
            args={"metric": "steps"})[0])
        self.assertTrue(policy.authorize(
            principal, "query_metric_series", NOW,
            args={"metric": "distance_km"})[0])
        allowed, reason = policy.authorize(
            principal, "query_metric_series", NOW, args={"metric": "spo2_avg"})
        self.assertFalse(allowed)
        self.assertEqual(reason, policy.Reason.ARGS_DENIED)

    def test_arg_match_cannot_bypass_a_later_tool_deny(self):
        principal = oauth_principal(
            user_tool_policy=[{
                "tool": "query_metric_series",
                "args": {"metric": ["steps"]},
            }],
            client_tool_policy=["health_status"])
        names = {tool["name"] for tool in policy.allowed_tools(principal)}
        self.assertNotIn("query_metric_series", names)

    def test_argument_required_to_match(self):
        principal = oauth_principal(
            user_tool_policy=[{
                "tool": "query_metric_series",
                "args": {"metric": "steps"},
            }])
        self.assertFalse(policy.authorize(
            principal, "query_metric_series", NOW, args={})[0])
        self.assertTrue(policy.authorize(
            principal, "query_metric_series", NOW,
            args={"metric": "steps", "days": 7})[0])

    def test_bool_is_not_an_integer_match(self):
        principal = oauth_principal(
            user_tool_policy=[{
                "tool": "query_heart_rate",
                "args": {"limit": [1]},
            }])
        self.assertTrue(policy.authorize(
            principal, "query_heart_rate", NOW, args={"limit": 1})[0])
        self.assertFalse(policy.authorize(
            principal, "query_heart_rate", NOW, args={"limit": True})[0])


class WindowBasicsTests(unittest.TestCase):
    def test_null_window_is_unrestricted(self):
        self.assertTrue(policy.window_allow(None, NOW))
        self.assertTrue(policy.windows_allow([None, None, None], NOW))

    def test_timezone_aware(self):
        window = {
            "tz": "Asia/Shanghai",
            "start": "09:00",
            "end": "18:00",
        }
        self.assertTrue(policy.window_allow(
            window, ts_at("2026-11-02T09:00:00")))
        self.assertTrue(policy.window_allow(
            window, ts_at("2026-11-02T17:59:00")))
        self.assertFalse(policy.window_allow(
            window, ts_at("2026-11-02T18:00:00")))
        self.assertFalse(policy.window_allow(
            window, ts_at("2026-11-02T08:59:00")))
        # 同一时刻换时区判断：UTC 02:00 = 上海 10:00。
        self.assertTrue(policy.window_allow(window, int(
            datetime(2026, 11, 2, 2, 0, tzinfo=timezone.utc).timestamp())))

    def test_overnight_window(self):
        window = {
            "tz": "Asia/Shanghai",
            "start": "22:00",
            "end": "06:00",
        }
        self.assertTrue(policy.window_allow(
            window, ts_at("2026-11-02T22:00:00")))
        self.assertTrue(policy.window_allow(
            window, ts_at("2026-11-02T23:30:00")))
        self.assertTrue(policy.window_allow(
            window, ts_at("2026-11-03T05:59:00")))
        self.assertFalse(policy.window_allow(
            window, ts_at("2026-11-03T06:00:00")))
        self.assertFalse(policy.window_allow(
            window, ts_at("2026-11-02T12:00:00")))

    def test_overnight_days_apply_to_start_day(self):
        window = {
            "tz": "Asia/Shanghai",
            "days": [1],  # Monday
            "start": "22:00",
            "end": "06:00",
        }
        # 2026-11-02 is Monday. Early Tuesday belongs to Monday's window.
        self.assertTrue(policy.window_allow(
            window, ts_at("2026-11-02T23:00:00")))
        self.assertTrue(policy.window_allow(
            window, ts_at("2026-11-03T02:00:00")))
        self.assertFalse(policy.window_allow(
            window, ts_at("2026-11-04T02:00:00")))  # Wednesday early

    def test_days_filter(self):
        window = {"tz": "UTC", "days": [1, 3], "start": "00:00", "end": "23:59"}
        monday = int(datetime(2026, 11, 2, 12, tzinfo=timezone.utc).timestamp())
        tuesday = int(datetime(2026, 11, 3, 12, tzinfo=timezone.utc).timestamp())
        self.assertTrue(policy.window_allow(window, monday))
        self.assertFalse(policy.window_allow(window, tuesday))

    def test_absolute_from_until(self):
        window = {
            "tz": "Asia/Shanghai",
            "from": "2026-11-02T00:00:00+08:00",
            "until": "2026-11-03T00:00:00+08:00",
        }
        self.assertFalse(policy.window_allow(
            window, ts_at("2026-11-01T23:59:59")))
        self.assertTrue(policy.window_allow(
            window, ts_at("2026-11-02T00:00:00")))
        self.assertTrue(policy.window_allow(
            window, ts_at("2026-11-03T00:00:00")))
        self.assertFalse(policy.window_allow(
            window, ts_at("2026-11-03T00:00:01")))

    def test_start_and_end_equal_is_all_day(self):
        window = {"tz": "UTC", "days": [1], "start": "00:00", "end": "00:00"}
        monday = int(datetime(2026, 11, 2, 12, tzinfo=timezone.utc).timestamp())
        tuesday = int(datetime(2026, 11, 3, 12, tzinfo=timezone.utc).timestamp())
        self.assertTrue(policy.window_allow(window, monday))
        self.assertFalse(policy.window_allow(window, tuesday))


class MalformedWindowTests(unittest.TestCase):
    def test_bad_window_shapes_deny(self):
        bad = [
            "not-json",
            [],
            42,
            {"tz": "No/Such_Zone"},
            {"start": "09:00", "end": "18:00"},
            {"tz": "UTC", "start": "9:00", "end": "18:00"},
            {"tz": "UTC", "start": "09:00", "end": "25:00"},
            {"tz": "UTC", "start": "09:00"},
            {"tz": "UTC", "end": "18:00"},
            {"tz": "UTC", "days": "1"},
            {"tz": "UTC", "days": [0]},
            {"tz": "UTC", "days": [8]},
            {"tz": "UTC", "days": [True]},
            {"tz": "UTC", "days": []},
            {"tz": "UTC", "from": "yesterday"},
            {"tz": "UTC", "until": "2026-13-01T00:00:00+00:00"},
            {"tz": "UTC", "start": 900, "end": 1800},
            {"tz": "UTC", "start": "09:00", "end": "09:00"},
        ]
        for value in bad:
            self.assertFalse(policy.window_allow(value, NOW), value)
            self.assertFalse(policy.window_allow_validated(value, NOW), value)

    def test_malformed_window_layer_denies(self):
        principal = oauth_principal()
        principal["windows"] = [{"tz": "UTC", "start": "bad", "end": "18:00"}, None, None]
        allowed, reason = policy.authorize(principal, "health_status", NOW)
        self.assertFalse(allowed)
        self.assertEqual(reason, policy.Reason.WINDOW_MALFORMED)

    def test_closed_window_denies(self):
        principal = oauth_principal()
        principal["windows"] = [None, {
            "tz": "UTC", "days": [1], "start": "09:00", "end": "18:00"}, None]
        # NOW = Tuesday 2023-11-14T22:13:20Z.
        allowed, reason = policy.authorize(principal, "health_status", NOW)
        self.assertFalse(allowed)
        self.assertEqual(reason, policy.Reason.WINDOW_CLOSED)

    def test_four_window_layers_intersect(self):
        open_window = {"tz": "UTC", "start": "00:00", "end": "23:59"}
        closed_window = {"tz": "UTC", "start": "03:00", "end": "04:00"}
        principal = oauth_principal()
        principal["windows"] = [open_window, open_window, closed_window]
        allowed, reason = policy.authorize(principal, "health_status", NOW)
        self.assertFalse(allowed)
        self.assertEqual(reason, policy.Reason.WINDOW_CLOSED)

    def test_static_owner_window_intersects_key_window(self):
        open_window = {"tz": "UTC", "start": "00:00", "end": "23:59"}
        closed_window = {"tz": "UTC", "start": "03:00", "end": "04:00"}
        principal = static_principal(
            user_id=9, owner=9, data_mode="own", is_admin=False,
            user_window=closed_window,
            window_json=json.dumps(open_window))
        allowed, reason = policy.authorize(principal, "health_status", NOW)
        self.assertFalse(allowed)
        self.assertEqual(reason, policy.Reason.WINDOW_CLOSED)


class AdminTests(unittest.TestCase):
    def test_human_admin_bypasses_every_window(self):
        closed_window = {"tz": "UTC", "start": "03:00", "end": "04:00"}
        principal = oauth_principal(
            user_id=0, owner=0, is_admin=True, role="admin",
            user_window=closed_window,
            client_window=closed_window,
            window=closed_window)
        self.assertTrue(policy.authorize(principal, "health_status", NOW)[0])

    def test_ownerless_static_key_does_not_bypass_window(self):
        closed_window = {"tz": "UTC", "start": "03:00", "end": "04:00"}
        principal = static_principal(
            user_id=0, owner=0, is_admin=True,
            window_json=json.dumps(closed_window))
        allowed, reason = policy.authorize(principal, "health_status", NOW)
        self.assertFalse(allowed)
        self.assertEqual(reason, policy.Reason.WINDOW_CLOSED)

    def test_unknown_or_session_kind_fails_closed(self):
        for kind in ("session", "bogus", None):
            principal = static_principal()
            principal["kind"] = kind
            allowed, reason = policy.authorize(principal, "health_status", NOW)
            self.assertFalse(allowed, kind)
            self.assertEqual(reason, policy.Reason.PRINCIPAL_INVALID, kind)

    def test_admin_still_bound_by_key_policy(self):
        principal = static_principal(
            user_id=0, owner=0, is_admin=True,
            tool_policy_json=json.dumps(["health_status"]))
        self.assertTrue(policy.authorize(principal, "health_status", NOW)[0])
        allowed, reason = policy.authorize(principal, "sync_now", NOW)
        self.assertFalse(allowed)
        self.assertEqual(reason, policy.Reason.TOOL_DENIED)

    def test_missing_user_id_is_not_admin(self):
        closed_window = {"tz": "UTC", "start": "03:00", "end": "04:00"}
        principal = static_principal(
            user_id=0, owner=0, is_admin=False,
            window_json=json.dumps(closed_window))
        principal.pop("user_id")
        allowed, reason = policy.authorize(principal, "health_status", NOW)
        self.assertFalse(allowed)
        self.assertEqual(reason, policy.Reason.WINDOW_CLOSED)

    def test_admin_still_bound_by_client_and_token_layers(self):
        principal = oauth_principal(
            user_id=0, owner=0, is_admin=True,
            client_tool_policy=["health_status"],
            tool_policy=["health_status"])
        self.assertTrue(policy.authorize(principal, "health_status", NOW)[0])
        self.assertFalse(policy.authorize(principal, "query_sleep", NOW)[0])

    def test_admin_window_still_bypassed_when_other_layers_allow(self):
        principal = oauth_principal(
            user_id=0, owner=0, is_admin=True,
            user_window={"tz": "UTC", "start": "03:00", "end": "04:00"},
            client_window={"tz": "UTC", "start": "03:00", "end": "04:00"},
            window={"tz": "UTC", "start": "03:00", "end": "04:00"})
        self.assertTrue(policy.authorize(principal, "health_status", NOW)[0])


class ToolsListFilterTests(unittest.TestCase):
    def names(self, principal):
        return {tool["name"] for tool in policy.allowed_tools(principal)}

    def test_read_only_lists_no_write_or_ingest(self):
        names = self.names(oauth_principal(scopes=("health.read",)))
        self.assertEqual(names, set(oauth.READ_TOOLS))

    def test_policy_intersection_filters_list(self):
        principal = oauth_principal(
            user_tool_policy=["health_status", "query_sleep"],
            client_tool_policy=["health_status", "query_sleep"],
            tool_policy=["health_status"])
        self.assertEqual(self.names(principal), {"health_status"})

    def test_tool_list_ignores_windows_but_calls_deny(self):
        principal = oauth_principal()
        principal["windows"] = [{"tz": "UTC", "start": "03:00", "end": "04:00"}] * 3
        self.assertIn("health_status", self.names(principal))
        allowed, reason = policy.authorize(principal, "health_status", NOW)
        self.assertFalse(allowed)
        self.assertEqual(reason, policy.Reason.WINDOW_CLOSED)

    def test_admin_list_still_filtered_by_client_and_token(self):
        principal = oauth_principal(
            user_id=0, owner=0, is_admin=True,
            client_tool_policy=["health_status"],
            tool_policy=["health_status", "query_sleep"])
        self.assertEqual(self.names(principal), {"health_status"})

    def test_empty_policy_lists_nothing(self):
        principal = oauth_principal(tool_policy=[])
        self.assertEqual(self.names(principal), set())

    def test_arg_constrained_tool_is_listed_but_call_checks_args(self):
        principal = oauth_principal(tool_policy=[{
            "tool": "query_metric_series",
            "args": {"metric": ["steps"]},
        }])
        names = self.names(principal)
        self.assertIn("query_metric_series", names)
        self.assertNotIn("health_status", names)
        self.assertTrue(policy.authorize(
            principal, "query_metric_series", NOW,
            args={"metric": "steps"})[0])
        allowed, reason = policy.authorize(
            principal, "query_metric_series", NOW, args={"metric": "spo2_avg"})
        self.assertFalse(allowed)
        self.assertEqual(reason, policy.Reason.ARGS_DENIED)

    def test_arg_match_cannot_bypass_a_later_tool_deny(self):
        principal = oauth_principal(
            user_tool_policy=[{
                "tool": "query_metric_series",
                "args": {"metric": ["steps"]},
            }],
            client_tool_policy=["health_status"])
        names = {tool["name"] for tool in policy.allowed_tools(principal)}
        self.assertNotIn("query_metric_series", names)


class OwnerTests(unittest.TestCase):
    def test_shared_user_is_owner_zero(self):
        principal = oauth_principal(user_id=7, owner=0, data_mode="shared")
        self.assertEqual(policy.owner_for(principal), 0)

    def test_own_user_is_own_partition(self):
        principal = oauth_principal(user_id=7, owner=7, data_mode="own")
        self.assertEqual(policy.owner_for(principal), 7)

    def test_admin_is_owner_zero(self):
        self.assertEqual(policy.owner_for(static_principal()), 0)

    def test_owner_falls_back_to_data_mode(self):
        self.assertEqual(policy.owner_for(
            {"kind": "oauth", "user_id": 4, "data_mode": "own"}), 4)
        self.assertEqual(policy.owner_for(
            {"kind": "oauth", "user_id": 4, "data_mode": "shared"}), 0)

    def test_bad_owner_fails_closed_to_zero(self):
        for value in (-1, True, "7", 3.5, None):
            self.assertEqual(policy.owner_for({"owner": value}), 0, value)


class SyncOwnersTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="hcb-sync-")
        self.old_db = os.environ.get("HEALTH_DB")
        os.environ["HEALTH_DB"] = os.path.join(self.tmp, "health.db")
        context.set_owner(0)

    def tearDown(self):
        if self.old_db is None:
            os.environ.pop("HEALTH_DB", None)
        else:
            os.environ["HEALTH_DB"] = self.old_db
        context.set_owner(0)
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_creds_path_is_per_partition(self):
        with hdb.db(0) as con:
            user = auth.create_user(con, "olga", "pw-olga-123456", data_mode="own")
        path = server.creds_path(user["id"])
        self.assertEqual(path, os.path.join(
            os.path.dirname(os.environ["HEALTH_DB"]), "users",
            f"{user['id']}.creds.json"))
        self.assertEqual(server.creds_path(0), server.CREDS_PATH)

    def test_sync_owners_is_zero_plus_own_with_creds(self):
        with hdb.db(0) as con:
            with_creds = auth.create_user(
                con, "olga", "pw-olga-123456", data_mode="own")
            no_creds = auth.create_user(
                con, "nina", "pw-nina-123456", data_mode="own")
            shared = auth.create_user(con, "sam", "pw-sam-123456")
            auth.create_user(con, "off", "pw-off-123456",
                             data_mode="own", enabled=False)
        path = server.creds_path(with_creds["id"])
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:
            json.dump({"userId": "x", "passToken": "y"}, f)
        self.assertEqual(server._sync_owners(), [0, with_creds["id"]])
        self.assertNotIn(no_creds["id"], server._sync_owners())
        self.assertNotIn(shared["id"], server._sync_owners())

    def test_sync_once_passes_owner_to_fetch(self):
        with hdb.db(0) as con:
            user = auth.create_user(con, "olga", "pw-olga-123456", data_mode="own")
        seen = {}

        def fake(con, start, end, owner=None):
            seen["owner"] = owner
            return {"ok": 1}

        with mock.patch.object(server, "_sync_window_rows", fake):
            self.assertEqual(server.sync_once(3, owner=user["id"]), {"ok": 1})
        self.assertEqual(seen["owner"], user["id"])


class RpcIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="hcb-policy-")
        self.old_db = os.environ.get("HEALTH_DB")
        os.environ["HEALTH_DB"] = os.path.join(self.tmp, "health.db")
        context.set_owner(0)

    def tearDown(self):
        if self.old_db is None:
            os.environ.pop("HEALTH_DB", None)
        else:
            os.environ["HEALTH_DB"] = self.old_db
        context.set_owner(0)
        shutil.rmtree(self.tmp, ignore_errors=True)

    def call(self, principal, name, arguments=None):
        return server.rpc({
            "jsonrpc": "2.0", "id": 1, "method": "tools/call",
            "params": {"name": name, "arguments": arguments or {}},
        }, principal)

    def test_unknown_tool_denied(self):
        out = self.call(static_principal(), "no_such_tool")
        self.assertEqual(out["error"]["code"], -32003)
        self.assertIn("unknown_tool", out["error"]["message"])

    def test_policy_denial_returns_minus_32003(self):
        principal = static_principal(
            tool_policy_json=json.dumps(["health_status"]))
        out = self.call(principal, "query_heart_rate")
        self.assertEqual(out["error"]["code"], -32003)
        self.assertIn(policy.Reason.TOOL_DENIED.value, out["error"]["message"])

    def test_non_object_arguments_denied_over_rpc(self):
        out = server.rpc({
            "jsonrpc": "2.0", "id": 1, "method": "tools/call",
            "params": {"name": "query_heart_rate", "arguments": ["bad"]},
        }, static_principal(tool_policy_json=json.dumps(["query_heart_rate"])))
        self.assertEqual(out["error"]["code"], -32003)
        self.assertIn(policy.Reason.ARGS_DENIED.value, out["error"]["message"])

    def test_write_only_oauth_with_null_policy_denies_read_tool(self):
        principal = oauth_principal(scopes=("health.write",))
        self.assertEqual(principal["tool_policies"], [None, None, None])
        out = self.call(principal, "health_status")
        self.assertEqual(out["error"]["code"], -32003)
        self.assertIn(policy.Reason.SCOPE_DENIED.value, out["error"]["message"])

    def test_window_denial_includes_bounds_and_timezone(self):
        principal = oauth_principal()
        principal["windows"] = [{
            "tz": "UTC", "start": "03:00", "end": "04:00"}] * 3
        out = self.call(principal, "health_status")
        self.assertEqual(out["error"]["code"], -32003)
        self.assertIn("03:00-04:00", out["error"]["message"])
        self.assertIn("UTC", out["error"]["message"])

    def test_disabled_user_static_key_does_not_authenticate(self):
        with hdb.db(0) as con:
            user = auth.create_user(
                con, "olga", "pw-olga-123456", data_mode="own")
            auth.static_token_create(
                con, "olga-key", token="olga-disabled-key",
                owner_user_id=user["id"])
            auth.update_user(con, user["id"], enabled=False)
        self.assertIsNone(server.resolve_principal("Bearer olga-disabled-key"))

    def test_oauth_ingest_denied_before_tool_runs(self):
        out = self.call(oauth_principal(), "ingest", {"items": []})
        self.assertEqual(out["error"]["code"], -32003)
        self.assertIn("static", out["error"]["message"].lower())

    def test_tools_list_filtered_by_policy(self):
        principal = static_principal(
            tool_policy_json=json.dumps(["health_status"]))
        out = server.rpc({
            "jsonrpc": "2.0", "id": 1, "method": "tools/list"}, principal)
        self.assertEqual([t["name"] for t in out["result"]["tools"]], ["health_status"])

    def test_own_and_shared_partitions_do_not_mix(self):
        # 固定到上海时区的中午，避免 UTC/本机时区跨日导致日期不匹配。
        now = int(datetime(2026, 11, 2, 12, 0,
                           tzinfo=ZoneInfo("Asia/Shanghai")).timestamp())
        with hdb.db(0) as con:
            con.execute(
                "INSERT OR REPLACE INTO heart_rate(ts,bpm,source) VALUES(?,?,?)",
                (now, 61, "owner-zero"))
        with hdb.db(5) as con:
            con.execute(
                "INSERT OR REPLACE INTO heart_rate(ts,bpm,source) VALUES(?,?,?)",
                (now, 55, "owner-five"))

        shared = static_principal(user_id=2, owner=0, data_mode="shared",
                                  is_admin=False)
        own = static_principal(user_id=5, owner=5, data_mode="own",
                               is_admin=False)
        args = {"start_date": "2026-11-02", "end_date": "2026-11-02"}
        shared_out = self.call(shared, "query_heart_rate", args)
        own_out = self.call(own, "query_heart_rate", args)
        shared_text = shared_out["result"]["content"][0]["text"]
        own_text = own_out["result"]["content"][0]["text"]
        self.assertIn("owner-zero", shared_text)
        self.assertNotIn("owner-five", shared_text)
        self.assertIn("owner-five", own_text)
        self.assertNotIn("owner-zero", own_text)

    def test_none_principal_is_denied(self):
        allowed, reason = policy.authorize(None, "health_status", NOW)
        self.assertFalse(allowed)
        self.assertEqual(reason, policy.Reason.PRINCIPAL_INVALID)
        out = self.call(None, "health_status")
        self.assertEqual(out["error"]["code"], -32003)
        out = server.rpc({
            "jsonrpc": "2.0", "id": 1, "method": "tools/list"}, None)
        self.assertEqual(out["result"]["tools"], [])

    def test_anonymous_principal_keeps_open_mode_working(self):
        anonymous = {"kind": "anonymous", "auth": "none", "owner": 0,
                     "is_admin": False, "scopes": None, "username": "anonymous"}
        out = server.rpc({
            "jsonrpc": "2.0", "id": 1, "method": "tools/list"}, anonymous)
        self.assertTrue(out["result"]["tools"])
        out = self.call(anonymous, "health_status")
        self.assertIn("result", out)


if __name__ == "__main__":
    unittest.main()
