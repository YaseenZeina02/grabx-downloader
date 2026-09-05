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

        return enqueueRow(row, true);
    }

    public DownloadRow enqueueDirect(String url, String folder, String suggestedFilename) {
        String filename = trimToEmpty(suggestedFilename);
        DownloadRow row = create(url, folder, "Direct", "Auto",
                filename.isBlank() ? null : filename);
        if (!filename.isBlank()) row.titleLocked.set(true);
        return enqueueRow(row, false);
    }

    private DownloadRow enqueueRow(DownloadRow row, boolean enrichMetadata) {

        Runnable addAndStart = () -> {
            DownloadRow duplicate = findActiveDuplicate(row);
            if (duplicate != null) {
                if (duplicate.getState() == DownloadRow.State.PAUSED) {
                    duplicate.setState(DownloadRow.State.PENDING);
                    duplicate.status.set("Preparing");
                    if (startDownload != null) startDownload.accept(duplicate, true);
                } else if (statusUpdater != null) {
                    statusUpdater.accept("This download is already in progress");
                }
                return;
            }

            if (attachHistory != null) attachHistory.accept(row);
            if (enrichMetadata && applyThumbnail != null) applyThumbnail.accept(row, row.url);
            downloadItems.add(0, row);
            if (saveHistory != null) saveHistory.run();
            if (startDownload != null) startDownload.accept(row, false);
            if (enrichMetadata && resolveTitle != null) resolveTitle.accept(row, row.url);
        };
        if (uiExecutor != null) uiExecutor.accept(addAndStart); else addAndStart.run();
        return row;
    }

    private DownloadRow findActiveDuplicate(DownloadRow candidate) {
        if (downloadItems == null || candidate == null) return null;
        for (DownloadRow existing : downloadItems) {
            if (existing == null || !sameRequest(existing, candidate)) continue;
            DownloadRow.State state = existing.getState();
            if (state == DownloadRow.State.QUEUED
                    || state == DownloadRow.State.PENDING
                    || state == DownloadRow.State.DOWNLOADING
                    || state == DownloadRow.State.PAUSED) {
                return existing;
            }
        }
        return null;
    }

    private static boolean sameRequest(DownloadRow first, DownloadRow second) {
        return java.util.Objects.equals(first.url, second.url)
                && java.util.Objects.equals(first.folder, second.folder)
                && java.util.Objects.equals(first.mode, second.mode)
                && java.util.Objects.equals(first.quality, second.quality);
    }

    private String defaultFolder() {
        return defaultFolder == null ? "" : trimToEmpty(defaultFolder.get());
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
