package com.grabx.app.grabx.ui.components;

import com.grabx.app.grabx.core.service.HoverTooltipService;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

/** Owns the application's SVG icons and consistent icon-button setup. */
public final class IconButtonService {
    public static final String PLUS = "M19 11H13V5h-2v6H5v2h6v6h2v-6h6v-2z";
    public static final String FOLDER_OPEN = "M3 6.5C3 5.12 4.12 4 5.5 4H10L12 6H18.5C19.88 6 21 7.12 21 8.5V17.5C21 18.88 19.88 20 18.5 20H5.5C4.12 20 3 18.88 3 17.5V6.5Z";
    public static final String PAUSE = "M6 5h4v14H6V5zm8 0h4v14h-4V5z";
    public static final String PLAY = "M8 5v14l11-7L8 5z";
    public static final String CANCEL = "M18.3 5.71 12 12l6.3 6.29-1.41 1.42L10.59 13.4 4.3 19.71 2.89 18.29 9.17 12 2.89 5.71 4.3 4.29 10.59 10.6 16.89 4.29z";
    public static final String RETRY = "M12 5a7 7 0 1 1-6.32 4H3l3.5-3.5L10 9H7.76A5.5 5.5 0 1 0 12 6.5V5z";
    public static final String CLEAR = "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z";
    public static final String LINK = "M14 3h7v7h-2V6.41l-9.29 9.3-1.42-1.42 9.3-9.29H14V3z";
    /** Picture-in-picture metaphor: move the main window into a smaller working view. */
    public static final String COMPACT_VIEW =
            "M19 7h-8v6h8V7zm2-4H3C1.9 3 1 3.9 1 5v14c0 1.1.9 2 2 2h18c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16H3V5h18v14z";
    /** Diagonal expand arrows: restore Compact View to the larger application window. */
    public static final String FULL_VIEW =
            "M13 3h8v8h-2V6.41l-5.29 5.3-1.42-1.42L17.59 5H13V3zM11 21H3v-8h2v4.59l5.29-5.3 1.42 1.42L6.41 19H11v2z";
    public static final String DOWNLOAD_TRAY =
            "M19 3H5C3.9 3 3 3.9 3 5v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 12h-4c0 1.66-1.35 3-3 3s-3-1.34-3-3H5V5h14v10zm-3-5h-2V7h-4v3H8l4 4 4-4z";
    public static final String SETTINGS =
            "M19.14 12.94c.04-.31.06-.63.06-.94s-.02-.63-.06-.94l2.03-1.58" +
            "c.18-.14.23-.41.12-.61l-1.92-3.32c-.11-.2-.36-.28-.57-.2l-2.39.96" +
            "c-.5-.38-1.04-.69-1.64-.92l-.36-2.54c-.03-.22-.22-.38-.45-.38h-3.84" +
            "c-.23 0-.42.16-.45.38l-.36 2.54c-.6.23-1.14.54-1.64.92l-2.39-.96" +
            "c-.21-.08-.46 0-.57.2L2.71 8.89c-.11.2-.06.47.12.61l2.03 1.58" +
            "c-.04.31-.06.63-.06.94s.02.63.06.94L2.83 14.54c-.18.14-.23.41-.12.61" +
            "l1.92 3.32c.11.2.36.28.57.2l2.39-.96c.5.38 1.04.69 1.64.92l.36 2.54" +
            "c.03.22.22.38.45.38h3.84c.23 0 .42-.16.45-.38l.36-2.54" +
            "c.6-.23 1.14-.54 1.64-.92l2.39.96c.21.08.46 0 .57-.2l1.92-3.32" +
            "c.11-.2.06-.47-.12-.61l-2.03-1.58z" +
            "M12 15.5c-1.93 0-3.5-1.57-3.5-3.5S10.07 8.5 12 8.5s3.5 1.57 3.5 3.5-1.57 3.5-3.5 3.5z";

    private final HoverTooltipService tooltips;

    public IconButtonService(HoverTooltipService tooltips) {
        this.tooltips = tooltips;
    }

    public void initializeToolbar(Button addLink, Button pauseAll, Button resumeAll,
                                  Button cancelAll, Button clearAll, Button settings,
                                  Button compactView) {
        configure(addLink, PLUS, "Add link");
        configure(pauseAll, PAUSE, "Pause all");
        configure(resumeAll, PLAY, "Resume all");
        configure(cancelAll, CANCEL, "Cancel All");
        configure(clearAll, CLEAR, "Clear all");
        configure(settings, SETTINGS, "Settings");
        configure(compactView, COMPACT_VIEW, "Compact View");
    }

    public void installTooltip(Button button, String text) {
        try {
            if (tooltips != null) tooltips.install(button, text);
        } catch (Exception ignored) {
        }
    }

    public static void setupSvgButton(Button button, String svgPath) {
        if (button == null) return;
        button.getStyleClass().addAll("gx-icon-btn", "gx-task-action");
        button.setFocusTraversable(false);
        button.setText(null);
        button.setGraphic(svgIcon(svgPath, 34));
    }

    public static Node createSvgIcon(String svgPath, double boxSize) {
        return svgIcon(svgPath, boxSize);
    }

    public static DownloadListViewService.Icons downloadIcons() {
        return new DownloadListViewService.Icons(PAUSE, PLAY, CANCEL, LINK, FOLDER_OPEN, RETRY, CLEAR);
    }

    private void configure(Button button, String path, String tooltip) {
        if (button == null) return;
        setupSvgButton(button, path);
        button.setPickOnBounds(true);
        Node graphic = button.getGraphic();
        if (graphic != null) graphic.setMouseTransparent(true);
        installTooltip(button, tooltip);
    }

    private static Node svgIcon(String path, double boxSize) {
        SVGPath svg = new SVGPath();
        svg.setContent(path);
        svg.getStyleClass().add("gx-svg-icon");
        StackPane box = new StackPane(svg);
        box.setMinSize(boxSize, boxSize);
        box.setPrefSize(boxSize, boxSize);
        box.setMaxSize(boxSize, boxSize);
        Platform.runLater(() -> {
            var bounds = svg.getBoundsInLocal();
            if (bounds.getWidth() <= 0 || bounds.getHeight() <= 0) return;
            double target = boxSize * 0.52;
            double scale = Math.min(target / bounds.getWidth(), target / bounds.getHeight());
            svg.setScaleX(scale);
            svg.setScaleY(scale);
        });
        return box;
    }
}
