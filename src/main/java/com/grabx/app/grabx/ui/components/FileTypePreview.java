package com.grabx.app.grabx.ui.components;

import javafx.geometry.Pos;
import javafx.scene.layout.VBox;
import javafx.scene.control.Label;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import java.util.Locale;

/** Local vector artwork for downloads without a thumbnail. */
final class FileTypePreview {
    private FileTypePreview() {}

    static VBox create(String filename, String mode, boolean compact) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String extension = dot >= 0 ? name.substring(dot + 1) : "";
        String kind = "FILE";
        String color = "#8DA6CA";
        String symbol = "M8 11H16 M8 14H16 M8 17H13";
        if (java.util.Set.of("zip", "rar", "7z", "tar", "gz", "bz2", "xz").contains(extension)) {
            kind = extension.toUpperCase(Locale.ROOT); color = "#F5B85B";
            symbol = "M11 5H13 M11 8H13 M11 11H13 M11 14H13 M11 17H13V20H11Z";
        } else if (java.util.Set.of("mp3", "wav", "flac", "m4a", "aac", "ogg").contains(extension)
                || (mode != null && mode.toLowerCase(Locale.ROOT).contains("audio"))) {
            kind = "AUDIO"; color = "#BE9AFF";
            symbol = "M14 10V17 M14 10L18 9V15 M14 17C14 20 9 20 9 18C9 16 14 16 14 17 M18 15C18 18 14 18 14 16C14 14 18 14 18 15";
        } else if (java.util.Set.of("mp4", "mkv", "webm", "mov", "avi").contains(extension) || "Video".equalsIgnoreCase(mode)) {
            kind = "VIDEO"; color = "#7CA9FF"; symbol = "M10 10L17 14L10 18Z";
        } else if (java.util.Set.of("png", "jpg", "jpeg", "webp", "gif", "svg", "heic").contains(extension)) {
            kind = "IMAGE"; color = "#66D6C0"; symbol = "M7 18L11 13L14 16L17 12L19 18Z M8 10H9";
        } else if (java.util.Set.of("pdf", "doc", "docx", "txt", "rtf", "xlsx", "csv", "pptx").contains(extension)) {
            kind = extension.toUpperCase(Locale.ROOT); color = "pdf".equals(extension) ? "#FF8A91" : "#78BCD9";
        } else if (java.util.Set.of("dmg", "exe", "msi", "pkg", "apk").contains(extension)) {
            kind = "APP"; color = "#87A5FF"; symbol = "M8 10H11V13H8Z M14 10H17V13H14Z M8 16H11V19H8Z M14 16H17V19H14Z";
        }
        SVGPath outline = new SVGPath();
        outline.setContent("M5 2H14L20 8V22H5Z M14 2V8H20 " + symbol);
        outline.setFill(Color.web(color, 0.09));
        outline.setStroke(Color.web(color));
        outline.setStrokeWidth(1.3);
        outline.setStrokeLineJoin(javafx.scene.shape.StrokeLineJoin.ROUND);
        double scale = compact ? 1 : 1.5;
        javafx.scene.Group artwork = new javafx.scene.Group(outline);
        outline.setScaleX(scale); outline.setScaleY(scale);
        // Group layout bounds account for the child's transform.
        Label caption = new Label(kind);
        caption.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 9px; -fx-font-weight: bold;");
        VBox preview = new VBox(compact ? 2 : 5, artwork, caption);
        preview.setAlignment(Pos.CENTER);
        preview.setAccessibleText(kind + " file");
        preview.setMouseTransparent(true);
        return preview;
    }
}
