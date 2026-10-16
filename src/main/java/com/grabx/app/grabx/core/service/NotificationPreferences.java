package com.grabx.app.grabx.core.service;

import java.util.prefs.Preferences;

/** Visual feedback is independent of the existing sound preferences. */
public final class NotificationPreferences {
    public record Values(boolean enabled, boolean completed, boolean failed, boolean connection, boolean banners) {
        public static Values defaults() { return new Values(true, true, true, true, true); }
    }
    private final Preferences prefs;
    public NotificationPreferences(Preferences prefs) { this.prefs = prefs; }
    public Values load() {
        return new Values(get("enabled"), get("completed"), get("failed"), get("connection"), get("banners"));
    }
    private boolean get(String key) { return prefs.getBoolean("grabx.notifications." + key, true); }
    public void save(Values values) {
        prefs.putBoolean("grabx.notifications.enabled", values.enabled());
        prefs.putBoolean("grabx.notifications.completed", values.completed());
        prefs.putBoolean("grabx.notifications.failed", values.failed());
        prefs.putBoolean("grabx.notifications.connection", values.connection());
        prefs.putBoolean("grabx.notifications.banners", values.banners());
    }
}
