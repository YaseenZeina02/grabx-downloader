package com.grabx.app.grabx.util;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class YtDlpManager {

    private static volatile Path cached;

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
        System.out.println("YTDLP ensureAvailable() CALLED");

        var cl = YtDlpManager.class.getClassLoader();
        System.out.println("RES mac=" + cl.getResource("tools/yt-dlp/mac/yt-dlp"));
//        System.out.println("RES mac.universal=" + cl.getResource("tools/yt-dlp/mac.universal/yt-dlp"));
//        System.out.println("RES mac/arm64=" + cl.getResource("tools/yt-dlp/mac/arm64/yt-dlp"));
        System.out.println("RES win=" + cl.getResource("tools/yt-dlp/windows/x64/yt-dlp.exe"));
        System.out.println("RES lin=" + cl.getResource("tools/yt-dlp/linux/x64/yt-dlp"));

        if (cached != null && Files.exists(cached)) return cached;

        // 1) bundled (prefer bundled on macOS/packaged app)
        try {
            String res = resolveBundledResourcePath();
            if (res == null) {
                // fallback to PATH
                Path fromPath = findOnPath(detectOS() == OS.WINDOWS ? "yt-dlp.exe" : "yt-dlp");
                if (fromPath != null) return cached = fromPath;
                return null;
            }

            Path toolsDir = getAppToolsDir();
            Files.createDirectories(toolsDir);

            String outName = outputBinaryFileName();
            Path out = toolsDir.resolve(outName);

            if (!Files.exists(out) || Files.size(out) == 0) {
                try (InputStream in = YtDlpManager.class.getClassLoader().getResourceAsStream(res)) {
                    if (in == null) return null;
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
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

            // quick smoke-test to understand failures (prints to console)
            try {
                List<String> test = new ArrayList<>();
                test.add(out.toAbsolutePath().toString());
                test.add("--version");
                Process t = new ProcessBuilder(test).redirectErrorStream(true).start();
                String ver;
                try (BufferedReader r = new BufferedReader(new InputStreamReader(t.getInputStream(), StandardCharsets.UTF_8))) {
                    ver = r.readLine();
                }
                t.waitFor();
                System.out.println("YTDLP extracted=" + out + "  version=" + ver);
            } catch (Exception ignored) {}

            return cached = out;

        } catch (Exception e) {
            // 2) PATH fallback if bundled failed
            Path fromPath = findOnPath(detectOS() == OS.WINDOWS ? "yt-dlp.exe" : "yt-dlp");
            if (fromPath != null) return cached = fromPath;
            return null;
        }
    }

    /** Run yt-dlp and return stdout+stderr (merged) as UTF-8. */
    public static String run(List<String> args) throws IOException, InterruptedException {
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
        p.waitFor();
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

        cmd.add("-o");
        cmd.add(req.outputDir.resolve("%(title)s.%(ext)s").toString());

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
//            c.add("tools/yt-dlp/mac.universal/yt-dlp");
//            c.add("tools/yt-dlp/mac/" + (arch == ARCH.ARM64 ? "arm64" : "x64") + "/yt-dlp");
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
}