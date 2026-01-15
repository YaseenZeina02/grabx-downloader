package com.grabx.app.grabx.ui.components;

import javafx.animation.PauseTransition;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

public final class HoverBubble {
    private final Pane layer;
    private final StackPane bubble;
    private final Label label;
    private Node currentOwner;
    private boolean sceneHooksInstalled = false;

    // Match your old behavior (same delays you had)
    private final PauseTransition showTimer = new PauseTransition(Duration.millis(160));
    private final PauseTransition hideTimer = new PauseTransition(Duration.millis(180));

    public HoverBubble(Pane layer) {
        this.layer = layer;

        label = new Label();
        label.setWrapText(false);
        label.setMaxWidth(Double.MAX_VALUE);
        label.getStyleClass().add("gx-hoverlabel");

        bubble = new StackPane(label);
        bubble.getStyleClass().add("gx-hoverbubble");

        // Key to kill jitter: bubble must NOT capture mouse events
        bubble.setMouseTransparent(true);
        // Overlay node: do not affect layout
        bubble.setManaged(false);
        // Ensure it renders above everything
        bubble.setViewOrder(-10_000);
        bubble.setVisible(false);

        layer.getChildren().add(bubble);
    }

    private void ensureSceneHooks(Scene sc) {
        if (sc == null || sceneHooksInstalled) return;
        sceneHooksInstalled = true;

        // Global guards to avoid stuck tooltips when owner disappears/recycles (ListCell) or when mouse leaves.
        sc.addEventFilter(MouseEvent.MOUSE_MOVED, e -> {
            if (currentOwner == null) return;
            if (currentOwner.getScene() == null || !currentOwner.isVisible() || !currentOwner.isHover()) {
                hide();
                return;
            }
            position(currentOwner);
        });

        sc.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> hide());

        // Hide when window loses focus (optional polish)
        try {
            if (sc.getWindow() != null) {
                sc.getWindow().focusedProperty().addListener((o, a, b) -> {
                    if (b == null || !b) hide();
                });
            }
        } catch (Exception ignored) {
        }
    }

    public void install(Button btn, String text) {
        if (btn == null) return;

        // Prevent attaching multiple listeners when cells are recycled / updateItem runs often
        if (Boolean.TRUE.equals(btn.getProperties().get("gx-hover-installed"))) {
            btn.getProperties().put("gx-hover-text", text);
            return;
        }
        btn.getProperties().put("gx-hover-installed", Boolean.TRUE);
        btn.getProperties().put("gx-hover-text", text);

        // Ensure button hover is stable (icons do not steal events)
        btn.setPickOnBounds(true);
        Node g = btn.getGraphic();
        if (g != null) g.setMouseTransparent(true);

        btn.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_ENTERED, e -> {
            hideTimer.stop();
            showTimer.stop();
            showTimer.setOnFinished(ev -> {
                if (!btn.isHover()) return;
                String txt = (String) btn.getProperties().get("gx-hover-text");
                show(btn, txt);
            });
            showTimer.playFromStart();
        });

        btn.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_EXITED, e -> {
            showTimer.stop();
            hideTimer.stop();
            hideTimer.setOnFinished(ev -> hide());
            hideTimer.playFromStart();
        });

        // Clicking should hide immediately
        btn.armedProperty().addListener((obs, wasArmed, isArmed) -> {
            if (isArmed) {
                showTimer.stop();
                hideTimer.stop();
                hide();
            }
        });
    }

    private void show(Node owner, String text) {
        if (owner == null || owner.getScene() == null) return;

        label.setText(text == null ? "" : text);
        currentOwner = owner;

        bubble.setVisible(true);
        // Important: node is unmanaged inside a Pane, so we must autosize manually
        bubble.applyCss();
        bubble.autosize();

        ensureSceneHooks(owner.getScene());
        position(owner);
    }

    private void hide() {
        bubble.setVisible(false);
        currentOwner = null;
    }

    private void position(Node owner) {
        if (!bubble.isVisible() || owner == null || owner.getScene() == null) return;

        Bounds b = owner.localToScene(owner.getBoundsInLocal());
        if (b == null) return;

        bubble.applyCss();
        bubble.autosize();
        double bubbleW = bubble.getLayoutBounds().getWidth();
        double bubbleH = bubble.getLayoutBounds().getHeight();

        double targetX = b.getMinX() + (b.getWidth() - bubbleW) / 2.0;
        double targetY = b.getMaxY() + 6; // same feel you had

        double sceneW = owner.getScene().getWidth();
        double sceneH = owner.getScene().getHeight();
        double pad = 6;

        if (targetX + bubbleW > sceneW - pad) targetX = sceneW - pad - bubbleW;
        if (targetX < pad) targetX = pad;

        // Flip above if bottom overflow
        if (targetY + bubbleH > sceneH - pad) {
            targetY = b.getMinY() - bubbleH - 10;
        }
        if (targetY < pad) targetY = pad;

        bubble.relocate(targetX, targetY);
    }
}