package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import com.grabx.app.grabx.thumbs.ThumbnailCacheManager;
import javafx.application.Platform;
import javafx.collections.ObservableList;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;


public class HistoryService {

    private static final String PREF_HISTORY_DAYS = "grabx.history.days";
    private static final int DEFAULT_HISTORY_DAYS = 30;

    private static final Path DOWNLOAD_HISTORY_FILE =
            Paths.get(System.getProperty("user.home"), ".grabx", "download-history.tsv");

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
                r.outputFile.addListener((obs, o, n) -> scheduleSave());
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
            java.nio.file.Files.write(
                    DOWNLOAD_HISTORY_FILE,
                    java.util.List.of(),
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE
            );
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
                                + esc(outputPath) + "\t" + updated
                );
            }
            if (!lines.isEmpty()) {
                Files.write(DOWNLOAD_HISTORY_FILE, lines, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
            }
        } catch (Exception ignored) {
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
            Path dir = DOWNLOAD_HISTORY_FILE.getParent();
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
        Thread saveThread = new Thread(() -> {
            try {
                ensureHistoryDir();

                int keepDays = getHistoryDays();
                long cutoff = System.currentTimeMillis() - (long) keepDays * 24L * 60L * 60L * 1000L;

                // Snapshot on FX thread (prevents empty writes)
                final List<DownloadRow> snap = new ArrayList<>();
                final CountDownLatch latch = new CountDownLatch(1);

                Platform.runLater(() -> {
                    try {
                        if (downloadItems != null && !downloadItems.isEmpty()) {
                            snap.addAll(downloadItems);
                        }
                    } catch (Exception ignored) {
                    } finally {
                        latch.countDown();
                    }
                });

                try { latch.await(800, TimeUnit.MILLISECONDS); } catch (Exception ignored) {}

                // IMPORTANT: don't truncate history if snapshot is empty
                if (snap.isEmpty()) return;

                List<String> lines = new ArrayList<>();
                for (DownloadRow r : snap) {
                    if (r == null) continue;
                    if (r.url == null || r.url.isBlank()) continue;

                    String title = safeGet(r.title);

                    String state = "QUEUED";
                    try { state = String.valueOf(r.state.get()); } catch (Exception ignored) {}

                    String outPath = "";
                    try {
                        Path p = (r.outputFile == null) ? null : r.outputFile.get();
                        if (p != null) outPath = p.toAbsolutePath().normalize().toString();
                    } catch (Exception ignored) {}

                    long lastUpdated = (r.completedAt > 0) ? r.completedAt : System.currentTimeMillis();
                    if (lastUpdated < cutoff) continue;

                    lines.add(
                            esc(r.url) + "\t" +
                                    esc(title) + "\t" +
                                    esc(r.folder) + "\t" +
                                    esc(r.mode) + "\t" +
                                    esc(r.quality) + "\t" +
                                    esc(state) + "\t" +
                                    esc(outPath) + "\t" +
                                    lastUpdated
                    );
                }

                // IMPORTANT: also don't truncate to empty due to cutoff
                if (lines.isEmpty()) return;

                Files.write(
                        DOWNLOAD_HISTORY_FILE,
                        lines,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                );

            } catch (Exception ignored) {}
        }, "grabx-save-history");
        saveThread.setDaemon(true);
        saveThread.start();
    }

    private void loadDownloadHistoryOnce() {
        try {
            if (!Files.exists(DOWNLOAD_HISTORY_FILE)) return;

            int keepDays = getHistoryDays();
            long cutoff = System.currentTimeMillis() - (long) keepDays * 24L * 60L * 60L * 1000L;

            List<String> lines = Files.readAllLines(DOWNLOAD_HISTORY_FILE, StandardCharsets.UTF_8);
            List<DownloadRow> restored = new ArrayList<>();

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

                if (lastUpdated > 0 && lastUpdated < cutoff) continue;
                if (url == null || url.isBlank()) continue;

                DownloadRow r = new DownloadRow(
                        url,
                        (title == null || title.isBlank()) ? shorten(url) : title,
                        downloadOrderSeq.getAndIncrement(),
                        folder == null ? "" : folder,
                        (mode == null || mode.isBlank()) ? "Video" : mode,
                        (quality == null || quality.isBlank()) ? "Best quality (Recommended)" : quality
                );

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

                    // never restore DOWNLOADING after restart
                    if (st == DownloadRow.State.DOWNLOADING) st = DownloadRow.State.PAUSED;

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

                    if ((st == DownloadRow.State.COMPLETED || st == DownloadRow.State.MISSING) && lastUpdated > 0) {
                        r.completedAt = lastUpdated;
                    }
                } catch (Exception ignored) {
                    r.setState(DownloadRow.State.QUEUED);
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

                restored.add(r);
            }

            if (!restored.isEmpty()) {
                Platform.runLater(() -> {
                    try {
                        downloadItems.addAll(restored);

                        if (reconcileLoadedRowsWithDisk != null) reconcileLoadedRowsWithDisk.run();

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
