# Persistent direct-download resume

Implemented and locally verified on macOS, with an additional validator-free resume
path on 2026-10-01. The existing HTTP engine
handles `Direct` tasks; extracted video/audio still uses yt-dlp with `--continue`
and its own partial files. No aria2 is used and no browser connection must remain open.

## Findings in the previous implementation

`DownloadRunner.startDirect` waited up to 120 seconds for a read and only scheduled
three detached reconnect attempts. The detached retry was not owned by a live
transfer during backoff. Segmented workers also had bounded retries. A zero-speed
label did not explain those waits. The ordinary initial download did not retain
its ETag; history recorded an ETag only after manual link replacement. History
writes truncated the live file, restored incomplete progress as zero, and could
race other background snapshots. `PartialRetentionService` expired paused/failed
parts (default two days). These are code findings, not a diagnosis from a trace of
the user's original 1.1 GB transfer, which was not supplied.

## Lifecycle and persisted state

The existing runner keeps a single registered transfer through checking, downloading,
network waiting and retry. Transport EOF/reset/timeout/DNS errors and HTTP 408/429/5xx
retry with exponential delays (normally 2–60 seconds; 429 starts at 30 seconds).
Retry-After is honored up to the existing five-minute limit. Retries continue until
success, pause, cancel or an error requiring intervention. Reads time out after 15
seconds; connection establishment after 20. There is no OS reachability dependency:
the next bounded attempt detects that connectivity has returned.

Each output has an atomically replaced hidden `.NAME.grabx.resume` properties file:
original/current/final URL, output name/path, Referer, request User-Agent, ETag,
Last-Modified, total size, measured range support and segment count. It is written
before payload bytes. Hidden `.NAME.grabx.part[.N]` files remain the authoritative
byte checkpoints. Segments keep their saved boundaries; an interrupted merge is
rebuilt from segments, without double-counting its temporary combined file.

History remains a backward-compatible TSV, with additional original-URL/status
columns. Writes now use atomic replacement and ordered FX snapshots. Output path
changes are saved immediately. Restart loads the sidecar and counts bytes on disk;
previously running/checking tasks reopen paused and Resume continues them. Incomplete
tasks survive history-age filtering. Paused/failed parts never expire automatically;
explicit removal and the existing cancelled-task cleanup remain available.

Rejected links and unsafe resume checks now use `FAILED`, which the existing
**Cancelled** sidebar includes. `PAUSED` is reserved for an ordinary pause or a
restored interrupted transfer. `MISSING` still means the completed local output
is absent. A failed Direct task keeps its byte count and parts indefinitely even
though it is listed under Cancelled; only an explicitly cancelled task is subject
to cancelled-file retention. Update download link and Retry remain available.
History loading migrates known legacy PAUSED error messages (including the former
truncated HTTP 498 message), preserves failure reasons, and leaves normal pauses
unchanged. The status tooltip exposes the longer error text.

## HTTP safety

An actual `Range: bytes=0-0` probe requires valid 206/Content-Range and a body byte.
Accept-Ranges alone is insufficient. Support is reported as Yes, No or Unknown.
Redirects 301/302/303/307/308 are bounded and resolved before transferring.

Resume verifies total size plus a strong ETag, or Last-Modified plus known size,
and sends If-Range. Actual payload responses must match validators and byte ranges.
A trailing range request returning 200 is never appended. Unexpected offsets, changed
sizes/versions and encoded responses stop for user action, retaining saved bytes.
Parallel downloads require a strong ETag; other fresh downloads use one stream.

