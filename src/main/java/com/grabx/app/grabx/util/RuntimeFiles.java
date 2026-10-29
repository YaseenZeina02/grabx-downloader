package com.grabx.app.grabx.util;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.UUID;

/** Transactional replacement of GrabX-owned runtime directories only. */
final class RuntimeFiles {
    private RuntimeFiles() {}
    static String sha256(Path file) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
    }
    static void replace(Path staged, Path destination) throws IOException {
        if (Files.isSymbolicLink(destination)) throw new IOException("Runtime directory must not be a symbolic link: " + destination);
        Path backup = destination.resolveSibling(".backup-" + UUID.randomUUID());
        boolean existed = Files.exists(destination);
        if (existed) move(destination, backup);
        try { move(staged, destination); }
        catch (IOException error) {
            if (existed) try { move(backup, destination); } catch (IOException rollback) { error.addSuppressed(rollback); }
            throw error;
        }
        // A locked old file must not invalidate a successfully installed replacement.
        if (existed) try { deleteTree(backup); } catch (IOException ignored) { }
    }
    private static void move(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException error) { Files.move(from, to); }
    }
    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var files = Files.walk(root)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }
}
