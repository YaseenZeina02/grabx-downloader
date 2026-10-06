package com.grabx.app.grabx.core.service;

import javafx.stage.Stage;
import javafx.stage.Window;
import java.awt.Desktop;

/** Called on the FX thread; native application activation is limited to browser handoffs. */
public final class WindowActivationService {
    private WindowActivationService() { }

    public static void fromBrowser(Stage stage) {
        if (stage == null) return;
        bringToFront(stage);
        // On macOS, bringing a JavaFX window forward alone can leave the browser
        // as the active application. Use the JDK's native activation API as well.
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) {
                    desktop.requestForeground(true);
                }
            }
        } catch (UnsupportedOperationException | SecurityException ignored) {
            // Other desktop environments retain normal window-manager focus behavior.
        }
        bringToFront(stage);
    }

    public static void bringToFront(Window window) {
        if (window == null) return;
        if (window instanceof Stage stage) {
            stage.setIconified(false);
            stage.show();
            stage.toFront();
        }
        window.requestFocus();
    }
}
