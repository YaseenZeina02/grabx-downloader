package com.grabx.app.grabx.ui.components;

import com.grabx.app.grabx.core.model.DownloadRow;
import com.grabx.app.grabx.core.service.DownloadStateCoordinator;
import com.grabx.app.grabx.ui.dialogs.NativeDialogs;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.control.Label;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class DownloadRowActions {
    private final DownloadStateCoordinator downloadStateCoordinator;
    private final Map<DownloadRow, Process> activeProcesses;
    private final Map<DownloadRow, String> stopReasons;
    private final ObservableList<DownloadRow> downloadItems;
    private final Label statusText;
    private final BiConsumer<DownloadRow, Boolean> startDownloadRow;
    private final Runnable updateMissingSidebarItem;
    private final Runnable refilterDownloads;
    private final Consumer<Path> revealInFileManager;

    public DownloadRowActions(
            DownloadStateCoordinator downloadStateCoordinator,
            Map<DownloadRow, Process> activeProcesses,
            Map<DownloadRow, String> stopReasons,
            ObservableList<DownloadRow> downloadItems,
            Label statusText,
            BiConsumer<DownloadRow, Boolean> startDownloadRow,
            Runnable updateMissingSidebarItem,
            Runnable refilterDownloads,
            Consumer<Path> revealInFileManager
    ) {
        this.downloadStateCoordinator = downloadStateCoordinator;
        this.activeProcesses = activeProcesses;
        this.stopReasons = stopReasons;
        this.downloadItems = downloadItems;
        this.statusText = statusText;
        this.startDownloadRow = startDownloadRow;
        this.updateMissingSidebarItem = updateMissingSidebarItem == null ? () -> {} : updateMissingSidebarItem;
        this.refilterDownloads = refilterDownloads == null ? () -> {} : refilterDownloads;
        this.revealInFileManager = revealInFileManager;
    }

    public void openDownloadLink(DownloadRow row) {
        if (row == null || row.url == null || row.url.isBlank()) return;
        try {
            Desktop.getDesktop().browse(new URI(row.url.trim()));
        } catch (Exception ignored) {}
    }

    public void retryDownloadRow(DownloadRow row) {
        if (row == null) return;

        try {
            if (downloadStateCoordinator != null) {
                downloadStateCoordinator.cancel(row);
            }
        } catch (Exception ignored) {}

        try { row.progress.set(0); } catch (Exception ignored) {}
        try { row.speed.set("0 KB/s"); } catch (Exception ignored) {}
        try { row.eta.set("--"); } catch (Exception ignored) {}
        try { row.setState(DownloadRow.State.QUEUED); } catch (Exception ignored) {}

        try {
            if (statusText != null && row.title != null) {
                statusText.setText("Retry: " + row.title.get());
            }
        } catch (Exception ignored) {}

        try {
            if (startDownloadRow != null) {
                startDownloadRow.accept(row, true);
            }
        } catch (Exception ignored) {}

        try { updateMissingSidebarItem.run(); } catch (Exception ignored) {}
    }

    public void openFolderForDownloadRow(DownloadRow row) {
        if (row == null) return;

        try {
            if (row.state == null || row.state.get() != DownloadRow.State.COMPLETED) return;

            Path outFile = row.outputFile == null ? null : row.outputFile.get();
            if (outFile != null) {
                Path abs = outFile.toAbsolutePath().normalize();
                if (Files.exists(abs)) {
                    if (revealInFileManager != null) {
                        revealInFileManager.accept(abs);
                    }
                    return;
                }
            }

            if (statusText != null) {
                String name = row.title == null ? "This file" : row.title.get();
                statusText.setText(name + " was moved or deleted.");
            }

            row.setState(DownloadRow.State.MISSING);

            Platform.runLater(() -> {
                try { updateMissingSidebarItem.run(); } catch (Exception ignored) {}
                try { refilterDownloads.run(); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    public void clearDownloadRow(DownloadRow row) {
        if (row == null) return;

        Path absOut = null;
        try {
            Path out = (row.outputFile != null) ? row.outputFile.get() : null;
            if (out != null) {
                absOut = out.toAbsolutePath().normalize();
            }
        } catch (Exception ignored) {}

        boolean processAlive = false;
        try {
            Process pr = activeProcesses == null ? null : activeProcesses.get(row);
            processAlive = pr != null && pr.isAlive();
        } catch (Exception ignored) {}

        boolean risky = requiresRemovalConfirmation(row, absOut, processAlive);

        String fileName = null;
        try { fileName = (row.title == null) ? null : row.title.get(); } catch (Exception ignored) {}
        if (fileName == null || fileName.isBlank()) fileName = "this download";

        boolean deleteFiles = false;
        if (risky) {
            NativeDialogs.RemoveChoice choice = NativeDialogs.showRemoveConfirm(fileName, true);

            if (choice == null || choice == NativeDialogs.RemoveChoice.CANCEL) {
                return;
            }

            deleteFiles = (choice == NativeDialogs.RemoveChoice.REMOVE_AND_DELETE);
        }

        try {
            if (downloadStateCoordinator != null) {
                downloadStateCoordinator.cancel(row);
            }
        } catch (Exception ignored) {}

        try { if (activeProcesses != null) activeProcesses.remove(row); } catch (Exception ignored) {}
        try { if (stopReasons != null) stopReasons.remove(row); } catch (Exception ignored) {}

        if (deleteFiles) {
            deleteKnownOutputFiles(absOut);
            deleteCommonPartialFiles(row);
        }

        Platform.runLater(() -> {
            try {
                if (downloadItems != null) downloadItems.remove(row);
                updateMissingSidebarItem.run();
            } catch (Exception ignored) {}
        });
    }

    static boolean requiresRemovalConfirmation(DownloadRow row, Path outputFile, boolean processAlive) {
        if (row == null) return false;
        if (processAlive) return true;

        try {
            if (outputFile != null && Files.exists(outputFile)) return true;
        } catch (Exception ignored) {}

        DownloadRow.State state = null;
        try { state = row.getState(); } catch (Exception ignored) {}
        if (state == DownloadRow.State.COMPLETED || state == DownloadRow.State.MISSING) {
            return false;
        }

        try {
            return row.progress != null && row.progress.get() > 0.0001;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void deleteKnownOutputFiles(Path absOut) {
        if (absOut == null) return;

        try { Files.deleteIfExists(absOut); } catch (Exception ignored) {}
        try { Files.deleteIfExists(Paths.get(absOut + ".part")); } catch (Exception ignored) {}
        try { Files.deleteIfExists(Paths.get(absOut + ".ytdl")); } catch (Exception ignored) {}
        try { Files.deleteIfExists(Paths.get(absOut + ".temp")); } catch (Exception ignored) {}
        try { Files.deleteIfExists(Paths.get(absOut + ".tmp")); } catch (Exception ignored) {}
    }

    private void deleteCommonPartialFiles(DownloadRow row) {
        try {
            String folderStr = (row.folder == null) ? null : row.folder.trim();
            if (folderStr == null || folderStr.isBlank()) return;

            Path dir = Paths.get(folderStr).toAbsolutePath().normalize();
            if (!Files.exists(dir) || !Files.isDirectory(dir)) return;

            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                for (Path pth : ds) {
                    if (pth == null) continue;

                    String n = null;
                    try { n = pth.getFileName().toString().toLowerCase(Locale.ROOT); } catch (Exception ignored) {}
                    if (n == null) continue;

                    boolean isPartial = n.endsWith(".part")
                            || n.endsWith(".ytdl")
                            || n.endsWith(".tmp")
                            || n.endsWith(".temp")
                            || n.endsWith(".part-frag")
                            || n.endsWith(".f");

                    if (isPartial) {
                        try { Files.deleteIfExists(pth); } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}
    }
}
