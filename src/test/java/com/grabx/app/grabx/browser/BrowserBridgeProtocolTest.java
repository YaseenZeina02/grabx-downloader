package com.grabx.app.grabx.browser;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrowserBridgeProtocolTest {
    private final BrowserBridgeProtocol protocol = new BrowserBridgeProtocol();

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
}
