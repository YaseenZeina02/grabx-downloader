package com.grabx.app.grabx.core.service;

import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ConnectivityMonitorTest {
    @Test void requiresStableEvidenceAndSuppressesInitialOnlineAndFlapping() {
        var state = new ConnectivityMonitor.Stability();
        assertNull(state.sample(true)); assertNull(state.sample(true));
        assertNull(state.sample(false)); assertNull(state.sample(true)); assertNull(state.sample(false));
        assertEquals(Boolean.FALSE, state.sample(false)); assertNull(state.sample(false));
        assertNull(state.sample(true)); assertNull(state.sample(false));
        assertNull(state.sample(true)); assertEquals(Boolean.TRUE, state.sample(true)); assertNull(state.sample(true));
    }
    @Test void startupOfflineIsReportedOnceAndRecoveryNeedsConfirmation() {
        var state = new ConnectivityMonitor.Stability();
        assertNull(state.sample(false)); assertEquals(Boolean.FALSE, state.sample(false));
        assertNull(state.sample(false)); assertNull(state.sample(true)); assertEquals(Boolean.TRUE, state.sample(true));
        state.reset(); assertNull(state.sample(true)); assertNull(state.sample(true));
    }
    @Test void disablingStopsProbesAndSuppressesPendingDeliveryAndShutdownIsQuiet() {
        var enabled = new AtomicBoolean(true); var probes = new AtomicInteger();
        var ui = new ArrayDeque<Runnable>(); var events = new ArrayList<Boolean>();
        var monitor = new ConnectivityMonitor(enabled::get, () -> { probes.incrementAndGet(); return false; }, events::add, ui::add);
        monitor.check(); monitor.check(); assertEquals(2, probes.get());
        enabled.set(false); ui.remove().run(); monitor.check();
        assertTrue(events.isEmpty()); assertEquals(2, probes.get());
        enabled.set(true); monitor.check(); monitor.check(); ui.remove().run(); assertEquals(List.of(false), events);
        monitor.close(); monitor.check(); assertEquals(4, probes.get());
    }
    @Test void endpointChecksRejectRedirectsAndCaptivePortalPages() throws Exception {
        for (int status : new int[]{204, 200, 302, 503}) {
            try (var server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
                 var monitor = new ConnectivityMonitor(() -> true, () -> true, b -> {}, Runnable::run)) {
                server.setSoTimeout(3000);
                var served = java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try (var socket = server.accept()) {
                        socket.setSoTimeout(3000);
                        var input = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));
                        String line; while ((line = input.readLine()) != null && !line.isEmpty()) { }
                        socket.getOutputStream().write(("HTTP/1.1 " + status + " Test\r\nContent-Length: 0\r\n"
                                + "Location: http://127.0.0.1:1/portal\r\nConnection: close\r\n\r\n")
                                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    } catch (Exception ex) { throw new RuntimeException(ex); }
                });
                assertEquals(status == 204, monitor.checkEndpoint("http://127.0.0.1:" + server.getLocalPort() + "/check"));
                served.get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
    }
    @Test void probeErrorsAreBoundedObservationsNotSchedulerFailures() {
        var events = new ArrayList<Boolean>();
        try (var monitor = new ConnectivityMonitor(() -> true, () -> { throw new IllegalStateException(); }, events::add, Runnable::run)) {
            monitor.check(); monitor.check(); monitor.check(); assertEquals(List.of(false), events);
        }
    }
}
