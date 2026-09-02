package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DownloadHistoryReconcilerTest {
    @TempDir
    Path tempDir;

    @Test
    void marksRowCompletedWhenOutputFileExists() throws Exception {
        Path output = tempDir.resolve("video.mp4");
        Files.write(output, new byte[1_500]);
        DownloadRow row = row(DownloadRow.State.QUEUED);
        row.outputFile.set(output);
        row.progress.set(0.4);
        row.speed.set("2 MB/s");
        row.eta.set("10s");

        reconciler(List.of(row)).reconcileRow(row);

        assertEquals(DownloadRow.State.COMPLETED, row.getState());
        assertEquals(1, row.progress.get());
        assertEquals("", row.speed.get());
        assertEquals("", row.eta.get());
        assertEquals("1.5 KB", row.size.get());
    }

    @Test
    void marksCompletedRowMissingWhenOutputFileIsGone() {
        DownloadRow row = row(DownloadRow.State.COMPLETED);
        row.outputFile.set(tempDir.resolve("missing.mp4"));

        reconciler(List.of(row)).reconcileRow(row);

        assertEquals(DownloadRow.State.MISSING, row.getState());
    }

    @Test
    void resetsInterruptedDownloadToQueued() {
        DownloadRow row = row(DownloadRow.State.DOWNLOADING);
        row.progress.set(0.7);
        row.speed.set("1 MB/s");
        row.eta.set("20s");

        reconciler(List.of(row)).reconcileRow(row);

        assertEquals(DownloadRow.State.QUEUED, row.getState());
        assertEquals(0, row.progress.get());
        assertEquals("", row.speed.get());
        assertEquals("", row.eta.get());
    }

    @Test
    void reconcilesAllRowsAndRefreshesViews() {
        DownloadRow missing = row(DownloadRow.State.COMPLETED);
        AtomicInteger refreshes = new AtomicInteger();
        DownloadHistoryReconciler reconciler = new DownloadHistoryReconciler(
                List.of(missing), Runnable::run, refreshes::incrementAndGet, refreshes::incrementAndGet
        );

        reconciler.reconcileLoadedRows();

        assertEquals(DownloadRow.State.MISSING, missing.getState());
        assertEquals(2, refreshes.get());
    }

    private static DownloadHistoryReconciler reconciler(List<DownloadRow> rows) {
        return new DownloadHistoryReconciler(rows, Runnable::run, null, null);
    }

    private static DownloadRow row(DownloadRow.State state) {
        DownloadRow row = new DownloadRow("https://example.com/video", "Video", 1, "/tmp", "Video", "720p");
        row.setState(state);
        return row;
    }
}
