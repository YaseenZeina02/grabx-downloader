package com.grabx.app.grabx.setup;

import com.grabx.app.grabx.util.*;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

public final class DeviceReadinessPane extends VBox {
    private final VBox results = new VBox(10);
    private final Button prepare = new Button("Check & prepare missing tools");
    public DeviceReadinessPane() {
        super(12); setId("device-readiness");
        Label info = new Label("GrabX detects this device, reuses working tools and prepares missing tools for your account. An internet connection may be needed. Java is included with the installer.");
        info.setWrapText(true);
        Label system = new Label(System.getProperty("os.name") + " · " + System.getProperty("os.arch"));
        prepare.setId("device-prepare"); prepare.setOnAction(e -> prepare());
        getChildren().addAll(info, system, prepare, results);
    }
    private void prepare() {
        prepare.setDisable(true); results.getChildren().setAll(new Label("Preparing…"));
        Thread thread = new Thread(() -> {
            try {
                var checks = DevicePreparation.prepare();
                Platform.runLater(() -> {
                    results.getChildren().clear();
                    for (var check : checks) {
                        Label row = new Label((check.ready() ? "✓ " : "! ") + check.name() + "\n" + check.detail());
                        row.setWrapText(true); results.getChildren().add(row);
                    }
                    prepare.setDisable(false);
                });
            } catch (Exception error) { Platform.runLater(() -> { results.getChildren().setAll(new Label(error.toString())); prepare.setDisable(false); }); }
        }, "grabx-device-prepare"); thread.setDaemon(true); thread.start();
    }
}
