package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.ui.components.HoverBubble;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * HoverTooltipService
 * -------------------
 * Stable in-scene hover bubble (no Popup/Tooltip jitter).
 * Extracted from MainController.
 */
public final class HoverTooltipService {

    private final Parent rootNode;
    private final Class<?> resourceOwner;

    private Pane hoverLayer;
    private HoverBubble hoverBubble;

    private final List<javafx.util.Pair<Button, String>> pendingTooltips = new ArrayList<>();
    private volatile boolean hoverBubbleReady = false;

    public HoverTooltipService(Parent rootNode, Class<?> resourceOwner) {
        this.rootNode = Objects.requireNonNull(rootNode, "rootNode");
        this.resourceOwner = (resourceOwner == null) ? HoverTooltipService.class : resourceOwner;
        attachSceneListener();
    }

    /** Install tooltip on a button. Safe to call during initialize (queues until scene is ready). */
    public void install(Button btn, String text) {
        if (btn == null) return;

        if (hoverBubble == null || !hoverBubbleReady) {
            btn.getProperties().put("gx-hover-text", text);
            pendingTooltips.add(new javafx.util.Pair<>(btn, text));
            Platform.runLater(this::flushPendingTooltips);
            return;
        }

        hoverBubble.install(btn, text);
    }

    // ===================== internals =====================

    private void attachSceneListener() {
        rootNode.sceneProperty().addListener((obs, oldSc, newSc) -> {
            if (newSc == null) return;

            // Ensure tooltip CSS is available
            try {
                var cssUrl = resourceOwner.getResource("/com/grabx/app/grabx/styles/buttons.css");
                if (cssUrl != null) {
                    String css = cssUrl.toExternalForm();
                    if (!newSc.getStylesheets().contains(css)) {
                        newSc.getStylesheets().add(css);
                    }
                }
            } catch (Exception ignored) {}

            Platform.runLater(() -> ensureOverlay(newSc));
        });
    }

    private void ensureOverlay(Scene sc) {
        try {
            // already ready
            if (hoverLayer != null && hoverBubble != null) {
                hoverBubbleReady = true;
                flushPendingTooltips();
                return;
            }

            Parent currentRoot = sc.getRoot();

            if (currentRoot instanceof StackPane sp) {
                hoverLayer = buildHoverLayer(sp);
                sp.getChildren().add(hoverLayer);
                hoverLayer.toFront();
                hoverLayer.setViewOrder(-10_000);
            } else {
                StackPane wrapper = new StackPane();
                wrapper.getChildren().add(currentRoot);

                hoverLayer = buildHoverLayer(wrapper);
                wrapper.getChildren().add(hoverLayer);
                hoverLayer.toFront();
                hoverLayer.setViewOrder(-10_000);

                sc.setRoot(wrapper);
            }

            hoverBubble = new HoverBubble(hoverLayer);
            hoverBubbleReady = true;
            flushPendingTooltips();
        } catch (Exception ignored) {}
    }

    private Pane buildHoverLayer(StackPane host) {
        Pane layer = new Pane();
        layer.setManaged(false);
        layer.prefWidthProperty().bind(host.widthProperty());
        layer.prefHeightProperty().bind(host.heightProperty());
        layer.setMinSize(0, 0);
        layer.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        StackPane.setAlignment(layer, javafx.geometry.Pos.TOP_LEFT);
        layer.setPickOnBounds(false);
        layer.setMouseTransparent(true);
        layer.getStyleClass().add("gx-hover-layer");
        return layer;
    }

    private void flushPendingTooltips() {
        if (hoverBubble == null) return;
        hoverBubbleReady = true;

        for (var p : pendingTooltips) {
            Button b = p.getKey();
            if (b == null) continue;
            String txt = (String) b.getProperties().get("gx-hover-text");
            if (txt == null) txt = p.getValue();
            try {
                hoverBubble.install(b, txt);
            } catch (Exception ignored) {}
        }
        pendingTooltips.clear();
    }
}