# GrabX browser extension

## Automatic downloads before filename completion (0.3.2)

When **Send downloads to GrabX** is enabled, the download filename-determination
event holds browser completion while the running app accepts or rejects the file.
This covers script-started downloads, redirect chains and new-tab download pages
without clicking a GX button. GrabX receives the final HTTP(S) URL and referring
page; no second preflight consumes the link. The tentative browser name is a
filename hint, not proof of a selected folder, so GrabX uses its folder chooser.

The browser transfer is paused and cancelled only after acceptance. A closed app,
failed bridge or app closing during handoff leaves the download in the browser
and releases filename determination. Automatic downloads do not create closed-app
confirmation requests; explicit page/popup/context-menu actions retain that workflow.
Per-download event serialization and a session marker prevent duplicate delivery.
The existing filename-ready / Save As route remains as a fallback when the early
event is unavailable. Non-HTTP(S) blob/data downloads remain unsupported.

Reload the unpacked extension (0.3.2). No new permissions or app rebuild required.
Earlier notes below about always waiting for browser Save As describe the fallback
path; the early path deliberately delegates folder selection to GrabX.

## Consistent media link actions (0.3.1)

GX buttons beside MP4/WebM/MOV/MKV links now use the same Video action as the
popup, and MP3/M4A/WAV/FLAC links use Audio. Documents, archives and opaque links
retain file inspection. Link buttons keep their compact appearance. Successful
handoffs clear stale saved bridge errors. Reload the extension and refresh the page.

## Page assistant and automatic handoff (0.3.0)

New page buttons use GrabX's navy panels, blue GX mark and green detection dot:
**Download with GrabX** over video/audio and a compact **GX Download** beside file
links. The toolbar badge counts up to 99 detected items per tab. Detection observes
changing pages, lazy-loaded media and YouTube navigation. Clicks show delivery,
waiting-confirmation or failure messages. Automatic link interception respects
**Send downloads to GrabX**; **Show download buttons on pages** independently hides
the in-page shortcuts. Modifier clicks and new-tab links keep browser behavior.

Confirmed browser downloads no longer require a redundant native preflight, which
could reject short-lived links after the browser already received the file response.
The browser is paused, handed off and cancelled only after GrabX accepts it; native
failure resumes only a transfer GrabX paused. Save As cancellation remains respected.
Direct recognizable links are intercepted earlier on ordinary clicks; failed early
handoffs replay the original click so the browser can continue. Unknown links are
left to the browser's download events. Small downloads can finish before those
events are interceptable. Page buttons never automatically start downloading.

Content scripts now run on HTTP(S) pages, rather than requiring an extension click
for each page. Reload the extension and refresh existing pages; the browser may ask
to approve updated website access. No cookies, authorization headers, page forms,
or browsing history are collected. Detection sends a count to the worker; URLs are
sent to the existing native handoff only after a download action. Protected browser
pages, cross-origin frames, DRM and some site-managed blob downloads are unsupported;
blob media buttons fall back to page analysis rather than exporting protected streams.


This folder contains the first Chromium vertical slice of the GrabX browser bridge.

## Bring GrabX forward (0.2.6)

After Video/Audio or another popup action is delivered to the running app, the
extension popup closes and GrabX brings its window forward. On macOS, the app
also requests native application activation through the JDK Desktop API. Minimized
windows are restored; an existing Add Link dialog is reused and focused. Other
desktop environments use JavaFX window focus where native activation is unsupported.
Failed requests and closed-app queue confirmations keep the popup available.
Restart the app and reload the extension to activate both sides of this change.

## Current capabilities

- Automatically detects HTML5 media and recognizable file links in the top-level HTTP(S) page.
  A per-tab number on the extension icon shows the detected count; the popup still provides
  its deeper on-demand scan. Page shortcuts can be hidden independently in the popup.
- Adds **Download with GrabX** to the context menu of HTTP(S) links.
- Can automatically intercept new browser downloads. The switch in the popup controls this behavior;
  it is enabled after installation. GrabX accepts the handoff before the browser download is cancelled,
  and a failed handoff resumes in the browser.
- Intercepts before filename completion where supported; folder selection happens in GrabX.
  The filename-ready fallback waits for browser **Save As** and uses its selected destination.
  Cancelling that fallback picker leaves no GrabX request.
- Chrome exposes the final path, not whether a Save As dialog was shown. Its default download folder
  is therefore also treated as a resolved destination. Enable **Ask where to save each file before
  downloading** in browser settings if every intercepted download should ask for a destination.
- Detects HTML5 video/audio sources, Open Graph media, downloadable anchors, and common direct file links.
- Falls back to sending the page URL so GrabX/yt-dlp can analyze sites that hide media behind `blob:` URLs.
- YouTube Video/Audio actions read the active player's existing quality levels and send a small
  validated numeric list to GrabX (0.2.2). The app can show these immediately, with extractor
  fallback if the player APIs are unavailable or the video changed. Signed media URLs are still
  resolved by the app at download time. Version 0.2.3 also sends per-quality video+audio
  transfer estimates from existing stream lengths or bitrate/duration metadata. Add Link
  displays them before downloading and updates on quality changes. Unknown sizes stay
  unavailable; these are estimates, not a promise of the final merged/converted file size.
