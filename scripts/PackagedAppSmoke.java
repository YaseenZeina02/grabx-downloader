import com.grabx.app.grabx.setup.*;
import com.grabx.app.grabx.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;

/** Runs with the packaged Java runtime, isolated home, no developer PATH. */
public class PackagedAppSmoke {
    public static void main(String[] args) throws Exception {
        for (var check : DevicePreparation.prepare()) {
            System.out.println(check.name() + ": " + check.ready() + " · " + check.detail());
            if (!check.ready()) throw new AssertionError("Missing bundled capability: " + check.name());
        }
        Path extension = ExtensionBundleService.forCurrentUser().prepare();
        var manifest = new ObjectMapper().readTree(extension.resolve("manifest.json").toFile());
        for (var file : manifest.path("icons")) if (!Files.isRegularFile(extension.resolve(file.asText()))) throw new AssertionError("Missing extension icon");
        for (var script : manifest.path("content_scripts"))
            for (var file : script.path("js")) if (!Files.isRegularFile(extension.resolve(file.asText()))) throw new AssertionError("Missing script");
        if (!extension.equals(ExtensionBundleService.forCurrentUser().prepare())) throw new AssertionError("Unstable extension path");
        Path media = com.grabx.app.grabx.util.YtDlpManager.ensureAvailable();
        if (media == null) throw new AssertionError("Cannot prepare bundled yt-dlp");
        Process probe = new ProcessBuilder(media.toString(), "--version").redirectErrorStream(true).start();
        if (!probe.waitFor(20, java.util.concurrent.TimeUnit.SECONDS) || probe.exitValue() != 0) throw new AssertionError("Bundled yt-dlp cannot run");
        // Exercise actual offline media conversion and JavaScript execution.
        Path audio = Path.of(System.getProperty("user.home"), "conversion.mp3");
        Process conversion = new ProcessBuilder(InstalledLayout.tool("ffmpeg").toString(), "-y", "-f", "lavfi",
                "-i", "sine=frequency=1000:duration=0.2", audio.toString()).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if (!conversion.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) || conversion.exitValue() != 0 || Files.size(audio) < 100)
            throw new AssertionError("Offline MP3 conversion failed");
        Process javascript = new ProcessBuilder(InstalledLayout.tool("node").toString(), "-e", "if (6*7 !== 42) process.exit(1)").start();
        if (!javascript.waitFor(15, java.util.concurrent.TimeUnit.SECONDS) || javascript.exitValue() != 0)
            throw new AssertionError("JavaScript execution failed");
        // Damage only this isolated user's extracted files, then repair in the same process.
        Path isolatedHome = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        if (!media.toAbsolutePath().normalize().startsWith(isolatedHome)
                || !extension.toAbsolutePath().normalize().startsWith(isolatedHome))
            throw new AssertionError("Preparation escaped the isolated user directory");
        Files.writeString(media, "damaged media runtime");
        Files.delete(extension.resolve("manifest.json"));
        for (var check : DevicePreparation.prepare()) if (!check.ready()) throw new AssertionError("Repair failed: " + check);
        Path repaired = YtDlpManager.ensureAvailable();
        Process repairedProbe = new ProcessBuilder(repaired.toString(), "--version").start();
        if (!repairedProbe.waitFor(20, java.util.concurrent.TimeUnit.SECONDS) || repairedProbe.exitValue() != 0)
            throw new AssertionError("Repaired yt-dlp cannot run");
        if (!Files.isRegularFile(extension.resolve("manifest.json"))) throw new AssertionError("Extension repair failed");
        // Check offline repair of damaged package executables, followed by damaged private copies.
        Path originalTools = InstalledLayout.contentDirectory().resolve("tools");
        String suffix = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
        Files.writeString(originalTools.resolve("node" + suffix), "damaged");
        Files.delete(originalTools.resolve("ffmpeg" + suffix));
        for (int attempt = 0; attempt < 2; attempt++) {
            for (var check : DevicePreparation.prepare()) if (!check.ready()) throw new AssertionError("Offline tool repair failed: " + check);
            if (attempt == 0) Files.writeString(InstalledLayout.tool("node"), "damaged private runtime");
        }
        System.out.println("PASS: offline conversion, JavaScript, missing/corrupt tools and extension repair, repeated preparation");
        if (args.length > 0 && args[0].equals("register")) {
            for (var browser : BrowserSetupService.Browser.values()) {
                Path host = BrowserSetupService.register(browser, "a".repeat(32));
                if (!Files.readString(host).contains("grabx-native-host")) throw new AssertionError("Missing native launcher");
            }
        }
        System.out.println("PASS: packaged runtime, media tools, embedded extension and native host registration");
    }
}
