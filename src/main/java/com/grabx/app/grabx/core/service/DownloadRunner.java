
package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import com.grabx.app.grabx.util.AppLog;
import javafx.application.Platform;

import java.nio.file.Path;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

import java.util.function.LongFunction;
import java.util.function.Predicate;
import java.util.logging.Logger;

public final class DownloadRunner {
    private static final Logger LOG = AppLog.get(DownloadRunner.class);
    private static final int MP3_BITRATE_BITS_PER_SECOND = 320_000;
    private static final int MAX_DIRECT_SEGMENTS = 16;
    private static final long TARGET_SEGMENT_BYTES = 8L * 1024 * 1024;
    private static final long MIN_SEGMENTED_BYTES = 4L * 1024 * 1024;

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
        if (existing != null && existing.isAlive()) {
            if (resume && existing instanceof PausableTransfer transfer) {
                transfer.resumeTransfer();
                row.setState(DownloadRow.State.DOWNLOADING);
                row.status.set("Downloading");
            }
            return;
        }

        stopReasons.remove(row);

        // UI immediately: preparing (indeterminate)
        Platform.runLater(() -> {
            row.setState(DownloadRow.State.PENDING);
            row.status.set("Preparing");
            row.size.set("");
            row.speed.set("");
            row.eta.set("");
            row.progress.set(-1); // indeterminate while yt-dlp is preparing

        });

        if ("Direct".equalsIgnoreCase(row.mode)) {
            startDirect(row, resume);
            return;
        }

        final String url = row.url;
        final String folder = row.folder;
        final String mode = row.mode;
        final String quality = row.quality;

