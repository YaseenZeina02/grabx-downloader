package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import javafx.collections.FXCollections;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DownloadServiceTest {
    @Test
    void keepsActiveRowsAboveTerminalRowsWithoutReorderingWithinGroups() {
        DownloadRow completed = row(0, DownloadRow.State.COMPLETED);
        DownloadRow downloading = row(1, DownloadRow.State.DOWNLOADING);
        DownloadRow failed = row(2, DownloadRow.State.FAILED);
        DownloadRow paused = row(3, DownloadRow.State.PAUSED);
        DownloadService service = new DownloadService(FXCollections.observableArrayList(
                completed, downloading, failed, paused
        ));

        assertEquals(
                java.util.List.of(downloading, paused, completed, failed),
                java.util.List.copyOf(service.view())
        );
    }

    @Test
    void automaticallyMovesARowBelowActiveWorkOnlyAfterItFinishes() {
        DownloadRow first = row(0, DownloadRow.State.DOWNLOADING);
        DownloadRow second = row(1, DownloadRow.State.QUEUED);
        DownloadService service = new DownloadService(FXCollections.observableArrayList(first, second));

        first.setState(DownloadRow.State.PAUSED);
        assertEquals(java.util.List.of(first, second), java.util.List.copyOf(service.view()));

        first.setState(DownloadRow.State.COMPLETED);
        assertEquals(java.util.List.of(second, first), java.util.List.copyOf(service.view()));
    }

    private static DownloadRow row(long order, DownloadRow.State state) {
        DownloadRow row = new DownloadRow("https://example.com/" + order, "Item " + order,
                order, "/downloads", "Video", "720p");
        row.setState(state);
        return row;
    }
}
