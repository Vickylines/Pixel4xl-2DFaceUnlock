"""Offline recovery regression tests. All ADB invocations are intercepted."""
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


class RecoveryCleanupTest(unittest.TestCase):
    def setUp(self):
        path = Path(__file__).with_name("restore_legacy_enrollment.py")
        spec = importlib.util.spec_from_file_location("recovery_under_test", path)
        self.tool = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.tool)
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.tool.__file__ = str(self.root / "tools" / path.name)
        backup = self.root / "device-backups/20261008-before-full-install"
        backup.mkdir(parents=True)
        source, runner = b"template_version=3\nsynthetic=fixture\n", b"synthetic-apk"
        (backup / "production-face2d.properties").write_bytes(source)
        (backup / "manifest.json").write_text(json.dumps({
            "production_config_sha256": hashlib.sha256(source).hexdigest(),
            "maintenance_apk_sha256": hashlib.sha256(runner).hexdigest(),
            "deviceSerial": "synthetic-device",
        }), encoding="utf-8")
        (self.root / "test-artifacts").mkdir()
        (self.root / "test-artifacts/enrollment-transfer.apk").write_bytes(runner)
        self.calls = []
        self.fail_uninstall = False
        self.fail_delete = False

    def fake_adb(self, command, **kwargs):
        self.calls.append((command, kwargs))
        if "uninstall" in command and self.fail_uninstall:
            raise subprocess.CalledProcessError(1, command)
        if command[-1].startswith("rm -f ") and self.fail_delete:
            raise subprocess.CalledProcessError(1, command)
        output = b""
        if "exec-out" in command:
            output = b"synthetic-current-enrollment"
        elif command[-1].startswith("stat -c %u "):
            output = b"10123\n"
        elif "install" in command:
            output = b"Success\n"
        elif "instrument" in command:
            # A rejected transfer leaves the staging file for the Python owner to clean.
            output = b"result=FAIL\n"
        return subprocess.CompletedProcess(command, 0, stdout=output, stderr=b"")

    def apply(self):
        with patch.object(self.tool.subprocess, "run", side_effect=self.fake_adb), \
                patch("sys.argv", ["restore_legacy_enrollment.py", "--apply"]), \
                patch("builtins.print"):
            with self.assertRaises((RuntimeError, subprocess.CalledProcessError)):
                self.tool.main()

    def test_uninstall_failure_still_attempts_both_staging_cleanup_steps(self):
        self.fail_uninstall = True
        self.apply()
        commands = [call[0][-1] for call in self.calls]
        self.assertTrue(any(command.startswith("rm -f ") for command in commands))
        self.assertTrue(any(command.startswith("rmdir ") for command in commands))

    def test_delete_failure_still_attempts_directory_cleanup(self):
        self.fail_delete = True
        self.apply()
        self.assertTrue(any(call[0][-1].startswith("rmdir ") for call in self.calls))

    def test_adb_calls_have_a_finite_timeout(self):
        self.apply()
        self.assertTrue(self.calls)
        for _, options in self.calls:
            self.assertGreater(options.get("timeout", 0), 0)
            self.assertLessEqual(options["timeout"], 120)

    def test_verification_only_never_calls_adb(self):
        with patch.object(self.tool.subprocess, "run") as run, \
                patch("sys.argv", ["restore_legacy_enrollment.py"]), patch("builtins.print"):
            self.tool.main()
        run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
