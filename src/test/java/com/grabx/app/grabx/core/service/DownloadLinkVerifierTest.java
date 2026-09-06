package com.grabx.app.grabx.core.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DownloadLinkVerifierTest {
    @TempDir Path dir;
    @Test void acceptsMatchingSavedBytesAndRejectsSameSizeDifferentContent() throws Exception {
        byte[] source = new byte[4096]; new Random(9).nextBytes(source);
        Path output = dir.resolve("file.bin");
        Path part = Files.write(DirectPartialFiles.part(output, -1), Arrays.copyOf(source, 1024));
        try (var server = new Fixture(source, "\"version-1\"")) {
            var result = DownloadLinkVerifier.verify(server.url(), null, output, source.length);
            assertEquals("\"version-1\"", result.etag());
            assertEquals(source.length, result.total());
            byte[] changed = Files.readAllBytes(part); changed[500] ^= 1; Files.write(part, changed);
            assertThrows(IOException.class, () -> DownloadLinkVerifier.verify(server.url(), null, output, source.length));
            assertArrayEquals(changed, Files.readAllBytes(part));
        }
    }
    @Test void verifiesNoncontiguousSegmentsAtTheirOriginalOffsets() throws Exception {
        byte[] source = new byte[4096]; new Random(7).nextBytes(source);
        Path output = dir.resolve("segments.bin");
        Files.write(DirectPartialFiles.part(output, 0), Arrays.copyOfRange(source, 0, 100));
        Path second = Files.write(DirectPartialFiles.part(output, 1), Arrays.copyOfRange(source, 2048, 2148));
        try (var server = new Fixture(source, "\"version\"")) {
            assertEquals(source.length, DownloadLinkVerifier.verify(server.url(), null, output, source.length).total());
            Files.write(second, Arrays.copyOfRange(source, 0, 100));
            assertThrows(IOException.class, () -> DownloadLinkVerifier.verify(server.url(), null, output, source.length));
        }
    }
    @Test void refusesWeakValidatorsAndDifferentTotalWithoutChangingParts() throws Exception {
        byte[] source = new byte[4096];
        Path output = dir.resolve("file.bin");
        Path part = Files.write(DirectPartialFiles.part(output, -1), new byte[128]);
        try (var weak = new Fixture(source, "W/\"version\"")) {
            assertThrows(IOException.class, () -> DownloadLinkVerifier.verify(weak.url(), null, output, source.length));
        }
        try (var strong = new Fixture(source, "\"version\"")) {
            assertThrows(IOException.class, () -> DownloadLinkVerifier.verify(strong.url(), null, output, 5000));
        }
        assertEquals(128, Files.size(part));
    }
    static class Fixture implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0);
        final Thread thread;
        Fixture(byte[] source, String etag) throws IOException {
            thread = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(3000);
                        var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                        String line; int start = 0, end = 0;
                        while ((line = reader.readLine()) != null && !line.isEmpty()) {
                            if (line.toLowerCase(Locale.ROOT).startsWith("range:")) {
                                var range = line.substring(line.indexOf('=') + 1).split("-");
                                start = Integer.parseInt(range[0]); end = Integer.parseInt(range[1]);
                            }
                        }
                        var out = socket.getOutputStream();
                        out.write(("HTTP/1.1 206 Partial Content\r\nETag: " + etag
                                + "\r\nContent-Range: bytes " + start + "-" + end + "/" + source.length
                                + "\r\nContent-Length: " + (end-start+1) + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        out.write(source, start, end-start+1); out.flush();
                    } catch (IOException ignored) { }
                }
            }); thread.setDaemon(true); thread.start();
        }
        String url() { return "http://localhost:" + server.getLocalPort() + "/new-token"; }
        public void close() throws Exception { server.close(); thread.join(3000); }
    }
}
