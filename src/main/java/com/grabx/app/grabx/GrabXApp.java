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

        FXMLLoader fxmlLoader = new FXMLLoader(GrabXApp.class.getResource("main.fxml"));
        Scene scene = new Scene(fxmlLoader.load(), 1280, 820);
        controller = fxmlLoader.getController();

        stage.setTitle("GrabX");
        stage.setMinWidth(900);
        stage.setMinHeight(500);
        stage.setScene(scene);

        stage.setOnCloseRequest(e -> {
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
