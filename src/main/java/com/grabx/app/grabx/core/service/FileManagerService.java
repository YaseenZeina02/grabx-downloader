package com.grabx.app.grabx.core.service;

import javafx.application.Platform;

import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public final class FileManagerService {
    @FunctionalInterface
    interface CommandLauncher {
        void launch(List<String> command) throws Exception;
    }

    @FunctionalInterface
    interface FolderOpener {
        boolean open(Path folder) throws Exception;
    }

    private final String osName;
    private final CommandLauncher commandLauncher;
    private final FolderOpener folderOpener;
    private final Consumer<Runnable> uiDispatcher;
    private final Runnable missingFileNotice;

    public FileManagerService(Runnable missingFileNotice) {
        this(
                System.getProperty("os.name", ""),
                command -> new ProcessBuilder(command).start(),
                folder -> {
                    if (!Desktop.isDesktopSupported()) return false;
                    Desktop.getDesktop().open(folder.toFile());
                    return true;
                },
                Platform::runLater,
                missingFileNotice
        );
    }

    FileManagerService(
            String osName,
            CommandLauncher commandLauncher,
            FolderOpener folderOpener,
            Consumer<Runnable> uiDispatcher,
            Runnable missingFileNotice
    ) {
        this.osName = osName == null ? "" : osName;
        this.commandLauncher = commandLauncher;
        this.folderOpener = folderOpener;
        this.uiDispatcher = uiDispatcher == null ? Runnable::run : uiDispatcher;
        this.missingFileNotice = missingFileNotice;
    }

    public void reveal(Path file) {
        Path normalized = normalize(file);
        if (normalized == null) return;
        if (!exists(normalized)) {
            if (missingFileNotice != null) uiDispatcher.accept(missingFileNotice);
            return;
        }

        try {
            if (isMac()) {
                commandLauncher.launch(List.of("open", "-R", normalized.toString()));
            } else if (isWindows()) {
                commandLauncher.launch(List.of("explorer", "/select,", normalized.toString()));
            } else {
                openFolder(normalized.getParent());
            }
        } catch (Exception ignored) {
        }
    }

    public void openFolder(Path folder) {
        Path normalized = normalize(folder);
        if (normalized == null) return;

        try {
            if (folderOpener != null && folderOpener.open(normalized)) return;
        } catch (Exception ignored) {
        }

        try {
            commandLauncher.launch(folderCommand(osName, normalized));
        } catch (Exception ignored) {
        }
    }

    static List<String> folderCommand(String osName, Path folder) {
        String normalizedOs = normalizeOs(osName);
        if (normalizedOs.contains("mac")) return List.of("open", folder.toString());
        if (normalizedOs.contains("win")) return List.of("explorer", folder.toString());
        return List.of("xdg-open", folder.toString());
    }

    private boolean isMac() {
        return normalizeOs(osName).contains("mac");
    }

    private boolean isWindows() {
        return normalizeOs(osName).contains("win");
    }

    private static String normalizeOs(String osName) {
        return osName == null ? "" : osName.toLowerCase(Locale.ROOT);
    }

    private static Path normalize(Path path) {
        try {
            return path == null ? null : path.toAbsolutePath().normalize();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean exists(Path path) {
        try {
            return Files.exists(path);
        } catch (Exception ignored) {
            return false;
        }
    }
}
