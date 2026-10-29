package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.setup.SetupWizard;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.PopupWindow;
import javafx.stage.Window;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SetupWizardTest {
    @TempDir Path dir;
    static boolean started;
    @BeforeAll static void toolkit() throws Exception {
        assumeTrue("true".equals(System.getenv("GRABX_TEST_SETUP")));
        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); });
        assertTrue(ready.await(10, TimeUnit.SECONDS)); started = true;
    }
    @AfterAll static void stop() { if (started) Platform.exit(); }
    static void fx(Runnable action) throws Exception {
        var task = new FutureTask<Void>(action, null); Platform.runLater(task); task.get(30, TimeUnit.SECONDS);
    }
    @Test void browserSettingsPrepareBeforeIdAndRegisterOnlyTheSelectedBrowser() throws Exception {
        var ref = new Stage[1]; var connected = new CountDownLatch(1);
        var selected = new java.util.concurrent.atomic.AtomicReference<String>();
        fx(() -> {
            var pane = new com.grabx.app.grabx.setup.BrowserIntegrationPane(value -> { if (value) connected.countDown(); },
                    () -> new com.grabx.app.grabx.setup.ExtensionBundleService(dir.resolve("extension")).prepare(),
                    (browser,id) -> selected.set(browser.name() + ":" + id));
            Stage stage = new Stage(); ref[0] = stage; stage.setScene(new javafx.scene.Scene(pane,700,700)); stage.show();
            assertFalse(stage.getScene().lookup("#browser-connect-id").isVisible());
            assertTrue(((Button)stage.getScene().lookup("#browser-connect")).isDisabled());
            ((Button)stage.getScene().lookup("#browser-prepare")).fire();
        });
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); var ready = new boolean[1];
            do { fx(() -> ready[0] = ref[0].getScene().lookup("#browser-connect-id").isVisible()); if (!ready[0]) Thread.sleep(50); }
            while (!ready[0] && System.nanoTime() < deadline);
            assertTrue(ready[0]); assertTrue(java.nio.file.Files.isRegularFile(dir.resolve("extension/chromium/manifest.json")));
            fx(() -> {
                var scene = ref[0].getScene(); var id = (TextField)scene.lookup("#browser-connect-id"); var connect = (Button)scene.lookup("#browser-connect");
                id.setText("invalid"); assertTrue(connect.isDisabled());
                ((ComboBox<com.grabx.app.grabx.setup.BrowserSetupService.Browser>)scene.lookup("#browser-connect-browser"))
                        .setValue(com.grabx.app.grabx.setup.BrowserSetupService.Browser.BRAVE);
                id.setText("a".repeat(32)); assertFalse(connect.isDisabled()); connect.fire();
            });
            assertTrue(connected.await(5,TimeUnit.SECONDS)); assertEquals("BRAVE:" + "a".repeat(32),selected.get());
        } finally { fx(() -> ref[0].close()); }
    }
    @Test void setupRendersBothDirectionsAndKeepsDraftsUnsaved() throws Exception {
        for (String locale : new String[]{"en", "ar"}) {
            var store = new SoundFeedbackServiceTest.MemoryPreferences(); store.put("grabx.language", locale);
            var ref = new Stage[1];
            fx(() -> {
                Stage stage = new Stage(); ref[0] = stage;
                var wizard = new SetupWizard(stage, store, () -> fail("Preview must not commit")); wizard.show();
                assertFalse(stage.getIcons().isEmpty(), "Setup must carry its branded icon even for Java launches");
                stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
                snapshot(stage, "welcome-" + locale);
                var combo = (ComboBox<?>) stage.getScene().lookup("#setup-language");
                combo.show();
                var popup = Window.getWindows().stream().filter(w -> w instanceof PopupWindow && w.isShowing()).findFirst().orElseThrow();
                popup.getScene().getRoot().applyCss(); popup.getScene().getRoot().layout();
                snapshotRoot(popup.getScene().getRoot(), "language-popup-" + locale);
                combo.hide();
                ((Button) stage.getScene().lookup("#setup-next")).fire();
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
            var done = new boolean[1];
            do {
                fx(() -> done[0] = !((Button) ref[0].getScene().lookup("#setup-next")).isDisabled());
                if (!done[0]) Thread.sleep(100);
            } while (!done[0] && System.nanoTime() < deadline);
            assertTrue(done[0], "Local dependency scan timed out");
            fx(() -> {
                var stage = ref[0]; Button next = (Button) stage.getScene().lookup("#setup-next");
                next.fire(); stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
                TextField folder = (TextField) stage.getScene().lookup("#setup-folder");
                assertNotNull(folder); snapshot(stage, "defaults-" + locale);
                folder.setText(dir.resolve("missing").toString()); next.fire();
                assertSame(folder, stage.getScene().lookup("#setup-folder"), "Invalid folder must keep the user on Defaults");
                var inline = (Label) stage.getScene().lookup("#setup-folder-error");
                assertTrue(inline.isVisible()); assertFalse(inline.getText().isBlank());
                stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout(); snapshot(stage, "folder-error-" + locale);
                assertFalse(store.getBoolean("grabx.setup.complete.v1", false));
                folder.setText(dir.toString()); assertFalse(inline.isVisible());
                Button plus = (Button) stage.getScene().lookup("#setup-concurrency-plus");
                Button minus = (Button) stage.getScene().lookup("#setup-concurrency-minus");
                Label value = (Label) stage.getScene().lookup("#setup-concurrency-value");
                for (int i = 0; i < 20; i++) plus.fire();
                assertEquals("16", value.getText()); assertTrue(plus.isDisabled());
                for (int i = 0; i < 20; i++) minus.fire();
                assertEquals("1", value.getText()); assertTrue(minus.isDisabled());
                next.fire(); stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout(); snapshot(stage, "browser-" + locale);
                assertTrue(next.localToScene(next.getBoundsInLocal()).getMaxY() < stage.getScene().getHeight());
                assertFalse(store.getBoolean("grabx.setup.complete.v1", false)); stage.close();
            });
        }
    }
    static void snapshot(Stage stage, String name) {
        snapshotRoot(stage.getScene().getRoot(), name);
    }
    static void snapshotRoot(javafx.scene.Parent root, String name) {
        var image = root.snapshot(null, null);
        var buffer = new java.awt.image.BufferedImage((int)image.getWidth(), (int)image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.getPixelReader().getPixels(0, 0, buffer.getWidth(), buffer.getHeight(), javafx.scene.image.PixelFormat.getIntArgbInstance(),
                ((java.awt.image.DataBufferInt)buffer.getRaster().getDataBuffer()).getData(), 0, buffer.getWidth());
        try { javax.imageio.ImageIO.write(buffer, "png", Path.of(System.getProperty("java.io.tmpdir"), "grabx-setup-" + name + ".png").toFile()); }
        catch (java.io.IOException e) { throw new RuntimeException(e); }
    }
}
