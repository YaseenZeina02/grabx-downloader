package com.grabx.app.grabx.core.service;

/** Per-transfer throughput experiments. No host history or external probes are needed. */
final class AdaptiveConnections {
    static final long WINDOW = 4_000_000_000L;
    static final long COOLDOWN = 30_000_000_000L;
    private final int maximum;
    private int connections, previousConnections, slowWindows;
    private long lastTime = -1, lastBytes, nextProbe;
    private double baseline;
    private boolean probing;

    AdaptiveConnections(int maximum) {
        if (maximum < 1 || maximum > 64) throw new IllegalArgumentException("Invalid connection ceiling");
        this.maximum = maximum;
        connections = Math.min(4, maximum);
    }

    int connections() { return connections; }

    void reset(long bytes, long now) {
        lastTime = now;
        lastBytes = bytes;
        baseline = 0;
        probing = false;
        slowWindows = 0;
    }

    int sample(long bytes, long now, long speedLimit, boolean enoughWork) {
        // A shared user speed ceiling and the final few pieces obscure server capacity.
        if (speedLimit > 0 || !enoughWork || lastTime < 0 || bytes < lastBytes) {
            reset(bytes, now);
            return connections;
        }
        if (now - lastTime < WINDOW) return connections;
        double speed = (bytes - lastBytes) * 1e9 / (now - lastTime);
        lastTime = now;
        lastBytes = bytes;
        if (speed <= 0) return connections; // Existing stalled/server-busy recovery handles this.
        if (probing) {
            probing = false;
            if (speed < baseline * 1.10) {
                connections = previousConnections;
                nextProbe = now + COOLDOWN;
                slowWindows = 0;
                return connections;
            }
            baseline = speed;
        } else if (baseline > 0 && speed < baseline * .70) {
            if (++slowWindows < 2) return connections;
            connections = Math.max(1, connections / 2);
            baseline = 0;
            slowWindows = 0;
            nextProbe = now + COOLDOWN;
            return connections;
        } else {
            slowWindows = 0;
            baseline = speed;
        }
        if (now >= nextProbe && connections < maximum) {
            previousConnections = connections;
            connections = Math.min(maximum, connections * 2);
            probing = true;
        }
        return connections;
    }
}
