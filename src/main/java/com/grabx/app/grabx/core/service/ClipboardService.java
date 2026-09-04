package com.grabx.app.grabx.core.service;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.input.Clipboard;
import javafx.util.Duration;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class ClipboardService {

    private final Parent root;
    private final Consumer<String> openAddLinkWithUrl;
    private final Predicate<String> isHttpUrl;

    private String lastClipboardText = "";
    private Timeline clipboardPollTimeline;
    private final AtomicBoolean addLinkOpenScheduled = new AtomicBoolean(false);

    public ClipboardService(
            Parent root,
            Consumer<String> openAddLinkWithUrl,
            Predicate<String> isHttpUrl
    ) {
        this.root = root;
        this.openAddLinkWithUrl = openAddLinkWithUrl;
        this.isHttpUrl = isHttpUrl == null ? value -> false : isHttpUrl;
    }

    public void start() {
        if (root == null) return;

        if (Boolean.TRUE.equals(root.getProperties().get("gx-clip-listener"))) return;
        root.getProperties().put("gx-clip-listener", Boolean.TRUE);

        clipboardPollTimeline = new Timeline(
                new KeyFrame(Duration.millis(900), e -> tick())
        );
        clipboardPollTimeline.setCycleCount(Animation.INDEFINITE);
        clipboardPollTimeline.play();
        Platform.runLater(this::tick);
    }

    public void stop() {
        try {
            if (clipboardPollTimeline != null) clipboardPollTimeline.stop();
        } catch (Exception ignored) {
        } finally {
            clipboardPollTimeline = null;
            addLinkOpenScheduled.set(false);
            if (root != null) root.getProperties().remove("gx-clip-listener");
        }
    }

    private void tick() {
        String clip = readClipboardTextSafe();
        if (clip.equals(lastClipboardText)) return;
        lastClipboardText = clip;

        if (!isHttpUrl.test(clip)) return;

        if (!addLinkOpenScheduled.compareAndSet(false, true)) return;

        final String captured = clip;
        Platform.runLater(() -> {
            try {
                openAddLinkWithUrl.accept(captured);
            } finally {
                addLinkOpenScheduled.set(false);
            }
        });
    }

    public static String readClipboardTextSafe() {
        try {
            Clipboard cb = Clipboard.getSystemClipboard();
            if (cb != null && cb.hasString()) return cb.getString().trim();
        } catch (Exception ignored) {}
        return "";
    }

}
