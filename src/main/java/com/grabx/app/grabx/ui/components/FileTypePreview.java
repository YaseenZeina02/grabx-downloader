package com.grabx.app.grabx.ui.components;

import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.paint.*;
import javafx.scene.shape.*;
import java.util.Locale;

/** Local vector artwork for downloads without a thumbnail. */
final class FileTypePreview {
    private FileTypePreview() {}

    enum Kind {
        VIDEO("#7CA9FF", "#315C97", "#162C4C", "video"),
        AUDIO("#BE9AFF", "#644597", "#30204F", "audio"),
        IMAGE("#66D6C0", "#286F69", "#163B3A", "image"),
        ARCHIVE("#F5B85B", "#896133", "#46321E", "archive"),
        PDF("#FF8A91", "#954956", "#4E2431", "PDF document"),
        DOCUMENT("#78BCD9", "#386A8A", "#1B364D", "document"),
        SHEET("#7AD6A4", "#337450", "#1A3D2D", "spreadsheet"),
        SLIDES("#FFA97A", "#955C3B", "#4D3025", "presentation"),
        APP("#A5AEFF", "#505E9B", "#282D50", "application"),
        DISK("#91BFE8", "#496981", "#243747", "disk image"),
        CODE("#81D7E7", "#356D82", "#1B3545", "code"),
        BOOK("#EAA7D8", "#835179", "#442940", "ebook"),
        FILE("#A3B2C8", "#4A5A71", "#252F40", "file");

        final String accent, top, bottom, description;
        Kind(String accent, String top, String bottom, String description) {
            this.accent = accent; this.top = top; this.bottom = bottom; this.description = description;
        }
    }
    record Format(Kind kind, String caption) { }

    /** Recognized extensions take precedence over a generic download mode. */
    static Format describe(String filename, String mode) {
        String name = filename == null ? "" : filename.strip().toLowerCase(Locale.ROOT);
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        for (String compound : new String[]{"tar.gz", "tar.bz2", "tar.xz", "tar.zst"}) {
            if (name.endsWith("." + compound)) return new Format(Kind.ARCHIVE, compound.toUpperCase(Locale.ROOT));
        }
        int dot = name.lastIndexOf('.');
        String extension = dot > 0 ? name.substring(dot + 1) : "";
        Kind kind = switch (extension) {
            case "mp4", "mkv", "webm", "mov", "avi", "m4v", "mpg", "mpeg", "wmv", "flv", "ts", "m2ts", "3gp" -> Kind.VIDEO;
            case "mp3", "wav", "flac", "m4a", "aac", "ogg", "opus", "aiff", "aif", "wma", "alac" -> Kind.AUDIO;
            case "png", "jpg", "jpeg", "webp", "gif", "svg", "heic", "heif", "avif", "bmp", "tif", "tiff", "ico", "psd" -> Kind.IMAGE;
            case "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "zst", "tgz", "tbz2", "txz" -> Kind.ARCHIVE;
            case "pdf" -> Kind.PDF;
            case "doc", "docx", "odt", "txt", "rtf", "md", "pages", "log" -> Kind.DOCUMENT;
            case "xls", "xlsx", "xlsm", "ods", "csv", "tsv", "numbers" -> Kind.SHEET;
            case "ppt", "pptx", "odp", "key" -> Kind.SLIDES;
            case "exe", "msi", "dmg", "pkg", "apk", "deb", "rpm", "appimage", "msix" -> Kind.APP;
            case "iso", "img", "vhd", "vhdx", "vmdk" -> Kind.DISK;
            case "json", "xml", "html", "htm", "css", "js", "jsx", "tsx", "py", "java", "c", "cpp", "h", "go", "rs", "sh", "sql", "yaml", "yml" -> Kind.CODE;
            case "epub", "mobi", "azw", "azw3", "fb2" -> Kind.BOOK;
            default -> Kind.FILE;
        };
        if (kind == Kind.FILE) {
            String safeMode = mode == null ? "" : mode.toLowerCase(Locale.ROOT);
            if (safeMode.contains("audio")) return new Format(Kind.AUDIO, "AUDIO");
            if (safeMode.equals("video")) return new Format(Kind.VIDEO, "VIDEO");
        }
        String caption = extension.matches("[a-z0-9]{1,8}") ? extension.toUpperCase(Locale.ROOT) : "FILE";
        return new Format(kind, caption);
    }

    static VBox create(String filename, String mode, boolean compact) {
        Format format = describe(filename, mode);
        Kind kind = format.kind();
        Group symbol = artwork(kind);
        if (compact) { symbol.setScaleX(0.68); symbol.setScaleY(0.68); }
        Group artwork = new Group(symbol); // Include the transform in layout bounds.
        Label caption = new Label(format.caption());
        caption.setStyle("-fx-text-fill: " + kind.accent + "; -fx-font-family: 'System'; -fx-font-size: "
                + (compact ? "8px" : "9px") + "; -fx-font-weight: bold;");
        caption.setMaxWidth(compact ? 46 : 88);
        caption.setAlignment(Pos.CENTER);
        VBox preview = new VBox(compact ? 2 : 4, artwork, caption);
        preview.setAlignment(Pos.CENTER);
        preview.setAccessibleText(format.caption() + " • " + kind.description);
        preview.setMouseTransparent(true);
        return preview;
    }

