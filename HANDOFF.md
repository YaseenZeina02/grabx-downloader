# GrabX handoff

Updated: 2026-10-16 after fixing audio artwork retry following disk exhaustion.

## Latest outcome: audio retry after disk-full failure (2026-10-16)

- User's audio MP3 first failed with Errno 28, then with Postprocessing: Error
  opening output files: Invalid argument after freeing space. Read-only inspection
  found a zero-byte WEBP sidecar beside a 12,323,000-byte MP3, valid JPEG and WEBM.
  About 2.39 GB was free at inspection. The log did not retain the subprocess error.
- Reproduced the exact postprocessing error locally with copied media and an empty
  cover, then with a one-second generated fixture. yt-dlp's --no-overwrites reuses
  even an empty thumbnail. A valid cover makes the same processing succeed.
- Audio formats with embedded artwork now use --no-force-overwrites: refresh
  sidecars but retain existing media and continuation. Other audio/video and direct
  paths keep their previous behavior. No broad deletion or forced media overwrite.
- Added AudioArtworkRetryTest: native offline fixture first fails with the old
  option, then succeeds with the new option, reuses the existing audio and embeds
  a fresh cover. Source bytes stay unchanged. All 25 focused tests passed (artwork,
  output naming/cleanup, extractor options and metadata cache), Java 21 installDist
  succeeded. Full suite not rerun for this narrow option change; no Windows live test.
- User Desktop/history/preferences untouched; actual application not restarted.
  User next step: Stop/Run updated GrabX, then Retry. Do not claim their existing
  task has already been repaired or completed. Native test is entirely local/offline.
- Proposed next improvements: available-space checks with conversion headroom;
  clearer messages separating artwork/conversion failure from transfer failure.

## Latest outcome: notifications (2026-10-16)

- Reviewed current checkout: clean before this work; latest startup-resume feature
  already committed. No unfinished implementation found. Delete confirmations and
  window preferences were proposals, not partially implemented features. User now
  explicitly requested failure, internet loss/recovery and other useful notifications.
- Added an in-app notification center and brief non-modal banners styled with GrabX's
  navy palette and green/red/amber event accents. Bells in full toolbar and compact
  footer share 50 session entries, unread count and Clear all. Outcome bursts group;
  reconnect countdowns do not chatter. Background events stay in the inbox; banners
  do not activate the app or replace native OS notifications.
- Settings now has six sections, including Notifications with master/category/banner
  toggles, draft Save/Discard/Keep editing and persistence. Existing sounds unchanged.
- ConnectivityMonitor checks two independent fixed HTTPS 204 endpoints on a daemon,
  two samples per transition, no initial online alert, bounded connect/read timeouts,
  no redirects, no user download data. Disabled notifications/connection category
  stops subsequent checks. Server retry alerts are separate from global offline.
  Existing transfer/retry/resume logic is unchanged. Shutdown detaches observers,
  stops probes and closes popups before download shutdown.
- Tests: full Java 21 + GRABX_TEST_RESUME=true test/installDist passed (278 discovered,
  268 passed, 10 optional skips; all 34 native resume tests). After small endpoint/UI
  refinements, final 12 notification/connectivity tests passed and installDist built.
  Tests cover grouping/bounds, categories, stale outcomes/history/manual actions,
  safe error messages, connectivity transitions/disabling, and local HTTP portal,
  redirect and failure responses. Both final HTTPS endpoints passed using Java.
- Visual preview caught/fixed compact-banner shadow overflow. Isolated harness passed
  settings drafts/save/discard, both views' inbox/banners, badges/clear, background
  suppression, and footer bounds at 900x500. macOS did not foreground the test stage;
  harness simulated JavaFX focus for rendering/gating checks, so actual OS focus
  behavior remains for user smoke testing. No user downloads/preferences/history
  changed, no real app restart or internet disconnection, no Windows live check.
- Docs: docs/NOTIFICATIONS.md. Preview /private/tmp/GrabXNotificationPreview.java;
  snapshots /private/tmp/grabx-notifications-*.png. Persistent copies are in the chat's
  visualization directory. Next user check: Stop/Run the updated build, inspect the
  bell and Settings > Notifications. Extension reload is unnecessary.
- Proposed next batch: explicit remove-task versus delete-file confirmation;
  remember window geometry/startup view; optional native OS notifications when GrabX
  is in the background. Torrent and Safari remain deferred. User wants several next
  steps presented together; do not treat proposals as already authorized.

## Latest outcome: optional startup resume (2026-10-06)

- Implemented the authorized next step in Settings > Tasks: Resume interrupted
  downloads when GrabX opens. Default off, draft-based Save/Discard/Keep editing,
  persistent preference; saving it does not start paused downloads immediately.
- History adds a backwards-compatible trailing interruption boolean. Shutdown
  captures running/preparing/queued rows before pauseAll; repeated late snapshots
  preserve this intent. Manual pause/Pause all, failure, cancellation and completion
  remain excluded. Old active states identify crashes; ambiguous old PAUSED records
  stay manual. Existing saved data verification and transfer paths are unchanged.
- Startup reconciles disk state before submitting only still-paused interrupted
  rows through the coordinator/shared scheduler. It respects the concurrency cap,
  skips newly completed/failed rows, and does not start work during shutdown.
  Disabled startup consumes old intent so a later opt-in does not revive past pauses.
  Restored rows now receive history autosave listeners for subsequent state changes.
- Verification: Java 21 full test + installDist with GRABX_TEST_RESUME=true passed:
  267 discovered, 257 passed, 10 optional skips, zero failures/errors. All 34 native
  resume tests passed. Added normal shutdown/repeated saves, crash/legacy migration,
  default-off/reload, manual pause, failed/completed reconciliation, shared limit,
  disabled-then-enabled launch and close-during-load checks.
- Isolated JavaFX harness verified startup draft, Keep editing, discard, confirmation
  Save, and existing five-section settings behavior at 1200x800 and 900x500. Snapshot
  /private/tmp/grabx-settings-tasks.png inspected. No real app restart, downloads,
  history or preferences changed. Browser extension unchanged; no reload needed.
- User now wants multiple concrete proposed next steps grouped at the end to reduce
  back-and-forth. Suggested next batch: clearer delete/clear confirmations, desktop
  notifications with preferences, then appearance/window-memory options. These are
  proposals, not implemented or separately authorized yet. Torrent/Safari deferred.

## Commit checkpoint (2026-10-06)

- User explicitly requested committing the accumulated work and reiterated that
  completed responses must include a concrete next development step. This local
  checkpoint covers persistent resume, browser handoff/icons, full/compact download
  presentation, original sound feedback, settings, speed/folder preferences and
  the shared task queue. No push requested.
- Commit preparation: git diff --check passed; all 32 browser-extension tests
  passed again. Java/native/UI verification is recorded below; those checks were
  not rerun solely for committing. Historical "uncommitted" notes below describe
  their original sessions and are superseded by this checkpoint.
- Proposed next feature: optional startup auto-resume, off by default, honoring
  the shared task limit and distinguishing interrupted tasks from intentional
  pauses. Failed/expired-link tasks should still require user action. Then consider
  delete-confirmation preferences. Torrent and Safari remain deferred.

## Latest outcome: task concurrency and shared queue (2026-10-06)

- User authorized the next settings step: simultaneous download limit and queue.
  Added Tasks section with max active 1–16, default 3, persisted via TaskPreferences.
  It participates in existing draft Save/Discard/Keep editing behavior. Startup
  auto-resume and expanded delete-confirmation preferences remain future work.
