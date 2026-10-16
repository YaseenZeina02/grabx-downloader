package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Observes outcomes without changing transfers, retries, sounds or saved data. */
public final class DownloadNotificationObserver implements AutoCloseable {
    private final ObservableList<DownloadRow> rows;
    private final NotificationCenter center;
    private final Consumer<Runnable> later;
    private final Map<DownloadRow, Watch> watches = new IdentityHashMap<>();
    private final ListChangeListener<DownloadRow> listener = change -> {
        while (change.next()) {
            change.getRemoved().forEach(this::detach);
            change.getAddedSubList().forEach(this::attach);
        }
    };
    public DownloadNotificationObserver(ObservableList<DownloadRow> rows, NotificationCenter center, Consumer<Runnable> later) {
        this.rows = rows; this.center = center; this.later = later;
        rows.forEach(this::attach); rows.addListener(listener);
    }
    private void attach(DownloadRow row) { watches.computeIfAbsent(row, Watch::new); }
    private void detach(DownloadRow row) { Watch watch = watches.remove(row); if (watch != null) watch.close(); }
    @Override public void close() { rows.removeListener(listener); watches.values().forEach(Watch::close); watches.clear(); }
    private static boolean active(DownloadRow.State state) {
        return state == DownloadRow.State.PENDING || state == DownloadRow.State.DOWNLOADING || state == DownloadRow.State.QUEUED;
    }
    private static String displayName(DownloadRow row) {
        String title = row.title.get();
        if (title == null || title.isBlank() || title.trim().toLowerCase(Locale.ROOT).startsWith("http")) return "Download";
        title = title.replaceAll("[\\p{Cntrl}]", " ");
        return title.length() > 160 ? title.substring(0, 157) + "…" : title;
    }
    static String failureMessage(String status) {
        String text = status == null ? "" : status.toLowerCase(Locale.ROOT);
        if (text.contains("498") || text.contains("403") || text.contains("expired") || text.contains("session rejected"))
            return "The download link was rejected or expired. Get a fresh link to try again.";
        if (text.contains("identity") || text.contains("mismatch") || text.contains("verification") || text.contains("changed"))
            return "The saved data could not be verified. Review the download before retrying.";
        if (text.contains("space") || text.contains("disk full")) return "There is not enough free disk space. Free some space before retrying.";
        if (text.contains("permission") || text.contains("access denied")) return "The destination is not writable. Check the folder permissions.";
        return "Open the download card for details and retry when the problem is resolved.";
    }
    private final class Watch {
        final DownloadRow row;
        boolean live, retryAnnounced, attached = true;
        long revision;
        final ChangeListener<DownloadRow.State> state = (o, before, after) -> stateChanged(before, after);
        final ChangeListener<String> status = (o, before, after) -> statusChanged(after);
        Watch(DownloadRow row) {
            this.row = row; live = active(row.getState());
            row.state.addListener(state); row.status.addListener(status);
        }
        void stateChanged(DownloadRow.State before, DownloadRow.State after) {
            long current = ++revision;
            if (active(after)) {
                live = true;
                if (!active(before)) retryAnnounced = false;
                return;
            }
            boolean announce = live && (after == DownloadRow.State.COMPLETED || after == DownloadRow.State.FAILED);
            live = false;
            if (!announce) return;
            // Runner often writes the final reason immediately after changing state.
            later.accept(() -> {
                if (!attached || revision != current || row.getState() != after) return;
                boolean success = after == DownloadRow.State.COMPLETED;
                String name = displayName(row);
                // Never copy raw engine errors: they can include signed URLs or cookies.
                center.publish(success ? NotificationCenter.Kind.COMPLETED : NotificationCenter.Kind.FAILED,
                        success ? "Download complete" : "Download failed",
                        name + "\n" + (success ? "Your file is ready in its download folder." : failureMessage(row.status.get())));
            });
        }
        void statusChanged(String text) {
            if (!live || retryAnnounced || text == null || center.isOffline()) return;
            String value = text.toLowerCase(Locale.ROOT);
            if (!(value.contains("retry in") || value.startsWith("reconnecting") || value.startsWith("waiting for network"))) return;
            retryAnnounced = true;
            center.publish(NotificationCenter.Kind.RECONNECTING, "Download is reconnecting",
                    displayName(row) + "\nGrabX is retrying the connection. This may be a server issue; saved progress is kept.");
        }
        void close() { attached = false; row.state.removeListener(state); row.status.removeListener(status); }
    }
}
