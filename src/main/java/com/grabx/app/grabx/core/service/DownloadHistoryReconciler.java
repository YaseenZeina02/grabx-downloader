package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import com.grabx.app.grabx.util.DownloadRuntimeUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

public final class DownloadHistoryReconciler {
    private final Iterable<DownloadRow> rows;
    private final Consumer<Runnable> uiDispatcher;
    private final Runnable refreshDownloads;
    private final Runnable refreshMissingItems;

    public DownloadHistoryReconciler(
            Iterable<DownloadRow> rows,
            Consumer<Runnable> uiDispatcher,
            Runnable refreshDownloads,
            Runnable refreshMissingItems
    ) {
        this.rows = rows;
        this.uiDispatcher = uiDispatcher == null ? Runnable::run : uiDispatcher;
        this.refreshDownloads = refreshDownloads;
        this.refreshMissingItems = refreshMissingItems;
    }

    public void reconcileLoadedRows() {
        uiDispatcher.accept(() -> {
            if (rows != null) {
                for (DownloadRow row : rows) reconcileRow(row);
            }
            runSafely(refreshDownloads);
            runSafely(refreshMissingItems);
        });
    }

    void reconcileRow(DownloadRow row) {
        if (row == null) return;

        Path outputFile = getOutputFile(row);
        if (fileExists(outputFile)) {
            markCompleted(row, outputFile);
            return;
        }

        DownloadRow.State state = getState(row);
        if (state == DownloadRow.State.COMPLETED) {
            row.setState(DownloadRow.State.MISSING);
        } else if (state == DownloadRow.State.DOWNLOADING) {
            row.setState(DownloadRow.State.QUEUED);
            setTransferDetails(row, 0, "", "");
        }
    }

    private static void markCompleted(DownloadRow row, Path outputFile) {
        row.setState(DownloadRow.State.COMPLETED);
        setTransferDetails(row, 1, "", "");
        try {
            if (row.size != null && (row.size.get() == null || row.size.get().isBlank())) {
                row.size.set(DownloadRuntimeUtils.formatBytesDecimal(Files.size(outputFile)));
            }
        } catch (Exception ignored) {
        }
    }

    private static Path getOutputFile(DownloadRow row) {
        try {
            Path outputFile = row.outputFile == null ? null : row.outputFile.get();
            return outputFile == null ? null : outputFile.toAbsolutePath().normalize();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean fileExists(Path outputFile) {
        if (outputFile == null) return false;
        try {
            return Files.exists(outputFile);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static DownloadRow.State getState(DownloadRow row) {
        try {
            return row.state == null ? null : row.state.get();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void setTransferDetails(
            DownloadRow row, double progress, String speed, String eta
    ) {
        try { row.progress.set(progress); } catch (Exception ignored) {}
        try { row.speed.set(speed); } catch (Exception ignored) {}
        try { row.eta.set(eta); } catch (Exception ignored) {}
    }

    private static void runSafely(Runnable action) {
        if (action == null) return;
        try {
            action.run();
        } catch (Exception ignored) {
        }
    }
}
