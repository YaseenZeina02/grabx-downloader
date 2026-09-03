package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Builds the playlist batch and dialog-flow services as one connected runtime. */
public final class PlaylistServicesFactory {
    private PlaylistServicesFactory() {
    }

    public static Runtime create(
            PlaylistFlowService.DialogGateway dialog,
            Dependencies dependencies
    ) {
        PlaylistBatchCoordinator batchCoordinator;
        try {
            batchCoordinator = new PlaylistBatchCoordinator(
                    dependencies.createRow(),
                    dependencies.addRow(),
                    dependencies.applyThumbnail(),
                    dependencies.attachHistory(),
                    dependencies.saveHistory(),
                    dependencies.startDownload(),
                    dependencies.refilter(),
                    dependencies.uiExecutor(),
                    dependencies.watchUrl(),
                    dependencies.extractVideoId(),
                    dependencies.statusUpdater()
            );
        } catch (Exception ignored) {
            batchCoordinator = null;
        }

        PlaylistBatchCoordinator connectedBatch = batchCoordinator;
        PlaylistFlowService flow = new PlaylistFlowService(
                dialog,
                new PlaylistFlowService.BatchGateway() {
                    @Override public boolean isAvailable() {
                        return connectedBatch != null;
                    }

                    @Override public void enqueue(PlaylistDialogService.Result result) {
                        connectedBatch.enqueue(result.batch(), result.mode(), result.quality());
                    }
                },
                dependencies.defaultFolder(),
                dependencies.saveFolder(),
                dependencies.statusUpdater(),
                dependencies.completePlaylist(),
                dependencies.returnFromPlaylist()
        );
        return new Runtime(batchCoordinator, flow);
    }

    public record Runtime(PlaylistBatchCoordinator batchCoordinator, PlaylistFlowService flow) {
    }

    public record Dependencies(
            PlaylistBatchService.QuadFunction<String, String, String, String, DownloadRow> createRow,
            Consumer<DownloadRow> addRow,
            BiConsumer<DownloadRow, String> applyThumbnail,
            Consumer<DownloadRow> attachHistory,
            Runnable saveHistory,
            Consumer<DownloadRow> startDownload,
            Runnable refilter,
            Consumer<Runnable> uiExecutor,
            Function<String, String> watchUrl,
            Function<String, String> extractVideoId,
            Consumer<String> statusUpdater,
            Supplier<String> defaultFolder,
            Consumer<String> saveFolder,
            Runnable completePlaylist,
            Runnable returnFromPlaylist
    ) {
    }
}
