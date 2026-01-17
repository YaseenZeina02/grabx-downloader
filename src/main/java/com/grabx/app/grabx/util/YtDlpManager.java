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

    /** Returns yt-dlp path (PATH first, then bundled-extract). */
    public static Path ensureAvailable() {
        if (cached != null && Files.exists(cached)) return cached;

        // 1) PATH
        Path fromPath = findOnPath(detectOS() == OS.WINDOWS ? "yt-dlp.exe" : "yt-dlp");
        if (fromPath != null) return cached = fromPath;

        // 2) bundled
        try {
            String res = resolveBundledResourcePath();
            if (res == null) return null;

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
                // best-effort: chmod +x
                try {
                    new ProcessBuilder("chmod", "+x", out.toAbsolutePath().toString())
                            .redirectErrorStream(true)
                            .start()
                            .waitFor();
                } catch (Exception ignored) {}
            }

            return cached = out;

        } catch (Exception e) {
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
        ARCH arch = detectArch();

        if (os == OS.WINDOWS) {
            if (arch == ARCH.X86) return "yt-dlp_x86.exe";
            if (arch == ARCH.ARM64) return "yt-dlp_arm64.exe";
            return "yt-dlp.exe";
        }
        if (os == OS.MAC) return "yt-dlp_macos";                 // universal
        if (os == OS.LINUX) return (arch == ARCH.ARM64) ? "yt-dlp_linux_aarch64" : "yt-dlp_linux";
        return (os == OS.WINDOWS) ? "yt-dlp.exe" : "yt-dlp";
    }

    private static String resolveBundledResourcePath() {
        OS os = detectOS();
        ARCH arch = detectArch();

        List<String> c = new ArrayList<>();

        // Your current tree: resources/tools.yt-dlp/...
        if (os == OS.MAC) {
            c.add("tools.yt-dlp/mac.universal/yt-dlp");
        } else if (os == OS.LINUX) {
            c.add("tools.yt-dlp/linux/" + (arch == ARCH.ARM64 ? "arm64" : "x64") + "/yt-dlp");
        } else if (os == OS.WINDOWS) {
            String a = (arch == ARCH.ARM64) ? "arm64" : (arch == ARCH.X86 ? "x86" : "x64");
            c.add("tools.yt-dlp/windows/" + a + "/yt-dlp.exe");
        }

        // Optional flat layout later:
        c.add("tools.yt-dlp/" + outputBinaryFileName());

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