
package com.grabx.app.grabx.core.service;

import java.io.BufferedOutputStream;
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
    private static final Object DIRECT_OUTPUT_LOCK = new Object();
    private static final java.util.Set<Path> DIRECT_OUTPUTS = java.util.concurrent.ConcurrentHashMap.newKeySet();

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

    private final java.util.Set<DownloadRow> workerRows = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private Runnable onWorkerReleased = () -> {};

    public void onWorkerReleased(Runnable callback) { onWorkerReleased = callback; }

    public boolean canStart(DownloadRow row, boolean resume) {
        Process process = activeProcesses.get(row);
        if (resume && process instanceof DirectTransferProcess direct && direct.isAlive()
                && direct.pauseGate.isPaused() && !stopReasons.containsKey(row)) return true;
        return !workerRows.contains(row);
    }

    public boolean occupiesSlot(DownloadRow row) {
        Process process = activeProcesses.get(row);
        if (process instanceof DirectTransferProcess direct && direct.isAlive() && direct.pauseGate.isPaused()) return false;
        return workerRows.contains(row);
    }

    private void releaseWorker(DownloadRow row) {
        // Post after all completion/stop UI callbacks, preventing a stale callback
        // from overwriting a newly admitted run of the same row.
        Platform.runLater(() -> { workerRows.remove(row); onWorkerReleased.run(); });
    }

    private final BandwidthLimiter bandwidth = new BandwidthLimiter();
    private volatile long mediaRate;
    private volatile boolean quickResumeChecks;
    public void configureQuickResumeChecks(boolean value) { quickResumeChecks = value; }
    private volatile int directConnections = TransferPreferences.DEFAULT_CONNECTIONS;
    public void configureDirectConnections(int value) {
        if (value < 0 || value > TransferPreferences.MAX_CONNECTIONS) throw new IllegalArgumentException("Invalid connection count");
        directConnections = value;
    }

    public void configureTransferLimits(long directTotal, long mediaPerTask) {
        bandwidth.setBytesPerSecond(directTotal);
        mediaRate = mediaPerTask;
    }

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

        if (!workerRows.add(row)) return;
        stopReasons.remove(row);

        if ("Direct".equalsIgnoreCase(row.mode)) {
            row.setState(DownloadRow.State.DOWNLOADING);
            row.status.set("Checking server");
            startDirect(row, resume);
            return;
        }

        // UI immediately: preparing (indeterminate)
        Platform.runLater(() -> {
            row.setState(DownloadRow.State.PENDING);
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

        Thread downloadThread = new Thread(() -> {
            Process p = null;
            com.grabx.app.grabx.util.MediaInfoCache.Input mediaInput = null;
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
                            "^(?:gx:|download:gx:)\\s*([0-9.]+|NA)%?\\|\\s*([^|]*)\\|\\s*([^|]*)\\|\\s*([^|]*)\\|\\s*([^|]*)\\|\\s*([^|]*)\\|\\s*([^|]*)\\|\\s*([^|]*)\\|\\s*([^|]*)\\|\\s*([^|]*)$"
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
                cmd.addAll(com.grabx.app.grabx.util.YtDlpOptions.extractionArguments());

                cmd.add("--newline");
                cmd.add("--no-warnings");
                cmd.add("--no-playlist");

                // allow resume / pause-resume
                cmd.add("--continue");

                // Use the same extractor defaults as the format-analysis step.

                // Keep downloaded media, but refresh audio artwork after interrupted/disk-full attempts.
                cmd.add(com.grabx.app.grabx.util.YtDlpOptions.overwriteArgument(
                        audioOnly && supportsAudioThumbnailEmbedding.test(resolvedAudioFormat)));

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
                            selector = com.grabx.app.grabx.util.VideoQualityUtils.formatSelectorForHeight(requestedHeight);
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

                long probeStartMs = System.currentTimeMillis();
                var outputPlan = com.grabx.app.grabx.util.DownloadRuntimeUtils.planOutput(
                        outDir, row.outputFile == null ? null : row.outputFile.get(), resume, baseTpl,
                        () -> probeOutputFilename.probe(yt, url, selector, outDir, baseTpl));
                String outTpl = outputPlan.template();
                LOG.fine(() -> "Output planning took " + (System.currentTimeMillis() - probeStartMs) + " ms");

                // Persist the chosen source path before yt-dlp starts. If the app is
                // closed before the first Destination line, the next launch can still
                // reuse the exact stem and continue its .part file.
                java.nio.file.Path plannedOutput = outputPlan.plannedOutput();
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


                MediaTransferProgress mediaProgress = new MediaTransferProgress();
                cmd.addAll(java.util.List.of("--print", MediaTransferProgress.SELECTION_TEMPLATE,
                        "--no-simulate", "--no-quiet", "--progress"));

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
                                + "|%(info.format_id)s|%(progress.status)s|%(progress.speed)s"
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
                    cmd.addAll(java.util.List.of("--merge-output-format", "mp4"));
                    cmd.addAll(com.grabx.app.grabx.util.YtDlpOptions.videoFormatArguments());
                }


                mediaInput = com.grabx.app.grabx.util.MediaInfoCache.SHARED.openInput(url);
                cmd.addAll(mediaInput.arguments());
                LOG.fine("Reusing analyzed media information: " + mediaInput.cached());

                Path ffmpeg = com.grabx.app.grabx.util.FfmpegManager.ensureAvailable();
                if (ffmpeg != null) {
                    cmd.add("--ffmpeg-location");
                    cmd.add(ffmpeg.toAbsolutePath().toString());
                    LOG.info(() -> "Using FFmpeg at " + ffmpeg);
                } else {
                    LOG.warning("Managed FFmpeg unavailable; yt-dlp will try the system PATH");
                }

                int attemptCode;
                String attemptReason;
                while (true) {
                    ProcessBuilder pb = new ProcessBuilder(TransferPreferences.mediaCommand(cmd, mediaRate));
                    pb.redirectErrorStream(true);
                    pb.environment().putIfAbsent("PYTHONIOENCODING", "utf-8");

                    long processStartMs = System.currentTimeMillis();
                    if (stopReasons.get(row) != null) throw new InterruptedException("Preparation stopped");
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

                            if (s.startsWith("gxmeta:")) {
                                String metadata = s.substring("gxmeta:".length());
                                int separator = metadata.indexOf('|');
                                double duration = 0;
                                if (separator >= 0) {
                                    try { duration = Double.parseDouble(metadata.substring(0, separator)); }
                                    catch (NumberFormatException ignored) {}
                                    metadata = metadata.substring(separator + 1);
                                }
                                mediaProgress.select(ExactMediaSize.resolveStreams(metadata), duration);
                                if (mediaProgress.combined()) {
                                    var selection = mediaProgress.snapshot();
                                    Platform.runLater(() -> {
                                        row.totalBytes.set(selection.total());
                                        row.size.set(selection.sizeText());
                                    });
                                }
                                continue;
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

                                final boolean combined = mediaProgress.combined();
                                if (!combined) lastProgressMap.remove(row);
                                Platform.runLater(() -> {
                                    try {
                                        if (!combined) {
                                            row.downloadedBytes.set(0);
                                            row.totalBytes.set(-1);
                                            row.size.set("");
                                            row.progress.set(0);
                                        }
                                        row.speed.set("");
                                        row.eta.set("");
                                        if (row.state.get() != DownloadRow.State.DOWNLOADING)
                                            row.setState(DownloadRow.State.DOWNLOADING);
                                        row.status.set(phaseLabel);
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

                                    final java.nio.file.Path finalOut2 = com.grabx.app.grabx.util.DownloadRuntimeUtils
                                            .mediaOutputPath(finalOut);
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
                                long exactTotal = total;


                                final boolean combined = mediaProgress.combined();
                                MediaTransferProgress.Snapshot combinedProgress = null;
                                String phase = null;
                                if (combined) {
                                    String formatId = m.group(8).trim();
                                    combinedProgress = mediaProgress.update(formatId, downloaded, exactTotal, total,
                                            "finished".equals(m.group(9).trim()));
                                    downloaded = combinedProgress.downloaded();
                                    total = combinedProgress.total();
                                    pct = combinedProgress.fraction();
                                    phase = mediaProgress.phase(formatId);
                                }
                                final String sizeText = combinedProgress != null ? combinedProgress.sizeText()
                                        : com.grabx.app.grabx.util.DownloadRuntimeUtils.formatTransferSize(downloaded, total);
                                final String transferPhase = phase;
                                final String transferEta = combinedProgress != null ? combinedProgress.eta(m.group(10)) : et;

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

                                    if (transferPhase != null) row.status.set(transferPhase);
                                    if (combined && fpct < 0) row.progress.set(-1);
                                    else applyProgressMonotonic.apply(row, fpct);

                                    if (spd != null && !spd.isBlank() && !"NA".equalsIgnoreCase(spd))
                                        row.speed.set(normalizeSpeedUnit.apply(spd));

                                    row.eta.set(transferEta != null && !"NA".equalsIgnoreCase(transferEta)
                                            ? transferEta : "");
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

                    attemptCode = p.waitFor();
                    attemptReason = stopReasons.get(row);
                    var retry = com.grabx.app.grabx.util.MediaInfoCache.SHARED
                            .retryCommand(cmd, mediaInput, attemptCode, attemptReason);
                    if (retry == null) break;
                    cmd.clear();
                    cmd.addAll(retry);
                    lastError[0] = null;
                    Platform.runLater(() -> {
                        if (stopReasons.get(row) == null) row.status.set("Refreshing stream links...");
                    });
                }
                if (attemptCode == 0 && !audioOnly && attemptReason == null) {
                    Path compatible = com.grabx.app.grabx.util.MacVideoCompatibility.prepare(
                            detectedOutput.get(), ffmpeg,
                            process -> {
                                activeProcesses.put(row, process);
                                if (stopReasons.get(row) != null) killProcessTree.accept(process);
                            }, () -> stopReasons.get(row) != null,
                            () -> Platform.runLater(() -> {
                                row.status.set("Preparing video for macOS...");
                                row.progress.set(-1);
                                row.speed.set("");
                                row.eta.set("");
                            }));
                    if (compatible != null) {
                        detectedOutput.set(compatible);
                        Platform.runLater(() -> row.outputFile.set(compatible));
                    }
                }
                final int code = attemptCode;
                final String reason = stopReasons.get(row);

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
                                row.downloadedBytes.set(sz);
                                row.totalBytes.set(sz);
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
            } finally {
                if (mediaInput != null) mediaInput.close();
                releaseWorker(row);
            }
        }, "yt-dlp-download");
        downloadThread.setDaemon(true);
        downloadThread.start();

    }

    private void startDirect(DownloadRow row, boolean resume) {
        DirectTransferProcess transfer = new DirectTransferProcess();
        activeProcesses.put(row, transfer);
        Thread worker = new Thread(() -> {
            Path output = null;
            boolean reserved = false;
            try {
                Path directory = Path.of(row.folder).toAbsolutePath().normalize();
                Files.createDirectories(directory);
                boolean continuing = resume || hasDirectPartial(row);
                synchronized (DIRECT_OUTPUT_LOCK) {
                    output = directOutputPath(row, directory, continuing);
                    if (!DIRECT_OUTPUTS.add(output)) throw new DirectRemoteProbe.NeedsAction("Another task is using this partial file");
                    reserved = true;
                }
                DirectPartialFiles.hideLegacyParts(output);
                boolean durable = DirectDownloadState.load(row, output);
                if (!durable && DirectDownloadState.hasSegments(output)) {
                    row.resumeTotal = row.totalBytes.get();
                    if (row.resumeTotal <= 0) throw new DirectRemoteProbe.NeedsAction("Original segment layout is unavailable");
                    row.resumeSegments = directSegmentCount(row.resumeTotal);
                }
                final Path chosenOutput = output;
                final long initialSaved = DirectDownloadState.downloaded(output);
                directUiAndWait(() -> {
                    row.outputFile.set(chosenOutput);
                    if (row.resumeTotal >= 0) row.totalBytes.set(row.resumeTotal);
                    row.downloadedBytes.set(initialSaved);
                    row.progress.set(row.totalBytes.get() > 0 ? (double) initialSaved / row.totalBytes.get() : 0);
                    row.size.set(com.grabx.app.grabx.util.DownloadRuntimeUtils.formatTransferSize(initialSaved, row.totalBytes.get()));
                    saveHistory();
                });
                int attempt = 0;
                boolean refreshed = false;
                while (true) {
                    HttpURLConnection connection = null;
                    try {
                        transfer.awaitRunning();
                        if (stopReasons.get(row) != null) throw new InterruptedException("Transfer stopped");
                        directStatus(row, attempt == 0 ? "Checking server" : "Reconnecting • checking download URL");
                        var remote = DirectRemoteProbe.probe(row.url, row.getReferer(), row.requestUserAgent, c -> transfer.attach(c::disconnect, null));
                        row.resumeSupport = remote.support();
                        long saved = DirectDownloadState.downloaded(output);
                        // Without a saved validator, default to a full-prefix comparison in
                        // the payload response. The opt-in sampled check is less comprehensive.
                        boolean verifyPrefix = saved > 0 && durable && row.resumeSegments == 0
                                && DirectRemoteProbe.validator(row) == null;
                        if (!continuing && !durable && saved == 0) {
                            String name = selectDirectFilename(remote.disposition(), remote.contentType(), output.getFileName().toString(), row.url);
                            if (!name.equals(output.getFileName().toString())) {
                                synchronized (DIRECT_OUTPUT_LOCK) {
                                    Path selected = uniqueDirectOutput(directory, name);
                                    DIRECT_OUTPUTS.add(selected);
                                    DIRECT_OUTPUTS.remove(output);
                                    output = selected;
                                }
                            }
                            final Path selected = output;
                            directUiAndWait(() -> {
                                row.outputFile.set(selected); row.title.set(selected.getFileName().toString());
                                row.titleLocked.set(true); saveHistory();
                            });
                        }
                        if (saved > 0) {
                            directStatus(row, "Verifying remote file");
                            if (!durable) {
                                // Legacy parts have no trustworthy saved identity. Compare them once before adopting metadata.
                                var verified = DownloadLinkVerifier.verify(row.url, row.getReferer(), output,
                                        row.resumeTotal > 0 ? row.resumeTotal : row.totalBytes.get(),
                                        row.resumeSegments, row.requestUserAgent);
                                row.resumeEtag = verified.etag();
                                row.resumeTotal = verified.total();
                            }
                            if (verifyPrefix) {
                                if (row.resumeTotal >= 0 && remote.total() >= 0 && row.resumeTotal != remote.total())
                                    throw new DirectRemoteProbe.NeedsAction("Remote file size changed");
                            } else {
                                DirectRemoteProbe.verify(row, remote);
                            }
                        } else if (!durable) {
                            row.resumeEtag = remote.etag();
                            row.resumeLastModified = remote.modified();
                            row.resumeTotal = remote.total();
                            // Parallel transfers require a stable validator and a successful actual range probe.
                            row.resumeSegments = remote.total() >= MIN_SEGMENTED_BYTES
                                    && "Yes".equals(remote.support()) && DirectRemoteProbe.strong(remote.etag())
                                    ? directSegmentCount(remote.total(), directConnections) : 0;
                        } else {
                            // Layout and identity cannot change under previously created segment files.
                            if (row.resumeSegments > 0) DirectRemoteProbe.verify(row, remote);
                            else if (saved == 0) {
                                row.resumeEtag = remote.etag(); row.resumeLastModified = remote.modified();
                                row.resumeTotal = remote.total();
                            }
                        }
                        row.finalUrl = remote.finalUrl();
                        row.resumeSupport = remote.support();
                        final long total = row.resumeTotal;
                        DirectDownloadState.save(row, output); // Must succeed before any payload is written.
                        durable = true;
                        directUiAndWait(() -> {
                            if (row.getState() != DownloadRow.State.PAUSED) row.setState(DownloadRow.State.DOWNLOADING);
                            row.totalBytes.set(total);
                            row.downloadedBytes.set(saved);
                            row.progress.set(total > 0 ? (double) saved / total : -1);
                            row.size.set(com.grabx.app.grabx.util.DownloadRuntimeUtils.formatTransferSize(saved, total));
                            if (row.getState() != DownloadRow.State.PAUSED)
                                row.status.set((saved > 0 ? "Resuming" : "Downloading") + " • Resume support: " + row.resumeSupport);
                            saveHistory();
                        });
                        transfer.awaitRunning();
                        if (row.resumeSegments > 0) {
                            long size = downloadSegmented(row, output, total, row.resumeSegments, transfer);
                            if (stopReasons.get(row) != null) throw new InterruptedException("Transfer stopped");
                            completeDirect(row, output, size);
                            break;
                        }
                        Path partial = DirectPartialFiles.part(output, -1);
                        long existing = Files.isRegularFile(partial) ? Files.size(partial) : 0;
                        if (total >= 0 && existing > total) throw new DirectRemoteProbe.NeedsAction("Saved data exceeds remote size");
                        if (verifyPrefix && quickResumeChecks && "Yes".equals(row.resumeSupport) && total >= existing) {
                            directStatus(row, "Quick check before resume");
                            boolean checked = QuickResumeVerifier.verify(partial, existing, total, (start, end) -> {
                                HttpURLConnection sample = openRangeConnection(row.finalUrl == null ? row.url : row.finalUrl,
                                        start, end, row.getReferer());
                                if (row.requestUserAgent != null && !row.requestUserAgent.isBlank())
                                    sample.setRequestProperty("User-Agent", row.requestUserAgent);
                                transfer.attach(sample::disconnect, null);
                                return sample;
                            }, () -> {
                                transfer.awaitRunning();
                                if (stopReasons.get(row) != null) throw new InterruptedException("Transfer stopped");
                            }, input -> bandwidth.wrap(input, () -> {
                                transfer.awaitRunning();
                                if (stopReasons.get(row) != null) throw new InterruptedException("Transfer stopped");
                            }));
                            if (checked) {
                                verifyPrefix = false;
                                LOG.info("Direct quick resume check completed: savedBytes=" + existing);
                            }
                            // A server ignoring sample ranges falls back to the full, same-response check.
                        }
                        // A verified complete local prefix needs no further range request (including the 416 boundary).
                        if (!verifyPrefix && existing == total && (existing > 0 || total == 0)) {
                            if (!Files.exists(partial)) Files.createFile(partial);
                            Files.move(partial, output); // Never overwrite an unrelated completed file.
                            completeDirect(row, output, total);
                            break;
                        }
                        boolean requestRange = !verifyPrefix && "Yes".equals(row.resumeSupport) && total >= 0;
                        connection = openRangeConnection(row.finalUrl == null ? row.url : row.finalUrl,
                                requestRange ? existing : -1, Math.max(existing, total - 1), row.getReferer());
                        if (row.requestUserAgent != null && !row.requestUserAgent.isBlank()) connection.setRequestProperty("User-Agent", row.requestUserAgent);
                        DirectRemoteProbe.conditional(connection, row, requestRange);
                        HttpURLConnection opened = connection;
                        transfer.attach(opened::disconnect, null);
                        DirectRemoteProbe.validateIdentity(connection, row);
                        int status = connection.getResponseCode();
                        if (verifyPrefix && status != 200)
                            throw new DirectRemoteProbe.NeedsAction("Expected a full response to verify saved data");
                        if (!verifyPrefix && existing > 0 && status != 206) {
                            row.resumeSupport = "No";
                            DirectDownloadState.save(row, output);
                            throw new DirectRemoteProbe.NeedsAction("Server does not support resume or the file changed (HTTP " + status + ")");
                        }
                        long expected = connection.getContentLengthLong();
                        if (status == 206) {
                            long last = SegmentResponse.validate(connection.getHeaderField("Content-Range"), existing, total - 1, total);
                            long length = last - existing + 1;
                            if (expected >= 0 && expected != length) throw new DirectRemoteProbe.NeedsAction("Invalid range response length");
                            expected = length;
                        } else if (total >= 0 && expected >= 0 && expected != total) {
                            throw new DirectRemoteProbe.NeedsAction("Remote file size changed");
                        }
                        if (expected < 0 && total >= 0) expected = verifyPrefix ? total : total - existing;
                        if (verifyPrefix && expected >= 0 && expected < existing)
                            throw new DirectRemoteProbe.NeedsAction("Remote file is shorter than saved data");
                        long downloaded = existing;
                        long received = 0;
                        long updated = System.nanoTime();
                        TransferSpeedMeter meter = new TransferSpeedMeter(downloaded, updated);
                        try (InputStream in = bandwidth.wrap(connection.getInputStream(), () -> {
                            transfer.awaitRunning();
                            if (stopReasons.get(row) != null) throw new InterruptedException("Transfer stopped");
                        })) {
                            if (verifyPrefix) {
                                long verificationStarted = System.nanoTime();
                                LOG.info("Direct resume verification started: savedBytes=" + existing);
                                updateVerificationProgress(row, 0, existing, 0);
                                TransferSpeedMeter verificationSpeed = new TransferSpeedMeter(0, verificationStarted);
                                long[] lastUpdate = {verificationStarted};
                                long[] resumeRevision = {transfer.resumeRevision};
                                DirectResumeVerifier.verify(in, partial, existing, () -> {
                                    transfer.awaitRunning();
                                    if (stopReasons.get(row) != null) throw new InterruptedException("Transfer stopped");
                                }, checked -> {
                                    long now = System.nanoTime();
                                    if (resumeRevision[0] != transfer.resumeRevision) {
                                        verificationSpeed.reset(checked, now);
                                        resumeRevision[0] = transfer.resumeRevision;
                                        lastUpdate[0] = 0;
                                    }
                                    if (now - lastUpdate[0] >= 400_000_000L) {
                                        updateVerificationProgress(row, checked, existing, verificationSpeed.sample(checked, now));
                                        lastUpdate[0] = now;
                                    }
                                });
                                if (expected >= 0) expected -= existing;
                                LOG.info("Direct resume verification completed: verifiedBytes=" + existing
                                        + ", elapsedMs=" + (System.nanoTime() - verificationStarted) / 1_000_000);
                                directUiAndWait(() -> {
                                    if (row.getState() != DownloadRow.State.DOWNLOADING) return;
                                    row.status.set("Resuming • saved data verified");
                                    row.speed.set(""); row.eta.set("");
                                });
                                updated = System.nanoTime();
                                meter = new TransferSpeedMeter(downloaded, updated);
                            }
                            // Open for append only after verification; keep consuming the same
                            // response, so a later unvalidated request cannot switch file versions.
                            try (OutputStream out = new BufferedOutputStream(
                                    Files.newOutputStream(partial, StandardOpenOption.CREATE, StandardOpenOption.APPEND), 128 * 1024)) {
                                byte[] buffer = new byte[128 * 1024];
                                while (expected < 0 || received < expected) {
                                    transfer.awaitRunning();
                                    if (stopReasons.get(row) != null) throw new InterruptedException("Transfer stopped");
                                    int count = in.read(buffer, 0, expected < 0 ? buffer.length : (int) Math.min(buffer.length, expected - received));
                                    if (count < 0) break;
                                    transfer.awaitRunning();
                                    out.write(buffer, 0, count);
                                    downloaded += count; received += count;
                                    long now = System.nanoTime();
                                    if (now - updated >= 400_000_000L) {
                                        updateDirectProgress(row, downloaded, total, meter.sample(downloaded, now));
                                        updated = now;
                                    }
                                }
                            }
                        }
                        if (stopReasons.get(row) != null) throw new InterruptedException("Transfer stopped");
                        if ((expected >= 0 && received < expected) || (total >= 0 && downloaded < total))
                            throw new java.io.EOFException("Connection ended before the file completed");
                        if (total >= 0 && downloaded != total) throw new DirectRemoteProbe.NeedsAction("Unexpected file length");
                        Files.move(partial, output);
                        completeDirect(row, output, downloaded);
                        break;
                    } catch (Exception error) {
                        Throwable cause = rootCause(error);
                        if (stopReasons.get(row) != null || cause instanceof InterruptedException) throw error;
                        if (cause instanceof DirectRemoteProbe.HttpFailure http
                                && (http.status == 401 || http.status == 403 || http.status == 404 || http.status == 410)
                                && !refreshed && linkResolvers.hasResolver(row.originalUrl)) {
                            refreshed = true;
                            directStatus(row, "Refreshing download link");
                            try { linkResolvers.refresh(row, output); }
                            catch (Exception refreshError) {
                                throw new DirectRemoteProbe.NeedsAction("Download link could not be refreshed or verified; update it manually");
                            }
                            durable = true;
                            continue;
                        }
                        if (!(isNetworkInterruption(cause) || cause instanceof SegmentServerBusy)) throw error;
                        long saved = DirectDownloadState.downloaded(output);
                        int status = cause instanceof SegmentServerBusy busy ? busy.status : 0;
                        long requested = cause instanceof SegmentServerBusy busy ? busy.retryAfterMillis : 0;
                        long delay = Math.max(Math.min(60_000, (status == 429 ? 30_000L : 2_000L) << Math.min(attempt++, 5)), requested);
                        LOG.info("Direct recovery: " + cause.getClass().getSimpleName() + ", HTTP=" + status
                                + ", savedBytes=" + saved + ", retryMs=" + delay);
                        final long savedBytes = saved;
                        directUiAndWait(() -> {
                            row.downloadedBytes.set(savedBytes);
                            if (row.resumeTotal > 0) row.progress.set((double) savedBytes / row.resumeTotal);
                            row.size.set(com.grabx.app.grabx.util.DownloadRuntimeUtils.formatTransferSize(savedBytes, row.resumeTotal));
                            row.speed.set(""); row.eta.set(""); saveHistory();
                        });
                        // Keep the same process registered during backoff: pause/resume/cancel cannot race a detached retry.
                        if (connection != null) { connection.disconnect(); connection = null; }
                        transfer.closeConnections();
                        long remaining = delay;
                        while (remaining > 0) {
                            transfer.awaitRunning();
                            if (stopReasons.get(row) != null) throw new InterruptedException("Transfer stopped");
                            directStatus(row, (status == 0 ? "Waiting for network" : "Retrying • server " + status)
                                    + " • retry in " + ((remaining + 999) / 1000) + "s");
                            long slice = Math.min(1000, remaining);
                            Thread.sleep(slice); remaining -= slice;
                        }
                    } finally {
                        if (connection != null) connection.disconnect();
                        transfer.closeConnections();
                    }
                }
                transfer.complete(0);
            } catch (Exception error) {
                if (output != null) {
                    try {
                        long saved = DirectDownloadState.downloaded(output);
                        Platform.runLater(() -> {
                            row.downloadedBytes.set(saved);
                            if (row.resumeTotal > 0) row.progress.set((double) saved / row.resumeTotal);
                            row.size.set(com.grabx.app.grabx.util.DownloadRuntimeUtils.formatTransferSize(saved, row.resumeTotal));
                        });
                    } catch (java.io.IOException ignored) { /* Preserve the last known checkpoint. */ }
                }
                String reason = stopReasons.get(row);
                if (reason != null || error instanceof InterruptedException) {
                    finishStoppedDirect(row, reason == null ? "PAUSE" : reason, null);
                } else {
                    Throwable cause = rootCause(error);
                    LOG.warning("Direct download stopped: " + cause.getClass().getSimpleName()
                            + (cause instanceof DirectRemoteProbe.HttpFailure http ? ", HTTP=" + http.status : ""));
                    Platform.runLater(() -> {
                        // A rejected link/unsafe resume needs user action, not an ordinary pause.
                        // FAILED appears in Cancelled without expiring the retained partial files.
                        row.setState(DownloadRow.State.FAILED);
                        row.status.set(shortError(cause.getMessage()));
                        row.speed.set(""); row.eta.set(""); saveHistory(); refreshFilters();
                    });
                }
                transfer.complete(1);
            } finally {
                transfer.closeConnections();
                if (reserved) DIRECT_OUTPUTS.remove(output);
                activeProcesses.remove(row, transfer);
                releaseWorker(row);
            }
        }, "http-download");
        transfer.attach(null, worker);
        worker.setDaemon(true);
        worker.start();
    }

    private final DownloadLinkResolvers linkResolvers = new DownloadLinkResolvers();
    public DownloadLinkResolvers linkResolvers() { return linkResolvers; }

    private static void directStatus(DownloadRow row, String text) {
        Platform.runLater(() -> {
            if (row.getState() != DownloadRow.State.PAUSED && row.getState() != DownloadRow.State.CANCELLED) {
                row.status.set(text);
            }
        });
    }

    private static void directUiAndWait(Runnable action) throws InterruptedException {
        if (Platform.isFxApplicationThread()) { action.run(); return; }
        var latch = new java.util.concurrent.CountDownLatch(1);
        Platform.runLater(() -> { try { action.run(); } finally { latch.countDown(); } });
        latch.await();
    }

    private long downloadSegmented(DownloadRow row, Path output, long total, int segmentCount,
                                   DirectTransferProcess transfer) throws Exception {
        boolean automatic = directConnections == TransferPreferences.AUTO_CONNECTIONS;
        int connections = Math.min(automatic ? TransferPreferences.MAX_CONNECTIONS : directConnections, segmentCount);
        LOG.info("Direct parallel transfer: requestedConnections=" + directConnections
                + ", savedSegments=" + segmentCount + ", workerLimit=" + connections);
        for (int attempt = 0; ; attempt++) {
            try {
                return downloadSegmentedAttempt(row, output, total, segmentCount, connections, automatic, transfer);
            } catch (Exception error) {
                Throwable cause = rootCause(error);
                if (isInterruptedSegment(cause)) cause = new SegmentServerBusy(0, null);
                if (!(cause instanceof SegmentServerBusy busy) || attempt >= 5 || stopReasons.get(row) != null) throw error;
                connections = busy.status == 429 ? 1 : Math.max(1, connections / 2);
                long delay = segmentRetryDelay(busy.status, attempt, busy.retryAfterMillis);
                final int nextConnections = connections;
                Platform.runLater(() -> {
                    if (row.getState() == DownloadRow.State.DOWNLOADING) {
                        row.status.set("Server busy • retrying with " + nextConnections + " connection(s)");
                        row.speed.set("");
                        row.eta.set("");
                    }
                });
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(delay);
                long lastSecond = -1;
                while (System.nanoTime() < deadline) {
                    long seconds = Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(deadline - System.nanoTime()) + 1);
                    if (seconds != lastSecond) {
                        lastSecond = seconds;
                        Platform.runLater(() -> {
                            if (row.getState() == DownloadRow.State.DOWNLOADING)
                                row.status.set((busy.status == 0 ? "Connection interrupted" : "Server " + busy.status) + " • retry in " + seconds + "s • " + nextConnections + " connection(s)");
                        });
                    }
                    transfer.awaitRunning();
                    if (stopReasons.get(row) != null) throw error;
                    Thread.sleep(100);
                }
            }
        }
    }

    private long downloadSegmentedAttempt(DownloadRow row, Path output, long total, int segmentCount,
                                         int connections, boolean automatic, DirectTransferProcess transfer) throws Exception {
        var roundConnections = new java.util.concurrent.ConcurrentLinkedQueue<HttpURLConnection>();
        AdaptiveConnections adaptive = automatic ? new AdaptiveConnections(connections) : null;
        int initialConnections = automatic ? adaptive.connections() : connections;
        class Slots extends java.util.concurrent.Semaphore {
            private int limit = initialConnections;
            Slots() { super(initialConnections); }
            void resize(int value) {
                int change = value - limit;
                if (change > 0) release(change); else if (change < 0) reducePermits(-change);
                limit = value;
            }
        }
        Slots slots = new Slots();
        var active = new java.util.concurrent.atomic.AtomicInteger();
        var pool = new java.util.concurrent.ThreadPoolExecutor(initialConnections, connections,
                0L, java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.LinkedBlockingQueue<>(), runnable -> {
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
                boolean acquired = false;
                try {
                    while (!slots.tryAcquire(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        if (stopReasons.get(row) != null || failure.get() != null) throw new InterruptedException("Transfer stopped");
                        transfer.awaitRunning();
                    }
                    acquired = true;
                    active.incrementAndGet();
                    SegmentTransfer.download(part, start, end, total, (offset, last) -> {
                        HttpURLConnection opened = openRangeConnection(row.finalUrl == null ? row.url : row.finalUrl, offset, last, row.getReferer());
                        if (row.requestUserAgent != null && !row.requestUserAgent.isBlank()) opened.setRequestProperty("User-Agent", row.requestUserAgent);
                        DirectRemoteProbe.conditional(opened, row, true);
                        roundConnections.add(opened);
                        transfer.attach(opened::disconnect, Thread.currentThread());
                        try { DirectRemoteProbe.validateIdentity(opened, row); }
                        catch (Exception error) { opened.disconnect(); throw error; }
                        return opened;
                    }, () -> {
                        transfer.awaitRunning();
                        if (stopReasons.get(row) != null || failure.get() != null)
                            throw new InterruptedException("Transfer stopped");
                    }, downloaded::addAndGet, roundConnections::remove, input -> bandwidth.wrap(input, () -> {
                        transfer.awaitRunning();
                        if (stopReasons.get(row) != null || failure.get() != null)
                            throw new InterruptedException("Transfer stopped");
                    }));
                } catch (Throwable throwable) {
                    if (stopReasons.get(row) == null) failure.compareAndSet(null, throwable);
                } finally {
                    if (acquired) { active.decrementAndGet(); slots.release(); }
                    finished.countDown();
                }
            });
        }

        TransferSpeedMeter speedMeter = new TransferSpeedMeter(downloaded.get(), System.nanoTime());
        try {
            while (!finished.await(400, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                Throwable error = failure.get();
                if (error != null) {
                    roundConnections.forEach(HttpURLConnection::disconnect);
                    // Join this round before reopening any part for append.
                    continue;
                }
                long current = downloaded.get();
                long now = System.nanoTime();
                if (transfer.pauseGate.isPaused()) {
                    speedMeter.reset(current, now);
                    if (adaptive != null) adaptive.reset(current, now);
                    continue;
                }
                long speed = speedMeter.sample(current, now);
                if (adaptive != null) {
                    int target = adaptive.sample(current, now, bandwidth.bytesPerSecond(),
                            finished.getCount() > pool.getCorePoolSize() && active.get() <= pool.getCorePoolSize());
                    if (target != pool.getCorePoolSize()) {
                        LOG.info("Automatic direct connections: " + pool.getCorePoolSize() + " -> " + target);
                        slots.resize(target);
                        pool.setCorePoolSize(target);
                    }
                }
                // Retiring workers finish their current piece before the smaller cap takes effect.
                int workerCap = Math.max(pool.getCorePoolSize(), active.get());
                updateDirectProgress(row, current, total, speed, workerCap, automatic);
            }
            Throwable error = failure.get();
            if (error != null) throw new java.util.concurrent.ExecutionException(error);
            if (stopReasons.get(row) != null) return downloaded.get();

            Path combined = DirectPartialFiles.part(output, -1);
            try (OutputStream merged = Files.newOutputStream(combined, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (int segment = 0; segment < segmentCount; segment++) {
                    Files.copy(segmentPart(output, segment), merged);
                }
            }
            if (Files.size(combined) != total) throw new java.io.EOFException("Combined file is incomplete");
            Files.move(combined, output);
            cleanupSegmentParts(output, segmentCount);
            return Files.size(output);
        } finally {
            roundConnections.forEach(HttpURLConnection::disconnect);
            pool.shutdownNow();
            // No next attempt may open parts until every writer from this round has stopped.
            boolean interrupted = Thread.interrupted();
            while (!pool.isTerminated()) {
                try { pool.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException e) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    static final class SegmentServerBusy extends java.io.IOException {
        final long retryAfterMillis;
        final int status;
        SegmentServerBusy(int status, String retryAfter) {
            super("Server busy (HTTP " + status + "); downloaded parts kept");
            this.status = status;
            retryAfterMillis = retryDelayMillis(retryAfter);
        }
    }

    static boolean isNetworkInterruption(Throwable error) {
        return error instanceof java.net.SocketException
                || error instanceof java.net.SocketTimeoutException
                || error instanceof java.net.UnknownHostException
                || error instanceof java.io.EOFException;
    }

    static boolean isInterruptedSegment(Throwable error) {
        return error instanceof SocketTimeoutException || error instanceof java.io.EOFException;
    }

    static boolean isTemporaryHttpFailure(int status) {
        return status == 408 || status == 429 || (status >= 500 && status <= 599);
    }

    static long segmentRetryDelay(int status, int attempt, long retryAfterMillis) {
        long base = status == 429 ? 30_000L : 2_000L;
        return Math.max(base << Math.min(Math.max(0, attempt), 4), retryAfterMillis);
    }

    static long retryDelayMillis(String value) {
        if (value == null) return 0;
        try { return Math.min(300_000L, Math.min(300L, Math.max(0, Long.parseLong(value.trim()))) * 1000L); }
        catch (NumberFormatException ignored) {
            try {
                long delay = java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant().toEpochMilli() - System.currentTimeMillis();
                return Math.min(300_000L, Math.max(0, delay));
            } catch (java.time.DateTimeException invalid) { return 0; }
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

    static HttpURLConnection openRangeConnection(String url, long start, long end, String referer) throws Exception {
        HttpURLConnection connection = com.grabx.app.grabx.util.VerifiedConnections.open(new URL(url));
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(20_000);
        // Payload servers can pause between bursts. A short idle timeout forces
        // validator-less downloads to re-read their entire saved prefix on every retry.
        // Keep this bounded; cancellation still disconnects the registered connection.
        connection.setReadTimeout(120_000);
        connection.setRequestProperty("User-Agent", DirectRemoteProbe.USER_AGENT);
        connection.setRequestProperty("Accept", "*/*");
                connection.setRequestProperty("Accept-Encoding", "identity");
        com.grabx.app.grabx.util.RequestReferer.apply(connection, referer);
        if (start >= 0) connection.setRequestProperty("Range", "bytes=" + start + "-" + end);
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

    public static int directSegmentCountForLegacy(long total) { return total > 0 ? directSegmentCount(total) : 0; }

    static int directSegmentCount(long total, int connections) {
        if (connections == TransferPreferences.AUTO_CONNECTIONS) connections = TransferPreferences.MAX_CONNECTIONS;
        // Keep individual pieces useful on small files, but allow large files to use all requested workers.
        return (int) Math.max(1, Math.min(connections, total / (256 * 1024)));
    }

    static int directSegmentCount(long total) {
        long desired = Math.max(2, (total + TARGET_SEGMENT_BYTES - 1) / TARGET_SEGMENT_BYTES);
        return (int) Math.min(MAX_DIRECT_SEGMENTS, desired);
    }

    private static Path segmentPart(Path output, int index) {
        return DirectPartialFiles.part(output, index);
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

    private void updateVerificationProgress(DownloadRow row, long checked, long saved, long bytesPerSecond) {
        String speedText = bytesPerSecond > 0 ? formatBytesDecimal.apply(bytesPerSecond) + "/s" : "";
        long remaining = Math.max(0, saved - checked);
        String etaText = bytesPerSecond > 0 ? formatEta((long) Math.ceil((double) remaining / bytesPerSecond)) : "";
        Platform.runLater(() -> {
            if (row.getState() != DownloadRow.State.DOWNLOADING) return;
            row.status.set("Verifying saved data • " + (checked * 100 / saved) + "% • re-reading from server");
            row.speed.set(speedText);
            row.eta.set(etaText); // ETA for verification only; saved-byte progress stays unchanged.
        });
    }

    private void updateDirectProgress(DownloadRow row, long downloaded, long total, long bytesPerSecond) {
        updateDirectProgress(row, downloaded, total, bytesPerSecond, 1);
    }

    private void updateDirectProgress(DownloadRow row, long downloaded, long total, long bytesPerSecond, int connections) {
        updateDirectProgress(row, downloaded, total, bytesPerSecond, connections, false);
    }

    private void updateDirectProgress(DownloadRow row, long downloaded, long total, long bytesPerSecond,
                                      int connections, boolean automatic) {
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
            row.status.set(bytesPerSecond <= 0 ? "Waiting for server • reconnecting if stalled"
                    : (total > 0 ? "Downloading" : "Downloading • total size unknown")
                        + " • Resume support: " + row.resumeSupport + (automatic ? " • Auto" : "") + connectionStatus(connections));
            // Keep unknown-length transfers visibly active until a size is discovered.
            row.progress.set(total > 0 ? Math.min(1, (double) downloaded / total) : -1);
        });
    }

    static String connectionStatus(int maximum) {
        return maximum <= 1 ? " • 1 connection" : " • Up to " + maximum + " connections";
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
            return DirectPartialFiles.hasParts(output);
        } catch (Exception ignored) {
            return false;
        }
    }

    static Path uniqueDirectOutput(Path directory, String value) {
        String filename = safeFilename(value);
        if (filename.isBlank()) filename = "download";
        Path candidate = directory.resolve(filename).normalize();
        if (!candidate.getParent().equals(directory)) candidate = directory.resolve("download");
        if (!directOutputOccupied(candidate)) {
            return candidate;
        }
        String name = candidate.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int index = 2; ; index++) {
            Path alternate = directory.resolve(stem + " (" + index + ")" + extension);
            if (!directOutputOccupied(alternate)) return alternate;
        }
    }

    private static boolean directOutputOccupied(Path output) {
        if (DIRECT_OUTPUTS.contains(output) || Files.exists(output) || Files.exists(DirectDownloadState.path(output))
                || DirectPartialFiles.hasParts(output)) return true;
        String prefix = output.getFileName() + ".grabx.part.";
        try (var entries = Files.newDirectoryStream(output.getParent())) {
            for (Path entry : entries) {
                if (entry.getFileName().toString().startsWith(prefix)) return true;
            }
            return false;
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException("Cannot safely select download filename", error);
        }
    }

    static String selectDirectFilename(String disposition, String type, String suggested, String url) {
        String supplied = contentDispositionFilename(disposition);
        String fromUrl = filenameFromUrl(url);
        for (String candidate : new String[]{supplied, suggested, fromUrl}) {
            if (candidate != null && candidate.matches("(?i).+\\.[a-z0-9]{2,5}")
                    && !candidate.matches("(?i)(download|file|video)( \\(\\d+\\))?\\.[a-z0-9]+"))
                return safeFilename(candidate);
        }
        if (suggested != null && !suggested.isBlank() && !suggested.equals("download")
                && !suggested.matches("(?i)(download|file|video)\\.[a-z0-9]+")) {
            String fallback = extensionForContentType(type);
            int dot = fallback.lastIndexOf('.');
            return safeFilename(suggested) + (dot >= 0 ? fallback.substring(dot) : "");
        }
        return responseFilename(disposition, type);
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
        return text.length() > 1024 ? text.substring(0, 1024) + "…" : text;
    }

    private static final class DirectTransferProcess extends Process implements PausableTransfer {
        private final TransferPauseGate pauseGate = new TransferPauseGate();
        private volatile long resumeRevision;
        @Override public void pauseTransfer() { pauseGate.pause(); }
        @Override public void resumeTransfer() { resumeRevision++; pauseGate.resume(); }
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

        void closeConnections() {
            threads.removeIf(thread -> !thread.isAlive());
            Closeable stream;
            while ((stream = streams.poll()) != null) { try { stream.close(); } catch (Exception ignored) {} }
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
