package com.grabx.app.grabx.core.service;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.function.*;

/** Retries transport interruptions locally, leaving other segment workers running. */
final class SegmentTransfer {
    @FunctionalInterface interface ConnectionFactory { HttpURLConnection open(long start, long end) throws Exception; }
    @FunctionalInterface interface Checkpoint { void check() throws InterruptedException; }

    static void download(Path part, long start, long end, long total, ConnectionFactory factory,
                         Checkpoint checkpoint, LongConsumer progress, Consumer<HttpURLConnection> closed) throws Exception {
        int interruptions = 0;
        while (true) {
            checkpoint.check();
            long present = Files.exists(part) ? Files.size(part) : 0;
            long expected = end - start + 1;
            if (present == expected) return;
            if (present > expected) throw new IOException("Saved segment is larger than expected");
            HttpURLConnection connection = null;
            try {
                connection = factory.open(start + present, end);
                int status = connection.getResponseCode();
                if (DownloadRunner.isTemporaryHttpFailure(status))
                    throw new DownloadRunner.SegmentServerBusy(status, connection.getHeaderField("Retry-After"));
                if (status != 206) throw new IOException("Server rejected range request (HTTP " + status + ")");
                long responseEnd = SegmentResponse.validate(connection.getHeaderField("Content-Range"), start + present, end, total);
                long remaining = responseEnd - (start + present) + 1;
                try (InputStream input = connection.getInputStream();
                     OutputStream output = Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    byte[] buffer = new byte[64 * 1024];
                    while (remaining > 0) {
                        checkpoint.check();
                        int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        if (read < 0) throw new EOFException("Connection ended before segment completed");
                        checkpoint.check();
                        if (read == 0) continue;
                        output.write(buffer, 0, read);
                        progress.accept(read);
                        remaining -= read;
                    }
                }
                // A server may deliberately cap each response to a smaller valid range.
                interruptions = 0;
            } catch (EOFException | SocketTimeoutException | SocketException interrupted) {
                if (++interruptions > 5) throw interrupted;
                // Bounded backoff for this worker only; cancellation remains responsive.
                for (int tick = 0; tick < Math.min(20, interruptions * 2); tick++) {
                    checkpoint.check();
                    Thread.sleep(100);
                }
            } finally {
                if (connection != null) {
                    closed.accept(connection);
                    connection.disconnect();
                }
            }
        }
    }
}
