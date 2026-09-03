
package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import com.grabx.app.grabx.util.AppLog;
import javafx.application.Platform;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

import java.util.function.LongFunction;
import java.util.function.Predicate;
import java.util.logging.Logger;

public final class DownloadRunner {
    private static final Logger LOG = AppLog.get(DownloadRunner.class);

    @FunctionalInterface
    public interface OutputFilenameProbe {
        String probe(Path yt, String url, String selector, Path outDir, String outTpl);
    }

    @FunctionalInterface
    public interface ProgressApplier {
        void apply(DownloadRow row, double progress);
    }

    @FunctionalInterface
    public interface HistorySaver {
        void scheduleSave();
    }

    private final Map<DownloadRow, Process> activeProcesses;
    private final Map<DownloadRow, String> stopReasons;
    private final Map<DownloadRow, Double> lastProgressMap;

    private final HistorySaver historySaver;
    private final Runnable updateMissingSidebarItem;
    private final Runnable refilterDownloads;


    private final OutputFilenameProbe probeOutputFilename;
    private final Predicate<String> supportsAudioThumbnailEmbedding;
    private final Predicate<String> isAudioStreamFromDestinationLine;
    private final Function<String, Long> parseLongSafe;
    private final LongFunction<String> formatBytesDecimal;
    private final Function<String, String> normalizeSpeedUnit;
    private final ProgressApplier applyProgressMonotonic;
    private final Consumer<Process> killProcessTree;

    private final String modeAudio;
    private final String qualityBest;
    private final String qualitySeparator;
    private final String audioBest;
    private final String audioDefaultFormat;
    private final Function<String, Integer> parseHeightFromLabel;

    public DownloadRunner(
            Map<DownloadRow, Process> activeProcesses,
            Map<DownloadRow, String> stopReasons,
            Map<DownloadRow, Double> lastProgressMap,
            HistorySaver historySaver,
            Runnable updateMissingSidebarItem,
            Runnable refilterDownloads,
            Function<String, Integer> parseHeightFromLabel,
            OutputFilenameProbe probeOutputFilename,
            Predicate<String> supportsAudioThumbnailEmbedding,
            Predicate<String> isAudioStreamFromDestinationLine,
            Function<String, Long> parseLongSafe,
            LongFunction<String> formatBytesDecimal,
            Function<String, String> normalizeSpeedUnit,
            ProgressApplier applyProgressMonotonic,
            Consumer<Process> killProcessTree,
            String modeAudio,
            String qualityBest,
            String qualitySeparator,
            String audioBest,
            String audioDefaultFormat
    ) {
        this.activeProcesses = Objects.requireNonNull(activeProcesses, "activeProcesses");
        this.stopReasons = Objects.requireNonNull(stopReasons, "stopReasons");
        this.lastProgressMap = Objects.requireNonNull(lastProgressMap, "lastProgressMap");
        this.historySaver = historySaver == null ? () -> {} : historySaver;
        this.updateMissingSidebarItem = updateMissingSidebarItem == null ? () -> {} : updateMissingSidebarItem;
        this.refilterDownloads = refilterDownloads == null ? () -> {} : refilterDownloads;
        this.parseHeightFromLabel = Objects.requireNonNull(parseHeightFromLabel, "parseHeightFromLabel");
        this.probeOutputFilename = Objects.requireNonNull(probeOutputFilename, "probeOutputFilename");
        this.supportsAudioThumbnailEmbedding = Objects.requireNonNull(supportsAudioThumbnailEmbedding, "supportsAudioThumbnailEmbedding");
        this.isAudioStreamFromDestinationLine = Objects.requireNonNull(isAudioStreamFromDestinationLine, "isAudioStreamFromDestinationLine");
        this.parseLongSafe = Objects.requireNonNull(parseLongSafe, "parseLongSafe");
        this.formatBytesDecimal = Objects.requireNonNull(formatBytesDecimal, "formatBytesDecimal");
        this.normalizeSpeedUnit = Objects.requireNonNull(normalizeSpeedUnit, "normalizeSpeedUnit");
        this.applyProgressMonotonic = Objects.requireNonNull(applyProgressMonotonic, "applyProgressMonotonic");
        this.killProcessTree = Objects.requireNonNull(killProcessTree, "killProcessTree");
        this.modeAudio = Objects.requireNonNull(modeAudio, "modeAudio");
        this.qualityBest = Objects.requireNonNull(qualityBest, "qualityBest");
        this.qualitySeparator = Objects.requireNonNull(qualitySeparator, "qualitySeparator");
        this.audioBest = Objects.requireNonNull(audioBest, "audioBest");
        this.audioDefaultFormat = Objects.requireNonNull(audioDefaultFormat, "audioDefaultFormat");
    }

