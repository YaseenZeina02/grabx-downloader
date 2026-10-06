package com.grabx.app.grabx.core.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DownloadPreferencesTest {
    @Test void fixedFolderDoesNotFollowOneOffDestinationsAndRememberModeMigrates() {
        var store = new SoundFeedbackServiceTest.MemoryPreferences();
        store.put("gx_last_download_folder", "/tmp/old-downloads");
        var folders = new DownloadFolderPreferences(store);
        assertTrue(folders.remembersLastFolder());
        assertEquals("/tmp/old-downloads", folders.getLastFolderOrDefault());
        folders.configure("/tmp/fixed", false);
        folders.saveLastFolder("/tmp/one-off");
        assertEquals("/tmp/fixed", folders.getLastFolderOrDefault());
        folders = new DownloadFolderPreferences(store);
        assertEquals("/tmp/fixed", folders.getLastFolderOrDefault());
        folders.configure("/tmp/fixed", true);
        assertEquals("/tmp/one-off", folders.getLastFolderOrDefault());
    }
    @Test void limitsPersistIndependentlyAndBadSavedValuesFallBackToUnlimited() {
        var store = new SoundFeedbackServiceTest.MemoryPreferences();
        var limits = new TransferPreferences(store);
        assertEquals(0, limits.directRate()); assertEquals(0, limits.mediaRate());
        limits.save(2_000_000, 750_000);
        var restored = new TransferPreferences(store);
        assertEquals(2_000_000, restored.directRate()); assertEquals(750_000, restored.mediaRate());
        assertThrows(IllegalArgumentException.class, () -> limits.save(-1, 100));
        assertEquals(2_000_000, restored.directRate());
        store.putLong("grabx.speed.direct", Long.MAX_VALUE);
        assertEquals(0, restored.directRate());
    }
    @Test void mediaLimitIsAppliedBeforeUrlWithoutMutatingTheRetryCommand() {
        var base = List.of("yt-dlp", "--continue", "--", "https://example.com/video");
        assertEquals(base, TransferPreferences.mediaCommand(base, 0));
        var limited = TransferPreferences.mediaCommand(base, 500_000);
        assertTrue(limited.indexOf("--limit-rate") < limited.indexOf("--"));
        assertEquals("500000", limited.get(limited.indexOf("--limit-rate") + 1));
        assertEquals(4, base.size());
        assertFalse(TransferPreferences.mediaCommand(base, 0).contains("--limit-rate"));
    }
}
