#!/usr/bin/env python3
"""Bounded preflight and UI instrumentation for an isolated 480×800 Karoo AVD."""

import argparse
import math
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
import time


RUNNERS = {
    "recording": "com.karoocameracontrol.DescentUiInstrumentation",
}
PACKAGE = "com.karoocameracontrol"
REPO = Path(__file__).resolve().parents[1]


class CheckFailure(RuntimeError):
    pass


def positive_seconds(value):
    seconds = float(value)
    if not math.isfinite(seconds) or seconds <= 0:
        raise argparse.ArgumentTypeError("Use a finite timeout greater than zero")
    return seconds


def emulator_serial(value):
    if not re.fullmatch(r"emulator-\d+", value):
        raise argparse.ArgumentTypeError("Use an isolated emulator serial, never a physical device")
    return value


def run_logged(command, log, timeout, check=True):
    try:
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, timeout=timeout, check=False)
    except subprocess.TimeoutExpired as error:
        partial = error.stdout or ""
        if isinstance(partial, bytes):
            partial = partial.decode(errors="replace")
        result = subprocess.CompletedProcess(command, 124, f"{partial}\nTimed out after {timeout:g}s\n")
    with log.open("a") as stream:
        stream.write(f"$ {shlex.join(map(str, command))}\n{result.stdout}\nExit: {result.returncode}\n")
    if check and result.returncode:
        raise CheckFailure(f"Command failed (exit {result.returncode}); see {log}")
    return result


def wait_for_boot(adb, serial, timeout, log):
    deadline = time.monotonic() + timeout
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise CheckFailure(f"{serial} did not become ADB-ready with sys.boot_completed=1 within {timeout:g}s")
        result = run_logged([str(adb), "-s", serial, "shell", "getprop", "sys.boot_completed"],
                            log, min(3, remaining), check=False)
        if result.returncode == 0 and result.stdout.strip() == "1" and time.monotonic() <= deadline:
            return
        time.sleep(max(0, min(2, deadline - time.monotonic())))


def verify_avd(adb, serial, log):
    name = run_logged([str(adb), "-s", serial, "emu", "avd", "name"], log, 5).stdout.splitlines()
    if not name or name[0].strip() != "Karoo":
        raise CheckFailure("Expected the isolated Karoo AVD; refusing to install or run tests")
    size = run_logged([str(adb), "-s", serial, "shell", "wm", "size"], log, 5).stdout
    if re.findall(r"(?:Physical|Override) size:\s*(\d+x\d+)", size) not in (["480x800"], ["480x800", "480x800"]):
        raise CheckFailure("Expected a 480×800 display without a different size override")


def collect_diagnostics(adb, serial, directory):
    # Each diagnostic has its own short limit, even when the guest is offline.
    commands = {
        "adb-devices.log": ["devices", "-l"],
        "adb-state.log": ["-s", serial, "get-state"],
        "boot-status.log": ["-s", serial, "shell", "getprop", "sys.boot_completed"],
        "system-startup.log": ["-s", serial, "shell", "logcat", "-b", "system", "-d", "-t", "200"],
    }
    for filename, arguments in commands.items():
        try:
            run_logged([str(adb), *arguments], directory / filename, 3, check=False)
        except OSError as error:
            (directory / filename).write_text(str(error))


def instrumentation_passed(result):
    output = result.stdout
    failure = re.search(
        r"(?mi)^FAIL:|INSTRUMENTATION_(?:FAILED|ABORTED)|FAILURES!!!|Process crashed|\b(?:Error|shortMsg)=|"
        r"INSTRUMENTATION_CODE:\s*(?!-1\b)-?\d+", output,
    )
    return result.returncode == 0 and not failure and bool(re.search(r"(?m)^PASS:", output))


def start_emulator(sdk, adb, serial, directory):
    devices = run_logged([str(adb), "devices"], directory / "adb-devices.log", 5).stdout
    if re.search(rf"(?m)^{re.escape(serial)}\s", devices):
        return None  # Existing offline devices must finish booting; never launch a duplicate.
    port = int(serial.removeprefix("emulator-"))
    if port < 5554 or port > 5682 or port % 2:
        raise CheckFailure("Starting an AVD requires an even emulator port between 5554 and 5682")
    emulator = sdk / "emulator/emulator"
    if not emulator.is_file():
        raise CheckFailure(f"Emulator not found: {emulator}")
    with (directory / "emulator-startup.log").open("w") as log:
        return subprocess.Popen([str(emulator), "-avd", "Karoo", "-port", str(port),
                                 "-no-audio", "-no-boot-anim", "-no-snapshot-save"],
                                stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)


