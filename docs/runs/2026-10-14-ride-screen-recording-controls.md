# Ride-screen recording controls

Source doc: docs/roadmap/ride-screen-recording-controls.md

## Goal
Start and stop a connected GoPro from a dedicated Karoo graphical data field using the existing manual recording controller.

## Scope
Add a RemoteViews field, private manual-action receiver, deterministic presentation/action policy tests, Android integration checks, screenshots, and feature documentation. Preserve existing numeric fields and app controls.

## Non-goals
No dependency updates, Bonus Actions, camera mode/preset changes, native alerts, whole-ride recording, or automation status field.

## Implementation Plan
### Phase 1: Field and manual actions
1.1 Implement the field, guarded actions, and state/controller tests.
### Phase 2: Integration and documentation
2.1 Verify RemoteViews actions and layouts on the isolated Karoo emulator, capture changed UI, and document field setup and limitations.
### Phase 3: Validation and review
3.1 Run formatting, build/check/lint, and Copilot CLI review; fix actionable findings and finalize the PR.

## Workflow and validation
The user authorized proceeding autonomously despite missing pipeline setup/companion skills. Use repository-native gh operations and Copilot CLI review. Base branch resolved from GitHub default branch: main. Labels: feature and version:minor; unavailable pipeline labels are skipped. Reuse this linked worktree. Validation: ./gradlew ktlintFormat, ./gradlew ktlintCheck :app:testDebugUnitTest :app:verifyDescentRecording :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest, and ./gradlew build where release signing configuration permits. Review covers correctness, compatibility, security, and scope.

## Risks
Interactive field behavior must be checked against SDK 1.1.3. Emulator integration cannot prove Karoo ride-host touch behavior or GoPro BLE operation; physical device QA remains required before release. Do not mark physical QA or pending CI as passed.

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles.

### Phase 1: Field and manual actions

- [ ] 1.1 Implement the field, guarded actions, and state/controller tests.

### Phase 2: Integration and documentation

- [ ] 2.1 Verify RemoteViews actions and layouts on the isolated Karoo emulator, capture changed UI, and document field setup and limitations.

### Phase 3: Validation and review

- [ ] 3.1 Run formatting, build/check/lint, and Copilot CLI review; fix actionable findings and finalize the PR.