For a persisted single-stream task with no saved strong ETag or Last-Modified,
GrabX requests a full response, compares **every saved byte** against its prefix,
then appends only the remainder from that **same open response**. It does not
compare a sample or use matching URL/size as identity proof. A known size change
or prefix mismatch stops without changing the part. A short network response
retries verification; pause/cancel remain available. Even an apparently complete
part must be compared before finalization. Verification displays its own percentage,
network throughput and estimated time remaining to verify (not the time to finish
the entire download), without counting reread bytes as newly downloaded data. The
main card shows a distinct amber "Checking before resume" panel with explicitly
labeled verification speed, check time left and saved size. The ordinary download
footer is hidden until verification finishes. The rate estimate resets after an
in-process pause, and verification metrics clear
before payload download begins. Start/completion logs include bytes and elapsed
wall-clock milliseconds, including any pauses, for IntelliJ diagnosis. Reusable
comparison buffers and buffered disk reads reduce allocation and small-read overhead;
the loop still checks every byte and keeps the same response open. This rereads the saved
amount over the network on each reconnect/restart and can work without Range;
it is not a bandwidth-saving range resume. Ordinary Pause/Resume preserves the
same connection and verification position while that connection remains usable.
The buffer optimization does not establish a measured network speedup or eliminate
the required reread after restarting the app. Existing strong validators, legacy
parts without sidecars and segmented layouts retain their previous strict checks.

A verified local file already at the remote size is finalized without requesting an
unsatisfiable trailing range. A 416 for an empty resource is supported. Other 416
responses keep the partial file for verification. HTTP 401 requests authentication;
403/404/410 reports an expired/unavailable link; 412 reports a changed version.

## Replacement links and browser integration

`DownloadLinkResolvers.Resolver` supplies supports/resolve/refresh methods, with the
original/current URL and Referer as context. Register through
`DownloadRunner.linkResolvers().register(...)`. No host-specific resolver is shipped.
An installed resolver may refresh an expired link; GrabX, not the resolver, verifies
its result before adopting it. The existing manual Update Link action remains usable.

Replacement links must support Range and a strong ETag. Verification compares every
saved byte at its original offset before attaching the new URL. This can reread the
saved amount over the network, but cannot overwrite it. The same conservative check
migrates legacy partials without persisted identity. Ordinary reconnects with valid
saved validators request only missing bytes. Different identity information stops
safely; missing saved validators in persisted single-stream tasks use the full-prefix
comparison above. Filenames and matching length alone are not identity proof.

The browser extension/native bridge already exists and remains separate. It currently
passes URLs and page context; this change does not import browser cookies, bypass site
restrictions, or add authenticated-session capture. Sites needing a fresh session can
require a newly authorized link or a future resolver/session bridge.

## Verification and limits

`SmartResumeIntegrationTest` runs the actual DownloadRunner and FX adapter against
local socket fixtures. It covers repeated drops at 95%, 97.5% and 98.75%, pause/resume,
fresh task restoration from disk, a real isolated history save/load/resume, no-range
servers, 200 instead of 206, expired/auth/changed URLs, 416, complete/empty files,
pause/cancel during backoff, segmented downloads and interrupted assembly, registered
resolver refresh and rejection, concurrent output ownership, redirects, a 15-second socket timeout, 408/500/503 retries and 429.
Completed files are compared byte-for-byte with their source. State/unit tests cover
metadata corruption, segment accounting, validation, replacement-link mismatch and
retention. Fixtures touch temporary files and localhost only.

Final verification on 2026-09-30: Java 21 with `GRABX_TEST_RESUME=true ./gradlew test installDist`
succeeded: 211 tests discovered, 201 passed, zero failures/errors, 10 optional skips
(4 native compact-window tests and 6 real yt-dlp/FFmpeg checks). All 19 runner recovery
integration tests passed, including browser headers, legacy failure migration and
retry after HTTP 498 without losing saved data. A final Update Link callback adjustment
was compiled and packaged afterward. `git diff --check` also passed.

Verification on 2026-10-01 after the validator-free resume fix: Java 21 with
`GRABX_TEST_RESUME=true ./gradlew test installDist` passed: 228 tests discovered, 218 passed, 10 optional skips, zero failures/errors.
All 28 runner integration cases passed, including the nine new validator-free
recovery/safety cases. A one-byte probe of the affected MediaFire URL returned
206 with the saved total but no ETag or Last-Modified, confirming the trigger.
The real saved part/history were not modified; full live resumption remains a
manual check after restarting the updated app.

