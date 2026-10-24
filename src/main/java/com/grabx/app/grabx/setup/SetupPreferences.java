package com.grabx.app.grabx.setup;

import com.grabx.app.grabx.core.service.DownloadFolderPreferences;
import com.grabx.app.grabx.core.service.LanguagePreferences;
import com.grabx.app.grabx.core.service.TaskPreferences;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.prefs.Preferences;

public final class SetupPreferences {
    private final Preferences store;
    private final Path home;
    public enum FolderProblem { EMPTY, INVALID_PATH, NOT_FOUND, NOT_DIRECTORY, NOT_WRITABLE }
    public static final class FolderValidationException extends IOException {
        private final FolderProblem problem;
        public FolderValidationException(FolderProblem problem) {
            super(switch (problem) {
                case EMPTY -> "Choose a download folder.";
                case INVALID_PATH -> "Enter a valid absolute folder path.";
                case NOT_FOUND -> "This folder does not exist. Choose an existing folder.";
                case NOT_DIRECTORY -> "This path points to a file. Choose a folder.";
                case NOT_WRITABLE -> "GrabX cannot write to this folder. Choose another folder.";
            });
            this.problem = problem;
        }
        public FolderProblem problem() { return problem; }
    }
    public SetupPreferences(Preferences store) { this(store, Path.of(System.getProperty("user.home"))); }
    public SetupPreferences(Preferences store, Path home) { this.store = store; this.home = home.toAbsolutePath().normalize(); }
    public boolean completed() { return store.getBoolean("grabx.setup.complete.v1", false); }
    public Path suggestedFolder() { return home.resolve("Downloads/GrabX"); }
    public String initialFolder() {
        return completed() ? new DownloadFolderPreferences(store).configuredFolder() : suggestedFolder().toString();
    }
    /** Validate at the Defaults step and again at Finish, without committing the draft. */
    public Path validateFolder(String folder) throws FolderValidationException {
        if (folder == null || folder.isBlank()) throw new FolderValidationException(FolderProblem.EMPTY);
        Path directory;
        try {
            directory = Path.of(folder);
            if (!directory.isAbsolute()) throw new IllegalArgumentException();
            directory = directory.normalize();
        } catch (IllegalArgumentException e) { throw new FolderValidationException(FolderProblem.INVALID_PATH); }
        Path writable = directory;
        if (!Files.exists(directory)) {
            // The suggested GrabX folder is the only missing path we offer to create.
            // Arbitrary typed paths must already exist, so typos cannot pass silently.
            if (!directory.equals(suggestedFolder())) throw new FolderValidationException(FolderProblem.NOT_FOUND);
            while (writable != null && !Files.exists(writable)) writable = writable.getParent();
        }
        if (writable == null || !Files.isDirectory(writable)) throw new FolderValidationException(FolderProblem.NOT_DIRECTORY);
        try {
            Path probe = Files.createTempFile(writable, ".grabx-write-check-", ".tmp");
            Files.delete(probe);
        } catch (IOException | SecurityException e) { throw new FolderValidationException(FolderProblem.NOT_WRITABLE); }
        return directory;
    }
    public void finish(String folder, String language, boolean remember, int simultaneous, boolean sounds) throws IOException {
        if (!LanguagePreferences.valid(language)) throw new IllegalArgumentException("Choose a supported language.");
        if (simultaneous < 1 || simultaneous > TaskPreferences.MAX_LIMIT) throw new IllegalArgumentException("Invalid queue limit.");
        Path directory = validateFolder(folder);
        if (directory.equals(suggestedFolder())) Files.createDirectories(directory);
        new DownloadFolderPreferences(store).configure(directory.toString(), remember);
        new LanguagePreferences(store).save(language);
        new TaskPreferences(store).save(simultaneous);
        store.putBoolean("grabx.sounds.enabled", sounds);
        store.putBoolean("grabx.setup.complete.v1", true);
        try { store.flush(); } catch (java.util.prefs.BackingStoreException e) { throw new IOException("Could not save setup. Please try again.", e); }
    }
}
