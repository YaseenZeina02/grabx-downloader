package com.grabx.app.grabx.core.service;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class TransferPauseGateTest {
    @Test void resumesTheSameConnectionWithoutReplayingBytes() throws Exception {
        TransferPauseGate gate = new TransferPauseGate();
        gate.pause();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
             Socket sender = server.accept()) {
            client.setSoTimeout(2000);
            sender.getOutputStream().write(new byte[]{1, 2, 3, 4, 5, 6});
            sender.shutdownOutput();
            InputStream input = client.getInputStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            output.write(input.readNBytes(3));
            var started = new CountDownLatch(1);
            var result = new CompletableFuture<byte[]>();
            Thread worker = new Thread(() -> {
                started.countDown();
                try {
                    gate.awaitRunning();
                    input.transferTo(output);
                    result.complete(output.toByteArray());
                } catch (Exception e) { result.completeExceptionally(e); }
            });
            worker.setDaemon(true);
            worker.start();
            try {
                assertTrue(started.await(1, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> result.get(100, TimeUnit.MILLISECONDS));
                assertEquals(3, output.size());
                gate.resume();
                assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6}, result.get(2, TimeUnit.SECONDS));
            } finally { gate.stop(); worker.join(2000); }
        }
    }

    @Test void cancellationUnblocksPausedWorkers() {
        TransferPauseGate gate = new TransferPauseGate();
        gate.pause();
        gate.stop();
        assertThrows(InterruptedException.class, gate::awaitRunning);
    }

    @Test void refusesIgnoredOrWrongResumeRanges() throws Exception {
        DownloadRunner.validateResumeResponse(100, 206, "bytes 100-199/200");
        assertThrows(IOException.class, () -> DownloadRunner.validateResumeResponse(100, 200, null));
        assertThrows(IOException.class, () -> DownloadRunner.validateResumeResponse(100, 206, "bytes 0-199/200"));
        assertThrows(IOException.class, () -> DownloadRunner.validateResumeResponse(100, 206, null));
    }
}
