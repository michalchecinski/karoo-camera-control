# Ride-screen recording controls

Goal: Let riders start, stop, and cancel video recording from a dedicated Karoo graphical field while the app activity is closed.

Source doc: docs/roadmap/ride-screen-recording-controls.md

## Scope

Use pinned SDK 1.1.3 RemoteViews, an explicit private PendingIntent receiver, and the existing application-scoped command controller and manual automation overrides. Preserve numeric fields and dependencies. Preview fields cannot issue commands. Revalidate every action against current state to reject stale and repeated presses.

Non-goals: Bonus Actions, dependency updates, alerts/sound, whole-ride recording, automation status field, mode/preset changes.

## Implementation plan

### Phase 1: Field and manual actions

1.1 Add a tested presentation/action policy and shared manual entry point.
1.2 Register a graphical field with live state, timer, safe actions, and lifecycle cleanup.

### Phase 2: Verification and delivery

2.1 Add emulator integration checks and capture changed field states; update product docs.
2.2 Run ktlint, unit/controller checks, debug build, and Copilot CLI review; finalize PR.

## Risks

- Another linked worktree claimed feat/ride-screen-recording-controls during preflight; use a v2 slot to preserve that concurrent run. Operator requested autonomous continuation.

- Physical Karoo touch forwarding, day/night appearance, and GoPro BLE require hardware QA; emulator proof cannot establish these.
- RemoteViews emissions are rate-limited by the SDK; render latest state once per second.
- Pipeline configuration and companion skills are absent. Operator authorized autonomous fallback using gh, repository build checks, and Copilot CLI review. Base branch resolved from GitHub default-branch: main. Missing pipeline labels are skipped; existing feature/version labels applied.
- Local release signing credentials are unavailable; validate debug build, lint, and tests rather than signing a release.

## Validation gate

Fallback gate derived from repository checks (JDK 17, installed Android SDK):

- `./gradlew :app:ktlintFormat :app:ktlintCheck`
- `./gradlew :app:testDebugUnitTest :app:verifyDescentRecording`
- `./gradlew :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest -PcameraTestRunner=com.karoocameracontrol.RecordingFieldInstrumentation`
- Run `RecordingFieldInstrumentation` on an isolated 480×800 emulator and inspect app-only screenshots.
- Copilot CLI read-only review against `origin/main`, including compatibility, security, breaking-change, and scope checks.

## Verification outcomes

- Final combined gate passed: ktlintFormat/ktlintCheck, 30 unit tests (8 field tests), 41 descent/controller checks, lintDebug, assembleDebug, and assembleDebugAndroidTest.
- Four isolated Karoo-sized emulator scenes passed real RemoteViews reapply/action guards, private receiver delivery while disconnected, 1Hz ViewEmitter updates and cancellation. All final 480×656 screenshots inspected; synthetic test-host states are explicitly identified on the PR.
- GitHub Copilot CLI 1.0.97 reviewed source changes from b0c93dd through f907782 in read-only mode: **Approve; no concrete actionable defects**. No source edits followed that review during the initial implementation; subsequent base-branch integration is recorded below.
- Physical Karoo/GoPro touch, BLE, and day/night checks remain a disclosed pre-release QA requirement. Release signing was not attempted locally; GitHub CI handles the release build.

## Base-branch integration

Merged `origin/main` at `68d489c` after native camera alerts and the connected-screen layout landed. Preserved both sets of strings and roadmap implementation statuses. The field's `targetRecording` now derives from the same command target emitted to native alerts, preserving start/stop origins, terminal events, and cancellation guards. A regression test covers descent Start overridden by field Stop and verifies both the alert events and field command state.

The combined formatting, 62 unit tests (including nine field tests), 41 controller checks, Android lint, debug APK and test APK validation gate passed after resolution. Copilot CLI approved the merged controller; all constructor consumers were checked and remain compatible with the optional trailing state property. Existing field layouts and screenshots are unchanged.

## Progress

PR: #49

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles.

### Phase 1: Field and manual actions

- [x] 1.1 Add a tested presentation/action policy and shared manual entry point. — ce9048c
- [x] 1.2 Register a graphical field with live state, timer, safe actions, and lifecycle cleanup. — ce9048c

### Phase 2: Verification and delivery

- [x] 2.1 Add emulator integration checks and capture changed field states; update product docs. — f907782
- [x] 2.2 Run ktlint, unit/controller checks, debug build, and Copilot CLI review; finalize PR. — c6aae5d
