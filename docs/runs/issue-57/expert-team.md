# Expert team — connection keep-alive

## Task as verbs
Keep one ready BLE connection supplied with periodic writes across UI, ride and preference changes. Stop stale work before it can affect a replacement camera and prove failure recovery.

## Team
| Role | Why needed | What they do | Deliverable |
|---|---|---|---|
| Integration engineer (primary) | Move ownership to BLE | Implement manager hooks and preserve protocol/mutex behavior | Code and feature docs |
| Lifecycle reviewer (subagent) | Stop obsolete work | Trace GATT callbacks, extension teardown and singleton scopes | Concrete hook/race recommendations |
| Verification engineer (subagent) | Prove recovery | Inspect test infrastructure and define dependency-free coroutine barriers | Regression test approach |
| Concurrency critic (review pass) | Break the implementation | Inspect actual changes for queued writes, duplicate loops and cancellation | Findings and resolutions |

## Run plan
Run lifecycle and test inspection in parallel with primary source analysis. Implement with those findings, then give the actual diff to a critic. Run executable checks after fixes.

## Findings and resolution (after the run)
| Critic finding | Decision | Reason |
|---|---|---|
| Manager scope outlives service | Fixed | Explicit stop on extension shutdown and restart on recreation |
| Remote disconnect bypasses disconnectGatt | Fixed | Cancel at remote-disconnect and error hooks too |
| Queued write may see replacement GATT | Fixed | Check original GATT identity inside operationMutex |
| Handshake could restart the task after service shutdown | Fixed | Lifecycle guard blocks starts until extension recreation; covered by a regression test |
| Scheduler tests do not execute Android GATT wiring | Accepted limitation | Manager hooks and original-GATT checks reviewed in source; real BLE testing remains pending |
| Physical 60-second sleep behavior unknown | Pending hardware | Software checks cannot establish firmware timers |