- Sends versioned, validated JSON through Chrome Native Messaging.
- Stores only the latest 20 non-sensitive capture summaries. It does not collect cookies or browsing history.

## File links and server errors (0.2.4)

Opaque context-menu links (without a recognizable extension) are inspected through the native
bridge using HTTP response metadata. File responses use Direct mode; HTML pages and server
rejections produce a message in the extension instead of the unsupported video dialog. YouTube
analysis and the existing GitHub link conversions remain available. Version 0.2.5 uses a custom
GX monogram with the app's navy panels and pale blue accent for the toolbar, popup and browser
context menu. The letterforms are vector paths, so rendering does not depend on installed fonts.

When GrabX is running, file handoffs first check the server response. A rejected inspection
returns an intercepted download to the browser instead of cancelling it. This is a preflight,
not confirmation that the eventual transfer has started or will finish: tokens can expire or
servers can reject a later request. Closed-app requests retain the explicit queue confirmation.
The actual browser User-Agent and referring page travel with direct downloads and resume state;
cookies and authorization headers are not captured. Both the app and native bridge must be updated.

For example, HTTP 498 from a download host indicates a server rejection, not an unsupported URL
syntax. Its exact meaning depends on the host. If the browser also fails with **Send downloads
to GrabX** off, obtain a working link/session on the site first. GrabX does not solve site challenges.
Reload the unpacked extension after updating and restart the running development app.

Icon sources are in `chromium/icons/grabx.svg`; regenerate the PNG sizes with Java 21:

```sh
java -Djava.awt.headless=true browser-extension/tools/GenerateIcons.java browser-extension/chromium/icons
```

## Cross-platform builds

See [PLATFORMS.md](../PLATFORMS.md) for Windows/Linux prerequisites, platform-specific
builds, installation, and the live test checklist. Each distribution includes this
extension and the native-host installers; build it on the destination OS.

## Linux registration

```sh
python3 browser-extension/native-host/install-linux.py EXTENSION_ID build/install/GrabX --browser chrome --java-home "$JAVA_HOME"
```

Supports regular Chrome, Chromium, Brave and Edge packages; honors XDG config/data
locations and pins Java for the browser. Reload the extension after registration.

## macOS development installation

1. Open `chrome://extensions`, enable **Developer mode**, and choose **Load unpacked**.
2. Select `browser-extension/chromium` and copy the extension ID Chrome assigns.
3. Build the GrabX distribution so its `grabx-native-host` launcher has an absolute executable path.
4. Run `native-host/install-macos.sh EXTENSION_ID /absolute/path/to/grabx-native-host chrome`.
   For Brave, use `brave` as the last argument; the installer covers both Brave's native path and its
   Chrome-compatibility path. When given a Gradle application distribution, the installer stages the host
   and its libraries under `~/Library/Application Support/GrabX/native-host`; this avoids macOS privacy
   restrictions that prevent GUI browsers from executing development builds stored under Documents.
5. Restart Chrome after changing a native-host manifest.

The native host manifest intentionally requires an exact extension ID; wildcards are not permitted by Chrome.

## Windows development installation (Chrome, Brave, Edge)

Running GrabX from IntelliJ starts the app, but does not register or update the browser's native host.
The bridge is a separate process, and Chrome does not inherit IntelliJ's JDK selection.
Build the distribution **on Windows** so it contains Windows JavaFX libraries, then run this in
PowerShell from the project directory (the directory containing `gradlew.bat`):

```powershell
.\gradlew.bat installDist
.\browser-extension\native-host\install-windows.ps1 `
  -ExtensionId 'YOUR_32_CHARACTER_EXTENSION_ID' `
  -NativeHostPath '.\build\install\GrabX\bin\grabx-native-host.bat' `
  -Browser chrome `
  -JavaHome 'C:\Path\To\Your\jdk-21'
```

Use the ID shown on `chrome://extensions` (or the corresponding Brave/Edge extensions page).
The script stages the distribution under `%LOCALAPPDATA%\GrabX\browser-bridge`, pins the Java
executable, and registers the manifest under `HKEY_CURRENT_USER`; administrator access is not needed.
`-JavaHome` may be omitted if `JAVA_HOME` or `java.exe` on PATH already selects Java 21 or newer.
The browser's **Open GrabX** button can launch the staged app even without a packaged `GrabX.exe`.
An app already running from IntelliJ under the same Windows account receives captures through the same inbox.

Restart the browser and reload the unpacked extension after installing. **After Java bridge changes,
repeat both build and installation steps**: running changed sources in IntelliJ alone does not update
the native host's staged JARs. Reload the extension after JavaScript changes too.

