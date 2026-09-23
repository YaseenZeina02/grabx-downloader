# macOS runtime

`runtime-2026.08.19.zip` is the unmodified official yt-dlp directory distribution:

- Source: https://github.com/yt-dlp/yt-dlp/releases/download/2026.08.19/yt-dlp_macos.zip
- SHA-256: `07e54b0865303c864006925913bce2604f8ee8cc6f18699bac9c309f9328a6d8`
- Checksums: https://github.com/yt-dlp/yt-dlp/releases/download/2026.08.19/SHA2-256SUMS

The app verifies and extracts this bundle once under its tools directory, preserving the runtime
libraries next to the executable. This avoids extracting the bundled Python interpreter for each probe
and download. The previous single-file binary remains an installation fallback. EJS solver scripts and
third-party licenses are included in the official archive. JavaScript execution uses an installed Deno
2.3+ or Node 22+, discovered explicitly by GrabX even when launched without the shell's PATH.
