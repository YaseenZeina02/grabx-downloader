package com.grabx.app.grabx.ui.components;

import javafx.animation.PauseTransition;
import javafx.animation.AnimationTimer;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.beans.value.ObservableValue;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Text;
import javafx.util.Duration;
import javafx.scene.robot.Robot;

public final class HoverBubble {
    private final Pane layer;
    private final StackPane bubble;
    private final Label label;
    private Node currentOwner;
    private boolean sceneHooksInstalled = false;
    private Robot pointerRobot;
    private final AnimationTimer pointerGuard = new AnimationTimer() {
        private long lastCheckNs;

        @Override
        public void handle(long now) {
            // Around 20 checks/second is responsive without doing needless work.
            if (now - lastCheckNs < 50_000_000L) return;
            lastCheckNs = now;

            Node owner = currentOwner;
            if (owner == null || pointerRobot == null || owner.getScene() == null) {
                stop();
                return;
            }

            try {
                Point2D pointer = pointerRobot.getMousePosition();
                Bounds ownerOnScreen = owner.localToScreen(owner.getBoundsInLocal());
                if (pointer == null || ownerOnScreen == null
                        || !ownerOnScreen.contains(pointer.getX(), pointer.getY())) {
                    hide();
                }
            } catch (Exception ignored) {
                // Event-based guards remain active if Robot is unavailable.
            }
        }

        @Override
        public void stop() {
            super.stop();
            lastCheckNs = 0;
        }
    };

    // Match your old behavior (same delays you had)
    private final PauseTransition showTimer = new PauseTransition(Duration.millis(160));
    private final PauseTransition hideTimer = new PauseTransition(Duration.millis(180));

    public HoverBubble(Pane layer) {
        this.layer = layer;

        try {
            pointerRobot = new Robot();
        } catch (Exception ignored) {
            pointerRobot = null;
        }

        label = new Label();
        label.setWrapText(true);
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
            if (currentOwner.getScene() == null || !currentOwner.isVisible()
                    || !containsScenePoint(currentOwner, e.getSceneX(), e.getSceneY())) {
                hide();
                return;
            }
            position(currentOwner);
        });

        // On macOS a fast move from the owner straight to the Dock can skip the
        // owner's normal MOUSE_EXITED update. Detect crossing the scene bounds
        // as a second, immediate guard so an in-scene bubble can never remain
        // stuck after the pointer has left GrabX.
        sc.addEventFilter(MouseEvent.MOUSE_EXITED, e -> {
            double x = e.getSceneX();
            double y = e.getSceneY();
            if (x <= 0 || y <= 0 || x >= sc.getWidth() || y >= sc.getHeight()) {
                showTimer.stop();
                hideTimer.stop();
                hide();
            }
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
        install(btn, new javafx.beans.property.SimpleStringProperty(text));
    }

    public void install(Node owner, ObservableValue<String> text) {
        if (owner == null) return;

        // Prevent attaching multiple listeners when cells are recycled / updateItem runs often
        if (Boolean.TRUE.equals(owner.getProperties().get("gx-hover-installed"))) {
            owner.getProperties().put("gx-hover-value", text);
            return;
        }
        owner.getProperties().put("gx-hover-installed", Boolean.TRUE);
        owner.getProperties().put("gx-hover-value", text);

        // Ensure button hover is stable (icons do not steal events)
        owner.setPickOnBounds(true);
        if (owner instanceof Button btn) {
            Node g = btn.getGraphic();
            if (g != null) g.setMouseTransparent(true);
        }

        owner.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_ENTERED, e -> {
            hideTimer.stop();
            showTimer.stop();
            showTimer.setOnFinished(ev -> {
                if (!owner.isHover()) return;
                // A title bubble is useful only when the label has actually
                // been shortened by its available layout width.
                if (owner instanceof Label ownerLabel && !isTextTruncated(ownerLabel)) return;
                Object value = owner.getProperties().get("gx-hover-value");
                String txt = value instanceof ObservableValue<?> observable
                        ? String.valueOf(observable.getValue()) : "";
                show(owner, txt);
            });
            showTimer.playFromStart();
        });

        owner.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_EXITED, e -> {
            showTimer.stop();
            hideTimer.stop();
            hideTimer.setOnFinished(ev -> hide());
            hideTimer.playFromStart();
        });

        // Clicking should hide immediately
        if (owner instanceof Button btn) {
            btn.armedProperty().addListener((obs, wasArmed, isArmed) -> {
                if (isArmed) {
                    showTimer.stop();
                    hideTimer.stop();
                    hide();
                }
            });
        }
    }

    private static boolean isTextTruncated(Label owner) {
        String value = owner.getText();
        if (value == null || value.isBlank() || owner.getWidth() <= 0) return false;

        Text measurement = new Text(value);
        measurement.setFont(owner.getFont());

        double availableWidth = owner.getWidth()
                - owner.getInsets().getLeft()
                - owner.getInsets().getRight();
        Node graphic = owner.getGraphic();
        if (graphic != null) {
            availableWidth -= graphic.getLayoutBounds().getWidth() + owner.getGraphicTextGap();
        }

        return isOverflowing(measurement.getLayoutBounds().getWidth(), availableWidth);
    }

    static boolean isOverflowing(double textWidth, double availableWidth) {
        return availableWidth > 0 && textWidth > availableWidth + 1.0;
    }

    private void show(Node owner, String text) {
        if (owner == null || owner.getScene() == null) return;

        label.setText(text == null ? "" : text);
        // Keep long titles inside the application instead of creating an
        // oversized popup-like strip beyond the window edge.
        double availableWidth = Math.max(180, owner.getScene().getWidth() - 32);
        label.setMaxWidth(Math.min(720, availableWidth));
        bubble.setMaxWidth(Math.min(744, availableWidth));
        currentOwner = owner;

        bubble.setVisible(true);
        // Important: node is unmanaged inside a Pane, so we must autosize manually
        bubble.applyCss();
        bubble.autosize();

        ensureSceneHooks(owner.getScene());
        position(owner);
        if (pointerRobot != null) pointerGuard.start();
    }

    private void hide() {
        pointerGuard.stop();
        bubble.setVisible(false);
        currentOwner = null;
    }

    private static boolean containsScenePoint(Node node, double sceneX, double sceneY) {
        if (node == null || node.getScene() == null) return false;
        try {
            Bounds bounds = node.localToScene(node.getBoundsInLocal());
            return bounds != null && bounds.contains(sceneX, sceneY);
        } catch (Exception ignored) {
            return false;
        }
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
