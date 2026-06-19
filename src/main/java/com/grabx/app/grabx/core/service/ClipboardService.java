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
import java.util.function.Supplier;

public final class ClipboardService {

    private final Parent root;
    private final Consumer<String> openAddLinkWithUrl;
    private final Consumer<String> updateAddLinkUrl;
    private final Supplier<Boolean> isDialogOpen;

    private String lastClipboardText = "";
    private Timeline clipboardPollTimeline;
    private final AtomicBoolean addLinkOpenScheduled = new AtomicBoolean(false);

    public ClipboardService(
            Parent root,
            Consumer<String> openAddLinkWithUrl,
            Consumer<String> updateAddLinkUrl,
            Supplier<Boolean> isDialogOpen
    ) {
        this.root = root;
        this.openAddLinkWithUrl = openAddLinkWithUrl;
        this.updateAddLinkUrl = updateAddLinkUrl;
        this.isDialogOpen = isDialogOpen;
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
    }

    private void tick() {
        String clip = readClipboardTextSafe();
        if (clip.equals(lastClipboardText)) return;
        lastClipboardText = clip;

        if (!isHttpUrl(clip)) return;

        if (isDialogOpen.get()) {
            updateAddLinkUrl.accept(clip);
            return;
        }

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

    private static String readClipboardTextSafe() {
        try {
            Clipboard cb = Clipboard.getSystemClipboard();
            if (cb != null && cb.hasString()) return cb.getString().trim();
        } catch (Exception ignored) {}
        return "";
    }

    private static boolean isHttpUrl(String s) {
        if (s == null) return false;
        String ss = s.trim().toLowerCase();
        return ss.startsWith("http://") || ss.startsWith("https://");
    }
}