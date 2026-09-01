package com.grabx.app.grabx.core.service;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaylistProbeSchedulerTest {
    @Test
    void deduplicatesConcurrentRequestsAndCachesResult() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        PlaylistProbeScheduler scheduler = new PlaylistProbeScheduler(url -> {
            calls.incrementAndGet();
            return Set.of(720, 1080);
        }, Runnable::run, 1);
        long session = scheduler.beginSession();
        CountDownLatch callbacks = new CountDownLatch(2);

        assertTrue(scheduler.request(session, "id", "url", PlaylistProbeScheduler.Priority.VISIBLE,
                heights -> callbacks.countDown()));
        assertTrue(scheduler.request(session, "id", "url", PlaylistProbeScheduler.Priority.SELECTED,
                heights -> callbacks.countDown()));
        assertTrue(callbacks.await(2, TimeUnit.SECONDS));

        CountDownLatch cached = new CountDownLatch(1);
        scheduler.request(session, "id", "url", PlaylistProbeScheduler.Priority.PREFETCH,
                heights -> cached.countDown());
        assertTrue(cached.await(1, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
    }

    @Test
    void ignoresCallbacksFromCancelledSession() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Set<Integer>> delivered = new AtomicReference<>();
        PlaylistProbeScheduler scheduler = new PlaylistProbeScheduler(url -> {
            started.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return Set.of(1080);
        }, Runnable::run, 1);
        long session = scheduler.beginSession();

        scheduler.request(session, "id", "url", PlaylistProbeScheduler.Priority.VISIBLE, delivered::set);
        assertTrue(started.await(1, TimeUnit.SECONDS));
        scheduler.cancelSession(session);
        release.countDown();
        Thread.sleep(100);

        assertNull(delivered.get());
    }

    @Test
    void promotesQueuedPrefetchWhenItemBecomesSelected() throws Exception {
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(3);
        List<String> executionOrder = new CopyOnWriteArrayList<>();
        PlaylistProbeScheduler scheduler = new PlaylistProbeScheduler(url -> {
            executionOrder.add(url);
            if ("blocker".equals(url)) {
                blockerStarted.countDown();
                try {
                    releaseBlocker.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            return Set.of(720);
        }, Runnable::run, 1);
        long session = scheduler.beginSession();

        scheduler.request(session, "blocker", "blocker", PlaylistProbeScheduler.Priority.VISIBLE,
                heights -> completed.countDown());
        assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));
        scheduler.request(session, "first", "first", PlaylistProbeScheduler.Priority.PREFETCH,
                heights -> completed.countDown());
        scheduler.request(session, "promoted", "promoted", PlaylistProbeScheduler.Priority.PREFETCH,
                heights -> completed.countDown());

        assertTrue(scheduler.promote(session, "promoted", PlaylistProbeScheduler.Priority.SELECTED));
        releaseBlocker.countDown();
        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertEquals(List.of("blocker", "promoted", "first"), executionOrder);
    }

    @Test
    void cancelsQueuedProbeWithoutInterruptingRunningProbe() throws Exception {
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        CountDownLatch blockerCompleted = new CountDownLatch(1);
        AtomicInteger queuedCallbacks = new AtomicInteger();
        PlaylistProbeScheduler scheduler = new PlaylistProbeScheduler(url -> {
            if ("blocker".equals(url)) {
                blockerStarted.countDown();
                try {
                    releaseBlocker.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            return Set.of(1080);
        }, Runnable::run, 1);
        long session = scheduler.beginSession();

        scheduler.request(session, "blocker", "blocker", PlaylistProbeScheduler.Priority.VISIBLE,
                heights -> blockerCompleted.countDown());
        assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));
        scheduler.request(session, "queued", "queued", PlaylistProbeScheduler.Priority.PREFETCH,
                heights -> queuedCallbacks.incrementAndGet());

        assertTrue(scheduler.cancelQueued(session, "queued"));
        assertFalse(scheduler.cancelQueued(session, "blocker"));
        releaseBlocker.countDown();
        assertTrue(blockerCompleted.await(2, TimeUnit.SECONDS));
        assertEquals(0, queuedCallbacks.get());
    }
}
