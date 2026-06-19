package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import javafx.application.Platform;
import javafx.collections.ObservableList;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * DownloadStateCoordinator
 * -----------------------
 * مرحلة وسيطة: تنسيق حالات DownloadRow (pause/resume/cancel/retry)
 * بدون الدخول في محرك yt-dlp بالكامل.
 *
 * ملاحظة: إيقاف التحميل هنا = destroy() للـ Process الحالي (نفس سلوك MainController الحالي).
 */
public final class DownloadStateCoordinator {

    private final ObservableList<DownloadRow> items;
    private final Map<DownloadRow, Process> activeProcesses;
    private final Map<DownloadRow, String> stopReasons;

    /** (row, isResume) -> startDownloadRow(row, isResume) داخل MainController */
    private final BiConsumer<DownloadRow, Boolean> startOrResume;

    /** callback بسيط لتحديث الـ UI/Sidebar بعد تغييرات الحالة */
    private final Runnable onStateChanged;

    public DownloadStateCoordinator(
            ObservableList<DownloadRow> items,
            Map<DownloadRow, Process> activeProcesses,
            Map<DownloadRow, String> stopReasons,
            BiConsumer<DownloadRow, Boolean> startOrResume,
            Runnable onStateChanged
    ) {
        this.items = Objects.requireNonNull(items, "items");
        this.activeProcesses = Objects.requireNonNull(activeProcesses, "activeProcesses");
        this.stopReasons = Objects.requireNonNull(stopReasons, "stopReasons");
        this.startOrResume = Objects.requireNonNull(startOrResume, "startOrResume");
        this.onStateChanged = (onStateChanged == null) ? () -> {} : onStateChanged;
    }

    // ===================== Public API (safe from any thread) =====================

    public void pause(DownloadRow row) {
        if (row == null) return;
        fx(() -> pauseFx(row));
    }

    public void resume(DownloadRow row) {
        if (row == null) return;
        fx(() -> resumeFx(row));
    }

    public void cancel(DownloadRow row) {
        if (row == null) return;
        fx(() -> cancelFx(row));
    }

    /** UI logic only: reset row to QUEUED and ask MainController to start it again */
    public void retry(DownloadRow row) {
        if (row == null) return;
        fx(() -> retryFx(row));
    }

    public int pauseAll() {
        // bulk should count accurately -> do everything inside one FX block
        final int[] affected = {0};
        fxAndWait(() -> {
            for (DownloadRow row : items) {
                if (row == null) continue;
                if (pauseFx(row)) affected[0]++;
            }
            onStateChanged.run();
        });
        return affected[0];
    }

    public int resumeAll() {
        final int[] affected = {0};
        fxAndWait(() -> {
            for (DownloadRow row : items) {
                if (row == null) continue;
                if (resumeFx(row)) affected[0]++;
            }
            onStateChanged.run();
        });
        return affected[0];
    }

    public int cancelAll() {
        final int[] affected = {0};
        fxAndWait(() -> {
            for (DownloadRow row : items) {
                if (row == null) continue;
                if (cancelFx(row)) affected[0]++;
            }
            onStateChanged.run();
        });
        return affected[0];
    }

    // ===================== FX-thread implementations =====================

    /** @return true if state changed */
    private boolean pauseFx(DownloadRow row) {
        DownloadRow.State st = safeState(row);
        if (st != DownloadRow.State.DOWNLOADING
                && st != DownloadRow.State.QUEUED
                && st != DownloadRow.State.PENDING) return false;

        stopProcess(row, "PAUSE");
        row.setState(DownloadRow.State.PAUSED);
        return true;
    }

    /** @return true if attempt to resume was made */
    private boolean resumeFx(DownloadRow row) {
        DownloadRow.State st = safeState(row);
        if (st != DownloadRow.State.PAUSED
                && st != DownloadRow.State.QUEUED
                && st != DownloadRow.State.PENDING) return false;

        try {
            startOrResume.accept(row, true);
            return true;
        } catch (Exception ex) {
            // keep it paused if resume fails
            row.setState(DownloadRow.State.PAUSED);
            return false;
        }
    }

    /** @return true if cancelled */
    private boolean cancelFx(DownloadRow row) {
        DownloadRow.State st = safeState(row);
        if (st == DownloadRow.State.COMPLETED || st == DownloadRow.State.CANCELLED) return false;

        stopProcess(row, "CANCEL");
        row.setState(DownloadRow.State.CANCELLED);
        return true;
    }

    /** @return true if retried */
    private boolean retryFx(DownloadRow row) {
        stopProcess(row, "RETRY");

        try { row.progress.set(0); } catch (Exception ignored) {}
        try { row.status.set("Queued"); } catch (Exception ignored) {}

        row.setState(DownloadRow.State.QUEUED);

        try {
            startOrResume.accept(row, false);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    // ===================== Helpers =====================

    private void stopProcess(DownloadRow row, String reason) {
        try { stopReasons.put(row, reason); } catch (Exception ignored) {}
        try {
            Process p = activeProcesses.get(row);
            if (p != null && p.isAlive()) p.destroy();
        } catch (Exception ignored) {}
    }

    private static DownloadRow.State safeState(DownloadRow row) {
        try {
            DownloadRow.State st = row.state.get();
            return st == null ? DownloadRow.State.PENDING : st;
        } catch (Exception ignored) {
            return DownloadRow.State.PENDING;
        }
    }

    private static void fx(Runnable r) {
        if (r == null) return;
        if (Platform.isFxApplicationThread()) r.run();
        else Platform.runLater(r);
    }

    /**
     * Executes on FX thread and waits for completion.
     * Used only for bulk ops so the returned count is accurate.
     */
    private static void fxAndWait(Runnable r) {
        if (r == null) return;
        if (Platform.isFxApplicationThread()) {
            r.run();
            return;
        }
        final Object lock = new Object();
        final boolean[] done = {false};
        Platform.runLater(() -> {
            try {
                r.run();
            } finally {
                synchronized (lock) {
                    done[0] = true;
                    lock.notifyAll();
                }
            }
        });
        synchronized (lock) {
            while (!done[0]) {
                try { lock.wait(1000L); } catch (InterruptedException ignored) { break; }
            }
        }
    }
}