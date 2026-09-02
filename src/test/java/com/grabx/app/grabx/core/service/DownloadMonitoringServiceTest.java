package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DownloadMonitoringServiceTest {
    @Test
    void observesAddedRowsAndSchedulesOneSavePerChange() {
        ObservableList<DownloadRow> rows = FXCollections.observableArrayList();
        List<String> events = new ArrayList<>();
        DownloadMonitoringService service = new DownloadMonitoringService(
                rows,
                null,
                null,
                row -> events.add("attach:" + row.title.get()),
                () -> events.add("save"),
                () -> { },
                () -> { },
                url -> { },
                url -> true
        );

        service.observeDownloads();
        service.observeDownloads();
        rows.addAll(row("One"), row("Two"));

        assertEquals(List.of("attach:One", "attach:Two", "save"), events);
    }

    private static DownloadRow row(String title) {
        return new DownloadRow("url", title, 0, "/tmp", "Video", "720p");
    }
}
