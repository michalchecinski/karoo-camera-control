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
