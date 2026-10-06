package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import javafx.collections.FXCollections;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DownloadServiceTest {
    @Test
    void failedResumeMovesFromPausedToCancelledWithoutBecomingMissing() {
        DownloadRow blocked = row(0, DownloadRow.State.PAUSED);
        DownloadService service = new DownloadService(FXCollections.observableArrayList(blocked));
        service.setSidebarKey("PAUSED");
        assertEquals(java.util.List.of(blocked), java.util.List.copyOf(service.view()));
        blocked.setState(DownloadRow.State.FAILED);
        assertEquals(0, service.view().size());
        assertEquals(0, service.activeView().size());
        service.setSidebarKey("CANCELLED");
        assertEquals(java.util.List.of(blocked), java.util.List.copyOf(service.view()));
        service.setSidebarKey("MISSING");
        assertEquals(0, service.view().size());
    }

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
    void pausedRowImmediatelyMovesBelowWaitingWorkInBothViews() {
        DownloadRow first = row(0, DownloadRow.State.DOWNLOADING);
        DownloadRow second = row(1, DownloadRow.State.QUEUED);
        DownloadService service = new DownloadService(FXCollections.observableArrayList(first, second));

        first.setState(DownloadRow.State.PAUSED);
        assertEquals(java.util.List.of(second, first), java.util.List.copyOf(service.view()));
        assertEquals(java.util.List.of(second, first), java.util.List.copyOf(service.activeView()));

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

        assertEquals(java.util.List.of(downloading, paused), java.util.List.copyOf(service.activeView()));

        downloading.setState(DownloadRow.State.COMPLETED);
        assertEquals(java.util.List.of(paused), java.util.List.copyOf(service.activeView()));

        completed.setState(DownloadRow.State.QUEUED);
        assertEquals(java.util.List.of(completed, paused), java.util.List.copyOf(service.activeView()));
    }

    @Test void runningRowsWinRegardlessOfDateAndResumeImmediatelyReordersBothViews() {
        DownloadRow paused = row(100, DownloadRow.State.PAUSED);
        DownloadRow running = row(1, DownloadRow.State.DOWNLOADING);
        DownloadRow queued = row(200, DownloadRow.State.QUEUED);
        DownloadRow completed = row(300, DownloadRow.State.COMPLETED);
        DownloadService service = new DownloadService(FXCollections.observableArrayList(paused, queued, completed, running));
        for (String mode : java.util.List.of("Newest", "Oldest", "Last 5", "Last 10")) {
            service.setHistoryView(mode);
            assertEquals(java.util.List.of(running, queued, paused, completed), java.util.List.copyOf(service.view()));
            assertEquals(java.util.List.of(running, queued, paused), java.util.List.copyOf(service.activeView()));
        }
        service.setHistoryView("Newest");
        paused.setState(DownloadRow.State.DOWNLOADING);
        assertEquals(java.util.List.of(paused, running, queued), java.util.List.copyOf(service.activeView()));
        assertEquals(paused, service.view().getFirst());
        service.setHistoryView("Oldest");
        assertEquals(java.util.List.of(running, paused, queued), java.util.List.copyOf(service.activeView()));
        running.setState(DownloadRow.State.COMPLETED);
        assertEquals(paused, service.view().getFirst());
        assertEquals(java.util.List.of(paused, queued), java.util.List.copyOf(service.activeView()));
    }

    @Test void filteringDoesNotHideActiveCompactRowsOrBreakStatePriority() {
        DownloadRow running = row(1, DownloadRow.State.DOWNLOADING);
        DownloadRow paused = row(5, DownloadRow.State.PAUSED);
        DownloadService service = new DownloadService(FXCollections.observableArrayList(paused, running));
        service.setCombinedFilter("PAUSED", "Item 5");
        assertEquals(java.util.List.of(paused), java.util.List.copyOf(service.view()));
        assertEquals(java.util.List.of(running, paused), java.util.List.copyOf(service.activeView()));
        paused.setState(DownloadRow.State.DOWNLOADING);
        assertEquals(0, service.view().size());
        service.items().remove(running);
        running.setState(DownloadRow.State.PAUSED);
        assertEquals(java.util.List.of(paused), java.util.List.copyOf(service.activeView()));
    }

    private static DownloadRow row(long order, DownloadRow.State state) {
        DownloadRow row = new DownloadRow("https://example.com/" + order, "Item " + order,
                order, "/downloads", "Video", "720p");
        row.setState(state);
        return row;
    }
}
