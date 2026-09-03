package com.grabx.app.grabx.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Process and output helpers used by the download runner. */
public final class DownloadRuntimeUtils {
    private static final Set<String> EMBEDDABLE_AUDIO_FORMATS = Set.of(
            "mp3", "m4a", "opus", "ogg", "flac", "mka", "mkv", "mp4", "m4b", "m4p"
    );
    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            "m4a", "mp3", "aac", "opus", "ogg", "flac", "wav"
    );
    private static final Set<Integer> AUDIO_FORMAT_IDS = Set.of(
            139, 140, 141, 249, 250, 251, 599, 600
    );
    private static final Pattern FORMAT_ID = Pattern.compile("\\.f(\\d{2,4})\\.", Pattern.CASE_INSENSITIVE);
    private static final Pattern SPEED = Pattern.compile(
            "([0-9]+(?:\\.[0-9]+)?)\\s*(KB/s|MB/s|GB/s|TB/s)",
            Pattern.CASE_INSENSITIVE
    );

    private DownloadRuntimeUtils() {}

    public static boolean supportsAudioThumbnailEmbedding(String format) {
        if (format == null) return false;
        return EMBEDDABLE_AUDIO_FORMATS.contains(format.trim().toLowerCase(Locale.ROOT));
    }

    public static boolean isAudioStreamFromDestinationLine(String line) {
        if (line == null) return false;
        if (line.startsWith("[ExtractAudio]")) return true;

        int destination = line.indexOf("Destination");
        if (destination < 0) return false;
        int colon = line.indexOf(':', destination);
        String path = colon >= 0
                ? line.substring(colon + 1).trim()
                : line.substring(destination + "Destination".length()).trim();
        path = stripQuotes(path);
        if (path.isEmpty()) return false;

        Matcher formatId = FORMAT_ID.matcher(path);
        if (formatId.find()) {
            try {
                return AUDIO_FORMAT_IDS.contains(Integer.parseInt(formatId.group(1)));
            } catch (NumberFormatException ignored) {
                return false;
            }
        }

        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) return false;
        return AUDIO_EXTENSIONS.contains(path.substring(dot + 1).trim().toLowerCase(Locale.ROOT));
    }

    public static String probeOutputFilename(Path ytDlp, String url, String selector, Path outputDirectory, String template) {
        if (ytDlp == null || url == null || url.isBlank() || selector == null
                || outputDirectory == null || template == null) return null;

        try {
            List<String> command = new ArrayList<>();
            command.add(ytDlp.toAbsolutePath().toString());
            command.add("--no-warnings");
            command.add("--no-playlist");
            command.add("--skip-download");
            command.add("--encoding");
            command.add("utf-8");
            command.add("--user-agent");
            command.add("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36");
            command.add("--referer");
            command.add("https://www.youtube.com/");
            command.add("--extractor-args");
            command.add("youtube:player_client=android");
            command.add("-f");
            command.add(selector);
            command.add("-o");
            command.add(outputDirectory.resolve(template).toString());
            command.add("--print");
            command.add("filename");
            command.add(url.trim());

            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String line;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                line = reader.readLine();
            }
            try {
                process.waitFor();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
            return line == null || line.trim().isEmpty() ? null : line.trim();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Turns a probed filename into a literal yt-dlp template whose stem does not
     * collide with an existing output, regardless of the existing file's extension.
     */
    public static String uniqueOutputTemplate(Path outputDirectory, String probedFilename) {
        if (outputDirectory == null || probedFilename == null || probedFilename.isBlank()) return null;
        try {
            Path probedPath = Path.of(probedFilename.trim());
            String filename = probedPath.getFileName().toString();
            String stem = stripExtension(filename);
            if (stem.isBlank()) return null;

            int suffix = 0;
            while (stemExists(outputDirectory, stem, suffix)) suffix++;

            String uniqueStem = suffix == 0 ? stem : stem + " (" + suffix + ")";
            // Literal '%' characters in media titles must not become template fields.
            String escapedStem = uniqueStem.replace("%", "%%");
            return outputDirectory.resolve(escapedStem + ".%(ext)s").toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean stemExists(Path directory, String stem, int suffix) {
        String candidate = suffix == 0 ? stem : stem + " (" + suffix + ")";
        try (var files = Files.list(directory)) {
            return files.anyMatch(path -> {
                try {
                    return Files.isRegularFile(path)
                            && stripExtension(path.getFileName().toString()).equals(candidate);
                } catch (Exception ignored) {
                    return false;
                }
            });
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String stripExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    public static void killProcessTree(Process process) {
        if (process == null) return;
        try {
            ProcessHandle handle = process.toHandle();
            handle.descendants().forEach(child -> {
                try { child.destroy(); } catch (Exception ignored) {}
            });
            try { handle.destroy(); } catch (Exception ignored) {}
            try {
                Thread.sleep(150);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            handle.descendants().forEach(child -> {
                try { if (child.isAlive()) child.destroyForcibly(); } catch (Exception ignored) {}
            });
            try { if (handle.isAlive()) handle.destroyForcibly(); } catch (Exception ignored) {}
        } catch (Exception ignored) {
            try { process.destroyForcibly(); } catch (Exception ignoredAgain) {}
        }
    }

    public static long parseLongSafe(String value) {
        try {
            if (value == null) return 0L;
            String normalized = value.trim();
            if (normalized.isEmpty() || "NA".equalsIgnoreCase(normalized) || "None".equalsIgnoreCase(normalized)) return 0L;
            return Long.parseLong(normalized);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    public static String formatBytesDecimal(long bytes) {
        if (bytes <= 0) return "0 B";
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unit = 0;
        while (value >= 1000.0 && unit < units.length - 1) {
            value /= 1000.0;
            unit++;
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit]);
    }

    public static String formatTransferSize(long downloaded, long total) {
        if (downloaded <= 0) return "";
        String downloadedText = formatBytesDecimal(downloaded);
        if (total <= 0) return downloadedText;
        return downloadedText + " / " + formatBytesDecimal(total);
    }

    public static String formatDownloadedSource(long downloaded, long total) {
        long completedSourceBytes = total > 0 ? total : downloaded;
        return completedSourceBytes > 0
                ? formatBytesDecimal(completedSourceBytes) + " downloaded"
                : "";
    }

    public static String postProcessStatus(String outputLine) {
        if (outputLine == null || outputLine.isBlank()) return null;
        String line = outputLine.trim();
        if (line.startsWith("[ExtractAudio]")) return "Converting audio...";
        if (line.startsWith("[Merger]") || line.contains("Merging formats into")) {
            return "Merging audio and video...";
        }
        if (line.contains("Post-process") || line.contains("Postprocessing")
                || line.contains("Fixing") || line.contains("Deleting original file")
                || line.contains("Deleting original files")) {
            return "Finalizing...";
        }
        return null;
    }

    public static String normalizeSpeedUnit(String speed) {
        if (speed == null) return null;
        String normalized = speed.trim();
        if (normalized.isEmpty() || "NA".equalsIgnoreCase(normalized)) return "";

        normalized = normalized.replace("KiB/s", "KB/s")
                .replace("MiB/s", "MB/s")
                .replace("GiB/s", "GB/s")
                .replace("TiB/s", "TB/s");
        Matcher matcher = SPEED.matcher(normalized);
        if (!matcher.find()) return normalized;
        try {
            double value = Double.parseDouble(matcher.group(1));
            return String.format(Locale.US, "%.1f %s", value, matcher.group(2).toUpperCase(Locale.ROOT));
        } catch (NumberFormatException ignored) {
            return normalized;
        }
    }

    private static String stripQuotes(String value) {
        if (value == null || value.isEmpty()) return "";
        if ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }
}
