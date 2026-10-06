package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class DirectDownloadStateTest {
    @TempDir Path directory;
    @Test void restoresIdentityUrlsRefererAndImmutableLayoutUsingActualPartSizes() throws Exception {
        Path output = directory.resolve("video.mp4");
        DownloadRow row = row(); row.originalUrl = "https://example.com/original";
        row.url = "https://example.com/token?value=abc"; row.finalUrl = "https://cdn.example.com/video";
        row.setReferer("https://example.com/page"); row.resumeEtag = "\"stable-version\"";
        row.resumeLastModified = "Wed, 30 Sep 2026 08:00:00 GMT";
        row.resumeSupport = "Yes"; row.resumeTotal = 20000; row.resumeSegments = 2;
        DirectDownloadState.save(row, output);
        Files.write(DirectPartialFiles.part(output, 0), new byte[10000]);
        Files.write(DirectPartialFiles.part(output, 1), new byte[9800]);
        Files.write(DirectPartialFiles.part(output, -1), new byte[4000]); // interrupted merge: do not double count
        DownloadRow loaded = row(); assertTrue(DirectDownloadState.load(loaded, output));
        assertEquals(row.originalUrl, loaded.originalUrl); assertEquals(row.url, loaded.url);
        assertEquals(row.finalUrl, loaded.finalUrl); assertEquals(row.getReferer(), loaded.getReferer());
        assertEquals(row.resumeEtag, loaded.resumeEtag); assertEquals(row.resumeLastModified, loaded.resumeLastModified);
        assertEquals(2, loaded.resumeSegments); assertEquals(20000, loaded.resumeTotal);
        assertEquals(19800, DirectDownloadState.downloaded(output));
        DirectPartialFiles.cleanup(output);
        assertFalse(Files.exists(DirectDownloadState.path(output)));
    }
    @Test void invalidOrMisplacedStateDoesNotRewriteSavedParts() throws Exception {
        Path output = directory.resolve("file.bin");
        Path part = Files.writeString(DirectPartialFiles.part(output, -1), "saved");
        Files.writeString(DirectDownloadState.path(output), "version=1\nsegments=oops\ntotal=123\n");
        assertThrows(IOException.class, () -> DirectDownloadState.load(row(), output));
        assertEquals("saved", Files.readString(part));
        assertFalse(DirectDownloadState.load(row(), directory.resolve("other.bin")));
    }
    @Test void rejectsUnknownIdentityAndChangedSizeEvenWhenServerAdvertisesRangeSupport() throws Exception {
        DownloadRow row = row(); row.resumeTotal = 2000; row.resumeEtag = "\"v1\"";
        var changed = new DirectRemoteProbe.Remote(row.url, 3000, "\"v1\"", null, "Yes", null, null);
        assertThrows(DirectRemoteProbe.NeedsAction.class, () -> DirectRemoteProbe.verify(row, changed));
        row.resumeEtag = null;
        var unknown = new DirectRemoteProbe.Remote(row.url, 2000, null, null, "Yes", null, null);
        assertThrows(DirectRemoteProbe.NeedsAction.class, () -> DirectRemoteProbe.verify(row, unknown));
    }
    private DownloadRow row() { return new DownloadRow("https://example.com/file", "file.bin", 1, directory.toString(), "Direct", "Auto"); }
}
