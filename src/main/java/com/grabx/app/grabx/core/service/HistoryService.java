package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import com.grabx.app.grabx.thumbs.ThumbnailCacheManager;
import javafx.application.Platform;
import javafx.collections.ObservableList;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;


public class HistoryService {

    private static final int DEFAULT_HISTORY_DAYS = 30;

    /** Migrate the previous release's blocked Direct tasks out of ordinary Paused. */
    static void restoreFailureStatus(DownloadRow row, String savedStatus) {
        if (savedStatus == null || savedStatus.isBlank()) return;
        boolean previousDirectFailure = "Direct".equalsIgnoreCase(row.mode)
                && row.getState() == DownloadRow.State.PAUSED
                && (savedStatus.contains(" • saved data ke") || savedStatus.contains("saved data kept")
                    || savedStatus.contains(" • partial files kept") || savedStatus.contains("HTTP 498")
                    || savedStatus.startsWith("Link not updated:")
                    || savedStatus.startsWith("Download link could not be refreshed")
                    || savedStatus.startsWith("Server does not support resume"));
        if (previousDirectFailure) row.setState(DownloadRow.State.FAILED);
        if (row.getState() == DownloadRow.State.FAILED) row.status.set(savedStatus);
    }

    private static final Path DEFAULT_HISTORY_FILE =
            Paths.get(System.getProperty("user.home"), ".grabx", "download-history.tsv");

    private final Path historyFile;
    private final ObservableList<DownloadRow> downloadItems;
    private final AtomicLong downloadOrderSeq;

    private final ScheduledExecutorService uiDelayExec;
    private final Supplier<Integer> historyDaysSupplier;

    private final Runnable reconcileLoadedRowsWithDisk;
    private final Runnable updateMissingSidebarItem;
    private final java.util.function.Consumer<List<DownloadRow>> warmMissingThumbnailsAsync;

    // optional (only if you want service to compute thumb url when needed)
    private final Function<String, String> thumbFromUrl;

    private final AtomicBoolean historySaveScheduled = new AtomicBoolean(false);
    private volatile boolean downloadHistoryLoaded = false;
    private java.util.Set<DownloadRow> interruptedOnShutdown;
    private java.util.function.BooleanSupplier autoResumeEnabled = () -> false;
    private java.util.function.Consumer<DownloadRow> resumeInterrupted = row -> {};

    public void configureStartupResume(java.util.function.BooleanSupplier enabled,
                                       java.util.function.Consumer<DownloadRow> resume) {
        autoResumeEnabled = java.util.Objects.requireNonNull(enabled);
        resumeInterrupted = java.util.Objects.requireNonNull(resume);
    }

    /** Capture intent before shutdown pauses workers; later saves retain that intent. FX thread only. */
    public void prepareForShutdown() {
        if (interruptedOnShutdown != null) return;
        interruptedOnShutdown = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (DownloadRow row : downloadItems) {
            if (isInterruptedState(row.getState())) interruptedOnShutdown.add(row);
        }
    }

    private static boolean isInterruptedState(DownloadRow.State state) {
        return state == DownloadRow.State.DOWNLOADING || state == DownloadRow.State.PENDING
                || state == DownloadRow.State.QUEUED;
    }

    private boolean shouldResumeAfterRestart(DownloadRow row) {
        return isInterruptedState(row.getState()) || (row.getState() == DownloadRow.State.PAUSED
                && interruptedOnShutdown != null && interruptedOnShutdown.contains(row));
    }

    private final java.util.Set<DownloadRow> historyAttachedRows =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /**
     * Attach auto-save listeners to this DownloadRow, if not already attached.
     * - On state change: schedule save (and set completedAt if completed)
     * - On outputFile/title change: schedule save
     */
    public void attachAutoSave(DownloadRow r) {
        if (r == null) return;

        // prevent double-attaching listeners
        try {
            if (!historyAttachedRows.add(r)) return;
        } catch (Exception ignored) {
            // if set fails for any reason, still try to attach once
        }

        // state changes => save (and stamp completedAt when needed)
        try {
            r.stateProperty().addListener((obs, oldV, newV) -> {
                try {
                    if (newV == DownloadRow.State.COMPLETED && r.completedAt <= 0) {
                        r.completedAt = System.currentTimeMillis();
                    }
                } catch (Exception ignored2) {}
                scheduleSave();
            });
        } catch (Exception ignored) {}

        // output file changes => save
        try {
            if (r.outputFile != null) {
                r.outputFile.addListener((obs, o, n) -> { if ("Direct".equalsIgnoreCase(r.mode)) saveNow(); else scheduleSave(); });
            }
        } catch (Exception ignored) {}

        // title changes => save
        try {
            if (r.title != null) {
                r.title.addListener((obs, o, n) -> scheduleSave());
            }
        } catch (Exception ignored) {}
    }

