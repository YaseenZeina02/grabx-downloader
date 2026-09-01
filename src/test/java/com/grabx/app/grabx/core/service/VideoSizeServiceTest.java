package com.grabx.app.grabx.core.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class VideoSizeServiceTest {
    @Test
    void parsesFirstPositiveNumericLine() {
        assertEquals(123456L, VideoSizeService.parseFirstPositiveBytes("warning\nNA\n123456\n"));
        assertNull(VideoSizeService.parseFirstPositiveBytes("warning\nNA\n0"));
    }

    @Test
    void cachesEquivalentYoutubeUrlsWithinTheSession() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        VideoSizeService service = new VideoSizeService(arguments -> {
            calls.incrementAndGet();
            return "987654";
        });

        assertEquals(987654L, service.probeAsync(
                "https://youtu.be/abc123", "Video", "720p", "selector").get(2, TimeUnit.SECONDS));
        assertEquals(987654L, service.probeAsync(
                "https://www.youtube.com/watch?v=abc123", "Video", "720p", "selector").get(2, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
    }
}
