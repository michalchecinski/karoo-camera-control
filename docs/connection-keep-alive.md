# Connected-camera keep-alive

The shared GoPro BLE manager schedules one keep-alive every **60 seconds** after
connection setup reaches Connected. The first write is due 60 seconds after
setup. It runs before a ride, during active/paused rides, after a ride ends and
between clips. Recording state and all app settings, including radar and descent,
do not control it. There is no keep-alive setting.

Closing the configuration activity leaves the loop running while BLE remains
connected. Disconnection, connection errors and extension service shutdown cancel
the loop, including any pending response wait. A new ready BLE connection starts
a fresh interval; restarting the extension resumes it on an existing connection.
Repeated starts for the same connection do not create duplicate loops.

The write reuses Open GoPro Setting **0x5B**, value **0x42**, and waits for its
Settings Response. It waits for in-progress shutter, mode or preset operations
and uses the same BLE operation mutex as other commands. The original GATT
connection is checked after acquiring the mutex so queued work cannot target a
replacement camera. A command that arrives after a keep-alive write begins waits
for that bounded BLE transaction to finish, as with other BLE operations.

Response failures and timeouts are logged in local diagnostics and retried at
the next scheduled interval. A busy command can defer a due write; missed
intervals never produce a burst of catch-up writes. Normal response latency does
not shift the 60-second schedule. Cancellation is not reported as a camera failure.
Radar retains its existing keep-alive failure warning using the shared result.

Keeping a camera awake consumes battery, including outside rides and with all
recording automations disabled. This write preserves the camera's saved
Auto Power Down setting.

## Interval limitation and physical validation

[Open GoPro's Control documentation](https://gopro.github.io/OpenGoPro/docs/ble/control/#keep-alive)
recommends sending every **3 seconds**. Issue #57 explicitly requests **60 seconds**.
This implementation follows that requirement. Software checks cannot prove that
60 seconds prevents idle sleep on a particular camera or firmware. No physical
Karoo/GoPro validation was performed in this implementation session; reliability
at this interval remains unverified. Do not change the interval based on a failed
hardware check without a maintainer decision.

Before release, record model, firmware, app build, auto-power-off preference,
idle duration, timestamps of keep-alive writes/responses and subsequent recording
results. Test before ride start, active/paused rides, after ride end, and with
configuration closed; vary radar/descent off/on and leave idle beyond the camera's
configured auto-power-off duration. Confirm later manual, radar and descent
recording, disconnect/reconnect and service shutdown/restart. Verify the saved
auto-power-off setting is unchanged. If sleep occurs, attach diagnostics and
obtain a decision on the interval.

| Model | Firmware | Idle/ride/command checks | Result |
|---|---|---|---|
| Not tested | Not tested | Physical Karoo and supported GoPro required | Pending |

## Automated checks

`ConnectionKeepAliveTest` uses injected clock/wait barriers with the existing
coroutine libraries to verify the production 60-second interval, duplicate start
protection, response-time cadence, missed-period handling, command deferral,
timeout/failure recovery, disconnect cancellation, reconnection and service-style
stop/restart. Manager start/stop hooks, original-GATT checks and existing BLE
response/mutex integration were reviewed by source inspection; they are not
exercised by an Android BLE fixture. Physical transport and firmware behavior
require the checks above.

```sh
./gradlew :app:ktlintCheck :app:testDebugUnitTest :app:verifyDescentRecording \
  :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin
```
