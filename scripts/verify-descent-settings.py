#!/usr/bin/env python3
"""Verify the real settings UI on an isolated Karoo AVD; never use a physical device."""

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
    parser.error("Use an isolated emulator, not a personal Karoo/camera")

repo = Path(__file__).resolve().parents[1]
sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
adb = sdk / "platform-tools/adb"
package = "com.karoocameracontrol"


def command(*parts):
    return subprocess.check_output([str(adb), "-s", args.serial, *parts], text=True)


def hierarchy():
    command("shell", "uiautomator", "dump", "/sdcard/descent-ui-check.xml")
    return ET.fromstring(command("shell", "cat", "/sdcard/descent-ui-check.xml"))


def node(attribute, value):
    matches = [n for n in hierarchy().iter("node") if n.get(attribute) == value]
    assert matches, f"UI element missing: {value}"
    return matches[0]


def tap(element):
    left, top, right, bottom = map(int, re.findall(r"\d+", element.get("bounds")))
    command("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
    time.sleep(0.2)


def description(value):
    return node("content-desc", value)


def open_settings():
    tap(description("More options"))
    tap(node("text", "Descent recording"))


def screenshot(filename):
    time.sleep(0.5)
    subprocess.check_call([
        str(repo / ".codex/skills/karoo-screenshots/scripts/capture-app-only.sh"),
        args.serial,
        str(repo / "screenshots/emulator-karoo-480x800" / filename),
    ])


def launch():
    command("shell", "am", "force-stop", package)
    command("shell", "am", "start", "-n", f"{package}/.MainActivity")
    time.sleep(0.5)


assert "Karoo" == command("emu", "avd", "name").splitlines()[0]
assert "480x800" in command("shell", "wm", "size")
# These checks change settings only in a freshly installed, isolated emulator app.
launch()
tap(description("More options"))
node("text", "Descent recording")
screenshot("descent-menu.png")
tap(node("text", "Descent recording"))
assert description("Start on descents").get("checked") == "false"
for label in ("Less steep gradient", "Steeper gradient", "Shorter confirmation", "Longer confirmation"):
    assert description(label).get("enabled") == "false"
print("PASS: navigation, off by default, and disabled adjustments", flush=True)

tap(description("Start on descents"))
assert description("Start on descents").get("checked") == "true"
for text in ("−3%", "3 s", "Waiting for Karoo"):
    found = node("text", text)
    assert int(re.findall(r"\d+", found.get("bounds"))[-1]) <= 576, f"Clipped setting: {text}"
screenshot("descent-settings-enabled.png")
tap(description("Steeper gradient"))
tap(description("Longer confirmation"))
launch()
open_settings()
assert description("Start on descents").get("checked") == "true"
node("text", "−4%")
node("text", "4 s")
print("PASS: enabled flag, gradient, and delay persist across process restart", flush=True)

for label in ("Less steep gradient", "Shorter confirmation"):
    control = description(label)
    for _ in range(3):
        tap(control)
    assert description(label).get("enabled") == "false"
node("text", "−1%")
node("text", "1 s")
for label in ("Steeper gradient", "Longer confirmation"):
    control = description(label)
    for _ in range(9):
        tap(control)
    assert description(label).get("enabled") == "false"
node("text", "−10%")
node("text", "10 s")
print("PASS: adjustment bounds at 1 and 10", flush=True)

for label in ("Less steep gradient", "Shorter confirmation"):
    control = description(label)
    for _ in range(7):
        tap(control)
tap(description("Start on descents"))
command("shell", "input", "keyevent", "KEYCODE_BACK")
node("content-desc", "More options")
open_settings()
assert description("Start on descents").get("checked") == "false"
node("text", "−3%")
node("text", "3 s")
tap(description("Back"))
node("content-desc", "More options")
print("PASS: system Back, in-app Back, and restored default settings", flush=True)
