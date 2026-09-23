package com.grabx.app.grabx.util;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class YtDlpOptionsTest {
    @Test void explicitlyEnablesTheSolverAndPreservesTheBasicClientWhenUnavailable() {
        Path node = Path.of("/tools with spaces/node");
        assertEquals(List.of("--js-runtimes", "node:" + node.toAbsolutePath()),
                YtDlpOptions.argumentsFor(new YtDlpOptions.Runtime("node", node)));
        assertEquals(List.of("--extractor-args", "youtube:player_client=android"), YtDlpOptions.argumentsFor(null));
    }

    @Test void rejectsRuntimesTooOldForTheBundledChallengeSolver() {
        assertFalse(YtDlpOptions.supportedVersion("node", "v20.19.0"));
        assertTrue(YtDlpOptions.supportedVersion("node", "v22.0.0"));
        assertTrue(YtDlpOptions.supportedVersion("node", "v26.7.0"));
        assertFalse(YtDlpOptions.supportedVersion("deno", "deno 2.2.0"));
        assertTrue(YtDlpOptions.supportedVersion("deno", "deno 2.3.0 (stable, release, aarch64-apple-darwin)"));
        assertFalse(YtDlpOptions.supportedVersion("node", "not installed"));
    }

    @Test void findsRuntimesWhenTheGuiDidNotInheritTheShellPath() {
        var mac = YtDlpOptions.candidates(Map.of("PATH", "/usr/bin:/bin"), Path.of("/Users/test"), false);
        assertTrue(mac.contains(new YtDlpOptions.Runtime("node", Path.of("/opt/homebrew/bin/node"))));
        assertTrue(mac.contains(new YtDlpOptions.Runtime("deno", Path.of("/Users/test/.deno/bin/deno"))));
        var windows = YtDlpOptions.candidates(Map.of("ProgramFiles", "C:\\Program Files"), Path.of("C:\\Users\\test"), true);
        assertTrue(windows.contains(new YtDlpOptions.Runtime("node", Path.of("C:\\Program Files").resolve("nodejs/node.exe"))));
    }
    @Test void audioOnlyKeepsItsOriginalCodecSelection() {
        assertEquals(List.of(), YtDlpOptions.selectionArguments("bestaudio/best"));
        assertEquals(YtDlpOptions.videoFormatArguments(), YtDlpOptions.selectionArguments("bv*+ba/best"));
    }
}
