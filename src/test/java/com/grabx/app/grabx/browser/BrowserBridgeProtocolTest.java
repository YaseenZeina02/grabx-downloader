package com.grabx.app.grabx.browser;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;

class BrowserBridgeProtocolTest {
    private final BrowserBridgeProtocol protocol = new BrowserBridgeProtocol();

    private BrowserCapture capture(String url, String folder, boolean resolved) throws Exception {
        return protocol.validate(new BrowserCapture(1, "capture", "request-1234", "https://example.com/watch",
                url, "Movie", "video/mp4", "file", "file", "Movie.mp4", folder, resolved, 100));
    }

    @Test
    void acceptsBrowserMovieUrlsWithoutCorruptingSignedParameters() throws Exception {
        String browserUrl = "https://cdn.example.com/Movie[1080p]|part.mp4?token=a%2Fb%3D&x=1&x=2";
        assertThrows(IllegalArgumentException.class, () -> java.net.URI.create(browserUrl));
        assertEquals("https://cdn.example.com/Movie%5B1080p%5D%7Cpart.mp4?token=a%2Fb%3D&x=1&x=2",
                capture(browserUrl, "", false).mediaUrl());
        String signed = "https://cdn.example.com/video.mp4?sig=a+b/%2f==&data=[1]&empty=&flag#t=10";
        assertEquals(signed, capture(signed, "", false).mediaUrl());
        assertEquals("https://example.com/100%25%20movie.mp4?data=%7Bx%7D",
                capture("https://example.com/100% movie.mp4?data={x}", "", false).mediaUrl());
        assertEquals("https://[::1]/Movie%5B1%5D.mp4", capture("https://[::1]/Movie[1].mp4", "", false).mediaUrl());
    }

    @Test
    void stillRejectsUnsafeAndMalformedMediaAddresses() {
        for (String url : new String[]{"file:///tmp/movie.mp4", "javascript:alert(1)", "blob:https://example.com/id",
                "https:///movie.mp4", "https://bad host/movie.mp4", "https://example.com/movie\n.mp4"}) {
            assertThrows(BrowserBridgeProtocol.ProtocolException.class, () -> capture(url, "", false));
        }
    }

    @Test
    void chooserIsSkippedOnlyForAResolvedUsableBrowserDirectory(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder)
            throws Exception {
        String url = "https://example.com/movie.mp4";
        var accepted = capture(url, folder.toString(), true);
        assertEquals(folder, accepted.browserDestinationDirectory());
        assertEquals(folder, protocol.parse(protocol.serialize(accepted)).browserDestinationDirectory());
        assertNull(capture(url, folder.toString(), false).browserDestinationDirectory());
        assertNull(capture(url, folder.resolve("missing").toString(), true).browserDestinationDirectory());
        assertNull(capture(url, "relative", true).browserDestinationDirectory());
        assertNull(capture(url, "", true).browserDestinationDirectory());
    }

    @Test
    void acceptsAndNormalizesAValidCapture() throws Exception {
        BrowserCapture capture = protocol.parse("""
                {
                  "protocolVersion": 1,
                  "type": "capture",
                  "requestId": "request-1234",
                  "pageUrl": "https://example.com/watch?id=1",
                  "mediaUrl": "https://cdn.example.com/video.mp4",
                  "title": "  Example video  ",
                  "mimeType": "video/mp4",
                  "mediaKind": "VIDEO",
                  "action": "video",
                  "createdAt": 100
                }
                """.getBytes(StandardCharsets.UTF_8));

        assertEquals("video", capture.mediaKind());
        assertEquals("Example video", capture.title());
        assertEquals("https://cdn.example.com/video.mp4", capture.effectiveUrl());
    }

    @Test
    void rejectsNonHttpAndUnsupportedRequests() {
        assertThrows(BrowserBridgeProtocol.ProtocolException.class, () -> protocol.parse("""
                {
                  "protocolVersion": 1,
                  "type": "capture",
                  "requestId": "request-1234",
                  "pageUrl": "file:///private/file.mp4",
                  "mediaKind": "file",
                  "action": "file"
                }
                """.getBytes(StandardCharsets.UTF_8)));

        assertThrows(BrowserBridgeProtocol.ProtocolException.class, () -> protocol.parse("""
                {
                  "protocolVersion": 99,
                  "type": "capture",
                  "requestId": "request-1234",
                  "pageUrl": "https://example.com"
                }
                """.getBytes(StandardCharsets.UTF_8)));
    }
    @Test
    void acceptsFreshQualitiesOnlyForTheMatchingYouTubeVideo() throws Exception {
        var qualities = java.util.List.of(1080, 720, 480, 720, 999, -1);
        String page = "https://www.youtube.com/watch?v=DodLg1SxmWI";
        long now = System.currentTimeMillis();
        var capture = new BrowserCapture(1, "capture", "request-1234", page, null,
                "Video", "", "page", null, "", "", false, now, qualities);
        assertEquals(java.util.List.of(1080, 720, 480), protocol.parse(protocol.serialize(capture)).availableQualities());
        for (String media : java.util.List.of("https://youtube.com/watch?v=Oe8bk0CmdzQ", "https://cdn.example.com/video.mp4")) {
            assertEquals(java.util.List.of(), protocol.validate(new BrowserCapture(1, "capture", "request-1234", page, media,
                    "Video", "", "page", "video", "", "", false, now, qualities)).availableQualities());
        }
        for (String host : java.util.List.of("https://youtube.com.evil.example/watch?v=DodLg1SxmWI", "https://example.com")) {
            assertEquals(java.util.List.of(), protocol.validate(new BrowserCapture(1, "capture", "request-1234", host, null,
                    "Video", "", "page", "video", "", "", false, now, qualities)).availableQualities());
        }
        assertEquals(java.util.List.of(), protocol.validate(new BrowserCapture(1, "capture", "request-1234", page, null,
                "Video", "", "page", "video", "", "", false, now - 600_000, qualities)).availableQualities());
    }
    @Test void validatesSizeEstimatesForFreshMatchingQualitiesOnly() throws Exception {
        var sizes = java.util.List.of(new BrowserVideoSize(1080, 30_000_000), new BrowserVideoSize(480, 12_000_000),
                new BrowserVideoSize(1080, 1), new BrowserVideoSize(2160, 999), new BrowserVideoSize(720, -1),
                new BrowserVideoSize(360, Long.MAX_VALUE));
        long now = System.currentTimeMillis();
        var capture = new BrowserCapture(1, "capture", "request-1234", "https://youtube.com/watch?v=DodLg1SxmWI", null,
                "Video", "", "page", "video", "", "", false, now, java.util.List.of(1080,720,480,360), sizes);
        assertEquals(sizes.subList(0,2), protocol.parse(protocol.serialize(capture)).qualitySizes());
        var stale = new BrowserCapture(1, "capture", "request-1234", capture.pageUrl(), null,
                "Video", "", "page", "video", "", "", false, now-600_000, capture.availableQualities(), sizes);
        assertEquals(java.util.List.of(), protocol.validate(stale).qualitySizes());
        var wrongVideo = new BrowserCapture(1, "capture", "request-1234", capture.pageUrl(), "https://youtube.com/watch?v=Oe8bk0CmdzQ",
                "Video", "", "page", "video", "", "", false, now, capture.availableQualities(), sizes);
        assertEquals(java.util.List.of(), protocol.validate(wrongVideo).qualitySizes());
    }
}
