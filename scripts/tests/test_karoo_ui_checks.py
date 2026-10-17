"""Regression checks for offline boot deadlines, device guards and false-green instrumentation."""

import contextlib
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "run-karoo-ui-checks.py"
spec = importlib.util.spec_from_file_location("karoo_ui_checks", SCRIPT)
checks = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checks)


class InstrumentationResultTests(unittest.TestCase):
    def result(self, output, code=0):
        return subprocess.CompletedProcess([], code, output)

    def test_accepts_explicit_clean_pass(self):
        self.assertTrue(checks.instrumentation_passed(self.result("PASS: checks completed\n")))
        self.assertTrue(checks.instrumentation_passed(self.result("PASS: checks completed\nINSTRUMENTATION_CODE: -1\n")))

    def test_adb_exit_zero_does_not_hide_failure(self):
        for output in ["FAIL: assertion failed\n", "", "INSTRUMENTATION_CODE: 0\n",
                       "PASS: preliminary\nFAIL: later assertion\n",
                       "PASS: preliminary\nINSTRUMENTATION_RESULT: shortMsg=Process crashed.\n",
                       "INSTRUMENTATION_FAILED: runner not registered\n"]:
            with self.subTest(output=output):
                self.assertFalse(checks.instrumentation_passed(self.result(output)))
        self.assertFalse(checks.instrumentation_passed(self.result("PASS: checks completed\n", 124)))

    def test_hung_command_is_bounded_and_keeps_partial_output(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "hung.log"
            with patch.object(checks.subprocess, "run", side_effect=subprocess.TimeoutExpired(["adb"], 2, output=b"starting\n")):
                result = checks.run_logged(["adb"], log, 2, check=False)
            self.assertEqual(124, result.returncode)
            self.assertIn("starting", log.read_text())
            self.assertIn("Timed out", log.read_text())

    def test_boot_deadline_uses_remaining_budget(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(checks.time, "monotonic", side_effect=[10, 10.5, 11, 12]), \
                    patch.object(checks.time, "sleep") as sleep, \
                    patch.object(checks, "run_logged", return_value=self.result("", 1)) as command:
                with self.assertRaisesRegex(checks.CheckFailure, "within 2s"):
                    checks.wait_for_boot(Path("adb"), "emulator-5554", 2, Path(directory) / "boot.log")
            self.assertEqual(1.5, command.call_args.args[2])
            sleep.assert_called_once_with(1)


class PreflightCliTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.sdk = self.root / "sdk"
        adb = self.sdk / "platform-tools/adb"
        adb.parent.mkdir(parents=True)
        adb.write_text(f"#!{sys.executable}\n" + '''
import os, sys
from pathlib import Path
arguments = sys.argv[1:]
with Path(os.environ["FAKE_CALLS"]).open("a") as log:
    log.write(" ".join(arguments) + "\\n")
if arguments[-2:] == ["getprop", "sys.boot_completed"]:
    if os.environ.get("FAKE_OFFLINE") == "1":
        print("error: device offline")
        sys.exit(1)
    print("1")
elif arguments[-3:] == ["emu", "avd", "name"]:
    print(os.environ.get("FAKE_AVD", "Karoo") + "\\nOK")
elif arguments[-2:] == ["wm", "size"]:
    print("Physical size: " + os.environ.get("FAKE_SIZE", "480x800"))
elif "instrument" in arguments:
    print(os.environ.get("FAKE_INSTRUMENTATION", "PASS: fixture checks"))
    sys.exit(int(os.environ.get("FAKE_EXIT", "0")))
else:
    print("diagnostic fixture")
''')
        adb.chmod(0o755)
        self.calls = self.root / "calls.log"
        self.environment = {**os.environ, "FAKE_CALLS": str(self.calls)}

    def invoke(self, serial="emulator-5554", boot_timeout="3", **environment):
        return subprocess.run([sys.executable, str(SCRIPT), serial, "--sdk", str(self.sdk),
                               "--preflight-only", "--boot-timeout", boot_timeout, "--log-dir", str(self.root / "logs")],
                              env={**self.environment, **environment}, text=True, capture_output=True, timeout=10)

    def test_ready_preflight_does_not_install_or_change_permissions(self):
        result = self.invoke()
        self.assertEqual(0, result.returncode, result.stderr)
        calls = self.calls.read_text()
        self.assertIn("emu avd name", calls)
        self.assertNotIn("install", calls)
        self.assertNotIn("pm grant", calls)

    def test_physical_device_is_rejected_before_adb_is_called(self):
        result = self.invoke(serial="personal-karoo")
        self.assertEqual(2, result.returncode)
        self.assertFalse(self.calls.exists())

    def test_offline_device_exits_with_diagnostics(self):
        result = self.invoke(boot_timeout="0.15", FAKE_OFFLINE="1")
        self.assertEqual(1, result.returncode)
        self.assertIn("within 0.15s", result.stderr)
        self.assertTrue((self.root / "logs/preflight.log").is_file())
        self.assertIn("device offline", (self.root / "logs/boot-status.log").read_text())
        self.assertTrue((self.root / "logs/system-startup.log").is_file())

    def test_wrong_avd_and_wrong_resolution_are_rejected(self):
        for environment in [{"FAKE_AVD": "PersonalPhone"}, {"FAKE_SIZE": "1080x2400"}]:
            with self.subTest(environment=environment):
                result = self.invoke(**environment)
                self.assertEqual(1, result.returncode)
                self.assertNotIn("install", self.calls.read_text())

    def test_complete_runner_propagates_instrumentation_failure_despite_adb_exit_zero(self):
        for output, expected in [("PASS: fixture checks", 0), ("FAIL: fixture assertion", 1)]:
            with self.subTest(output=output):
                for relative in ["app/build/outputs/apk/debug/app-debug.apk",
                                 "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]:
                    apk = self.root / relative
                    apk.parent.mkdir(parents=True, exist_ok=True)
                    apk.touch()
                with patch.object(checks, "REPO", self.root), \
                        patch.dict(os.environ, {**self.environment, "FAKE_INSTRUMENTATION": output}), \
                        contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                    code = checks.main(["emulator-5554", "--sdk", str(self.sdk), "--skip-build",
                                        "--log-dir", str(self.root / "logs"), "--boot-timeout", "3"])
                self.assertEqual(expected, code)
                self.assertIn(output, (self.root / "logs/instrumentation.log").read_text())

    def test_existing_offline_emulator_is_not_started_twice(self):
        devices = subprocess.CompletedProcess([], 0, "List of devices attached\nemulator-5554\toffline\n")
        with patch.object(checks, "run_logged", return_value=devices), patch.object(checks.subprocess, "Popen") as launch:
            self.assertIsNone(checks.start_emulator(self.sdk, Path("adb"), "emulator-5554", self.root))
        launch.assert_not_called()

    def test_timed_out_instrumentation_stops_guest_checks(self):
        for relative in ["app/build/outputs/apk/debug/app-debug.apk",
                         "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]:
            apk = self.root / relative
            apk.parent.mkdir(parents=True, exist_ok=True)
            apk.touch()
        with patch.object(checks, "REPO", self.root), \
                patch.dict(os.environ, {**self.environment, "FAKE_EXIT": "124"}), \
                contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            code = checks.main(["emulator-5554", "--sdk", str(self.sdk), "--skip-build",
                                "--log-dir", str(self.root / "logs"), "--boot-timeout", "3"])
        self.assertEqual(1, code)
        self.assertEqual(2, self.calls.read_text().count("shell am force-stop com.karoocameracontrol"))

    def test_launched_emulator_has_its_own_session_and_startup_log(self):
        binary = self.sdk / "emulator/emulator"
        binary.parent.mkdir()
        binary.touch()
        devices = subprocess.CompletedProcess([], 0, "List of devices attached\n")
        with patch.object(checks, "run_logged", return_value=devices), patch.object(checks.subprocess, "Popen") as launch:
            checks.start_emulator(self.sdk, Path("adb"), "emulator-5554", self.root)
        self.assertTrue(launch.call_args.kwargs["start_new_session"])
        self.assertEqual(self.root / "emulator-startup.log", Path(launch.call_args.kwargs["stdout"].name))
        self.assertNotIn("-wipe-data", launch.call_args.args[0])


if __name__ == "__main__":
    unittest.main()
