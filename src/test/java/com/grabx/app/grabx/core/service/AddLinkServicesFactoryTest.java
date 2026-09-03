package com.grabx.app.grabx.core.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertNull;

class AddLinkServicesFactoryTest {
    @Test
    void returnsUnavailableRuntimeWhenRootIsMissing() {
        var executor = Executors.newSingleThreadScheduledExecutor();
        try {
            AddLinkServicesFactory.Runtime runtime = AddLinkServicesFactory.create(
                    null,
                    executor,
                    new AddLinkServicesFactory.Dependencies(
                            () -> "/downloads",
                            folder -> { },
                            (url, folder, mode, quality) -> { },
                            (url, folder) -> { },
                            text -> { },
                            value -> true,
                            () -> "",
                            Runnable::run
                    ),
                    AddLinkDialogFactory.defaultConfig(
                            "Video", "Audio only", "Best quality", "---", "mp3", List.of("mp3")
                    )
            );

            assertNull(runtime.dialog());
            assertNull(runtime.flow());
        } finally {
            executor.shutdownNow();
        }
    }
}
