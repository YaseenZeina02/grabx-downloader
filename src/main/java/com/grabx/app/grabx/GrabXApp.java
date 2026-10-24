package com.grabx.app.grabx;

import com.grabx.app.grabx.util.SingleInstanceGuard;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public class GrabXApp extends Application {
    private final SingleInstanceGuard singleInstance = SingleInstanceGuard.forCurrentUser();
    private MainController controller;

    @Override
    public void start(Stage stage) throws IOException {
        if (!singleInstance.acquire()) {
            Platform.exit();
            return;
        }

        var preferences = java.util.prefs.Preferences.userNodeForPackage(MainController.class);
        boolean requested = getParameters().getRaw().contains("--setup");
        if (requested || (Boolean.getBoolean("grabx.packaged") && !new com.grabx.app.grabx.setup.SetupPreferences(preferences).completed())) {
            new com.grabx.app.grabx.setup.SetupWizard(stage, preferences, () -> {
                try { openMain(stage); }
                catch (IOException e) { throw new IllegalStateException("Could not open GrabX", e); }
            }).show();
            return;
        }
        openMain(stage);
    }

    private void openMain(Stage stage) throws IOException {
        com.grabx.app.grabx.util.AppBranding.apply(stage, false);
        com.grabx.app.grabx.util.I18n.install(new com.grabx.app.grabx.core.service.LanguagePreferences(
                java.util.prefs.Preferences.userNodeForPackage(MainController.class)).code());
        FXMLLoader fxmlLoader = new FXMLLoader(GrabXApp.class.getResource("main.fxml"));
        Scene scene = new Scene(fxmlLoader.load(), 1280, 820);
        controller = fxmlLoader.getController();

        stage.setTitle("GrabX");
        stage.setMinWidth(900);
        stage.setMinHeight(500);
        stage.setScene(scene);

        stage.setOnCloseRequest(e -> {
            if (controller != null && !controller.leaveSettings()) { e.consume(); return; }
            if (controller != null) controller.shutdown();
            Platform.exit();
        });
        stage.show();
    }

    @Override
    public void stop() {
        if (controller != null) controller.shutdown();
        singleInstance.close();
    }
}
