package com.grabx.app.grabx.core.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
import java.util.function.UnaryOperator;
import static org.junit.jupiter.api.Assertions.*;

class QuickResumeVerifierTest {
    @TempDir Path directory;

    @Test void interruptedSampleNeverWritesOrPromotesSavedData() throws Exception {
        try (var server = new SmartResumeIntegrationTest.Fixture(1024 * 1024)) {
            server.etag = null; server.cuts.set(1); server.cutAt = 17;
            byte[] before = Arrays.copyOf(server.bytes, 800000);
            Path part = Files.write(directory.resolve("file.part"), before);
            assertThrows(EOFException.class, () -> QuickResumeVerifier.verify(part, before.length, server.bytes.length,
                    (start, end) -> DownloadRunner.openRangeConnection(server.url(), start, end, null),
                    () -> {}, UnaryOperator.identity()));
            assertArrayEquals(before, Files.readAllBytes(part));
        }
    }

    @Test void cancellationBeforeSampleDoesNotSendRequestOrTouchData() throws Exception {
        byte[] before = {1, 2, 3};
        Path part = Files.write(directory.resolve("file.part"), before);
        assertThrows(InterruptedException.class, () -> QuickResumeVerifier.verify(part, 3, 10,
                (start, end) -> { fail("Cancelled check must not open a connection"); return null; },
                () -> { throw new InterruptedException(); }, UnaryOperator.identity()));
        assertArrayEquals(before, Files.readAllBytes(part));
    }
}
