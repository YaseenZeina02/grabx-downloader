package com.grabx.app.grabx.core.service;

import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import java.time.Clock;
import java.time.Instant;
import java.util.function.Consumer;

/** Bounded, session-only notification inbox. All mutations occur on the FX thread. */
public final class NotificationCenter implements AutoCloseable {
    public enum Kind { COMPLETED, FAILED, OFFLINE, ONLINE, RECONNECTING }
    public record Entry(Kind kind, String title, String message, Instant time, int count, boolean read) {}
    private final ObservableList<Entry> entries = FXCollections.observableArrayList();
    private final ObservableList<Entry> view = FXCollections.unmodifiableObservableList(entries);
    private final ReadOnlyIntegerWrapper unread = new ReadOnlyIntegerWrapper();
    private final Clock clock;
    private Consumer<Entry> banner = entry -> {};
    private volatile NotificationPreferences.Values preferences;
    private volatile boolean closed;
    private boolean offline;
    public NotificationCenter(NotificationPreferences.Values preferences) { this(preferences, Clock.systemUTC()); }
    NotificationCenter(NotificationPreferences.Values preferences, Clock clock) { this.preferences = preferences; this.clock = clock; }
    public ObservableList<Entry> entries() { return view; }
    public ReadOnlyIntegerProperty unreadProperty() { return unread.getReadOnlyProperty(); }
    public NotificationPreferences.Values preferences() { return preferences; }
    public void configure(NotificationPreferences.Values values) {
        preferences = values;
        if (!monitorsConnection()) offline = false;
    }
    public void onBanner(Consumer<Entry> consumer) { banner = consumer; }
    public boolean monitorsConnection() { return !closed && preferences.enabled() && preferences.connection(); }
    public boolean isOffline() { return offline; }
    public void connectionChanged(boolean connected) {
        offline = !connected;
        publish(connected ? Kind.ONLINE : Kind.OFFLINE,
                connected ? "Connection restored" : "Internet connection unavailable",
                connected ? "Connection checks are responding again. Retrying downloads can continue; failed tasks may need Retry."
                        : "Connection checks are not responding. Existing saved data is kept; downloads may wait or fail.");
    }
    public void publish(Kind kind, String title, String message) {
        if (closed || !allowed(kind)) return;
        Instant now = clock.instant();
        int count = 1;
        if (!entries.isEmpty() && (kind == Kind.COMPLETED || kind == Kind.FAILED)) {
            Entry previous = entries.getFirst();
            if (previous.kind() == kind && now.toEpochMilli() - previous.time().toEpochMilli() <= 3000) {
                count = previous.count() + 1;
                entries.removeFirst();
                title = count + (kind == Kind.COMPLETED ? " downloads completed" : " downloads failed");
                message = "Latest: " + message;
            }
        }
        Entry entry = new Entry(kind, title, message, now, count, false);
        entries.addFirst(entry);
        if (entries.size() > 50) entries.remove(50, entries.size());
        recount();
        if (preferences.banners()) banner.accept(entry);
    }
    private boolean allowed(Kind kind) {
        if (!preferences.enabled()) return false;
        return switch (kind) {
            case COMPLETED -> preferences.completed();
            case FAILED -> preferences.failed();
            default -> preferences.connection();
        };
    }
    public void markRead() {
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (!e.read()) entries.set(i, new Entry(e.kind(), e.title(), e.message(), e.time(), e.count(), true));
        }
        recount();
    }
    public void remove(Entry entry) { entries.remove(entry); recount(); }
    public void clear() { entries.clear(); recount(); }
    private void recount() { unread.set((int) entries.stream().filter(e -> !e.read()).count()); }
    @Override public void close() { closed = true; banner = entry -> {}; }
}
