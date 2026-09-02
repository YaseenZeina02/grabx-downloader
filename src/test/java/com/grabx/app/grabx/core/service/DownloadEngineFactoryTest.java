package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import javafx.collections.FXCollections;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class DownloadEngineFactoryTest {
    @Test
    void createsTheEngineRuntimeComponents() {
        DownloadEngineFactory.Runtime runtime = DownloadEngineFactory.create(
                FXCollections.observableArrayList(),
                new ConcurrentHashMap<DownloadRow, Process>(),
                new ConcurrentHashMap<>(),
                new DownloadProgressTracker(),
                (row, resume) -> { },
                () -> { },
                () -> { },
                () -> { },
                new DownloadEngineFactory.Config(
                        "Audio only", "Best quality", "---", "Best audio", "mp3"
                )
        );

        assertNotNull(runtime.stateCoordinator());
        assertNotNull(runtime.runner());
    }
}
