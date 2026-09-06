package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.animation.Animation;
import javafx.collections.ObservableList;
import javafx.collections.ListChangeListener;
import javafx.util.Duration;
import java.nio.file.*;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/** Runs on the FX thread so a resume cannot race with expiry and file deletion. */
public final class PartialRetentionService {
    private final ObservableList<DownloadRow> items;
    private final Map<DownloadRow, Process> processes;
    private final Consumer<DownloadRow> cancel;
    private final IntSupplier days;
    private final Runnable save;
    private final Timeline timer;
    private final java.util.Set<DownloadRow> removed = new java.util.HashSet<>();
    private final ListChangeListener<DownloadRow> listener = change -> {
        while (change.next()) if (change.wasRemoved()) removed.addAll(change.getRemoved());
    };

    public PartialRetentionService(ObservableList<DownloadRow> items, Map<DownloadRow, Process> processes,
            Consumer<DownloadRow> cancel, IntSupplier days, Runnable save) {
        this.items = items; this.processes = processes; this.cancel = cancel; this.days = days; this.save = save;
        timer = new Timeline(new KeyFrame(Duration.seconds(30), event -> sweep()));
        timer.setCycleCount(Animation.INDEFINITE);
    }
    public void start() { items.addListener(listener); sweep(); timer.play(); }
    public void stop() { timer.stop(); items.removeListener(listener); }
    public void sweep() {
        for (DownloadRow row : java.util.List.copyOf(removed)) {
            Process process = processes.get(row);
            if (process != null && process.isAlive()) { cancel.accept(row); continue; }
            if (cleanup(row)) removed.remove(row);
        }
        long cutoff = System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(days.getAsInt());
        for (DownloadRow row : java.util.List.copyOf(items)) {
            var state = row.getState();
            if (state != DownloadRow.State.PAUSED && state != DownloadRow.State.FAILED
                    && state != DownloadRow.State.CANCELLED) continue;
            try {
                if (!DirectPartialFiles.expired(row.outputFile.get(), cutoff)) continue;
                Process process = processes.get(row);
                if (process != null && process.isAlive()) { cancel.accept(row); continue; }
                if (cleanup(row)) {
                    row.setState(DownloadRow.State.CANCELLED);
                    row.downloadedBytes.set(0); row.progress.set(0); row.size.set("");
                    row.status.set("Incomplete files expired • restart to download again");
                    save.run();
                }
            } catch (Exception error) { report(error); }
        }
    }
    private boolean cleanup(DownloadRow row) {
        try { DirectPartialFiles.cleanup(row.outputFile.get()); return true; }
        catch (Exception error) { report(error); return false; }
    }
    private void report(Exception error) {
        com.grabx.app.grabx.util.AppLog.get(PartialRetentionService.class)
                .warning("Incomplete-file cleanup failed: " + error.getMessage());
    }
}
