# Native camera alerts and sound

Open **More options → Camera alerts**. Three preferences save immediately and
survive app/device restarts:

- **Recording confirmations**: visual start/stop messages; off by default.
- **Camera warnings**: visual failures, connection loss and resource warnings;
  on by default.
- **Sound**: Karoo beeps for all camera events; off by default. This switch is
  independent of the visual switches, so sound-only and visual-only operation
  are supported.

Notifications run through the Karoo extension while settings are closed, and
only during an active or paused ride. No alerts are queued for the next ride,
reconnect, or extension restart. In-app command errors remain available even
when all notification preferences are disabled.

## Events

Start and stop confirmations require a verified recording transition. Shared
manual, descent and radar commands wait for both GoPro shutter acknowledgement
and the requested recording state before notifying. The message identifies the
initiator when known. Physical camera shutter transitions use the same policy.
Whole-ride recording can use this policy when that separate feature exists.

An initial connected snapshot establishes a baseline; it never confirms a new
start/stop. Rejected, timed-out and cancelled commands do not produce success
messages. A matching transition immediately after a failed/cancelled request
is ambiguous because GoPro status does not identify the shutter initiator. It
is conservatively suppressed for up to five seconds (the existing command
confirmation window), or until a fresh recording-state report establishes the
opposite baseline. Later independent physical changes are reported with camera
origin; stale suppression never hides them indefinitely. Repeated command failures
are suppressed until a successful command, independent physical transition,
or new connection. Commands and automation ownership are unchanged.

Disconnecting during recording or a pending recording command produces
**Camera connection lost**, with **Recording state unknown; check the camera**.
Bluetooth loss never implies recording stopped. Reconnecting establishes a new
baseline and does not claim an existing clip.

Battery warnings trigger at **20% or below**, and recording-capacity warnings
at **five minutes or below**. These are camera-reported values, not predictions.
Unknown or invalid values are ignored; zero is a valid warning value. Each
warning fires once and rearms after recovery to at least **25% battery** or
**more than six minutes** remaining. This hysteresis avoids threshold chatter.
Battery and capacity warnings arriving within 300 ms are combined. Warning
latches are tracked per camera with opaque in-memory IDs, so Bluetooth flapping
or switching away and back does not rearm a low value without recovery. A pending
combined warning is discarded when the ride, camera or Karoo connection ends.

Confirmations automatically dismiss after three seconds; failures/warnings
after six seconds. Alerts use a camera icon and readable text. Start and stop
use ascending/descending two-tone beeps; warnings/errors use two lower tones
separated by silence.

## SDK and lifecycle

`CameraAlertMonitor` belongs to `KarooCameraControlExtension`. It observes
`RideState`, accepts only `Recording`/`Paused`, cancels delayed warnings on ride
end or Karoo loss, and removes its consumer/live listener on destruction.
Failed/completed ride subscriptions retry after three seconds without retaining
ride eligibility. Events come from a main-thread live feed; detached monitors
receive no replay. Initial monitoring seeds the latest known snapshot.

Only public SDK `InRideAlert` and `PlayBeepPattern` effects are used, following
[the official effect definitions](https://github.com/hammerheadnav/karoo-ext/blob/master/lib/src/main/kotlin/io/hammerhead/karooext/models/KarooEffect.kt)
and the presentation approach of
[karoofirefly](https://github.com/derstrassi/karoofirefly/blob/main/app/src/main/kotlin/io/github/derstrassi/karoofirefly/KarooLightControllerExtension.kt).
The existing karoo-ext 1.1.3 dependency is unchanged. No camera BLE commands
or pairing flow were added or changed.

## Validation

```sh
./gradlew :app:ktlintFormat :app:ktlintCheck :app:testDebugUnitTest \
  :app:verifyDescentRecording :app:assembleDebug :app:lintDebug
./gradlew :app:assembleDebugAndroidTest \
  -PcameraTestRunner=com.karoocameracontrol.CameraAlertInstrumentation
adb -s <isolated-emulator> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <isolated-emulator> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <isolated-emulator> shell am instrument -w \
  com.karoocameracontrol.test/com.karoocameracontrol.CameraAlertInstrumentation
python3 scripts/verify-camera-alert-settings.py <isolated-emulator>
```

JUnit checks cover snapshots, physical transitions, command acknowledgement,
manual/automation origins, override, failures/late statuses, deduplication,
unknown resources, hysteresis, ride eligibility, independent preferences,
and restart baselines. Android instrumentation checks all eight persisted
preference combinations and the native SDK effects: text, resource IDs,
dismissal duration, visual-only/sound-only operation and distinct beep patterns.
The UI script requires an isolated Karoo AVD at 480×800, checks navigation,
defaults and persistence across process restart, restores defaults, and
captures app-only screenshots.

### Alert payload preview

The PR includes [a labelled emulator screenshot](../screenshots/emulator-karoo-480x800/camera-alert-preview.png)
showing recording confirmation, connection loss, and a combined low-resource alert.
Its text, icon, and colors come directly from production `InRideAlert` payloads.
The test-only card layout is illustrative; it is **not** the proprietary Karoo
ride-alert renderer and does not verify native positioning, dismissal, or sound.

Reproduce on the isolated 480×800 Karoo AVD after installing the debug APK.
Wake/unlock the emulator and grant the app Bluetooth Scan, Bluetooth Connect,
and Fine Location permissions first, so Android dialogs cannot cover the preview.

```sh
./gradlew :app:assembleDebugAndroidTest \
  -PcameraTestRunner=com.karoocameracontrol.CameraAlertPreviewInstrumentation
adb -s <isolated-emulator> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <isolated-emulator> shell am instrument -w \
  com.karoocameracontrol.test/com.karoocameracontrol.CameraAlertPreviewInstrumentation
adb -s <isolated-emulator> exec-out run-as com.karoocameracontrol \
  cat cache/camera-alert-preview.png > /tmp/camera-alert-preview-raw.png
sips --cropToHeightWidth 656 480 --cropOffset 48 0 \
  /tmp/camera-alert-preview-raw.png \
  --out screenshots/emulator-karoo-480x800/camera-alert-preview.png
```

Instrumentation verifies all three alert titles/details are visible within the
app area before capturing the Android surface. Inspect the final 480×656 asset
and retain the visible emulator-preview label when publishing it.

An emulator cannot establish proprietary Karoo ride-alert rendering or beep
audibility, nor live GoPro BLE behavior. Before release, validate on a physical
Karoo and supported GoPro: manual/descent/radar starts and stops, physical
shutter changes, pauses/end, failed/pending commands and overrides, disconnect
and reconnect, low battery/capacity, all preferences, dismissal, audible patterns
and coexistence with navigation/radar alerts. These hardware checks remain
outstanding until performed.
