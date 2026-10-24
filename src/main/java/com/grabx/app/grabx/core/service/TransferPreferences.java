package com.grabx.app.grabx.core.service;

import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/** Independent direct-total and media-per-task ceilings. Zero means unlimited. */
public final class TransferPreferences {
    public static final long MAX_RATE = 1_000_000_000L;
    private final Preferences preferences;
    public TransferPreferences(Preferences preferences) { this.preferences = preferences; }
    public static final int AUTO_CONNECTIONS = 0;
    public static final int DEFAULT_CONNECTIONS = AUTO_CONNECTIONS;
    public static final int MAX_CONNECTIONS = 64;
    public int directConnections() {
        int value = preferences.getInt("grabx.direct.connections", DEFAULT_CONNECTIONS);
        return value >= AUTO_CONNECTIONS && value <= MAX_CONNECTIONS ? value : DEFAULT_CONNECTIONS;
    }
    public void saveDirectConnections(int value) {
        if (value < AUTO_CONNECTIONS || value > MAX_CONNECTIONS) throw new IllegalArgumentException("Choose Automatic or 1 to 64 connections");
        preferences.putInt("grabx.direct.connections", value);
    }
    public boolean quickResumeChecks() { return preferences.getBoolean("grabx.direct.quickResumeChecks", false); }
    public void saveQuickResumeChecks(boolean value) { preferences.putBoolean("grabx.direct.quickResumeChecks", value); }
    public long directRate() { return valid(preferences.getLong("grabx.speed.direct", 0)); }
    public long mediaRate() { return valid(preferences.getLong("grabx.speed.media", 0)); }
    public void save(long direct, long media) {
        if (valid(direct) != direct || valid(media) != media) throw new IllegalArgumentException("Invalid speed limit");
        preferences.putLong("grabx.speed.direct", direct);
        preferences.putLong("grabx.speed.media", media);
    }
    private static long valid(long rate) { return rate >= 0 && rate <= MAX_RATE ? rate : 0; }
    public static List<String> mediaCommand(List<String> base, long rate) {
        var command = new ArrayList<>(base);
        if (rate > 0) {
            // Options must precede the URL separator if the caller uses one.
            int position = command.indexOf("--");
            if (position < 0) position = Math.min(1, command.size());
            command.addAll(position, List.of("--limit-rate", Long.toString(rate), "--concurrent-fragments", "1"));
        }
        return command;
    }
}
