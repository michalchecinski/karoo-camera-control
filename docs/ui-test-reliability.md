# Reliable emulator UI checks

The test tooling uses existing Android, Kotlin/coroutines and Python standard
libraries. It does not change app behavior or dependency versions.

## Command completion in unit tests

`CameraAlertPolicyTest.sharedControllerTimeoutWarnsOnceAndRejectsLateSuccess`
waits for the controller's observable busy state to clear, with a two-second
deadline. It still exercises a real command timeout and verifies failure and
late-success suppression. Waiting for completion replaces a fixed 50 ms sleep
that could finish before the timeout callback ran on a busy host.

## Shared Android UI helpers

`app/src/androidTest/kotlin/com/karoocameracontrol/UiTestSupport.kt` supplies the
connected-camera instrumentation runner with:

- Fresh accessibility trees, clearing the Android accessibility cache on API
  33 and later so enabled/visible state is not reused across recompositions.
- Ten-second bounded text/description waits with the missing selector in errors.
- Clickable-parent selection and viewport checks for scrolling rows, plus
  bounded scroll-and-click retries.

The helpers remain in `androidTest`; none are included in production APKs.
Runner-specific camera fixtures, assertions and screenshot capture stay in their
respective instrumentation classes.

## Bounded preflight and instrumentation runner

Use an isolated **Karoo** AVD at **480×800**, with no saved personal cameras.
The runner refuses physical-device serials, other AVD names and different
display resolutions. It never wipes app data or camera pairings.

```sh
# Check an existing emulator without building, installing or granting permissions.
python3 scripts/run-karoo-ui-checks.py emulator-5554 --preflight-only

# Start Karoo if the serial is absent, then build/install and run recording checks.
python3 scripts/run-karoo-ui-checks.py emulator-5554 --start-emulator

# Explicitly select the connected-camera instrumentation runner.
python3 scripts/run-karoo-ui-checks.py emulator-5554 --runner recording
```

The script reads `ANDROID_SDK_ROOT`, then `ANDROID_HOME`, or defaults to
`~/Library/Android/sdk`. Use `--sdk /path/to/sdk` for another installation.
`--skip-build` requires both debug APKs already built for the selected runner;
otherwise the script builds with the appropriate `cameraTestRunner` property.

Without `--start-emulator`, the runner first checks the ADB device list. An
absent serial fails immediately with instructions to start the Karoo AVD or use
`--start-emulator`, instead of waiting for the boot deadline. An emulator listed
as offline still gets the bounded boot wait; it is never started a second time.
Failure diagnostics are retained for absent devices too.

Preflight checks ADB responsiveness and `sys.boot_completed=1` within one
shared deadline. Defaults are **120 seconds for boot**, **300 seconds for the
build**, and **120 seconds for instrumentation**. Change them with
`--boot-timeout`, `--build-timeout` and `--test-timeout`. Other ADB commands also
have bounded timeouts. On failure, collecting four diagnostic commands can add
up to twelve seconds after the failed operation's deadline.

Each run prints a temporary log directory outside the repository. Override it
with `--log-dir /tmp/karoo-ui-checks`. It contains preflight, build, install,
setup and instrumentation logs as those stages execute. Failures also capture
ADB device/state, boot status and system startup logs. When the script starts
the emulator, its stdout/stderr go to `emulator-startup.log`; an emulator it
started is terminated on failure. Existing emulators are never terminated, and
a successfully started emulator stays available after the checks.

After a successful preflight, the normal runner installs the APKs, grants the
test app's Bluetooth/location permissions, wakes/unlocks the emulator and
force-stops the app before instrumentation. The supported runner reports
an explicit `PASS:` or `FAIL:`. ADB exit code zero alone is insufficient:
missing success output, failure/crash markers, cancelled instrumentation and
timeouts all produce a nonzero script exit code.
When instrumentation exceeds its deadline, the script also force-stops the
tested app so guest-side checks cannot keep running after the ADB client exits.

## Verification

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/tests -v
./gradlew :app:ktlintCheck :app:testDebugUnitTest :app:assembleDebugAndroidTest \
  -PcameraTestRunner=com.karoocameracontrol.DescentUiInstrumentation
```

The Python regression checks use fake ADB executables and mocked clocks to
verify boot deadlines, diagnostic retention, device guards and false-green
instrumentation output without requiring an emulator. Run the real emulator
command above to verify helper integration when a Karoo AVD is available.

On 2026-10-18, all 71 JUnit tests and all 12 Python regression checks passed,
along with formatting, ktlint, Android lint and debug/instrumentation APK builds.
The connected-camera instrumentation passed through this runner on the Karoo AVD.
