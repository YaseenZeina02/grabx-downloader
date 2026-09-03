package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import javafx.collections.FXCollections;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class DownloadServicesFactoryTest {
    @Test
    void createsAllDownloadServicesAsOneRuntime() {
        DownloadServicesFactory.Runtime runtime = DownloadServicesFactory.create(
                FXCollections.observableArrayList(),
                new ConcurrentHashMap<DownloadRow, Process>(),
                new ConcurrentHashMap<>(),
                new AtomicLong(),
                new DownloadServicesFactory.Dependencies(
                        () -> "/downloads",
                        row -> { },
                        () -> { },
                        (row, url) -> { },
                        (row, url) -> { },
                        Runnable::run,
                        text -> { },
                        () -> { },
                        () -> { },
                        () -> { }
                ),
                new DownloadServicesFactory.Config(
                        "Video", "Audio only", "Best quality", "---", "Best audio", "mp3"
                )
        );

        assertNotNull(runtime.stateCoordinator());
        assertNotNull(runtime.runner());
        assertNotNull(runtime.queue());
        assertNotNull(runtime.bulkActions());
    }
}
