# GrabX handoff

Updated: 2026-09-24 after investigating the user's Windows native-host error.

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
