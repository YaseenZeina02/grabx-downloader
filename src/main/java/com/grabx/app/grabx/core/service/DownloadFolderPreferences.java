package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.MainController;

import java.nio.file.Path;
import java.util.prefs.Preferences;

/** Keeps a fixed default separately from the last selected folder. */
public final class DownloadFolderPreferences {
    private static final String LAST_FOLDER_KEY = "gx_last_download_folder";

    private final Preferences preferences;

    public DownloadFolderPreferences() {
        // Keep the original preference node so existing users retain their saved folder.
        this(Preferences.userNodeForPackage(MainController.class));
    }

    public DownloadFolderPreferences(Preferences preferences) { this.preferences = preferences; }

    public boolean remembersLastFolder() { return preferences.getBoolean("grabx.folder.remember", true); }
    public String configuredFolder() {
        String folder = preferences.get("grabx.folder.default", "");
        return folder.isBlank() ? systemDefault() : folder;
    }
    public void configure(String folder, boolean remember) {
        String normalized = Path.of(folder).toAbsolutePath().normalize().toString();
        // An explicit default change must take effect even when remembering folders.
        // Saving unrelated settings must not reset the last one-off destination.
        if (!normalized.equals(configuredFolder())) saveLastFolder(normalized);
        preferences.put("grabx.folder.default", normalized);
        preferences.putBoolean("grabx.folder.remember", remember);
    }
    private String systemDefault() {
        return Path.of(System.getProperty("user.home"), "Downloads").toString();
    }

    public String getLastFolderOrDefault() {
        if (!remembersLastFolder()) return configuredFolder();
        try {
            String savedFolder = preferences.get(LAST_FOLDER_KEY, null);
            if (savedFolder != null && !savedFolder.trim().isEmpty()) {
                return Path.of(savedFolder.trim()).toAbsolutePath().normalize().toString();
            }
        } catch (Exception ignored) {
            // Fall through to the platform default.
        }

        try {
            return configuredFolder();
        } catch (Exception ignored) {
            return System.getProperty("user.home");
        }
    }

    public void saveLastFolder(String folder) {
        if (folder == null || folder.trim().isEmpty()) return;

        try {
            preferences.put(LAST_FOLDER_KEY, folder.trim());
            preferences.flush();
        } catch (Exception ignored) {
            // Preferences are best-effort; a failure must not block the download flow.
        }
    }
}
