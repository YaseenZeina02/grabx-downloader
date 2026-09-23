package com.grabx.app.grabx.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlatformToolsTest {
    @TempDir Path temporary;

    @Test void everyWindowsAndLinuxBundleMatchesThePinnedReleaseChecksum() throws Exception {
        var loader = BundledYtDlpRuntime.class.getClassLoader();
        var manifest = new Properties();
        try (var input = loader.getResourceAsStream("tools/yt-dlp/bundled.properties")) { manifest.load(input); }
        assertEquals(BundledYtDlpRuntime.VERSION, manifest.getProperty("version"));
        for (String resource : manifest.stringPropertyNames()) {
            if (resource.equals("version")) continue;
            var hash = MessageDigest.getInstance("SHA-256");
            try (var input = loader.getResourceAsStream("tools/yt-dlp/" + resource)) {
                assertNotNull(input, resource);
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) hash.update(buffer, 0, count);
            }
            assertEquals(manifest.getProperty(resource), HexFormat.of().formatHex(hash.digest()), resource);
        }
    }

    @Test void unsupportedArchitecturesNeverSilentlyUseAnX64Executable() {
        assertNull(BundledYtDlpRuntime.platformResource(YtDlpManager.OS.LINUX, YtDlpManager.ARCH.X86));
        assertNull(BundledYtDlpRuntime.platformResource(YtDlpManager.OS.WINDOWS, YtDlpManager.ARCH.OTHER));
        assertEquals("linux/arm64/yt-dlp", BundledYtDlpRuntime.platformResource(YtDlpManager.OS.LINUX, YtDlpManager.ARCH.ARM64));
        assertEquals("windows/x64/yt-dlp.exe", BundledYtDlpRuntime.platformResource(YtDlpManager.OS.WINDOWS, YtDlpManager.ARCH.X64));
        assertNull(FfmpegManager.chooseAssetName(YtDlpManager.OS.WINDOWS, YtDlpManager.ARCH.X86));
        assertNull(FfmpegManager.chooseAssetName(YtDlpManager.OS.WINDOWS, YtDlpManager.ARCH.ARM64));
        assertEquals("ffmpeg-linux-arm64.tar.xz", FfmpegManager.chooseAssetName(YtDlpManager.OS.LINUX, YtDlpManager.ARCH.ARM64));
        assertEquals("ffmpeg-win-x64.zip", FfmpegManager.chooseAssetName(YtDlpManager.OS.WINDOWS, YtDlpManager.ARCH.X64));
    }

    @Test void toolDiscoveryHandlesMinimalGuiPathsAndQuotedWindowsPaths() {
        var linux = ToolExecutable.candidates("ffmpeg", Map.of("PATH", "/bin"), temporary, false);
        assertTrue(linux.contains(temporary.resolve(".local/bin/ffmpeg")));
        assertTrue(linux.contains(Path.of("/usr/bin/ffmpeg")));
        var windows = ToolExecutable.candidates("ffmpeg.exe", Map.of("PATH", "\"C:\\Tools Folder\";D:\\Tools",
                "LOCALAPPDATA", "C:\\User Data"), temporary, true);
        assertTrue(windows.contains(Path.of("C:\\Tools Folder").resolve("ffmpeg.exe")));
        assertTrue(windows.contains(Path.of("C:\\User Data", "Microsoft", "WinGet", "Links", "ffmpeg.exe")));
    }

    @Test void bundledToolForThisHostInstallsAndRuns() throws Exception {
        var os = YtDlpManager.detectOS();
        if (os != YtDlpManager.OS.WINDOWS && os != YtDlpManager.OS.LINUX) return;
        Path executable = BundledYtDlpRuntime.installStandalone(temporary, os, YtDlpManager.detectArch());
        assertNotNull(executable);
        assertEquals(BundledYtDlpRuntime.VERSION, ToolExecutable.version(executable, "--version"));
        assertEquals(executable, BundledYtDlpRuntime.installStandalone(temporary, os, YtDlpManager.detectArch()));
    }

    @Test void invalidToolPathsAreNotAccepted() throws Exception {
        assertNull(ToolExecutable.version(temporary.resolve("missing"), "--version"));
        assertNull(ToolExecutable.version(temporary, "--version"));
        Path broken = Files.writeString(temporary.resolve("not-a-program"), "not an executable");
        broken.toFile().setExecutable(true);
        assertNull(ToolExecutable.version(broken, "--version"));
    }
}
