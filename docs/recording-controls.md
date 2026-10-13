# Recording controls

The app confirms a recording start or stop in two stages: it first waits for
the GoPro Set Shutter (`0x01`) command response to report success, then
verifies the requested recording state from the GoPro status response or its
registered status update.

The record control remains available while a recording command is in flight.
A second press stops or cancels the pending recording request even before the
GoPro has reported that recording began. Mode and preset controls remain
disabled while recording because the GoPro cannot safely switch them
mid-recording.

Manual commands and [automatic descent starts](descent-recording.md) use the
same application-scoped controller. Command progress remains visible when
the screen is reopened, and failures appear in a snackbar. The timer and
Record/Stop button remain fixed above Back on the Karoo screen; other camera
details scroll when needed. A manual Stop suppresses automatic starts until
the next descent.

If the shutter response or recording-state confirmation times out, the shared
controller reports **Camera command timed out** in the snackbar and releases
the controls for retry or manual Stop. Cancelling a command through manual
override or disconnection does not report a timeout error.

An optional **Stop at descent end** setting stops only clips started by descent
automation, after confirmed flatter terrain or ride finish. It is off by
default. Automatic stops use the same acknowledgement and progress path;
manual Stop remains available while an automatic stop is pending. Manual clips
and recordings found after reconnect are never stopped by this option.
