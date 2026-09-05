package com.grabx.app.grabx.core.service;

final class TransferPauseGate {
    private boolean paused;
    private boolean stopped;

    synchronized void pause() { if (!stopped) paused = true; }
    synchronized void resume() { paused = false; notifyAll(); }
    synchronized void stop() { stopped = true; paused = false; notifyAll(); }
    synchronized boolean isPaused() { return paused; }

    synchronized void awaitRunning() throws InterruptedException {
        while (paused && !stopped) wait();
        if (stopped) throw new InterruptedException("Transfer stopped");
    }
}
