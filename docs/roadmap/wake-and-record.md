# One-press wake-and-record and ride keep-alive

Status: **Implemented; physical hardware validation pending**.

The selected design is documented in [camera wake-up and ride keep-alive](../connection-keep-alive.md).
The app and ride-screen field share a bounded, cancellable wake-and-record action.
Keep-alive runs every three seconds during active rides with radar or descent
enabled. The whole-ride readiness fallback is a saved option, off by default,
under Settings & features → Camera connection; it stops on pause/end and does
not start or stop recordings.

This supersedes the original connection-wide 60-second design of issue #57.
The fallback is distinct from [whole-ride recording](whole-ride-recording.md).
Physical wake latency, battery consumption, firmware sleep behavior and native
Karoo integration still require the checks in the current feature document.
