package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.ui.playlist.PlaylistEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaylistFlowServiceTest {
    @Test
    void downloadsResultUsingDefaultFolderAndCompletesFlow() {
        PlaylistEntry entry = new PlaylistEntry(1, "abc", "Episode", null, true);
        AtomicReference<String> shownFolder = new AtomicReference<>();
        AtomicReference<String> savedFolder = new AtomicReference<>();
        AtomicReference<PlaylistDialogService.Result> enqueued = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean();
        List<String> statuses = new ArrayList<>();

        PlaylistFlowService service = new PlaylistFlowService(
                (owner, url, folder) -> {
                    shownFolder.set(folder);
                    return PlaylistDialogService.Result.download(
                            List.of(entry), "Video", "720p", "/chosen"
                    );
                },
                availableGateway(true, enqueued),
                () -> "/default",
                savedFolder::set,
                statuses::add,
                () -> completed.set(true),
                () -> { }
        );

        service.open(null, "https://example.test/playlist", "  ");

        assertEquals("/default", shownFolder.get());
        assertEquals("/chosen", savedFolder.get());
        assertEquals(List.of(entry), enqueued.get().batch());
        assertEquals(List.of("Queued playlist: 1 items"), statuses);
        assertTrue(completed.get());
    }

    @Test
    void returnsToAddLinkWhenDialogGoesBack() {
        AtomicBoolean returned = new AtomicBoolean();
        PlaylistFlowService service = new PlaylistFlowService(
                (owner, url, folder) -> PlaylistDialogService.Result.back(),
                availableGateway(true, new AtomicReference<>()),
                () -> "/default",
                folder -> { },
                text -> { },
                () -> { },
                () -> returned.set(true)
        );

        service.open(null, "playlist", "/explicit");

        assertTrue(returned.get());
    }

    @Test
    void reportsUnavailableBatchServiceWithoutCompleting() {
        AtomicBoolean completed = new AtomicBoolean();
        List<String> statuses = new ArrayList<>();
        PlaylistFlowService service = new PlaylistFlowService(
                (owner, url, folder) -> PlaylistDialogService.Result.download(
                        List.of(), "Video", "Best quality", "/chosen"
                ),
                availableGateway(false, new AtomicReference<>()),
                () -> "/default",
                folder -> { },
                statuses::add,
                () -> completed.set(true),
                () -> { }
        );

        service.open(null, "playlist", null);

        assertEquals(List.of("Playlist download service is unavailable."), statuses);
        assertFalse(completed.get());
    }

    private static PlaylistFlowService.BatchGateway availableGateway(
            boolean available,
            AtomicReference<PlaylistDialogService.Result> enqueued
    ) {
        return new PlaylistFlowService.BatchGateway() {
            @Override public boolean isAvailable() {
                return available;
            }

            @Override public void enqueue(PlaylistDialogService.Result result) {
                enqueued.set(result);
            }
        };
    }
}
