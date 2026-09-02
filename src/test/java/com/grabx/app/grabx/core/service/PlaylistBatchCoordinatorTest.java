package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import com.grabx.app.grabx.ui.playlist.PlaylistEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaylistBatchCoordinatorTest {
    @Test
    void createsPendingRowAndStartsTheSameRow() {
        List<String> events = new ArrayList<>();
        List<DownloadRow> rows = new ArrayList<>();
        AtomicLong order = new AtomicLong();

        PlaylistBatchCoordinator coordinator = new PlaylistBatchCoordinator(
                (url, mode, quality, title) -> new DownloadRow(
                        url, title, order.getAndIncrement(), "/downloads", mode, quality
                ),
                row -> { rows.add(row); events.add("add"); },
                (row, url) -> events.add("thumbnail"),
                row -> events.add("history"),
                () -> events.add("save"),
                row -> events.add("start:" + row.title.get()),
                () -> events.add("refilter"),
                Runnable::run,
                id -> "https://youtube.test/watch?v=" + id,
                url -> url.substring(url.indexOf("v=") + 2),
                text -> events.add("status")
        );

        PlaylistEntry entry = new PlaylistEntry(1, "abc", "Episode", null, true);
        entry.setQuality("720p");
        coordinator.enqueue(List.of(entry), "Video", "Best quality");

        assertEquals(1, rows.size());
        assertEquals("https://youtube.test/watch?v=abc", rows.getFirst().url);
        assertEquals("1. Episode", rows.getFirst().title.get());
        assertEquals("720p", rows.getFirst().quality);
        assertEquals(
                List.of("thumbnail", "add", "refilter", "history", "save", "start:1. Episode"),
                events
        );
    }
}
