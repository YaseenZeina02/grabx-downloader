package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.probe.VideoProbeService;
import javafx.application.Platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/** Schedules lightweight playlist quality probes without blocking the JavaFX thread. */
public final class PlaylistProbeScheduler {
    private static final int MAX_CACHE_ENTRIES = 256;

    public enum Priority {
        SELECTED(0), VISIBLE(1), PREFETCH(2);

        private final int order;

        Priority(int order) {
            this.order = order;
        }
    }

    private final Function<String, Set<Integer>> probe;
    private final Consumer<Runnable> callbackExecutor;
    private final ThreadPoolExecutor executor;
    private final Map<String, Set<Integer>> cache = new ConcurrentHashMap<>();
    private final Map<String, PendingProbe> pending = new ConcurrentHashMap<>();
    private final AtomicLong sessionSequence = new AtomicLong();
    private final AtomicLong taskSequence = new AtomicLong();
    private volatile long activeSession;

    public PlaylistProbeScheduler(VideoProbeService videoProbeService) {
        this(videoProbeService::probeHeights, Platform::runLater, 2);
    }

    PlaylistProbeScheduler(
            Function<String, Set<Integer>> probe,
            Consumer<Runnable> callbackExecutor,
            int threadCount
    ) {
        this.probe = probe;
        this.callbackExecutor = callbackExecutor;
        int threads = Math.max(1, threadCount);
        this.executor = new ThreadPoolExecutor(
                threads,
                threads,
                30L,
                TimeUnit.SECONDS,
                new PriorityBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "playlist-probe");
                    thread.setDaemon(true);
                    return thread;
                }
        );
    }

    public long beginSession() {
        long session = sessionSequence.incrementAndGet();
        activeSession = session;
        executor.getQueue().removeIf(task -> task instanceof ProbeTask probeTask
                && probeTask.session != session);
        pending.entrySet().removeIf(entry -> entry.getValue().session != session);
        return session;
    }

    public void cancelSession(long session) {
        if (activeSession != session) return;
        activeSession = 0L;
        executor.getQueue().removeIf(task -> task instanceof ProbeTask probeTask
                && probeTask.session == session);
        pending.entrySet().removeIf(entry -> entry.getValue().session == session);
    }

    public void shutdown() {
        activeSession = 0L;
        pending.clear();
        executor.getQueue().clear();
        executor.shutdownNow();
    }

    public boolean request(
            long session,
            String videoId,
            String videoUrl,
            Priority priority,
            Consumer<Set<Integer>> onDone
    ) {
        if (session != activeSession || isBlank(videoId) || isBlank(videoUrl) || onDone == null) {
            return false;
        }

        Set<Integer> cached = cache.get(videoId);
        if (cached != null) {
            deliver(session, onDone, cached);
            return true;
        }

        String key = session + ":" + videoId;
        Priority requestedPriority = priority == null ? Priority.PREFETCH : priority;
        PendingProbe created = new PendingProbe(session, requestedPriority);
        PendingProbe current = pending.putIfAbsent(key, created);
        PendingProbe target = current == null ? created : current;
        target.add(onDone);
        if (current != null) {
            current.promote(requestedPriority);
            return true;
        }

        ProbeTask task = new ProbeTask(
                session,
                key,
                videoId,
                videoUrl,
                requestedPriority,
                taskSequence.incrementAndGet()
        );
        created.attach(task);
        executor.execute(task);
        return true;
    }

    /** Raises an already queued probe's priority without starting a duplicate probe. */
    public boolean promote(long session, String videoId, Priority priority) {
        if (session != activeSession || isBlank(videoId) || priority == null) return false;
        PendingProbe current = pending.get(session + ":" + videoId);
        if (current == null) return cache.containsKey(videoId);
        current.promote(priority);
        return true;
    }

    /** Cancels a probe only while it is still queued; a running external process is left intact. */
    public boolean cancelQueued(long session, String videoId) {
        if (session != activeSession || isBlank(videoId)) return false;
        String key = session + ":" + videoId;
        PendingProbe current = pending.get(key);
        if (current == null || !current.removeFromQueue()) return false;
        pending.remove(key, current);
        return true;
    }

    private void runProbe(ProbeTask task) {
        Set<Integer> heights;
        try {
            Set<Integer> result = probe.apply(task.videoUrl);
            heights = result == null ? Set.of() : Set.copyOf(result);
            cacheResult(task.videoId, heights);
        } catch (Exception ignored) {
            heights = Set.of();
        }

        PendingProbe completed = pending.remove(task.key);
        if (completed == null || task.session != activeSession) return;
        for (Consumer<Set<Integer>> listener : completed.listeners()) {
            deliver(task.session, listener, heights);
        }
    }

    private void deliver(long session, Consumer<Set<Integer>> listener, Set<Integer> heights) {
        callbackExecutor.accept(() -> {
            if (session == activeSession) listener.accept(heights);
        });
    }

    private void cacheResult(String videoId, Set<Integer> heights) {
        if (!cache.containsKey(videoId) && cache.size() >= MAX_CACHE_ENTRIES) {
            var iterator = cache.keySet().iterator();
            if (iterator.hasNext()) cache.remove(iterator.next());
        }
        cache.put(videoId, heights);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private final class PendingProbe {
        private final long session;
        private final List<Consumer<Set<Integer>>> listeners = new ArrayList<>();
        private Priority desiredPriority;
        private ProbeTask task;

        private PendingProbe(long session, Priority priority) {
            this.session = session;
            this.desiredPriority = priority;
        }

        private synchronized void add(Consumer<Set<Integer>> listener) {
            listeners.add(listener);
        }

        private synchronized List<Consumer<Set<Integer>>> listeners() {
            return List.copyOf(listeners);
        }

        private synchronized void attach(ProbeTask newTask) {
            task = newTask;
            task.priority = desiredPriority;
        }

        private synchronized void promote(Priority priority) {
            if (priority.order >= desiredPriority.order) return;
            desiredPriority = priority;
            if (task == null || !executor.getQueue().remove(task)) return;
            task.priority = priority;
            executor.getQueue().offer(task);
        }

        private synchronized boolean removeFromQueue() {
            return task != null && executor.getQueue().remove(task);
        }
    }

    private final class ProbeTask implements Runnable, Comparable<ProbeTask> {
        private final long session;
        private final String key;
        private final String videoId;
        private final String videoUrl;
        private volatile Priority priority;
        private final long sequence;

        private ProbeTask(long session, String key, String videoId, String videoUrl, Priority priority, long sequence) {
            this.session = session;
            this.key = key;
            this.videoId = videoId;
            this.videoUrl = videoUrl;
            this.priority = priority;
            this.sequence = sequence;
        }

        @Override
        public void run() {
            if (session == activeSession) runProbe(this);
            else pending.remove(key);
        }

        @Override
        public int compareTo(ProbeTask other) {
            int byPriority = Integer.compare(priority.order, other.priority.order);
            return byPriority != 0 ? byPriority : Long.compare(sequence, other.sequence);
        }
    }
}