        Thread downloadThread = new Thread(() -> {
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
                            "^(?:gx:|download:gx:)\\s*([0-9.]+)%\\|\\s*([^|]*)\\|\\s*([^|]*)\\|\\s*([^|]*)\\|\\s*([^|]*)\\|\\s*([^|]*)\\|\\s*([^|]*)$"
                    );

            // fallback native progress line
            final java.util.regex.Pattern PROG_FALLBACK =
                    java.util.regex.Pattern.compile("^\\[download\\]\\s+([0-9.]+)%\\s+at\\s+([^\\s]+)\\s+ETA\\s+([^\\s]+).*$");

            final java.util.concurrent.atomic.AtomicBoolean startedDownloading =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            final java.util.concurrent.atomic.AtomicReference<java.nio.file.Path> detectedOutput =
                    new java.util.concurrent.atomic.AtomicReference<>();

            try {
                java.nio.file.Path outDir = java.nio.file.Paths.get(folder);
                java.nio.file.Files.createDirectories(outDir);

                boolean audioOnly =
                        modeAudio.equals(mode) ||
                                "Audio".equalsIgnoreCase(mode) ||
                                "Audio only".equalsIgnoreCase(mode);
                String audioFormat = quality;
                if (audioFormat == null || audioFormat.isBlank()
                        || audioBest.equals(audioFormat) || qualitySeparator.equals(audioFormat)) {
                    audioFormat = audioDefaultFormat;
                }
                audioFormat = audioFormat.trim().toLowerCase(java.util.Locale.ROOT);
                final String resolvedAudioFormat = audioFormat;

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
                    // Prefer an audio-only stream. Some YouTube clients expose no
                    // standalone audio format, so retain a compatible fallback.
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
                String probedOutput = null;
                try {
                    long probeStartMs = System.currentTimeMillis();
                    String probed = probeOutputFilename.probe(yt, url, selector, outDir, baseTpl);
                    probedOutput = probed;
                    LOG.fine(() -> "Output filename probe took "
                            + (System.currentTimeMillis() - probeStartMs) + " ms");
                    String selectedTemplate = resume
                            ? com.grabx.app.grabx.util.DownloadRuntimeUtils.resumeOutputTemplate(
                                    outDir, row.outputFile == null ? null : row.outputFile.get())
                            : null;
                    if (selectedTemplate == null || selectedTemplate.isBlank()) {
                        selectedTemplate = com.grabx.app.grabx.util.DownloadRuntimeUtils
                                .uniqueOutputTemplate(outDir, probed);
                    }
                    if (selectedTemplate != null && !selectedTemplate.isBlank()) outTpl = selectedTemplate;
                } catch (Exception ignored) {
                }

                // Persist the chosen source path before yt-dlp starts. If the app is
                // closed before the first Destination line, the next launch can still
                // reuse the exact stem and continue its .part file.
                java.nio.file.Path plannedOutput = com.grabx.app.grabx.util.DownloadRuntimeUtils
                        .concreteOutputPath(outTpl, probedOutput);
                if (plannedOutput != null) {
                    detectedOutput.set(plannedOutput);
                    final java.nio.file.Path savedPlannedOutput = plannedOutput;
                    Platform.runLater(() -> {
                        try { row.outputFile.set(savedPlannedOutput); } catch (Exception ignored) {}
                    });
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
                                + "|%(info.duration)s"
                );

                if (audioOnly) {
                    cmd.add("-x");
                    cmd.add("--audio-quality");
                    cmd.add("mp3".equals(resolvedAudioFormat) ? "320K" : "0");

                    String fmt = resolvedAudioFormat;
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

                        // DOWNLOAD PHASE: yt-dlp started a source stream/file.
                        if (s.startsWith("[download] Destination:")) {

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

                        // POST-PROCESS PHASE: yt-dlp does not expose a reliable
                        // percentage here, so start a distinct animated phase.
                        String postProcessStatus = com.grabx.app.grabx.util.DownloadRuntimeUtils
                                .postProcessStatus(s);
                        if (postProcessStatus != null) {

                            Platform.runLater(() -> {
                                try {
                                    long expectedBytes = row.totalBytes.get();
                                    row.size.set(expectedBytes > 0
                                            ? formatBytesDecimal.apply(expectedBytes)
                                            : "");
                                    row.speed.set("");
                                    row.eta.set("");
                                    row.status.set(postProcessStatus);
                                    row.progress.set(-1);
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
                                detectedOutput.set(finalOut2);
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

                            long estimatedOutput = 0;
                            if (audioOnly && "mp3".equals(resolvedAudioFormat)) {
                                estimatedOutput = com.grabx.app.grabx.util.DownloadRuntimeUtils
                                        .estimateEncodedAudioBytes(m.group(7), MP3_BITRATE_BITS_PER_SECOND);
                            }
                            if (estimatedOutput > 0) {
                                total = estimatedOutput;
                                downloaded = pct < 0
                                        ? 0
                                        : Math.round(estimatedOutput * Math.min(1, pct));
                            }

                            // UI size text: downloaded / total (if total known)
                            final String sizeText = com.grabx.app.grabx.util.DownloadRuntimeUtils
                                    .formatTransferSize(downloaded, total);

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

                if (code == 0 && audioOnly) {
                    com.grabx.app.grabx.util.MacFileIconService.applyEmbeddedArtwork(
                            detectedOutput.get(), ffmpeg
                    );
                }

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
                        try {
                            com.grabx.app.grabx.util.DownloadRuntimeUtils
                                    .cleanupSupersededArtifacts(row.outputFile.get());
                        } catch (Exception ignored) {}
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
        }, "yt-dlp-download");
        downloadThread.setDaemon(true);
        downloadThread.start();

    }

    private void startDirect(DownloadRow row, boolean resume) {
        startDirect(row, resume, 0);
    }

    private void startDirect(DownloadRow row, boolean resume, int reconnectAttempt) {
        Thread downloadThread = new Thread(() -> {
            HttpURLConnection connection = null;
            DirectTransferProcess transfer = new DirectTransferProcess();
            try {
                Path directory = Path.of(row.folder).toAbsolutePath().normalize();
                Files.createDirectories(directory);
                boolean continuing = resume || hasDirectPartial(row);
                Path provisionalOutput = directOutputPath(row, directory, continuing);
                Path provisionalPartial = provisionalOutput.resolveSibling(
                        provisionalOutput.getFileName() + ".grabx.part");
                long existing = continuing && Files.isRegularFile(provisionalPartial)
                        ? Files.size(provisionalPartial) : 0;

                Platform.runLater(() -> {
                    row.setState(DownloadRow.State.PENDING);
                    row.status.set("Connecting");
                });

                connection = (HttpURLConnection) new URL(row.url).openConnection();
                connection.setInstanceFollowRedirects(true);
                connection.setConnectTimeout(20_000);
                connection.setReadTimeout(120_000);
                connection.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/121 Safari/537.36");
                connection.setRequestProperty("Accept", "*/*");
                connection.setRequestProperty("Accept-Encoding", "identity");
                if (existing > 0) connection.setRequestProperty("Range", "bytes=" + existing + "-");

                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) {
                    throw new java.io.IOException("HTTP " + status + " " + connection.getResponseMessage());
                }
                String responseFilename = responseFilename(
                        connection.getHeaderField("Content-Disposition"),
                        connection.getContentType());
                Path output = !continuing && !responseFilename.isBlank()
                        ? uniqueDirectOutput(directory, responseFilename)
                        : provisionalOutput;
                Path partial = output.resolveSibling(output.getFileName() + ".grabx.part");
                final Path selectedOutput = output;
                final String selectedFilename = output.getFileName().toString();
                Platform.runLater(() -> {
                    row.outputFile.set(selectedOutput);
                    row.title.set(selectedFilename);
                    row.titleLocked.set(true);
                });
                validateResumeResponse(existing, status, connection.getHeaderField("Content-Range"));
                boolean append = existing > 0;
                long responseLength = connection.getContentLengthLong();
                long detectedTotal = DirectSizeProbe.sizeFromHeaders(status, responseLength,
                        connection.getHeaderField("Content-Range"));
                // Never hold an already-open download while another request probes size.
                var sizeProbe = detectedTotal < 0
                        ? DirectSizeProbe.probeAsync(connection.getURL(), connection.getHeaderField("ETag"))
                        : java.util.concurrent.CompletableFuture.completedFuture(detectedTotal);
                final long total = detectedTotal;
                boolean supportsRanges = "bytes".equalsIgnoreCase(
                        connection.getHeaderField("Accept-Ranges"));
                if (existing == 0 && total >= MIN_SEGMENTED_BYTES && supportsRanges) {
                    connection.disconnect();
                    connection = null;
                    activeProcesses.put(row, transfer);
                    int segments = directSegmentCount(total);
                    Platform.runLater(() -> {
                        row.setState(DownloadRow.State.DOWNLOADING);
                        row.status.set("Downloading • " + segments + " connections");
                        row.totalBytes.set(total);
                    });
                    long finalSize = downloadSegmented(row, output, total, segments, transfer);
                    String reason = stopReasons.get(row);
                    if (reason == null || reason.isBlank()) {
                        completeDirect(row, output, finalSize);
                    } else {
                        if ("CANCEL".equals(reason)) cleanupSegmentParts(output, segments);
                        finishStoppedDirect(row, reason, null);
                    }
                    transfer.complete(0);
                    return;
                }
                InputStream input = connection.getInputStream();
                transfer.attach(input, downloadThread());
                activeProcesses.put(row, transfer);

                final long initialBytes = existing;
                final long totalBytes = total;
                Platform.runLater(() -> {
                    row.setState(DownloadRow.State.DOWNLOADING);
                    row.status.set(totalBytes > 0 ? "Downloading" : "Downloading • total size unknown");
                    row.downloadedBytes.set(initialBytes);
                    row.totalBytes.set(totalBytes);
                    row.progress.set(totalBytes > 0 ? (double) initialBytes / totalBytes : 0);
                });

                long downloaded = existing;
                long windowBytes = downloaded;
                long windowStarted = System.nanoTime();
                byte[] buffer = new byte[128 * 1024];
                try (InputStream in = input;
                     OutputStream out = Files.newOutputStream(partial,
                             StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                             append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING)) {
                    int read;
                    while (true) {
                        transfer.awaitRunning();
                        read = in.read(buffer);
                        if (read < 0) break;
                        transfer.awaitRunning();
                        String reason = stopReasons.get(row);
                        if ("PAUSE".equals(reason) || "CANCEL".equals(reason) || "RETRY".equals(reason)) break;
                        if (read == 0) continue;
                        out.write(buffer, 0, read);
                        downloaded += read;

                        long now = System.nanoTime();
                        if (now - windowStarted >= 400_000_000L) {
                            double seconds = (now - windowStarted) / 1_000_000_000.0;
                            long bytesPerSecond = Math.max(0, Math.round((downloaded - windowBytes) / seconds));
                            long discoveredTotal = total > 0 ? total : sizeProbe.getNow(-1L);
                            // A fallback probe is advisory; never show an impossible percentage.
                            if (discoveredTotal < downloaded) discoveredTotal = -1;
                            updateDirectProgress(row, downloaded, discoveredTotal, bytesPerSecond);
                            windowBytes = downloaded;
                            windowStarted = now;
                        }
                    }
                }

                String reason = stopReasons.get(row);
                if (reason == null || reason.isBlank()) {
                    if (total > 0 && downloaded < total) {
                        throw new java.io.EOFException(
                                "Connection closed after " + downloaded + " of " + total + " bytes");
                    }
                    Files.move(partial, output, StandardCopyOption.REPLACE_EXISTING);
                    long finalSize = Files.size(output);
                    completeDirect(row, output, finalSize);
                } else {
                    finishStoppedDirect(row, reason, partial);
                }
                transfer.complete(0);
            } catch (Exception exception) {
                String reason = stopReasons.get(row);
                if (reason == null && transfer.pauseGate.isPaused()) reason = "PAUSE";
                if (reason != null && !reason.isBlank()) {
                    finishStoppedDirect(row, reason, null);
                } else if (rootCause(exception) instanceof SocketTimeoutException && reconnectAttempt < 3) {
                    int nextAttempt = reconnectAttempt + 1;
                    Platform.runLater(() -> {
                        row.setState(DownloadRow.State.PENDING);
                        row.status.set("Reconnecting (" + nextAttempt + "/3)");
                        row.speed.set("");
                        row.eta.set("");
                    });
                    Thread reconnect = new Thread(() -> {
                        try { Thread.sleep(750L * nextAttempt); } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        if (stopReasons.get(row) == null) startDirect(row, true, nextAttempt);
                    }, "http-download-reconnect");
                    reconnect.setDaemon(true);
                    reconnect.start();
                } else {
                    String message = exception.getMessage();
                    Platform.runLater(() -> {
                        row.setState(DownloadRow.State.FAILED);
                        row.status.set("Failed: " + shortError(message));
                        row.speed.set("");
                        row.eta.set("");
                        saveHistory();
                        refreshFilters();
                    });
                }
                transfer.complete(1);
            } finally {
                if (connection != null) connection.disconnect();
                activeProcesses.remove(row, transfer);
            }
        }, "http-download");
        downloadThread.setDaemon(true);
        downloadThread.start();
    }

    private long downloadSegmented(DownloadRow row, Path output, long total, int segmentCount,
                                   DirectTransferProcess transfer) throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors
                .newFixedThreadPool(segmentCount, runnable -> {
                    Thread thread = new Thread(runnable, "http-segment");
                    thread.setDaemon(true);
                    return thread;
                });
        java.util.concurrent.atomic.AtomicLong downloaded = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(segmentCount);

        for (int index = 0; index < segmentCount; index++) {
            final int segment = index;
            final long start = total * segment / segmentCount;
            final long end = total * (segment + 1) / segmentCount - 1;
            final Path part = segmentPart(output, segment);
            long present = 0;
            try {
                present = Files.isRegularFile(part) ? Math.min(Files.size(part), end - start + 1) : 0;
            } catch (Exception ignored) {}
            downloaded.addAndGet(present);
            final long existing = present;

            pool.execute(() -> {
                HttpURLConnection segmentConnection = null;
                try {
                    if (existing >= end - start + 1 || stopReasons.get(row) != null) return;
                    segmentConnection = openRangeConnection(row.url, start + existing, end);
                    int status = segmentConnection.getResponseCode();
                    if (status != HttpURLConnection.HTTP_PARTIAL) {
                        throw new java.io.IOException("Server stopped supporting ranged downloads (HTTP " + status + ")");
                    }
                    try (InputStream in = segmentConnection.getInputStream();
                         OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE,
                                 StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                        transfer.attach(in, Thread.currentThread());
                        byte[] buffer = new byte[128 * 1024];
                        int read;
                        while (true) {
                            transfer.awaitRunning();
                            read = in.read(buffer);
                            if (read < 0) break;
                            transfer.awaitRunning();
                            if (stopReasons.get(row) != null) return;
                            if (read == 0) continue;
                            out.write(buffer, 0, read);
                            downloaded.addAndGet(read);
                        }
                    }
                    long expected = end - start + 1;
                    if (Files.size(part) != expected) {
                        throw new java.io.EOFException("Segment " + (segment + 1) + " is incomplete");
                    }
                } catch (Throwable throwable) {
                    if (stopReasons.get(row) == null) failure.compareAndSet(null, throwable);
                } finally {
                    if (segmentConnection != null) segmentConnection.disconnect();
                    finished.countDown();
                }
            });
        }

        long previous = downloaded.get();
        long previousTime = System.nanoTime();
        try {
            while (!finished.await(400, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                Throwable error = failure.get();
                if (error != null) {
                    transfer.destroy();
                    throw new java.util.concurrent.ExecutionException(error);
                }
                long current = downloaded.get();
                long now = System.nanoTime();
                double seconds = (now - previousTime) / 1_000_000_000.0;
                long speed = seconds <= 0 ? 0 : Math.max(0, Math.round((current - previous) / seconds));
                updateDirectProgress(row, current, total, speed);
                previous = current;
                previousTime = now;
            }
            Throwable error = failure.get();
            if (error != null) throw new java.util.concurrent.ExecutionException(error);
            if (stopReasons.get(row) != null) return downloaded.get();

            Path combined = output.resolveSibling(output.getFileName() + ".grabx.part");
            try (OutputStream merged = Files.newOutputStream(combined, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (int segment = 0; segment < segmentCount; segment++) {
                    Files.copy(segmentPart(output, segment), merged);
                }
            }
            if (Files.size(combined) != total) throw new java.io.EOFException("Combined file is incomplete");
            Files.move(combined, output, StandardCopyOption.REPLACE_EXISTING);
            cleanupSegmentParts(output, segmentCount);
            return Files.size(output);
        } finally {
            pool.shutdownNow();
        }
    }

    static void validateResumeResponse(long existing, int status, String contentRange) throws java.io.IOException {
        if (existing <= 0) return;
        if (status != HttpURLConnection.HTTP_PARTIAL) {
            throw new java.io.IOException("Server cannot resume; downloaded part has been kept");
        }
        var range = java.util.regex.Pattern.compile("(?i)bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)")
                .matcher(contentRange == null ? "" : contentRange.trim());
        try {
            if (range.matches() && Long.parseLong(range.group(1)) == existing
                    && Long.parseLong(range.group(2)) >= existing) return;
        } catch (NumberFormatException ignored) {}
        throw new java.io.IOException("Invalid resume range; downloaded part has been kept");
    }

    private static HttpURLConnection openRangeConnection(String url, long start, long end) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(120_000);
        connection.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/121 Safari/537.36");
        connection.setRequestProperty("Accept", "*/*");
                connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("Range", "bytes=" + start + "-" + end);
        return connection;
    }

    private void completeDirect(DownloadRow row, Path output, long finalSize) {
        Platform.runLater(() -> {
            row.outputFile.set(output);
            row.setState(DownloadRow.State.COMPLETED);
            row.progress.set(1);
            row.downloadedBytes.set(finalSize);
            row.totalBytes.set(finalSize);
            row.size.set(formatBytesDecimal.apply(finalSize));
            row.speed.set("");
            row.eta.set("");
            saveHistory();
            refreshFilters();
        });
    }

    private static int directSegmentCount(long total) {
        long desired = Math.max(2, (total + TARGET_SEGMENT_BYTES - 1) / TARGET_SEGMENT_BYTES);
        return (int) Math.min(MAX_DIRECT_SEGMENTS, desired);
    }

    private static Path segmentPart(Path output, int index) {
        return output.resolveSibling(output.getFileName() + ".grabx.part." + index);
    }

    private static void cleanupSegmentParts(Path output, int count) {
        for (int index = 0; index < count; index++) {
            try { Files.deleteIfExists(segmentPart(output, index)); } catch (Exception ignored) {}
        }
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current;
    }

    private void updateDirectProgress(DownloadRow row, long downloaded, long total, long bytesPerSecond) {
        String sizeText = com.grabx.app.grabx.util.DownloadRuntimeUtils.formatTransferSize(downloaded, total);
        String speedText = formatBytesDecimal.apply(bytesPerSecond) + "/s";
        String etaText = total > 0 && bytesPerSecond > 0
                ? formatEta((total - downloaded) / bytesPerSecond) : "";
        Platform.runLater(() -> {
            if (row.getState() != DownloadRow.State.DOWNLOADING) return;
            row.downloadedBytes.set(downloaded);
            row.totalBytes.set(total);
            row.size.set(sizeText == null ? "" : sizeText);
            row.speed.set(speedText);
            row.eta.set(etaText);
            row.status.set(total > 0 ? "Downloading" : "Downloading • total size unknown");
            row.progress.set(total > 0 ? Math.min(1, (double) downloaded / total) : 0);
        });
    }

    private void finishStoppedDirect(DownloadRow row, String reason, Path partial) {
        if ("CANCEL".equals(reason) && partial != null) {
            try { Files.deleteIfExists(partial); } catch (Exception ignored) {}
        }
        Platform.runLater(() -> {
            row.setState("PAUSE".equals(reason) ? DownloadRow.State.PAUSED : DownloadRow.State.CANCELLED);
            row.speed.set("");
            row.eta.set("");
            saveHistory();
            refreshFilters();
        });
    }

    private static Path directOutputPath(DownloadRow row, Path directory, boolean resume) {
        if (resume && row.outputFile != null && row.outputFile.get() != null) {
            return row.outputFile.get().toAbsolutePath().normalize();
        }
        String filename = safeFilename(row.title == null ? null : row.title.get());
        if (filename.isBlank() || "New item".equals(filename)) filename = filenameFromUrl(row.url);
        return uniqueDirectOutput(directory, filename);
    }

    private static boolean hasDirectPartial(DownloadRow row) {
        try {
            Path output = row == null || row.outputFile == null ? null : row.outputFile.get();
            if (output == null) return false;
            Path partial = output.resolveSibling(output.getFileName() + ".grabx.part");
            return Files.isRegularFile(partial) || Files.isRegularFile(segmentPart(output, 0));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Path uniqueDirectOutput(Path directory, String value) {
        String filename = safeFilename(value);
        if (filename.isBlank()) filename = "download";
        Path candidate = directory.resolve(filename).normalize();
        if (!candidate.getParent().equals(directory)) candidate = directory.resolve("download");
        if (!Files.exists(candidate) && !Files.exists(candidate.resolveSibling(candidate.getFileName() + ".grabx.part"))) {
            return candidate;
        }
        String name = candidate.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int index = 2; ; index++) {
            Path alternate = directory.resolve(stem + " (" + index + ")" + extension);
            if (!Files.exists(alternate)
                    && !Files.exists(alternate.resolveSibling(alternate.getFileName() + ".grabx.part"))) return alternate;
        }
    }

    static String responseFilename(String contentDisposition, String contentType) {
        String filename = contentDispositionFilename(contentDisposition);
        if (!filename.isBlank()) return safeFilename(filename);
        return extensionForContentType(contentType);
    }

    private static String contentDispositionFilename(String value) {
        if (value == null || value.isBlank()) return "";
        java.util.regex.Matcher encoded = java.util.regex.Pattern
                .compile("(?i)(?:^|;)\\s*filename\\*\\s*=\\s*([^;]+)")
                .matcher(value);
        if (encoded.find()) {
            String raw = encoded.group(1).trim().replaceAll("^\"|\"$", "");
            int marker = raw.indexOf("''");
            if (marker >= 0) raw = raw.substring(marker + 2);
            try {
                return java.net.URLDecoder.decode(raw, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
        }
        java.util.regex.Matcher plain = java.util.regex.Pattern
                .compile("(?i)(?:^|;)\\s*filename\\s*=\\s*(?:\"([^\"]+)\"|([^;]+))")
                .matcher(value);
        if (plain.find()) return plain.group(1) != null ? plain.group(1) : plain.group(2).trim();
        return "";
    }

    private static String extensionForContentType(String value) {
        if (value == null) return "";
        String type = value.toLowerCase(java.util.Locale.ROOT).split(";", 2)[0].trim();
        return switch (type) {
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "download.xlsx";
            case "application/vnd.ms-excel" -> "download.xls";
            case "application/pdf" -> "download.pdf";
            case "application/zip" -> "download.zip";
            case "image/jpeg" -> "download.jpg";
            case "image/png" -> "download.png";
            case "video/mp4" -> "download.mp4";
            case "audio/mpeg" -> "download.mp3";
            default -> "";
        };
    }

    private static String filenameFromUrl(String value) {
        try {
            String path = new URL(value).getPath();
            String name = path.substring(path.lastIndexOf('/') + 1);
            name = java.net.URLDecoder.decode(name, java.nio.charset.StandardCharsets.UTF_8);
            String safe = safeFilename(name);
            return safe.isBlank() ? "download" : safe;
        } catch (Exception ignored) {
            return "download";
        }
    }

    private static String safeFilename(String value) {
        if (value == null) return "";
        String name = value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        if (name.equals(".") || name.equals("..")) return "";
        return name.length() > 255 ? name.substring(0, 255) : name;
    }

    private static String formatEta(long seconds) {
        long safe = Math.max(0, seconds);
        return String.format(java.util.Locale.ROOT, "%02d:%02d", safe / 60, safe % 60);
    }

    private static String shortError(String value) {
        String text = value == null || value.isBlank() ? "Download failed" : value.strip();
        return text.length() > 90 ? text.substring(0, 90) + "…" : text;
    }

    private static Thread downloadThread() {
        return Thread.currentThread();
    }

    private static final class DirectTransferProcess extends Process implements PausableTransfer {
        private final TransferPauseGate pauseGate = new TransferPauseGate();
        @Override public void pauseTransfer() { pauseGate.pause(); }
        @Override public void resumeTransfer() { pauseGate.resume(); }
        void awaitRunning() throws InterruptedException { pauseGate.awaitRunning(); }
        private final java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.ConcurrentLinkedQueue<Closeable> streams =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        private final java.util.concurrent.ConcurrentLinkedQueue<Thread> threads =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        private volatile Integer exitCode;

        void attach(Closeable stream, Thread thread) {
            if (stream != null) streams.add(stream);
            if (thread != null) threads.add(thread);
        }

        void complete(int code) {
            exitCode = code;
            finished.countDown();
        }

        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() throws InterruptedException { finished.await(); return exitValue(); }
        @Override public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
            return finished.await(timeout, unit);
        }
        @Override public int exitValue() {
            if (exitCode == null) throw new IllegalThreadStateException("Transfer is running");
            return exitCode;
        }
        @Override public void destroy() {
            pauseGate.stop();
            streams.forEach(stream -> { try { stream.close(); } catch (Exception ignored) {} });
            threads.forEach(Thread::interrupt);
        }
        @Override public Process destroyForcibly() { destroy(); return this; }
        @Override public boolean isAlive() { return exitCode == null; }
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
