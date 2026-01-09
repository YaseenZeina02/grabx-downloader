package com.grabx.app.grabx.ui.components;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollBar;
import javafx.scene.input.ScrollEvent;
import javafx.util.Duration;

import java.util.Set;

public final class ScrollbarAutoHide {

    public static void enableGlobalAutoHide(Parent root) {
        Platform.runLater(() -> {
            if (root == null) return;

            // 1) اجباري: نخفي كل السكرولات كبداية
            applyHiddenState(root);

            // 2) أي ScrollEvent بأي مكان: أظهر السكرولات ثم اخفِها بعد delay
            PauseTransition hideDelay = new PauseTransition(Duration.millis(700));

            root.addEventFilter(ScrollEvent.ANY, e -> {
                showAllScrollBars(root);
                hideDelay.stop();
                hideDelay.setOnFinished(ev -> hideAllScrollBars(root));
                hideDelay.playFromStart();
            });

            // 3) كمان لو دخلت الماوس على سكرول بار، ما يختفي
            Set<Node> bars = root.lookupAll(".scroll-bar");
            for (Node n : bars) {
                if (n instanceof ScrollBar sb) {
                    sb.hoverProperty().addListener((obs, was, is) -> {
                        if (is) {
                            showScrollBar(sb);
                            hideDelay.stop();
                        } else {
                            hideDelay.playFromStart();
                        }
                    });
                }
            }
        });
    }

    private static void applyHiddenState(Parent root) {
        for (Node n : root.lookupAll(".scroll-bar")) {
            if (n instanceof ScrollBar sb) {
                sb.setOpacity(0.0);
                sb.setMouseTransparent(true);
            }
        }
    }

    private static void showAllScrollBars(Parent root) {
        for (Node n : root.lookupAll(".scroll-bar")) {
            if (n instanceof ScrollBar sb) showScrollBar(sb);
        }
    }

    private static void hideAllScrollBars(Parent root) {
        for (Node n : root.lookupAll(".scroll-bar")) {
            if (n instanceof ScrollBar sb) hideScrollBar(sb);
        }
    }

    private static void showScrollBar(ScrollBar sb) {
        sb.setMouseTransparent(false);
        FadeTransition ft = new FadeTransition(Duration.millis(120), sb);
        ft.setFromValue(sb.getOpacity());
        ft.setToValue(1.0);
        ft.play();
    }

    private static void hideScrollBar(ScrollBar sb) {
        FadeTransition ft = new FadeTransition(Duration.millis(220), sb);
        ft.setFromValue(sb.getOpacity());
        ft.setToValue(0.0);
        ft.setOnFinished(e -> sb.setMouseTransparent(true));
        ft.play();
    }
}