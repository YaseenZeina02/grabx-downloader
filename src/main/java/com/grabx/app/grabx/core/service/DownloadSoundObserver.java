package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Observes user-visible transitions without changing download behavior. FX thread only. */
public final class DownloadSoundObserver implements AutoCloseable {
    private final ObservableList<DownloadRow> rows;
    private final Consumer<SoundFeedbackService.Cue> sounds;
    private final Map<DownloadRow, Watch> watches = new IdentityHashMap<>();
    private final ListChangeListener<DownloadRow> listListener = change -> {
        while (change.next()) {
            if (change.wasRemoved()) change.getRemoved().forEach(this::detach);
            if (change.wasAdded()) change.getAddedSubList().forEach(this::attach);
        }
    };

    public DownloadSoundObserver(ObservableList<DownloadRow> rows, Consumer<SoundFeedbackService.Cue> sounds) {
        this.rows = rows; this.sounds = sounds;
        rows.forEach(this::attach);
        rows.addListener(listListener);
    }

    private void attach(DownloadRow row) { watches.computeIfAbsent(row, Watch::new); }
    private void detach(DownloadRow row) { Watch watch = watches.remove(row); if (watch != null) watch.close(); }
    @Override public void close() { rows.removeListener(listListener); watches.values().forEach(Watch::close); watches.clear(); }

    private final class Watch {
        final DownloadRow row;
        long baseline;
        boolean live, announced;
        SoundFeedbackService.Cue next;
        final ChangeListener<DownloadRow.State> stateListener = (o, before, after) -> changed(before, after);
        final ChangeListener<Number> bytesListener = (o, before, after) -> progress();
        Watch(DownloadRow row) {
            this.row = row;
            baseline = row.downloadedBytes.get();
            next = baseline > 0 || row.getState() == DownloadRow.State.PAUSED || row.getState() == DownloadRow.State.FAILED
                    ? SoundFeedbackService.Cue.RESUME : SoundFeedbackService.Cue.START;
            row.state.addListener(stateListener); row.downloadedBytes.addListener(bytesListener);
        }
        void changed(DownloadRow.State before, DownloadRow.State after) {
            if (after == DownloadRow.State.QUEUED || after == DownloadRow.State.PENDING || after == DownloadRow.State.DOWNLOADING) {
                live = true;
                if (before == DownloadRow.State.PAUSED || before == DownloadRow.State.FAILED || before == DownloadRow.State.CANCELLED) {
                    next = SoundFeedbackService.Cue.RESUME; announced = false; baseline = row.downloadedBytes.get();
                }
            } else if (after == DownloadRow.State.PAUSED) {
                if (live) sounds.accept(SoundFeedbackService.Cue.PAUSE);
                live = false;
            } else if (after == DownloadRow.State.CANCELLED) {
                if (before != DownloadRow.State.COMPLETED && before != DownloadRow.State.MISSING)
                    sounds.accept(SoundFeedbackService.Cue.CANCEL);
                live = false;
            } else if (after == DownloadRow.State.COMPLETED || after == DownloadRow.State.FAILED) {
                if (live) sounds.accept(after == DownloadRow.State.COMPLETED ? SoundFeedbackService.Cue.COMPLETE : SoundFeedbackService.Cue.ERROR);
                live = false;
            }
        }
        void progress() {
            long current = row.downloadedBytes.get();
            String status = row.status.get();
            boolean checking = status != null && (status.startsWith("Checking") || status.startsWith("Verifying")
                    || status.startsWith("Reconnecting") || status.startsWith("Waiting for network"));
            if (row.getState() == DownloadRow.State.DOWNLOADING && !checking && current > baseline && !announced) {
                announced = true; live = true; sounds.accept(next);
            }
            baseline = current;
        }
        void close() { row.state.removeListener(stateListener); row.downloadedBytes.removeListener(bytesListener); }
    }
}
