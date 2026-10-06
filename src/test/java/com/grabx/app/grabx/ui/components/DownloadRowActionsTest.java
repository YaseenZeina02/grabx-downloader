package com.grabx.app.grabx.ui.components;

import com.grabx.app.grabx.core.model.DownloadRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DownloadRowActionsTest {
    @TempDir
    Path tempDir;

    @Test
    void opensDestinationDuringDownloadAndPauseWithoutChangingProgress() {
        for (var state : new DownloadRow.State[]{DownloadRow.State.DOWNLOADING, DownloadRow.State.PAUSED}) {
            for (String mode : new String[]{"Direct", "Video"}) {
                var row = new DownloadRow("url", "movie.mp4", 1, tempDir.toString(), mode, "Auto");
                row.setState(state); row.progress.set(0.6); row.downloadedBytes.set(600);
                var opened = new java.util.concurrent.atomic.AtomicReference<Path>();
                actions(opened).openFolderForDownloadRow(row);
                assertEquals(tempDir, opened.get());
                assertEquals(state, row.getState());
                assertEquals(0.6, row.progress.get());
                assertEquals(600, row.downloadedBytes.get());
            }
        }
    }

    @Test
    void prefersActualOutputParentEvenWhenOnlyHiddenPartsExist() throws Exception {
        Path nested = Files.createDirectory(tempDir.resolve("selected"));
        var row = new DownloadRow("url", "movie.mp4", 1, tempDir.toString(), "Direct", "Auto");
        row.setState(DownloadRow.State.PAUSED);
        row.outputFile.set(nested.resolve("movie.mp4"));
        Path part = Files.writeString(nested.resolve(".movie.mp4.grabx.part.0"), "saved bytes");
        var opened = new java.util.concurrent.atomic.AtomicReference<Path>();
        actions(opened).openFolderForDownloadRow(row);
        assertEquals(nested, opened.get());
        assertEquals("saved bytes", Files.readString(part));
        assertEquals(DownloadRow.State.PAUSED, row.getState());
    }

    @Test
    void unavailableFolderDoesNotOpenOrReclassifyAnUnfinishedDownload() {
        var row = new DownloadRow("url", "movie.mp4", 1, tempDir.resolve("gone").toString(), "Direct", "Auto");
        row.setState(DownloadRow.State.PAUSED);
        var opened = new java.util.concurrent.atomic.AtomicReference<Path>();
        actions(opened).openFolderForDownloadRow(row);
        assertNull(opened.get());
        assertNull(DownloadRowActions.activeDownloadFolder(row));
        assertEquals(DownloadRow.State.PAUSED, row.getState());
    }

    @Test
    void completedDownloadStillRevealsTheFinalFile() throws Exception {
        var row = row(DownloadRow.State.COMPLETED);
        Path file = Files.writeString(tempDir.resolve("movie.mp4"), "complete");
        row.outputFile.set(file);
        var opened = new java.util.concurrent.atomic.AtomicReference<Path>();
        actions(opened).openFolderForDownloadRow(row);
        assertEquals(file, opened.get());
        assertEquals(DownloadRow.State.COMPLETED, row.getState());
    }

    private static DownloadRowActions actions(java.util.concurrent.atomic.AtomicReference<Path> opened) {
        return new DownloadRowActions(null, null, null, null, null, null, null, null, opened::set);
    }

    @Test
    void doesNotConfirmWhenCompletedFileIsAlreadyGone() {
        DownloadRow row = row(DownloadRow.State.COMPLETED);
        row.progress.set(1);

        assertFalse(DownloadRowActions.requiresRemovalConfirmation(
                row, tempDir.resolve("missing.mp3"), false
        ));
    }

    @Test
    void confirmsWhenOutputStillExists() throws Exception {
        DownloadRow row = row(DownloadRow.State.COMPLETED);
        Path output = Files.writeString(tempDir.resolve("song.mp3"), "audio");

        assertTrue(DownloadRowActions.requiresRemovalConfirmation(row, output, false));
    }

    @Test
    void confirmsForAnActiveProcessEvenWithoutAnOutputFile() {
        DownloadRow row = row(DownloadRow.State.DOWNLOADING);

        assertTrue(DownloadRowActions.requiresRemovalConfirmation(row, null, true));
    }

    private static DownloadRow row(DownloadRow.State state) {
        DownloadRow row = new DownloadRow("url", "title", 1, "/downloads", "Audio only", "mp3");
        row.setState(state);
        return row;
    }
}
