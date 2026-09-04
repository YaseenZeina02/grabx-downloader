package com.grabx.app.grabx.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SingleInstanceGuardTest {
    @TempDir
    Path tempDir;

    @Test
    void allowsOnlyOneOwnerAndReleasesTheLockOnClose() {
        Path lockFile = tempDir.resolve("grabx.lock");
        SingleInstanceGuard first = new SingleInstanceGuard(lockFile);
        SingleInstanceGuard second = new SingleInstanceGuard(lockFile);

        assertTrue(first.acquire());
        assertFalse(second.acquire());

        first.close();
        assertTrue(second.acquire());
        second.close();
    }
}