- DownloadScheduler now admits direct, video/audio, playlists, retry and resume
  requests through one FIFO queue, independent of display sorting. Preparation,
  ordinary resume verification, post-processing and worker cleanup retain slots.
  Raising the limit fills slots; lowering it does not interrupt running transfers.
  Waiting rows say "Queued • waiting for a free slot" and can be paused/cancelled.
- Coordinator pause/cancel/retry withdraw pending requests. Deferred admission
  prevents Pause all / Cancel all from starting waiters midway through the bulk
  action. Direct paused responses still resume in place, but only after admission.
  Runner worker-release callbacks prevent overlapping a stopped worker on quick
  resume/retry. Re-adding a paused duplicate requests QUEUED admission, not PENDING,
  so it is not mistaken for an already active worker while cleanup is in progress.
- PlaylistBatchService submits all selected rows to the shared queue with per-row
  mode/quality; removed its independent sequential terminal-listener chain.
- Scheduler closes before shutdown pauseAll. History restores queued tasks as Paused
  for explicit Resume/Resume all, including after abrupt exit. Existing live rows,
  transfer paths, saved bytes and validation rules are preserved.
- Verification: full Java 21 suite with GRABX_TEST_RESUME=true passed (261 discovered,
  251 passed, 10 optional skips). All 30 native engine/resume tests passed, including
  real local-server queue/pause/resume/cancel with byte-for-byte output comparison
  and queued-history restoration. A new playlist test initially had the wrong
  fixture quality default; corrected the fixture to explicitly test fallback.
  After a final paused-duplicate fix, 19 focused scheduler/queue/factory/playlist
  tests passed, including its new regression case; final installDist passed.
- Isolated JavaFX harness verified all five settings sections, task-limit draft,
  discard/save/Keep editing, and footer visibility at 1200×800 and 900×500.
  Preview: /private/tmp/grabx-settings-tasks.png. git diff --check passed.
  No real downloads, history or preferences changed; app not restarted; uncommitted.
- Next: after current downloads finish, Stop/Run, choose Settings > Tasks, save a
  limit and add more tasks than the limit to inspect the queue. No extension reload.

## Latest refinement: unsaved settings confirmation (2026-10-06)

- Replaced "Stay here" with "Keep editing". Replaced the stock question-mark Alert
  header with a custom Dialog content layout: amber pencil badge, concise heading,
  wrapped explanation, separator and explicit action hierarchy.
- Discard sits on the left; Keep editing and blue Save changes sit on the right.
  The safe keyboard default remains Keep editing; window dismissal keeps the draft.
  Save still validates before allowing navigation. No transfer logic changed.
- installDist passed. Isolated JavaFX settings harness verified Save, Discard,
  Keep editing, window close/dismissal and invalid Save, plus page draft/navigation
  regressions. Preview: /private/tmp/grabx-settings-confirm.png. No real downloads
  or preferences touched; full engine suite was not rerun for this visual refinement.
- Next: Stop/Run after active downloads finish, edit a setting and press Back to
  see the new confirmation. Changes remain uncommitted.

## Latest outcome: settings workspace and download preferences (2026-10-06)

- User deferred Torrent and Safari. Authorized a comprehensive, extensible settings
  page inside the main window, preserving sound settings and confirming unsaved
  edits on return. Implemented four sections: Downloads, Network, Sounds, Storage.
- SettingsPage replaces only the library content. The gear becomes Back, other
  toolbar actions hide, the original library node is retained for return, and the
  download services/global speed footer continue running. Back and main-window
  close offer Save changes / Discard / Stay here; dismissal and invalid Save stay
  on the page. Discard changes restores the last saved draft across all sections.
- Fixed default folder is independent of remember-last behavior; legacy last-folder
  preference remains active by default. Per-task/browser-selected destinations and
  existing download paths are preserved. Newly selected defaults must be writable
  existing directories; an unavailable unchanged folder does not block sound edits.
- Speed settings explicitly distinguish a shared direct-download ceiling from a
  per-media-task ceiling (NOT a single app-wide cap). Direct streams share one live
  interruptible token bucket, including segments and full-prefix resume checks.
  yt-dlp receives --limit-rate on each process launch; existing media tasks need
  pause/resume to adopt a changed limit. Some live/external media downloads may not
  honor it. Default unlimited; units decimal KB/s and MB/s. No forced restarts.
- Existing sound enable/volume and six previews are retained. Preview uses draft
  volume only; existing fractional volumes do not falsely mark the page dirty.
  Existing cancelled-part retention 1–365 days is retained; paused/failed/completed
  data is not swept by this policy. Future settings can be added as real sections.
- Verification: full Java 21 suite with GRABX_TEST_RESUME=true: 248 discovered,
  238 passed, 10 optional skips, zero failures/errors; all 28 resume cases passed.
  Seven new limit/preferences tests cover combined concurrency, prefix comparison,
  interruption, live/unlimited changes, folder migration/reload and media options.
  Isolated JavaFX harness covers four sections, dirty drafts, Save/Discard/Stay,
  validation and 1200×800 / 900×500 rendering; action bar stays visible while content
  and sidebar scroll. Final installDist built. No real downloads/history/preferences
  changed, no running app restarted. Changes remain uncommitted.
- Details and known rate-limit scope: docs/SETTINGS.md. Preview harness:
  /private/tmp/GrabXSettingsPreview.java; images /private/tmp/grabx-settings-*.png.
- Next: after current downloads finish, Stop/Run and open Settings to try the new
  workspace. No browser-extension reload. Torrent/Safari remain deferred.

## Latest check: IntelliJ constructor diagnostic (2026-10-06)

- User screenshot reported "Expected 6 arguments but found 7" at the compact cell
  factory in MainController. Current source already has both the six-argument
  compatibility constructor and the seven-argument constructor accepting the
  verification-window callback; the call and helper method match. No source fix
  was needed, and no duplicate source class was found.
- Forced Java 21 compileJava --rerun-tasks passed. Live IntelliJ Problems now shows
  11 warnings/suggestions and no constructor error, consistent with stale editor
  analysis in the screenshot. No cache reset or project sync was performed.
- No application launch, real transfer, history, or preference changes. Next:
  run the application normally to inspect the previously implemented compact UI.


## Latest outcome: running-first lists and compact verification (2026-10-05)

- User wants currently downloading rows first in full and compact mode, and the
  compact resume-verification presentation to match the full card. Their temporary
  image attachments were unavailable; implementation follows their text request.
- DownloadService now ranks DOWNLOADING first, PENDING/QUEUED next, PAUSED next,
  then terminal rows. Both sorted views use the same comparator and immediately
  re-sort on state transitions. Newest/Oldest controls ordering within each group;
  Last 5/10 keeps all active rows and limits only terminal history as before. Custom
  comparators also preserve state priority. Compact still ignores main sidebar/search
  filters. Existing isActive membership (including paused) is unchanged.
- ResumeVerificationPane now has a compact variant reused in CompactDownloadRowCell:
  amber checking-before-resume heading/percentage, labeled verification speed/check
  time left, saved size and a short explanation. The ordinary compact status is
  hidden only during this phase; pause/download transitions and cell reuse detach
  bindings correctly. Saved-progress bar remains unchanged, not verification percent.
- Compact opens at 260px height when verification is active and grows to 260px if
  checking appears while it is open, within existing 160–290px limits. A taller
  user-sized window is retained; no automatic shrinking or repositioning afterward.
