package com.grabx.app.grabx.core.service;

import javafx.scene.Parent;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Creates the Add Link dialog and its navigation flow as one connected unit. */
public final class AddLinkServicesFactory {
    private AddLinkServicesFactory() {
    }

    public static Runtime create(
            Parent root,
            ScheduledExecutorService delayExecutor,
            Dependencies dependencies,
            AddLinkDialogService.Config config
    ) {
        AtomicReference<AddLinkFlowService> flowRef = new AtomicReference<>();
        AddLinkDialogService dialog;
        try {
            dialog = AddLinkDialogFactory.create(
                    root,
                    delayExecutor,
                    dependencies.defaultFolder(),
                    dependencies.saveFolder(),
                    dependencies.addDownload(),
                    (playlistUrl, folder) -> {
                        AddLinkFlowService flow = flowRef.get();
                        if (flow != null) flow.beginPlaylist(playlistUrl);
                        safeAccept(dependencies.openPlaylist(), playlistUrl, folder);
                    },
                    dependencies.statusUpdater(),
                    config
            );
        } catch (Exception ignored) {
            dialog = null;
        }

        if (dialog == null) return new Runtime(null, null);

        AddLinkDialogService connectedDialog = dialog;
        AddLinkFlowService flow = new AddLinkFlowService(
                new AddLinkFlowService.DialogGateway() {
                    @Override public boolean isOpen() {
                        return connectedDialog.isOpen();
                    }

                    @Override public void show(String prefillUrl) {
                        connectedDialog.show(prefillUrl);
                    }
                },
                dependencies.isHttpUrl(),
                dependencies.clipboardText(),
                (action, delay) -> delayExecutor.schedule(
                        action, delay, java.util.concurrent.TimeUnit.MILLISECONDS
                ),
                dependencies.uiExecutor(),
                dependencies.statusUpdater()
        );
        flowRef.set(flow);
        return new Runtime(dialog, flow);
    }

    private static <T, U> void safeAccept(BiConsumer<T, U> action, T first, U second) {
        try {
            if (action != null) action.accept(first, second);
        } catch (Exception ignored) {
        }
    }

    public record Runtime(AddLinkDialogService dialog, AddLinkFlowService flow) {
    }

    public record Dependencies(
            Supplier<String> defaultFolder,
            Consumer<String> saveFolder,
            AddLinkDialogFactory.QuadConsumer<String, String, String, String> addDownload,
            BiConsumer<String, String> openPlaylist,
            Consumer<String> statusUpdater,
            Predicate<String> isHttpUrl,
            Supplier<String> clipboardText,
            Consumer<Runnable> uiExecutor
    ) {
    }
}
