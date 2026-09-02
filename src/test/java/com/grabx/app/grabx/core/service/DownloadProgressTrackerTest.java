package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DownloadProgressTrackerTest {
    private final DownloadProgressTracker tracker = new DownloadProgressTracker();
    private final DownloadRow row = new DownloadRow("url", "title", 1, "/tmp", "Video", "720p");

    @Test
    void neverMovesProgressBackward() {
        tracker.applyMonotonic(row, 0.6);
        tracker.applyMonotonic(row, 0.4);

        assertEquals(0.6, row.progress.get());
        assertEquals(0.6, tracker.progressByRow().get(row));
    }

    @Test
    void toleratesSmallRoundingVariationWithoutMovingBackward() {
        tracker.applyMonotonic(row, 0.6);
        tracker.applyMonotonic(row, 0.598);

        assertEquals(0.6, row.progress.get());
    }

    @Test
    void clampsProgressAtCompletion() {
        tracker.applyMonotonic(row, 1.2);

        assertEquals(1.0, row.progress.get());
        assertEquals(1.0, tracker.progressByRow().get(row));
    }

    @Test
    void ignoresInvalidProgress() {
        tracker.applyMonotonic(row, -1);
        tracker.applyMonotonic(row, Double.NaN);

        assertFalse(tracker.progressByRow().containsKey(row));
        assertEquals(0, row.progress.get());
    }
}
