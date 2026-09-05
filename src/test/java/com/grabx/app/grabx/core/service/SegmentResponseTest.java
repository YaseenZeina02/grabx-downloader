package com.grabx.app.grabx.core.service;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class SegmentResponseTest {
    @Test void truncatedBodyIsRetryableButInvalidRangeIsNot() {
        assertTrue(DownloadRunner.isInterruptedSegment(new java.io.EOFException("incomplete")));
        assertTrue(DownloadRunner.isInterruptedSegment(new java.net.SocketTimeoutException()));
        assertFalse(DownloadRunner.isInterruptedSegment(new IOException("wrong range")));
    }

    @Test void acceptsCorrectResumedRangeAndShorterServerChunk() throws Exception {
        SegmentResponse.validate("bytes 100-199/1000", 100, 199, 1000);
        SegmentResponse.validate("bytes 100-149/1000", 100, 199, 1000);
    }
    @Test void rejectsWrongOffsetOversizedRangeAndChangedTotalBeforeAppend() {
        for (String range : new String[]{"bytes 0-199/1000", "bytes 100-299/1000", "bytes 100-199/2000", "", "bytes 100-99/1000"})
            assertThrows(IOException.class, () -> SegmentResponse.validate(range, 100, 199, 1000));
    }
}