- Verification: 8 DownloadServiceTest cases passed with zero skips/failures, covering
  priority, both views, pause/resume/completion, Newest/Oldest/Last 5/10, filters and
  removal. JavaFX harness rendered actual compact ListView cells at 350/390px widths
  and 260px height, checked labeled metrics, bounds, pause/download transitions and
  recycled-cell detachment. Preview: /private/tmp/grabx-compact-resume.png. No real
  download/history/preferences touched. git diff --check passed; installDist built.
- Next: after current transfers finish, Stop/Run to load the UI changes; inspect the
  new order and enter Compact while checking a resumed task. No extension reload.
  Changes remain uncommitted.

## Latest outcome: quiet download-event sounds (2026-10-01)

- Added six original, local PCM WAV cues (start, resume, pause, cancel, complete,
  error), synthesized by scripts/generate-feedback-sounds.py. Durations 0.285–0.545s,
  soft attack/release and silent ends; no external assets, network or new dependencies.
  Default enabled with 30% app volume, independent of system volume.
- DownloadSoundObserver attaches to list rows without touching engine behavior.
  Start/resume waits for actual new payload bytes, stays quiet during checkpoint
  reconciliation/verification, and does not repeat for automatic reconnects or
  extracted video/audio phases. Restored history is silent; listeners detach on
  removal and before shutdown's pauseAll. Explicit state changes announce pause,
  cancel, completion or failure. Tiny transfers may only announce completion.
- SoundFeedbackService uses Java Sound on a daemon worker, software volume, one
  audio line and a coalescing/cooldown queue: bursts select one cue, with terminal
  outcomes taking priority; no overlapping cue layers. No audio I/O under the
  shared UI-call lock. Missing/busy devices fail quietly. No JavaFX media dependency.
- Settings now combines the existing 1–365 day cancelled-part cleanup setting with
  enable/mute, volume and per-cue Preview. Save persists, Cancel preserves values;
  preview uses draft volume. Dark styling matches GrabX. PCM assets are packaged in
  the JAR; startup/history and application exit stay quiet.
- Verification: Java 21 full suite with GRABX_TEST_RESUME=true and installDist passed:
  239 discovered, 229 passed, 10 optional skips, zero failures/errors.
  Nine sound/settings tests cover transition timing, verification silence, reconnects,
  history/removal/shutdown, burst coalescing, preferences, preview, PCM bounds and
  retention validation. Native Java Sound hardware smoke passed with a silent clip.
  Isolated JavaFX harness verified settings render, preview dispatch, mute, validation,
  Save and Cancel. Preview: /private/tmp/grabx-sound-settings.png.
- Listening sample: /private/tmp/grabx-sound-preview.wav, at the default app gain;
  order: start, resume, pause, cancel, complete, error. Sound pleasantness remains
  subjective; user can audition and adjust. No real downloads or user preferences
  were modified or restarted. git diff --check passed. Changes remain uncommitted.
- Next: let the current transfer complete, then Stop/Run; use Settings > Preview to
  audition or mute/adjust the cues. No browser-extension reload needed.

## Latest outcome: a distinct verification phase in download cards (2026-10-01)

- User's screenshots show 729.4 MB saved with 2.0 MB/s and 04:29 during verification;
  those are comparison traffic and remaining comparison time, not new file bytes or
  full-download ETA. Showing them in the ordinary download footer was misleading.
- Added ResumeVerificationPane to the main card: amber "Checking before resume"
  heading with verification percentage, explicitly labeled "Verification speed",
  "Check time left" (approximate), "Saved", and a sentence explaining that download
  resumes after checking server data. Metric labels wrap on narrower cards; initial
  values say Measuring/Estimating. The ordinary footer is hidden during verification
  and returns automatically afterward. The main progress bar remains saved progress.
- This is presentation only: no download engine, state model, raw speed aggregation,
  sidecar/history, pause logic or compact view changed. Bindings detach on cell reuse;
  paused/cancelled/finished rows cannot retain the active verification panel.
- Verification: Java 21 installDist and git diff --check passed. Temporary native
  JavaFX harness checked labels, unchanged byte/progress properties, pause, transition
  to normal downloading and cell reuse. Inspected real card renders at 1120px and
  700px: /private/tmp/grabx-verification-phase.png and
  /private/tmp/grabx-verification-narrow.png. No real task interrupted or files changed.
- Next: allow the current download to finish, then Stop/Run to load the new card.
  No extension reload required. Changes remain uncommitted.

## Latest outcome: verification speed, ETA and overhead (2026-10-01)

- User confirmed the real MediaFire task resumed (525.5 MB -> 549.7 MB), then asked
  for verification time remaining and faster resume if possible. IntelliJ's existing
  console showed app startup at 07:24:21, not verification start/end, so the previous
  estimate of roughly seven minutes at 1.2 MB/s was illustrative, not measured.
- Verification now populates row.speed and row.eta using only verified network bytes
  and the remaining saved prefix. The ordinary saved-byte counter/progress do not
  advance. Global speed includes this real traffic through its existing aggregation.
  Estimates are initially blank, reset after Pause/Resume, and clear before normal
  download telemetry begins. Start/completion INFO logs include byte counts and
  elapsed wall-clock milliseconds (including pauses), without URLs/tokens.
- DirectResumeVerifier now reuses two fixed comparison buffers, buffers local reads,
  and reports each actual network read instead of allocating new arrays and waiting
  for a full block. It still compares every saved byte, never consumes the unsaved
  tail during verification, and appends using the same response only after success.
  This reduces allocation/disk-call overhead; no wall-time/network speedup is claimed.
- Checked the existing Pause path: it keeps the same connection and position while
  usable. The integration test now asserts no second payload request after resuming,
  visible verification speed/ETA, unchanged saved progress, and cleared paused/final
  metrics. Without a server validator after app restart, a full prefix reread is still
  necessary for this verification strategy; no sampling or unsafe metadata adoption.
- Verification: 38 targeted tests passed (28 actual-runner recovery tests, 5 verifier,
  2 rolling-rate and 3 global-speed tests), zero failures/skips; installDist passed.
  After the final buffered-disk-read adjustment, all 5 verifier tests and installDist
  passed again. git diff --check passed. No real transfer, partial/history or running
  app changed.
- Next: let the current download finish before Stop/Run; verify speed and remaining
  verification time on a future resume. No extension reload needed. Changes remain
  uncommitted. A substantial reduction in network reread time remains server-dependent.

## Latest outcome: MediaFire resume without validators (2026-10-01)

- User's MediaFire task failed after restarting with "Cannot verify remote identity".
  Read-only inspection found a valid version-1 sidecar, segments=0, Range support=Yes,
  total=1,958,523,729 and an intact 525,467,648-byte part. Neither ETag nor
  Last-Modified was saved. A live one-byte request to the saved download URL returned
  HTTP 206, the same total, and neither validator. The link was accessible during
  diagnosis; this was the runner's strict metadata requirement, not an expired URL.
- DownloadRunner now handles persisted single-stream parts with no saved validator by
  comparing every local prefix byte against a full HTTP 200 response before opening
  the part for append, then consuming the remainder from that same response. No
  separate unvalidated trailing request and no sampling. Known size changes and byte
  mismatches stop without modifying the part; full-sized parts also need verification.
  Strong-ETag/Last-Modified and segmented/legacy verification paths remain strict.
- The UI reports "Verifying saved data" and a comparison percentage. Verification
  rereads the saved amount over the network on every reconnect/restart; it cannot
  save that bandwidth without reliable validators. Prefix network EOF retries;
  pause/resume/cancel and actual on-disk progress remain supported. Verification
  alone never increases the saved-byte count. No browser needs to stay open, but
  finishing any incomplete download still requires reaching its file server.
