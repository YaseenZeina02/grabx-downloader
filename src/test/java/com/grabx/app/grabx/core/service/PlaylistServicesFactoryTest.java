package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class PlaylistServicesFactoryTest {
    @Test
    void createsConnectedPlaylistServices() {
        AtomicLong order = new AtomicLong();
        PlaylistServicesFactory.Runtime runtime = PlaylistServicesFactory.create(
                (owner, url, folder) -> PlaylistDialogService.Result.none(),
                new PlaylistServicesFactory.Dependencies(
                        (url, mode, quality, title) -> new DownloadRow(
                                url, title, order.getAndIncrement(), "/downloads", mode, quality
                        ),
                        row -> { },
                        (row, url) -> { },
                        row -> { },
                        () -> { },
                        row -> { },
                        () -> { },
                        Runnable::run,
                        id -> "https://example.test/watch?v=" + id,
                        url -> url,
                        text -> { },
                        () -> "/downloads",
                        folder -> { },
                        () -> { },
                        () -> { }
                )
        );

        assertNotNull(runtime.batchCoordinator());
        assertNotNull(runtime.flow());
    }
}
