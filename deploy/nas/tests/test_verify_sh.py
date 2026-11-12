"""Task 9: `deploy/nas/verify.sh` 的本地测试（真实 HTTP 服务 + curl）。

只验证脚本行为：只读检查、PASS/FAIL 输出与退出码、审计链失败能被发现、
输出里不出现任何密码/令牌。不接触 NAS、不启动容器、不读 `.env`。
"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
import unittest
from pathlib import Path

TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
if TESTS_DIR not in sys.path:
    sys.path.insert(0, TESTS_DIR)

from test_admin import AdminBase  # noqa: E402
from test_http import ADMIN_PW, STATIC_TOKEN  # noqa: E402

from hcb import db as hdb  # noqa: E402

NAS_DIR = Path(TESTS_DIR).parent
VERIFY = NAS_DIR / "verify.sh"
CAN_RUN = bool(shutil.which("bash") and shutil.which("curl"))


@unittest.skipUnless(CAN_RUN, "verify.sh needs bash and curl")
class VerifyShTests(AdminBase):
    def run_verify(self, *args, base=None):
        env = {**os.environ}
        env.pop("HCB_AUDIT_DB", None)
        env.pop("HCB_BASE_URL", None)
        env.pop("HCB_SKIP_AUDIT", None)
        return subprocess.run(
            ["bash", str(VERIFY), "--base-url", base or self.base, *args],
            cwd=str(NAS_DIR), env=env, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120,
        )

    def summary(self, output: str) -> str:
        for line in output.splitlines():
            if line.startswith("SUMMARY:"):
                return line
        self.fail("no SUMMARY line in output:\n" + output)

    def test_all_checks_pass(self):
        result = self.run_verify("--audit-db", os.environ["HEALTH_DB"])
        self.assertEqual(result.returncode, 0, result.stdout)
        for label in ("health:", "mcp 401:", "resource metadata:",
                      "as metadata:", "login page:", "admin gate:",
                      "audit chain:"):
            self.assertIn("PASS " + label, result.stdout, result.stdout)
        self.assertIn("FAIL=0", self.summary(result.stdout))
        self.assertNotIn("FAIL ", result.stdout)

    def test_output_has_no_secrets(self):
        result = self.run_verify("--audit-db", os.environ["HEALTH_DB"])
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertNotIn(ADMIN_PW, result.stdout)
        self.assertNotIn(STATIC_TOKEN, result.stdout)
        self.assertNotIn("oa_", result.stdout)
        self.assertNotIn("or_", result.stdout)
        self.assertNotIn("Cookie", result.stdout)

    def test_skip_audit(self):
        result = self.run_verify("--skip-audit")
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("SKIP audit chain:", result.stdout)
        self.assertIn("SKIP=1", self.summary(result.stdout))

    def test_missing_database_is_skipped_not_failed(self):
        missing = os.path.join(self.tmp, "nope.db")
        result = self.run_verify("--audit-db", missing)
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("SKIP audit chain:", result.stdout)
        self.assertIn("PASS=6", self.summary(result.stdout))

    def test_wrong_base_url_fails(self):
        result = self.run_verify(base="http://127.0.0.1:1")
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("FAIL health:", result.stdout)
        self.assertIn("FAIL mcp 401:", result.stdout)
        self.assertIn("FAIL=6", self.summary(result.stdout))

    def test_unknown_argument_is_usage_error(self):
        result = self.run_verify("--nonsense")
        self.assertEqual(result.returncode, 2, result.stdout)
        self.assertIn("unknown argument", result.stdout)

    def test_tampered_audit_chain_fails(self):
        with hdb.db(0) as con:
            con.execute(
                "UPDATE access_log SET result='tampered' "
                "WHERE id=(SELECT MAX(id) FROM access_log WHERE hash IS NOT NULL)")
        result = self.run_verify("--audit-db", os.environ["HEALTH_DB"])
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("FAIL audit chain:", result.stdout)


if __name__ == "__main__":
    unittest.main()
