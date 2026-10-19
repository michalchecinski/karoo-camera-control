# Wake-and-record validation — 2026-10-19

The maintainer selected one-press manual wake-and-record, three-second keep-alive
for radar/descent during active rides, and a saved whole-ride readiness fallback
that defaults off. This replaces the original issue #57 connection-wide 60-second
policy. The fallback lives under Camera connection and never starts a recording.

The shared controller exposes wake progress and cancellation on the app and field.
Readiness waits are bounded to 60 seconds including serialization. A queued manual
request retains a background connection across pause/settings changes; cancelling
the last queued manual request closes an otherwise abandoned retained attempt.
Explicit app/field Start actions revalidate the selected camera and cannot become
Stop because of stale state. Shutdown cancels pre-shutter intent, attempts Stop
for a pending Start, lets an existing Stop finish and preserves completed clips.

Lifecycle/concurrency review identified and resolved joining an existing connection,
late Connected publication after cancellation, service-shutdown cleanup, and a
background attempt being cancelled while a manual request depended on it. Final
source review reported no remaining concrete blockers. GATT creation/publication
and teardown share lifecycle synchronization; byte writes/responses retain the
existing operation mutex.

Verification passed:

- 101 JUnit tests, including wake/controller/coordinator/ride-policy regressions.
- All 41 deterministic descent checks.
- ktlint formatting and checks, debug APK, test APK, and Android lint (zero errors,
  15 warnings). Format and check were run sequentially.
- Isolated Karoo 480×800 emulator: production activity wake UI, no connection on
  startup, touch and synthetic NAVIGATE_IN cancellation, offline fallback setting
  persistence, activity recreation and navigation.
- Production ride-field RemoteViews wake states, reapply/preview/small-size guards,
  private receiver, safe disconnected action, 1 Hz rendering and cancellation.
- App-only screenshots inspected at 480×656; no Android system bars are included.

Build and local checks:

```sh
./gradlew :app:ktlintFormat --offline
./gradlew :app:ktlintCheck :app:testDebugUnitTest :app:verifyDescentRecording \
  :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest --offline
```

UI runner builds use `-PcameraTestRunner=com.karoocameracontrol.WakeRecordingInstrumentation`
and `com.karoocameracontrol.RecordingFieldInstrumentation`, installed and run on
the isolated emulator. The latter used `-e scene wake -e capture true`.
The UI runners use sample state without a physical GATT connection and do not
establish real firmware behavior or physical Karoo key delivery.

Physical Karoo/GoPro validation, battery measurements and wake-to-record latency
remain pending. See [the feature procedure](../connection-keep-alive.md).
