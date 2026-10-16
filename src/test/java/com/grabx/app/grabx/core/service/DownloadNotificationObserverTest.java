package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import javafx.collections.FXCollections;
import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import static com.grabx.app.grabx.core.model.DownloadRow.State.*;
import static org.junit.jupiter.api.Assertions.*;

class DownloadNotificationObserverTest {
    DownloadRow row() { return new DownloadRow("https://example.test/file?secret=123", "file.mp4", 0, "/tmp", "Direct", "Auto"); }
    @Test void finalReasonIsReadAfterStateAndDoesNotExposeSignedUrls() {
        var rows = FXCollections.<DownloadRow>observableArrayList();
        var ui = new ArrayDeque<Runnable>(); var center = new NotificationCenter(NotificationPreferences.Values.defaults());
        try (var observer = new DownloadNotificationObserver(rows, center, ui::add)) {
            var row = row(); row.title.set(row.url); rows.add(row); row.setState(DOWNLOADING); row.setState(FAILED);
            row.status.set("HTTP 498 https://example.test/file?secret=123");
            assertTrue(center.entries().isEmpty()); ui.remove().run();
            assertTrue(center.entries().getFirst().message().contains("expired"));
            assertFalse(center.entries().getFirst().message().contains("secret"));
            row.setState(QUEUED); row.setState(DOWNLOADING); row.setState(COMPLETED); ui.remove().run();
            assertEquals(NotificationCenter.Kind.COMPLETED, center.entries().getFirst().kind());
        }
    }
    @Test void restoredHistoryManualActionsRemovalAndShutdownDoNotNotify() {
        var rows = FXCollections.<DownloadRow>observableArrayList();
        var ui = new ArrayDeque<Runnable>(); var center = new NotificationCenter(NotificationPreferences.Values.defaults());
        var observer = new DownloadNotificationObserver(rows, center, ui::add);
        for (var state : new DownloadRow.State[]{PAUSED, COMPLETED, FAILED, CANCELLED}) {
            var row = row(); row.setState(state); rows.add(row); row.setState(MISSING);
        }
        var row = row(); rows.add(row); row.setState(DOWNLOADING); row.setState(PAUSED); row.setState(CANCELLED);
        assertTrue(center.entries().isEmpty());
        row.setState(DOWNLOADING); row.setState(FAILED); rows.remove(row);
        while (!ui.isEmpty()) ui.remove().run(); assertTrue(center.entries().isEmpty());
        var other = row(); rows.add(other); other.setState(FAILED); observer.close();
        while (!ui.isEmpty()) ui.remove().run(); assertTrue(center.entries().isEmpty());
    }
    @Test void automaticRetriesNotifyOncePerAttemptAndDoNotClaimInternetIsOffline() {
        var row = row(); var rows = FXCollections.observableArrayList(row);
        var center = new NotificationCenter(NotificationPreferences.Values.defaults());
        try (var observer = new DownloadNotificationObserver(rows, center, Runnable::run)) {
            row.setState(DOWNLOADING);
            for (int i = 0; i < 30; i++) row.status.set("Connection interrupted • retry in " + i + "s");
            row.status.set("Downloading"); row.downloadedBytes.set(100); row.status.set("Waiting for network");
            assertEquals(1, center.entries().size()); assertFalse(center.isOffline());
            assertEquals(NotificationCenter.Kind.RECONNECTING, center.entries().getFirst().kind());
            center.connectionChanged(false);
            row.setState(PAUSED); row.setState(DOWNLOADING); row.status.set("Reconnecting");
            assertEquals(2, center.entries().size(), "Global offline event should prevent retry chatter");
        }
    }
    @Test void aRetryBeforeTheDeferredFailureDoesNotEmitAStaleFailure() {
        var row = row(); var rows = FXCollections.observableArrayList(row);
        var ui = new ArrayDeque<Runnable>(); var center = new NotificationCenter(NotificationPreferences.Values.defaults());
        try (var observer = new DownloadNotificationObserver(rows, center, ui::add)) {
            row.setState(FAILED); row.setState(QUEUED); ui.remove().run();
            assertTrue(center.entries().isEmpty());
        }
    }
}
