package com.grabx.app.grabx.ui.components;

import javafx.animation.AnimationTimer;
import javafx.stage.Stage;

/** A frozen full-window state that cannot be overwritten by compact/show callbacks. */
public record CompactWindowState(double x, double y, double width, double height,
                                 boolean maximized, boolean fullScreen) {
    public static CompactWindowState capture(Stage stage) {
        return new CompactWindowState(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight(),
                stage.isMaximized(), stage.isFullScreen());
    }

    public void restore(Stage stage, Runnable finished) {
        // Set window modes before bounds; leaving zoom/full-screen can itself resize
        // the native window. Apply before show to avoid flashing at a different size.
        apply(stage);
        stage.setIconified(false);
        stage.show();
        stage.toFront();

        // show() can return before native geometry/mode notifications have arrived.
        // Keep the snapshot until bounds settle, rather than accepting a late zoom
        // frame as the next compact toggle's saved size. Stop promptly if the user
        // hides/minimizes the window, and bound the wait if the platform refuses it.
        new AnimationTimer() {
            private long started;
            private long stableSince;
            @Override public void handle(long now) {
                if (started == 0) started = now;
                if (!stage.isShowing() || stage.isIconified()) { complete(); return; }
                if (!matches(stage)) {
                    stableSince = 0;
                    apply(stage);
                } else if (stableSince == 0) stableSince = now;
                if ((stableSince != 0 && now - stableSince >= 200_000_000L)
                        || now - started >= 1_000_000_000L) complete();
            }
            private void complete() { stop(); finished.run(); }
        }.start();
    }

    private void apply(Stage stage) {
        stage.setFullScreen(fullScreen);
        stage.setMaximized(maximized);
        if (!maximized && !fullScreen) {
            stage.setX(x);
            stage.setY(y);
            stage.setWidth(width);
            stage.setHeight(height);
        }
    }

    boolean matches(Stage stage) {
        return stage.isMaximized() == maximized && stage.isFullScreen() == fullScreen
                && (maximized || fullScreen || (near(stage.getX(), x) && near(stage.getY(), y)
                && near(stage.getWidth(), width) && near(stage.getHeight(), height)));
    }

    private static boolean near(double actual, double expected) { return Math.abs(actual - expected) <= 2; }
}
