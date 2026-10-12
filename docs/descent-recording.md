# Recording when a descent starts

## Implementation plan

1. Build a deterministic descent detector and shared camera command controller.
   Verify confirmation, noisy/missing data, rearming, and manual Stop before
   committing and pushing the first milestone.
2. Subscribe to Karoo ride state, gradient, and speed from the extension service.
   Add persistent, opt-in settings and expose automatic command progress to the
   existing recording controls.
3. Build and check the changed screens on the 480×800 Karoo Android emulator,
   capture app-only screenshots, run formatting and validation, then commit,
   push, and open a pull request.

## Behaviour

Open **More options → Descent recording** to enable automatic starts. It is off
by default. The default trigger is a gradient of **−3% or steeper for 3 seconds**
while moving at **5 km/h or faster**. Gradient and confirmation duration are
adjustable from 1 to 10 percent and 1 to 10 seconds. Changes save immediately.

The feature starts video recording only during an actively recording Karoo
ride, with a connected GoPro in Video mode. It does not change the selected
mode or preset or wake a disconnected camera.
The normal Stop control remains available during an automatic start.

**Stop at descent end** is a separate option, off by default. When selected,
recordings started by this automation stop after a gradient of **−0.5% or
flatter for 3 seconds** while riding and moving, or when the Karoo ride finishes.
Short flat sections, pauses, slow/stationary riding, missing data, and Bluetooth
loss do not declare a descent end. Automatic stopping does not touch manual
recordings. Manual Stop, a confirmed camera-side stop, or a disconnection releases
ownership; recordings discovered after reconnect must be stopped manually.
Turning automatic stopping off leaves the current clip running.

If a descent ends while an automatic start is still awaiting confirmation,
the app cancels the pending start and sends Stop through the same command
controller. Automatic-stop failures leave manual Stop available and report
the camera error. Automatic stopping does not suppress the next descent.

A confirmed descent consumes one automatic start opportunity, including when
the camera is unavailable, already recording, busy, or rejects the command.
Manual Stop suppresses further starts until the next descent. Rearming requires
a gradient of **−0.5% or flatter for 3 seconds** while riding and moving. A pause,
connection loss, settings change, or missing data does not rearm a consumed
descent. Ending the ride resets detection for the next ride.

Gradient and speed must be finite and no more than 2.5 seconds old. Interruptions
or stale data clear the confirmation timer. Detection uses live measurements
and requires no route. Confirmation and camera command latency mean recording
starts after the descent begins.

## SDK integration

The existing `karoo-ext` 1.1.3 dependency provides `RideState` and
`OnStreamState.StartStreaming(DataType.Type.ELEVATION_GRADE / SPEED)`. The
detector reads `DataType.Field.ELEVATION_GRADE` (percent) and
`DataType.Field.SPEED` (metres per second). There is no dedicated public
descent-start event. The extension follows the official subscription and
cleanup examples, streaming gradient and speed only while the setting is
enabled. Ride-state observation stays active so ending a ride also resets
suppression when the feature is off.

Automatic and manual recording share an application-scoped command controller.
It uses the existing GoPro Set Shutter response and recording-state confirmation
path, prevents overlapping camera configuration commands, and surfaces failures
in the app. The extension service owns ride-data subscriptions so detection
continues while the camera-control screen is closed and Karoo keeps the
extension running.

References:

- [Karoo extension examples](https://github.com/hammerheadnav/karoo-ext/tree/master/app/src/main/kotlin/io/hammerhead/sampleext/extension)
- [Karoo data types](https://github.com/hammerheadnav/karoo-ext/blob/master/lib/src/main/kotlin/io/hammerhead/karooext/models/DataType.kt)
- [Open GoPro BLE specification](https://gopro.github.io/OpenGoPro/ble/index.html)

## Validation

Run `./gradlew :app:verifyDescentRecording` for the deterministic checks. They
also run as part of Gradle `check` and CI `build`, using the app's existing
dependencies. No library versions have changed.

CI explicitly installs `platform-tools` through the existing pinned
`setup-android` action. Its default also requests the obsolete `tools` package,
which fails before Gradle starts on current runners.

For UI checks, build `:app:assembleDebug :app:assembleDebugAndroidTest`, install
both APKs on the isolated Karoo AVD, and run:

```sh
adb -s emulator-5554 shell am instrument -w \
  com.karoocameracontrol.test/com.karoocameracontrol.DescentUiInstrumentation
python3 scripts/verify-descent-settings.py emulator-5554
```

The instrumentation uses an isolated fake camera to exercise the production
connected screen on a real Android emulator. It checks pending and confirmed
automatic starts, disabled mode controls, visible Stop, and manual override.
The settings script requires the defaults in an isolated app install, checks
persistence across process restart and adjustment boundaries, captures the
changed menu/settings screens, and restores defaults. It refuses physical
devices. The final screenshots are 480×656 with Android system bars removed.

Deterministic tests cover sustained descents, noise, slow/stationary riding,
pauses, stale/non-finite data, manual suppression, rearming, and shared camera
command behaviour. Emulator validation covers settings navigation, persisted
values, and recording controls. The emulator does not provide real Karoo
telemetry or GoPro BLE hardware. Before release, test on an actual Karoo and
supported GoPro, including gradient responsiveness, recording acknowledgement,
manual override, ride pauses, and Bluetooth disconnect/reconnect.
