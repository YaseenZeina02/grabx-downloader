import com.grabx.app.grabx.setup.*;
import com.grabx.app.grabx.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;

/** Runs with the packaged Java runtime, isolated home, no developer PATH. */
public class PackagedAppSmoke {
    public static void main(String[] args) throws Exception {
        for (var check : SetupDiagnostics.inspect()) {
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
        if (args.length > 0 && args[0].equals("register")) {
            for (var browser : BrowserSetupService.Browser.values()) {
                Path host = BrowserSetupService.register(browser, "a".repeat(32));
                if (!Files.readString(host).contains("grabx-native-host")) throw new AssertionError("Missing native launcher");
            }
        }
        System.out.println("PASS: packaged runtime, media tools, embedded extension and native host registration");
    }
}
