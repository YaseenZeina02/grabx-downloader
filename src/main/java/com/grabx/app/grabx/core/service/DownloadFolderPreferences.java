package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.MainController;

import java.nio.file.Path;
import java.util.prefs.Preferences;

/** Persists the last folder selected by the user for a download. */
public final class DownloadFolderPreferences {
    private static final String LAST_FOLDER_KEY = "gx_last_download_folder";

    private final Preferences preferences;

    public DownloadFolderPreferences() {
        // Keep the original preference node so existing users retain their saved folder.
        preferences = Preferences.userNodeForPackage(MainController.class);
    }

    public String getLastFolderOrDefault() {
        try {
            String savedFolder = preferences.get(LAST_FOLDER_KEY, null);
            if (savedFolder != null && !savedFolder.trim().isEmpty()) {
                return Path.of(savedFolder.trim()).toAbsolutePath().normalize().toString();
            }
        } catch (Exception ignored) {
            // Fall through to the platform default.
        }

        try {
            return Path.of(System.getProperty("user.home"), "Downloads").toString();
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