    public void start(DownloadRow row, boolean resume) {
        if (row == null) return;
        // prevent duplicate runs for same row
        Process existing = activeProcesses.get(row);
        if (existing != null && existing.isAlive()) return;

        stopReasons.remove(row);

        // UI immediately: preparing (indeterminate)
        Platform.runLater(() -> {
            row.setState(DownloadRow.State.DOWNLOADING);
            row.status.set("Preparing");
            row.size.set("");
            row.speed.set("");
            row.eta.set("");
            row.progress.set(-1); // indeterminate while yt-dlp is preparing

        });

        final String url = row.url;
        final String folder = row.folder;
        final String mode = row.mode;
        final String quality = row.quality;

        new Thread(() -> {
            Process p = null;
            final String[] lastError = new String[]{null};

            // detect output file path
            final java.util.regex.Pattern DEST1 =
                    java.util.regex.Pattern.compile("\\[download\\]\\s+Destination:\\s+(.+)$");
            final java.util.regex.Pattern DEST2 =
                    java.util.regex.Pattern.compile("\\[ExtractAudio\\]\\s+Destination:\\s+(.+)$");
            final java.util.regex.Pattern MERGE =
                    java.util.regex.Pattern.compile("\\[Merger\\]\\s+Merging formats into\\s+\\\"(.+)\\\"");

            // our progress template (percent may have padding)
            // gx:  12.3%| 1.2MiB/s| 00:12
            final java.util.regex.Pattern PROG =
                    java.util.regex.Pattern.compile(
                            "^(?:gx:|download:gx:)\\s*([0-9.]+)%\\|\\s*([^|]*)\\|\\s*([^|]*)\\|\\s*([^|]*)\\|\\s*([^|]*)\\|\\s*([^|]*)$"
                    );

            // fallback native progress line
            final java.util.regex.Pattern PROG_FALLBACK =
                    java.util.regex.Pattern.compile("^\\[download\\]\\s+([0-9.]+)%\\s+at\\s+([^\\s]+)\\s+ETA\\s+([^\\s]+).*$");

            final java.util.concurrent.atomic.AtomicBoolean startedDownloading =
                    new java.util.concurrent.atomic.AtomicBoolean(false);

            try {
                java.nio.file.Path outDir = java.nio.file.Paths.get(folder);
                java.nio.file.Files.createDirectories(outDir);

                boolean audioOnly =
                        modeAudio.equals(mode) ||
                                "Audio".equalsIgnoreCase(mode) ||
                                "Audio only".equalsIgnoreCase(mode);

                java.nio.file.Path yt = com.grabx.app.grabx.util.YtDlpManager.ensureAvailable();
                if (yt == null) throw new IllegalStateException("yt-dlp not available");

                java.util.List<String> cmd = new java.util.ArrayList<>();
                cmd.add(yt.toAbsolutePath().toString());

                cmd.add("--newline");
                cmd.add("--no-warnings");
                cmd.add("--no-playlist");

                // allow resume / pause-resume
                cmd.add("--continue");

                cmd.add("--user-agent");
                cmd.add("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36");
                cmd.add("--referer");
                cmd.add("https://www.youtube.com/");
                cmd.add("--extractor-args");
                cmd.add("youtube:player_client=android");

                // Do NOT overwrite existing files (we will decide the naming strategy below)
                cmd.add("--no-overwrites");

                // UTF-8 output
                cmd.add("--encoding");
                cmd.add("utf-8");


                // Build format selector first (we also need it to probe the final output filename)
                String selector;
                int requestedHeight = -1; // used for stable filenames by selected quality

                if (audioOnly) {
                    selector = "bestaudio/best";

                } else {
                    String q = (quality == null) ? qualityBest : quality;

                    if (qualityBest.equals(q) || qualitySeparator.equals(q)) {
                        // Best: selector can yield varying heights, so filename can use real %(height)s
                        selector = "bv*+ba/best";
                        requestedHeight = -1;
                    } else {
                        requestedHeight = parseHeightFromLabel.apply(q);
                        if (requestedHeight > 0) {
                            selector = "bv*[height<=" + requestedHeight + "]+ba/b[height<=" + requestedHeight + "]/best";
                        } else {
                            selector = "bv*+ba/best";
                            requestedHeight = -1;
                        }
                    }

                }

                // Decide the base output template. A concrete collision-free name is
                // selected after probing because yt-dlp's autonumber restarts at 1
                // for every process and is not a collision resolver.
                String baseTpl;
                if (audioOnly) {
                    baseTpl = "%(title)s [audio].%(ext)s";
                } else {
                    if (requestedHeight > 0) {
                        baseTpl = "%(title)s [" + requestedHeight + "p].%(ext)s";
                    } else {
                        baseTpl = "%(title)s [%(height)sp].%(ext)s";
                    }
                }

                String outTpl = baseTpl;
                try {
                    long probeStartMs = System.currentTimeMillis();
                    String probed = probeOutputFilename.probe(yt, url, selector, outDir, baseTpl);
                    LOG.fine(() -> "Output filename probe took "
                            + (System.currentTimeMillis() - probeStartMs) + " ms");
                    String uniqueTemplate = com.grabx.app.grabx.util.DownloadRuntimeUtils
                            .uniqueOutputTemplate(outDir, probed);
                    if (uniqueTemplate != null && !uniqueTemplate.isBlank()) outTpl = uniqueTemplate;
                } catch (Exception ignored) {
                }

                cmd.add("-o");
                cmd.add(java.nio.file.Path.of(outTpl).isAbsolute()
                        ? outTpl
                        : outDir.resolve(outTpl).toString());


                // progress template
                cmd.add("--progress-template");
                cmd.add(
                        "download:gx:%(progress._percent_str)s"
                                + "|%(progress._speed_str)s"
                                + "|%(progress._eta_str)s"
                                + "|%(progress.downloaded_bytes)s"
                                + "|%(progress.total_bytes)s"
                                + "|%(progress.total_bytes_estimate)s"
                );

                if (audioOnly) {
                    cmd.add("-x");
                    cmd.add("--audio-quality");
                    cmd.add("0");

                    String fmt = quality;
                    if (fmt == null || fmt.isBlank() || audioBest.equals(fmt) || qualitySeparator.equals(fmt)) {
                        fmt = audioDefaultFormat;
                    }
                    fmt = fmt.trim().toLowerCase(java.util.Locale.ROOT);
                    cmd.add("--audio-format");
                    cmd.add(fmt);
                    cmd.add("--add-metadata");

                    if (supportsAudioThumbnailEmbedding.test(fmt)) {
                        cmd.add("--embed-thumbnail");
                        cmd.add("--convert-thumbnails");
                        cmd.add("jpg");
                        cmd.add("--postprocessor-args");
                        cmd.add("ffmpeg:-id3v2_version 3");
                        LOG.fine("Audio thumbnail embedding enabled for " + fmt);
                    } else {
                        LOG.fine("Audio thumbnail embedding is unsupported for " + fmt);
                    }

                    cmd.add("-f");
                    cmd.add(selector);

                } else {
                    cmd.add("-f");
                    cmd.add(selector);
                }


                cmd.add(url);

                Path ffmpeg = com.grabx.app.grabx.util.FfmpegManager.ensureAvailable();
                if (ffmpeg != null) {
                    cmd.add("--ffmpeg-location");
                    cmd.add(ffmpeg.toAbsolutePath().toString());
                    LOG.info(() -> "Using FFmpeg at " + ffmpeg);
                } else {
                    LOG.warning("Managed FFmpeg unavailable; yt-dlp will try the system PATH");
                }

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                pb.environment().putIfAbsent("PYTHONIOENCODING", "utf-8");

                long processStartMs = System.currentTimeMillis();
                p = pb.start();
                LOG.fine(() -> "Download process start took "
                        + (System.currentTimeMillis() - processStartMs) + " ms");
                long firstOutputClockMs = System.currentTimeMillis();
                final boolean[] firstOutputLogged = {false};
                activeProcesses.put(row, p);

                try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {

                    String line;
                    while ((line = br.readLine()) != null) {
                        if (!firstOutputLogged[0]) {
                            firstOutputLogged[0] = true;
                            LOG.fine(() -> "First yt-dlp output received after "
                                    + (System.currentTimeMillis() - firstOutputClockMs) + " ms");
                        }

                        String s = line.trim();
                        if (s.isEmpty()) continue;

                        String liveStopReason = stopReasons.get(row);
                        if ("PAUSE".equals(liveStopReason) || "CANCEL".equals(liveStopReason) || "RETRY".equals(liveStopReason)) {
                            try { killProcessTree.accept(p); } catch (Exception ignored) {}
                            break;
                        }

                        // NEWFILE: yt-dlp started a new stream/file (audio/video). Reset monotonic progress so it can start from 0 again.
                        if (s.startsWith("[download] Destination:") || s.startsWith("[ExtractAudio] Destination:")) {

                            final String phaseLabel;
                            if (audioOnly || modeAudio.equals(mode) || "Audio".equalsIgnoreCase(mode) || "Audio only".equalsIgnoreCase(mode)) {
                                phaseLabel = "Downloading audio ";
                            } else {
                                final boolean isAudioStream = isAudioStreamFromDestinationLine.test(s);
                                phaseLabel = isAudioStream ? "Downloading audio " : "Downloading video ";
                            }

                            lastProgressMap.remove(row);

                            Platform.runLater(() -> {
                                try {
                                    row.downloadedBytes.set(0);
                                    row.totalBytes.set(-1);
                                    row.speed.set("");
                                    row.eta.set("");
                                    row.size.set("");
                                    if (row.progress.get() < 0) row.progress.set(0);
                                    row.progress.set(0);

                                    row.status.set(phaseLabel);

                                    if (row.state.get() != DownloadRow.State.DOWNLOADING)
                                        row.setState(DownloadRow.State.DOWNLOADING);

                                } catch (Exception ignored) {}
                            });
                        }

                        // POST: merging/postprocessing (progress is misleading here)
                        if (s.contains("Merging formats into") || s.startsWith("[Merger]") ||
                                s.contains("Post-process") || s.contains("Postprocessing") ||
                                s.contains("Fixing") || s.contains("Extracting") ||
                                s.contains("Deleting original file") || s.contains("Deleting original files")) {

                            Platform.runLater(() -> {
                                try {
                                    row.speed.set("");
                                    row.eta.set("");
                                    row.status.set("Merging . . .");
                                    row.progress.set(-1); // indeterminate
                                } catch (Exception ignored) {}
                            });
                        }

                        if (s.startsWith("ERROR:")) lastError[0] = s;

                        // capture output path
                        try {
                            var d1 = DEST1.matcher(s);
                            var d2 = DEST2.matcher(s);
                            var mg = MERGE.matcher(s);

                            String pathStr = null;
                            if (d1.find()) pathStr = d1.group(1);
                            else if (d2.find()) pathStr = d2.group(1);
                            else if (mg.find()) pathStr = mg.group(1);

                            if (pathStr != null && !pathStr.isBlank()) {
                                String ps = pathStr.trim();
                                if ((ps.startsWith("\"") && ps.endsWith("\"")) || (ps.startsWith("'") && ps.endsWith("'"))) {
                                    ps = ps.substring(1, ps.length() - 1);
                                }
                                java.nio.file.Path finalOut = java.nio.file.Paths.get(ps);
                                try {
                                    if (!finalOut.isAbsolute()) {
                                        // Resolve relative output paths against the selected output directory
                                        finalOut = outDir.resolve(finalOut).normalize();
                                    }
                                } catch (Exception ignored) {}

                                final java.nio.file.Path finalOut2 = finalOut;
                                Platform.runLater(() -> {
                                    try { row.outputFile.set(finalOut2); } catch (Exception ignored) {}
                                });
                            }
                        } catch (Exception ignored) {}

                        // progress (preferred)
                        var m = PROG.matcher(s);
                        if (m.find()) {
                            if (startedDownloading.compareAndSet(false, true)) {
                                Platform.runLater(() -> {
//                                    row.status.set("Downloading");
                                    String cur = row.status.get();
                                    if (cur == null || cur.isBlank() || cur.equals("Preparing")) {
                                        row.status.set("Downloading");
                                    }
                                    row.size.set("");
                                    if (row.progress.get() < 0) row.progress.set(0);
                                });
                            }

                            double pct;
                            try {
                                pct = Double.parseDouble(m.group(1)) / 100.0;
                            } catch (Exception ex) {
                                pct = -1;
                            }

                            String spd = m.group(2);
                            String et  = m.group(3);

                            long downloaded = parseLongSafe.apply(m.group(4));
                            long total = parseLongSafe.apply(m.group(5));
                            if (total <= 0) total = parseLongSafe.apply(m.group(6));

                            // UI size text: downloaded / total (if total known)
                            final String sizeText = com.grabx.app.grabx.util.DownloadRuntimeUtils
                                    .formatTransferSize(downloaded, total, audioOnly);

                            double fpct = pct;
                            long finalDownloaded = downloaded;
                            long finalTotal = total;

                            Platform.runLater(() -> {
                                row.downloadedBytes.set(Math.max(0, finalDownloaded));
                                row.totalBytes.set(finalTotal > 0 ? finalTotal : -1);
//                                row.status.set("Downloading");
                                String cur = row.status.get();
                                if (cur == null || cur.isBlank() || cur.equals("Preparing")) {
                                    row.status.set("Downloading");
                                }
                                // أو ببساطة احذفها إذا أنت أصلاً بتضبط status من NEWFILE
                                row.size.set(sizeText == null ? "" : sizeText);

                                applyProgressMonotonic.apply(row, fpct);

                                if (spd != null && !spd.isBlank() && !"NA".equalsIgnoreCase(spd))
                                    row.speed.set(normalizeSpeedUnit.apply(spd));

                                if (et != null && !et.isBlank() && !"NA".equalsIgnoreCase(et))
                                    row.eta.set(et);
                            });
                            continue;
                        }

                        // progress fallback
                        var mf = PROG_FALLBACK.matcher(s);
                        if (mf.find()) {
                            if (startedDownloading.compareAndSet(false, true)) {
                                Platform.runLater(() -> {
//                                    row.status.set("Downloading");
                                    String cur = row.status.get();
                                    if (cur == null || cur.isBlank() || cur.equals("Preparing")) {
                                        row.status.set("Downloading");
                                    }
                                    // أو ببساطة احذفها إذا أنت أصلاً بتضبط status من NEWFILE
                                    if (row.progress.get() < 0) row.progress.set(0);
                                });
                            }

                            double pct;
                            try { pct = Double.parseDouble(mf.group(1)) / 100.0; } catch (Exception ex) { pct = -1; }
                            String spd = mf.group(2);
                            String et = mf.group(3);

                            double fpct = pct;
                            Platform.runLater(() -> {
//                                row.status.set("Downloading");
                                String cur = row.status.get();
                                if (cur == null || cur.isBlank() || cur.equals("Preparing")) {
                                    row.status.set("Downloading");
                                }
                                // أو ببساطة احذفها إذا أنت أصلاً بتضبط status من NEWFILE
                                applyProgressMonotonic.apply(row, fpct);
                                if (spd != null && !spd.isBlank()) row.speed.set(normalizeSpeedUnit.apply(spd));
                                if (et != null && !et.isBlank()) row.eta.set(et);
                            });
                            continue;
                        }

                        // phase updates during preparing
                        if (!startedDownloading.get()) {
                            // Convert noisy yt-dlp phases to a short friendly text
                            String phase = null;
                            String sl = s.toLowerCase(java.util.Locale.ROOT);

                            if (sl.contains("downloading m3u8") || sl.contains("m3u8 information")) {
                                phase = "Preparing stream";
                            } else if (sl.contains("downloading webpage")) {
                                phase = "Preparing";
                            } else if (sl.contains("extracting")) {
                                phase = "Extracting info";
                            } else if (s.startsWith("[info]") || s.startsWith("[youtube]") || s.startsWith("[generic]")) {
                                phase = "Preparing";
                            }

                            if (phase != null) {
                                final String ph = phase;
                                Platform.runLater(() -> row.status.set(ph));
                            }

                            // Switch to Downloading as soon as we see download lines
                            if (s.startsWith("[download]")) {
                                if (startedDownloading.compareAndSet(false, true)) {
                                    Platform.runLater(() -> {
//                                        row.status.set("Downloading");
                                        String cur = row.status.get();
                                        if (cur == null || cur.isBlank() || cur.equals("Preparing")) {
                                            row.status.set("Downloading");
                                        }
                                        if (row.progress.get() < 0) row.progress.set(0);
                                    });
                                }
                            }
                        }
                    }
                }

                int code = p.waitFor();
                String reason = stopReasons.get(row);

                Platform.runLater(() -> {
                    activeProcesses.remove(row);

                    if ("CANCEL".equals(reason)) {
                        row.setState(DownloadRow.State.CANCELLED);
                        refreshFilters();
                        row.status.set("Cancelled");
                        lastProgressMap.remove(row);
                        row.size.set("");
                        row.speed.set("");
                        row.eta.set("");
                        saveHistory();

                        return;
                    }

                    if ("PAUSE".equals(reason)) {
                        row.setState(DownloadRow.State.PAUSED);
                        row.status.set("Paused");
                        lastProgressMap.remove(row);
                        row.size.set("");
                        row.speed.set("");
                        row.eta.set("");
                        saveHistory();

                        return;
                    }

                    if (code == 0 && hasCompletedOutput(row)) {
                        row.setState(DownloadRow.State.COMPLETED);
                        // CHANGED: set final size from disk if possible
                        try {
                            java.nio.file.Path out = null;
                            if (row.outputFile != null) out = row.outputFile.get();
                            if (out != null && java.nio.file.Files.exists(out)) {
                                long sz = java.nio.file.Files.size(out);
                                row.size.set(formatBytesDecimal.apply(sz));
                            } else {
                                row.size.set("");
                            }
                        } catch (Exception ignored) {
                            row.size.set("");
                        }
                        row.progress.set(1.0);
                        lastProgressMap.put(row, 1.0);
                        row.speed.set("");
                        row.eta.set("");
                        saveHistory();

                    } else {
                        row.setState(DownloadRow.State.FAILED);
                        String err = lastError[0];
                        if (err != null && !err.isBlank()) {
                            // keep it short on the card
                            String msg = err;
                            if (msg.startsWith("ERROR:")) msg = msg.substring("ERROR:".length()).trim();
                            if (msg.length() > 90) msg = msg.substring(0, 90) + "…";
                            row.status.set("Failed: " + msg);
                        } else if (code == 0) {
                            row.status.set("Failed: no output file was created");
                        } else {
                            row.status.set("Failed (exit " + code + ")");
                        }
                        row.size.set("");
                        row.speed.set("");
                        row.eta.set("");
                        saveHistory();

                    }
                });

            } catch (Exception ex) {
                final Process fp = p;
                final String reason = stopReasons.get(row);

                Platform.runLater(() -> {
                    try { if (fp != null) killProcessTree.accept(fp); } catch (Exception ignored) {}
                    activeProcesses.remove(row);

                    if ("PAUSE".equals(reason)) {
                        row.setState(DownloadRow.State.PAUSED);
                        row.status.set("Paused");
                        lastProgressMap.remove(row);
                        row.size.set("");
                        row.speed.set("");
                        row.eta.set("");
                        saveHistory();
                        return;
                    }

                    if ("CANCEL".equals(reason)) {
                        row.setState(DownloadRow.State.CANCELLED);
                        row.status.set("Cancelled");
                        lastProgressMap.remove(row);
                        row.size.set("");
                        row.speed.set("");
                        row.eta.set("");
                        saveHistory();
                        return;
                    }

                    row.setState(DownloadRow.State.FAILED);
                    row.status.set("Failed");
                    row.size.set("");
                    row.speed.set("");
                    row.eta.set("");
                    saveHistory();
                });
            }
        }, "yt-dlp-download").start();

    }

    private void saveHistory() {
        try { historySaver.scheduleSave(); } catch (Exception ignored) {}
    }

    private static boolean hasCompletedOutput(DownloadRow row) {
        try {
            Path output = row == null || row.outputFile == null ? null : row.outputFile.get();
            return output != null && java.nio.file.Files.isRegularFile(output)
                    && java.nio.file.Files.size(output) > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void refreshFilters() {
        Platform.runLater(() -> {
            try { updateMissingSidebarItem.run(); } catch (Exception ignored) {}
            try { refilterDownloads.run(); } catch (Exception ignored) {}
        });
    }
}
