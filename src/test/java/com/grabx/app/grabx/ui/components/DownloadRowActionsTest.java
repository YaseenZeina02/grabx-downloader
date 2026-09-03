package com.grabx.app.grabx.ui.components;

import com.grabx.app.grabx.core.model.DownloadRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadRowActionsTest {
    @TempDir
    Path tempDir;

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
