# GrabX browser extension

This folder contains the first Chromium vertical slice of the GrabX browser bridge.

## Current capabilities

- Scans the active tab only after the user clicks the extension.
- Adds **Download with GrabX** to the context menu of HTTP(S) links.
- Can automatically intercept new browser downloads. The switch in the popup controls this behavior;
  it is enabled after installation. GrabX accepts the handoff before the browser download is cancelled,
  and a failed handoff resumes in the browser.
- Detects HTML5 video/audio sources, Open Graph media, downloadable anchors, and common direct file links.
- Falls back to sending the page URL so GrabX/yt-dlp can analyze sites that hide media behind `blob:` URLs.
- Sends versioned, validated JSON through Chrome Native Messaging.
- Stores only the latest 20 non-sensitive capture summaries. It does not collect cookies or browsing history.

## Development installation

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

## Security boundary

Every message is validated again in the extension service worker and in the Java native host. Only HTTP(S)
URLs are accepted. Browser cookies and authorization headers are deliberately excluded from this first slice.
