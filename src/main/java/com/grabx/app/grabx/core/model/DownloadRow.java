package com.grabx.app.grabx.core.model;

import static com.grabx.app.grabx.MainController.thumbFromUrl;

public  class DownloadRow {
    final String url;
    public final String folder;
    public final String mode;
    public final String quality;

    public final javafx.beans.property.StringProperty title = new javafx.beans.property.SimpleStringProperty("New item");
    public final javafx.beans.property.StringProperty thumbUrl = new javafx.beans.property.SimpleStringProperty(null);

    public final javafx.beans.property.StringProperty status = new javafx.beans.property.SimpleStringProperty("Queued");
    public final javafx.beans.property.DoubleProperty progress = new javafx.beans.property.SimpleDoubleProperty(0);
    public final javafx.beans.property.StringProperty speed = new javafx.beans.property.SimpleStringProperty("0 KB/s");
    public final javafx.beans.property.StringProperty eta = new javafx.beans.property.SimpleStringProperty("--");

    public DownloadRow(String url, String initialTitle, String folder, String mode, String quality) {
        this.url = url;
        this.folder = folder;
        this.mode = mode;
        this.quality = quality;
        if (initialTitle != null && !initialTitle.isBlank()) {
            this.title.set(initialTitle);
        }
        this.thumbUrl.set(thumbFromUrl(url));
    }
}