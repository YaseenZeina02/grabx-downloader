package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import javafx.collections.FXCollections;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static com.grabx.app.grabx.core.model.DownloadRow.State.*;
import static com.grabx.app.grabx.core.service.SoundFeedbackService.Cue.*;
import static org.junit.jupiter.api.Assertions.*;

class DownloadSoundObserverTest {
    DownloadRow row() { return new DownloadRow("https://example.test/file", "test", 1, "/tmp", "Direct", "Auto"); }
    @Test void firstBytesResumePauseCancelAndCompletionEachHaveTheirOwnCue() {
        var rows = FXCollections.<DownloadRow>observableArrayList();
        var heard = new ArrayList<SoundFeedbackService.Cue>();
        try (var observer = new DownloadSoundObserver(rows, heard::add)) {
            var row = row(); rows.add(row);
            row.setState(DOWNLOADING); row.status.set("Downloading");
            assertTrue(heard.isEmpty());
            row.downloadedBytes.set(10); row.downloadedBytes.set(20);
            row.setState(PAUSED); row.setState(DOWNLOADING); row.downloadedBytes.set(30);
            row.setState(COMPLETED);
            assertEquals(List.of(START, PAUSE, RESUME, COMPLETE), heard);
            var cancelled = row(); rows.add(cancelled); cancelled.setState(CANCELLED);
            assertEquals(CANCEL, heard.getLast());
        }
    }
    @Test void verificationAndRetriesRemainQuietUntilNewPayloadBytesArrive() {
        var row = row(); row.setState(PAUSED); row.downloadedBytes.set(100);
        var rows = FXCollections.observableArrayList(row);
        var heard = new ArrayList<SoundFeedbackService.Cue>();
        try (var observer = new DownloadSoundObserver(rows, heard::add)) {
            row.setState(QUEUED); row.setState(DOWNLOADING); row.status.set("Checking server");
            row.downloadedBytes.set(120); // disk checkpoint reconciliation
            row.status.set("Verifying saved data • 50%"); row.speed.set("2 MB/s");
            assertTrue(heard.isEmpty());
            row.status.set("Resuming • saved data verified"); row.downloadedBytes.set(130);
            assertEquals(List.of(RESUME), heard);
            row.status.set("Waiting for network"); row.status.set("Downloading"); row.downloadedBytes.set(140);
            assertEquals(List.of(RESUME), heard); // Automatic reconnect does not chatter.
            row.setState(FAILED); row.setState(QUEUED); row.setState(DOWNLOADING); row.downloadedBytes.set(150);
            assertEquals(List.of(RESUME, ERROR, RESUME), heard);
        }
    }
    @Test void restoredHistoryRemovedRowsAndShutdownStaySilent() {
        var rows = FXCollections.<DownloadRow>observableArrayList();
        var heard = new ArrayList<SoundFeedbackService.Cue>();
        var observer = new DownloadSoundObserver(rows, heard::add);
        var restored = row(); restored.setState(PAUSED); rows.add(restored);
        var finished = row(); finished.setState(COMPLETED); rows.add(finished); finished.setState(MISSING);
        var legacy = row(); rows.add(legacy); legacy.setState(PAUSED);
        assertTrue(heard.isEmpty());
        rows.remove(restored); restored.setState(CANCELLED);
        observer.close(); legacy.setState(DOWNLOADING); legacy.setState(PAUSED);
        assertTrue(heard.isEmpty());
    }
    @Test void extractorPreparationAndSeparateVideoAudioStreamsOnlyAnnounceOnce() {
        var rows = FXCollections.<DownloadRow>observableArrayList();
        var heard = new ArrayList<SoundFeedbackService.Cue>();
        try (var observer = new DownloadSoundObserver(rows, heard::add)) {
            var row = row(); rows.add(row); row.setState(PENDING); row.status.set("Preparing");
            assertTrue(heard.isEmpty());
            row.setState(DOWNLOADING); row.status.set("Downloading video"); row.downloadedBytes.set(10);
            row.setState(PENDING); row.setState(DOWNLOADING); row.status.set("Downloading audio"); row.downloadedBytes.set(20);
            row.setState(COMPLETED);
            assertEquals(List.of(START, COMPLETE), heard);
        }
    }
}
