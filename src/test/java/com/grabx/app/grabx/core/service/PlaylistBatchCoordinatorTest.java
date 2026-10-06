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
    void submitsAllSelectedRowsAndTerminalCallbacksNeverResubmitTheNextItem() {
        var callbacks = new PlaylistBatchService.Callbacks();
        var created = new ArrayList<DownloadRow>(); var starts = new ArrayList<DownloadRow>();
        callbacks.youtubeWatchUrl = id -> "https://example.test/" + id;
        callbacks.addPendingRow = (url, mode, quality, title) -> {
            var row = new DownloadRow(url, title, created.size(), "/tmp", mode, quality); created.add(row); return row;
        };
        callbacks.startDownloadRow = starts::add;
        var batches = new PlaylistBatchService(callbacks);
        var a = new PlaylistEntry(1,"a","First",null,true);
        var b = new PlaylistEntry(2,"b","Second",null,true);
        b.setQuality("720p");
        batches.enqueue(List.of(a,b),"Video","Best");
        assertEquals(created,starts); assertEquals(2,starts.size());
        var c = new PlaylistEntry(3,"c","Audio",null,true);
        c.setQuality("");
        batches.enqueue(List.of(c),"Audio only","mp3");
        assertEquals("Video",starts.get(1).mode); assertEquals("720p",starts.get(1).quality);
        assertEquals("Audio only",starts.get(2).mode); assertEquals("mp3",starts.get(2).quality);
        starts.get(0).setState(DownloadRow.State.FAILED); starts.get(0).setState(DownloadRow.State.COMPLETED);
        assertEquals(3,starts.size(), "Terminal callbacks must not restart playlist items");
    }

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
