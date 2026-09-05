package com.grabx.app.grabx.core.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class DirectResumeVerifierTest {
    @TempDir Path directory;

    @Test void verifiesSavedPrefixAndAppendsOnlyRemainingBytes() throws Exception {
        Path partial = directory.resolve("file.part");
        Files.write(partial, new byte[]{1, 2, 3});
        var response = new ByteArrayInputStream(new byte[]{1, 2, 3, 4, 5});
        DirectResumeVerifier.verify(response, partial, 3, () -> {}, n -> {});
        try (var out = Files.newOutputStream(partial, StandardOpenOption.APPEND)) { response.transferTo(out); }
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, Files.readAllBytes(partial));
    }

    @Test void changedOrShortResponseNeverModifiesSavedData() throws Exception {
        Path partial = directory.resolve("file.part");
        byte[] saved = {1, 2, 3};
        Files.write(partial, saved);
        for (byte[] remote : new byte[][]{{1, 9, 3, 4}, {1, 2}}) {
            assertThrows(IOException.class, () -> DirectResumeVerifier.verify(
                    new ByteArrayInputStream(remote), partial, 3, () -> {}, n -> {}));
            assertArrayEquals(saved, Files.readAllBytes(partial));
        }
    }

    @Test void cancellationStopsVerificationAndKeepsThePartial() throws Exception {
        Path partial = directory.resolve("file.part");
        Files.write(partial, new byte[]{1});
        assertThrows(InterruptedException.class, () -> DirectResumeVerifier.verify(
                new ByteArrayInputStream(new byte[]{1, 2}), partial, 1,
                () -> { throw new InterruptedException(); }, n -> {}));
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(partial));
    }
}
