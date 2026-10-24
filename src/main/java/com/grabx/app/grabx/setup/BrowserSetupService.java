package com.grabx.app.grabx.setup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grabx.app.grabx.util.InstalledLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Registers a packaged native launcher for this user and the exact extension ID. */
public final class BrowserSetupService {
    public enum Browser {
        CHROME("Google Chrome", "chrome://extensions", "Google/Chrome", "google-chrome", "Google\\Chrome"),
        BRAVE("Brave", "brave://extensions", "BraveSoftware/Brave-Browser", "BraveSoftware/Brave-Browser", "BraveSoftware\\Brave-Browser"),
        EDGE("Microsoft Edge", "edge://extensions", "Microsoft Edge", "microsoft-edge", "Microsoft\\Edge"),
        CHROMIUM("Chromium", "chrome://extensions", "Chromium", "chromium", "Chromium");
        public final String title, extensions, mac, linux, registry;
        Browser(String title, String extensions, String mac, String linux, String registry) {
            this.title = title; this.extensions = extensions; this.mac = mac; this.linux = linux; this.registry = registry;
        }
        @Override public String toString() { return title; }
    }
    private BrowserSetupService() {}
    public static boolean validId(String id) { return id != null && id.matches("[a-p]{32}"); }
    public static Path manifestPath(Browser browser, String os, Path home, Map<String, String> env) {
        if (os.toLowerCase().contains("mac")) return home.resolve("Library/Application Support").resolve(browser.mac).resolve("NativeMessagingHosts/com.grabx.browser_bridge.json");
        if (os.toLowerCase().contains("win")) return Path.of(env.getOrDefault("LOCALAPPDATA", home.resolve("AppData/Local").toString())).resolve("GrabX/browser-bridge/" + browser.name().toLowerCase() + ".json");
        String config = env.get("XDG_CONFIG_HOME");
        Path root = config != null && Path.of(config).isAbsolute() ? Path.of(config) : home.resolve(".config");
        return root.resolve(browser.linux).resolve("NativeMessagingHosts/com.grabx.browser_bridge.json");
    }
    public static String manifest(String id, Path launcher) throws IOException {
        if (!validId(id)) throw new IllegalArgumentException("Paste the 32-character extension ID (letters a–p).");
        return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "name", "com.grabx.browser_bridge", "description", "GrabX browser integration", "path", launcher.toAbsolutePath().toString(),
                "type", "stdio", "allowed_origins", List.of("chrome-extension://" + id + "/")));
    }
    public static Path register(Browser browser, String id) throws Exception {
        Path launcher = InstalledLayout.nativeLauncher();
        String json = manifest(id, launcher);
        String os = System.getProperty("os.name", "");
        Path target = manifestPath(browser, os, Path.of(System.getProperty("user.home")), System.getenv());
        Files.createDirectories(target.getParent());
        Files.writeString(target, json);
        if (os.toLowerCase().contains("win")) {
            Process p = new ProcessBuilder("reg.exe", "add", "HKCU\\Software\\" + browser.registry + "\\NativeMessagingHosts\\com.grabx.browser_bridge",
                    "/ve", "/t", "REG_SZ", "/d", target.toString(), "/f").redirectErrorStream(true).start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IOException("Browser registration timed out."); }
            if (p.exitValue() != 0) throw new IOException("Windows could not register the browser connection.");
        }
        return target;
    }
}
