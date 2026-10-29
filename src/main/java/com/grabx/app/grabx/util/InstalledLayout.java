package com.grabx.app.grabx.util;

import java.nio.file.Files;
import java.nio.file.Path;

/** Shared by packaged launchers, tool discovery and the setup assistant. */
public final class InstalledLayout {
    private InstalledLayout() {}
    public static Path contentDirectory() {
        try {
            return Path.of(InstalledLayout.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
        } catch (Exception e) { throw new IllegalStateException("Cannot locate GrabX application files", e); }
    }
    public static Path tool(String name) {
        String suffix = System.getProperty("os.name", "").toLowerCase().contains("win") ? ".exe" : "";
        Path repaired = PackagedToolRepair.repairedDirectory();
        if (repaired != null && Files.isDirectory(repaired)) return repaired.resolve(name + suffix);
        return contentDirectory().resolve("tools").resolve(name + suffix);
    }
    public static Path nativeLauncher() {
        String launcher = System.getProperty("jpackage.app-path");
        if (launcher == null) throw new IllegalStateException("Browser linking requires an installed GrabX package.");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path path = Path.of(launcher).resolveSibling("grabx-native-host" + (windows ? ".exe" : ""));
        if (System.getProperty("os.name", "").toLowerCase().contains("mac") && path.startsWith("/Volumes"))
            throw new IllegalStateException("Move GrabX to Applications and reopen it before connecting the browser.");
        if (!Files.isRegularFile(path)) throw new IllegalStateException("Native browser launcher is missing. Reinstall GrabX.");
        return path;
    }
}
