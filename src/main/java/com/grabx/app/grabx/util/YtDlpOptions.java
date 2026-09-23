package com.grabx.app.grabx.util;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** One extractor configuration for analysis, filename probes, and downloads. */
public final class YtDlpOptions {
    private static volatile List<String> resolved;
    private YtDlpOptions() {}

    public static List<String> extractionArguments() {
        List<String> result = resolved;
        if (result != null) return result;
        synchronized (YtDlpOptions.class) {
            if (resolved != null) return resolved;
            for (Runtime candidate : candidates(System.getenv(), Path.of(System.getProperty("user.home")),
                    System.getProperty("os.name", "").toLowerCase().contains("win"))) {
                if (isSupported(candidate)) {
                    resolved = argumentsFor(candidate);
                    AppLog.get(YtDlpOptions.class).info("YouTube JavaScript runtime: " + candidate.path);
                    return resolved;
                }
            }
            // Preserve the previously working basic client on machines without JS.
            // Do not silently select the default clients that require an unavailable solver.
            AppLog.get(YtDlpOptions.class).warning("No supported JavaScript runtime: using basic YouTube formats. Install Node 22+ or Deno 2.3+ for full quality support.");
            return resolved = argumentsFor(null);
        }
    }

    record Runtime(String name, Path path) {}

    static List<String> argumentsFor(Runtime runtime) {
        return runtime == null ? List.of("--extractor-args", "youtube:player_client=android")
                : List.of("--js-runtimes", runtime.name + ":" + runtime.path.toAbsolutePath());
    }

    public static List<String> videoFormatArguments() {
        // Resolution wins; codec preference only breaks ties at the same resolution.
        return List.of("--format-sort", "res,vcodec:h264,acodec:aac");
    }

    public static List<String> selectionArguments(String selector) {
        return selector == null || selector.startsWith("bestaudio") || selector.startsWith("ba/")
                ? List.of() : videoFormatArguments();
    }

    static List<Runtime> candidates(Map<String, String> environment, Path home, boolean windows) {
        var result = new ArrayList<Runtime>();
        var directories = new java.util.LinkedHashSet<Path>();
        String search = environment.getOrDefault("PATH", "");
        for (String part : search.split(Pattern.quote(windows ? ";" : File.pathSeparator))) {
            if (!part.isBlank()) try { directories.add(Path.of(part)); } catch (Exception ignored) {}
        }
        directories.add(home.resolve(".deno/bin"));
        if (!windows) directories.add(home.resolve(".local/bin"));
        if (windows) {
            for (String key : List.of("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA")) {
                String path = environment.get(key);
                if (path == null || path.isBlank()) continue;
                directories.add(Path.of(path).resolve("nodejs"));
                directories.add(Path.of(path).resolve("Microsoft/WinGet/Links"));
            }
        } else {
            // Finder/IntelliJ need not inherit the interactive shell's PATH.
            directories.add(Path.of("/opt/homebrew/bin"));
            directories.add(Path.of("/usr/local/bin"));
            directories.add(Path.of("/usr/bin"));
        }
        for (String name : List.of("deno", "node")) {
            for (Path directory : directories) result.add(new Runtime(name, directory.resolve(name + (windows ? ".exe" : ""))));
        }
        return result;
    }

    static boolean supportedVersion(String runtime, String version) {
        var matcher = Pattern.compile("(?:deno\\s+|v)(\\d+)\\.(\\d+)").matcher(version == null ? "" : version);
        if (!matcher.find()) return false;
        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        return runtime.equals("node") ? major >= 22 : major > 2 || (major == 2 && minor >= 3);
    }

    private static boolean isSupported(Runtime runtime) {
        if (!Files.isRegularFile(runtime.path) || !Files.isExecutable(runtime.path)) return false;
        Process process = null;
        try {
            process = new ProcessBuilder(runtime.path.toString(), "--version").redirectErrorStream(true).start();
            return process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0
                    && supportedVersion(runtime.name, new String(process.getInputStream().readNBytes(1024), StandardCharsets.UTF_8));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception exception) {
            return false;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }
}
