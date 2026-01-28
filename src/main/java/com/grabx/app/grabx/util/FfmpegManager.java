package com.grabx.app.grabx.util;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.prefs.Preferences;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class FfmpegManager {

    // ====== CHANGE IF NEEDED ======
    private static final String GITHUB_OWNER = "YaseenZeina02";
    private static final String GITHUB_REPO  = "grabx-downloader";
    // Uses GitHub "latest release" API:
    // https://api.github.com/repos/{owner}/{repo}/releases/latest

    private static final Preferences PREFS =
            Preferences.userRoot().node("com.grabx.app.grabx");

    private static final String PREF_FFMPEG_PATH = "ffmpeg.path";
    private static final String PREF_FFMPEG_VER  = "ffmpeg.version";
    private static final String PREF_FFMPEG_TS   = "ffmpeg.resolvedAt";

    private static final Object INIT_LOCK = new Object();
    private static volatile boolean initDone = false;
    private static volatile Path cached;

    private FfmpegManager() {}

    /* ========= Public API ========= */

    public static Path ensureAvailable() {
        log("ensureAvailable() called");

        try {
            if (cached != null && Files.exists(cached)) {
                log("✅ Using cached ffmpeg: " + cached);
                return cached;
            }
        } catch (Exception ignored) {}

        ensureInitializedOnce();

        try {
            if (cached != null && Files.exists(cached)) {
                log("✅ Resolved ffmpeg after init: " + cached);
                return cached;
            }
        } catch (Exception ignored) {}

        // PATH fallback
        try {
            String exe = isWindows() ? "ffmpeg.exe" : "ffmpeg";
            Path fromPath = findOnPath(exe);
            if (fromPath != null) {
                cached = fromPath;
                log("✅ Found ffmpeg on PATH: " + cached);
                return cached;
            }
        } catch (Exception ignored) {}

        log("❌ ffmpeg NOT found");
        return null;
    }

    public static void prewarmAsync() {
        new Thread(() -> {
            try { ensureAvailable(); } catch (Exception ignored) {}
        }, "grabx-ffmpeg-prewarm").start();
    }

    public static String getPersistedPath() {
        try { return PREFS.get(PREF_FFMPEG_PATH, null); } catch (Exception e) { return null; }
    }

    public static String getPersistedVersion() {
        try { return PREFS.get(PREF_FFMPEG_VER, null); } catch (Exception e) { return null; }
    }

    /* ========= Initialization ========= */

    private static void ensureInitializedOnce() {
        if (initDone) return;

        synchronized (INIT_LOCK) {
            if (initDone) return;

            log("Initializing ffmpeg...");

            // 1) Preferences
            try {
                String saved = PREFS.get(PREF_FFMPEG_PATH, null);
                if (saved != null && !saved.isBlank()) {
                    Path p = Paths.get(saved);
                    if (Files.exists(p)) {
                        cached = p;
                        log("✅ Loaded ffmpeg from preferences: " + p);
                        initDone = true;
                        return;
                    } else {
                        log("Prefs path exists? NO -> " + p);
                    }
                } else {
                    log("No ffmpeg path in preferences yet");
                }
            } catch (Exception e) {
                log("Prefs read failed: " + e.getMessage());
            }

            // 2) Download from GitHub Release (latest)
            try {
                Path toolsDir = getAppToolsDir();
                Files.createDirectories(toolsDir);

                Path ffmpegOut = toolsDir.resolve(outputBinaryFileName());
                if (Files.exists(ffmpegOut) && Files.size(ffmpegOut) > 0) {
                    log("✅ ffmpeg already present in tools dir: " + ffmpegOut);
                    if (!isWindows()) makeExecutable(ffmpegOut);

                    String ver = probeVersion(ffmpegOut);
                    cached = ffmpegOut;
                    persist(ffmpegOut, ver);
                    initDone = true;
                    return;
                }

                String assetName = chooseAssetName();
                if (assetName == null) {
                    log("❌ Unsupported OS/ARCH for ffmpeg download");
                    initDone = true;
                    return;
                }

                log("Will download ffmpeg asset: " + assetName);

                String downloadUrl = resolveLatestReleaseAssetUrl(assetName);
                if (downloadUrl == null) {
                    log("❌ Could not find asset in latest release: " + assetName);
                    initDone = true;
                    return;
                }

                log("Download URL resolved ✅");

                // download to temp
                Path tmpDir = Files.createTempDirectory("grabx-ffmpeg-");
                Path archive = tmpDir.resolve(assetName);

                downloadFile(downloadUrl, archive);

                if (!Files.exists(archive) || Files.size(archive) <= 0) {
                    log("❌ Downloaded archive is missing/empty: " + archive);
                    initDone = true;
                    return;
                }

                log("Downloaded: " + archive + " (" + Files.size(archive) + " bytes)");

                // extract -> find ffmpeg binary -> move to toolsDir
                Path extractedRoot = tmpDir.resolve("extract");
                Files.createDirectories(extractedRoot);

                if (assetName.endsWith(".zip")) {
                    unzip(archive, extractedRoot);
                } else if (assetName.endsWith(".tar.xz")) {
                    extractTarXzWithSystemTar(archive, extractedRoot);
                } else {
                    log("❌ Unsupported archive type: " + assetName);
                    initDone = true;
                    return;
                }

                String targetFileName = outputBinaryFileName();
                Path found = findFileRecursive(extractedRoot, targetFileName);

                // Some builds might have just "ffmpeg" without .exe on Windows (rare), so fallback:
                if (found == null && isWindows()) {
                    found = findFileRecursive(extractedRoot, "ffmpeg");
                }
                // Or on *nix might be inside bin/ffmpeg
                if (found == null && !isWindows()) {
                    found = findFileRecursive(extractedRoot, "ffmpeg");
                }

                if (found == null) {
                    log("❌ Could not locate ffmpeg binary inside extracted files: " + extractedRoot);
                    initDone = true;
                    return;
                }

                log("Found ffmpeg binary at: " + found);

                // replace/move to toolsDir
                Files.copy(found, ffmpegOut, StandardCopyOption.REPLACE_EXISTING);

                if (!isWindows()) {
                    makeExecutable(ffmpegOut);
                    removeMacQuarantineIfAny(ffmpegOut);
                }

                String ver = probeVersion(ffmpegOut);

                cached = ffmpegOut;
                persist(ffmpegOut, ver);

                log("✅ ffmpeg ready: " + ffmpegOut);
                if (ver != null) log("ffmpeg version: " + ver);

                // cleanup temp best-effort
                try { deleteRecursive(tmpDir); } catch (Exception ignored) {}

                initDone = true;
                return;

            } catch (Exception e) {
                log("❌ Download/extract failed: " + e.getMessage());
            }

            initDone = true;
        }
    }

    /* ========= Download from GitHub Releases ========= */

    private static String resolveLatestReleaseAssetUrl(String assetName) throws IOException {
//        String api = "https://api.github.com/repos/" + GITHUB_OWNER + "/" + GITHUB_REPO + "/releases/latest";
        final String FFMPEG_RELEASE_TAG = "ffmpeg-tools-v1";
        String api = "https://api.github.com/repos/" + GITHUB_OWNER + "/" + GITHUB_REPO + "/releases/tags/" + FFMPEG_RELEASE_TAG;
        log("Fetching latest release json: " + api);

        String json = httpGetText(api);

        if (json == null || json.isBlank()) {
            log("❌ Empty GitHub API response");
            return null;
        }

        // Very simple extraction (no external JSON libs):
        // Find the asset object containing: "name":"assetName" and then capture "browser_download_url":"..."
        // This is robust enough for GitHub release JSON.
        String needle = "\"name\":\"" + assetName + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) {
            // Sometimes json has escaped characters or different spacing, try a looser search:
            needle = "\"name\": \"" + assetName + "\"";
            idx = json.indexOf(needle);
        }
        if (idx < 0) {
            log("Asset name not found in JSON: " + assetName);
            return null;
        }

        // Search forward for browser_download_url
        int urlKey = json.indexOf("\"browser_download_url\"", idx);
        if (urlKey < 0) return null;

        int colon = json.indexOf(':', urlKey);
        if (colon < 0) return null;

        int firstQuote = json.indexOf('"', colon + 1);
        if (firstQuote < 0) return null;

        int secondQuote = json.indexOf('"', firstQuote + 1);
        while (secondQuote > 0 && json.charAt(secondQuote - 1) == '\\') {
            secondQuote = json.indexOf('"', secondQuote + 1);
        }
        if (secondQuote < 0) return null;

        String url = json.substring(firstQuote + 1, secondQuote);
        url = url.replace("\\/", "/");
        return url;
    }

    private static void downloadFile(String url, Path out) throws IOException {
        log("Downloading asset -> " + out.getFileName());
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "GrabX/1.0");

        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code + " while downloading");
        }

        try (InputStream in = conn.getInputStream()) {
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
        }
        log("Download complete ✅");
    }

    private static String httpGetText(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "GrabX/1.0");
        conn.setRequestProperty("Accept", "application/vnd.github+json");

        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code + " for " + url);
        }

        try (InputStream in = conn.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String chooseAssetName() {
        // Names you are uploading in the Release:
        // ffmpeg-win-x64.zip
        // ffmpeg-mac-x64.zip
        // ffmpeg-mac-arm64.zip
        // ffmpeg-linux-x64.tar.xz
        // ffmpeg-linux-arm64.tar.xz

        String arch = detectArch();
        if (isWindows()) {
            // If you ever add win-arm64 later, adjust here
            return "ffmpeg-win-x64.zip";
        }
        if (isMac()) {
            if ("arm64".equals(arch)) return "ffmpeg-mac-arm64.zip";
            return "ffmpeg-mac-x64.zip";
        }
        if (isLinux()) {
            if ("arm64".equals(arch)) return "ffmpeg-linux-arm64.tar.xz";
            return "ffmpeg-linux-x64.tar.xz";
        }
        return null;
    }

    /* ========= Extract helpers ========= */

    private static void unzip(Path zip, Path outDir) throws IOException {
        log("Unzipping: " + zip.getFileName());
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;

                Path out = outDir.resolve(e.getName()).normalize();
                if (!out.startsWith(outDir)) {
                    // zip-slip protection
                    throw new IOException("Bad zip entry: " + e.getName());
                }
                Files.createDirectories(out.getParent());
                Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        log("Unzip done ✅ -> " + outDir);
    }

    private static void extractTarXzWithSystemTar(Path archive, Path outDir) throws IOException, InterruptedException {
        // Requires: tar supports xz (most Linux distros do)
        log("Extracting tar.xz using system tar: " + archive.getFileName());

        List<String> cmd = new ArrayList<>();
        cmd.add("tar");
        cmd.add("-xf");
        cmd.add(archive.toAbsolutePath().toString());
        cmd.add("-C");
        cmd.add(outDir.toAbsolutePath().toString());

        Process p = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();

        String out = readAll(p.getInputStream());
        int code = p.waitFor();
        log("tar output: " + (out == null ? "" : out.trim()));

        if (code != 0) throw new IOException("tar failed (exit " + code + ")");
        log("tar extract done ✅ -> " + outDir);
    }

    private static String readAll(InputStream in) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static Path findFileRecursive(Path root, String fileName) {
        if (root == null || fileName == null) return null;
        try {
            try (var stream = Files.walk(root)) {
                return stream
                        .filter(p -> {
                            try {
                                return p.getFileName() != null
                                        && p.getFileName().toString().equalsIgnoreCase(fileName)
                                        && Files.isRegularFile(p);
                            } catch (Exception e) {
                                return false;
                            }
                        })
                        .findFirst()
                        .orElse(null);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static void deleteRecursive(Path p) throws IOException {
        if (p == null || !Files.exists(p)) return;
        try (var s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(x -> {
                try { Files.deleteIfExists(x); } catch (Exception ignored) {}
            });
        }
    }

    /* ========= OS helpers ========= */

    private static Path getAppToolsDir() {
        if (isWindows()) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                return Paths.get(appData, "GrabX", "tools");
            }
            return Paths.get(System.getProperty("user.home"), ".grabx", "tools");
        }
        if (isMac()) {
            return Paths.get(System.getProperty("user.home"),
                    "Library", "Application Support", "GrabX", "tools");
        }
        return Paths.get(System.getProperty("user.home"), ".grabx", "tools");
    }

    private static String outputBinaryFileName() {
        return isWindows() ? "ffmpeg.exe" : "ffmpeg";
    }

    private static void makeExecutable(Path p) {
        try {
            p.toFile().setExecutable(true, false);
            new ProcessBuilder("chmod", "+x", p.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start()
                    .waitFor();
            log("chmod +x applied ✅: " + p);
        } catch (Exception e) {
            log("chmod failed: " + e.getMessage());
        }
    }

    private static void removeMacQuarantineIfAny(Path p) {
        if (!isMac()) return;
        try {
            Process pr = new ProcessBuilder("xattr", "-dr", "com.apple.quarantine", p.toAbsolutePath().toString())
                    .redirectErrorStream(true).start();
            String out = readAll(pr.getInputStream());
            int code = pr.waitFor();
            log("xattr quarantine remove: exit=" + code + (out == null ? "" : (" | " + out.trim())));
        } catch (Exception e) {
            log("xattr not available / failed: " + e.getMessage());
        }
    }

    private static String probeVersion(Path ffmpeg) {
        try {
            Process p = new ProcessBuilder(ffmpeg.toAbsolutePath().toString(), "-version")
                    .redirectErrorStream(true)
                    .start();

            String firstLine;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                firstLine = r.readLine();
            }
            p.waitFor();
            return firstLine;
        } catch (Exception e) {
            log("Version probe failed: " + e.getMessage());
            return null;
        }
    }

    private static void persist(Path ffmpegPath, String versionLine) {
        try {
            PREFS.put(PREF_FFMPEG_PATH, ffmpegPath.toAbsolutePath().toString());
            if (versionLine != null) PREFS.put(PREF_FFMPEG_VER, versionLine);
            PREFS.putLong(PREF_FFMPEG_TS, System.currentTimeMillis());
            log("Persisted ffmpeg path/version ✅");
        } catch (Exception e) {
            log("Persist failed: " + e.getMessage());
        }
    }

    private static Path findOnPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) return null;

        for (String part : path.split(File.pathSeparator)) {
            if (part == null || part.isBlank()) continue;
            Path p = Paths.get(part, exe);
            if (Files.exists(p)) return p;
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean isMac() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac");
    }

    private static boolean isLinux() {
        String n = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        return n.contains("nux") || n.contains("linux");
    }

    private static String detectArch() {
        String a = System.getProperty("os.arch");
        if (a == null) return "x64";
        a = a.toLowerCase(Locale.ROOT);

        if (a.contains("aarch64") || a.contains("arm64")) return "arm64";
        // treat everything else as x64 for our release assets
        return "x64";
    }

    private static void log(String msg) {
        System.out.println("[FFMPEG] " + msg);
    }
}