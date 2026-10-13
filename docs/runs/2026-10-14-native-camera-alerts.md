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

## Risks

Native rendering, beep audibility and live BLE interactions require physical Karoo/GoPro checks.
The supplied automatic PR pipeline configuration and companion skills are unavailable; the
operator authorized a standard autonomous workflow. Base branch resolved from GitHub: main.
Validation: ktlintFormat, ktlintCheck, testDebugUnitTest, verifyDescentRecording,
assembleDebug, lintDebug, and focused emulator instrumentation.

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles.

### Phase 1: Event policy and integration

- [ ] 1.1 Implement deterministic alert policy and focused tests.
- [ ] 1.2 Feed confirmed camera/command events and dispatch native alerts/beeps from the extension.

### Phase 2: Settings and documentation

- [ ] 2.1 Add persistent preferences and settings UI; verify persistence and capture changed screens.
- [ ] 2.2 Update feature and roadmap documentation.

### Phase 3: Validation and review

- [ ] 3.1 Run formatting, unit checks, debug build, lint and relevant emulator checks.
- [ ] 3.2 Run GitHub Copilot CLI review, fix relevant findings, and publish the PR.
