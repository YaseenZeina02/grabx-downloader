package com.grabx.app.grabx.core.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExactMediaSizeTest {
    @Test void sumsOnlySelectedExactStreamsAndNeverUsesBitrateOrApproximateSizes() {
        assertEquals(1200L, ExactMediaSize.selectedTotal("{\"requested_formats\":[{\"filesize\":1000},{\"filesize\":200}]}"));
        assertNull(ExactMediaSize.selectedTotal("{\"requested_formats\":[{\"filesize\":1000},{\"filesize_approx\":200}]}"));
        assertNull(ExactMediaSize.selectedTotal("{\"filesize_approx\":1000,\"tbr\":80,\"duration\":100}"));
    }
    @Test void directHeadersSupplyMissingStreamSizeBeforePayloadDownload() throws Exception {
        try (var fixture = new BrowserFileProbeTest.Fixture(200, "video/mp4", null)) {
            String json = "{\"url\":\"" + fixture.url() + "\",\"protocol\":\"http\"}";
            assertEquals(1L, ExactMediaSize.selectedTotal(json));
            assertTrue(fixture.request.get().startsWith("HEAD "));
        }
    }
    @Test void oneByteRangeTotalIsUsedWhenHeadIsUnavailable() throws Exception {
        // The fixture answers 206 to HEAD as well; only the validated range response counts.
        try (var fixture = new java.net.ServerSocket(0, 2, java.net.InetAddress.getLoopbackAddress())) {
            var served = java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    for (int i=0;i<2;i++) try(var socket=fixture.accept()) {
                        var reader=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));
                        String first=reader.readLine(),line;
                        while((line=reader.readLine())!=null&&!line.isEmpty()){}
                        String response=first.startsWith("HEAD")?"HTTP/1.1 405 No\r\nContent-Length: 0\r\nConnection: close\r\n\r\n":"HTTP/1.1 206 OK\r\nContent-Range: bytes 0-0/4567\r\nContent-Length: 1\r\nConnection: close\r\n\r\nx";
                        socket.getOutputStream().write(response.getBytes());
                    }
                } catch(Exception error){throw new RuntimeException(error);}
            });
            assertEquals(4567L,ExactMediaSize.selectedTotal("{\"url\":\"http://localhost:"+fixture.getLocalPort()+"/video\",\"protocol\":\"http\"}"));
            served.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
    @Test void manifestsAndLiveStreamsDoNotUseManifestLengthAsVideoSize() {
        assertNull(ExactMediaSize.selectedTotal("{\"url\":\"https://example.com/live.m3u8\",\"protocol\":\"m3u8_native\"}"));
        assertNull(ExactMediaSize.selectedTotal("{\"is_live\":true,\"url\":\"https://example.com/live\"}"));
    }
}
