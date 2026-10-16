# Settings workspace

Implemented 2026-10-06. Settings replaces the main library content inside the same
window. The gear becomes Back; the original library node (filters, selection and
scroll state) is retained. Download services and the global speed footer keep running.
Download toolbar actions are hidden while Settings is open; the notification bell stays accessible. Six independent sections
leave room for future supported preferences without presenting nonfunctional options.

## Available preferences

- **Downloads:** fixed default directory and a separate remember-last-folder option.
  Existing installations keep remember-last enabled and retain their old last folder.
  New destinations must exist and be writable. The folder setting affects new tasks;
  saved/resumed tasks retain their recorded paths. A destination explicitly chosen
  in a download dialog still wins for that task. Browser-provided destinations remain
  honored; the default supplies the fallback when no destination was chosen.
- **Tasks:** maximum simultaneous tasks, 1–16, default 3. Shared by direct,
  video/audio, playlists, retries and resumes. Preparing, ordinary resume verification
  and post-processing keep their slot. Admission follows submission order, independent
  of visual sorting. Pausing releases a slot once the engine stops transferring;
  resuming joins the same queue. Raising the limit admits waiting tasks; lowering it
  lets existing tasks finish without interrupting them. Pause/Cancel all also withdraw
  pending requests before the next admission pass. Optional **Resume interrupted
  downloads when GrabX opens** is off by default. It only resumes tasks that were
  running, preparing or queued at exit, through the same limit and verification.
  Manually paused, failed, cancelled, completed and missing tasks stay stopped.
  Saving this option takes effect at the next launch, not immediately.
- **Network:** optional direct-download combined limit and independent video/audio
  per-task limit, decimal KB/s and MB/s. Default is unlimited. Values range from
  1 KB/s to 1,000 MB/s. These are **not one total application-wide ceiling**.
- **Notifications:** independent enable, completion/failure/connection events and
  foreground banners. A shared bell inbox in full/compact view keeps up to 50 events
  for the session. Disabling connection events also disables periodic checks; sounds
  remain independent. See [notification behavior and checks](NOTIFICATIONS.md).
- **Sounds:** existing enabled preference, volume and previews for all six original
  cues. Preview uses draft volume; regular download feedback changes only on Save.
- **Storage:** existing 1–365 day retention for cancelled partial downloads. Paused
  and failed data are still retained; completed files are never cleaned by this rule.

## Drafts and navigation

Edits across all sections remain a draft until Save. Back and the full window's
close action prompt Save changes / Discard / Keep editing when dirty. Dismissal means
Keep editing. Invalid values keep the page open and show an error, including when Save was
chosen in the leave prompt. Discard changes restores the latest saved snapshot;
merely switching sections does not commit or discard anything. Saving does not
close Settings. The bottom action bar remains visible at the app's 900×500 minimum;
content and sidebar scroll as needed.

## Transfer limits

One interruptible token bucket belongs to DownloadRunner and wraps every direct
payload stream: single-part, all segment workers, and the same response used for
full saved-prefix verification. It limits reads before bytes are consumed, preserves
all existing identity checks, and never holds its lock during pause/wait. Live
changes reach existing direct streams. A bounded 100ms / 16KiB token capacity and
network buffering allow brief fluctuations; probes and protocol overhead are not
part of the payload ceiling. No user download is restarted by changing this setting.

Media tasks use yt-dlp's `--limit-rate` and one concurrent fragment. The command is
copied per process launch, including retries; an already running process retains its
old setting until stopped/resumed. The limit is per task, not shared among processes.
Native HTTP/HLS transfers support the option; live streams or external downloaders
may not honor it. See [yt-dlp download options](https://github.com/yt-dlp/yt-dlp#download-options).
A true shared, live app-wide budget including every media engine is future work.

## Shared task queue

DownloadScheduler runs on the FX thread and defers admission via Platform.runLater,
so bulk actions finish before queued work can start. Reservations cover preparation,
transfer and cleanup. The runner signals worker release only after posting final UI
callbacks, preventing a quick retry from overlapping an old worker or being overwritten
by its completion callback. A paused direct response can resume in its existing worker
only after getting a new slot. Cancelled/removed requests do not come back automatically.
The scheduler closes before pauseAll on shutdown.

PlaylistBatchService now submits all selected items, with per-row mode/quality captured,
to this shared scheduler. It no longer maintains an independent sequential chain or
terminal-state listeners that could restart later playlist rows more than once.
History first restores unfinished rows as Paused and reconciles their files before
optional startup admission. A new trailing TSV boolean preserves interruption intent
across orderly shutdown (captured before shutdown's pauseAll) and repeated late saves.
For older history files, active/queued states identify abrupt exits; old PAUSED rows
are left manual because their original reason cannot be recovered. Intent is consumed
at startup even when the preference is off; enabling it later does not revive tasks
left paused in an earlier session. Closing during restoration prevents admission.
Restored rows receive autosave listeners so later state changes are persisted.

## Startup-resume verification (2026-10-06)

- Full Java 21 test + installDist with GRABX_TEST_RESUME=true: 267 discovered,
  257 passed, 10 optional skips, zero failures/errors; all 34 native resume cases passed.
- Covers shutdown versus manual pause, repeated saves, legacy crash states, failed
  link migration, disk reconciliation before admission, shared concurrency, disabled
  then enabled launches, closing during restore, and preference persistence.
- Isolated JavaFX harness verified default off, unsaved draft, Keep editing, discard,
  confirmation Save, and footer visibility at both normal and minimum window sizes.
  No real downloads, preferences or history touched.

## Earlier verification

- Full Java 21 suite with GRABX_TEST_RESUME=true: 261 discovered, 251 passed,
  10 optional skips, zero failures/errors. All 30 smart-resume cases passed.
- Seven new tests cover combined/concurrent direct budgets, prefix verification,
  cancellation, live limit changes, unlimited mode, preference migration and reload,
  and correctly scoped media command options.
- Fourteen added queue tests cover FIFO admission, completion/failure, preparing slots,
  paused/resumed/removed tasks, bulk pause/cancel, live limit changes, rapid resume
  during worker cleanup, shutdown, preference reload, playlist submission, restored
  history and real socket transfers through the engine and coordinator. The socket
  test verifies both completed files byte-for-byte and that a cancelled waiter never
  opens an output or network transfer. After the full suite, the final paused-duplicate
  admission fix passed 19 focused scheduler/queue/factory/playlist tests (including
  its additional regression test); installDist was rebuilt afterward.
- Isolated JavaFX harness /private/tmp/GrabXSettingsPreview.java exercises all five
  sections, Save/Discard/Stay, invalid retention/folder/rate, fixed-folder selection, concurrency draft/save/discard,
  and footer visibility at 1200×800 and 900×500. Memory preferences and fake audio;
  no real history, downloads or preferences changed. Native screenshots are under
  /private/tmp/grabx-settings-*.png.
- installDist and git diff --check passed. No browser-extension reload is needed.
