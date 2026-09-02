package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ThumbnailServiceTest {
    private final ThumbnailService service = new ThumbnailService(null);

    @Test
    void buildsThumbnailUrlForYouTubeVideo() {
        assertEquals(
                "https://img.youtube.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
                service.thumbnailUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        );
        assertEquals(
                "https://img.youtube.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
                service.thumbnailUrl("https://youtu.be/dQw4w9WgXcQ")
        );
    }

    @Test
    void ignoresUrlsWithoutYouTubeVideoId() {
        assertNull(service.thumbnailUrl(null));
        assertNull(service.thumbnailUrl("https://example.com/file.mp4"));
    }

    @Test
    void leavesRowUntouchedWhenNoThumbnailCanBeDerived() {
        DownloadRow row = new DownloadRow(
                "https://example.com/file.mp4", "File", 1, "/tmp", "Video", "720p"
        );

        service.applyToRow(row, row.url);

        assertNull(row.thumbUrl.get());
    }
}
