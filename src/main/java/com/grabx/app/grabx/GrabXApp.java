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
        Scene scene = new Scene(fxmlLoader.load(), 1280, 820); //"1280" prefHeight="820
//        scene.getStylesheets().addAll(
//                java.util.Objects.requireNonNull(GrabXApp.class.getResource("styles/theme-base.css")).toExternalForm(),
//                java.util.Objects.requireNonNull(GrabXApp.class.getResource("styles/layout.css")).toExternalForm(),
//                java.util.Objects.requireNonNull(GrabXApp.class.getResource("styles/buttons.css")).toExternalForm(),
//                java.util.Objects.requireNonNull(GrabXApp.class.getResource("styles/tabs.css")).toExternalForm(),
//                java.util.Objects.requireNonNull(GrabXApp.class.getResource("styles/table.css")).toExternalForm(),
//                java.util.Objects.requireNonNull(GrabXApp.class.getResource("styles/sidebar.css")).toExternalForm()
//        );
        stage.setTitle("GrabX");
        stage.setMinWidth(900);
        stage.setMinHeight(500);
        stage.setScene(scene);
        stage.show();
    }
}
