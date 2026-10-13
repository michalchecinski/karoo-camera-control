# Radar-triggered recording

## Feasibility and device APIs

The existing Karoo SDK dependency (`io.hammerhead:karoo-ext:1.1.3`) exposes
`DataType.Type.RADAR` through `OnStreamState.StartStreaming`. It includes a
required `RADAR_THREAT_LEVEL`, eight optional target-range fields, and a radar
error field. No additional radar connection or ANT+ implementation is needed:
pair the radar in **Karoo Settings > Sensors**. The extension consumes Karoo's
selected radar stream.

The SDK does not expose an explicit pass event or identify a detected target
as a car. A positive threat level starts recording; zero is the radar's clear
report. This approximates the last vehicle passing. A vehicle leaving the
radar's detection area can also produce clear. Optional target ranges are not
used to infer a pass or override the clear report.

GoPro's Open GoPro BLE **Set Shutter** command starts/stops recording. The
existing camera manager waits for command acknowledgement and the requested
encoding state. Automation waits for both recording state and camera mode to
be received, and requires Video mode; it does not change the selected preset.
No dependency versions were changed.

Sources:

- [Official Karoo SDK and extension examples](https://github.com/hammerheadnav/karoo-ext/tree/master/app/src/main/kotlin/io/hammerhead/sampleext/extension)
- [Karoo radar fields](https://github.com/hammerheadnav/karoo-ext/blob/master/lib/src/main/kotlin/io/hammerhead/karooext/models/DataType.kt)
- [Karoo event subscriptions](https://github.com/hammerheadnav/karoo-ext/blob/master/lib/src/main/kotlin/io/hammerhead/karooext/models/KarooEvent.kt)
- [Open GoPro BLE camera control / Set Shutter](https://gopro.github.io/OpenGoPro/docs/ble/control/)
- [Official GoPro Kotlin tutorial](https://github.com/gopro/OpenGoPro/tree/main/demos/kotlin/tutorial)
- [Karoo app examples](https://github.com/timklge/awesome-karoo)

## Configuration

Open **More options > Radar recording**. Enable **Record detected traffic**
and choose **Stop after radar clears** with the −5s/+5s buttons. The delay is
5–120 seconds, defaulting to 15 seconds. The feature is off by default.
Settings save immediately and survive app/device restarts. The status card
explains whether a camera, Video mode, active ride, or radar data is missing.
**Setup & limits** explains the prerequisites, pass approximation, manual
override, and signal-loss behaviour.

Connect a supported GoPro and select Video mode, then start a Karoo ride.
`RideState.Recording` enables automation. A paused or ended ride stops an
automation-owned recording. When the extension starts with the feature
enabled it tries the existing saved-camera reconnect flow after requesting
Bluetooth access from Karoo. First pairing still requires the normal setup.
While enabled during an active ride, the controller sends Open GoPro
keep-alive messages every three seconds to keep the connected camera ready
between clips. This keeps the camera awake and uses battery. It does not
change the camera's saved auto-power-off setting. Keep-alive failures appear
in the status card and are retried on the next interval, including response
timeouts. Cancelling the extension stops the loop without reporting a camera
failure or sending another keep-alive.

## Recording lifecycle

- The first vehicle detection starts one recording. Further detections keep
  that recording running.
- The stop delay starts when radar reports clear. Repeated clear samples do
  not reset it. A new vehicle cancels the countdown; the full delay starts
  again after the next clear report. The timer runs independently of samples.
- Disabling the feature, pausing/ending the ride, or losing Karoo System
  stops an owned recording without the normal tail delay.
- Radar, descent automation, and manual controls share one camera command
  controller. Each automation stops only recordings it started; a descent
  recording is left running by radar and vice versa. Ambiguous radar starts
  retain ownership until cleanup succeeds, preventing descent from claiming
  the same recording.
- Manual recordings that are already running are never stopped by radar.
  Using the app's shutter, mode, or preset controls releases ownership and
  overrides the current encounter. Automation resumes after a fresh clear
  report followed by another vehicle. Stopping via the camera shutter also
  suppresses a restart while that encounter remains detected, including when
  the stopped recording was started manually. A physical stop while radar
  is already clear leaves the next encounter armed.
- A missing, searching, idle, errored, or invalid radar stream is unavailable,
  not a pass report. Silence for five seconds is also unavailable. An owned
  recording then stops after the configured delay; new valid detections
  cancel that timeout.
  Completed or failed Karoo subscriptions are retried after five seconds.
- A failed or ambiguous start triggers a cleanup stop. Failed commands are
  shown in the status card and logged; stop is retried at five-second
  intervals while the camera remains connected.
- Camera disconnection releases ownership and cancels pending commands. A
  reconnect does not claim an already-running recording. If the camera
  disconnects or Android kills the extension process, a stop command cannot
  be guaranteed; check the camera and stop it manually if necessary.

The controller belongs to `KarooCameraControlExtension`, so closing the
configuration activity does not stop monitoring while Karoo keeps the
extension running. The extension's manifest uses the official SDK action and
metadata names so Karoo can discover and bind it.
The activity and extension use separate Bluetooth resource IDs, so closing
the activity releases only its own request and preserves the extension's
Bluetooth request.

## Validation

Build and formatting:

```sh
./gradlew :app:assembleDebug :app:ktlintCheck :app:lintDebug
```

The dependency-free Android instrumentation runner covers the radar decoder,
start guards, recording ownership, the clear timer, repeated traffic, manual
overrides, physical shutter stops, radar silence/loss, disconnection, and
command failure cleanup/retry. They also exercise a real coroutine timeout
followed by a successful keep-alive retry and cancellation during an
in-flight keep-alive. Shared command checks verify radar/descent ownership,
manual cancellation, and failed radar cleanup retries:

```sh
./gradlew :app:connectedDebugAndroidTest
```

Emulator UI checks: open the menu and configuration screen, enable/disable,
adjust the delay, verify the limits and saved values after relaunch, and test
the on-screen and Android back buttons. App-only screenshots are stored in
`screenshots/emulator-karoo-480x800/`.

Verified on the 480×800 Karoo emulator profile (Android 16 / API 36): 25
instrumentation checks passed, enable/disable and delay bounds worked,
settings survived process restart, the setup dialog opened and closed, and
both back controls returned to the main screen. Debug build, `ktlint`, and
Android lint passed (lint reported warnings, no errors). All 41 descent checks
and the descent connected-screen instrumentation also passed after integration.
Screenshots were
visually inspected at 480×656 without Android system bars.

CI explicitly requests `platform-tools` from the pinned Android setup
action. This avoids the obsolete `tools` default of the earlier action,
which caused setup to fail before compilation. The branch preserves main's
runner and action updates when merging main; this feature does not introduce
any additional action or dependency version changes.

An Android emulator cannot validate Karoo's proprietary radar service or a
physical GoPro BLE connection. Before release, test on a Karoo with a paired
radar and supported GoPro: single pass, successive cars, manual recording,
manual override, ride pause/end, radar loss, camera loss/reconnect, settings
disable, background operation, and existing manual controls. Measure actual
radar-to-shutter latency and confirm clear/error values on the target
firmware; there is no pre-roll capture guarantee.
