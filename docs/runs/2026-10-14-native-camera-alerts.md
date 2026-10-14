# Native camera alerts and audible confirmation

Goal: Implement roadmap item 2 with native ride alerts and distinguishable Karoo beeps.

Source doc: docs/roadmap/camera-alerts-and-sound.md

## Scope

Shared notification policy for confirmed recording transitions, recording command failures,
connection loss during recording/pending commands, low battery and capacity. Persist independent
confirmation, warning and sound preferences. Run monitoring in the extension lifecycle.

Non-goals: dependency updates, whole-ride recording, new recording controls, camera command
retries or ownership changes, other camera integrations.

## Implementation plan

### Phase 1: Event policy and integration

1.1 Implement deterministic alert policy and focused tests.
1.2 Feed confirmed camera/command events and dispatch native alerts/beeps from the extension.

### Phase 2: Settings and documentation

2.1 Add persistent preferences and settings UI; verify persistence and capture changed screens.
2.2 Update feature and roadmap documentation.

### Phase 3: Validation and review

3.1 Run formatting, unit checks, debug build, lint and relevant emulator checks.
3.2 Run GitHub Copilot CLI review, fix relevant findings, and publish the PR.

## Alert screenshot follow-up

Added a test-only emulator preview of three production InRideAlert payloads:
recording confirmation, connection loss, and combined battery/capacity warning.
The image visibly states that native Karoo layout may differ. Instrumentation
checks all titles/details are inside the app area, captures the Android surface,
and the asset is cropped/inspected at 480×656. Build and ktlint checks passed.
This preview does not replace the outstanding physical Karoo rendering checks.

## PR review and merge follow-up

Merged origin/main 2c637e3: retain the compact header and connected-control layout,
while preserving Camera alerts navigation/title and both documentation additions.
Tighter settings spacing and a UI bounds assertion keep help fully above Back with
main's taller header. Refreshed all three settings/menu screenshots and inspected them.
The updated DescentUiInstrumentation and settings persistence/navigation checks passed.

Both Copilot PR review findings were valid and fixed:

- [Ride/transport boundaries retained pending command alerts](https://github.com/michalchecinski/karoo-camera-control/pull/50#discussion_r4268228294):
  rebaseline on unsubscribe and ride eligibility transitions; mark any existing
  request ineligible, retaining only ignored status/terminal bookkeeping. Preserve
  bounded late-status suppression and resource hysteresis. Add four regression
  tests covering old start/stop completions, inactive starts, late failed status,
  and warning latches across boundaries.
- [Wrong GATT provenance for queued notifications](https://github.com/michalchecinski/karoo-camera-control/pull/50#discussion_r4268228337):
  carry the callback's actual source instance from both Android API overloads;
  guard before packet parsing/response completion and again on Main. Add three
  regression tests for stale replacement callbacks, deferred delivery, disconnect
  and accepting current callbacks, including equal camera identities on distinct
  connection instances.

Full validation passed: 53 JUnit tests (28 alert, 3 callback, 22 diagnostics),
41 deterministic descent checks, debug build, formatting/ktlint and lint. The
first merge gate hit an unrelated existing diagnostics timing test; the rerun
and the subsequent full post-fix gate passed without diagnostics changes.

## Outcome

PR: https://github.com/michalchecinski/karoo-camera-control/pull/50
Status: complete. Native alert policy, SDK effects, persisted settings and documentation
implemented; Copilot review findings fixed and follow-up clean. All available local
checks passed. Physical Karoo/GoPro validation and remote CI remain external gates.

## Risks

Native rendering, beep audibility and live BLE interactions require physical Karoo/GoPro checks.
The supplied automatic PR pipeline configuration and companion skills are unavailable; the
operator authorized a standard autonomous workflow. Base branch resolved from GitHub: main.
Validation: ktlintFormat, ktlintCheck, testDebugUnitTest, verifyDescentRecording,
assembleDebug, lintDebug, and focused emulator instrumentation.

## Verification evidence

Local gate passed: ktlintFormat, ktlintCheck, testDebugUnitTest (46 tests, including 24 alert
checks), verifyDescentRecording (41 checks), assembleDebug and lintDebug (0 errors,
30 warnings). CameraAlertInstrumentation passed 12 preferences/SDK effect checks.
Settings UI script passed defaults, independent toggles, restart persistence and both
Back controls. All three changed-screen screenshots were inspected at 480×656.
Copilot CLI identified two medium and one low finding. All were accepted:

- Stale failed/cancelled-command suppression: bound ambiguity to five seconds or a fresh
  opposite recording baseline; add physical-shutter/expiry and non-encoding packet tests.
- Status-feed vs observed-state race: apply feed and all parsed state values atomically
  in one Main coroutine, guarding against a GATT connection replaced before application.
- Reconnect warning repeats: retain per-camera resource latches across reconnect/selection
  with stable opaque IDs; test reconnect, camera switching and recovery. Cancel grouped
  warnings on camera disconnection so old camera resources cannot appear after reconnect.

Fixes landed in add238c. The full post-fix local gate passed with 46 JUnit tests,
including 24 alert checks, plus 41 descent checks. Radar regression instrumentation
also passed 25 checks. The Copilot CLI follow-up verdict was clean: no remaining actionable defects introduced
by the feature or fixes. Its non-blocking note about initial status polling was checked:
that path already issues its status query without awaiting the response and is outside this feature. The
unnecessary polling barrier was removed to keep the change scoped to event application.

## Progress

PR: #50

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles.

### Phase 1: Event policy and integration

- [x] 1.1 Implement deterministic alert policy and focused tests. — 21d199d
- [x] 1.2 Feed confirmed camera/command events and dispatch native alerts/beeps from the extension. — 21d199d

### Phase 2: Settings and documentation

- [x] 2.1 Add persistent preferences and settings UI; verify persistence and capture changed screens. — 8b967d5
- [x] 2.2 Update feature and roadmap documentation. — 8b967d5

### Phase 3: Validation and review

- [x] 3.1 Run formatting, unit checks, debug build, lint and relevant emulator checks. — 8b967d5
- [x] 3.2 Run GitHub Copilot CLI review, fix relevant findings, and publish the PR. — add238c (review fixes), 368ce79 (scope cleanup); PR #50
