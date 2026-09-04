package com.grabx.app.grabx.browser;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Watches the browser inbox without keeping a polling timer alive. */
public final class BrowserBridgeService implements AutoCloseable {
    private final BrowserBridgeInbox inbox;
    private final BrowserBridgeProtocol protocol;
    private final Consumer<BrowserCapture> captureHandler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService watcherExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "browser-bridge");
        thread.setDaemon(true);
        return thread;
    });
    private final Path runningMarker;
    private WatchService watchService;

    public BrowserBridgeService(Consumer<BrowserCapture> captureHandler) {
        this(BrowserBridgeInbox.forCurrentUser(), new BrowserBridgeProtocol(), captureHandler);
    }

    BrowserBridgeService(
            BrowserBridgeInbox inbox,
            BrowserBridgeProtocol protocol,
            Consumer<BrowserCapture> captureHandler
    ) {
        this.inbox = inbox;
        this.protocol = protocol;
        this.captureHandler = captureHandler;
        this.runningMarker = inbox.directory().getParent().resolve("app.pid");
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        try {
            inbox.ensureDirectory();
            writeRunningMarker();
            watchService = FileSystems.getDefault().newWatchService();
            inbox.directory().register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
            processPending();
            watcherExecutor.execute(this::watchLoop);
        } catch (IOException exception) {
            running.set(false);
            closeWatchService();
        }
    }

    private void watchLoop() {
        while (running.get()) {
            try {
                WatchKey key = watchService.take();
                key.pollEvents().forEach(event -> {
                    Object context = event.context();
                    if (context instanceof Path relative && relative.toString().endsWith(".json")) {
                        processFile(inbox.directory().resolve(relative));
                    }
                });
                if (!key.reset()) break;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ignored) {
                // A malformed request must not stop future browser captures.
            }
        }
    }

    private void processPending() {
        try (var files = Files.list(inbox.directory())) {
            files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparingLong(BrowserBridgeService::lastModified))
                    .forEach(this::processFile);
        } catch (IOException ignored) {
        }
    }

    private void processFile(Path path) {
        try {
            BrowserCapture capture = protocol.parse(Files.readAllBytes(path));
            if (captureHandler != null) captureHandler.accept(capture);
            Files.deleteIfExists(path);
        } catch (BrowserBridgeProtocol.ProtocolException exception) {
            quarantine(path);
        } catch (Exception ignored) {
            // Leave valid requests in place so they can be retried on next launch.
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static void quarantine(Path path) {
        try {
            Files.move(path, path.resolveSibling(path.getFileName() + ".invalid"));
        } catch (IOException ignored) {
        }
    }

    private void writeRunningMarker() {
        try {
            Files.writeString(runningMarker, Long.toString(ProcessHandle.current().pid()));
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        running.set(false);
        closeWatchService();
        watcherExecutor.shutdownNow();
        try {
            if (Files.exists(runningMarker)
                    && Files.readString(runningMarker).trim().equals(Long.toString(ProcessHandle.current().pid()))) {
                Files.deleteIfExists(runningMarker);
            }
        } catch (IOException ignored) {
        }
    }

    private void closeWatchService() {
        try {
            if (watchService != null) watchService.close();
        } catch (IOException ignored) {
        }
    }
}
