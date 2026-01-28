package com.grabx.app.grabx.util;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class YtDlpManager {

    private static final java.util.prefs.Preferences PREFS = java.util.prefs.Preferences.userRoot().node("com.grabx.app.grabx");

    // Persisted resolved binary path so we don't redo heavy extraction/probing every run.
    private static final String PREF_YTDLP_PATH = "ytdlp.path";
    private static final String PREF_YTDLP_VER  = "ytdlp.version";
    private static final String PREF_YTDLP_TS   = "ytdlp.resolvedAt";

    private static final Object INIT_LOCK = new Object();
    private static volatile boolean initDone = false;

    public static volatile Path cached;

    private YtDlpManager() {}

    public enum OS { WINDOWS, MAC, LINUX, OTHER }
    public enum ARCH { X64, X86, ARM64, OTHER }

    public static OS detectOS() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return OS.WINDOWS;
        if (os.contains("mac") || os.contains("darwin")) return OS.MAC;
        if (os.contains("nux") || os.contains("nix")) return OS.LINUX;
        return OS.OTHER;
    }

    public static ARCH detectArch() {
        String a = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (a.contains("aarch64") || a.contains("arm64")) return ARCH.ARM64;
        if (a.equals("x86") || a.contains("i386") || a.contains("i486") || a.contains("i586") || a.contains("i686")) return ARCH.X86;
        if (a.contains("amd64") || a.contains("x86_64")) return ARCH.X64;
        return ARCH.OTHER;
    }

    /** Returns yt-dlp path (bundled first, then PATH fallback). */
    public static Path ensureAvailable() {
        // Fast path: already resolved this run.
        try {
            if (cached != null && Files.exists(cached)) return cached;
        } catch (Exception ignored) {}

        // One-time init per run: try prefs first, then do bundled extract once.
        ensureInitializedOnce();

        try {
            if (cached != null && Files.exists(cached)) return cached;
        } catch (Exception ignored) {}

        // Last resort: PATH lookup (very fast) if init failed.
        try {
            Path fromPath = findOnPath(detectOS() == OS.WINDOWS ? "yt-dlp.exe" : "yt-dlp");
            if (fromPath != null) return cached = fromPath;
        } catch (Exception ignored) {}

        return null;
    }

    private static void ensureInitializedOnce() {
        if (initDone) return;
        synchronized (INIT_LOCK) {
            if (initDone) return;
            try {
                String saved = PREFS.get(PREF_YTDLP_PATH, null);
                if (saved != null && !saved.isBlank()) {
                    Path p = Paths.get(saved);
                    if (Files.exists(p)) {
                        cached = p;
                        initDone = true;
                        return;
                    }
                }
            } catch (Exception ignored) {}

            // 2) Try bundled extraction.
            try {
                String res = resolveBundledResourcePath();
                if (res != null) {
                    Path toolsDir = getAppToolsDir();
                    Files.createDirectories(toolsDir);

                    String outName = outputBinaryFileName();
                    Path out = toolsDir.resolve(outName);

                    if (!Files.exists(out) || Files.size(out) == 0) {
                        try (InputStream in = YtDlpManager.class.getClassLoader().getResourceAsStream(res)) {
                            if (in != null) {
                                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                            }
                        }
                    }

                    // mac/linux: executable
                    OS os = detectOS();
                    if (os == OS.MAC || os == OS.LINUX) {
                        try { out.toFile().setExecutable(true, false); } catch (Exception ignored) {}
                        try {
                            new ProcessBuilder("chmod", "+x", out.toAbsolutePath().toString())
                                    .redirectErrorStream(true)
                                    .start()
                                    .waitFor();
                        } catch (Exception ignored) {}
                    }

                    // Persist + smoke test (only once, here)
                    String ver = null;
                    try {
                        List<String> test = new ArrayList<>();
                        test.add(out.toAbsolutePath().toString());
                        test.add("--version");
                        Process t = new ProcessBuilder(test).redirectErrorStream(true).start();
                        try (BufferedReader r = new BufferedReader(new InputStreamReader(t.getInputStream(), StandardCharsets.UTF_8))) {
                            ver = r.readLine();
                        }
                        t.waitFor();
                    } catch (Exception ignored) {}

                    cached = out;
                    try {
                        PREFS.put(PREF_YTDLP_PATH, out.toAbsolutePath().toString());
                        if (ver != null) PREFS.put(PREF_YTDLP_VER, ver);
                        PREFS.putLong(PREF_YTDLP_TS, System.currentTimeMillis());
                    } catch (Exception ignored) {}

                    initDone = true;
                    return;
                }
            } catch (Exception ignored) {}

            // 3) PATH fallback (persist if found)
            try {
                Path fromPath = findOnPath(detectOS() == OS.WINDOWS ? "yt-dlp.exe" : "yt-dlp");
                if (fromPath != null) {
                    cached = fromPath;
                    try {
                        PREFS.put(PREF_YTDLP_PATH, fromPath.toAbsolutePath().toString());
                        PREFS.putLong(PREF_YTDLP_TS, System.currentTimeMillis());
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}

            initDone = true;
        }
    }

    /** Optional: call this at app startup to pre-warm yt-dlp without blocking UI. */
    public static void prewarmAsync() {
        new Thread(() -> {
            try { ensureAvailable(); } catch (Exception ignored) {}
        }, "grabx-prewarm-ytdlp").start();
    }

    /** Convenience: read the persisted path for Settings UI. */
    public static String getPersistedPath() {
        try { return PREFS.get(PREF_YTDLP_PATH, null); } catch (Exception e) { return null; }
    }

    /** Convenience: read the persisted version for Settings UI. */
    public static String getPersistedVersion() {
        try { return PREFS.get(PREF_YTDLP_VER, null); } catch (Exception e) { return null; }
    }

    /** Run yt-dlp and return stdout+stderr (merged) as UTF-8. */
    public static String run(List<String> args) throws IOException, InterruptedException {
        long t = tStart(
                "run",
                String.join(" ", args)
        );

        Path bin = ensureAvailable();
        if (bin == null || !Files.exists(bin)) throw new FileNotFoundException("yt-dlp not found");

        List<String> cmd = new ArrayList<>();
        cmd.add(bin.toAbsolutePath().toString());
        cmd.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.environment().putIfAbsent("PYTHONIOENCODING", "utf-8");

        Process p = pb.start();

        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
//        p.waitFor();
        int code = p.waitFor();
        tEnd("run(exit=" + code + ")", t);

        return sb.toString();
    }



    // ============================
    // Download support (blocking)
    // ============================
    public static final class DownloadRequest {
        public final String url;
        public final Path outputDir;
        public final boolean audioOnly;
        public final String quality; // video: "720p" or "Best quality..." | audio: "mp3","m4a",...

        public DownloadRequest(String url, Path outputDir, boolean audioOnly, String quality) {
            this.url = url;
            this.outputDir = outputDir;
            this.audioOnly = audioOnly;
            this.quality = quality;
        }
    }

    public interface ProgressListener {
        void onStatus(String status);
        void onProgress(double percent, String speed, String eta);
    }

    public static int downloadBlocking(DownloadRequest req, ProgressListener listener)
            throws IOException, InterruptedException {

        Path bin = ensureAvailable();
        if (bin == null || !Files.exists(bin)) throw new FileNotFoundException("yt-dlp not found");

        Files.createDirectories(req.outputDir);

        List<String> cmd = new ArrayList<>();
        cmd.add(bin.toAbsolutePath().toString());

        cmd.add("--newline");
        cmd.add("--no-warnings");
        cmd.add("--progress");
        cmd.add("--encoding"); cmd.add("utf-8");
        // Avoid overwriting an existing file with the same name
        cmd.add("--no-overwrites");
        // Auto-number duplicates: (1), (2), ...
        cmd.add("--autonumber-start");
        cmd.add("1");

        String outTpl;
        if (req.audioOnly) {
            outTpl = "%(title)s [audio] (%(autonumber)d).%(ext)s";
        } else {
            outTpl = "%(title)s [%(height)sp] (%(autonumber)d).%(ext)s";
        }
        cmd.add("-o");
        cmd.add(req.outputDir.resolve(outTpl).toString());


        if (req.audioOnly) {
            cmd.add("-x");
            cmd.add("--audio-format");
            cmd.add(normalizeAudioFormat(req.quality));
            cmd.add("-f"); cmd.add("bestaudio/best");
        } else {
            String fmt = buildVideoFormatSelector(req.quality);
            cmd.add("-f"); cmd.add(fmt);
        }

        cmd.add(req.url.trim());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.environment().putIfAbsent("PYTHONIOENCODING", "utf-8");

        if (listener != null) listener.onStatus("Starting...");

        Process p = pb.start();

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String s = line.trim();
                if (s.isEmpty()) continue;

                // ---- internal phase signals for the UI (so progress doesn't get stuck at 100%) ----
                // yt-dlp often downloads audio first, then video, then merges => percent resets.
                if (listener != null) {
                    // New output file/stream (audio/video). Reset monotonic progress on the UI side.
                    if (s.startsWith("[download] Destination:") || s.startsWith("[download] Destination")) {
                        listener.onStatus("GX_EVT_NEWFILE");
                    }

                    // Merge / post-processing phases (progress is not meaningful here)
                    if (s.contains("Merging formats into") || s.startsWith("[Merger]") ||
                        s.contains("Post-process") || s.contains("Postprocessing") ||
                        s.contains("Fixing") || s.contains("Extracting") ||
                        s.contains("Deleting original file") || s.contains("Deleting original files")) {
                        listener.onStatus("GX_EVT_POST");
                    }
                }

                if (s.startsWith("[download]")) {
                    ParsedProgress pp = parseProgressLine(s);
                    if (pp != null && listener != null) listener.onProgress(pp.percent, pp.speed, pp.eta);
                }

                if (listener != null) listener.onStatus(s);
            }
        }

        int code = p.waitFor();
        if (listener != null) listener.onStatus(code == 0 ? "Completed" : ("Failed (exit " + code + ")"));
        return code;
    }

    private static String normalizeAudioFormat(String q) {
        if (q == null) return "m4a";
        String s = q.trim().toLowerCase(Locale.ROOT);
        if (s.isBlank() || s.contains("best")) return "m4a";
        return switch (s) {
            case "m4a", "mp3", "opus", "aac", "wav", "flac" -> s;
            default -> "m4a";
        };
    }

    private static String buildVideoFormatSelector(String quality) {
        if (quality == null) return "bv*+ba/b";
        String q = quality.trim();
        if (q.isBlank() || q.toLowerCase(Locale.ROOT).contains("best")) return "bv*+ba/b";

        Integer h = extractHeight(q);
        if (h == null || h <= 0) return "bv*+ba/b";

        // best video up to height + best audio
        return "bv*[height<=" + h + "]+ba/b[height<=" + h + "]/bv*+ba/b";
    }

    private static Integer extractHeight(String q) {
        String s = q.trim().toLowerCase(Locale.ROOT);
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) d.append(c);
            else if (d.length() > 0) break;
        }
        if (d.length() == 0) return null;
        try { return Integer.parseInt(d.toString()); } catch (Exception e) { return null; }
    }

    private static final class ParsedProgress {
        final double percent; // 0..1
        final String speed;
        final String eta;
        ParsedProgress(double percent, String speed, String eta) {
            this.percent = percent;
            this.speed = speed;
            this.eta = eta;
        }
    }

    private static ParsedProgress parseProgressLine(String line) {
        try {
            int pctIdx = line.indexOf('%');
            if (pctIdx < 0) return null;

            int i = pctIdx - 1;
            while (i >= 0 && (Character.isDigit(line.charAt(i)) || line.charAt(i) == '.' || line.charAt(i) == ' ')) i--;
            String pctStr = line.substring(i + 1, pctIdx).trim();
            double pct = Double.parseDouble(pctStr) / 100.0;

            String speed = "";
            String eta = "";
            int at = line.indexOf(" at ");
            if (at >= 0) {
                int etaIdx = line.indexOf(" ETA ", at);
                if (etaIdx >= 0) {
                    speed = line.substring(at + 4, etaIdx).trim();
                    eta = line.substring(etaIdx + 5).trim();
                } else speed = line.substring(at + 4).trim();
            }

            if (pct < 0) pct = 0;
            if (pct > 1) pct = 1;
            return new ParsedProgress(pct, speed, eta);
        } catch (Exception ignored) {
            return null;
        }
    }

    // -------- internals --------

    private static Path getAppToolsDir() {
        OS os = detectOS();
        if (os == OS.WINDOWS) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) return Paths.get(appData, "GrabX", "tools");
            return Paths.get(System.getProperty("user.home"), ".grabx", "tools");
        }
        if (os == OS.MAC) {
            return Paths.get(System.getProperty("user.home"), "Library", "Application Support", "GrabX", "tools");
        }
        return Paths.get(System.getProperty("user.home"), ".grabx", "tools");
    }

    private static String outputBinaryFileName() {
        OS os = detectOS();
        if (os == OS.WINDOWS) return "yt-dlp.exe";
        return "yt-dlp"; // macOS / Linux
    }

    private static String resolveBundledResourcePath() {
        OS os = detectOS();
        ARCH arch = detectArch();

        List<String> c = new ArrayList<>();

        // Your current tree: src/main/resources/tools/yt-dlp/...
        if (os == OS.MAC) {
            // Try multiple layouts (some repos keep a universal binary, others keep per-arch)
            c.add("tools/yt-dlp/mac/yt-dlp");
        } else if (os == OS.LINUX) {
            // linux/{arm64|x64}/yt-dlp
            c.add("tools/yt-dlp/linux/" + (arch == ARCH.ARM64 ? "arm64" : "x64") + "/yt-dlp");
        } else if (os == OS.WINDOWS) {
            // windows/{arm64|x86|x64}/yt-dlp.exe
            String a = (arch == ARCH.ARM64) ? "arm64" : (arch == ARCH.X86 ? "x86" : "x64");
            c.add("tools/yt-dlp/windows/" + a + "/yt-dlp.exe");
        }

        // Optional flat layout later:
        c.add("tools/yt-dlp/" + outputBinaryFileName());

        ClassLoader cl = YtDlpManager.class.getClassLoader();
        for (String p : c) {
            URL u = cl.getResource(p);
            if (u != null) return p;
        }
        return null;
    }

    private static Path findOnPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) return null;
        for (String part : path.split(File.pathSeparator)) {
            if (part == null || part.isBlank()) continue;
            Path cand = Paths.get(part, exe);
            if (Files.exists(cand)) return cand;
        }
        return null;
    }


    // ============================
    // yt-dlp timing helpers
    // ============================
    private static boolean YTDLP_TIMING = true; // عطّلها لاحقًا لو بدك

    public static long tStart(String tag, String detail) {
        if (!YTDLP_TIMING) return 0L;
        System.out.println(
                "[yt-dlp][START] " + tag +
                        (detail != null ? " | " + detail : "")
        );
        return System.nanoTime();
    }

    public static void tEnd(String tag, long startNs) {
        if (!YTDLP_TIMING || startNs == 0L) return;
        long ms = (System.nanoTime() - startNs) / 1_000_000;
        System.out.println("[yt-dlp][END]   " + tag + " | took " + ms + " ms");
    }
}