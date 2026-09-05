package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import javafx.collections.FXCollections;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalSpeedServiceTest {
    @Test
    void sumsOnlyActiveDownloadSpeedsAndRefreshesOnChanges() {
        var rows = FXCollections.<DownloadRow>observableArrayList();
        AtomicReference<String> footer = new AtomicReference<>();
        AtomicReference<String> summary = new AtomicReference<>();
        GlobalSpeedService service = new GlobalSpeedService(rows, footer::set, summary::set);
        service.start();

        DownloadRow first = row(DownloadRow.State.DOWNLOADING, "500.0 KB/S");
        DownloadRow second = row(DownloadRow.State.DOWNLOADING, "1.5 MB/s");
        DownloadRow completed = row(DownloadRow.State.COMPLETED, "9.0 MB/s");
        rows.addAll(first, second, completed);

        assertEquals("↓  2 MB/s", footer.get());
        assertEquals("2 downloading  ·  0 preparing  ·  0 queued  ·  0 paused  ·  1 completed", summary.get());

        first.speed.set("1.0 MB/s");
        assertEquals("↓  2.5 MB/s", footer.get());

        second.setState(DownloadRow.State.PAUSED);
        assertEquals("↓  1 MB/s", footer.get());
        assertEquals("1 downloading  ·  0 preparing  ·  0 queued  ·  1 paused  ·  1 completed", summary.get());
    }

    @Test
    void reportsPendingSeparatelyFromQueuedDownloads() {
        var rows = FXCollections.<DownloadRow>observableArrayList();
        rows.add(row(DownloadRow.State.PENDING, ""));
        rows.add(row(DownloadRow.State.QUEUED, ""));

        assertEquals(
                "0 downloading  ·  1 preparing  ·  1 queued  ·  0 paused  ·  0 completed",
                GlobalSpeedService.formatSummary(rows)
        );
    }

    @Test
    void formatsZeroSpeedForAnIdleList() {
        assertEquals("0 KB/s", GlobalSpeedService.formatSpeed(0));
        assertEquals("211 KB/s", GlobalSpeedService.formatSpeed(211_000));
        assertEquals("1.5 MB/s", GlobalSpeedService.formatSpeed(1_500_000));
    }

    private static DownloadRow row(DownloadRow.State state, String speed) {
        DownloadRow row = new DownloadRow("url", "title", 1, "/downloads", "Video", "720p");
        row.setState(state);
        row.speed.set(speed);
        return row;
    }
}