- Added nine actual-runner regression cases for restart without validators, weak/absent
  validators and mismatched contents, complete parts, interruption during comparison,
  pause/resume/cancel, no-range fallback, loss of a previously strong validator,
  changed size, and Retry from the user's prior FAILED state. Initial targeted run
  passed 32 tests. Final Java 21 full suite with GRABX_TEST_RESUME=true and
  installDist passed: 228 tests discovered, 218 passed, 10 optional skips, zero failures/errors.
  All 28 runner integration tests passed; git diff --check passed.
- User's actual part, sidecar and history were not changed or resumed. Only a one-byte
  server probe was made. Next: Stop/Run the updated app and use the circular Retry
  button on the existing failed task; expect a prefix-verification phase before new
  downloaded bytes increase. No extension reload is needed for this engine fix.

## Latest outcome: browser actions foreground GrabX (2026-09-30)

- User reports Video/Audio prepares Add Link in the app but leaves the browser in
  front. Incoming captures previously called JavaFX show/toFront/requestFocus only;
  existing Add Link reuse updated fields without raising its window, and the
  extension popup stayed open after delivery.
- WindowActivationService restores minimized windows and uses the JDK Desktop
  APP_REQUEST_FOREGROUND feature when supported (macOS), alongside normal JavaFX
  focus. Native activation is invoked only for browser handoffs. No always-on-top
  flag, additional permissions, external activation scripts or repeated focus loop.
- New and reused Add Link windows are raised/restored; existing dialogs still reuse
  their current URL/mode workflow. Extension 0.2.6 closes its popup only on successful
  delivered status, retaining errors and queued/awaiting-confirmation requests.
- Verification: all 32 browser-extension tests passed, including Video/Audio popup
  dismissal and error/queue preservation. 14 targeted Java tests passed for Add Link
  flow/factories and native delivery; installDist and git diff --check passed.
  Temporary macOS JavaFX harness restored a minimized owner, focused new and reused
  Add Link dialogs, retained the new Audio choice, and verified no duplicate dialog
  or always-on-top setting. No network/real download/history changes. Full Brave
  click-through and Windows/Linux native focus behavior remain manual checks.
- Next: pause/finish active downloads, Stop/Run the app and Reload the extension
  in brave://extensions; click Video or Audio to verify the complete browser-to-app
  focus transition. Native protocol/registration did not change, so no native-host
  reinstall is required for this revision. Changes remain uncommitted.

## Latest outcome: complete file-preview family (2026-09-30)

- Extended the approved layered video-player style to audio, images, archives,
  PDF, text documents, spreadsheets, presentations, applications/installers, disk
  images, code/config files, ebooks and a neutral unknown-file fallback. Each
  category has its own vector symbol and accent color; captions show the actual
  extension, including compound archives such as TAR.GZ. Added common extensions
  such as OPUS, AVIF, ODS, DEB, ISO and EPUB without any network lookup or assets.
- Known file extensions now take precedence over generic Video/Audio mode hints;
  mode fallback still handles titles with no recognizable extension. Basename
  parsing handles Unix/Windows paths, ignores hidden-dotfile names as extensions,
  and bounds unknown-extension captions. Real thumbnails remain unchanged.
- Verification: FileTypePreviewTest passed (3 tests, zero failures/skips), covering
  mode precedence, basename/compound/case handling and safe unknown fallbacks.
  installDist passed; final archive zipper detail was rebuilt afterward. Temporary
  JavaFX gallery rendered 15 normal and 15 compact examples; inspected both sizes
  and verified every compact preview fits the existing 46px slot. Final previews:
  /private/tmp/grabx-file-types.png and /private/tmp/grabx-file-types-compact.png.
  git diff --check passed. No download engine, history, parts or browser icon changed.
- Next: pause/finish active downloads and Stop/Run the app to display the new
  in-card and compact-view fallback artwork. No implementation work remains here.

## Latest outcome: folder access and video placeholder (2026-09-30)

- User requested folder access during Downloading/Paused and authorized improving
  the in-card file artwork if helpful. Browser extension icon remains the existing
  GX design; its separate experimental redesign is still deferred.
- Folder is now enabled for active rows (including queued/preparing) when their
  destination directory exists. It opens the actual output parent, falling back to
  the configured destination before an output is known. Hidden/segmented partials
  and an absent final file do not mark an unfinished task Missing. Completed rows
  still reveal the final output with existing missing-file behavior.
- FileManagerService.reveal opens directory targets themselves on macOS, Windows
  and Linux, preserving the existing file-selection commands for ordinary files.
- Video files without thumbnails now show a small layered video-player illustration
  and format caption (MP4/MKV/etc., VIDEO when unknown) using local JavaFX vectors.
  Real thumbnails and other file-kind artwork remain in place. Compact view uses
  the same scaled artwork and fits its 46px media slot.
- Verification: Java 21 targeted DownloadRowActionsTest + FileManagerServiceTest
  and installDist passed (13 tests, zero skips/failures). Tests cover active/paused
  Direct and Video modes, hidden parts, selected output directory, unavailable
  directories, completed files and directory-opening commands on all three OSes.
  Temporary JavaFX harness exercised the actual card's enabled folder button and
  callback in both states, checked unchanged state/progress and compact artwork
  dimensions, and rendered inspected snapshots. Its initial source-launcher run
  hit a classloader access limitation; compiling the harness on the shared app
  classpath resolved it. No real Finder action or user download was touched.
  Visual previews: /private/tmp/grabx-folder-preview.png and
  /private/tmp/grabx-video-previews.png. Full resume suite was not rerun for this UI
  change; earlier results below retain their scope. git diff --check passed.
- Next: pause/finish any active download, then Stop/Run the app in IntelliJ to load
  the enabled folder button and updated fallback video artwork. No extension Reload
  is needed for these app-only changes.

## Latest outcome: rejected resume belongs under Cancelled (2026-09-30)

- User's old 720p (3) task is PAUSED with HTTP 498. Read-only inspection confirmed
  16 hidden parts totaling 1,039,467,075 bytes out of 1,050,937,212 (98.91%), leaving
  11,470,137 bytes. No final output/sidecar exists for this legacy task. No user
  history, parts or running downloads were changed by the investigation.
- Direct errors requiring intervention now become FAILED rather than PAUSED.
  The existing Cancelled filter already includes FAILED, while Missing remains
  reserved for absent completed files. FAILED keeps saved bytes indefinitely;
  it does not trigger the explicit CANCELLED retention policy. Failed link updates
  and unreadable saved metadata also remain FAILED. Retry retains Direct progress.
- History restores failure reasons and migrates known old PAUSED failure messages,
  including the truncated 498 message, without converting ordinary pauses. Error
  text is no longer cut at 90 characters; the card has a wrapping status tooltip.
  The existing right-click Update download link and resume action accepts FAILED.
- Verification today: Java 21 `GRABX_TEST_RESUME=true ./gradlew test installDist`
  passed with 211 tests discovered, 201 passed, zero failures/errors and 10 optional
  skips. All 19 SmartResumeIntegrationTest cases ran, including HTTP 498, persisted
  old/new failures and successful retry from the existing byte offset. Added filter
  and migration checks; retention test confirms aged FAILED parts remain intact.
  The first run caught an overbroad migration match against a normal paused-status
  message; narrowed it and reran the suite successfully. Final callback adjustment
  lets Update Link continue after a still-exiting FAILED worker; installDist compiled
  and packaged it afterward. git diff --check passed. Live app was not restarted.
