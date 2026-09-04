package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.util.Duration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * MissingWatcherService
 * --------------------
 * Periodically checks COMPLETED downloads and marks them as MISSING if the output file disappears.
 * UI-safe: state changes and callbacks run on FX thread.
 */
public final class MissingWatcherService {

    private final ObservableList<DownloadRow> items;
    private final ScheduledExecutorService uiDelayExec;

    /** Called when any item changes to MISSING (e.g., refresh filters + sidebar). Must be FX-safe. */
    private final Runnable onAnyChanged;

    private Timeline tl;

    public MissingWatcherService(
            ObservableList<DownloadRow> items,
            ScheduledExecutorService uiDelayExec,
            Runnable onAnyChanged
    ) {
        this.items = Objects.requireNonNull(items, "items");
        this.uiDelayExec = Objects.requireNonNull(uiDelayExec, "uiDelayExec");
        this.onAnyChanged = (onAnyChanged == null) ? () -> {} : onAnyChanged;
    }

    /** Start watcher (idempotent). */
    public void start() {
        fx(() -> {
            if (tl != null) return;

            // Disk checks are intentionally infrequent: missing files are not
            // time-critical, and large histories should stay cheap while idle.
            tl = new Timeline(new KeyFrame(Duration.seconds(8), ev -> {
                try { refreshMissingFromDisk(); } catch (Exception ignored) {}
            }));
            tl.setCycleCount(Animation.INDEFINITE);
            try { tl.play(); } catch (Exception ignored) {}

            // Quick check shortly after startup
            uiDelayExec.schedule(() -> Platform.runLater(() -> {
                try { refreshMissingFromDisk(); } catch (Exception ignored) {}
            }), 350, TimeUnit.MILLISECONDS);
        });
    }

    /** Stop watcher (safe). */
    public void stop() {
        fx(() -> {
            try {
                if (tl != null) tl.stop();
            } catch (Exception ignored) {
            } finally {
                tl = null;
            }
        });
    }

    /** One-shot check. */
    public void refreshMissingFromDisk() {
        if (items == null || items.isEmpty()) return;

        boolean anyChanged = false;

        for (DownloadRow r : items) {
            if (r == null) continue;

            DownloadRow.State st;
            try { st = r.state.get(); } catch (Exception e) { continue; }

            // Only: Completed -> Missing if file is gone
            if (st != DownloadRow.State.COMPLETED) continue;

            boolean ok;
            try {
                Path p = (r.outputFile == null) ? null : r.outputFile.get();
                ok = p != null && Files.exists(p) && Files.size(p) > 0;
            } catch (Exception ignored) {
                ok = false;
            }

            if (!ok) {
                r.setState(DownloadRow.State.MISSING);
                anyChanged = true;
            }
        }

        if (anyChanged) {
            fx(onAnyChanged);
        }
    }

    private static void fx(Runnable r) {
        if (r == null) return;
        if (Platform.isFxApplicationThread()) r.run();
        else Platform.runLater(r);
    }
}
