# Automation status field

Status: **Planned**. Priority: **4** in the [Karoo integration roadmap](README.md).

## Description

Add a graphical Karoo data field that explains what camera automation is doing
without opening settings. Show whether it is ready, recording, suppressed by
manual override, or blocked by a missing prerequisite. Include the automation
that owns the current clip so the rider understands what may stop it.

This is a dedicated automation field, separate from the existing numeric GoPro
status/time/battery fields and the planned [recording control field](ride-screen-recording-controls.md).
It can first support existing descent and radar recording, then include
[whole-ride recording](whole-ride-recording.md).

## Proposed presentation

| State | Example text |
| --- | --- |
| All automation disabled | Automation off |
| Descent ready | Descent armed |
| Radar ready | Radar armed |
| Both descent and radar ready | Descent + radar armed |
| Whole-ride mode enabled before a ride | Whole ride: waiting for ride start |
| Automatic command pending | Radar: starting… / Descent: stopping… |
| Confirmed owned recording | Recording: radar / descent / whole ride |
| Radar clear with an active stop deadline | Radar: stopping in 12s |
| Manual suppression | Descent: overridden until next descent |
| Missing camera or telemetry | Unavailable: camera disconnected / radar data missing |
| Wrong camera mode | Unavailable: select Video mode |
| Command failure | Camera command failed; check camera |

Use concise text and an icon; colour may reinforce state but must not be the
only way to distinguish it. Compact fields show the most important state.
Larger fields can include per-automation readiness and blocking reasons.
Proposed display priority is command failure/unknown state, pending command,
current recording owner, then readiness or suppression. A recording with no
known automation owner must not be labelled as radar or descent merely because
that setting is enabled; show **Recording: manual/other** instead.

## Behaviour and integration

Read shared controller state; do not run a second detector or issue commands
from this field. The displayed state should match settings screens and alerts.
Controllers will likely need structured observable status, including ownership,
suppression reason, and actual stop deadline, rather than parsing UI strings.

Calculate countdowns from the active monotonic deadline, not the configured
delay. For example, the current radar status string says the configured number
of seconds; the new field should show the remaining time. New traffic cancels
that countdown. Missing radar data must remain distinct from radar-clear data.

Rendering should stop when the field is not observed, while enabled automation
continues under the extension lifecycle. A stopped extension, disconnected Karoo
system, or stale camera/telemetry state must display unavailable rather than armed.
Do not expose camera names, Bluetooth addresses, or diagnostic internals.

## Acceptance criteria

- The field is selectable in a Karoo ride profile alongside existing fields.
- Ready, disabled, pending, recording, suppressed, failed, and unavailable states
  are distinct and match controller behaviour.
- Descent/radar coexistence and whole-ride mode presentation follow their policies.
- Recording owner and manual override are accurate across app and physical controls.
- Countdown decreases correctly, cancels on fresh traffic, and respects radar loss.
- State updates while the settings activity is closed; field removal cleans up
  rendering without stopping the automation.
- Compact/large layouts and day/night readability are checked on a physical Karoo.

## References

- [Official graphical data field example](https://github.com/hammerheadnav/karoo-ext/blob/master/app/src/main/kotlin/io/hammerhead/sampleext/extension/CustomSpeedDataType.kt)
- [Current radar status and lifecycle](../radar-recording.md)
- [Current descent detection and suppression](../descent-recording.md)
