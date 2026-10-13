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

## Progress

PR: #49

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles.

### Phase 1: Field and manual actions

- [x] 1.1 Add a tested presentation/action policy and shared manual entry point. — ce9048c
- [x] 1.2 Register a graphical field with live state, timer, safe actions, and lifecycle cleanup. — ce9048c

### Phase 2: Verification and delivery

- [ ] 2.1 Add emulator integration checks and capture changed field states; update product docs.
- [ ] 2.2 Run ktlint, unit/controller checks, debug build, and Copilot CLI review; finalize PR.
