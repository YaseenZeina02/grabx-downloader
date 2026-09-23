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
        return probeOutputFilename(ytDlp, url, selector, outputDirectory, template, true);
    }

    private static String probeOutputFilename(Path ytDlp, String url, String selector, Path outputDirectory,
                                              String template, boolean useCache) {
        if (ytDlp == null || url == null || url.isBlank() || selector == null
                || outputDirectory == null || template == null) return null;

        Process process = null;
        try (MediaInfoCache.Input input = useCache ? MediaInfoCache.SHARED.openInput(url) : new MediaInfoCache.Input(url, null)) {
            List<String> command = new ArrayList<>();
            command.add(ytDlp.toAbsolutePath().toString());
            command.addAll(YtDlpOptions.extractionArguments());
            command.addAll(YtDlpOptions.selectionArguments(selector));
            command.add("--no-warnings");
            command.add("--no-playlist");
            command.add("--skip-download");
            command.add("--encoding");
            command.add("utf-8");
            command.add("--dump-single-json");
            command.add("-f");
            command.add(selector);
            command.add("-o");
            command.add(outputDirectory.resolve(template).toString());
            command.add("--print");
            command.add("filename");
            command.addAll(input.arguments());

            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String filename = null;
            String metadata = null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String value = line.trim();
                    if (value.startsWith("{")) metadata = value;
                    else if (filename == null) {
                        try {
                            if (Path.of(value).isAbsolute()) filename = value;
                        } catch (Exception ignored) {}
                    }
                }
            }
            if (process.waitFor() != 0) {
                if (input.cached()) {
                    MediaInfoCache.SHARED.invalidate(url);
                    return probeOutputFilename(ytDlp, url, selector, outputDirectory, template, false);
                }
                return null;
            }
            // Reading a cached snapshot must not extend its signed URLs' lifetime.
            if (!input.cached()) MediaInfoCache.SHARED.remember(url, metadata);
            if (filename != null && metadata != null && template.contains("%(height)sp")) {
                var info = new com.fasterxml.jackson.databind.ObjectMapper().readTree(metadata);
                int height = info.path("height").asInt(-1);
                int quality = VideoQualityUtils.qualityHeight(info.path("width").asInt(-1), height,
                        info.path("format_note").asText(""));
                if (quality > 0 && height > 0) filename = filename.replace("[" + height + "p].", "[" + quality + "p].");
            }
            return filename;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
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

    public record OutputPlan(String template, Path plannedOutput) {}

    public static OutputPlan planOutput(Path directory, Path recordedOutput, boolean resume,
                                        String baseTemplate, java.util.function.Supplier<String> probe) {
        String saved = resume ? resumeOutputTemplate(directory, recordedOutput) : null;
        if (saved != null) return new OutputPlan(saved, mediaOutputPath(recordedOutput).toAbsolutePath().normalize());
        try {
            String probed = probe.get();
            String selected = uniqueOutputTemplate(directory, probed);
            if (selected != null) return new OutputPlan(selected, concreteOutputPath(selected, probed));
        } catch (Exception ignored) {}
        return new OutputPlan(baseTemplate, null);
    }

    /**
     * Reuses the exact stem recorded for a paused row. A .part file is not a
     * collision: it is the data yt-dlp must see under the same output template
     * in order for --continue to resume it.
     */
    public static String resumeOutputTemplate(Path outputDirectory, Path recordedOutput) {
        if (outputDirectory == null || recordedOutput == null) return null;
        try {
            Path directory = outputDirectory.toAbsolutePath().normalize();
            Path recorded = recordedOutput.toAbsolutePath().normalize();
            if (recorded.getParent() == null || !recorded.getParent().equals(directory)) return null;

            String filename = mediaOutputPath(recorded).getFileName().toString();
            if (filename.endsWith(".part")) filename = filename.substring(0, filename.length() - 5);
            String stem = stripExtension(filename);
            if (stem.isBlank()) return null;
            return directory.resolve(stem.replace("%", "%%") + ".%(ext)s").toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Keep yt-dlp's intermediate format suffixes out of the persisted output stem.
     * Also repairs paths saved by older builds after repeated pause/resume.
     * Only GrabX's generated media naming pattern is recognized; ordinary titles
     * ending in something like .f401 are left intact.
     */
    public static Path mediaOutputPath(Path path) {
        if (path == null || path.getFileName() == null) return path;
        String name = path.getFileName().toString();
        if (name.endsWith(".part")) name = name.substring(0, name.length() - 5);
        name = name.replaceFirst("^(.*\\[(?:audio|[0-9]+p)\\](?: \\([0-9]+\\))?)(?:\\.f[\\w-]+)+(\\.[^.]+)$", "$1$2");
        return path.resolveSibling(name);
    }

    /** Resolves a literal %(ext)s template to the extension returned by the probe. */
    public static Path concreteOutputPath(String outputTemplate, String probedFilename) {
        if (outputTemplate == null || outputTemplate.isBlank()
                || probedFilename == null || probedFilename.isBlank()) return null;
        try {
            String probeName = Path.of(probedFilename.trim()).getFileName().toString();
            int dot = probeName.lastIndexOf('.');
            if (dot < 0 || dot == probeName.length() - 1) return null;
            String extension = probeName.substring(dot + 1);
            return Path.of(outputTemplate.replace("%(ext)s", extension).replace("%%", "%"));
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Removes partials and thumbnail sidecars for this exact numbered media stem
     * after a download completes. Finished audio/video files are never removed.
     */
    public static int cleanupSupersededArtifacts(Path completedOutput) {
        if (completedOutput == null) return 0;
        try {
            Path completed = completedOutput.toAbsolutePath().normalize();
            Path directory = completed.getParent();
            if (directory == null || !Files.isDirectory(directory)) return 0;
            String familyStem = stripExtension(completed.getFileName().toString());
            int removed = 0;
            try (var files = Files.list(directory)) {
                for (Path path : files.toList()) {
                    if (path == null || path.toAbsolutePath().normalize().equals(completed)) continue;
                    String name = path.getFileName().toString();
                    if (!isDisposableArtifact(name)) continue;
                    if (!familyStem.equals(canonicalArtifactStem(name))) continue;
                    try {
                        if (Files.deleteIfExists(path)) removed++;
                    } catch (Exception ignored) {
                    }
                }
            }
            return removed;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static boolean isDisposableArtifact(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".part")
                || lower.endsWith(".ytdl")
                || lower.endsWith(".tmp")
                || lower.endsWith(".temp")
                || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".webp")
                || lower.endsWith(".png");
    }

    private static String canonicalArtifactStem(String filename) {
        String value = filename;
        String lower = value.toLowerCase(Locale.ROOT);
        for (String suffix : List.of(".part", ".ytdl", ".tmp", ".temp")) {
            if (lower.endsWith(suffix)) {
                value = value.substring(0, value.length() - suffix.length());
                break;
            }
        }
        value = stripExtension(value);
        value = value.replaceFirst("(?i)(?:\\.f\\d{2,4})+$", "");
        return value;
    }

    private static String canonicalNumberedStem(String stem) {
        return stem == null ? "" : stem.replaceFirst(" \\(\\d+\\)$", "");
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

    public static long estimateEncodedAudioBytes(String durationSeconds, int bitrateBitsPerSecond) {
        if (durationSeconds == null || bitrateBitsPerSecond <= 0) return 0;
        try {
            double duration = Double.parseDouble(durationSeconds.trim());
            if (!Double.isFinite(duration) || duration <= 0) return 0;
            return Math.round(duration * bitrateBitsPerSecond / 8.0);
        } catch (NumberFormatException ignored) {
            return 0;
        }
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
