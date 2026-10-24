package com.grabx.app.grabx.core.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DownloadPreferencesTest {
    @TempDir Path directory;
    private String path(String name) { return directory.resolve(name).toString(); }
    @Test void fixedFolderDoesNotFollowOneOffDestinationsAndRememberModeMigrates() {
        var store = new SoundFeedbackServiceTest.MemoryPreferences();
        store.put("gx_last_download_folder", path("old-downloads"));
        var folders = new DownloadFolderPreferences(store);
        assertFalse(folders.remembersLastFolder());
        assertEquals(folders.configuredFolder(), folders.getLastFolderOrDefault());
        folders.configure(path("fixed"), false);
        folders.saveLastFolder(path("one-off"));
        assertEquals(path("fixed"), folders.getLastFolderOrDefault());
        folders = new DownloadFolderPreferences(store);
        assertEquals(path("fixed"), folders.getLastFolderOrDefault());
        folders.configure(path("fixed"), true);
        assertEquals(path("one-off"), folders.getLastFolderOrDefault());
    }
    @Test void explicitRememberPreferenceSurvivesRestartAndCanBeDisabled() {
        var store = new SoundFeedbackServiceTest.MemoryPreferences();
        var folders = new DownloadFolderPreferences(store);
        folders.configure(path("default"), true);
        folders.saveLastFolder(path("chosen"));
        folders = new DownloadFolderPreferences(store);
        assertTrue(folders.remembersLastFolder());
        assertEquals(path("chosen"), folders.getLastFolderOrDefault());
        assertEquals(path("default"), folders.configuredFolder());
        folders.configure(path("default"), false);
        assertEquals(path("default"), new DownloadFolderPreferences(store).getLastFolderOrDefault());
    }
    @Test void changingDefaultOverridesRememberedFolderButUnrelatedSavesPreserveIt() {
        var store = new SoundFeedbackServiceTest.MemoryPreferences();
        var folders = new DownloadFolderPreferences(store);
        folders.saveLastFolder(path("old"));
        folders.configure(path("new-default"), true);
        assertEquals(path("new-default"), folders.getLastFolderOrDefault());
        folders.saveLastFolder(path("next-selection"));
        folders.configure(path("new-default"), true);
        assertEquals(path("next-selection"), new DownloadFolderPreferences(store).getLastFolderOrDefault());
    }
    @Test void connectionCountPersistsAndRejectsInvalidValues() {
        var store = new SoundFeedbackServiceTest.MemoryPreferences();
        var preferences = new TransferPreferences(store);
        assertEquals(0, preferences.directConnections());
        for (int value : new int[]{0, 1, 16, 64}) {
            preferences.saveDirectConnections(value);
            assertEquals(value, new TransferPreferences(store).directConnections());
        }
        assertThrows(IllegalArgumentException.class, () -> preferences.saveDirectConnections(-1));
        assertThrows(IllegalArgumentException.class, () -> preferences.saveDirectConnections(65));
        store.putInt("grabx.direct.connections", 99);
        assertEquals(0, preferences.directConnections());
    }
    @Test void newSegmentLayoutsAllow64WhileLegacyLayoutsStayUnchanged() {
        long total = 1024L * 1024 * 1024;
        assertEquals(64, DownloadRunner.directSegmentCount(total, 64));
        assertEquals(64, DownloadRunner.directSegmentCount(total, 0));
        assertEquals(1, DownloadRunner.directSegmentCount(total, 1));
        assertEquals(16, DownloadRunner.directSegmentCountForLegacy(total));
        assertEquals(16, DownloadRunner.directSegmentCount(4L * 1024 * 1024, 64));
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
