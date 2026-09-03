package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import javafx.collections.ObservableList;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Builds the cooperating services used to create, run, and bulk-manage downloads. */
public final class DownloadServicesFactory {
    private DownloadServicesFactory() {
    }

    public static Runtime create(
            ObservableList<DownloadRow> items,
            Map<DownloadRow, Process> activeProcesses,
            Map<DownloadRow, String> stopReasons,
            AtomicLong orderSequence,
            Dependencies dependencies,
            Config config
    ) {
        AtomicReference<DownloadRunner> runnerRef = new AtomicReference<>();
        BiConsumer<DownloadRow, Boolean> startDownload = (row, resume) -> {
            DownloadRunner runner = runnerRef.get();
            if (runner != null) runner.start(row, resume);
        };

        DownloadEngineFactory.Runtime engine = DownloadEngineFactory.create(
                items,
                activeProcesses,
                stopReasons,
                new DownloadProgressTracker(),
                startDownload,
                dependencies.saveHistory(),
                dependencies.refreshMissing(),
                dependencies.refilter(),
                new DownloadEngineFactory.Config(
                        config.audioMode(),
                        config.bestQuality(),
                        config.qualitySeparator(),
                        config.bestAudio(),
                        config.defaultAudioFormat()
                )
        );
        runnerRef.set(engine.runner());

        DownloadQueueService queue = new DownloadQueueService(
                items,
                orderSequence,
                dependencies.defaultFolder(),
                dependencies.attachHistory(),
                dependencies.saveHistory(),
                dependencies.applyThumbnail(),
                dependencies.resolveTitle(),
                startDownload,
                dependencies.uiExecutor(),
                dependencies.statusUpdater(),
                config.videoMode(),
                config.audioMode(),
                config.bestQuality(),
                config.defaultAudioFormat()
        );

        ClearAllService clearAll = new ClearAllService(items);
        DownloadStateCoordinator state = engine.stateCoordinator();
        BooleanSupplier itemsEmpty = items == null ? () -> true : items::isEmpty;
        BulkDownloadActionsService bulkActions = new BulkDownloadActionsService(
                state::cancelAll,
                state::pauseAll,
                state::resumeAll,
                clearAll::clearNonActive,
                itemsEmpty,
                dependencies.refreshMissing(),
                dependencies.refilter(),
                dependencies.clearHistory(),
                dependencies.saveHistory(),
                dependencies.statusUpdater()
        );

        return new Runtime(state, engine.runner(), queue, bulkActions);
    }

    public record Runtime(
            DownloadStateCoordinator stateCoordinator,
            DownloadRunner runner,
            DownloadQueueService queue,
            BulkDownloadActionsService bulkActions
    ) {
    }

    public record Dependencies(
            Supplier<String> defaultFolder,
            Consumer<DownloadRow> attachHistory,
            Runnable saveHistory,
            BiConsumer<DownloadRow, String> applyThumbnail,
            BiConsumer<DownloadRow, String> resolveTitle,
            Consumer<Runnable> uiExecutor,
            Consumer<String> statusUpdater,
            Runnable refreshMissing,
            Runnable refilter,
            Runnable clearHistory
    ) {
    }

    public record Config(
            String videoMode,
            String audioMode,
            String bestQuality,
            String qualitySeparator,
            String bestAudio,
            String defaultAudioFormat
    ) {
    }
}
