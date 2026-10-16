package com.grabx.app.grabx.core.service;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Lightweight bounded checks; a failed download server never establishes global offline state. */
public final class ConnectivityMonitor implements AutoCloseable {
    static final class Stability {
        private Boolean confirmed;
        private Boolean candidate;
        private int samples;
        Boolean sample(boolean connected) {
            if (candidate == null || candidate != connected) { candidate = connected; samples = 1; }
            else samples++;
            if (samples < 2 || (confirmed != null && confirmed == connected)) return null;
            Boolean previous = confirmed; confirmed = connected;
            // An initially working connection is normal, not a recovery event.
            return previous == null && connected ? null : connected;
        }
        void reset() { confirmed = null; candidate = null; samples = 0; }
    }
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "grabx-connectivity"); thread.setDaemon(true); return thread;
    });
    private final BooleanSupplier enabled;
    private final BooleanSupplier probe;
    private final Consumer<Boolean> changed;
    private final Consumer<Runnable> ui;
    private final Stability stability = new Stability();
    private volatile boolean closed;
    private volatile HttpURLConnection current;
    private boolean started;
    public ConnectivityMonitor(BooleanSupplier enabled, Consumer<Boolean> changed, Consumer<Runnable> ui) {
        this.enabled = enabled; this.changed = changed; this.ui = ui; this.probe = this::reachable;
    }
    ConnectivityMonitor(BooleanSupplier enabled, BooleanSupplier probe, Consumer<Boolean> changed, Consumer<Runnable> ui) {
        this.enabled = enabled; this.probe = probe; this.changed = changed; this.ui = ui;
    }
    public void start() {
        if (started || closed) return;
        started = true;
        executor.scheduleWithFixedDelay(this::check, 1, 8, TimeUnit.SECONDS);
    }
    void check() {
        if (closed) return;
        if (!enabled.getAsBoolean()) { stability.reset(); return; }
        boolean connected;
        try { connected = probe.getAsBoolean(); } catch (RuntimeException error) { connected = false; }
        if (closed || !enabled.getAsBoolean()) { stability.reset(); return; }
        Boolean transition = stability.sample(connected);
        if (transition != null) ui.accept(() -> {
            if (!closed && enabled.getAsBoolean()) changed.accept(transition);
        });
    }
    private boolean reachable() {
        // Only fixed public endpoints are contacted. Download URLs/filenames are never sent.
        return checkEndpoint("https://www.gstatic.com/generate_204")
                || (!closed && checkEndpoint("https://cp.cloudflare.com/generate_204"));
    }
    boolean checkEndpoint(String address) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(address).toURL().openConnection();
            current = connection;
            if (closed) return false;
            connection.setConnectTimeout(2500); connection.setReadTimeout(2500);
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "GrabX-Connectivity/1.0");
            return connection.getResponseCode() == 204;
        } catch (Exception error) { return false; }
        finally { if (connection != null) connection.disconnect(); current = null; }
    }
    @Override public void close() {
        closed = true; executor.shutdownNow();
        HttpURLConnection connection = current;
        if (connection != null) connection.disconnect();
    }
}
