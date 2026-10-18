# Expert prompt — connection keep-alive

## Original prompt
> Implement issue #57 using 10x skills.

## Four expert questions
1. Expert move not asked for: trace GATT identity, cancellation, response locks and service lifetime before relocating radar's timer.
2. Rawest material: issue #57, GoProManager, RadarRecordingController, CameraCommandController, extension lifecycle, existing timeout tests, official Open GoPro Control documentation.
3. Methods and tools: inspect every disconnect path; inject clock/wait barriers into a coroutine scheduler; test timeout recovery, busy commands and session replacement; run Gradle unit, descent, formatting, build and lint checks.
4. Outside check: executable regression tests and Android compilation; actual supported GoPro models/firmware establish sleep prevention at 60 seconds.

## Assumptions to test
- A BLE connection outlives the activity and is shared by manual, radar and descent commands.
- Cancelling the extension's scope does not cancel the singleton manager's scope.
- A response timeout must not cancel the periodic job.
- Sixty seconds prevents sleep: unproven; official guidance recommends three seconds.

## Rewritten prompt
Implement exactly one 60-second keep-alive scheduler per ready GoPro BLE session in the shared manager. Remove radar ownership while preserving its failure warning. Reuse Setting 0x5B/value 0x42 and the existing response mutex; defer behind busy camera commands and reject stale sessions under the mutex. Cancel on disconnect and extension shutdown, restart on a new connection or extension recreation, and preserve all settings and recording ownership. Verify scheduling, replacement, command deferral, timeouts and cancellation without dependency updates. Document battery cost and the unvalidated physical-camera interval; never claim hardware checks passed without evidence.

## What changed and why
- Trace actual connection lifetime rather than relocating a timer by name.
- Verify failure recovery and session identity at asynchronous boundaries.
- Separate proven software behavior from physical sleep prevention.
