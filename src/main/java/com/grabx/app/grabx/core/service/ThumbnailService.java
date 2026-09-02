package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import com.grabx.app.grabx.thumbs.ThumbnailCacheManager;
import com.grabx.app.grabx.util.YouTubeUrls;
import javafx.application.Platform;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class ThumbnailService {
    private static final long WARMUP_DELAY_MILLIS = 1_500;

    private final ScheduledExecutorService scheduler;

    public ThumbnailService(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }

    public String thumbnailUrl(String mediaUrl) {
        String id = YouTubeUrls.extractVideoId(mediaUrl);
        if (id == null || id.isBlank()) return null;
        return "https://img.youtube.com/vi/" + id + "/hqdefault.jpg";
    }

    public void applyToRow(DownloadRow row, String mediaUrl) {
        if (row == null || mediaUrl == null || mediaUrl.isBlank()) return;

        String remoteThumbnailUrl = thumbnailUrl(mediaUrl);
        if (remoteThumbnailUrl == null || remoteThumbnailUrl.isBlank()) return;

        Path cached = ThumbnailCacheManager.getCachedPath(mediaUrl);
        setThumbnail(row, cached == null ? remoteThumbnailUrl : cached.toUri().toString());

        ThumbnailCacheManager.fetchAndCacheAsync(mediaUrl, remoteThumbnailUrl, () -> {
            Path downloaded = ThumbnailCacheManager.getCachedPath(mediaUrl);
            if (downloaded != null) {
                Platform.runLater(() -> setThumbnail(row, downloaded.toUri().toString()));
            }
        });
    }

    public void warmMissingAsync(List<DownloadRow> rows) {
        if (rows == null || rows.isEmpty()) return;

        Runnable warmup = () -> {
            Thread worker = new Thread(() -> warmMissing(rows), "grabx-warm-thumbs");
            worker.setDaemon(true);
            worker.start();
        };

        if (scheduler == null) {
            warmup.run();
        } else {
            scheduler.schedule(warmup, WARMUP_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    private void warmMissing(List<DownloadRow> rows) {
        for (DownloadRow row : rows) {
            if (row == null || row.url == null || row.url.isBlank() || hasThumbnail(row)) continue;

            Path cached = ThumbnailCacheManager.getCachedPath(row.url);
            if (cached == null) {
                String remoteThumbnailUrl = thumbnailUrl(row.url);
                if (remoteThumbnailUrl == null || remoteThumbnailUrl.isBlank()) continue;
                ThumbnailCacheManager.fetchAndCacheBlocking(row.url, remoteThumbnailUrl);
                cached = ThumbnailCacheManager.getCachedPath(row.url);
            }

            if (cached != null) {
                String cachedUrl = cached.toUri().toString();
                Platform.runLater(() -> setThumbnail(row, cachedUrl));
            }
        }
    }

    private static boolean hasThumbnail(DownloadRow row) {
        try {
            return row.thumbUrl != null
                    && row.thumbUrl.get() != null
                    && !row.thumbUrl.get().isBlank();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void setThumbnail(DownloadRow row, String thumbnailUrl) {
        if (row == null || row.thumbUrl == null || thumbnailUrl == null || thumbnailUrl.isBlank()) return;
        try {
            row.thumbUrl.set(thumbnailUrl);
        } catch (Exception ignored) {
        }
    }
}
