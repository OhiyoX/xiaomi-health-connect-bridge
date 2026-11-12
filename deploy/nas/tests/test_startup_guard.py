"""模板占位 token 必须在启动前被拒绝（Task 8 review fix）。"""
from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import unittest

NAS_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SERVER = os.path.join(NAS_DIR, "server.py")


class PlaceholderTokenTests(unittest.TestCase):
    def run_server(self, *, token="", tokens=""):
        tmp = tempfile.mkdtemp(prefix="hcb-startup-")
        env = {
            **os.environ,
            "HEALTH_DB": os.path.join(tmp, "health.db"),
            "HEALTH_CREDS": os.path.join(tmp, "creds.json"),
            "HEALTH_MCP_TOKEN": token,
            "HEALTH_MCP_TOKENS": tokens,
            "PORT": "0",
            "PUBLIC_BASE_URL": "https://health.example.com",
        }
        try:
            return subprocess.run(
                [sys.executable, SERVER],
                cwd=NAS_DIR, env=env, text=True,
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=10,
            )
        finally:
            import shutil
            shutil.rmtree(tmp, ignore_errors=True)

    def test_legacy_placeholder_is_rejected(self):
        proc = self.run_server(token="__TOKEN__")
        self.assertEqual(proc.returncode, 2, proc.stdout)
        self.assertIn("placeholder", proc.stdout)
        self.assertIn("refusing to start", proc.stdout)
        self.assertNotIn("__TOKEN__", proc.stdout)

    def test_change_me_placeholder_is_rejected(self):
        proc = self.run_server(token="CHANGE_ME")
        self.assertEqual(proc.returncode, 2, proc.stdout)
        self.assertIn("placeholder", proc.stdout)

    def test_named_placeholder_is_rejected(self):
        proc = self.run_server(tokens="phone:__TOKEN__")
        self.assertEqual(proc.returncode, 2, proc.stdout)
        self.assertIn("HEALTH_MCP_TOKENS", proc.stdout)
        self.assertIn("placeholder", proc.stdout)


if __name__ == "__main__":
    unittest.main()
