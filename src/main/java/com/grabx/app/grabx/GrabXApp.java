package com.grabx.app.grabx;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public class GrabXApp extends Application {

    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(GrabXApp.class.getResource("main.fxml"));
        Scene scene = new Scene(fxmlLoader.load(), 1280, 820);

        stage.setTitle("GrabX");
        stage.setMinWidth(900);
        stage.setMinHeight(500);
        stage.setScene(scene);

        stage.setOnCloseRequest(e -> {
            javafx.application.Platform.exit();
        });
        stage.show();
    }
}
