package com.grabx.app.grabx.util;

import com.grabx.app.grabx.setup.ExtensionBundleService;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;

/** Runs on a worker. Existing usable bundled/system tools are reused before installation. */
public final class DevicePreparation {
    private DevicePreparation() {}
    public static synchronized List<SetupDiagnostics.Check> prepare() {
        var failures = new ArrayList<SetupDiagnostics.Check>();
        attempt(failures, "Bundled tools", PackagedToolRepair::prepare);
        boolean bundledFailure = !failures.isEmpty();
        FfmpegManager.resetResolution();
        YtDlpManager.resetResolution();
        attempt(failures, "Browser extension", () -> ExtensionBundleService.forCurrentUser().prepare());
        attempt(failures, "FFmpeg", () -> {
            if (bundledFailure) throw new java.io.IOException("Bundled tool repair failed. See Bundled tools above.");
            if (ToolExecutable.version(FfmpegManager.ensureAvailable(), "-version") == null) throw new java.io.IOException("FFmpeg could not be prepared. Check the connection and system security report.");
        });
        attempt(failures, "YouTube JavaScript", () -> {
            if (bundledFailure) throw new java.io.IOException("Bundled tool repair failed. See Bundled tools above.");
            boolean available = false;
            for (var candidate : YtDlpOptions.candidates(System.getenv(), java.nio.file.Path.of(System.getProperty("user.home")),
                    System.getProperty("os.name").toLowerCase().contains("win"))) {
                if (YtDlpOptions.supportedVersion(candidate.name(), ToolExecutable.version(candidate.path(), "--version"))) { available = true; break; }
            }
            if (!available) NodeRuntimeInstaller.ensureAvailable();
            YtDlpOptions.refreshRuntime();
        });
        attempt(failures, "yt-dlp", () -> {
            var path = YtDlpManager.ensureAvailable();
            if (path == null || ToolExecutable.version(path, "--version") == null)
                throw new java.io.IOException("The included yt-dlp could not run. Check the system security report or repair GrabX.");
        });
        var result = new ArrayList<>(SetupDiagnostics.inspect());
        for (var failure : failures) { result.removeIf(check -> check.name().equals(failure.name())); result.add(failure); }
        if (failures.stream().noneMatch(check -> check.name().equals("Browser extension")))
            result.add(new SetupDiagnostics.Check("Browser extension", true, "Extension files are ready. Add the extension in your browser, then connect from Settings.", ""));
        return List.copyOf(result);
    }
    @FunctionalInterface private interface Work { void run() throws Exception; }
    private static void attempt(List<SetupDiagnostics.Check> failures, String name, Work work) {
        try { work.run(); } catch (Exception error) {
            failures.add(new SetupDiagnostics.Check(name, false, error.getMessage() == null ? error.toString() : error.getMessage(), ""));
        }
    }
}
