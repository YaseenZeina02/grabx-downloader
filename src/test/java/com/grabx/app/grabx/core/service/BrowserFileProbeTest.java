package com.grabx.app.grabx.core.service;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class BrowserFileProbeTest {
    @Test void recognizesAnOpaqueFileLinkAndPreservesRequestContext() throws Exception {
        try (Fixture fixture = new Fixture(206, "video/mp4", "attachment; filename=movie.mp4")) {
            var result = BrowserFileProbe.inspect(fixture.url(), "https://example.com/watch", "FixtureBrowser/145");
            assertTrue(result.ok()); assertTrue(result.file()); assertEquals("movie.mp4", result.filename());
            String request = fixture.request.get(5, TimeUnit.SECONDS);
            assertTrue(request.contains("User-Agent: FixtureBrowser/145"));
            assertTrue(request.contains("Referer: https://example.com/watch"));
            assertTrue(request.contains("Range: bytes=0-0"));
            assertEquals(fixture.url(), result.url());
        }
    }
    @Test void htmlIsNotDownloadedAsVideoEvenWithAnAttachmentFilename() throws Exception {
        try (Fixture fixture = new Fixture(200, "text/html", "attachment; filename=movie.mp4")) {
            var result = BrowserFileProbe.inspect(fixture.url(), null, null);
            assertTrue(result.ok()); assertFalse(result.file()); assertTrue(result.message().contains("web page"));
        }
    }
    @Test void serverRejectionIsExposedWithoutStartingADownload() throws Exception {
        try (Fixture fixture = new Fixture(498, "text/html", null)) {
            var result = BrowserFileProbe.inspect(fixture.url(), null, null);
            assertFalse(result.ok()); assertTrue(result.message().contains("498")); assertTrue(result.message().contains("fresh link"));
        }
    }
    @Test void invalidRequestsAreRejectedBeforeOpeningAConnection() {
        assertFalse(BrowserFileProbe.inspect("file:///tmp/file", null, null).ok());
        assertFalse(BrowserFileProbe.inspect("https://example.com/file", null, "bad\r\nX-Test: injected").ok());
        assertFalse(BrowserFileProbe.inspect("https://user:pass@example.com/file", null, null).ok());
    }
    @Test void ordinaryTextFilesRemainDownloadableAndMissingMetadataIsNotCalledHtml() throws Exception {
        for (String type : new String[]{"application/json", "application/xml", "text/javascript", "text/css"}) {
            try (Fixture fixture = new Fixture(206, type, null)) {
                assertTrue(BrowserFileProbe.inspect(fixture.url(), null, null).file(), type);
            }
        }
        try (Fixture fixture = new Fixture(206, null, null)) {
            var result = BrowserFileProbe.inspect(fixture.url(), null, null);
            assertTrue(result.ok()); assertFalse(result.file()); assertEquals("", result.mimeType());
            assertFalse(result.message().contains("web page"));
        }
    }
    static class Fixture implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        final CompletableFuture<String> request = new CompletableFuture<>();
        final Thread thread;
        Fixture(int status, String type, String disposition) throws IOException {
            thread = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    StringBuilder text = new StringBuilder(); String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) text.append(line).append('\n');
                    request.complete(text.toString());
                    String headers = "HTTP/1.1 " + status + " Response\r\n"
                            + (type == null ? "" : "Content-Type: " + type + "\r\n")
                            + "Content-Length: 1\r\nConnection: close\r\n"
                            + (status == 206 ? "Content-Range: bytes 0-0/10000\r\n" : "")
                            + (disposition == null ? "" : "Content-Disposition: " + disposition + "\r\n") + "\r\nx";
                    socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
                } catch (Exception error) { request.completeExceptionally(error); }
            }); thread.setDaemon(true); thread.start();
        }
        String url() { return "http://localhost:" + server.getLocalPort() + "/d/opaque?signature=a%2Fb"; }
        public void close() throws Exception { server.close(); thread.join(3000); }
    }
}
