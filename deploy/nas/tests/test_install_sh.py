"""Task 8: install.sh 的本地无 Docker 测试。

用临时部署目录 + 假 ``docker``/``curl`` 验证：备份、同步 hcb/ 与
hcb_admin.py、chmod、/health 与 /mcp 401 检查、失败回滚、--rollback。
不接触 NAS、不启动容器。
"""
from __future__ import annotations

import os
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

NAS_DIR = Path(__file__).resolve().parent.parent
INSTALL = NAS_DIR / "install.sh"

DOCKER_STUB = """#!/usr/bin/env bash
set -eu
if [ "${1:-}" = "compose" ] && [ "${2:-}" = "version" ]; then
    exit 0
fi
printf '%s\\n' "$*" >> "${STUB_LOG:?}"
exit 0
"""

CURL_STUB = """#!/usr/bin/env bash
set -eu
case " $* " in
  *" -w "*)
    if [ "${CURL_MODE:-ok}" = "fail_mcp" ]; then printf '200'; else printf '401'; fi
    exit 0
    ;;
esac
if [ "${CURL_MODE:-ok}" = "fail_health" ]; then
    exit 1
fi
printf '{"ok": true, "last_sync": ""}\\n'
"""


class InstallShTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="hcb-install-"))
        self.install = INSTALL
        self.deploy = self.tmp / "deploy"
        self.app = self.deploy / "app"
        (self.app / "hcb").mkdir(parents=True)
        (self.app / "server.py").write_text("OLD SERVER\n", encoding="utf-8")
        (self.app / "hcb" / "stale.py").write_text("OLD MODULE\n", encoding="utf-8")
        (self.deploy / "docker-compose.yaml").write_text(
            "services: {}\n", encoding="utf-8")
        self.env_file = self.deploy / ".env"
        self.secret = "DO-NOT-PRINT-THIS-SECRET"
        self.env_file.write_text(f"HEALTH_MCP_TOKEN={self.secret}\n", encoding="utf-8")
        self.stub = self.tmp / "bin"
        self.stub.mkdir()
        for name, text in (("docker", DOCKER_STUB), ("curl", CURL_STUB)):
            path = self.stub / name
            path.write_text(text, encoding="utf-8")
            path.chmod(0o755)
        self.log = self.tmp / "docker.log"
        self.base_env = {
            **os.environ,
            "PATH": f"{self.stub}:{os.environ.get('PATH', '')}",
            "STUB_LOG": str(self.log),
            "HCB_DEPLOY_DIR": str(self.deploy),
            "HCB_HEALTH_URL": "http://127.0.0.1:18927/health",
            "HCB_MCP_URL": "http://127.0.0.1:18927/mcp",
            "HCB_HEALTH_TIMEOUT": "1",
            "HCB_BACKUP_KEEP": "5",
        }
        self.base_env.pop("HCB_SYNC_COMPOSE", None)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def make_source_copy(self):
        """复制一份源码，便于模拟不可读模块而不改真实 worktree。"""
        src = self.tmp / "source"
        src.mkdir()
        for name in ("install.sh", "server.py", "hcb_admin.py",
                     "docker-compose.yaml"):
            shutil.copy2(NAS_DIR / name, src / name)
        shutil.copytree(
            NAS_DIR / "hcb", src / "hcb",
            ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
        (src / "install.sh").chmod(0o755)
        self.install = src / "install.sh"
        return src

    def run_install(self, *args, env=None):
        merged = {**self.base_env, **(env or {})}
        return subprocess.run(
            ["bash", str(self.install), *args],
            cwd=self.tmp, env=merged, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60,
        )

    def backups(self):
        root = self.deploy / "backups"
        return sorted(root.glob("app-*")) if root.exists() else []

    def mode(self, path):
        return stat.S_IMODE(path.stat().st_mode)

    def test_install_syncs_backs_up_and_smoke_checks(self):
        proc = self.run_install()
        self.assertEqual(proc.returncode, 0, proc.stdout)
        self.assertNotIn(self.secret, proc.stdout)
        self.assertIn("smoke: /health ok", proc.stdout)

        self.assertEqual((self.app / "server.py").read_text(encoding="utf-8"),
                         (NAS_DIR / "server.py").read_text(encoding="utf-8"))
        self.assertTrue((self.app / "hcb_admin.py").is_file())
        self.assertTrue((self.app / "hcb" / "__init__.py").is_file())
        self.assertFalse((self.app / "hcb" / "stale.py").exists())
        # 共享 SPA 静态资源随 hcb/ 一起部署，权限同样是 600/700。
        admin_ui = self.app / "hcb" / "admin_ui"
        self.assertEqual(self.mode(admin_ui), 0o700)
        self.assertEqual(self.mode(admin_ui / "index.html"), 0o600)
        self.assertEqual(self.mode(admin_ui / "adapter.js"), 0o600)
        self.assertEqual(
            (admin_ui / "index.html").read_text(encoding="utf-8"),
            (NAS_DIR / "hcb" / "admin_ui" / "index.html").read_text(encoding="utf-8"))
        self.assertEqual(self.mode(self.app / "server.py"), 0o600)
        self.assertEqual(self.mode(self.app / "hcb_admin.py"), 0o600)
        self.assertEqual(self.mode(self.app / "hcb" / "__init__.py"), 0o600)
        self.assertEqual(self.mode(self.app), 0o700)
        self.assertEqual(self.mode(self.app / "hcb"), 0o700)
        self.assertEqual(self.mode(self.env_file), 0o600)
        self.assertEqual(self.env_file.read_text(encoding="utf-8"),
                         f"HEALTH_MCP_TOKEN={self.secret}\n")

        backups = self.backups()
        self.assertEqual(len(backups), 1)
        old_server = backups[0] / "app" / "server.py"
        self.assertEqual(old_server.read_text(encoding="utf-8"), "OLD SERVER\n")
        self.assertTrue((backups[0] / "docker-compose.yaml").is_file())
        self.assertTrue((backups[0] / "MANIFEST").is_file())
        self.assertIn("up -d --force-recreate",
                      self.log.read_text(encoding="utf-8"))

    def test_backup_failure_aborts_without_deploy(self):
        (self.deploy / "backups").write_text("not a directory\n", encoding="utf-8")
        proc = self.run_install()
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("backup failed", proc.stdout)
        self.assertEqual((self.app / "server.py").read_text(encoding="utf-8"),
                         "OLD SERVER\n")
        self.assertFalse(self.log.exists()
                         and "up -d --force-recreate" in self.log.read_text(encoding="utf-8"))

    def test_unreadable_module_rolls_back(self):
        if os.geteuid() == 0:
            self.skipTest("chmod 000 is not effective as root")
        src = self.make_source_copy()
        unreadable = src / "hcb" / "auth.py"
        unreadable.chmod(0o000)
        proc = self.run_install()
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("sync failed", proc.stdout)
        self.assertIn("restoring backup", proc.stdout)
        self.assertEqual((self.app / "server.py").read_text(encoding="utf-8"),
                         "OLD SERVER\n")
        self.assertEqual((self.app / "hcb" / "stale.py").read_text(encoding="utf-8"),
                         "OLD MODULE\n")
        self.assertFalse((self.app / "hcb_admin.py").exists())
        self.assertTrue(self.backups())

    def test_install_prunes_stale_admin_ui_files(self):
        stale = self.app / "hcb" / "admin_ui"
        stale.mkdir(parents=True)
        (stale / "index.html").write_text("OLD INDEX\n", encoding="utf-8")
        (stale / "legacy.css").write_text("OLD CSS\n", encoding="utf-8")
        proc = self.run_install()
        self.assertEqual(proc.returncode, 0, proc.stdout)
        self.assertFalse((stale / "legacy.css").exists())
        self.assertEqual(
            (stale / "index.html").read_text(encoding="utf-8"),
            (NAS_DIR / "hcb" / "admin_ui" / "index.html").read_text(encoding="utf-8"))
        self.assertTrue((stale / "admin.css").is_file())
        backup_ui = self.backups()[0] / "app" / "hcb" / "admin_ui"
        self.assertEqual((backup_ui / "index.html").read_text(encoding="utf-8"),
                         "OLD INDEX\n")
        self.assertTrue((backup_ui / "legacy.css").is_file())

    def test_missing_admin_ui_aborts_before_deploy(self):
        src = self.make_source_copy()
        shutil.rmtree(src / "hcb" / "admin_ui")
        proc = self.run_install()
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("admin_ui", proc.stdout)
        self.assertEqual((self.app / "server.py").read_text(encoding="utf-8"),
                         "OLD SERVER\n")
        self.assertFalse(self.backups())

    def test_install_is_idempotent(self):
        first = self.run_install()
        second = self.run_install()
        self.assertEqual(first.returncode, 0, first.stdout)
        self.assertEqual(second.returncode, 0, second.stdout)
        self.assertEqual(len(self.backups()), 2)
        self.assertEqual((self.app / "server.py").read_text(encoding="utf-8"),
                         (NAS_DIR / "server.py").read_text(encoding="utf-8"))

    def test_failed_smoke_restores_backup(self):
        proc = self.run_install(env={"CURL_MODE": "fail_health"})
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("rollback", proc.stdout.lower())
        self.assertEqual((self.app / "server.py").read_text(encoding="utf-8"),
                         "OLD SERVER\n")
        self.assertEqual((self.app / "hcb" / "stale.py").read_text(encoding="utf-8"),
                         "OLD MODULE\n")
        self.assertTrue(self.backups())

    def test_explicit_rollback_restores_selected_backup(self):
        first = self.run_install()
        self.assertEqual(first.returncode, 0, first.stdout)
        backup = self.backups()[0]
        (self.app / "server.py").write_text("BROKEN\n", encoding="utf-8")
        proc = self.run_install("--rollback", str(backup))
        self.assertEqual(proc.returncode, 0, proc.stdout)
        self.assertIn("rollback complete", proc.stdout)
        self.assertEqual((self.app / "server.py").read_text(encoding="utf-8"),
                         "OLD SERVER\n")

    def test_help_does_not_need_docker_or_deploy_dir(self):
        proc = subprocess.run(
            ["bash", str(INSTALL), "--help"], cwd=self.tmp,
            env={**os.environ,
                 "PATH": f"{self.stub}:{os.environ.get('PATH', '')}"},
            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            timeout=30,
        )
        self.assertEqual(proc.returncode, 0, proc.stdout)
        self.assertIn("--rollback", proc.stdout)


if __name__ == "__main__":
    unittest.main()
