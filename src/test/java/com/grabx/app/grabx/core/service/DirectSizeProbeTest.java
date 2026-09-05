package com.grabx.app.grabx.core.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DirectSizeProbeTest {
    @Test void slowProbeDoesNotBlockDownloadCaller() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var url = new java.net.URL(null, "http://localhost/file", new java.net.URLStreamHandler() {
            @Override protected java.net.URLConnection openConnection(java.net.URL target) {
                return new java.net.HttpURLConnection(target) {
                    public void connect() {}
                    public void disconnect() {}
                    public boolean usingProxy() { return false; }
                    public int getResponseCode() throws java.io.IOException {
                        entered.countDown();
                        try { release.await(); } catch (InterruptedException e) { throw new java.io.IOException(e); }
                        return 200;
                    }
                    public long getContentLengthLong() { return 1234; }
                };
            }
        });
        try {
            var future = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    java.time.Duration.ofSeconds(1), () -> DirectSizeProbe.probeAsync(url, null));
            org.junit.jupiter.api.Assertions.assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(-1L, future.getNow(-1L));
            release.countDown();
            assertEquals(1234L, future.get(1, java.util.concurrent.TimeUnit.SECONDS));
        } finally { release.countDown(); }
    }

    @Test void readsFullResponseLength() {
        assertEquals(4096, DirectSizeProbe.sizeFromHeaders(200, 4096, null));
        assertEquals(-1, DirectSizeProbe.sizeFromHeaders(200, -1, null));
    }

    @Test void usesWholeSizeInsteadOfSingleByteOrRemainingLength() {
        assertEquals(4096, DirectSizeProbe.sizeFromHeaders(206, 1, "bytes 0-0/4096"));
        assertEquals(4096, DirectSizeProbe.sizeFromHeaders(206, 3096, "bytes 1000-4095/4096"));
    }

    @Test void rejectsUnknownMalformedAndErrorResponses() {
        for (String range : new String[]{"bytes 0-0/*", "bytes 9-2/10", "bytes 0-10/10", "bytes 0-0/999999999999999999999", "invalid"}) {
            assertEquals(-1, DirectSizeProbe.sizeFromHeaders(206, 1, range));
        }
        assertEquals(-1, DirectSizeProbe.sizeFromHeaders(206, 1, null));
        assertEquals(-1, DirectSizeProbe.sizeFromHeaders(404, 200, null));
    }
}
