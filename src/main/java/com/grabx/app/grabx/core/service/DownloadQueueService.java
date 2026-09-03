package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import com.grabx.app.grabx.util.YouTubeUrls;
import javafx.collections.ObservableList;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Creates download rows and coordinates adding new single downloads to the queue. */
public final class DownloadQueueService {
    private final ObservableList<DownloadRow> downloadItems;
    private final AtomicLong orderSequence;
    private final Supplier<String> defaultFolder;
    private final Consumer<DownloadRow> attachHistory;
    private final Runnable saveHistory;
    private final BiConsumer<DownloadRow, String> applyThumbnail;
    private final BiConsumer<DownloadRow, String> resolveTitle;
    private final BiConsumer<DownloadRow, Boolean> startDownload;
    private final Consumer<Runnable> uiExecutor;
    private final Consumer<String> statusUpdater;
    private final String videoMode;
    private final String audioMode;
    private final String bestVideoQuality;
    private final String defaultAudioFormat;

    public DownloadQueueService(
            ObservableList<DownloadRow> downloadItems,
            AtomicLong orderSequence,
            Supplier<String> defaultFolder,
            Consumer<DownloadRow> attachHistory,
            Runnable saveHistory,
            BiConsumer<DownloadRow, String> applyThumbnail,
            BiConsumer<DownloadRow, String> resolveTitle,
            BiConsumer<DownloadRow, Boolean> startDownload,
            Consumer<Runnable> uiExecutor,
            Consumer<String> statusUpdater,
            String videoMode,
            String audioMode,
            String bestVideoQuality,
            String defaultAudioFormat
    ) {
        this.downloadItems = downloadItems;
        this.orderSequence = orderSequence;
        this.defaultFolder = defaultFolder;
        this.attachHistory = attachHistory;
        this.saveHistory = saveHistory;
        this.applyThumbnail = applyThumbnail;
        this.resolveTitle = resolveTitle;
        this.startDownload = startDownload;
        this.uiExecutor = uiExecutor;
        this.statusUpdater = statusUpdater;
        this.videoMode = videoMode;
        this.audioMode = audioMode;
        this.bestVideoQuality = bestVideoQuality;
        this.defaultAudioFormat = defaultAudioFormat;
    }

    public DownloadRow create(String url, String mode, String quality, String title) {
        return create(url, defaultFolder(), mode, quality, title);
    }

    public DownloadRow create(String url, String folder, String mode, String quality, String title) {
        String normalizedUrl = YouTubeUrls.normalizeSingleVideoUrl(trimToEmpty(url));
        String resolvedTitle = title;
        if (resolvedTitle == null || resolvedTitle.isBlank()) {
            resolvedTitle = DownloadTitleService.shorten(normalizedUrl);
        }
        if (resolvedTitle == null || resolvedTitle.isBlank()) resolvedTitle = "New item";

        String resolvedMode = (mode == null || mode.isBlank()) ? videoMode : mode;
        String resolvedQuality = quality;
        if (resolvedQuality == null || resolvedQuality.isBlank()) {
            resolvedQuality = audioMode.equals(resolvedMode) ? defaultAudioFormat : bestVideoQuality;
        }

        DownloadRow row = new DownloadRow(
                normalizedUrl,
                resolvedTitle,
                orderSequence.getAndIncrement(),
                folder,
                resolvedMode,
                resolvedQuality
        );
        row.status.set("Preparing");
        return row;
    }

    public DownloadRow enqueue(String url, String folder, String mode, String quality) {
        DownloadRow row = create(url, folder, mode, quality, null);
        if (attachHistory != null) attachHistory.accept(row);
        if (applyThumbnail != null) applyThumbnail.accept(row, row.url);

        Runnable addAndStart = () -> {
            downloadItems.add(0, row);
            if (saveHistory != null) saveHistory.run();
            if (startDownload != null) startDownload.accept(row, false);
        };
        if (uiExecutor != null) uiExecutor.accept(addAndStart); else addAndStart.run();

        if (resolveTitle != null) resolveTitle.accept(row, row.url);
        return row;
    }

    private String defaultFolder() {
        return defaultFolder == null ? "" : trimToEmpty(defaultFolder.get());
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