- A fresh link must identify the same actual file. The new screenshot's 2 GB
  download cannot be used to continue the old 1.05 GB version. No fresh link to
  that same older resource was provided, so recovery of the actual remainder
  remains unverified; the implementation does not restart or discard it silently.
- Next: after active downloads finish (or are paused), Stop/Run in IntelliJ to
  apply the history migration; the old task should appear under Cancelled with
  its reason and saved progress. Right-click Update download link and resume
  requires a working direct link for the exact original file.
- Icon work was explicitly deferred by the user. Kept the currently built GX
  icon active; the unfinished G/capture-arrow draft is saved separately at
  /private/tmp/grabx-capture-icon-draft. Browser icon design remains deferred; the
  later request above separately authorizes improving the in-card file artwork.

## User verification: direct transfer starts (2026-09-30)

- New user screenshot shows an active Direct transfer, roughly 11.4 MB / 2.0 GB,
  with speed and "Resume support: Yes". This confirms a transfer starts in the
  updated UI; it does not establish completion or an actual interrupted resume.
  Their praise referred to the working transfer, not acceptance of the GX icon.
- User confirmed they meant the extension icon, then deferred icon work to focus
  on the old interrupted download. Existing GX is the temporary design to keep.

## Latest outcome: GX extension identity (2026-09-30)

