# Recording controls

The record control remains enabled as **Stop** as soon as the GoPro reports
that recording has begun, including while the original start command is still
waiting for its final status confirmation.

Tapping **Stop** at that point cancels the outstanding start operation and
sends the stop command. This prevents a successful recording start from
leaving the user unable to stop it. Mode and preset controls remain disabled
while recording because the GoPro cannot safely switch them mid-recording.
