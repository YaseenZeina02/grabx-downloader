package com.grabx.app.grabx.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class MediaInfoCacheTest {
    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    private final MediaInfoCache cache = new MediaInfoCache(now::get);
    private static final String URL = "https://www.youtube.com/watch?v=abcdefghijk";

    private String metadata(String page) {
        return """
                {"id":"abcdefghijk","title":"Sample","webpage_url":"%s",
                 "formats":[
                   {"format_id":"video","height":1080,"url":"https://cdn.example.com/video?token=abc"},
                   {"format_id":"audio","vcodec":"none","url":"https://cdn.example.com/audio"}],
                 "requested_downloads":[{"format_id":"video"}],"requested_formats":[{"format_id":"video"}],
                 "filepath":"/old/output.mp4","_filename":"old.mp4"}
                """.formatted(page);
    }

    @Test
    void sharesAnalysisAcrossYoutubeLinksKeepsAllFormatsAndDeletesTemporaryInputs() throws Exception {
        cache.remember(URL, "WARNING: example\n" + metadata(URL));
        Path file;
        try (var input = cache.openInput("https://youtu.be/abcdefghijk?t=20")) {
            assertTrue(input.cached());
            file = input.file();
            assertEquals(java.util.List.of("--load-info-json", file.toString()), input.arguments());
            var info = new ObjectMapper().readTree(Files.readString(file));
            assertEquals(2, info.path("formats").size());
            assertFalse(info.has("requested_downloads"));
            assertFalse(info.has("requested_formats"));
            assertFalse(info.has("filepath"));
            assertEquals(URL, info.path("webpage_url").asText());
        }
        assertFalse(Files.exists(file));
        try (var other = cache.openInput("https://example.com/watch?v=abcdefghijk")) {
            assertFalse(other.cached());
        }
    }

    @Test
    void doesNotReselectFormatsThatFailedDuringAnalysis() throws Exception {
        String json = metadata(URL).replace("\"height\":1080", "\"__working\":false,\"height\":1080");
        cache.remember(URL, json);
        try (var input = cache.openInput(URL)) {
            assertTrue(input.cached());
            var formats = new ObjectMapper().readTree(Files.readString(input.file())).path("formats");
            assertEquals(1, formats.size());
            assertEquals("audio", formats.get(0).path("format_id").asText());
        }
    }

    @Test
    void cacheReadsDoNotExtendExpiryAndInvalidationReturnsToTheOriginalUrl() {
        cache.remember(URL, metadata(URL));
        now.addAndGet(119_000);
        try (var input = cache.openInput(URL)) { assertTrue(input.cached()); }
        now.addAndGet(1_001);
        try (var input = cache.openInput(URL)) {
            assertFalse(input.cached());
            assertEquals(java.util.List.of(URL), input.arguments());
        }
        cache.remember(URL, metadata(URL));
        cache.invalidate(URL);
        try (var input = cache.openInput(URL)) { assertFalse(input.cached()); }
    }

    @Test
    void expiresBeforeSignedMediaUrlsAndRejectsLivePlaylistAndUnrelatedResults() {
        String signed = metadata(URL).replace("token=abc", "expire=" + (now.get() / 1000 + 80));
        cache.remember(URL, signed);
        try (var input = cache.openInput(URL)) { assertTrue(input.cached()); }
        now.addAndGet(20_001);
        try (var input = cache.openInput(URL)) { assertFalse(input.cached()); }
        for (String invalid : new String[]{"not JSON", metadata("https://example.com/other"),
                metadata(URL).replace("\"id\":", "\"is_live\":true,\"id\":"),
                metadata(URL).replace("\"id\":", "\"entries\":[],\"id\":"),
                metadata(URL).replace("token=abc", "expire=1")}) {
            cache.remember(URL, invalid);
            try (var input = cache.openInput(URL)) { assertFalse(input.cached()); }
        }
    }

    @Test
    void boundsTheCacheAndGivesConcurrentDownloadsIndependentTemporaryFiles() {
        for (int i = 0; i < 17; i++) {
            String page = "https://example.com/watch/" + i;
            cache.remember(page, metadata(page));
        }
        try (var first = cache.openInput("https://example.com/watch/0")) { assertFalse(first.cached()); }
        try (var a = cache.openInput("https://example.com/watch/16");
             var b = cache.openInput("https://example.com/watch/16")) {
            assertTrue(a.cached());
            assertTrue(b.cached());
            assertNotEquals(a.file(), b.file());
            a.close();
            assertTrue(Files.exists(b.file()));
        }
    }

    @Test
    void retriesAFailedCachedDownloadOnceAndHonorsPauseAndCancel() {
        cache.remember(URL, metadata(URL));
        try (var input = cache.openInput(URL)) {
            var command = new java.util.ArrayList<>(java.util.List.of("yt-dlp", "--continue", "--no-overwrites",
                    "-f", "bestaudio/best", "-o", "saved stem.%(ext)s"));
            command.addAll(input.arguments());
            assertNull(cache.retryCommand(command, input, 0, null));
            assertNull(cache.retryCommand(command, input, 1, "PAUSE"));
            assertNull(cache.retryCommand(command, input, 1, "CANCEL"));
            var retry = cache.retryCommand(command, input, 1, null);
            assertEquals(java.util.List.of("yt-dlp", "--continue", "--no-overwrites", "-f", "bestaudio/best",
                    "-o", "saved stem.%(ext)s", URL), retry);
            assertNull(cache.retryCommand(retry, input, 1, null));
            try (var fresh = cache.openInput(URL)) { assertFalse(fresh.cached()); }
        }
    }
}
