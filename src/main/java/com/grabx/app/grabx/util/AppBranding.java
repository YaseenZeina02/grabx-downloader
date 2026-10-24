package com.grabx.app.grabx.util;

import javafx.scene.image.Image;
import javafx.stage.Stage;
import java.awt.Taskbar;
import javax.imageio.ImageIO;

/** Explicit window and Dock branding also covers Java/IDE launches. */
public final class AppBranding {
    private AppBranding() {}
    public static void apply(Stage stage, boolean setup) {
        String resource = "/com/grabx/app/grabx/icons/" + (setup ? "grabx-setup.png" : "grabx.png");
        var url = AppBranding.class.getResource(resource);
        if (url == null) return;
        stage.getIcons().setAll(new Image(url.toExternalForm()));
        try {
            if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE))
                Taskbar.getTaskbar().setIconImage(ImageIO.read(url));
        } catch (Exception e) { AppLog.get(AppBranding.class).fine("Dock icon unavailable: " + e.getMessage()); }
    }
}
