package com.grabx.app.grabx.core.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class SegmentTransferIntegrationTest {
    @TempDir Path directory;

    @Test void finishesDownloadAfterTruncatedResponseAndServerCappedChunks() throws Exception {
        byte[] source = new byte[256 * 1024];
        new Random(71).nextBytes(source);
        List<Integer> offsets = new CopyOnWriteArrayList<>();
        try (ServerSocket server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(10000);
            CompletableFuture<Void> served = new CompletableFuture<>();
            Thread worker = new Thread(() -> {
                try {
                    boolean first = true;
                    while (true) {
                        try (Socket socket = server.accept()) {
                            socket.setSoTimeout(3000);
                            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                            int offset = 0;
                            boolean validReferer = false;
                            String line;
                            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                                if (line.equalsIgnoreCase("Referer: https://example.com/page")) validReferer = true;
                                if (line.toLowerCase(Locale.ROOT).startsWith("range:"))
                                    offset = Integer.parseInt(line.substring(line.indexOf('=') + 1, line.indexOf('-')));
                            }
                            assertTrue(validReferer, "Every segment and retry must carry the page reference");
                            offsets.add(offset);
                            int length = Math.min(65536, source.length - offset);
                            OutputStream out = socket.getOutputStream();
                            out.write(("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes " + offset + "-" + (offset + length - 1)
                                    + "/" + source.length + "\r\nContent-Length: " + length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                            int actual = first ? 8192 : length;
                            out.write(source, offset, actual);
                            out.flush();
                            first = false;
                            if (offset + actual == source.length) break;
                        }
                    }
                    served.complete(null);
                } catch (Throwable e) { served.completeExceptionally(e); }
            });
            worker.setDaemon(true);
            worker.start();
            Path part = directory.resolve("download.part");
            AtomicLong received = new AtomicLong();
            SegmentTransfer.download(part, 0, source.length - 1, source.length, (start, end) -> {
                return DownloadRunner.openRangeConnection(
                        "http://localhost:" + server.getLocalPort() + "/file", start, end,
                        "https://example.com/page");
            }, () -> {}, received::addAndGet, c -> {});
            served.get(5, TimeUnit.SECONDS);
            assertEquals(0, offsets.get(0));
            assertEquals(8192, offsets.get(1));
            assertEquals(source.length, received.get());
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(source),
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(part)));
        }
    }
}
