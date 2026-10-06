# Download sound feedback

Six original PCM cues ship in the application JAR. Regenerate them using
`python3 scripts/generate-feedback-sounds.py`; there are no external recordings,
services or audio-library dependencies. Gentle harmonic tones have smooth attack
and release, with durations below 0.6 seconds. Default app volume is 30%.

- Start: small ascending pair after initial payload arrives.
- Resume: a brighter ascending pair after new payload arrives following resume/retry.
- Pause: a short descending pair.
- Cancel: a lower, soft descending pair.
- Complete: three ascending notes.
- Error: two subdued descending notes, without an alarm loop.

DownloadSoundObserver observes state/bytes and never controls a transfer. Checking
server metadata and verifying saved data do not emit start/resume. Automatic
reconnects and video/audio subphases do not replay the initial cue. Restored history
is silent and observers close before shutdown pauses tasks. Very small downloads
can finish before progress notification; the completion cue suffices.

SoundFeedbackService runs Java Sound I/O on its own daemon executor. It coalesces
bursts over 70ms, spaces playback by at least the 700ms scheduling interval, keeps
one audio clip active, and prioritizes terminal outcomes over start/resume. Audio
availability cannot fail a download. User previews also use the same single line.

Settings preserves cancelled-part retention and adds sound enable/mute, volume,
and a cue picker with Preview. Values persist only on Save; previews use draft
volume. Zero volume suppresses cues. No OS audio settings are modified.

Validation (2026-10-01): nine focused tests and the full Java suite passed. A native
silent-clip test confirmed Java Sound playback on this macOS device; an isolated
JavaFX dialog harness verified validation, preview, mute, Save and Cancel. Windows
and Linux hardware and subjective listening quality remain manual checks.