Run the native runner tests separately from the compact-window suite, because each
suite owns its JavaFX toolkit lifecycle:

```sh
JAVA_HOME=/path/to/jdk21 GRABX_TEST_RESUME=true ./gradlew test --tests '*SmartResumeIntegrationTest'
JAVA_HOME=/path/to/jdk21 ./gradlew test installDist
```

An actual Wi-Fi/hotspot switch, physical sleep, force-quitting the complete desktop
app and native Windows/Linux runs remain manual checks. Network faults and restarting
the saved task were simulated; the user's connection and real downloads were not
interrupted. No live hosting service or automatic refresh plugin was exercised.
Servers without Range or sufficient identity metadata cannot support a verified
trailing-range resume. Persisted single-stream tasks without saved validators can
instead use the full-response comparison described above. Other unsupported cases
keep their bytes and ask for a verifiable link instead of restarting silently.

## Changed files

- `core/service/DownloadRunner.java`: recovery lifecycle, bounded connections, safe payload validation, ownership and UI updates.
- `core/service/DirectRemoteProbe.java` (new): real range probing, redirect handling, validators and classified HTTP errors.
- `core/service/DirectDownloadState.java` (new): atomic sidecar storage and disk byte accounting.
- `core/service/DownloadLinkResolvers.java` (new): modular resolver registry with mandatory verification.
- `core/service/DownloadLinkVerifier.java`: shared probing and persisted segment-layout verification.
- `core/service/HistoryService.java`, `DownloadHistoryReconciler.java`: atomic history and incomplete-task restoration.
- `core/model/DownloadRow.java`: persisted download identity/capability fields.
- `core/service/DownloadStateCoordinator.java`: retain direct progress on Retry.
- `core/service/PartialRetentionService.java`, `DirectPartialFiles.java`: preserve interrupted tasks and clean sidecars on explicit removal.
- `core/service/SegmentResponse.java`, `SegmentTransfer.java`: classify invalid/rejected ranges without deleting data.
- `MainController.java`: persist verified replacement links and correct the cleanup-setting explanation.
- Tests: new `SmartResumeIntegrationTest.java` and `DirectDownloadStateTest.java`; updated `PartialRetentionServiceTest.java` and `SegmentRetryTest.java`.
- Documentation: this file and `HANDOFF.md`.

Production paths above are relative to `src/main/java/com/grabx/app/grabx/`;
tests are in `src/test/java/com/grabx/app/grabx/core/service/`.

## Compact verification presentation (2026-10-05)

Compact mode reuses the amber verification panel in a denser layout, including
percentage, explicitly labeled check speed/time remaining and saved size. It grows
to 260px height for this phase when necessary, retaining a taller user-selected
height. Both views prioritize running downloads over queued/preparing and paused
rows, then terminal history; date ordering applies within each group. This is
presentation only and does not alter resume validation or transfer progress.

## Optional quick checks (2026-10-24)

Settings → Network → Resume checks → Use quick resume checks is OFF by default.
Save applies it to the next connection attempt; an in-flight full check is not replaced.
The setting persists separately from speed and connection limits.

For contiguous saved parts without a strong ETag or Last-Modified validator, with
known unchanged size and working byte ranges, quick checks compare five evenly
spaced 64 KiB samples (beginning through end of the saved prefix). At most 320 KiB
is reread; smaller prefixes are compared entirely. Then the remainder is requested
at the saved offset. This is deliberately less comprehensive than the default
full-prefix comparison: changes between samples or between requests can be missed.
It does not authenticate the whole file or enable parallel downloading.

Mismatch, wrong range/total, unexpected encoding or truncated sample never append
unverified data. Ignored sample ranges fall back to the original full-prefix check.
Pause/cancel checkpoints and the shared direct bandwidth limit apply to samples.
Existing strong-validator checks, legacy migration and saved split layouts retain
their existing behavior. No real saved task is migrated by saving this preference.
