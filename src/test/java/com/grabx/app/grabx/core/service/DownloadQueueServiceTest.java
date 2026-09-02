package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class DownloadQueueServiceTest {
    @Test
    void createsRowsWithNormalizedDefaultsAndIncreasingOrder() {
        DownloadQueueService service = service(FXCollections.observableArrayList(), new ArrayList<>());

        DownloadRow first = service.create("  https://example.com/video  ", null, null, null);
        DownloadRow second = service.create("https://example.com/audio", "Audio only", null, "Track");

        assertEquals("https://example.com/video", first.url);
        assertEquals("Video", first.mode);
        assertEquals("Best quality", first.quality);
        assertEquals("/downloads", first.folder);
        assertEquals(0, first.orderIndex);
        assertEquals("mp3", second.quality);
        assertEquals(1, second.orderIndex);
    }

    @Test
    void enqueueAddsAtTopAndRunsQueueCallbacks() {
        ObservableList<DownloadRow> rows = FXCollections.observableArrayList();
        List<String> events = new ArrayList<>();
        DownloadQueueService service = service(rows, events);

        DownloadRow row = service.enqueue("https://example.com/video", "/chosen", "Video", "720p");

        assertSame(row, rows.getFirst());
        assertEquals("/chosen", row.folder);
        assertEquals(List.of(
                "history", "thumbnail", "save", "start",
                "Queued: https://example.com/video", "title"
        ), events);
    }

    private static DownloadQueueService service(ObservableList<DownloadRow> rows, List<String> events) {
        return new DownloadQueueService(
                rows,
                new AtomicLong(),
                () -> "/downloads",
                row -> events.add("history"),
                () -> events.add("save"),
                (row, url) -> events.add("thumbnail"),
                (row, url) -> events.add("title"),
                (row, resume) -> events.add("start"),
                Runnable::run,
                text -> events.add(text),
                "Video",
                "Audio only",
                "Best quality",
                "mp3"
        );
    }
}
