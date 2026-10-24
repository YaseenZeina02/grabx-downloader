package com.grabx.app.grabx.core.service;

import java.io.*;
import java.util.Objects;
import java.util.function.LongSupplier;

/** One interruptible token bucket shared by all direct transfers, including verification. */
public final class BandwidthLimiter {
    @FunctionalInterface public interface Checkpoint { void check() throws InterruptedException; }
    @FunctionalInterface interface Sleeper { void sleep(long nanos) throws InterruptedException; }
    private final LongSupplier clock;
    private final Sleeper sleeper;
    private long rate, last;
    private double tokens;

    public BandwidthLimiter() {
        this(System::nanoTime, nanos -> Thread.sleep(nanos / 1_000_000, (int) (nanos % 1_000_000)));
    }
    BandwidthLimiter(LongSupplier clock, Sleeper sleeper) {
        this.clock = clock; this.sleeper = sleeper; last = clock.getAsLong();
    }
    public synchronized void setBytesPerSecond(long value) {
        if (value < 0) throw new IllegalArgumentException("Negative speed limit");
        if (rate == value) return;
        rate = value; tokens = 0; last = clock.getAsLong();
    }
    public synchronized long bytesPerSecond() { return rate; }
    private double capacity() { return Math.max(1, Math.min(16 * 1024, rate / 10.0)); }
    private int acquire(int requested, Checkpoint checkpoint) throws InterruptedException {
        while (true) {
            checkpoint.check();
            long wait;
            synchronized (this) {
                if (rate == 0) return requested;
                long now = clock.getAsLong();
                tokens = Math.min(capacity(), tokens + Math.max(0, now - last) * (rate / 1e9));
                last = now;
                int target = Math.min(requested, (int) capacity());
                int grant = tokens >= target ? target : 0;
                if (grant > 0) { tokens -= grant; return grant; }
                wait = Math.max(1, Math.min(50_000_000L, (long) Math.ceil((target - tokens) * 1e9 / rate)));
            }
            // Never hold the shared lock while paused or sleeping.
            sleeper.sleep(wait);
        }
    }
    private synchronized void refund(int count) {
        if (rate > 0) tokens = Math.min(capacity(), tokens + count);
    }
    public InputStream wrap(InputStream input, Checkpoint checkpoint) {
        return new FilterInputStream(input) {
            @Override public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                Objects.checkFromIndexSize(offset, length, bytes.length);
                if (length == 0) return 0;
                int allowed;
                try { allowed = acquire(length, checkpoint); }
                catch (InterruptedException e) {
                    InterruptedIOException stopped = new InterruptedIOException("Transfer stopped while limiting speed");
                    stopped.initCause(e); throw stopped;
                }
                int count = in.read(bytes, offset, allowed);
                refund(allowed - Math.max(0, count));
                return count;
            }
        };
    }
}
