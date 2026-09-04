package com.grabx.app.grabx.browser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/** Atomic, user-private handoff directory shared by the native host and GrabX. */
public final class BrowserBridgeInbox {
    private static final Set<PosixFilePermission> OWNER_DIRECTORY = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE
    );
    private static final Set<PosixFilePermission> OWNER_FILE = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE
    );

    private final Path directory;

    public BrowserBridgeInbox(Path directory) {
        this.directory = directory;
    }

    public static BrowserBridgeInbox forCurrentUser() {
        return new BrowserBridgeInbox(Path.of(System.getProperty("user.home"), ".grabx", "browser-inbox"));
    }

    public Path directory() {
        return directory;
    }

    public Path enqueue(String requestId, byte[] json) throws IOException {
        ensureDirectory();
        String safeId = requestId.replaceAll("[^A-Za-z0-9._-]", "_");
        Path temporary = Files.createTempFile(directory, safeId + "-", ".tmp");
        try {
            Files.write(temporary, json, StandardOpenOption.TRUNCATE_EXISTING);
            setPermissions(temporary, OWNER_FILE);
            Path destination = directory.resolve(safeId + ".json");
            try {
                return Files.move(temporary, destination,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                return Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public void ensureDirectory() throws IOException {
        Files.createDirectories(directory);
        setPermissions(directory, OWNER_DIRECTORY);
    }

    private static void setPermissions(Path path, Set<PosixFilePermission> permissions) {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows and non-POSIX filesystems use their platform ACLs.
        }
    }
}