    private static Group artwork(Kind kind) {
        Rectangle back = rectangle(0, 0, 42, 29, Color.web(kind.accent, 0.16));
        Rectangle panel = rectangle(4, 4, 44, 31, new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web(kind.top)), new Stop(1, Color.web(kind.bottom))));
        panel.setStroke(Color.web(kind.accent, 0.85)); panel.setStrokeWidth(1.2);
        Group art = new Group(back, panel);
        Color ink = Color.web("#EEF5FF");
        switch (kind) {
            case VIDEO -> {
                art.getChildren().add(path("M22 11L33 18L22 25Z", ink, ink, 1));
                art.getChildren().add(path("M13 29H39", null, Color.web(kind.accent, 0.4), 1.6));
            }
            case AUDIO -> art.getChildren().add(path("M14 17V23M20 12V28M26 9V30M32 14V26M38 17V23", null, ink, 2.6));
            case IMAGE -> {
                art.getChildren().add(new Circle(35, 13, 3, Color.web("#C9FFF0")));
                art.getChildren().add(path("M10 29L20 16L28 25L33 20L43 29Z", Color.web("#9DEAD7", 0.8), null, 0));
            }
            case ARCHIVE -> {
                art.getChildren().add(path("M23 7H26V10H23ZM26 10H29V13H26ZM23 13H26V16H23ZM26 16H29V19H26Z",
                        Color.web(kind.accent, 0.85), null, 0));
                art.getChildren().add(path("M23 20H29V27Q26 31 23 27Z", null, ink, 1.8));
                art.getChildren().add(path("M25 25H27", null, ink, 1.5));
            }
            case PDF, DOCUMENT, FILE -> {
                art.getChildren().add(path("M18 9H29L34 14V30H18ZM29 9V15H34", Color.web(kind.accent, 0.12), ink, 1.5));
                art.getChildren().add(path("M22 19H30M22 23H30M22 27H27", null, Color.web(kind.accent), 1.5));
            }
            case SHEET -> {
                art.getChildren().add(rectangle(13, 10, 26, 5, Color.web(kind.accent, 0.65)));
                art.getChildren().add(path("M13 10H39V29H13ZM13 16H39M13 22H39M22 16V29M31 16V29", null, ink, 1.4));
            }
            case SLIDES -> {
                art.getChildren().add(path("M12 10H40V26H12ZM26 26V31M21 31H31", null, ink, 1.5));
                art.getChildren().add(path("M18 22V19M25 22V16M32 22V13", null, Color.web(kind.accent), 3));
            }
            case APP -> {
                for (int y : new int[]{11, 22}) for (int x : new int[]{17, 28}) {
                    Rectangle tile = rectangle(x, y, 8, 8, ink); tile.setArcWidth(3); tile.setArcHeight(3);
                    art.getChildren().add(tile);
                }
            }
            case DISK -> {
                Circle disc = new Circle(26, 20, 11, Color.web(kind.accent, 0.18));
                disc.setStroke(ink); disc.setStrokeWidth(1.6); art.getChildren().add(disc);
                Circle hub = new Circle(26, 20, 3, Color.web(kind.bottom));
                hub.setStroke(ink); hub.setStrokeWidth(1.4); art.getChildren().add(hub);
                art.getChildren().add(path("M29 12Q33 13 34 17", null, Color.web(kind.accent), 2));
            }
            case CODE -> art.getChildren().add(path("M18 13L12 20L18 27M34 13L40 20L34 27M29 11L23 29", null, ink, 2));
            case BOOK -> {
                art.getChildren().add(path("M26 12Q19 8 12 11V28Q19 25 26 29Q33 25 40 28V11Q33 8 26 12ZM26 12V29", null, ink, 1.6));
                art.getChildren().add(path("M16 16L22 17M16 21L22 22M30 17L36 16M30 22L36 21", null, Color.web(kind.accent), 1.3));
            }
        }
        return art;
    }

    private static Rectangle rectangle(double x, double y, double width, double height, Paint fill) {
        Rectangle shape = new Rectangle(x, y, width, height);
        shape.setArcWidth(9); shape.setArcHeight(9); shape.setFill(fill);
        return shape;
    }

    private static SVGPath path(String content, Paint fill, Paint stroke, double width) {
        SVGPath path = new SVGPath(); path.setContent(content); path.setFill(fill); path.setStroke(stroke);
        path.setStrokeWidth(width); path.setStrokeLineCap(StrokeLineCap.ROUND); path.setStrokeLineJoin(StrokeLineJoin.ROUND);
        return path;
    }
}
