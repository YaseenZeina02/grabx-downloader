package com.grabx.app.grabx.core.model;

import static com.grabx.app.grabx.MainController.thumbFromUrl;

import javafx.beans.property.*;

public class DownloadRow {

    public final javafx.beans.property.ObjectProperty<java.nio.file.Path> outputFile =
            new javafx.beans.property.SimpleObjectProperty<>(null);

    public enum State {
        QUEUED,
        DOWNLOADING,
        PAUSED,
        COMPLETED,
        CANCELLED,
        FAILED
    }

    public final String url;
    public final String folder;
    public final String mode;
    public final String quality;

    public final StringProperty title = new SimpleStringProperty("New item");
    public final StringProperty thumbUrl = new SimpleStringProperty(null);

    public final StringProperty status = new SimpleStringProperty("Queued");
    public final DoubleProperty progress = new SimpleDoubleProperty(0);
    public final StringProperty speed = new SimpleStringProperty("");
    public final StringProperty eta = new SimpleStringProperty("");
    public final StringProperty size = new SimpleStringProperty(""); // e.g. "6.52 MB / 1.12 GB"

    public final ObjectProperty<State> state = new SimpleObjectProperty<>(State.QUEUED);

    public DownloadRow(String url, String initialTitle, String folder, String mode, String quality) {
        this.url = url;
        this.folder = folder;
        this.mode = mode;
        this.quality = quality;

        if (initialTitle != null && !initialTitle.isBlank()) {
            this.title.set(initialTitle);
        }

        setState(State.QUEUED);
        this.thumbUrl.set(thumbFromUrl(url));
    }

    public void setState(State s) {
        if (s == null) s = State.QUEUED;
        this.state.set(s);

        switch (s) {
            case QUEUED -> status.set("Queued");
            case DOWNLOADING -> status.set("Downloading");
            case PAUSED -> status.set("Paused");
            case COMPLETED -> status.set("Completed");
            case CANCELLED -> status.set("Cancelled");
            case FAILED -> status.set("Failed");
        }
    }
}