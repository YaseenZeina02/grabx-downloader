package com.grabx.app.grabx.core.service;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Builds the Add Link dialog and adapts application callbacks to its UI API. */
public final class AddLinkDialogFactory {
    @FunctionalInterface
    public interface QuadConsumer<A, B, C, D> {
        void accept(A first, B second, C third, D fourth);
    }

    private AddLinkDialogFactory() {
    }

    public static AddLinkDialogService create(
            Parent root,
            ScheduledExecutorService delayExecutor,
            Supplier<String> defaultFolder,
            Consumer<String> saveFolder,
            QuadConsumer<String, String, String, String> addDownload,
            BiConsumer<String, String> openPlaylist,
            Consumer<String> statusUpdater,
            AddLinkDialogService.Config config
    ) {
        if (root == null) return null;
        return new AddLinkDialogService(
                root,
                delayExecutor,
                new AddLinkDialogService.Callbacks() {
                    @Override public void installClickToDefocus(DialogPane pane) {
                        AddLinkDialogFactory.installClickToDefocus(pane);
                    }

                    @Override public void bringWindowToFront(Window window) {
                        AddLinkDialogFactory.bringWindowToFront(window);
                    }

                    @Override public String shorten(String value) {
                        return DownloadTitleService.shorten(value);
                    }

                    @Override public String getLastDownloadFolderOrDefault() {
                        return defaultFolder == null ? "" : defaultFolder.get();
                    }

                    @Override public void saveLastDownloadFolder(String folder) {
                        if (saveFolder != null) saveFolder.accept(folder);
                    }

                    @Override public void addDownloadItemToList(String url, String folder, String mode, String quality) {
                        if (addDownload != null) addDownload.accept(url, folder, mode, quality);
                    }

                    @Override public void onPlaylistDetected(String playlistUrl, String folder) {
                        if (openPlaylist != null) openPlaylist.accept(playlistUrl, folder);
                    }

                    @Override public void setStatusText(String text) {
                        if (statusUpdater != null && text != null) statusUpdater.accept(text);
                    }
                },
                config
        );
    }

    public static AddLinkDialogService.Config defaultConfig(
            String videoMode,
            String audioMode,
            String bestQuality,
            String qualitySeparator,
            String defaultAudioFormat,
            List<String> audioFormats
    ) {
        return new AddLinkDialogService.Config(
                videoMode,
                audioMode,
                bestQuality,
                qualitySeparator,
                defaultAudioFormat,
                audioFormats,
                "/com/grabx/app/grabx/styles/theme-base.css",
                "/com/grabx/app/grabx/styles/layout.css",
                "/com/grabx/app/grabx/styles/buttons.css",
                "/com/grabx/app/grabx/styles/sidebar.css"
        );
    }

    public static void installClickToDefocus(Node rootNode) {
        if (rootNode == null) return;
        rootNode.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            try {
                Scene scene = rootNode.getScene();
                if (scene != null && scene.getFocusOwner() instanceof TextInputControl) {
                    rootNode.requestFocus();
                }
            } catch (Exception ignored) {
            }
        });
    }

    private static void bringWindowToFront(Window window) {
        if (window == null) return;
        try {
            window.requestFocus();
            if (window instanceof Stage stage) {
                stage.toFront();
                stage.requestFocus();
            }
        } catch (Exception ignored) {
        }
    }
}
