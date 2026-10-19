# Zoom out — connection keep-alive

## Three levels
1. Task: implement issue #57's shared 60-second BLE setting write.
2. Outcome: keep an already connected camera available for later manual, radar and descent clips, independently of ride/settings state.
3. Standing system: maintain a firmware-specific idle/command regression matrix and rerun it when connection code changes.

## What the agent needs for level 3
| Need | Status | Where / how to add |
|---|---|---|
| Signals | have | GitHub issue and explicit diagnostic reports |
| Mapping | have | Manager response logs, source and versioned builds |
| Reproduction | partial | JVM scheduling tests; physical Karoo/GoPro access required for sleep |
| Verification | partial | Gradle checks available; model/firmware idle tests pending |
| Permission | partial | Local implementation authorized; no recurring monitoring or release mandate |

## Recommended level for this session
Level 2 within issue #57: implement and test connection lifecycle behavior and document the exact remaining hardware validation. Do not add monitoring or change the requested interval.

## Standing mandate (copy-paste prompt)
For an explicitly supplied Karoo/GoPro hardware matrix, reproduce idle BLE behavior before/during/after rides, validate subsequent manual/radar/descent commands, correlate results with firmware and builds, and propose fixes when regression checks fail. Obtain a decision before changing the 60-second requirement.

## Setup steps for the user
- Provide physical Karoo/GoPro test results, including model, firmware, auto-power-off setting and idle durations, before treating sleep prevention as verified.
