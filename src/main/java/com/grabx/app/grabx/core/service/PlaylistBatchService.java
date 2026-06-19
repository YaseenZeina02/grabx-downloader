package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.ui.playlist.PlaylistEntry;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import com.grabx.app.grabx.core.model.DownloadRow;

public final class PlaylistBatchService {


    /** Required callbacks (provided by MainController) */
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

    private final Deque<PlaylistEntry> queue = new ArrayDeque<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private String batchMode = "";
    private String batchDefaultQuality = "";

    private final Map<String, DownloadRow> rowByVideoId = new HashMap<>();

    public PlaylistBatchService(Callbacks callbacks) {
        this.cb = Objects.requireNonNull(callbacks);
    }

    public void enqueue(List<PlaylistEntry> batch, String mode, String defaultQuality) {
        if (batch == null || batch.isEmpty()) return;

        String modeNow = (mode == null || mode.isBlank()) ? "Video" : mode;
        this.batchMode = modeNow;

        String defQ = (defaultQuality == null || defaultQuality.isBlank())
                ? (modeNow.equalsIgnoreCase("Audio") ? "mp3" : "Best quality (Recommended)")
                : defaultQuality;
        this.batchDefaultQuality = defQ;

        // push queue
        for (PlaylistEntry it : batch) {
            if (it == null || it.isUnavailable() || !it.isSelected()) continue;
            queue.addLast(it);
        }

        // create pending rows immediately
        for (PlaylistEntry it : batch) {
            if (it == null || it.isUnavailable() || !it.isSelected()) continue;

            String vid = it.getId();
            String url = (cb.youtubeWatchUrl == null) ? null : cb.youtubeWatchUrl.apply(vid);
            if (url == null || url.isBlank()) continue;

            String q = it.getQuality();
            if (q == null || q.isBlank()) q = this.batchDefaultQuality;

            if (cb.addPendingRow != null) {
                DownloadRow pending = cb.addPendingRow.apply(url, modeNow, q, it.displayTitle());
                if (pending != null && vid != null) rowByVideoId.put(vid, pending);
            }
        }

        if (cb.scheduleHistorySave != null) {
            try { cb.scheduleHistorySave.run(); } catch (Exception ignored) {}
        }

        if (running.compareAndSet(false, true)) {
            startNext();
        }
    }

    private void startNext() {
        PlaylistEntry next = queue.pollFirst();
        if (next == null) {
            running.set(false);
            return;
        }

        String vid = next.getId();
        String url = (cb.youtubeWatchUrl == null) ? null : cb.youtubeWatchUrl.apply(vid);
        if (url == null || url.isBlank()) {
            // skip and continue
            startNext();
            return;
        }

        String modeNow = (batchMode == null || batchMode.isBlank()) ? "Video" : batchMode;

        String q = next.getQuality();
        if (q == null || q.isBlank()) q = batchDefaultQuality;

        DownloadRow existing = (vid == null) ? null : rowByVideoId.get(vid);

        startSingle(url, modeNow, q, next.displayTitle(), existing, this::startNext);
    }

    private void startSingle(String url,
                             String mode,
                             String quality,
                             String title,
                             DownloadRow existingRow,
                             Runnable onDone) {

        DownloadRow row = existingRow;

        if (row == null) {
            if (cb.startDownloadForUrl != null) {
                row = cb.startDownloadForUrl.apply(url, mode, quality, title);
            }
        } else {
            if (cb.startDownloadRow != null) {
                DownloadRow finalRow = row;
                try {
                    // mark queued then run engine on same row
                    finalRow.setState(DownloadRow.State.QUEUED);
                } catch (Exception ignored) {}
                cb.startDownloadRow.accept(finalRow);
            }
        }

        if (row == null) {
            // nothing we can do -> continue
            if (onDone != null) onDone.run();
            return;
        }

        DownloadRow finalRow = row;

        // when finishes => cleanup + continue
        finalRow.stateProperty().addListener((obs, oldS, newS) -> {
            if (newS == DownloadRow.State.COMPLETED
                    || newS == DownloadRow.State.FAILED
                    || newS == DownloadRow.State.CANCELLED) {

                try {
                    String id = (cb.extractYoutubeId != null) ? cb.extractYoutubeId.apply(finalRow.url) : null;
                    if (id != null) rowByVideoId.remove(id);
                } catch (Exception ignored) {}

                if (onDone != null) onDone.run();
            }
        });
    }
}