    public void clearHistoryFile() {
        try {
            ensureHistoryDir();
            DirectDownloadState.atomicWrite(historyFile, new byte[0]);
        } catch (Exception ignored) {}
    }

    /** Writes the current FX-thread snapshot immediately during application shutdown. */
    public void saveNow() {
        try {
            ensureHistoryDir();
            List<DownloadRow> snapshot = downloadItems == null
                    ? List.of()
                    : new ArrayList<>(downloadItems);
            if (snapshot.isEmpty()) return;

            List<String> lines = new ArrayList<>();
            for (DownloadRow row : snapshot) {
                if (row == null || row.url == null || row.url.isBlank()) continue;
                String state = "QUEUED";
                try { state = String.valueOf(row.state.get()); } catch (Exception ignored) {}
                String outputPath = "";
                try {
                    Path output = row.outputFile == null ? null : row.outputFile.get();
                    if (output != null) outputPath = output.toAbsolutePath().normalize().toString();
                } catch (Exception ignored) {}
                long updated = row.completedAt > 0 ? row.completedAt : System.currentTimeMillis();
                lines.add(
                        esc(row.url) + "\t" + esc(safeGet(row.title)) + "\t"
                                + esc(row.folder) + "\t" + esc(row.mode) + "\t"
                                + esc(row.quality) + "\t" + esc(state) + "\t"
                                + esc(outputPath) + "\t" + updated + "\t" + esc(row.getReferer()) + "\t" + esc(row.resumeEtag) + "\t" + row.totalBytes.get() + "\t" + esc(row.originalUrl) + "\t" + esc(row.status.get())
                                + "\t" + shouldResumeAfterRestart(row)
                );
            }
            if (!lines.isEmpty()) {
                DirectDownloadState.atomicWrite(historyFile,
                        (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception error) {
            com.grabx.app.grabx.util.AppLog.get(HistoryService.class).warning("Could not persist download history: " + error.getClass().getSimpleName());
        }
    }


    public HistoryService(
            ObservableList<DownloadRow> downloadItems,
            AtomicLong downloadOrderSeq,
            ScheduledExecutorService uiDelayExec,
            Supplier<Integer> historyDaysSupplier,
            Runnable reconcileLoadedRowsWithDisk,
            Runnable updateMissingSidebarItem,
            java.util.function.Consumer<List<DownloadRow>> warmMissingThumbnailsAsync,
            Function<String, String> thumbFromUrl
    ) {
        this(downloadItems, downloadOrderSeq, uiDelayExec, historyDaysSupplier, reconcileLoadedRowsWithDisk,
                updateMissingSidebarItem, warmMissingThumbnailsAsync, thumbFromUrl, DEFAULT_HISTORY_FILE);
    }

    HistoryService(ObservableList<DownloadRow> downloadItems, AtomicLong downloadOrderSeq,
            ScheduledExecutorService uiDelayExec, Supplier<Integer> historyDaysSupplier,
            Runnable reconcileLoadedRowsWithDisk, Runnable updateMissingSidebarItem,
            java.util.function.Consumer<List<DownloadRow>> warmMissingThumbnailsAsync,
            Function<String, String> thumbFromUrl, Path historyFile) {
        this.historyFile = historyFile;
        this.downloadItems = downloadItems;
        this.downloadOrderSeq = downloadOrderSeq;
        this.uiDelayExec = uiDelayExec;
        this.historyDaysSupplier = historyDaysSupplier;
        this.reconcileLoadedRowsWithDisk = reconcileLoadedRowsWithDisk;
        this.updateMissingSidebarItem = updateMissingSidebarItem;
        this.warmMissingThumbnailsAsync = warmMissingThumbnailsAsync;
        this.thumbFromUrl = thumbFromUrl;
    }

    public void loadOnce() {
        if (downloadHistoryLoaded) return;
        downloadHistoryLoaded = true;
        loadDownloadHistoryOnce();
    }

    public void scheduleSave() {
        if (uiDelayExec == null) {
            saveDownloadHistoryAsync();
            return;
        }

        // debounce
        if (!historySaveScheduled.compareAndSet(false, true)) return;

        uiDelayExec.schedule(() -> {
            try {
                saveDownloadHistoryAsync();
            } finally {
                historySaveScheduled.set(false);
            }
        }, 600, TimeUnit.MILLISECONDS);
    }

    private int getHistoryDays() {
        try {
            int v = (historyDaysSupplier == null) ? DEFAULT_HISTORY_DAYS : historyDaysSupplier.get();
            if (v < 1) v = 1;
            if (v > 365) v = 365;
            return v;
        } catch (Exception ignored) {
            return DEFAULT_HISTORY_DAYS;
        }
    }

    private void ensureHistoryDir() {
        try {
            Path dir = historyFile.getParent();
            if (dir != null) Files.createDirectories(dir);
        } catch (Exception ignored) {}
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\t", " ")
                .replace("\n", " ")
                .replace("\r", " ");
    }

    private static String unesc(String s) {
        if (s == null) return "";
        return s.replace("\\\\", "\\");
    }

    private static String safeGet(javafx.beans.property.StringProperty p) {
        try { return (p == null || p.get() == null) ? "" : p.get(); }
        catch (Exception ignored) { return ""; }
    }

    private static String shorten(String url) {
        if (url == null) return "";
        String u = url.trim();
        if (u.length() <= 60) return u;
        return u.substring(0, 57) + "...";
    }

    private static String formatBytesDecimal(long bytes) {
        if (bytes <= 0) return "";
        final double k = 1000.0, m = k * 1000.0, g = m * 1000.0;
        if (bytes >= g) return String.format(Locale.ROOT, "%.2f GB", (bytes / g));
        if (bytes >= m) return String.format(Locale.ROOT, "%.1f MB", (bytes / m));
        if (bytes >= k) return String.format(Locale.ROOT, "%.0f KB", (bytes / k));
        return bytes + " B";
    }

    private void saveDownloadHistoryAsync() {
        // Serialize immutable snapshots and atomic replacements on the FX thread.
        // A late background snapshot must never overwrite newer shutdown state.
        Platform.runLater(this::saveNow);
    }

    private void loadDownloadHistoryOnce() {
        try {
            if (!Files.exists(historyFile)) return;

            int keepDays = getHistoryDays();
            long cutoff = System.currentTimeMillis() - (long) keepDays * 24L * 60L * 60L * 1000L;

            List<String> lines = Files.readAllLines(historyFile, StandardCharsets.UTF_8);
            List<DownloadRow> restored = new ArrayList<>();
            List<DownloadRow> interrupted = new ArrayList<>();

            for (String line : lines) {
                if (line == null || line.isBlank()) continue;
                String[] c = line.split("\t", -1);
                if (c.length < 8) continue;

                String url = unesc(c[0]);
                String title = unesc(c[1]);
                String folder = unesc(c[2]);
                String mode = unesc(c[3]);
                String quality = unesc(c[4]);
                String state = unesc(c[5]);
                String outPath = unesc(c[6]);

                long lastUpdated = 0L;
                try { lastUpdated = Long.parseLong(c[7].trim()); } catch (Exception ignored) {}

                if (lastUpdated > 0 && lastUpdated < cutoff
                        && ("COMPLETED".equals(state) || "MISSING".equals(state) || "CANCELLED".equals(state))) continue;
                if (url == null || url.isBlank()) continue;

                DownloadRow r = new DownloadRow(
                        url,
                        (title == null || title.isBlank()) ? shorten(url) : title,
                        downloadOrderSeq.getAndIncrement(),
                        folder == null ? "" : folder,
                        (mode == null || mode.isBlank()) ? "Video" : mode,
                        (quality == null || quality.isBlank()) ? "Best quality (Recommended)" : quality
                );

                if (c.length > 8) r.setReferer(unesc(c[8]));
                if (c.length > 9) { String tag = unesc(c[9]); r.resumeEtag = tag == null || tag.isBlank() ? null : tag; }
                if (c.length > 10) { try { r.totalBytes.set(Long.parseLong(c[10])); } catch (NumberFormatException ignored) {} }

                if (c.length > 11 && !unesc(c[11]).isBlank()) r.originalUrl = unesc(c[11]);

                // Thumb: restore from disk cache only
                try {
                    Path tp = ThumbnailCacheManager.getCachedPath(url);
                    r.thumbUrl.set(tp == null ? null : tp.toUri().toString());
                } catch (Exception ignored) {}

                if (outPath != null && !outPath.isBlank()) {
                    try { r.outputFile.set(Paths.get(outPath)); } catch (Exception ignored) {}
                }

                // State reconcile
                try {
                    String norm = (state == null) ? "QUEUED" : state.trim().toUpperCase(Locale.ROOT);
                    DownloadRow.State st = DownloadRow.State.valueOf(norm);

                    // Legacy active states identify abrupt exits. Old PAUSED rows stay manual.
                    if (isInterruptedState(st) || (st == DownloadRow.State.PAUSED
                            && c.length > 13 && Boolean.parseBoolean(c[13]))) interrupted.add(r);
                    if (isInterruptedState(st)) st = DownloadRow.State.PAUSED;

                    // if completed but file missing => missing
                    if (st == DownloadRow.State.COMPLETED) {
                        try {
                            Path p = (r.outputFile == null) ? null : r.outputFile.get();
                            boolean ok = p != null && Files.exists(p) && Files.size(p) > 0;
                            if (!ok) st = DownloadRow.State.MISSING;
                        } catch (Exception ignored2) {
                            st = DownloadRow.State.MISSING;
                        }
                    }

                    r.setState(st);
                    if (st == DownloadRow.State.PAUSED) r.status.set("Paused");
                    restoreFailureStatus(r, c.length > 12 ? unesc(c[12]) : "");

                    if ((st == DownloadRow.State.COMPLETED || st == DownloadRow.State.MISSING) && lastUpdated > 0) {
                        r.completedAt = lastUpdated;
                    }
                } catch (Exception ignored) {
                    r.setState(DownloadRow.State.PAUSED);
                    r.status.set("Paused");
                }

                try {
                    DownloadRow.State st = r.state.get();
                    if (st == DownloadRow.State.COMPLETED) {
                        Path p = r.outputFile.get();
                        if (p != null && Files.exists(p)) {
                            r.size.set(formatBytesDecimal(Files.size(p)));
                        }
                        r.progress.set(1.0);
                    } else if (st == DownloadRow.State.MISSING) {
                        r.size.set("");
                        r.progress.set(0.0);
                    } else {
                        r.progress.set(0.0);
                    }
                } catch (Exception ignored) {}

                if ("Direct".equalsIgnoreCase(r.mode) && r.outputFile.get() != null
                        && r.getState() != DownloadRow.State.COMPLETED && r.getState() != DownloadRow.State.MISSING) {
                    try {
                        if (DirectDownloadState.load(r, r.outputFile.get())) r.totalBytes.set(r.resumeTotal);
                        long saved = DirectDownloadState.downloaded(r.outputFile.get());
                        r.downloadedBytes.set(saved);
                        r.progress.set(r.totalBytes.get() > 0 ? (double) saved / r.totalBytes.get() : 0);
                        r.size.set(com.grabx.app.grabx.util.DownloadRuntimeUtils.formatTransferSize(saved, r.totalBytes.get()));
                        if (r.getState() == DownloadRow.State.PAUSED) {
                            r.status.set("PAUSED".equals(state) && c.length > 12 && !unesc(c[12]).isBlank()
                                    ? unesc(c[12]) : "Paused • saved data ready to resume");
                        }
                    } catch (Exception error) {
                        r.setState(DownloadRow.State.FAILED);
                        r.status.set("Saved state needs verification • partial files kept");
                    }
                }
                restored.add(r);
            }

            if (!restored.isEmpty()) {
                Platform.runLater(() -> {
                    try {
                        downloadItems.addAll(restored);
                        restored.forEach(this::attachAutoSave);

                        if (reconcileLoadedRowsWithDisk != null) reconcileLoadedRowsWithDisk.run();
                        // Disk reconciliation may itself be queued on FX; admission must follow it.
                        Platform.runLater(() -> {
                            if (interruptedOnShutdown != null) return;
                            if (autoResumeEnabled.getAsBoolean()) {
                                for (DownloadRow row : interrupted) {
                                    if (downloadItems.contains(row) && row.getState() == DownloadRow.State.PAUSED)
                                        resumeInterrupted.accept(row);
                                }
                            }
                            // Consume this startup's intent, even with the preference disabled.
                            saveNow();
                        });

                        if (warmMissingThumbnailsAsync != null) {
                            warmMissingThumbnailsAsync.accept(restored);
                        } else if (thumbFromUrl != null) {
                            // optional minimal warmup
                            for (DownloadRow rr : restored) {
                                try {
                                    String cur = (rr.thumbUrl == null) ? null : rr.thumbUrl.get();
                                    if (cur != null && !cur.isBlank()) continue;

                                    Path cached = ThumbnailCacheManager.getCachedPath(rr.url);
                                    if (cached != null) {
                                        rr.thumbUrl.set(cached.toUri().toString());
                                        continue;
                                    }

                                    String tu = thumbFromUrl.apply(rr.url);
                                    if (tu == null || tu.isBlank()) continue;

                                    Thread warmThread = new Thread(() -> {
                                        try {
                                            ThumbnailCacheManager.fetchAndCacheBlocking(rr.url, tu);
                                            Path after = ThumbnailCacheManager.getCachedPath(rr.url);
                                            if (after != null) {
                                                Platform.runLater(() -> {
                                                    try { rr.thumbUrl.set(after.toUri().toString()); }
                                                    catch (Exception ignored) {}
                                                });
                                            }
                                        } catch (Exception ignored) {}
                                    }, "grabx-warm-thumb");
                                    warmThread.setDaemon(true);
                                    warmThread.start();

                                } catch (Exception ignored) {}
                            }
                        }

                        if (updateMissingSidebarItem != null) updateMissingSidebarItem.run();
                    } catch (Exception ignored) {}
                });
            }

        } catch (Exception ignored) {}
    }
}
