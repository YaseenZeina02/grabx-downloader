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
        try (InputStream saved = Files.newInputStream(partial)) {
            long checked = 0;
            while (checked < length) {
                checkpoint.check();
                int count = (int) Math.min(64 * 1024, length - checked);
                byte[] local = saved.readNBytes(count);
                byte[] remote = response.readNBytes(count);
                if (local.length != count || remote.length != count || !Arrays.equals(local, remote)) {
                    throw new IOException("Source changed; saved download kept. Use a new download for this version");
                }
                checked += count;
                progress.accept(checked);
            }
            checkpoint.check();
        }
    }
}
