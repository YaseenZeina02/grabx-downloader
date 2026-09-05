package com.grabx.app.grabx.core.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SegmentRetryTest {
    @Test void initialAndSegmentRequestsRecognizeTheSameTemporaryFailures() {
        for (int status : new int[]{429, 502, 503, 504}) assertTrue(DownloadRunner.isTemporaryHttpFailure(status));
        for (int status : new int[]{200, 206, 401, 403, 404, 416}) assertFalse(DownloadRunner.isTemporaryHttpFailure(status));
    }

    @Test void rateLimitGetsLongerCooldownAndRespectsServerDelay() {
        assertEquals(30000, DownloadRunner.segmentRetryDelay(429, 0, 0));
        assertEquals(60000, DownloadRunner.segmentRetryDelay(429, 1, 0));
        assertEquals(120000, DownloadRunner.segmentRetryDelay(429, 0, 120000));
        assertEquals(2000, DownloadRunner.segmentRetryDelay(503, 0, 0));
    }

    @Test void honorsRetryAfterAndBoundsExcessiveValues() {
        assertEquals(2000, DownloadRunner.retryDelayMillis("2"));
        assertEquals(0, DownloadRunner.retryDelayMillis("invalid"));
        assertEquals(0, DownloadRunner.retryDelayMillis("-1"));
        assertEquals(300000, DownloadRunner.retryDelayMillis(Long.toString(Long.MAX_VALUE)));
        assertEquals(0, DownloadRunner.retryDelayMillis("Sun, 06 Nov 1994 08:49:37 GMT"));
    }

    @Test void identifiesServerBusyWithoutClaimingResumeIsUnsupported() {
        var error = new DownloadRunner.SegmentServerBusy(503, "3");
        assertEquals(3000, error.retryAfterMillis);
        assertTrue(error.getMessage().contains("Server busy (HTTP 503)"));
        assertTrue(error.getMessage().contains("parts kept"));
    }
}
