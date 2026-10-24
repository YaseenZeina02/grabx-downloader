# GrabX notifications

Implemented 2026-10-16. These are in-app notifications, styled to match GrabX.
They do not register with macOS/Windows notification centers or bring the app to
front. Existing download sounds remain independent and unchanged.

## User experience

- Bell buttons in the normal toolbar and compact footer share a session inbox.
  Up to 50 entries are kept, newest first, with an unread badge and Clear all.
  Opening the inbox marks current entries read; closing GrabX clears the inbox.
- Success: completed download and restored connection. Error: failed download.
  Amber: unavailable internet checks and a download reconnecting to its server.
- Completion/failure bursts within three seconds are grouped, showing their count
  and latest file. Only one banner is shown; a short debounce coalesces rapid events.
  Banners dismiss after eight seconds and stay while hovered; they can be dismissed.
- Banners appear only when the relevant GrabX window is showing and focused.
  Background/minimized events remain in the inbox. View switching, moving/resizing,
  losing focus and application shutdown dismiss banners without activating windows.
- Settings > Notifications includes master enable, completion, failure, connection
  events and foreground banners. Uses the existing draft/save/discard confirmation.
  Turning off banners retains the inbox. Disabling all notifications or connection
  events disables periodic probes on their next check; pending results are ignored.
- Manual pause/cancel, restored history and shutdown do not emit outcome alerts.
  A reconnect alert is limited to once per user attempt, rather than every retry
  countdown. Global offline state suppresses redundant per-download retry alerts.

## Connection checks

ConnectivityMonitor owns a daemon worker with an eight-second fixed delay. It
checks HTTPS `https://www.gstatic.com/generate_204`; if unavailable it checks the
independent `https://cp.cloudflare.com/generate_204`. Only HTTP 204 is accepted.
Redirects and captive-portal HTML are rejected; TLS verification is never disabled.
Connection and read timeouts are 2.5 seconds each. Only fixed public endpoints are
contacted, with no filenames, download URLs, cookies or other user payloads.

Two consecutive matching samples establish a change; initial online is silent,
initial offline is reported after confirmation, and recovery requires confirmation.
Consequently detection is not instantaneous. Blocking both probe services can look
like a connectivity loss, so the banner explains that connection checks failed.
A download-server failure alone never changes global internet status. Monitoring is
informational: no download is paused, resumed or failed by the notification service.
Existing engine retries continue to own reconnection behavior.

## Implementation and verification

NotificationCenter is a bounded FX-thread model; preferences are safely published
to the monitor thread. DownloadNotificationObserver attaches/detaches row listeners,
defers final error reading until the runner has set the status, and ignores stale
callbacks after retry/removal/shutdown. Errors are mapped to short friendly messages;
raw engine output and signed URLs are not copied into notifications. URL-like titles
are replaced by a generic name.

- Full Java 21 test + installDist with GRABX_TEST_RESUME=true: 278 discovered,
  268 passed, 10 optional skips; all 34 native resume cases passed.
- After correcting compact shadow width and replacing a TLS-invalid probe candidate,
  installDist and the focused tests passed. Final 12 notification/connectivity tests
  passed, including bounded grouping, preferences, restored-history silence, retry
  suppression, signed URL redaction, lifecycle cleanup, hysteresis, disabled probes,
  and local HTTP fixtures rejecting redirects/200 portal pages/503 responses.
- Both production HTTPS endpoints passed a live check through GrabX's actual Java
  implementation. No actual user internet disconnection was performed.
- Isolated JavaFX harness verified draft/discard/save, category disabling, full and
  compact banners/inbox, unread/clear, background suppression and minimum-size footer.
  Native macOS foreground activation of the test stage was unavailable; the harness
  simulated JavaFX focus for banner rendering and the focus gate. Actual foreground
  behavior in the user's running app and Windows live behavior remain unverified.
- Preview source: /private/tmp/GrabXNotificationPreview.java; temporary snapshots
  /private/tmp/grabx-notifications-*.png. No real downloads, history or preferences
  were touched and the real app was not restarted. No extension reload is required.

Each inbox card has a × button to delete only that entry; unread count and the
empty state update immediately, and the inbox stays open. Clear all still clears
the inbox. The temporary banner's × continues to hide that banner only.
