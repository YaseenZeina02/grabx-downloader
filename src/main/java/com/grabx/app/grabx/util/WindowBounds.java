package com.grabx.app.grabx.util;

import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;

/** Window sizes use JavaFX logical work-area coordinates, including display scaling. */
public final class WindowBounds {
    private WindowBounds() {}

    public static Rectangle2D fit(Rectangle2D requested, Rectangle2D area) {
        double width = Math.min(requested.getWidth(), area.getWidth());
        double height = Math.min(requested.getHeight(), area.getHeight());
        double x = Math.max(area.getMinX(), Math.min(requested.getMinX(), area.getMaxX() - width));
        double y = Math.max(area.getMinY(), Math.min(requested.getMinY(), area.getMaxY() - height));
        return new Rectangle2D(x, y, width, height);
    }

    public static Rectangle2D area(double x, double y, double width, double height) {
        return Screen.getScreensForRectangle(x, y, width, height).stream()
                .findFirst().orElse(Screen.getPrimary()).getVisualBounds();
    }

    public static void show(Stage stage, double width, double height, double minWidth, double minHeight) {
        Rectangle2D area = Screen.getPrimary().getVisualBounds();
        width = Math.min(width, area.getWidth() * .94);
        height = Math.min(height, area.getHeight() * .94);
        stage.setMinWidth(Math.min(minWidth, width));
        stage.setMinHeight(Math.min(minHeight, height));
        // Set outer bounds after show so native title-bar decorations are included.
        stage.show();
        stage.setWidth(width);
        stage.setHeight(height);
        stage.setX(area.getMinX() + (area.getWidth() - width) / 2);
        stage.setY(area.getMinY() + (area.getHeight() - height) / 2);
    }
}
