package com.grabx.app.grabx.core.service;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class VideoProbeCacheTest {
    @Test void browserQualitiesSkipExtractionOnlyForTheirVideo() throws Exception {
        var cache = new VideoProbeCache();
        var qualities = Set.of(144,240,360,480,720,1080);
        cache.remember("https://youtube.com/watch?v=DodLg1SxmWI", qualities);
        assertEquals(qualities, cache.get("https://youtu.be/DodLg1SxmWI", ()->{
            fail("Fresh browser qualities must not launch another extractor"); return Set.of();
        }).get().heights());
        assertEquals(Set.of(2160), cache.get("https://youtube.com/watch?v=Oe8bk0CmdzQ", ()->Set.of(2160)).get().heights());
    }
    @Test void sizeTracksTheSelectedQualityAndNeverLeaksAcrossVideos() {
        var cache = new VideoProbeCache();
        String url = "https://youtube.com/watch?v=DodLg1SxmWI";
        cache.remember(url, Set.of(480,720,1080), java.util.List.of(
                new com.grabx.app.grabx.browser.BrowserVideoSize(480,12_000_000),
                new com.grabx.app.grabx.browser.BrowserVideoSize(1080,30_000_000)));
        assertEquals(30_000_000L, cache.estimatedBytes(url,-1));
        assertEquals(12_000_000L, cache.estimatedBytes(url,480));
        assertNull(cache.estimatedBytes(url,720));
        assertNull(cache.estimatedBytes("https://youtu.be/Oe8bk0CmdzQ",480));
        cache.remember(url, Set.of(480,1080,2160), java.util.List.of(new com.grabx.app.grabx.browser.BrowserVideoSize(480,12_000_000)));
        assertNull(cache.estimatedBytes(url,-1), "Best must not use a smaller quality's size");
    }
}
