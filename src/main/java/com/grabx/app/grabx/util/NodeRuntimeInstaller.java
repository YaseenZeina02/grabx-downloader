package com.grabx.app.grabx.util;

import com.grabx.app.grabx.setup.ExtensionBundleService;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

/** Private app runtime: no package manager, administrator access or PATH changes. */
public final class NodeRuntimeInstaller {
    public static final String VERSION = "22.23.4";
    private NodeRuntimeInstaller() {}
    public static Path executable() {
        return ExtensionBundleService.dataRoot(System.getProperty("os.name"), Path.of(System.getProperty("user.home")), System.getenv())
                .resolve("runtimes/node-" + VERSION + "-" + platformUnchecked()).resolve(windows() ? "node.exe" : "bin/node");
    }
    private static String platformUnchecked() {
        try { return platform(System.getProperty("os.name"), System.getProperty("os.arch")); }
        catch (IOException error) { return "unsupported"; }
    }
    private static boolean windows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }
    public static String platform(String os, String architecture) throws IOException {
        String system = os.toLowerCase(Locale.ROOT), cpu = architecture.toLowerCase(Locale.ROOT);
        String arch = switch (cpu) { case "amd64", "x86_64" -> "x64"; case "aarch64", "arm64" -> "arm64"; default -> throw new IOException("Unsupported processor: " + architecture); };
        String name = (system.contains("mac") || system.contains("darwin")) ? "darwin" : system.contains("win") ? "win" : system.contains("linux") ? "linux" : null;
        if (name == null) throw new IOException("Unsupported operating system: " + os);
        return name + "-" + arch;
    }
    public static synchronized Path ensureAvailable() throws Exception {
        Path executable = executable();
        if (YtDlpOptions.supportedVersion("node", ToolExecutable.version(executable, "--version"))) return executable;
        String platform = platform(System.getProperty("os.name"), System.getProperty("os.arch"));
        Path destination = windows() ? executable.getParent() : executable.getParent().getParent();
        return install(destination, platform, NodeRuntimeInstaller::download,
                binary -> YtDlpOptions.supportedVersion("node", ToolExecutable.version(binary, "--version")));
    }
    @FunctionalInterface interface Fetch { void download(URI uri, Path output, long limit) throws Exception; }
    static Path install(Path destination, String platform, Fetch fetch, java.util.function.Predicate<Path> usable) throws Exception {
        boolean windows = platform.startsWith("win-");
        Path executable = destination.resolve(windows ? "node.exe" : "bin/node");
        String root = "node-v" + VERSION + "-" + platform;
        String filename = root + (windows ? ".zip" : ".tar.gz");
        URI base = URI.create("https://nodejs.org/dist/v" + VERSION + "/");
        Files.createDirectories(destination.getParent());
        Path staging = Files.createTempDirectory(destination.getParent(), ".node-");
        try {
            Path sums = staging.resolve("SHASUMS256.txt"), archive = staging.resolve(filename);
            fetch.download(base.resolve("SHASUMS256.txt"), sums, 1024 * 1024);
            String expected = Files.readAllLines(sums).stream().map(String::trim)
                    .filter(line -> line.matches("[a-fA-F0-9]{64}\\s+\\*?" + java.util.regex.Pattern.quote(filename)))
                    .map(line -> line.substring(0,64)).findFirst().orElseThrow(() -> new IOException("Node archive checksum is unavailable."));
            fetch.download(base.resolve(filename), archive, 100L * 1024 * 1024);
            try (InputStream input = Files.newInputStream(archive)) {
                String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
                if (!actual.equalsIgnoreCase(expected)) throw new IOException("Node archive checksum mismatch.");
            }
            Path extracted = staging.resolve("extract"); Files.createDirectories(extracted);
            if (windows) {
                Path output = extracted.resolve(root); Files.createDirectories(output);
                try (ZipFile zip = new ZipFile(archive.toFile())) {
                    for (String name : new String[]{"node.exe", "LICENSE"}) {
                        var entry = zip.getEntry(root + "/" + name);
                        if (entry == null || entry.getSize() > 150L * 1024 * 1024) throw new IOException("Node archive is incomplete.");
                        try (InputStream in = zip.getInputStream(entry)) { Files.copy(in, output.resolve(name)); }
                    }
                }
            } else {
                Process process = new ProcessBuilder("/usr/bin/tar", "-xzf", archive.toString(), "-C", extracted.toString(), root + "/bin/node", root + "/LICENSE")
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("Node extraction timed out."); }
                if (process.exitValue() != 0) throw new IOException("Node extraction failed.");
            }
            Path ready = extracted.resolve(root), binary = ready.resolve(windows ? "node.exe" : "bin/node");
            if (!windows && !binary.toFile().setExecutable(true, true)) throw new IOException("Could not enable Node execution.");
            if (!usable.test(binary)) throw new IOException("Node could not run on this device. Check your system security report.");
            // Only this versioned private directory is managed here; never replace system software.
            RuntimeFiles.replace(ready, destination);
            return executable;
        } finally { try (var paths = Files.walk(staging)) { for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } }
    }
    private static void download(URI uri, Path output, long limit) throws Exception {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        var response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(3)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(output)) {
            if (response.statusCode() != 200) throw new IOException("Node download returned HTTP " + response.statusCode());
            long count = 0; byte[] buffer = new byte[65536]; int length;
            while ((length = in.read(buffer)) != -1) { count += length; if (count > limit) throw new IOException("Node download exceeds its limit."); out.write(buffer,0,length); }
        }
    }
}
