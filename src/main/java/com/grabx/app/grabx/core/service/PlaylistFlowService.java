package com.grabx.app.grabx.core.service;

import javafx.stage.Window;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** Coordinates the playlist dialog result with queueing and add-link navigation. */
public final class PlaylistFlowService {
    private final DialogGateway dialog;
    private final BatchGateway batchGateway;
    private final Supplier<String> defaultFolder;
    private final Consumer<String> saveFolder;
    private final Consumer<String> statusUpdater;
    private final Runnable completePlaylist;
    private final Runnable returnFromPlaylist;

    public PlaylistFlowService(
            DialogGateway dialog,
            BatchGateway batchGateway,
            Supplier<String> defaultFolder,
            Consumer<String> saveFolder,
            Consumer<String> statusUpdater,
            Runnable completePlaylist,
            Runnable returnFromPlaylist
    ) {
        this.dialog = dialog;
        this.batchGateway = batchGateway;
        this.defaultFolder = defaultFolder;
        this.saveFolder = saveFolder;
        this.statusUpdater = statusUpdater;
        this.completePlaylist = completePlaylist;
        this.returnFromPlaylist = returnFromPlaylist;
    }

    public void open(Window owner, String playlistUrl, String folder) {
        String playlistFolder = (folder == null || folder.isBlank())
                ? safeGet(defaultFolder)
                : folder;

        PlaylistDialogService.Result result = dialog.show(owner, playlistUrl, playlistFolder);
        if (result == null) return;

        if (result.action() == PlaylistDialogService.Action.DOWNLOAD) {
            safeAccept(saveFolder, result.folder());
            if (batchGateway == null || !batchGateway.isAvailable()) {
                safeAccept(statusUpdater, "Playlist download service is unavailable.");
                return;
            }

            batchGateway.enqueue(result);
            safeAccept(statusUpdater, "Queued playlist: " + result.batch().size() + " items");
            safeRun(completePlaylist);
            return;
        }

        if (result.action() == PlaylistDialogService.Action.BACK) {
            safeRun(returnFromPlaylist);
        }
    }

    private static String safeGet(Supplier<String> supplier) {
        try {
            return supplier == null ? null : supplier.get();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void safeRun(Runnable action) {
        try {
            if (action != null) action.run();
        } catch (Exception ignored) {
        }
    }

    private static <T> void safeAccept(Consumer<T> action, T value) {
        try {
            if (action != null) action.accept(value);
        } catch (Exception ignored) {
        }
    }

    @FunctionalInterface
    public interface DialogGateway {
        PlaylistDialogService.Result show(Window owner, String playlistUrl, String folder);
    }

    public interface BatchGateway {
        boolean isAvailable();

        void enqueue(PlaylistDialogService.Result result);
    }
}
