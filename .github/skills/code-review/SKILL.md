---
name: code-review
description: Review Karoo Camera Control pull requests and diffs for concrete regressions in GoPro BLE commands, Karoo lifecycle, recording automation, diagnostics, and Android UI. Use when reviewing code changes in this repository.
---

# Code review

Read `AGENTS.md` and `.github/copilot-instructions.md` before reviewing. Use
their evidence threshold: report actionable defects introduced by the change,
not preferences, praise, or speculative risks. Review without modifying code.

## Establish the intended behaviour

- Read the PR description, linked issue when available, and the diff against
  its actual base. Trace affected callers, state owners, and tests before
  reporting a defect; do not judge isolated diff fragments.
- Read the relevant project documentation in `docs` and alongside the affected
  source code. Identify product boundaries and the documented command,
  automation, pairing, and diagnostic behaviour relevant to the change.
- Distinguish roadmap proposals from shipped requirements. Evaluate an
  intentional behaviour change against its stated requirements and updated
  docs; do not report every difference from the old behaviour as a regression.
- Validate SDK claims against the dependency actually resolved by this
  project and the official references in `AGENTS.md`. Do not assume the newest
  upstream API is available or suggest dependency upgrades as a routine fix.

## Check the affected integration paths

Apply only the checks relevant to the diff.

### GoPro commands and pairing

- Follow a command from the initiating action through serialized GATT work,
  response parsing, timeout/cancellation, and camera-state confirmation.
  An acknowledgement alone must not be presented as confirmed recording.
- Check partial/malformed BLE packets, unsupported status values, disconnects,
  and late callbacks. Unknown state must not become a confirmed stopped state
  or allow an unsafe command. Old-session responses must not update a new
  camera connection or complete its pending command.
- Preserve selected-camera identity and permission/bonding gates. For the
  Karoo 3 pairing workaround, verify the Settings notification action is
  uniquely identified and bonding completes before GoPro commands begin.

### Recording automation

- Manual and automatic commands must use the shared recording controller.
  Manual Stop must remain usable while an automatic start or stop is pending.
- Check off-by-default opt-ins, active-ride and Video-mode guards, and known
  camera state before starting. Review each automation against its own
  documented rules; radar and descent intentionally differ on pause, data
  loss, rearming, and cleanup retries.
- Trace ownership through start, manual override, failure, disconnection,
  reconnect, and ride end. An automation must not stop a manual clip or
  another automation's clip, or claim a recording discovered on reconnect.
- Distinguish permitted, bounded cleanup/keep-alive retries from replaying
  a stale state-changing request. Check cancellation prevents delayed starts,
  stops, or retries after ownership or connection identity changes.

### Karoo and Android lifecycle

- Verify Karoo subscriptions, Bluetooth resource requests, callbacks, and
  coroutine scopes are released by their owning lifecycle. Closing the
  activity must not release resources owned by the extension or stop its
  enabled background monitoring.
- Check stream completion/error handling and stale or non-finite telemetry.
  Missing data is not a valid zero, clear radar report, or descent end.
- Keep blocking BLE, storage, and network work off the main thread. Check
  cancellation handling does not swallow cancellation and continue commands.
- For changed Compose screens or fields, inspect loading, unavailable,
  pending, and error states at Karoo resolution. Preserve accessible manual
  controls, navigation, and Android string resources.

### Diagnostics and secrets

- Check redaction before persistence, bounded storage/queues, retention, and
  backup exclusion. Exception messages, notification text, credentials,
  device addresses, and raw BLE payloads must not leak into diagnostics.
- Uploads require explicit send/retry consent. Reopening or reconnecting must
  not submit a report. Check retry identity, expiry/discard cleanup, and
  transport acceptance before showing success against the diagnostics docs.

## Verify and report

- Inspect focused regression coverage for changed parsers, state transitions,
  ownership, cancellation, persistence, and outcome classification. Prefer
  tests that reproduce the failure path as well as the intended outcome.
- When execution is available, select relevant checks from the existing
  Gradle tasks and feature docs: `:app:testDebugUnitTest`,
  `:app:verifyDescentRecording`, `:app:ktlintCheck`, `:app:lintDebug`, and
  `:app:assembleDebug`. Use documented instrumentation on an available
  emulator when needed. Do not require runtime tests for documentation-only
  changes or silently install or update dependencies to run a review.
- State which checks ran and which were unavailable. Emulator evidence does
  not prove physical Karoo services, firmware pairing, or GoPro BLE behaviour.
- For each finding, give severity, the smallest relevant changed line range,
  the concrete triggering scenario, user impact, and a suggested correction
  when supported. Verify the path against the surrounding implementation.
- If no actionable defects are supported, say so. Identify any material
  validation gaps separately without presenting them as proven bugs. Leave
  product decisions requiring human judgment to the maintainer.
