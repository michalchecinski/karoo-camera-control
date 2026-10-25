#!/usr/bin/env python3
"""Check camera alert preferences and capture the changed UI on an isolated Karoo AVD."""

import argparse
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("serial")
args = parser.parse_args()
if not args.serial.startswith("emulator-"):
    parser.error("Use an isolated emulator, never a personal Karoo/camera")
repo = Path(__file__).resolve().parents[1]
sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
adb = sdk / "platform-tools/adb"
package = "com.karoocameracontrol"


def command(*parts):
    return subprocess.check_output([str(adb), "-s", args.serial, *parts], text=True)


def node(attribute, value):
    # Cold process launches may take several seconds on the small AVD.
    for _ in range(6):
        try:
            output = command("shell", "uiautomator", "dump", "/sdcard/camera-alert-ui.xml")
        except subprocess.CalledProcessError:
            time.sleep(0.5)
            continue
        if "dumped" in output:
            root = ET.fromstring(command("shell", "cat", "/sdcard/camera-alert-ui.xml"))
            matches = [n for n in root.iter("node") if n.get(attribute) == value]
            if matches:
                return matches[0]
        time.sleep(0.5)
    raise AssertionError(f"UI element missing: {value}")


def tap(element):
    left, top, right, bottom = map(int, re.findall(r"\d+", element.get("bounds")))
    command("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
    time.sleep(0.3)


def control(title):
    return node("content-desc", title)


def screenshot(filename):
    time.sleep(0.5)
    subprocess.check_call([
        str(repo / ".agents/skills/karoo-screenshots/scripts/capture-app-only.sh"),
        args.serial, str(repo / "screenshots/emulator-karoo-480x800" / filename),
    ])


def launch():
    command("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    command("shell", "wm", "dismiss-keyguard")
    command("shell", "am", "force-stop", package)
    command("shell", "am", "start", "-W", "-n", f"{package}/.MainActivity")
    time.sleep(1)


def open_settings():
    tap(control("Settings and features"))
    tap(control("Alerts & sound"))


def assert_settings(confirmations, warnings, sound):
    for title, expected in zip(("Recording confirmations", "Camera warnings", "Sound"), (confirmations, warnings, sound)):
        element = control(title)
        assert element.get("checked") == str(expected).lower(), f"Wrong preference: {title}"
        assert element.get("enabled") == "true", f"Preference disabled: {title}"


assert command("emu", "avd", "name").splitlines()[0] == "Karoo"
assert "480x800" in command("shell", "wm", "size")
launch()
tap(control("Settings and features"))
control("Alerts & sound")
screenshot("settings-hub.png")
tap(control("Alerts & sound"))
assert_settings(False, True, False)
help_text = node("text", "Active or paused rides only. Warn at 20% battery or 5 minutes left.")
left, top, right, bottom = map(int, re.findall(r"\d+", help_text.get("bounds")))
assert bottom <= 576 and bottom - top >= 60, "Settings help text is clipped above Back"
screenshot("camera-alert-settings-default.png")
print("PASS: menu navigation and independent defaults", flush=True)
tap(control("Recording confirmations"))
tap(control("Sound"))
assert_settings(True, True, True)
screenshot("camera-alert-settings-enabled.png")
tap(control("Camera warnings"))
assert_settings(True, False, True)
launch()
open_settings()
assert_settings(True, False, True)
print("PASS: preferences persist across process restart", flush=True)
tap(control("Recording confirmations"))
assert_settings(False, False, True)
tap(control("Sound"))
assert_settings(False, False, False)
tap(control("Camera warnings"))
assert_settings(False, True, False)
command("shell", "input", "keyevent", "KEYCODE_BACK")
control("Alerts & sound")
tap(control("Back"))
control("Settings and features")
open_settings()
assert_settings(False, True, False)
tap(control("Back"))
control("Alerts & sound")
tap(control("Back"))
control("Settings and features")
print("PASS: visual and sound switches are independent; both Back controls work; defaults restored", flush=True)