## Diagnosing media URL errors

`Invalid media URL` is a validation response from the Java native host; it means the host was reached.
It is different from `Specified native messaging host not found` (missing/broken registration,
manifest or host path), `Access to the specified native messaging host is forbidden`
(extension ID missing from `allowed_origins`), or
`Native host has exited` (launcher / runtime). Version 0.2.1 quotes browser-accepted URL characters such
as brackets in movie filenames and pipes, while retaining existing percent escapes and signed query
parameters. Both the native host and running app must be updated. An expired link or a server that
requires browser cookies can still fail later during transfer; those are separate from URL validation.

For **host not found on Windows**, complete the Windows registration steps above using the
same Windows account as the browser. Select the browser you actually use with `-Browser`.
Loading the extension or starting GrabX from IntelliJ does not perform this registration.
If registration reports success but the error remains, inspect the default registry value:

```powershell
# Chrome; for Edge use Software\Microsoft\Edge instead of Software\Google\Chrome.
reg query "HKCU\Software\Google\Chrome\NativeMessagingHosts\com.grabx.browser_bridge" /ve
Get-Content -LiteralPath "$env:LOCALAPPDATA\GrabX\browser-bridge\com.grabx.browser_bridge.json"
Test-Path -LiteralPath "$env:LOCALAPPDATA\GrabX\browser-bridge\grabx-native-host.cmd"
```

The registry must point to the existing JSON manifest, its `path` must point to the existing
wrapper, and `allowed_origins` must include the ID from that browser's extensions page.
An unpacked extension can have a different ID on another computer. Keep the installer's
full error output if it fails; an unsuccessful installation is not repaired by Reload.

API references: [Chrome downloads](https://developer.chrome.com/docs/extensions/reference/api/downloads)
and [native messaging registration](https://developer.chrome.com/docs/extensions/develop/concepts/native-messaging).

## Verification

Portrait resolutions use the displayed quality/short dimension: 480x640 is 480p, not 640p.
Video downloads prefer H.264/AAC at the selected resolution. On macOS, incompatible output
is prepared for native playback without reducing resolution; this can add a conversion phase.
Existing files are not modified automatically. Set `GRABX_TEST_FFMPEG` alongside
`GRABX_TEST_YTDLP` to enable the native-media integration checks.


YouTube preparation shares a short-lived extractor result between the quality dialog, output-name
selection, and the actual download using yt-dlp's `--load-info-json`. Results expire after two minutes
(or earlier when a signed stream URL is about to expire); live streams are not cached. A saved resume
destination skips the name probe entirely. GrabX retries a failed cached download once through the original
page URL, preserving the output name and resume options; pause/cancel never triggers this retry.
The first extraction and later audio conversion/video merging still take time.

For videos downloaded as separate video/audio streams, the progress card now totals the selected
streams instead of resetting at each stream. It labels the video, audio, and merging phases and marks
the combined transfer size with `≈`; the final file size comes from disk after merging. Missing sizes
use a bitrate/duration estimate when available; an unknown combined total is not shown as a precise
percentage. This uses metadata from the download process and adds no separate analysis request.

Analysis and download now use the same explicit JavaScript runtime: Deno 2.3+ or Node 22+.
GrabX checks common installation locations as well as PATH, including when launched from Finder or
IntelliJ. Install one of these runtimes for full YouTube quality support; without one, GrabX falls
back to the basic Android client, whose available formats may be limited. See the official
[yt-dlp JavaScript runtime requirements](https://github.com/yt-dlp/yt-dlp/wiki/EJS).
On macOS, the bundled yt-dlp 2026.08.19 directory distribution is verified and installed once,
avoiding the old single-file executable's Python extraction cost on every invocation.

The optional `MediaInfoCacheIntegrationTest` uses a real yt-dlp with local fixtures to verify format
selection, download without page extraction, and recovery from an expired stream. Set `GRABX_TEST_YTDLP`
to an installed executable when running the tests to enable it.

```text
node --test browser-extension/tests/*.test.cjs
./gradlew test installDist
```

Manual checks on each target browser/OS:

- With **Ask where to save** enabled, choose a non-default folder. GrabX must use that folder without
  displaying another chooser. Cancel Save As and verify that no request appears in GrabX.
- With it disabled, the browser's configured download folder is used. A popup/context-menu file request
  should instead open one GrabX folder chooser; cancelling it must not enqueue a transfer.
- Disconnect the native host and verify that an intercepted transfer continues in the browser.
- With GrabX closed, confirm a waiting request, open GrabX, and verify that its saved browser destination survives.
- Test a direct film download and a YouTube video. For a media validation failure, retain a fresh example
  URL privately so its exact syntax can be reproduced; screenshots alone cannot establish the failing URL.

## Security boundary

Every message is validated again in the extension service worker and in the Java native host. Only HTTP(S)
URLs are accepted. Browser cookies and authorization headers are deliberately excluded from this first slice.
