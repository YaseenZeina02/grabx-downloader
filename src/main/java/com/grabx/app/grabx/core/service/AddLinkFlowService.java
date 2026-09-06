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
        default void closeIfUrlMatches(String url) { }

        default void show(String prefillUrl, String preferredAction) {
            show(prefillUrl);
        }

        default void show(String prefillUrl, String preferredAction, boolean autoAnalyze) {
            show(prefillUrl, preferredAction);
        }

        default void show(String prefillUrl, String preferredAction, boolean autoAnalyze, String preferredFolder) {
            show(prefillUrl, preferredAction, autoAnalyze);
        }
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
    private String suppressedClipboard;
    private String automaticClipboardUrl;
    private long clipboardGeneration;

    public void openFromClipboardMonitor(String url) {
        String normalized = normalizeUrl(url);
        if (normalized == null || normalized.equals(suppressedClipboard)) return;
        suppressedClipboard = null;
        long generation = ++clipboardGeneration;
        schedule(() -> uiExecutor.accept(() -> {
            if (generation != clipboardGeneration || dialog == null || dialog.isOpen()) return;
            automaticClipboardUrl = normalized;
            dialog.show(normalized);
        }));
    }

    public void browserDownloadStarted() {
        clipboardGeneration++;
        suppressedClipboard = normalizeUrl(safeClipboardText());
        if (dialog != null && automaticClipboardUrl != null) {
            dialog.closeIfUrlMatches(automaticClipboardUrl);
        }
        automaticClipboardUrl = null;
    }

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
        clipboardGeneration++;
        automaticClipboardUrl = null;
        if (dialog == null) {
            if (statusUpdater != null) statusUpdater.accept("Add link service is not ready");
            return;
        }
        dialog.show(normalizeUrl(prefillUrl));
    }

    public void openOrUpdate(String prefillUrl) {
        openOrUpdate(prefillUrl, "ask");
    }

    public void openOrUpdate(String prefillUrl, String preferredAction) {
        openOrUpdate(prefillUrl, preferredAction, false);
    }

    public void openOrUpdate(String prefillUrl, String preferredAction, boolean autoAnalyze) {
        openOrUpdate(prefillUrl, preferredAction, autoAnalyze, null);
    }

    public void openOrUpdate(String prefillUrl, String preferredAction, boolean autoAnalyze, String preferredFolder) {
        clipboardGeneration++;
        automaticClipboardUrl = null;
        String normalizedUrl = normalizeUrl(prefillUrl);
        if (dialog != null && dialog.isOpen()) {
            dialog.show(normalizedUrl, preferredAction, autoAnalyze, preferredFolder);
            return;
        }
        if (!openScheduled.compareAndSet(false, true)) return;
        schedule(() -> uiExecutor.accept(() -> {
            try {
                if (dialog != null) dialog.show(normalizedUrl, preferredAction, autoAnalyze, preferredFolder);
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
