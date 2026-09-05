package com.grabx.app.grabx.core.service;

import java.util.ArrayDeque;

/** Rolling throughput; saved bytes never count as newly transferred data. */
final class TransferSpeedMeter {
    private record Sample(long time, long bytes) {}
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private long lastBytes;
    private long lastDataTime;

    TransferSpeedMeter(long bytes, long now) { reset(bytes, now); }

    void reset(long bytes, long now) {
        samples.clear();
        samples.add(new Sample(now, bytes));
        lastBytes = bytes;
        lastDataTime = now;
    }

    long sample(long bytes, long now) {
        if (bytes < lastBytes) reset(bytes, now);
        if (bytes > lastBytes) lastDataTime = now;
        lastBytes = bytes;
        samples.addLast(new Sample(now, bytes));
        while (samples.size() > 2 && now - samples.peekFirst().time() > 5_000_000_000L) samples.removeFirst();
        if (now - lastDataTime >= 5_000_000_000L) return 0;
        Sample first = samples.peekFirst();
        long elapsed = now - first.time();
        return elapsed <= 0 ? 0 : Math.max(0, Math.round((bytes - first.bytes()) * 1_000_000_000.0 / elapsed));
    }
}
