package com.grabx.app.grabx.core.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BulkDownloadActionsServiceTest {
    @Test
    void reportsBulkStateActionCounts() {
        List<String> events = new ArrayList<>();
        BulkDownloadActionsService service = service(events, false, 2);

        assertEquals(2, service.cancelAll());
        assertEquals(2, service.pauseAll());
        assertEquals(2, service.resumeAll());
        assertEquals(List.of(
                "Cancelled 2 item(s)",
                "Paused 2 item(s)",
                "Resumed 2 item(s)"
        ), events);
    }

    @Test
    void clearingLastItemsRefreshesUiAndDeletesHistory() {
        List<String> events = new ArrayList<>();
        BulkDownloadActionsService service = service(events, true, 3);

        assertEquals(3, service.clearAll());
        assertEquals(List.of("missing", "refilter", "clear-history", "Cleared 3 item(s)"), events);
    }

    @Test
    void clearingWithRemainingItemsSchedulesHistorySave() {
        List<String> events = new ArrayList<>();
        BulkDownloadActionsService service = service(events, false, 1);

        service.clearAll();
        assertEquals(List.of("missing", "refilter", "save-history", "Cleared 1 item(s)"), events);
    }

    private static BulkDownloadActionsService service(List<String> events, boolean empty, int count) {
        return new BulkDownloadActionsService(
                () -> count,
                () -> count,
                () -> count,
                () -> count,
                () -> empty,
                () -> events.add("missing"),
                () -> events.add("refilter"),
                () -> events.add("clear-history"),
                () -> events.add("save-history"),
                events::add
        );
    }
}