def execute(args, adb, directory):
    wait_for_boot(adb, args.serial, args.boot_timeout, directory / "preflight.log")
    verify_avd(adb, args.serial, directory / "preflight.log")
    print(f"PASS: {args.serial} is a booted 480×800 Karoo AVD", flush=True)
    if args.preflight_only:
        return
    runner = RUNNERS[args.runner]
    if not args.skip_build:
        run_logged([str(REPO / "gradlew"), "-p", str(REPO), ":app:assembleDebug", ":app:assembleDebugAndroidTest",
                    f"-PcameraTestRunner={runner}"], directory / "build.log", args.build_timeout)
    for apk in [REPO / "app/build/outputs/apk/debug/app-debug.apk",
                REPO / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]:
        if not apk.is_file():
            raise CheckFailure(f"APK missing: {apk}; run without --skip-build")
        run_logged([str(adb), "-s", args.serial, "install", "-r", str(apk)], directory / "install.log", 30)
    for permission in ["BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION"]:
        run_logged([str(adb), "-s", args.serial, "shell", "pm", "grant", PACKAGE, f"android.permission.{permission}"],
                   directory / "setup.log", 5)
    for command in [["input", "keyevent", "KEYCODE_WAKEUP"], ["wm", "dismiss-keyguard"], ["am", "force-stop", PACKAGE]]:
        run_logged([str(adb), "-s", args.serial, "shell", *command], directory / "setup.log", 5)
    result = run_logged([str(adb), "-s", args.serial, "shell", "am", "instrument", "-w", f"{PACKAGE}.test/{runner}"],
                        directory / "instrumentation.log", args.test_timeout, check=False)
    print(result.stdout, end="", flush=True)
    if result.returncode == 124:
        # Killing the host ADB client alone does not stop guest-side instrumentation.
        run_logged([str(adb), "-s", args.serial, "shell", "am", "force-stop", PACKAGE],
                   directory / "setup.log", 5, check=False)
    if not instrumentation_passed(result):
        raise CheckFailure(f"Instrumentation did not report a clean PASS; see {directory / 'instrumentation.log'}")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial", type=emulator_serial)
    parser.add_argument("--runner", choices=RUNNERS, default="recording")
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or Path.home() / "Library/Android/sdk"))
    parser.add_argument("--boot-timeout", type=positive_seconds, default=120)
    parser.add_argument("--build-timeout", type=positive_seconds, default=300)
    parser.add_argument("--test-timeout", type=positive_seconds, default=120)
    parser.add_argument("--log-dir", type=Path)
    parser.add_argument("--start-emulator", action="store_true", help="Start Karoo if this serial is absent; never wipe data")
    parser.add_argument("--preflight-only", action="store_true", help="Check readiness without building, installing or granting permissions")
    parser.add_argument("--skip-build", action="store_true", help="Use APKs already built for the selected runner")
    args = parser.parse_args(argv)
    directory = args.log_dir or Path(tempfile.mkdtemp(prefix="karoo-ui-"))
    directory.mkdir(parents=True, exist_ok=True)
    print(f"Logs: {directory}", flush=True)
    adb = args.sdk / "platform-tools/adb"
    emulator = None
    try:
        if not adb.is_file():
            raise CheckFailure(f"ADB not found: {adb}; specify --sdk or ANDROID_SDK_ROOT")
        if args.start_emulator:
            emulator = start_emulator(args.sdk, adb, args.serial, directory)
        execute(args, adb, directory)
        return 0
    except (CheckFailure, OSError) as error:
        print(f"FAIL: {error}\nDiagnostics: {directory}", file=sys.stderr)
        if adb.is_file():
            collect_diagnostics(adb, args.serial, directory)
        if emulator is not None:
            emulator.terminate()
            try:
                emulator.wait(timeout=5)
            except subprocess.TimeoutExpired:
                emulator.kill()
                emulator.wait(timeout=5)
        return 1


if __name__ == "__main__":
    sys.exit(main())
