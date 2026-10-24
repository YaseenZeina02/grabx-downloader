# GrabX local installers

GrabX uses a native package for each OS/CPU and a shared first-run assistant. The
assistant is part of the installed app, not a web download page. It appears before
the main controller starts, checks local tool versions without installing software,
saves language/download folder/remember policy/queue limit/sounds on Finish, and
provides optional Chromium extension registration for the current user only.
English and Arabic setup instructions are available; all six existing app languages
remain selectable. Other setup languages currently use English guidance.

First setup proposes `~/Downloads/GrabX` independently of legacy test preferences.
Reopening a completed setup retains the user's saved folder. The Defaults step
checks typed paths and actual write access before Continue; errors appear at the
folder field. Only the suggested GrabX directory may be missing: a visible hint
explains that it will be created on Finish. Other missing paths, relative paths
and files are rejected. Finish checks again in case the folder changed meanwhile.
Language/browser selectors share a styled popup; simultaneous downloads use a
bounded minus/plus stepper (1–16) with accessible button names.

## Build

Use JDK 21 on the destination OS with matching JavaFX architecture. The packaging
script creates a Java runtime; end users do not need to install Java or Python.
No existing application, browser registration or user preferences are changed by
building a package.

```sh
python3 scripts/package-app.py --type app-image --tools /absolute/path/to/standalone-tools
python3 scripts/package-app.py --type dmg --tools /absolute/path/to/standalone-tools
```

Windows: `--type exe` or `msi` (JDK 21 jpackage needs WiX 3); Linux: `deb` (fakeroot)
or `rpm` (rpmbuild). Native installers must be built on their target OS. An image
can be produced without those installer tools using `--type app-image`. Build
outputs live under `build/installers/<OS>-<CPU>/<type>`. Installer version defaults
to 1.0.0; override with `--version`. Java and the bundled JavaFX/application jars
are included. Packaging does not sign or notarize builds.
Use `--dest /absolute/versioned/output` to retain previous application images.

`--tools` must contain executable standalone `ffmpeg[.exe]` and either
`node[.exe]` 22+ or `deno[.exe]` 2.3+, matching the OS/CPU, their licenses and
`THIRD_PARTY_NOTICES.md`. Do not copy Homebrew executables with external dylib
dependencies. The build validates both executables and records SHA-256 hashes.
For a deliberately incomplete development package, use `--allow-missing-tools`;
setup reports missing media capabilities. The bundled yt-dlp resource is already
inside the application jar and is prepared on first use by its existing manager.
A fully bundled package needs no network access for setup. Media downloads do.
Linux still needs system desktop/GTK libraries; Snap/Flatpak browser integration
is outside the tested scope.

## Install and browser connection

1. On macOS open the DMG, copy GrabX to Applications and open **the installed copy**.
   Keep the app in place after browser linking. For a local unsigned build macOS
   can request approval under Privacy & Security. Do not disable Gatekeeper.
2. Complete the five setup steps. Tools are actually probed, and missing tools have
   official installation links. Continuing with missing tools is clearly disclosed.
3. In Browser extension select Chrome, Brave, Edge or Chromium. Open that browser's
   Extensions page shown in the assistant, enable Developer mode, Load unpacked,
   and select the folder shown by **Prepare & show included extension**. The app
   carries the complete extension archive inside its own jar; the button exports
   it to a stable per-user GrabX data directory (macOS: Application Support,
   Windows: LocalAppData, Linux: XDG_DATA_HOME). No checkout, loose project files,
   prior installation or separate download is needed. Copy folder path is available
   after preparation. Existing exports are repaired/updated at the same path to
   retain the unpacked extension ID; reload in the browser after updating.
   This is a local build;
   no store listing or automatic browser installation is claimed.
4. Copy the exact extension ID into the assistant and click Connect browser. The
   manifest permits only that origin. Windows uses HKCU; macOS/Linux use per-user
   manifests. The packaged native host uses the bundled runtime, and can start the
   sibling GrabX launcher without Java/Python from PATH.
5. Reload the extension, keep GrabX open, and use the extension's connection status
   to verify it. Registering a host does not prove the browser is connected. Repeat
   for each browser: a successful registration remains if you switch to another.
   Firefox/Safari are not supported. Native browser host registration is immediate
   when Connect is clicked; cancelling setup does not undo it.

To reopen setup, close GrabX and use the **GrabX Setup** launcher alongside the
main launcher (macOS: `GrabX.app/Contents/MacOS/GrabX Setup`), or launch GrabX with
`--setup`. Existing settings are loaded as drafts; download history is preserved.
Changing the installed app's location requires reconnecting its browser host.
The exported extension remains in its stable user-data folder. Connecting the
native host from a temporary macOS /Volumes disk image is rejected: install and
reopen the application first.

## Icons

Main app and setup have distinct GX icons. Setup adds a download arrow/tray;
the main app retains the plain GX mark. Window/Dock branding is applied explicitly
and restored on entry to the main app, including launches from Java. PNG assets
are committed under application resources; the vector renderer is
`packaging/icons/render-icons.swift` (native AppKit, based on the existing GX
letterforms). The packager creates main/secondary launcher ICNS files. When making
a DMG from an app image, pass `--icon .../GrabX-Setup.icns` to jpackage as well:
otherwise jpackage can use its Java default icon for the mounted installer volume.

## Release limits

This is a local unsigned packaging workflow. Public distribution additionally needs
platform signing/notarization, verified platform-specific tool sources and license
compliance (FFmpeg's matching source/build details included where required), store
publication for a production extension, and live Windows/Linux/macOS installation,
browser launch, video/MP3 and upgrade/uninstall checks. Never label generated
Windows/Linux packages tested from a build performed only on a Mac.

## Complete Windows x64 build

`.github/workflows/windows-installer.yml` runs on Windows 2022 x64. It builds and
checks the application, prepares the pinned tools from `packaging/windows/tools.json`
using `scripts/prepare-windows-tools.py`, then emits a self-contained image plus
branded EXE and MSI installers. `ffprobe.exe` is included alongside FFmpeg. Java,
Node, yt-dlp and the extension are bundled; the recipient needs no developer tools.

The workflow installs the MSI on its disposable runner, runs
`smoke-packaged-app.py` using the installed/relocated bundled runtime and an empty
user-data directory with system Java/Node/FFmpeg removed from PATH, checks media
binaries and embedded extension extraction, exercises per-user native-host
registration and a real Setup launch, then uninstalls the MSI. Build results must
be inspected before claiming Windows verification. EXE/MSI are unsigned test
installers; runtime/browser steps still need the recipient's live confirmation.

