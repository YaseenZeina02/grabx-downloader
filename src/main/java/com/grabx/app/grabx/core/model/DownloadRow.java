package com.grabx.app.grabx.core.model;


import javafx.beans.property.*;

public class DownloadRow {

    public final javafx.beans.property.ObjectProperty<java.nio.file.Path> outputFile =
            new javafx.beans.property.SimpleObjectProperty<>(null);

    public final BooleanProperty titleLocked = new SimpleBooleanProperty(false);

    public final long orderIndex;
    public long completedAt = -1;

    public void setTitleOnce(String t) {
        if (titleLocked.get()) return;
        if (t == null || t.isBlank()) return;
        title.set(t);
        titleLocked.set(true);
    }

    public enum State {
        QUEUED,
        PENDING,
        DOWNLOADING,
        PAUSED,
        COMPLETED,
        CANCELLED,
        FAILED,
        MISSING
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
    public final StringProperty size = new SimpleStringProperty(""); // e.g. "Downloaded: 6.52 MB" or "Final size: 674.3 MB"

    public final ObjectProperty<State> state = new SimpleObjectProperty<>(State.QUEUED);

    public ObjectProperty<State> stateProperty() {
        return state;
    }

    public State getState() {
        return state.get();
    }

    public final LongProperty totalBytes = new SimpleLongProperty(-1);
    public final LongProperty downloadedBytes = new SimpleLongProperty(0);

    public DownloadRow(String url, String initialTitle, long orderIndex, String folder, String mode, String quality) {
        this.url = url;
        this.orderIndex = orderIndex;
        this.folder = folder;
        this.mode = mode;
        this.quality = quality;

        if (initialTitle != null && !initialTitle.isBlank()) {
            this.title.set(initialTitle);
        }

        setState(State.QUEUED);
    }

    public void setState(State s) {
        if (s == null) s = State.QUEUED;
        this.state.set(s);

        // If we pause/cancel while still in "Preparing" (indeterminate progress = -1),
        // freeze the progress bar instead of keeping the indeterminate animation.
        try {
            if (s == State.PAUSED || s == State.CANCELLED || s == State.FAILED) {
                if (progress.get() < 0) progress.set(0);
            }
        } catch (Exception ignored) {}

        switch (s) {
            case QUEUED -> status.set("Queued");
            case PENDING -> status.set("Pending");
            case DOWNLOADING -> status.set("Downloading");
            case PAUSED -> {
                status.set("Paused");
                // Stop showing speed/ETA during pause
                speed.set("");
                eta.set("");
            }
            case COMPLETED -> {
                status.set("Completed");
                if (completedAt <= 0) {
                    completedAt = System.currentTimeMillis();
                }
            }
            case CANCELLED -> status.set("Cancelled");
            case FAILED -> status.set("Failed");
            case MISSING -> status.set("Missing");
        }
    }
}