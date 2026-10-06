package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.ui.playlist.PlaylistEntry;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import com.grabx.app.grabx.core.model.DownloadRow;

public final class PlaylistBatchService {


    /** Required callbacks provided by the application coordinator. */
    public static final class Callbacks {
        /** videoId -> watch URL */
        public Function<String, String> youtubeWatchUrl;

        /** create a PENDING row and add it to main list */
        public QuadFunction<String, String, String, String, DownloadRow> addPendingRow;

        /** start download on an existing row (reuse row, no new row) */
        public Consumer<DownloadRow> startDownloadRow;

        /** create+add a normal download row (fallback) */
        public QuadFunction<String, String, String, String, DownloadRow> startDownloadForUrl;

        /** extract youtube id from a URL (for map cleanup) */
        public Function<String, String> extractYoutubeId;

        /** called when you want to persist history after adding rows (optional) */
        public Runnable scheduleHistorySave;

        /** notify user status (optional) */
        public Consumer<String> setStatusText;
    }

    @FunctionalInterface
    public interface QuadFunction<A,B,C,D,R> { R apply(A a, B b, C c, D d); }

    private final Callbacks cb;

    public PlaylistBatchService(Callbacks callbacks) {
        this.cb = Objects.requireNonNull(callbacks);
    }

    /** Submit every selected row to the shared admission queue, preserving batch order. */
    public void enqueue(List<PlaylistEntry> batch, String mode, String defaultQuality) {
        if (batch == null || batch.isEmpty()) return;
        String resolvedMode = mode == null || mode.isBlank() ? "Video" : mode;
        String fallback = defaultQuality == null || defaultQuality.isBlank()
                ? (resolvedMode.toLowerCase(java.util.Locale.ROOT).startsWith("audio") ? "mp3" : "Best quality (Recommended)")
                : defaultQuality;
        List<Runnable> submissions = new java.util.ArrayList<>();
        for (PlaylistEntry entry : batch) {
            if (entry == null || entry.isUnavailable() || !entry.isSelected()) continue;
            String url = cb.youtubeWatchUrl == null ? null : cb.youtubeWatchUrl.apply(entry.getId());
            if (url == null || url.isBlank()) continue;
            String quality = entry.getQuality() == null || entry.getQuality().isBlank() ? fallback : entry.getQuality();
            String title = entry.displayTitle();
            DownloadRow row = cb.addPendingRow == null ? null : cb.addPendingRow.apply(url, resolvedMode, quality, title);
            if (row != null && cb.startDownloadRow != null) {
                submissions.add(() -> cb.startDownloadRow.accept(row));
            } else if (row == null && cb.startDownloadForUrl != null) {
                submissions.add(() -> cb.startDownloadForUrl.apply(url, resolvedMode, quality, title));
            }
        }
        if (cb.scheduleHistorySave != null) cb.scheduleHistorySave.run();
        submissions.forEach(Runnable::run);
    }
}
