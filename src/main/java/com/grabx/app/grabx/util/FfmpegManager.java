package com.grabx.app.grabx.util;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.prefs.Preferences;

public final class FfmpegManager {

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
                log("Using cached ffmpeg: " + cached);
                return cached;
            }
        } catch (Exception ignored) {}

        ensureInitializedOnce();

        try {
            if (cached != null && Files.exists(cached)) {
                log("Resolved ffmpeg after init: " + cached);
                return cached;
            }
        } catch (Exception ignored) {}

        // PATH fallback
        try {
            String exe = isWindows() ? "ffmpeg.exe" : "ffmpeg";
            Path fromPath = findOnPath(exe);
            if (fromPath != null) {
                cached = fromPath;
                log("Found ffmpeg on PATH: " + cached);
                return cached;
            }
        } catch (Exception ignored) {}

        log("❌ ffmpeg NOT found");
        return null;
    }

    public static void prewarmAsync() {
        new Thread(() -> {
            try {
                ensureAvailable();
            } catch (Exception ignored) {}
        }, "grabx-ffmpeg-prewarm").start();
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
                        log("Loaded ffmpeg from preferences: " + p);
                        initDone = true;
                        return;
                    }
                }
            } catch (Exception ignored) {}

            // 2) Bundled binary
            try {
                String resource = resolveBundledResourcePath();
                if (resource != null) {
                    log("Found bundled ffmpeg resource: " + resource);

                    Path toolsDir = getAppToolsDir();
                    Files.createDirectories(toolsDir);

                    Path out = toolsDir.resolve(outputBinaryFileName());

                    if (!Files.exists(out) || Files.size(out) == 0) {
                        log("Extracting ffmpeg to: " + out);
                        try (InputStream in = FfmpegManager.class
                                .getClassLoader().getResourceAsStream(resource)) {
                            if (in == null)
                                throw new IOException("Resource stream is null");
                            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } else {
                        log("ffmpeg already extracted: " + out);
                    }

                    // chmod +x (mac / linux)
                    if (!isWindows()) {
                        makeExecutable(out);
                    }

                    // Smoke test
                    String version = probeVersion(out);

                    cached = out;
                    PREFS.put(PREF_FFMPEG_PATH, out.toAbsolutePath().toString());
                    if (version != null)
                        PREFS.put(PREF_FFMPEG_VER, version);
                    PREFS.putLong(PREF_FFMPEG_TS, System.currentTimeMillis());

                    log("✅ ffmpeg ready: " + out);
                    if (version != null) log("ffmpeg version: " + version);

                    initDone = true;
                    return;
                } else {
                    log("No bundled ffmpeg found in resources");
                }
            } catch (Exception e) {
                log("Error extracting ffmpeg: " + e.getMessage());
            }

            initDone = true;
        }
    }

    /* ========= Helpers ========= */
    private static void makeExecutable(Path p) {
        try {
            p.toFile().setExecutable(true, false);
            new ProcessBuilder("chmod", "+x", p.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start()
                    .waitFor();
            log("chmod +x applied: " + p);
        } catch (Exception e) {
            log("chmod failed: " + e.getMessage());
        }
    }

    private static String probeVersion(Path ffmpeg) {
        try {
            Process p = new ProcessBuilder(ffmpeg.toAbsolutePath().toString(), "-version")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line = r.readLine();
                p.waitFor();
                return line;
            }
        } catch (Exception e) {
            log("Version probe failed: " + e.getMessage());
            return null;
        }
    }

    private static Path getAppToolsDir() {
        if (isWindows()) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank())
                return Paths.get(appData, "GrabX", "tools");
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

    private static String resolveBundledResourcePath() {
        List<String> candidates = new ArrayList<>();

        if (isMac()) {
            candidates.add("tools/ffmpeg/mac/ffmpeg");
        } else if (isLinux()) {
            candidates.add("tools/ffmpeg/linux/x64/ffmpeg");
            candidates.add("tools/ffmpeg/linux/arm64/ffmpeg");
        } else if (isWindows()) {
            candidates.add("tools/ffmpeg/windows/x64/ffmpeg.exe");
            candidates.add("tools/ffmpeg/windows/x86/ffmpeg.exe");
            candidates.add("tools/ffmpeg/windows/arm64/ffmpeg.exe");
        }
        candidates.add("tools/ffmpeg/" + outputBinaryFileName());
        ClassLoader cl = FfmpegManager.class.getClassLoader();
        for (String p : candidates) {
            URL u = cl.getResource(p);
            if (u != null) return p;
        }
        return null;
    }

    private static Path findOnPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) return null;

        for (String part : path.split(File.pathSeparator)) {
            Path p = Paths.get(part, exe);
            if (Files.exists(p)) return p;
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static boolean isMac() {
        return System.getProperty("os.name").toLowerCase().contains("mac");
    }

    private static boolean isLinux() {
        return System.getProperty("os.name").toLowerCase().contains("nux");
    }

    private static void log(String msg) {
        System.out.println("[FFMPEG] " + msg);
    }
}