package com.grabx.app.grabx.core.service;

import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.input.Clipboard;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class ClipboardService {

    private final Parent root;
    private final Consumer<String> openAddLinkWithUrl;
    private final Predicate<String> isHttpUrl;
    private final Supplier<String> clipboardText;

    private String lastClipboardText = "";
    private ScheduledExecutorService clipboardPollExecutor;
    private volatile long generation;
    private final AtomicBoolean addLinkOpenScheduled = new AtomicBoolean(false);

    public ClipboardService(
            Parent root,
            Consumer<String> openAddLinkWithUrl,
            Predicate<String> isHttpUrl
    ) {
        this(root, openAddLinkWithUrl, isHttpUrl, ClipboardService::readClipboardTextSafe);
    }

    ClipboardService(Parent root, Consumer<String> openAddLinkWithUrl,
                     Predicate<String> isHttpUrl, Supplier<String> clipboardText) {
        this.root = root;
        this.openAddLinkWithUrl = openAddLinkWithUrl;
        this.isHttpUrl = isHttpUrl == null ? value -> false : isHttpUrl;
        this.clipboardText = clipboardText;
    }

    public void start() {
        if (root == null) return;

        if (Boolean.TRUE.equals(root.getProperties().get("gx-clip-listener"))) return;
        root.getProperties().put("gx-clip-listener", Boolean.TRUE);

        long currentGeneration = ++generation;
        // Poll independently of animation pulses, including when the app is minimized.
        // Clipboard access itself must remain on the JavaFX application thread.
        clipboardPollExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "grabx-clipboard");
            thread.setDaemon(true);
            return thread;
        });
        clipboardPollExecutor.scheduleWithFixedDelay(() -> {
            if (currentGeneration != generation || !addLinkOpenScheduled.compareAndSet(false, true)) return;
            Platform.runLater(() -> {
                try {
                    if (currentGeneration == generation) tick();
                } catch (Exception exception) {
                    com.grabx.app.grabx.util.AppLog.get(ClipboardService.class)
                            .warning("Clipboard check failed: " + exception.getClass().getSimpleName());
                } finally {
                    if (currentGeneration == generation) addLinkOpenScheduled.set(false);
                }
            });
        }, 0, 900, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        try {
            generation++;
            if (clipboardPollExecutor != null) clipboardPollExecutor.shutdownNow();
        } catch (Exception ignored) {
        } finally {
            clipboardPollExecutor = null;
            addLinkOpenScheduled.set(false);
            if (root != null) root.getProperties().remove("gx-clip-listener");
        }
    }

    private void tick() {
        String clip = clipboardText.get();
        if (clip == null) return;
        clip = clip.trim();
        if (clip.equals(lastClipboardText)) return;
        if (isHttpUrl.test(clip)) openAddLinkWithUrl.accept(clip);
        lastClipboardText = clip;
    }

    public static String readClipboardTextSafe() {
        try {
            Clipboard cb = Clipboard.getSystemClipboard();
            if (cb != null && cb.hasString()) return cb.getString().trim();
            if (cb != null && cb.hasUrl()) return cb.getUrl().trim();
        } catch (Exception ignored) {}
        return "";
    }

}
