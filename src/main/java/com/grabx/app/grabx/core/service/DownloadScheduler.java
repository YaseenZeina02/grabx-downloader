package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;

import java.util.*;
import java.util.function.*;

/** FX-thread admission queue, shared by single links, playlists and resumes. */
public final class DownloadScheduler implements AutoCloseable {
    private final ObservableList<DownloadRow> items;
    private final BiConsumer<DownloadRow, Boolean> start;
    private final BiPredicate<DownloadRow, Boolean> ready;
    private final Predicate<DownloadRow> busy;
    private final Consumer<Runnable> later;
    private final LinkedHashMap<DownloadRow, Boolean> waiting = new LinkedHashMap<>();
    private final Set<DownloadRow> admitted = new HashSet<>();
    private final Map<DownloadRow, ChangeListener<DownloadRow.State>> listeners = new IdentityHashMap<>();
    private final ListChangeListener<DownloadRow> listListener;
    private int limit = TaskPreferences.DEFAULT_LIMIT;
    private boolean scheduled, draining, closed;

    public DownloadScheduler(ObservableList<DownloadRow> items, BiConsumer<DownloadRow, Boolean> start,
                             BiPredicate<DownloadRow, Boolean> ready, Predicate<DownloadRow> busy,
                             Consumer<Runnable> later) {
        this.items = items; this.start = start; this.ready = ready; this.busy = busy; this.later = later;
        listListener = change -> {
            while (change.next()) {
                for (DownloadRow row : change.getRemoved()) {
                    waiting.remove(row);
                    ChangeListener<DownloadRow.State> listener = listeners.remove(row);
                    if (listener != null) row.stateProperty().removeListener(listener);
                }
            }
            wake();
        };
        items.addListener(listListener);
    }
    public void setLimit(int value) {
        if (value < 1 || value > TaskPreferences.MAX_LIMIT) throw new IllegalArgumentException("Invalid task limit");
        limit = value; wake();
    }
    public void request(DownloadRow row, boolean resume) {
        if (closed || row == null || !items.contains(row)) return;
        if (admitted.contains(row) && isActive(row) && !ready.test(row, resume)) return;
        if (!listeners.containsKey(row)) {
            ChangeListener<DownloadRow.State> listener = (obs, before, after) -> wake();
            listeners.put(row, listener); row.stateProperty().addListener(listener);
        }
        // putIfAbsent retains submission order and original resume intent on double-click.
        waiting.putIfAbsent(row, resume);
        showQueued(row); wake();
    }
    /** Called before explicit Pause/Cancel/Retry, so old worker callbacks cannot revive the request. */
    public void withdraw(DownloadRow row) { waiting.remove(row); wake(); }
    public void wake() {
        if (closed || scheduled || draining) return;
        scheduled = true;
        // Production uses Platform.runLater: bulk pause/cancel completes before admission.
        later.accept(() -> { scheduled = false; drain(); });
    }
    private void drain() {
        if (closed || draining) return;
        draining = true;
        try {
            admitted.removeIf(row -> (!items.contains(row) || !isActive(row)) && !busy.test(row));
            waiting.keySet().removeIf(row -> !items.contains(row));
            for (DownloadRow row : waiting.keySet()) showQueued(row);
            while (admitted.size() < limit) {
                DownloadRow next = null;
                boolean resume = false;
                for (var entry : waiting.entrySet()) {
                    // A stopped media worker must finish its cleanup before the same row restarts.
                    if (!admitted.contains(entry.getKey()) && ready.test(entry.getKey(), entry.getValue())) {
                        next = entry.getKey(); resume = entry.getValue(); break;
                    }
                }
                if (next == null) break;
                waiting.remove(next); admitted.add(next);
                next.setState(DownloadRow.State.PENDING);
                next.status.set("Preparing");
                try { start.accept(next, resume); }
                catch (RuntimeException error) {
                    admitted.remove(next); next.setState(DownloadRow.State.FAILED);
                    next.status.set("Could not start download");
                }
                // Handles synchronous completion/failure in adapters as well as async engines.
                admitted.removeIf(row -> !isActive(row) && !busy.test(row));
            }
        } finally { draining = false; }
    }
    private static boolean isActive(DownloadRow row) {
        return row.getState() == DownloadRow.State.PENDING || row.getState() == DownloadRow.State.DOWNLOADING;
    }
    private static void showQueued(DownloadRow row) {
        row.setState(DownloadRow.State.QUEUED);
        row.status.set("Queued • waiting for a free slot"); row.speed.set(""); row.eta.set("");
    }
    @Override public void close() {
        closed = true; waiting.clear(); admitted.clear();
        items.removeListener(listListener);
        listeners.forEach((row, listener) -> row.stateProperty().removeListener(listener)); listeners.clear();
    }
}
