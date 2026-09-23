# GrabX browser extension

This folder contains the first Chromium vertical slice of the GrabX browser bridge.

## Current capabilities

- Scans the active tab only after the user clicks the extension.
- Adds **Download with GrabX** to the context menu of HTTP(S) links.
- Can automatically intercept new browser downloads. The switch in the popup controls this behavior;
  it is enabled after installation. GrabX accepts the handoff before the browser download is cancelled,
  and a failed handoff resumes in the browser.
- Waits for the browser's final download filename (including time spent in **Save As**) and uses
  that destination in GrabX. Cancelling Save As leaves no GrabX request. Popup/context-menu requests
  without a resolved destination open a single folder chooser in GrabX.
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
It is different from `Specified native messaging host not found` (registration / extension ID) or
`Native host has exited` (launcher / runtime). Version 0.2.1 quotes browser-accepted URL characters such
as brackets in movie filenames and pipes, while retaining existing percent escapes and signed query
parameters. Both the native host and running app must be updated. An expired link or a server that
requires browser cookies can still fail later during transfer; those are separate from URL validation.

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
