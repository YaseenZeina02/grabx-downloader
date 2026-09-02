package com.grabx.app.grabx.core.service;

import org.junit.jupiter.api.Test;

import static com.grabx.app.grabx.core.service.UrlAnalysisService.ContentType.DIRECT_FILE;
import static com.grabx.app.grabx.core.service.UrlAnalysisService.ContentType.PLAYLIST;
import static com.grabx.app.grabx.core.service.UrlAnalysisService.ContentType.UNSUPPORTED;
import static com.grabx.app.grabx.core.service.UrlAnalysisService.ContentType.VIDEO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlAnalysisServiceTest {
    private final UrlAnalysisService service = new UrlAnalysisService();

    @Test
    void detectsYouTubeVideoUrls() {
        assertEquals(VIDEO, service.analyze("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertEquals(VIDEO, service.analyze("https://youtu.be/dQw4w9WgXcQ"));
    }

    @Test
    void treatsWatchUrlWithPlaylistAsSingleVideo() {
        assertEquals(VIDEO, service.analyze(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL123"
        ));
    }

    @Test
    void detectsYouTubePlaylistUrls() {
        assertEquals(PLAYLIST, service.analyze("https://www.youtube.com/playlist?list=PL123"));
        assertEquals(PLAYLIST, service.analyze("https://www.youtube.com/?list=PL123"));
    }

    @Test
    void detectsDirectFilesAndGenericWebUrls() {
        assertEquals(DIRECT_FILE, service.analyze("https://example.com/archive.zip?download=1"));
        assertEquals(DIRECT_FILE, service.analyze("https://example.com/video.mp4"));
        assertEquals(DIRECT_FILE, service.analyze("https://example.com/download/123"));
    }

    @Test
    void rejectsBlankAndNonHttpValues() {
        assertEquals(UNSUPPORTED, service.analyze(null));
        assertEquals(UNSUPPORTED, service.analyze(""));
        assertEquals(UNSUPPORTED, service.analyze("ftp://example.com/video.mp4"));
        assertEquals(UNSUPPORTED, service.analyze("not a url"));
    }

    @Test
    void validatesHttpSchemesIgnoringWhitespaceAndCase() {
        assertTrue(service.isHttpUrl("  HTTPS://example.com/file  "));
        assertTrue(service.isHttpUrl("http://example.com"));
        assertFalse(service.isHttpUrl(null));
        assertFalse(service.isHttpUrl("ftp://example.com"));
    }
}
