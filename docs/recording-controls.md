# Recording controls

The app confirms a recording start or stop in two stages: it first waits for
the GoPro command response to report success, then verifies the requested
recording state from the GoPro status response or its registered status update.

The record control remains enabled as **Stop** as soon as the GoPro reports
that recording has begun. Tapping **Stop** cancels any outstanding start
confirmation before sending the stop command. Mode and preset controls remain
disabled while recording because the GoPro cannot safely switch them
mid-recording.
