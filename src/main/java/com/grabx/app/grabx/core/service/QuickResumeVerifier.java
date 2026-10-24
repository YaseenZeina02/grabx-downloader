package com.grabx.app.grabx.core.service;

import java.io.*;
import java.net.HttpURLConnection;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.UnaryOperator;

/** Opt-in sampled comparison; deliberately does not claim full file identity verification. */
final class QuickResumeVerifier {
    static final int BLOCK = 64 * 1024;

    static boolean verify(Path part, long savedBytes, long total, SegmentTransfer.ConnectionFactory factory,
                          DirectResumeVerifier.Checkpoint checkpoint, UnaryOperator<InputStream> wrap) throws Exception {
        if (savedBytes <= 0 || total < savedBytes) throw new IllegalArgumentException("Invalid saved prefix");
        int count = savedBytes <= 5L * BLOCK ? 1 : 5;
        int length = (int) Math.min(savedBytes, count == 1 ? 5L * BLOCK : BLOCK);
        try (RandomAccessFile local = new RandomAccessFile(part.toFile(), "r")) {
            if (local.length() != savedBytes) throw new DirectRemoteProbe.NeedsAction("Saved data changed during verification");
            byte[] expected = new byte[BLOCK], actual = new byte[BLOCK];
            for (int sample = 0; sample < count; sample++) {
                long span = savedBytes - length;
                long start = count == 1 ? 0 : (span / 4) * sample + (span % 4) * sample / 4;
                long end = start + length - 1;
                checkpoint.check();
                HttpURLConnection connection = factory.open(start, end);
                try {
                    DirectRemoteProbe.checkStatus(connection);
                    if (connection.getResponseCode() == 200) return false;
                    long returnedEnd = SegmentResponse.validate(connection.getHeaderField("Content-Range"), start, end, total);
                    if (returnedEnd != end || (connection.getContentLengthLong() >= 0 && connection.getContentLengthLong() != length))
                        throw new DirectRemoteProbe.NeedsAction("Invalid sample response length");
                    local.seek(start);
                    try (InputStream input = wrap.apply(connection.getInputStream())) {
                        int remaining = length;
                        while (remaining > 0) {
                            checkpoint.check();
                            int read = input.read(actual, 0, Math.min(remaining, actual.length));
                            checkpoint.check();
                            if (read < 0) throw new EOFException("Connection ended during quick resume check");
                            if (read == 0) continue;
                            local.readFully(expected, 0, read);
                            if (!Arrays.equals(expected, 0, read, actual, 0, read))
                                throw new DirectRemoteProbe.NeedsAction("Source changed; saved data does not match this file");
                            remaining -= read;
                        }
                    }
                } finally { connection.disconnect(); }
            }
            checkpoint.check();
            return true;
        }
    }
}
