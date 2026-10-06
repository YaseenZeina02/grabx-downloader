package com.grabx.app.grabx.core.service;

import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
import java.util.function.LongConsumer;

/** Positions a full response after the saved prefix, only after verifying every byte. */
final class DirectResumeVerifier {
    @FunctionalInterface interface Checkpoint { void check() throws InterruptedException; }

    static void verify(InputStream response, Path partial, long length,
                       Checkpoint checkpoint, LongConsumer progress) throws IOException, InterruptedException {
        try (InputStream saved = new BufferedInputStream(Files.newInputStream(partial), 128 * 1024)) {
            byte[] local = new byte[128 * 1024];
            byte[] remote = new byte[local.length];
            long checked = 0;
            while (checked < length) {
                checkpoint.check();
                // Reuse bounded buffers and report actual socket reads rather than
                // waiting for an entire block before updating slow transfers.
                int count = response.read(remote, 0, (int) Math.min(remote.length, length - checked));
                checkpoint.check();
                if (count < 0) throw new EOFException("Connection ended while verifying saved data");
                if (count == 0) continue;
                if (saved.readNBytes(local, 0, count) != count)
                    throw new DirectRemoteProbe.NeedsAction("Saved data changed during verification");
                if (!Arrays.equals(local, 0, count, remote, 0, count))
                    throw new DirectRemoteProbe.NeedsAction("Source changed; saved data does not match this file");
                checked += count;
                progress.accept(checked);
            }
            checkpoint.check();
        }
    }
}
