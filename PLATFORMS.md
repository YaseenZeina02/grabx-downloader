# GrabX platform setup

Build on the destination OS: JavaFX includes native libraries, so a macOS distribution
cannot be copied to Windows or Linux. Use the same CPU architecture for Java and JavaFX.
The distribution is portable application files, not a signed installer or a bundled JRE.

## Requirements

- Java 21+ (JDK 21 to build), with `JAVA_HOME` pointing to it.
- Node 22+ or Deno 2.3+ for full YouTube challenge solving. Restart the browser after
  installing a runtime. GrabX checks common locations as well as PATH.
- FFmpeg for video/audio merging and audio conversion. GrabX uses a valid installed
  copy first, then its existing release download. To avoid a first-run download,
  install FFmpeg from your OS package manager and verify `ffmpeg -version`.
- Windows: 64-bit Windows with x64 Java/JavaFX. On Windows ARM use an x64 JDK under
  emulation for this JavaFX 21 build; native ARM Windows packaging is not verified.
- Linux: desktop Linux with GTK 3, X11 or XWayland, `xdg-open`, and a glibc-based x64
  or ARM64 userspace. Alpine/musl, 32-bit Linux and headless desktops are not targets
  of the bundled JavaFX/tool configuration. The Linux bridge installer needs Python 3.9+.
- Chromium-family desktop browser (Chrome, Chromium, Brave, Edge). These installers
  target regular desktop packages; sandboxed Snap/Flatpak browsers can need a separate
  native-messaging integration and are not verified here. Firefox/Safari are not supported
  by this Chromium extension.

The [official yt-dlp runtime guide](https://github.com/yt-dlp/yt-dlp/wiki/EJS)
explains the JS runtime requirement. Bundled Windows/Linux tools are pinned to
2026.08.19, checked against the official release SHA-256 list, and installed into a
version-specific directory so old cached binaries do not mask updates.

## Windows / IntelliJ

1. Configure IntelliJ's Gradle JVM to JDK 21 and install Node 22+ and FFmpeg.
2. In PowerShell from the project root:

   ```powershell
   .\gradlew.bat test installDist distZip
   .\build\install\GrabX\bin\GrabX.bat
   ```

3. In `chrome://extensions`, `brave://extensions` or `edge://extensions`, enable
   Developer mode and load `browser-extension/chromium` unpacked. Copy its ID.
4. Close GrabX before updating the staged app, then register the native host:

   ```powershell
   .\browser-extension\native-host\install-windows.ps1 -ExtensionId YOUR_EXTENSION_ID -NativeHostPath .\build\install\GrabX\bin\grabx-native-host.bat -Browser chrome -JavaHome $env:JAVA_HOME
   ```

   Browser can be `chrome`, `brave` or `edge`. This registers only for the current
   user (HKCU), stages the Windows libraries under LocalAppData, and pins Java for
   browsers launched outside IntelliJ. Use the same JDK as IntelliJ. If PowerShell
   policy blocks a local script, review it and use your organization's approved way
   to run scripts; no persistent execution-policy change is made by this installer.
5. Restart the browser, reload the extension, and start GrabX. `Open GrabX` also
   launches the staged app when it is closed.

## Linux

On Ubuntu/Debian, provide GTK 3 and the normal desktop libraries, FFmpeg, xdg-utils,
Java 21 and Python 3 through your distribution. Install a supported Node/Deno version;
older distro Node packages may not meet the YouTube requirement.

```sh
chmod +x gradlew
./gradlew test installDist distZip
./build/install/GrabX/bin/GrabX
python3 browser-extension/native-host/install-linux.py YOUR_EXTENSION_ID build/install/GrabX --browser chrome --java-home "$JAVA_HOME"
```

Load the unpacked extension as on Windows. Browser choices: `chrome`, `chromium`,
`brave`, `edge`. The installer honors XDG_CONFIG_HOME and XDG_DATA_HOME, registers
under the selected browser's `NativeMessagingHosts`, and pins the Java executable.
It does not need sudo or edit shell startup files. `--config-root` / `--data-root`
accept absolute paths for non-default XDG layouts. Non-default browser user-data
profiles must have the native manifest in that profile's NativeMessagingHosts directory.

The staged app is at `$XDG_DATA_HOME/GrabX/browser-bridge/app` (default
`~/.local/share/GrabX/browser-bridge/app`). Re-run registration after rebuilding;
reload the extension after JavaScript changes. The downloaded zip includes the
extension and installers: use its own directory as the distribution argument.

## Verification and release gate

`.github/workflows/platforms.yml` builds on Windows, Linux and macOS, runs Java and
extension tests, verifies platform-specific libraries and framed native messaging,
and exercises Linux/Windows installer launchers. Its zip artifacts are separated by
OS/CPU. Adding the workflow is not evidence it has run: inspect its results after the
commit is pushed. Native JavaFX window tests opt in with `GRABX_TEST_FX=true` on a
machine with an interactive desktop; CI currently runs the headless-safe suite.

Local checks:

```sh
node --test browser-extension/tests/*.test.cjs
python3 -m unittest discover -s scripts/tests -v  # POSIX installer fixtures
python3 scripts/smoke-distribution.py build/install/GrabX
```

Before calling a platform release tested, use its actual browser/app to verify:

- Open GrabX from the extension, then send a YouTube video and an audio-only request.
- Real quality list and available size estimate appear before downloading.
- A direct file uses the browser's selected folder exactly once (include a path
  with spaces and Arabic characters); cancelling Save As queues nothing.
- Video+audio complete and play; MP3 conversion works; pause/resume retains bytes.
- Restart the app, restore a paused task, reveal files, and check Remove vs Delete.
- Toggle Compact View repeatedly from a smaller window and from a maximized window.

No live Windows/Linux browser or desktop test has been performed on this Mac.
Current execution evidence and the next step are in HANDOFF.md.

## Bundled tool provenance

`scripts/update-bundled-ytdlp.py` refreshes the five Windows/Linux binaries from the
[official pinned release](https://github.com/yt-dlp/yt-dlp/releases/tag/2026.08.19)
only after their checksums match. `src/main/resources/tools/yt-dlp/bundled.properties`
records version and hashes. See the upstream release's source archive and
[third-party licenses](https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/THIRD_PARTY_LICENSES.txt)
for the standalone binary dependencies. The macOS directory runtime has its own
README/checksum and remains unchanged by that updater.
