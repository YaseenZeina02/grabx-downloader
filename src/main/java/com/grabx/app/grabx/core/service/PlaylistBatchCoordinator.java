package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import com.grabx.app.grabx.ui.playlist.PlaylistEntry;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/** Wires playlist batches to the application's download queue and UI callbacks. */
public final class PlaylistBatchCoordinator {
    private final PlaylistBatchService batchService;

    public PlaylistBatchCoordinator(
            PlaylistBatchService.QuadFunction<String, String, String, String, DownloadRow> createRow,
            Consumer<DownloadRow> addRow,
            BiConsumer<DownloadRow, String> applyThumbnail,
            Consumer<DownloadRow> attachHistory,
            Runnable saveHistory,
            Consumer<DownloadRow> startDownload,
            Runnable refilter,
            Consumer<Runnable> uiExecutor,
            Function<String, String> watchUrl,
            Function<String, String> extractVideoId,
            Consumer<String> statusUpdater
    ) {
        PlaylistBatchService.Callbacks callbacks = new PlaylistBatchService.Callbacks();
        callbacks.youtubeWatchUrl = watchUrl;
        callbacks.extractYoutubeId = extractVideoId;
        callbacks.scheduleHistorySave = saveHistory;
        callbacks.setStatusText = text -> runOnUi(uiExecutor, () -> safeAccept(statusUpdater, text));

        callbacks.addPendingRow = (url, mode, quality, title) -> {
            DownloadRow row = createRow.apply(url, mode, quality, title);
            if (row == null) return null;
            row.setState(DownloadRow.State.QUEUED);
            safeAccept(applyThumbnail, row, url);
            runOnUi(uiExecutor, () -> {
                safeAccept(addRow, row);
                safeRun(refilter);
                safeAccept(attachHistory, row);
            });
            return row;
        };

        callbacks.startDownloadRow = row -> {
            if (row != null) runOnUi(uiExecutor, () -> safeAccept(startDownload, row));
        };

        callbacks.startDownloadForUrl = (url, mode, quality, title) -> {
            DownloadRow row = createRow.apply(url, mode, quality, title);
            if (row == null) return null;
            safeAccept(applyThumbnail, row, url);
            runOnUi(uiExecutor, () -> {
                safeAccept(addRow, row);
                safeRun(refilter);
                safeAccept(attachHistory, row);
                safeAccept(startDownload, row);
            });
            return row;
        };

        batchService = new PlaylistBatchService(callbacks);
    }

    public void enqueue(List<PlaylistEntry> batch, String mode, String quality) {
        batchService.enqueue(batch, mode, quality);
    }

    private static void runOnUi(Consumer<Runnable> uiExecutor, Runnable action) {
        if (uiExecutor != null) uiExecutor.accept(action); else action.run();
    }

    private static void safeRun(Runnable action) {
        try {
            if (action != null) action.run();
        } catch (Exception ignored) {
        }
    }

    private static <T> void safeAccept(Consumer<T> action, T value) {
        try {
            if (action != null) action.accept(value);
        } catch (Exception ignored) {
        }
    }

    private static <T, U> void safeAccept(BiConsumer<T, U> action, T first, U second) {
        try {
            if (action != null) action.accept(first, second);
        } catch (Exception ignored) {
        }
    }
}
