package com.grabx.app.grabx.util;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Reject broken or wrong-architecture tool paths before persisting them. */
final class ToolExecutable {
    private ToolExecutable() {}
    static java.util.List<Path> candidates(String name, java.util.Map<String, String> environment, Path home, boolean windows) {
        var directories = new java.util.LinkedHashSet<Path>();
        for (String entry : environment.getOrDefault("PATH", "").split(windows ? ";" : java.io.File.pathSeparator)) {
            String value = entry.trim();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) value = value.substring(1, value.length()-1);
            if (!value.isBlank()) try { directories.add(Path.of(value)); } catch (Exception ignored) { }
        }
        if (windows) {
            String local = environment.get("LOCALAPPDATA");
            if (local != null && !local.isBlank()) directories.add(Path.of(local, "Microsoft", "WinGet", "Links"));
            String programs = environment.get("ProgramFiles");
            if (programs != null && !programs.isBlank()) directories.add(Path.of(programs, "ffmpeg", "bin"));
        } else {
            directories.add(home.resolve(".local/bin"));
            directories.add(Path.of("/usr/local/bin"));
            directories.add(Path.of("/usr/bin"));
            directories.add(Path.of("/opt/homebrew/bin"));
        }
        return directories.stream().map(directory -> directory.resolve(name)).toList();
    }
    static String version(Path executable, String option) {
        if (executable == null || !Files.isRegularFile(executable) || !Files.isExecutable(executable)) return null;
        Process process = null;
        try {
            process = new ProcessBuilder(executable.toString(), option).redirectErrorStream(true).start();
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) return null;
            String output = new String(process.getInputStream().readNBytes(4096), StandardCharsets.UTF_8).trim();
            return output.isEmpty() ? null : output.lines().findFirst().orElse(null);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); return null;
        } catch (Exception error) { return null; }
        finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
    }
}
