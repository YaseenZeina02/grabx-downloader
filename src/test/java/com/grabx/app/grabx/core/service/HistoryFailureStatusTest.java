package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HistoryFailureStatusTest {
    @Test void migratesOnlyKnownDirectFailureMessagesIncludingPreviouslyTruncatedOnes() {
        for (String message : new String[]{
                "Download link/session rejected (HTTP 498); get a fresh link in the browser • saved data ke…",
                "Remote file version changed • saved data kept",
                "Saved state needs verification • partial files kept",
                "Link not updated: Cannot safely resume",
                "Server does not support resume or the file changed (HTTP 200)"}) {
            var row = row("Direct");
            HistoryService.restoreFailureStatus(row, message);
            assertEquals(DownloadRow.State.FAILED, row.getState(), message);
            assertEquals(message, row.status.get());
        }
    }

    @Test void preservesUserPauseAndDoesNotMigrateVideoTasksOrCancelledRows() {
        for (String message : new String[]{"", "Paused", "Paused • saved data ready to resume", "Waiting for network"}) {
            var row = row("Direct");
            HistoryService.restoreFailureStatus(row, message);
            assertEquals(DownloadRow.State.PAUSED, row.getState());
        }
        var video = row("Video");
        HistoryService.restoreFailureStatus(video, "HTTP 498");
        assertEquals(DownloadRow.State.PAUSED, video.getState());
        var cancelled = row("Direct"); cancelled.setState(DownloadRow.State.CANCELLED);
        HistoryService.restoreFailureStatus(cancelled, "HTTP 498");
        assertEquals(DownloadRow.State.CANCELLED, cancelled.getState());
    }

    private DownloadRow row(String mode) {
        var row = new DownloadRow("https://example.com/file", "file", 1, "/tmp", mode, "Auto");
        row.setState(DownloadRow.State.PAUSED);
        return row;
    }
}
