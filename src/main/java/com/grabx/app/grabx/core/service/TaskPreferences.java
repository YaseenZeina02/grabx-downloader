package com.grabx.app.grabx.core.service;

import java.util.prefs.Preferences;

public final class TaskPreferences {
    public static final int DEFAULT_LIMIT = 3;
    public static final int MAX_LIMIT = 16;
    private final Preferences preferences;
    public TaskPreferences(Preferences preferences) { this.preferences = preferences; }
    public boolean autoResume() { return preferences.getBoolean("grabx.tasks.autoResume", false); }
    public void save(int limit, boolean autoResume) {
        save(limit);
        preferences.putBoolean("grabx.tasks.autoResume", autoResume);
    }
    public int maxActive() {
        int saved = preferences.getInt("grabx.tasks.maxActive", DEFAULT_LIMIT);
        return saved >= 1 && saved <= MAX_LIMIT ? saved : DEFAULT_LIMIT;
    }
    public void save(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) throw new IllegalArgumentException("Invalid task limit");
        preferences.putInt("grabx.tasks.maxActive", limit);
    }
}
