package com.grabx.app.grabx.core.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class BandwidthLimiterTest {
    @TempDir Path directory;

    @Test void savedPrefixAndNewBytesUseOneBudgetWithoutChangingContent() throws Exception {
        AtomicLong time = new AtomicLong();
        var limiter = new BandwidthLimiter(time::get, time::addAndGet);
        limiter.setBytesPerSecond(10_000);
        byte[] source = new byte[20_000]; new Random(4).nextBytes(source);
        Path partial = directory.resolve("part"); Files.write(partial, Arrays.copyOf(source, 12_000));
        try (var in = limiter.wrap(new ByteArrayInputStream(source), () -> {})) {
            DirectResumeVerifier.verify(in, partial, 12_000, () -> {}, done -> {});
            assertEquals(1_200_000_000L, time.get());
            assertArrayEquals(Arrays.copyOfRange(source, 12_000, source.length), in.readAllBytes());
        }
        // One final EOF read is budgeted and refunded; at most one 100ms chunk overhead.
        assertTrue(time.get() >= 2_000_000_000L && time.get() <= 2_100_000_000L);
        assertEquals(12_000, Files.size(partial));
    }

    @Test void liveLimitChangeAndUnlimitedWakeAnExistingStream() throws Exception {
        AtomicLong time = new AtomicLong();
        var limiter = new BandwidthLimiter(time::get, time::addAndGet);
        byte[] data = new byte[2_000]; byte[] buffer = new byte[1_000];
        var input = limiter.wrap(new ByteArrayInputStream(data), () -> {});
        limiter.setBytesPerSecond(1_000);
        assertEquals(100, input.read(buffer)); assertEquals(100_000_000L, time.get());
        limiter.setBytesPerSecond(10_000);
        assertEquals(1_000, input.read(buffer)); assertEquals(200_000_000L, time.get());
        limiter.setBytesPerSecond(0);
        assertEquals(900, input.read(buffer)); assertEquals(200_000_000L, time.get());
    }

    @Test void cancellationIsCheckedWhileWaitingAndNeverReadsSource() {
        AtomicLong time = new AtomicLong(); AtomicInteger checkpoints = new AtomicInteger();
        var limiter = new BandwidthLimiter(time::get, time::addAndGet); limiter.setBytesPerSecond(1_000);
        var source = new ByteArrayInputStream(new byte[100]);
        var input = limiter.wrap(source, () -> { if (checkpoints.incrementAndGet() == 2) throw new InterruptedException("cancelled"); });
        var exception = assertThrows(InterruptedIOException.class, () -> input.read(new byte[100]));
        assertInstanceOf(InterruptedException.class, exception.getCause());
        assertEquals(100, source.available()); assertEquals(50_000_000L, time.get());
    }

    @Test void concurrentTransfersShareTheCeilingAndReturnEveryByte() throws Exception {
        var limiter = new BandwidthLimiter(); limiter.setBytesPerSecond(100_000);
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Callable<Integer> transfer = () -> {
                start.await();
                try (var in = limiter.wrap(new ByteArrayInputStream(new byte[25_000]), () -> {})) { return in.readAllBytes().length; }
            };
            var first = pool.submit(transfer); var second = pool.submit(transfer);
            long begun = System.nanoTime(); start.countDown();
            assertEquals(25_000, first.get(5, TimeUnit.SECONDS)); assertEquals(25_000, second.get(5, TimeUnit.SECONDS));
            assertTrue(System.nanoTime() - begun >= 390_000_000L, "Two tasks must not each get the whole budget");
        }
    }
}
