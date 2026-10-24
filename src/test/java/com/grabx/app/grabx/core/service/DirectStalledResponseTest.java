package com.grabx.app.grabx.core.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DirectStalledResponseTest {
    @TempDir Path directory;

    @Test @Timeout(35)
    void shortServerStallKeepsSameResponseThroughVerificationAndDownload() throws Exception {
        byte[] body = {1, 2, 3, 4, 5, 6};
        Path part = Files.write(directory.resolve("saved.part"), new byte[]{1, 2, 3, 4});
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var served = new CompletableFuture<Void>();
            Thread worker = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    var request = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String line;
                    while ((line = request.readLine()) != null && !line.isEmpty()) {}
                    var out = socket.getOutputStream();
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 6\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.write(body, 0, 2); out.flush();
                    Thread.sleep(16_500); // Exceeds the former 15-second payload timeout.
                    out.write(body, 2, 4); out.flush();
                    served.complete(null);
                } catch (Throwable error) { served.completeExceptionally(error); }
            });
            worker.setDaemon(true); worker.start();
            var connection = DownloadRunner.openRangeConnection("http://localhost:" + server.getLocalPort() + "/file", -1, 5, null);
            try {
                try (var response = connection.getInputStream()) {
                    DirectResumeVerifier.verify(response, part, 4, () -> {}, ignored -> {});
                    try (var output = Files.newOutputStream(part, StandardOpenOption.APPEND)) { response.transferTo(output); }
                }
                served.get(5, TimeUnit.SECONDS);
                assertArrayEquals(body, Files.readAllBytes(part));
            } finally { connection.disconnect(); }
        }
    }
}
