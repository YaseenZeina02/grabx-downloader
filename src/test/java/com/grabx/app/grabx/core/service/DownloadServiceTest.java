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

    @Test
    void filtersToTheFiveNewestTerminalRowsButAlwaysKeepsActiveWork() {
        javafx.collections.ObservableList<DownloadRow> rows = FXCollections.observableArrayList();
        DownloadRow active = row(99, DownloadRow.State.DOWNLOADING);
        rows.add(active);
        for (int index = 0; index < 8; index++) {
            DownloadRow completed = row(index, DownloadRow.State.COMPLETED);
            completed.completedAt = 1_000 + index;
            rows.add(completed);
        }
        DownloadService service = new DownloadService(rows);

        service.setHistoryView("Last 5");

        assertEquals(6, service.view().size());
        assertEquals(active, service.view().getFirst());
        assertEquals(7, service.view().get(1).orderIndex);
        assertEquals(3, service.view().getLast().orderIndex);
    }

    @Test
    void sortsTerminalRowsByNewestOrOldestCompletionTime() {
        DownloadRow older = row(0, DownloadRow.State.COMPLETED);
        older.completedAt = 100;
        DownloadRow newer = row(1, DownloadRow.State.COMPLETED);
        newer.completedAt = 200;
        DownloadService service = new DownloadService(FXCollections.observableArrayList(older, newer));

        service.setHistoryView("Newest");
        assertEquals(java.util.List.of(newer, older), java.util.List.copyOf(service.view()));

        service.setHistoryView("Oldest");
        assertEquals(java.util.List.of(older, newer), java.util.List.copyOf(service.view()));
    }

    @Test
    void compactViewContainsOnlyActiveRowsAndTracksStateChanges() {
        DownloadRow downloading = row(2, DownloadRow.State.DOWNLOADING);
        DownloadRow paused = row(1, DownloadRow.State.PAUSED);
        DownloadRow completed = row(0, DownloadRow.State.COMPLETED);
        DownloadService service = new DownloadService(FXCollections.observableArrayList(
                downloading, paused, completed
        ));

        assertEquals(java.util.List.of(paused, downloading), java.util.List.copyOf(service.activeView()));

        downloading.setState(DownloadRow.State.COMPLETED);
        assertEquals(java.util.List.of(paused), java.util.List.copyOf(service.activeView()));

        completed.setState(DownloadRow.State.QUEUED);
        assertEquals(java.util.List.of(completed, paused), java.util.List.copyOf(service.activeView()));
    }

    private static DownloadRow row(long order, DownloadRow.State state) {
        DownloadRow row = new DownloadRow("https://example.com/" + order, "Item " + order,
                order, "/downloads", "Video", "720p");
        row.setState(state);
        return row;
    }
}
