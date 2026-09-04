package com.grabx.app.grabx.util;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Holds an OS-level lock for the lifetime of one GrabX process. */
public final class SingleInstanceGuard implements AutoCloseable {
    private final Path lockFile;
    private FileChannel channel;
    private FileLock lock;

    public SingleInstanceGuard(Path lockFile) {
        this.lockFile = lockFile;
    }

    public static SingleInstanceGuard forCurrentUser() {
        return new SingleInstanceGuard(
                Path.of(System.getProperty("user.home"), ".grabx", "grabx.lock")
        );
    }

    public synchronized boolean acquire() {
        if (lock != null && lock.isValid()) return true;
        try {
            Path parent = lockFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = channel.tryLock();
            if (lock != null) return true;
        } catch (OverlappingFileLockException ignored) {
            // Another GrabX instance in this JVM owns the lock.
        } catch (IOException ignored) {
            // If locking is unavailable, fail open rather than making GrabX unusable.
            close();
            return true;
        }
        close();
        return false;
    }

    @Override
    public synchronized void close() {
        try {
            if (lock != null) lock.release();
        } catch (Exception ignored) {
        } finally {
            lock = null;
        }
        try {
            if (channel != null) channel.close();
        } catch (Exception ignored) {
        } finally {
            channel = null;
        }
    }
}
