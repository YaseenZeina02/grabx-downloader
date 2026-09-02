package com.grabx.app.grabx.core.service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Coordinates opening and resuming the Add Link dialog without owning its UI. */
public final class AddLinkFlowService {
    public interface DialogGateway {
        boolean isOpen();
        void show(String prefillUrl);
    }

    private static final long OPEN_DELAY_MILLIS = 80;

    private final DialogGateway dialog;
    private final Predicate<String> isHttpUrl;
    private final Supplier<String> clipboardText;
    private final BiConsumer<Runnable, Long> delayScheduler;
    private final Consumer<Runnable> uiExecutor;
    private final Consumer<String> statusUpdater;
    private final AtomicBoolean openScheduled = new AtomicBoolean(false);

    private volatile boolean returnAfterPlaylist;
    private volatile String playlistUrl;

    public AddLinkFlowService(
            DialogGateway dialog,
            Predicate<String> isHttpUrl,
            Supplier<String> clipboardText,
            BiConsumer<Runnable, Long> delayScheduler,
            Consumer<Runnable> uiExecutor,
            Consumer<String> statusUpdater
    ) {
        this.dialog = dialog;
        this.isHttpUrl = isHttpUrl == null ? value -> false : isHttpUrl;
        this.clipboardText = clipboardText == null ? () -> "" : clipboardText;
        this.delayScheduler = delayScheduler;
        this.uiExecutor = uiExecutor == null ? Runnable::run : uiExecutor;
        this.statusUpdater = statusUpdater;
    }

    public void showFromClipboard() {
        show(normalizeUrl(safeClipboardText()));
    }

    public void showFromClipboardDeferred() {
        showDeferred(normalizeUrl(safeClipboardText()));
    }

    public void show(String prefillUrl) {
        if (dialog == null) {
            if (statusUpdater != null) statusUpdater.accept("Add link service is not ready");
            return;
        }
        dialog.show(normalizeUrl(prefillUrl));
    }

    public void openOrUpdate(String prefillUrl) {
        String normalizedUrl = normalizeUrl(prefillUrl);
        if (dialog != null && dialog.isOpen()) {
            dialog.show(normalizedUrl);
            return;
        }
        if (!openScheduled.compareAndSet(false, true)) return;
        schedule(() -> uiExecutor.accept(() -> {
            try {
                show(normalizedUrl);
            } finally {
                openScheduled.set(false);
            }
        }));
    }

    public void beginPlaylist(String url) {
        returnAfterPlaylist = true;
        playlistUrl = url;
    }

    public void completePlaylist() {
        returnAfterPlaylist = false;
        playlistUrl = null;
    }

    public void returnFromPlaylist() {
        if (!returnAfterPlaylist) return;
        String url = playlistUrl;
        completePlaylist();
        uiExecutor.accept(() -> openOrUpdate(url));
    }

    private void showDeferred(String prefillUrl) {
        schedule(() -> uiExecutor.accept(() -> show(prefillUrl)));
    }

    private void schedule(Runnable action) {
        if (delayScheduler == null) action.run();
        else delayScheduler.accept(action, OPEN_DELAY_MILLIS);
    }

    private String safeClipboardText() {
        try {
            return clipboardText.get();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String normalizeUrl(String value) {
        String candidate = value == null ? null : value.trim();
        return candidate != null && isHttpUrl.test(candidate) ? candidate : null;
    }
}