- User disliked the generic blue download arrow. Replaced it with custom GX
  vector letterforms on a navy rounded panel, using the existing app's gx-brand
  colors (#1C2230 family, #9FB6FF accent, #E8ECF3 text). No font dependency.
  Updated SVG and the Java2D generator; regenerated all four manifest PNG sizes.
  Extension version is 0.2.5. Download/bridge/resume behavior was not edited.
- Visually inspected the actual 128px, 32px and 16px PNGs. Existing popup/icon
  checks passed (2 tests); installDist and git diff --check passed. No Java test
  rerun for this asset-only change. Next: Reload the
  extension in brave://extensions to refresh toolbar/context-menu/popup branding.
  The browser-working mxdrop download still needs a user test with the updated
  app/extension as recorded below; the new screenshots only establish icon visibility.

## Correction: browser download succeeds (2026-09-30)

- The user corrected their earlier answer: Brave itself DOES download the file.
  The previous conclusion that this example fails independently of GrabX is withdrawn.
  HTTP 498 was observed for GrabX's request, not established for the successful
  browser request. Do not describe the site/link as generally broken or insist
  that the user first find a browser-working link; they already have one.
- Reviewed the current path: browser final URL, Referer and User-Agent reach the
  native file probe and Direct engine; cookies/authorization are not transferred.
  Both preflight and the Direct engine start with a bytes=0-0 request. Missing
  session context, different request behavior and link lifetime remain hypotheses,
  not a confirmed cause. No new transfer code was changed on this correction alone.
- User confirmed they have NOT yet tried the updated app/extension. Next: Stop/Run
  GrabX and Reload the extension, then retry the same browser-working link. That
  fresh result is needed before attributing remaining failure to session context
  or changing HTTP behavior. Earlier test
  results below belong to the implementation turn, not a rerun on this correction.

## Latest outcome: browser file links and download icon (2026-09-30)

- User reports mxdrop.top's Download > Save As never starts, and context-menu
  Download with GrabX opens the unsupported video dialog. They confirmed that
  Brave also fails with interception disabled (later corrected above). Existing local task history shows
  a mxcontent.net direct transfer paused with HTTP 498 and saved data retained.
  The exact host-specific meaning/cause of 498 is unconfirmed; this is not evidence
  that the latest resume changes broke the site. The supplied page/download page
  were inspected read-only; they require browser-side link generation/challenges.
  No challenge was bypassed and no live media file was downloaded.
- The context-menu classifier previously relied on URL extensions, so opaque CDN
  URLs went to unsupported video analysis. Native inspectFile now probes response
  metadata; verified files use Direct mode, while HTML/rejections explain the
  problem in the extension. YouTube and GitHub handling remains covered by tests.
- Running-app file handoffs now preflight server acceptance before cancelling an
  intercepted browser download; failed inspections resume the browser transfer.
  This is not transfer-start acknowledgement: a server can reject a later request.
  Closed-app queue confirmation is preserved. Known downloads without Content-Type
  remain eligible; JSON/XML/script/text downloads are not excluded by a MIME allowlist.
- Browser User-Agent now travels with Referer through native messaging, task
  creation, probing, transfers, retries and persisted resume metadata. No cookies
  or authorization headers were added. HTTP 498 is surfaced clearly; there is no
  site-specific resolver or claim that this host currently downloads successfully.
- Extension 0.2.4 includes blue/white download-arrow icons (16/32/48/128), manifest
  toolbar/context-menu declarations and popup branding. Recent errors are read by
  the popup rather than only stored. SVG + reproducible Java2D PNG generator included.
  The actual PNG was visually checked; native Brave UI control was unavailable,
  so the real browser menu and extension reload remain manual checks.
- Verification today: Java 21 `GRABX_TEST_RESUME=true ./gradlew test installDist`
  passed: 206 discovered, 196 passed, zero failures/errors, 10 optional native/media
  tests skipped. All 17 SmartResumeIntegrationTest cases passed, including custom
  browser headers across retry and saved state. All 30 extension tests passed.
  Native distribution smoke verified framed status + opaque-file inspection with
  User-Agent/Referer/Range against a tiny local HTTP fixture, without enqueueing.
  `git diff --check` passed. Existing user downloads/history were not modified.
- Rebuilt distribution and refreshed the already registered native bridge under
  ~/Library/Application Support/GrabX/native-host without changing browser origins.
  Source edits remain uncommitted. See browser-extension/README.md for behavior
  and preflight limitations. Existing resume work and unrelated edits are preserved.
- Next: Stop/Run GrabX in IntelliJ and Reload the existing unpacked extension in
  brave://extensions to activate app/JS/icon changes. A real mxdrop transfer through
  GrabX remains unverified; the user has since confirmed that Brave can download it.

## Follow-up: IntelliJ unresolved resume symbols (2026-09-30)

- User screenshot reports the old four-argument verifier, missing Verified.segments,
  and missing DirectDownloadState in MainController. Current disk source has all
  three definitions and no duplicate source files. Forced Java 21 compilation with
  `./gradlew compileJava --rerun-tasks --console=plain` succeeded today.
- Likely stale IDE file/index state; not confirmed in the live UI. Computer Use
  returned “permissions are not granted”, so no IDE synchronization was performed.
  No source or IDE configuration changes were necessary. Next: use IntelliJ's
  File > Reload All from Disk, then check whether the three red errors disappear.

## Latest outcome: persistent direct-download recovery (2026-09-30)

- Inspected the existing internal HTTP/segmented engine, yt-dlp route, task creation,
  history, pause/resume, link replacement and browser bridge before editing. The
  old direct engine had a 120-second read timeout and three detached reconnects;
  initial remote identity was not persisted and paused/failed parts expired.
  These are code findings; no log from the user's original failed transfer exists.
- Direct tasks now retain a single controllable process during bounded automatic
  network retries, probe actual Range support, verify identity and payload ranges,
  reject unsafe 200 responses, and keep progress/parts on interruption or expiry.
  Paused/failed parts no longer age out. Cancelled-task retention remains configurable.
- New atomic per-output `.grabx.resume` metadata stores original/current/final URL,
  Referer/User-Agent, validators, total size, capability and fixed segment layout.
  Part lengths are authoritative on restart. History uses ordered atomic snapshots;
  running/checking tasks restore paused with disk progress. Incomplete history does
  not disappear at the normal age cutoff. Concurrent tasks cannot share an output.
- Existing Update Link now persists verified replacement state. A general resolver
  registry is wired into expired-link handling; no site-specific resolver is installed.
  Replacement/legacy verification can reread saved bytes for safety. Browser session
  capture remains a separate future enhancement; yt-dlp behavior was preserved.
- Final verification: Java 21 `GRABX_TEST_RESUME=true ./gradlew test installDist`
  succeeded. 199 tests discovered, 189 passed, zero failures/errors, 10 optional
  tests skipped (4 native compact-window, 6 real yt-dlp/FFmpeg checks). All 16 new
  actual-runner/socket integration tests ran and passed. Includes drops at 95–99%,
  timeout recovery, pause/restart/history restoration, HTTP safety, 416, redirects,
  segmented assembly, retry cancellation, resolver rejection and output ownership.
  Final `git diff --check` passed. Earlier targeted run: 30 tests, no skips/failures.
  One initial test assertion incorrectly compared an integer 95% byte offset with
  an unrounded floating value; corrected it after byte-perfect output already passed.
- All fixtures used temporary files and localhost. No actual Wi-Fi/hotspot switching,
  physical sleep, full-app force-quit, Windows/Linux execution or live host resolver
  was tested. User downloads/history were not changed. Distribution refreshed;
  the currently running IDE app/native host was not restarted or restaged.
- Full implementation, changed-file inventory, verification and limits are recorded
  in `docs/SMART_RESUME.md`. Changes are uncommitted; no commit/push was requested.
- Next useful step: Stop/Run GrabX in IntelliJ, then validate a disposable real Range
  download across a short Wi-Fi interruption and application restart. This is the
  remaining manual integration check, not additional implementation already run.

## Previous checkpoint (2026-09-29)

Updated: 2026-09-29 after preparing the user-requested daily UI checkpoint.

Today's compact-view and ETA changes are included in the requested checkpoint
commit, `Refine compact view controls, dragging and remaining-time display`.
Verification is recorded below; no push was requested.

## Latest outcome: clearer toolbar over download media (2026-09-29)

- Darkened the translucent toolbar gradient. The download thumbnail/file preview
  and title now fade to 22% opacity in sync with the existing toolbar animation,
  returning to 100% when it hides. Status, progress and download buttons stay clear.
  A shared binding handles newly created/reused cells while the toolbar is visible;
  the empty-state icon follows the same dimming. Header-only hover and sizing remain.
- Verification: Java 21 installDist and git diff --check passed. Temporary native
  preview checked 22%/100% media opacity, unchanged status/button opacity, header-only
  hover, centered controls, pause/resume and minimum layout; inspected rendered
  hover screenshot. One initial pointer run lost hover at final re-entry; repeating
  the unchanged harness passed. No JUnit rerun for this visual follow-up.
- Next: Stop/Run GrabX in IntelliJ to view the darker header and media dimming.
  No implementation work remains pending; no real downloads/history were modified.

## Latest outcome: header-only hover and aligned controls (2026-09-29)

- Replaced the compact close/minimize traffic-light circles with 26px translucent
  rounded buttons and SVG icons. They are vertically centered in the header;
  close/minimize use red/amber highlights only when individually hovered.
- A persistent transparent 34px header hit area now controls the existing fade.
  Hover over the body/footer does not reveal the toolbar; moving onto its buttons
  keeps it visible, and leaving the header fades it out. All-area dragging stays.
- Verification: Java 21 installDist passed. Updated temporary CompactPreviewCheck
  exercised actual mouse hover on body, footer, header and a header button; checked
  centered button geometry, hide after leaving, and drag from footer/status/progress/
  empty area with button exclusion. Rendered toolbar inspected. No real downloads
  or user history touched. Earlier JUnit results below were not rerun this follow-up.
- Next: Stop/Run GrabX in IntelliJ to load this revision; hover the top strip to
  view the controls. No implementation work remains pending.

## Latest outcome: drag anywhere and lighter hover toolbar (2026-09-29)

- Compact View can now be dragged from the card, status, progress bar, footer,
  and empty area. Primary-button drag uses a fixed screen-space offset and consumes
  the gesture to prevent ListView selection handlers from competing. Buttons (with
  SVG descendants) and scrollbars retain their interactions; edges still resize.
  Open/closed hand cursors indicate draggable areas and active dragging.
- Replaced the opaque hover toolbar background with a translucent gradient, faint
  highlight border, soft shadow, and reversible 140ms opacity transition. Hover now
  covers the whole compact window, including the footer. This is a glass-like tint,
  not a native backdrop-blur implementation. Hidden controls do not intercept input.
- Verified this follow-up: Java 21 build/installDist and all 4 native compact-window
  restoration tests passed. Temporary CompactPreviewCheck used actual mouse drags
  from footer, status, progress, and empty area; intermediate/final window positions
  followed the pointer, while a drag starting on a button did not move the window.
  Minimum layout, hover, pause/resume and ETA checks also passed. Inspected the new
  rendered toolbar screenshot. No real downloads or user history were modified.
- Next: Stop/Run GrabX in IntelliJ to load the changes and try dragging the compact
  window from the footer or download card. No implementation work remains pending.

## Latest outcome: smaller Compact View and hour-based ETA (2026-09-29)

- Compact View now enters at its minimum 350 × 160 instead of 390 × 290.
  Resizing remains available up to 390 × 290. Status and pause/resume/cancel share
  a row, with tighter card/footer spacing and 30px action buttons.
- Window controls overlay the download area only while hovered; they reserve no
  extra title-bar height. Hovering the toolbar itself keeps it visible. Download
  actions remain below the overlay. Window dragging checks button ancestors so
  dragging an SVG button does not inadvertently move the window.
- DownloadRowCell formats both direct mm:ss and extractor hh:mm:ss ETA values as
  hours/minutes at one hour or above (81:35 → 1h 21m). Short estimates and unknown
  placeholders stay unchanged; ETA preferred width adapts to its text.
- Verified today using Java 21: 12 targeted tests passed with zero skips, including
  all four native CompactWindowState restoration checks; installDist succeeded.
  Default Java 24 failed Gradle test-task configuration, so use the installed
  temurin-21.0.9 JAVA_HOME for these commands.
- Temporary /private/tmp/CompactPreviewCheck.java exercised the real controller
  with fixture downloads: 350 × 160 layout, hidden toolbar, actual mouse hover,
  unchanged height, unobstructed pause control, paused/resume state, empty state,
  and eight ETA examples. Rendered snapshots were visually checked. The initial
  layout assertion included the card drop shadow; checking layout bounds confirmed
  the card ends at y=120, above the footer at y=123. No real download was started.
- Build output is refreshed; the running IDE app/native-host staged copy was not
  restarted/replaced. No Windows/Linux GUI run in this turn. Prior Windows native
  host investigation below remains unresolved and unrelated to these UI changes.
- Next: Stop/Run GrabX in IntelliJ to load this build, then open Compact View and
  hover the download card to see the new layout and controls.

## Latest outcome: Windows host not found (2026-09-24)

- User's Windows screenshot shows `Specified native messaging host not found.`
  This is a host-discovery failure; the screenshot does not establish whether
  registration was skipped, targets another browser/account, or has a broken path.
  Reviewed installer and official Chrome/Edge native messaging documentation.
  Starting IntelliJ/GrabX and loading the extension do not run the installer.
- Asked which browser was used and whether `install-windows.ps1` was run after
  updating; no answer received at this checkpoint. No Windows machine is connected.
  Do not claim the Windows installation or end-to-end problem is fixed.
- Extension errors now give registration guidance with the actual extension ID
  for both control and capture requests; forbidden-origin errors remain distinct
  from missing-host errors, and original browser diagnostics are retained.
  README now includes Windows registry/manifest/wrapper inspection instructions.
- Verified today: all 23 extension tests passed; an additional inline Node VM
  check exercised missing/forbidden host errors through status, capture and handoff,
  preserving successful status responses and other error details. `git diff --check`
  passed. No Java or installer implementation changes; no Windows execution.
- Next: establish the user's browser and installation result, run registration on
  their Windows account with that browser's extension ID, then verify Open GrabX
  and Video/Audio. If registration already succeeded, inspect registry default value,
  manifest and wrapper paths before changing the installer speculatively.

## Latest outcome: Windows/Linux readiness and checkpoint (2026-09-23)

- Added Linux native-host installation for regular Chrome, Chromium, Brave and Edge.
  It honors XDG directories, safely quotes Unicode/spaced paths, pins Java 21+, and
  stages a complete Linux distribution. Windows installation now rejects non-Windows
  JavaFX libraries and replaces staged libraries without retaining obsolete JARs.
- Updated all five Windows/Linux yt-dlp binaries to official 2026.08.19 releases,
  verified their SHA-256 hashes, and added versioned, checksum-checked installation
  ahead of old cached tool paths. Added the reproducible maintenance script and
  bundled.properties manifest. macOS's previously working directory runtime is kept.
- FFmpeg discovery now tries usable installed tools before a network download;
  invalid/pre-existing/downloaded executables are not accepted just because they
  exist. Added common GUI/WinGet/local-bin locations and explicit architecture
  mappings; unsupported architectures no longer silently get x64 FFmpeg/yt-dlp.
- Windows/Linux removal confirmation uses JavaFX with explicit Remove/Delete/Cancel
  actions, removing the PowerShell/WPF and zenity dependencies for that dialog.
- Distribution archives now include the extension, native installers, and PLATFORMS.md.
  They still require Java 21+; these are portable distributions, not signed installers
  or self-contained JRE packages. Build separately on the destination OS/CPU.
- Added GitHub Actions matrix for Windows/Linux/macOS builds, Java + extension tests,
  real bundled extractor tests, protocol smoke checks, installer-wrapper checks,
  and platform-labelled artifacts. The workflow has NOT run remotely in this session;
  a local commit does not trigger remote CI and no push is authorized/requested here.
- Supported packaging baseline is Windows x64 and desktop glibc Linux x64/ARM64.
  Windows ARM requires the x64 Java/JavaFX configuration under emulation for this
  JavaFX 21 setup; native Windows ARM, 32-bit desktops, Alpine/musl, Firefox/Safari,
  and sandboxed Snap/Flatpak browser integration are not claimed as verified.

## Verification for this checkpoint

- macOS: 180 Java tests passed, zero skips, with native JavaFX and local real yt-dlp /
  FFmpeg enabled; 23 extension tests passed; 3 POSIX installer tests passed using
  isolated fixtures (browser paths, quoted/apostrophe paths, upgrade cleanup,
  wrong-platform/invalid-ID rejection). Windows/Linux binaries all match official
  hashes. These checksums are not execution tests on those operating systems.
- `test installDist distZip` succeeded; packaged extension/installers/guide inspected.
  Native protocol smoke test from build/install/GrabX returned a clean UTF-8 frame.
- No Windows or Linux execution environment is connected here. Their GUI, actual
  browser handshake, media playback, pause/resume, and new Windows installer remain
  pending native execution. The Linux installer was tested with POSIX fixtures on Mac.
- All changes from the preceding sessions plus this work are intended for the single
  checkpoint commit: `Improve download reliability and cross-platform browser integration`.
  Use `git log -1 --oneline` for its hash; do not put a self-referential hash in this file.

## Immediate next step

On Windows (the user's existing test setup), follow PLATFORMS.md: install Java 21,
Node 22+ and FFmpeg; build with gradlew.bat; register the host with the same JDK and
extension ID; verify Open GrabX, one direct download to an Arabic/spaced folder,
a YouTube video/audio download, pause/resume, and Compact View restoration.
Then repeat the Linux checklist or inspect the new CI matrix after an explicitly
requested push. Do not call Windows/Linux release-tested before those runs succeed.

## Latest outcome: Compact View restoration (2026-09-23)

- User reports intermittent screen-sized main window after returning from Compact
  View, despite starting windowed (desktop visible). Their 30-second recording
  demonstrates the compact/full-view toggles but does not reproduce the fault.
- Inspected video frames and the old restore code: it showed the main Stage, set
  geometry once, then changed maximize/full-screen flags and immediately accepted
  another toggle. This leaves late native geometry notifications uncorrected.
  The spontaneous user fault was NOT independently reproduced.
- Replaced loose geometry fields/unused windowed tracking and unused fade helper
  with immutable `CompactWindowState`. It restores modes before bounds, applies
  them before show, then checks native state per JavaFX pulse until stable for
  200 ms (bounded to one second). Screen-sized callbacks during restoration get
  corrected to the frozen frame. A minimized/hidden window stops restoration.
- MainController blocks compact re-entry until restoration settles, so rapid clicks
  cannot capture an intermediate frame. Explicit maximized/full-screen snapshots
  remain distinct from normal windowed bounds. Compact Stage clears iconified state
  when entered again.
- Full pre-final-test suite passed: 174 Java tests, with real tools and native JavaFX
  windows enabled. Added the controller integration case afterwards; all 4 native
  window tests passed in a targeted rerun. Covers 8 normal-frame cycles with injected
  late screen-sized bounds, intentional maximization, resizing after restoration,
  hiding during restoration, and 3 actual MainController compact/restore cycles with
  a rapid extra click. Tests use isolated windows and do not start history/clipboard
  monitoring or read/write the user's download history.
- Distribution built and staged Chrome/Brave-host app updated. Extension JS was not
  changed in this turn. macOS native windows tested; no Windows or full-screen Space
  visual test. Native-window regression tests opt in with `GRABX_TEST_FX=true`.

## Previous Compact View handoff

Stop/Run GrabX from IntelliJ. Start with a smaller main window and visible desktop,
then toggle Compact View and restore several times, including a quick double click.
It should preserve the same size and position. User confirmation of this originally
intermittent case remains pending; do not claim the spontaneous fault was reproduced.

## Latest outcome: sizes before download (2026-09-23)

- User asked for the extension to send sizes along with qualities so size is visible
  before downloading. Extension 0.2.3 calculates per-quality transfer estimates from
  the current player's already loaded metadata: contentLength when present, otherwise
  bitrate × duration / 8. It combines a preferred H.264 video with preferred default
  AAC audio, counts muxed audio only once, and avoids counting other codecs/dubs.
- Unknown audio/video sizes, missing duration with no length, live videos, stale player
  identity, and invalid/out-of-range values produce no size estimate. No new network
  requests were added. The app validates bounded sizes against fresh matching qualities.
- Add Link immediately shows `Estimated download: ≈ … (video + audio)` for the selected
  quality, including Best. Changing quality updates it; audio-only hides this video
  estimate. Missing sizes are explicitly unavailable, not fabricated. URL and quality
  cache keys keep estimates separate. These remain estimates because the downloader may
  select different tracks and merging/conversion changes the final file size.
- 171 Java tests passed with real tools enabled; 23 extension tests passed. JavaFX test
  `/private/tmp/GrabXBrowserSizeCheck.java` verified protocol → flow → dialog displays
  30 MB for Best, immediately changes to 12 MB for 480p, and clears the video estimate
  on audio-only mode. Fixture values were used for this UI test; no live browser player
  measurement was performed in this turn. Build and staged Chrome/Brave bridge updated.

## Previous size-display handoff

Stop/Run GrabX from IntelliJ and Reload the unpacked extension (0.2.3). Use Video in
its popup on a YouTube page and change quality in Add Link: the video+audio transfer
estimate should appear before Download when that player's metadata provides sizes.
No need to redownload a whole video just to check this display. If unavailable,
inspect that player's metadata rather than claiming a precise total.

## Earlier quality/playback outcome (2026-09-23)

- Root cause on `DodLg1SxmWI`: the video is portrait (4:3 vertical). The previous
  raw-height parser exposed only 1440p/480p, and selecting 480p actually downloaded
  360x480. Quality labels now use the advertised format note or short side; bounded
  selectors distinguish portrait width from landscape height. Filename labels use
  the same rule. The actual list is 144, 240, 360, 480, 720, 1080.
- Removed the hardcoded fallback resolutions. If analysis fails, the dialog offers
  Best quality with a retry message instead of inventing available qualities.
- Extension 0.2.2 reads the current YouTube player's already loaded quality levels
  and labels when Video/Audio is clicked. A bounded numeric list travels through
  native messaging into the dialog cache; video identity and freshness are checked.
  Unsupported APIs, stale/navigation-mismatched data, and other sites fall back to
  the app's extractor. This skips the format-list extraction when hints are usable;
  the actual download still resolves fresh stream URLs. No cookies/stream URLs are
  read from the player for this feature.
- Old Desktop MP4 was AV1 + Opus at 360x480. AVFoundation explicitly returned
  `playable=false`, Cannot Decode (-11833/-12906). Video selection now prefers
  H.264/AAC at the requested resolution and merges to MP4. Audio-only selection
  retains its prior codec policy. On macOS, an incompatible completed video is
  converted without scaling; compatible video tracks are copied when only audio
  needs conversion. Work is tracked for pause/cancel, and the original survives
  failed/interrupted conversion. Such a fallback can take additional time.
- Built the distribution and refreshed the existing Chrome/Brave native host using
  the same registered extension ID. The running IntelliJ app and unpacked extension
  still need Stop/Run and Reload respectively. Existing Desktop files were untouched.

## Earlier quality/playback verification (2026-09-23)

- 169 Java tests passed, zero skips (real yt-dlp and bundled FFmpeg enabled).
  Covers portrait format selection at all resolutions, codec preference without
  resolution downgrade, filename labels, browser hint identity/freshness/cache,
  native-video no-op, audio conversion, VP9 conversion preserving dimensions,
  and cancellation during conversion preserving the original and removing temp files.
- 20 extension tests passed, including current-player quality reading, shorts,
  stale SPA metadata, absent player, and ambiguous highres fallback.
- Actual DownloadRunner downloaded the full same 34:41 video at 480p into temp:
  H.264 480x640 + AAC, duration 2081.088435 seconds, 110,458,012 bytes.
  Combined byte counts did not reset at the audio boundary; final counters match disk.
  AVFoundation reported `playable=true` and successfully decoded a video frame.
- Actual JavaFX Add Link test passed on the final build: a validated browser payload
  traverses AddLinkServicesFactory/flow/cache and displays exactly Best plus
  1080/720/480/360/240/144. No second format extraction was needed.
- Finder UI access was unavailable to Computer Use, so Space/Quick Look itself was
  not visually checked. Native AVFoundation decoding was checked instead. The new
  extension has not been reloaded/tested against the live browser player in this run;
  its injection/parser and native dialog path were tested separately. No Windows live test.
- Temporary live test: `/private/tmp/grabx-quality-check.log`,
  `/private/tmp/GrabXQualityCheck.java`, `/private/tmp/GrabXBrowserQualityCheck.java`.
  Complete compatible video is in:
  `/var/folders/yh/p1mh5qwj0p15bdx6c2k8dx5w0000gn/T/grabx-combined-18239669357431546423/`
  (the Arabic title ending `[480p].mp4`). Temp files may disappear after a reboot.

## Remaining platform checks

Finder Space/Quick Look was not visually checked through Computer Use; AVFoundation
native decoding passed for the new complete file. Existing incompatible files were
not rewritten. Windows bridge/download verification still requires a Windows setup.
Preserve all earlier uncommitted work.

## Previous session (2026-09-22)

### Completed

- Checked the old paused Tawalo task. History still has its legacy repeated
  `.f401` path, but no Tawalo output/partial files were present on Desktop.
  Do not promise recovery of the previously observed 31 MB from that path.
- Implemented the previously discussed combined video/audio progress display.
  `MediaTransferProgress` totals only the selected streams using structured
  yt-dlp output from the existing download process (no extra extraction).
  Stream changes retain transferred bytes and the shared percentage; ETA includes
  remaining audio. Video/audio/merge phases are labeled, and combined size uses
  `≈` until the merged file's actual size is read from disk.
- Fragmented streams without known sizes use bitrate/duration estimates when
  available. Otherwise no precise combined total/percentage is claimed.
- Built the distribution and refreshed the staged Chrome/Brave native host.

### Verification

- 160 Java tests passed, including new cases for shared totals, stream changes,
  resumed bytes, repeated events, unknown sizes, and tiny first-fragment estimates.
- Actual DownloadRunner test with `https://www.youtube.com/watch?v=I6yDvA5vmqw`:
  the audio phase retained 14,926,502 downloaded video bytes; combined transferred
  size reached 15,980,549 bytes. The final merged file was 15,976,461 bytes.
  Assertions checked that bytes did not decrease between streams and that final
  row counters equal file size. MP3 output also passed (2,580,627 bytes).
- ffprobe verified complete 63.421-second 1080p video with audio and 63.432-second
  MP3. No Windows live test was performed. The extension suite was not rerun
  today because extension JavaScript was unchanged.

## Completed in the previous work session (2026-09-15)

- Fixed YouTube 403/preparation issues with the macOS yt-dlp 2026.08.19 directory
  bundle, explicit JavaScript runtime discovery, and short-lived analysis reuse.
  Cache excludes formats already rejected during analysis.
- Add Link follows newly copied URLs even while populated. Changing the URL
  invalidates stale analysis. Clipboard polling runs independently of animation.
- Fixed pause/resume creating repeated `.f401` filename suffixes. Saved legacy
  paths normalize back to the original output stem. Cleanup preserves partials
  belonging to other numbered downloads.
- Browser captures reuse a resolved browser destination. Browser URL validation
  accepts browser-valid characters; a Windows native-host installer was added.

## Verification and limits

- Previous full Java suite: 155 tests passed on 2026-09-15; distribution built.
  Extension suite: 17 tests passed earlier in that session.
- Live macOS test of `https://www.youtube.com/watch?v=vm-lZF6ukf0`: paused and
  resumed from 3,661,787 and 6,716,425 bytes, including a simulated legacy saved
  path. Completed 4K video with audio, duration 233.421 seconds. Other live video,
  MP3, and Add Link UI checks also passed.
- Windows installation and downloading have not been tested on Windows.
- Prior code changes remain uncommitted. Do not discard them.
