package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import com.grabx.app.grabx.util.DownloadRuntimeUtils;
import com.grabx.app.grabx.util.VideoQualityUtils;
import javafx.collections.ObservableList;

import java.util.Map;
import java.util.function.BiConsumer;

/** Creates the state coordinator and runner used by the download engine. */
public final class DownloadEngineFactory {
    private DownloadEngineFactory() {
    }

    public static Runtime create(
            ObservableList<DownloadRow> items,
            Map<DownloadRow, Process> activeProcesses,
            Map<DownloadRow, String> stopReasons,
            DownloadProgressTracker progressTracker,
            BiConsumer<DownloadRow, Boolean> startDownload,
            Runnable saveHistory,
            Runnable refreshMissing,
            Runnable refilter,
            Config config
    ) {
        Runnable stateChanged = () -> {
            safeRun(refreshMissing);
            safeRun(refilter);
        };

        DownloadStateCoordinator stateCoordinator = new DownloadStateCoordinator(
                items,
                activeProcesses,
                stopReasons,
                startDownload,
                stateChanged
        );

        DownloadRunner runner = new DownloadRunner(
                activeProcesses,
                stopReasons,
                progressTracker.progressByRow(),
                saveHistory::run,
                refreshMissing,
                refilter,
                label -> VideoQualityUtils.parseHeight(String.valueOf(label)),
                DownloadRuntimeUtils::probeOutputFilename,
                DownloadRuntimeUtils::supportsAudioThumbnailEmbedding,
                DownloadRuntimeUtils::isAudioStreamFromDestinationLine,
                DownloadRuntimeUtils::parseLongSafe,
                DownloadRuntimeUtils::formatBytesDecimal,
                DownloadRuntimeUtils::normalizeSpeedUnit,
                progressTracker::applyMonotonic,
                DownloadRuntimeUtils::killProcessTree,
                config.audioMode(),
                config.bestQuality(),
                config.qualitySeparator(),
                config.bestAudio(),
                config.defaultAudioFormat()
        );

        return new Runtime(stateCoordinator, runner);
    }

    private static void safeRun(Runnable action) {
        try {
            if (action != null) action.run();
        } catch (Exception ignored) {
        }
    }

    public record Runtime(DownloadStateCoordinator stateCoordinator, DownloadRunner runner) {
    }

    public record Config(
            String audioMode,
            String bestQuality,
            String qualitySeparator,
            String bestAudio,
            String defaultAudioFormat
    ) {
    }
}
