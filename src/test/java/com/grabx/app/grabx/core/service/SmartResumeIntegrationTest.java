package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Actual runner + FX adapter + socket server. No real history, external network or user downloads. */
@Timeout(60)
class SmartResumeIntegrationTest {
    @TempDir Path dir;
    final Map<DownloadRow, Process> processes = new ConcurrentHashMap<>();
    final Map<DownloadRow, String> reasons = new ConcurrentHashMap<>();
    DownloadEngineFactory.Runtime runtime;
    static boolean started;

    @BeforeAll static void toolkit() throws Exception {
        assumeTrue("true".equals(System.getenv("GRABX_TEST_RESUME")));
        CountDownLatch latch = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); latch.countDown(); });
        assertTrue(latch.await(10, TimeUnit.SECONDS)); started = true;
    }
    @AfterAll static void stop() { if (started) Platform.exit(); }
    @BeforeEach void setup() {
        runtime = DownloadEngineFactory.create(FXCollections.observableArrayList(), processes, reasons,
                new DownloadProgressTracker(), (r, resume) -> runtime.runner().start(r, resume), () -> {}, () -> {}, () -> {},
                new DownloadEngineFactory.Config("Audio", "Best", "---", "Best", "mp3"));
    }
    @AfterEach void cleanup() throws Exception {
        for (var entry : List.copyOf(processes.entrySet())) {
            reasons.put(entry.getKey(), "PAUSE"); entry.getValue().destroyForcibly();
            assertTrue(entry.getValue().waitFor(20, TimeUnit.SECONDS));
        }
    }
    @Test void sharedSchedulerQueuesPausesResumesAndCancelsWithoutStartingExtraTransfers() throws Exception {
        try (Fixture server = new Fixture(1024 * 1024)) {
            server.slow = true;
            var items = FXCollections.<DownloadRow>observableArrayList();
            var queueRef = new java.util.concurrent.atomic.AtomicReference<DownloadScheduler>();
            runtime = DownloadEngineFactory.create(items, processes, reasons, new DownloadProgressTracker(),
                    (r, resume) -> queueRef.get().request(r, resume), () -> {}, () -> {}, () -> {},
                    new DownloadEngineFactory.Config("Audio", "Best", "---", "Best", "mp3"));
            var scheduler = new DownloadScheduler(items, runtime.runner()::start,
                    runtime.runner()::canStart, runtime.runner()::occupiesSlot, Platform::runLater);
            queueRef.set(scheduler); runtime.runner().onWorkerReleased(scheduler::wake);
            runtime.stateCoordinator().onWithdrawPending(scheduler::withdraw);
            DownloadRow first = row(server), second = row(server), cancelled = row(server);
            first.title.set("first.bin"); second.title.set("second.bin"); cancelled.title.set("cancelled.bin");
            try {
                fx(() -> {
                    scheduler.setLimit(1); items.addAll(first, second, cancelled);
                    scheduler.request(first, false); scheduler.request(second, false); scheduler.request(cancelled, false);
                });
                await(() -> diskBytes(first) >= 64 * 1024, 5);
                fx(() -> {
                    assertEquals(DownloadRow.State.QUEUED, second.getState()); assertNull(second.outputFile.get());
                    runtime.stateCoordinator().pause(first);
                    runtime.stateCoordinator().cancel(cancelled);
                });
                await(() -> diskBytes(second) >= 64 * 1024, 5);
                fx(() -> runtime.stateCoordinator().resume(first));
                fx(() -> assertEquals(DownloadRow.State.QUEUED, first.getState()));
                fx(() -> assertEquals(2, runtime.stateCoordinator().pauseAll()));
                Thread.sleep(100);
                fx(() -> {
                    assertEquals(DownloadRow.State.PAUSED, first.getState());
                    assertEquals(DownloadRow.State.PAUSED, second.getState());
                    assertEquals(DownloadRow.State.CANCELLED, cancelled.getState());
                    assertNull(cancelled.outputFile.get());
                    assertEquals(2, runtime.stateCoordinator().resumeAll());
                });
                await(() -> first.getState() == DownloadRow.State.COMPLETED && second.getState() == DownloadRow.State.COMPLETED, 15);
                assertArrayEquals(server.bytes, Files.readAllBytes(first.outputFile.get()));
                assertArrayEquals(server.bytes, Files.readAllBytes(second.outputFile.get()));
                assertNull(cancelled.outputFile.get());
                assertEquals(2, server.offsets.size(), "Pausing/resuming should keep both live direct responses");
            } finally { fx(scheduler::close); }
        }
    }
    @Test void startupResumesOnlyInterruptedTasksThroughTheSharedLimitAfterReconciliation() throws Exception {
        Path history = dir.resolve("startup.tsv");
        var rows = FXCollections.<DownloadRow>observableArrayList();
        for (String title : List.of("running", "waiting", "manual", "failed", "cancelled", "finished", "bad-state"))
            rows.add(new DownloadRow("https://example.test/" + title, title, rows.size(), dir.toString(), "Video", "Best"));
        var saver = history(rows, history, null);
        fx(() -> {
            rows.get(0).setState(DownloadRow.State.DOWNLOADING);
            rows.get(2).setState(DownloadRow.State.PAUSED);
            rows.get(3).setState(DownloadRow.State.FAILED);
            rows.get(4).setState(DownloadRow.State.CANCELLED);
            saver.prepareForShutdown();
            var coordinator = new DownloadStateCoordinator(rows, Map.of(), new HashMap<>(), (r, b) -> {}, () -> {});
            assertEquals(4, coordinator.pauseAll());
            saver.prepareForShutdown(); // repeated close must not forget the earlier active states
            saver.saveNow(); saver.saveNow(); // late snapshots keep the interruption marker
        });
        var restored = FXCollections.<DownloadRow>observableArrayList();
        var started = new ArrayList<DownloadRow>();
        var scheduler = new DownloadScheduler(restored, (r, resume) -> {
            assertTrue(resume); started.add(r); r.setState(DownloadRow.State.DOWNLOADING);
        }, (r, b) -> true, r -> false, Platform::runLater);
        var coordinator = new DownloadStateCoordinator(restored, Map.of(), new HashMap<>(), scheduler::request, () -> {});
        var loader = history(restored, history, () -> Platform.runLater(() -> {
            restored.get(5).setState(DownloadRow.State.COMPLETED);
            restored.get(6).setState(DownloadRow.State.FAILED);
        }));
        loader.configureStartupResume(() -> true, coordinator::resume);
        try {
            fx(() -> { scheduler.setLimit(1); loader.loadOnce(); loader.loadOnce(); });
            flushHistoryLoad();
            fx(() -> {
                assertEquals(List.of(restored.get(0)), started);
                assertEquals(DownloadRow.State.QUEUED, restored.get(1).getState());
                assertEquals(DownloadRow.State.PAUSED, restored.get(2).getState());
                assertEquals(DownloadRow.State.FAILED, restored.get(3).getState());
                assertEquals(DownloadRow.State.CANCELLED, restored.get(4).getState());
                assertEquals(DownloadRow.State.COMPLETED, restored.get(5).getState());
                assertEquals(DownloadRow.State.FAILED, restored.get(6).getState());
                restored.get(0).setState(DownloadRow.State.COMPLETED);
            });
            flushHistoryLoad();
            fx(() -> assertEquals(List.of(restored.get(0), restored.get(1)), started));
        } finally { fx(scheduler::close); }
    }
    @Test void disabledStartupConsumesOldIntentAndPauseAllStaysManual() throws Exception {
        for (boolean explicitPause : List.of(false, true)) {
            Path file = dir.resolve("disabled-" + explicitPause + ".tsv");
            var row = new DownloadRow("https://example.test/a", "a", 0, dir.toString(), "Video", "Best");
            var rows = FXCollections.observableArrayList(row);
            var saver = history(rows, file, null);
            fx(() -> {
                if (explicitPause) new DownloadStateCoordinator(rows, Map.of(), new HashMap<>(), (r,b) -> {}, () -> {}).pauseAll();
                saver.prepareForShutdown(); row.setState(DownloadRow.State.PAUSED); saver.saveNow();
            });
            for (boolean enabled : List.of(false, true)) {
                var restored = FXCollections.<DownloadRow>observableArrayList();
                var loader = history(restored, file, null);
                var calls = new AtomicInteger();
                loader.configureStartupResume(() -> enabled, r -> calls.incrementAndGet());
                fx(loader::loadOnce); flushHistoryLoad();
                fx(() -> { assertEquals(0, calls.get()); assertEquals(DownloadRow.State.PAUSED, restored.getFirst().getState()); });
            }
        }
    }
    @Test void legacyCrashStatesResumeButLegacyPausedAndFailuresDoNot() throws Exception {
        Path file = dir.resolve("legacy-startup.tsv");
        var lines = new ArrayList<String>();
        for (String state : List.of("DOWNLOADING", "PENDING", "QUEUED", "PAUSED", "FAILED", "CANCELLED", "MISSING", "INVALID"))
            lines.add("https://example.test/" + state + "\t" + state + "\t" + dir + "\tVideo\tBest\t" + state + "\t\t" + System.currentTimeMillis());
        // Legacy rejected Direct rows must migrate to Failed before automatic admission.
        lines.add("https://example.test/expired\texpired\t" + dir + "\tDirect\tAuto\tPAUSED\t\t" + System.currentTimeMillis()
                + "\t\t\t-1\t\tDownload link/session rejected (HTTP 498); saved data kept\ttrue");
        Files.write(file, lines);
        var restored = FXCollections.<DownloadRow>observableArrayList();
        var requested = new ArrayList<String>();
        var loader = history(restored, file, null);
        loader.configureStartupResume(() -> true, row -> requested.add(row.title.get()));
        fx(loader::loadOnce); flushHistoryLoad();
        fx(() -> assertEquals(List.of("DOWNLOADING", "PENDING", "QUEUED"), requested));
    }
    @Test void closingDuringHistoryRestoreCannotStartTransfers() throws Exception {
        Path file = dir.resolve("closing-startup.tsv");
        var rows = FXCollections.observableArrayList(new DownloadRow("https://example.test/a", "a", 0, dir.toString(), "Video", "Best"));
        fx(history(rows, file, null)::saveNow);
        var restored = FXCollections.<DownloadRow>observableArrayList();
        var loader = history(restored, file, null);
        var calls = new AtomicInteger();
        loader.configureStartupResume(() -> true, row -> calls.incrementAndGet());
        fx(() -> { loader.loadOnce(); loader.prepareForShutdown(); }); flushHistoryLoad();
        assertEquals(0, calls.get());
    }
    private HistoryService history(javafx.collections.ObservableList<DownloadRow> rows, Path file, Runnable reconcile) {
        return new HistoryService(rows, new java.util.concurrent.atomic.AtomicLong(), null, () -> 30,
                reconcile, null, null, null, file);
    }
    private void flushHistoryLoad() throws Exception { fx(() -> {}); fx(() -> {}); fx(() -> {}); }
    @Test void queuedHistoryRestoresPausedForManualResume() throws Exception {
        var row = new DownloadRow("https://example.test/pending", "queued", 0, dir.toString(), "Video", "Best");
        Path history = dir.resolve("queued-history.tsv");
        var items = FXCollections.observableArrayList(row);
        var saver = new HistoryService(items, new java.util.concurrent.atomic.AtomicLong(), null, () -> 30,
                null, null, null, null, history);
        fx(saver::saveNow);
        var restored = FXCollections.<DownloadRow>observableArrayList();
        var loader = new HistoryService(restored, new java.util.concurrent.atomic.AtomicLong(), null, () -> 30,
                null, null, null, null, history);
        loader.loadOnce(); await(() -> restored.size() == 1, 5);
        fx(() -> { assertEquals(DownloadRow.State.PAUSED, restored.getFirst().getState()); assertEquals("Paused", restored.getFirst().status.get()); });
    }
    @Test void repeatedDisconnectsAt95To99PercentRecoverWithOnlyRemainingBytes() throws Exception {
        try (Fixture server = new Fixture(1024 * 1024)) {
            server.cuts.set(3);
            DownloadRow row = row(server);
            start(row);
            await(() -> row.getState() == DownloadRow.State.COMPLETED, 25);
            assertArrayEquals(server.bytes, Files.readAllBytes(row.outputFile.get()));
            assertEquals(4, server.offsets.size());
            assertEquals(0, server.offsets.get(0));
            assertEquals(server.bytes.length * 95 / 100, server.offsets.get(1));
            assertTrue(server.offsets.get(2) > server.offsets.get(1));
            assertTrue(server.offsets.get(3) > server.offsets.get(2));
            assertTrue(server.conditions.stream().allMatch("\"v1\""::equals));
        }
    }
    @Test void pauseAndResumeKeepTheSamePartAndProgress() throws Exception {
        try (Fixture server = new Fixture(1024 * 1024)) {
            server.slow = true;
            DownloadRow row = row(server); start(row);
            await(() -> diskBytes(row) >= 64 * 1024, 5);
            fx(() -> runtime.stateCoordinator().pause(row));
            Thread.sleep(150);
            long saved = diskBytes(row);
            Thread.sleep(200); assertEquals(saved, diskBytes(row));
            fx(() -> runtime.stateCoordinator().resume(row));
            await(() -> row.getState() == DownloadRow.State.COMPLETED, 10);
            assertArrayEquals(server.bytes, Files.readAllBytes(row.outputFile.get()));
        }
    }
    @Test void restartedTaskLoadsMetadataAndResumesAtActualDiskByte() throws Exception {
        try (Fixture server = new Fixture(1024 * 1024)) {
            server.slow = true;
            DownloadRow old = row(server); start(old);
            await(() -> diskBytes(old) >= 128 * 1024, 5);
            Process process = processes.get(old);
            reasons.put(old, "PAUSE"); process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            long saved = diskBytes(old); assertTrue(saved > 0);
            server.slow = false;
            DownloadRow restored = row(server); restored.outputFile.set(old.outputFile.get());
            new DownloadHistoryReconciler(List.of(restored), Runnable::run, null, null).reconcileRow(restored);
            assertEquals(saved, restored.downloadedBytes.get());
            start(restored);
            await(() -> restored.getState() == DownloadRow.State.COMPLETED, 10);
            assertTrue(server.offsets.contains((int) saved));
            assertArrayEquals(server.bytes, Files.readAllBytes(restored.outputFile.get()));
        }
    }
    @Test void nonRangeServerDownloadsFreshButNeverAppendsToSavedData() throws Exception {
        try (Fixture server = new Fixture(256 * 1024)) {
            server.noRanges = true;
            DownloadRow row = row(server); start(row);
            await(() -> row.getState() == DownloadRow.State.COMPLETED, 5);
            assertEquals("No", row.resumeSupport);
            assertArrayEquals(server.bytes, Files.readAllBytes(row.outputFile.get()));
            DownloadRow saved = seeded(server, 10000, "saved.bin");
            byte[] before = Files.readAllBytes(DirectPartialFiles.part(saved.outputFile.get(), -1));
            start(saved); await(() -> saved.getState() == DownloadRow.State.FAILED, 5);
            assertTrue(saved.status.get().contains("does not support resume"));
            assertArrayEquals(before, Files.readAllBytes(DirectPartialFiles.part(saved.outputFile.get(), -1)));
        }
    }
    @Test void serverIgnoringRangeAfterProbeCannotAppend200() throws Exception {
        try (Fixture server = new Fixture(256 * 1024)) {
            server.ignoreDataRange = true;
            DownloadRow row = seeded(server, 10000, "file.bin"); start(row);
            await(() -> row.getState() == DownloadRow.State.FAILED, 5);
            assertEquals(10000, diskBytes(row)); assertFalse(Files.exists(row.outputFile.get()));
        }
    }
    @Test void expiredLinkAndChangedIdentityPreservePartialFiles() throws Exception {
        try (Fixture server = new Fixture(256 * 1024)) {
            for (int status : new int[]{401, 403, 404, 410, 412, 416, 498}) {
                server.status = status;
                DownloadRow row = seeded(server, 90000, "file" + status); start(row);
                await(() -> row.getState() == DownloadRow.State.FAILED, 5);
                assertEquals(90000, diskBytes(row)); assertFalse(Files.exists(row.outputFile.get()));
            }
            server.status = 0; server.etag = "\"v2\"";
            DownloadRow row = seeded(server, 90000, "changed"); start(row);
            await(() -> row.getState() == DownloadRow.State.FAILED, 5);
            assertEquals(90000, diskBytes(row));
        }
    }
    @Test void verifiedCompletePartFinalizesWithoutUnsatisfiableRangeAndEmpty416Works() throws Exception {
        try (Fixture server = new Fixture(64000)) {
            DownloadRow row = seeded(server, server.bytes.length, "complete"); start(row);
            await(() -> row.getState() == DownloadRow.State.COMPLETED, 5);
            assertTrue(server.offsets.isEmpty()); assertArrayEquals(server.bytes, Files.readAllBytes(row.outputFile.get()));
        }
        try (Fixture empty = new Fixture(0)) {
            DownloadRow row = row(empty); start(row);
            await(() -> row.getState() == DownloadRow.State.COMPLETED, 5);
            assertEquals(0, Files.size(row.outputFile.get()));
        }
    }
    @Test void retryBackoffCanBePausedAndCancelledWithoutAnOrphanRestart() throws Exception {
        try (Fixture server = new Fixture(128000)) {
            server.cuts.set(1);
            DownloadRow row = row(server); start(row);
            await(() -> row.status.get().startsWith("Waiting for network"), 5);
            fx(() -> runtime.stateCoordinator().pause(row));
            int requests = server.requests.get();
            Thread.sleep(2300); assertEquals(requests, server.requests.get());
            long saved = diskBytes(row);
            fx(() -> runtime.stateCoordinator().cancel(row));
            await(() -> !processes.containsKey(row), 5);
            Thread.sleep(500); assertEquals(requests, server.requests.get());
            assertEquals(saved, diskBytes(row));
        }
    }
    @Test void languageDraftSupportsSaveDiscardAndDoesNotChangeTransferSettings() throws Exception {
        fx(() -> {
            try (var sound = new SoundFeedbackService(new SoundFeedbackServiceTest.MemoryPreferences())) {
                var applied = new java.util.concurrent.atomic.AtomicReference<com.grabx.app.grabx.ui.components.SettingsPage.Values>();
                var initial = new com.grabx.app.grabx.ui.components.SettingsPage.Values(dir.toString(), true, 0, 0, false, .3, 30);
                var page = new com.grabx.app.grabx.ui.components.SettingsPage(initial, sound, applied::set);
                new javafx.scene.Scene(page,1000,750); page.applyCss(); page.layout();
                ((javafx.scene.control.ToggleButton)page.lookup("#settings-nav-language")).fire(); page.applyCss();page.layout();
                @SuppressWarnings("unchecked")
                var choice = (javafx.scene.control.ComboBox<LanguagePreferences.Language>)page.lookup("#settings-language");
                assertEquals(6,choice.getItems().size()); assertFalse(page.isDirty());
                choice.setValue(LanguagePreferences.LANGUAGES.get(1)); assertTrue(page.isDirty()); assertNull(applied.get());
                ((javafx.scene.control.Button)page.lookup("#settings-discard")).fire();
                assertEquals("en", choice.getValue().code()); assertFalse(page.isDirty());
                choice.setValue(LanguagePreferences.LANGUAGES.get(1)); assertTrue(page.saveChanges());
                assertEquals("ar",applied.get().language()); assertEquals(0,applied.get().directConnections());
                assertFalse(page.isDirty());
            }
        });
    }
    @Test void arabicSettingsMirrorLayoutButPreserveUserEnteredPathsAndRenderWithoutOverflow() throws Exception {
        fx(() -> {
            com.grabx.app.grabx.util.I18n.install("ar");
            try (var sound = new SoundFeedbackService(new SoundFeedbackServiceTest.MemoryPreferences())) {
                var page = new com.grabx.app.grabx.ui.components.SettingsPage(
                        new com.grabx.app.grabx.ui.components.SettingsPage.Values(dir.toString(),true,0,0,false,.3,30,3,false,NotificationPreferences.Values.defaults(),0,"ar"),sound,v->{});
                var wrapper = new javafx.scene.layout.StackPane(page);wrapper.setStyle("-fx-background-color:#0b1422;");
                var stage = new javafx.stage.Stage(); stage.setScene(new javafx.scene.Scene(wrapper,1000,750));
                for(String css:java.util.List.of("theme-base","layout","buttons"))wrapper.getStylesheets().add(getClass().getResource("/com/grabx/app/grabx/styles/"+css+".css").toExternalForm());
                com.grabx.app.grabx.util.I18n.localize(stage); wrapper.resize(1000,750);wrapper.applyCss();wrapper.layout();
                assertEquals(javafx.geometry.NodeOrientation.RIGHT_TO_LEFT,page.getEffectiveNodeOrientation());
                var folder=(javafx.scene.control.TextField)page.lookup("#settings-folder");
                assertEquals(dir.toString(),folder.getText());
                assertEquals(javafx.geometry.NodeOrientation.LEFT_TO_RIGHT,folder.getNodeOrientation());
                ((javafx.scene.control.ToggleButton)page.lookup("#settings-nav-language")).fire();page.applyCss();page.layout();
                var save=(javafx.scene.control.Button)page.lookup("#settings-save");
                assertEquals("حفظ التغييرات",save.getText());assertFalse(page.isDirty());
                var image=wrapper.snapshot(null,null);
                var output=new java.awt.image.BufferedImage((int)image.getWidth(),(int)image.getHeight(),java.awt.image.BufferedImage.TYPE_INT_ARGB);
                image.getPixelReader().getPixels(0,0,output.getWidth(),output.getHeight(),javafx.scene.image.PixelFormat.getIntArgbInstance(),((java.awt.image.DataBufferInt)output.getRaster().getDataBuffer()).getData(),0,output.getWidth());
                try {javax.imageio.ImageIO.write(output,"png",new java.io.File("/private/tmp/grabx-language-ar.png"));}catch(java.io.IOException error){throw new RuntimeException(error);}
                assertTrue(save.localToScene(save.getBoundsInLocal()).getMaxY()<=750);
                stage.close();
            } finally {com.grabx.app.grabx.util.I18n.install("en");}
        });
    }
    @Test void supportedLanguagesFitMinimumSettingsAndKeepModelValuesStable() throws Exception {
        fx(() -> {
            try (var sound = new SoundFeedbackService(new SoundFeedbackServiceTest.MemoryPreferences())) {
                for (var language : LanguagePreferences.LANGUAGES) {
                    com.grabx.app.grabx.util.I18n.install(language.code());
                    var initial = new com.grabx.app.grabx.ui.components.SettingsPage.Values(dir.toString(),true,0,0,false,.3,30,3,false,NotificationPreferences.Values.defaults(),0,language.code());
                    var page = new com.grabx.app.grabx.ui.components.SettingsPage(initial,sound,v->{});
                    var wrapper = new javafx.scene.layout.StackPane(page); wrapper.setStyle("-fx-background-color:#0b1422;");
                    var scene = new javafx.scene.Scene(wrapper,900,500);
                    com.grabx.app.grabx.util.I18n.localize(wrapper);wrapper.resize(900,500);wrapper.applyCss();wrapper.layout();
                    ((javafx.scene.control.ToggleButton)page.lookup("#settings-nav-language")).fire();page.applyCss();page.layout();
                    var save=(javafx.scene.control.Button)page.lookup("#settings-save");
                    assertEquals(com.grabx.app.grabx.util.I18n.text("Save changes",language.code()),save.getText());
                    var bounds=save.localToScene(save.getBoundsInLocal());
                    assertTrue(bounds.getMinX()>=0&&bounds.getMaxX()<=900&&bounds.getMaxY()<=500,language.code()+bounds);
                    assertFalse(page.isDirty());
                    var model = new javafx.beans.property.SimpleStringProperty("Downloading");
                    var status = new javafx.scene.control.Label();status.textProperty().bind(model.map(com.grabx.app.grabx.util.I18n::text));
                    wrapper.getChildren().add(status);
                    assertEquals("Downloading",model.get());
                    assertEquals(com.grabx.app.grabx.util.I18n.text("Downloading",language.code()),status.getText());
                    if(language.code().equals("ar")) {
                        var image=wrapper.snapshot(null,null);
                        var output=new java.awt.image.BufferedImage((int)image.getWidth(),(int)image.getHeight(),java.awt.image.BufferedImage.TYPE_INT_ARGB);
                        image.getPixelReader().getPixels(0,0,output.getWidth(),output.getHeight(),javafx.scene.image.PixelFormat.getIntArgbInstance(),((java.awt.image.DataBufferInt)output.getRaster().getDataBuffer()).getData(),0,output.getWidth());
                        try{javax.imageio.ImageIO.write(output,"png",new java.io.File("/private/tmp/grabx-language-ar-min.png"));}catch(java.io.IOException e){throw new RuntimeException(e);}
                    }
                }
            } finally {com.grabx.app.grabx.util.I18n.install("en");}
        });
    }
    @Test void inboxDismissButtonRemovesOneNotificationOnly() throws Exception {
        fx(() -> {
            var stage = new javafx.stage.Stage();
            var center = new NotificationCenter(NotificationPreferences.Values.defaults());
            center.publish(NotificationCenter.Kind.FAILED, "Failed", "first");
            center.publish(NotificationCenter.Kind.COMPLETED, "Done", "second");
            try (var view = new com.grabx.app.grabx.ui.components.NotificationView(center, () -> stage)) {
                var button = view.createButton(false);
                stage.setScene(new javafx.scene.Scene(new javafx.scene.layout.StackPane(button), 600, 500));
                stage.show(); button.fire();
                var popup = javafx.stage.Window.getWindows().stream()
                        .filter(w -> w instanceof javafx.stage.PopupWindow p && p.getOwnerWindow() == stage).findFirst().orElseThrow();
                var root = popup.getScene().getRoot(); root.applyCss(); root.layout();
                assertEquals(2, root.lookupAll("#notification-dismiss").size());
                ((javafx.scene.control.Button)root.lookup("#notification-dismiss")).fire();
                assertEquals(1, center.entries().size());
                assertEquals(1, root.lookupAll("#notification-dismiss").size());
                assertTrue(popup.isShowing());
            } finally { stage.close(); center.close(); }
        });
    }
    @Test void languageDirectionFollowsReplacementSceneRootWithoutRestart() throws Exception {
        fx(() -> {
            var stage = new javafx.stage.Stage();
            com.grabx.app.grabx.util.I18n.install("en");
            var first = new javafx.scene.control.Label("Downloads");
            var second = new javafx.scene.control.Label("Settings");
            var content = new javafx.scene.layout.HBox(first, second);
            var scene = new javafx.scene.Scene(content, 700, 400); stage.setScene(scene);
            com.grabx.app.grabx.util.I18n.localize(stage);
            try {
                // HoverTooltipService wraps the main root after the window has been localized.
                var wrapper = new javafx.scene.layout.StackPane(); scene.setRoot(wrapper);
                wrapper.getChildren().add(content);
                com.grabx.app.grabx.util.I18n.install("ar");
                assertEquals(javafx.geometry.NodeOrientation.RIGHT_TO_LEFT, wrapper.getEffectiveNodeOrientation());
                assertEquals(javafx.geometry.NodeOrientation.RIGHT_TO_LEFT, content.getEffectiveNodeOrientation());
                wrapper.resize(700, 400); wrapper.applyCss(); wrapper.layout();
                assertTrue(first.localToScene(first.getBoundsInLocal()).getMinX() > second.localToScene(second.getBoundsInLocal()).getMinX());
                com.grabx.app.grabx.util.I18n.install("en");
                assertEquals(javafx.geometry.NodeOrientation.LEFT_TO_RIGHT, content.getEffectiveNodeOrientation());
                wrapper.applyCss(); wrapper.layout();
                assertTrue(first.localToScene(first.getBoundsInLocal()).getMinX() < second.localToScene(second.getBoundsInLocal()).getMinX());
            } finally { stage.close(); com.grabx.app.grabx.util.I18n.install("en"); }
        });
    }
    @Test void savingLanguageUpdatesSameWindowAndBoundStatusesAndCanSwitchBackWithoutRestart() throws Exception {
        fx(() -> {
            com.grabx.app.grabx.util.I18n.install("en");
            try (var sound = new SoundFeedbackService(new SoundFeedbackServiceTest.MemoryPreferences())) {
                var store = new SoundFeedbackServiceTest.MemoryPreferences();
                var preferences = new LanguagePreferences(store);
                var initial = new com.grabx.app.grabx.ui.components.SettingsPage.Values(dir.toString(),true,0,0,false,.3,30);
                var page = new com.grabx.app.grabx.ui.components.SettingsPage(initial,sound,v->preferences.save(v.language()));
                var wrapper = new javafx.scene.layout.VBox(page);
                var statusSource = new javafx.beans.property.SimpleStringProperty("Downloading");
                var status = new javafx.scene.control.Label();status.textProperty().bind(com.grabx.app.grabx.util.I18n.binding(statusSource));
                var field=new javafx.scene.control.TextField("https://example.com/Video.mp4?token=a%2Fb");
                var detached=new javafx.scene.control.Label("Downloads"); wrapper.getChildren().addAll(status,field,detached);
                var scene=new javafx.scene.Scene(wrapper,1000,850);
                com.grabx.app.grabx.util.I18n.localize(wrapper);wrapper.resize(1000,850);wrapper.applyCss();wrapper.layout();
                wrapper.getChildren().remove(detached);
                ((javafx.scene.control.ToggleButton)page.lookup("#settings-nav-language")).fire();page.applyCss();page.layout();
                @SuppressWarnings("unchecked")
                var choice=(javafx.scene.control.ComboBox<LanguagePreferences.Language>)page.lookup("#settings-language");
                var save=(javafx.scene.control.Button)page.lookup("#settings-save");
                assertEquals("Save changes",save.getText());
                choice.setValue(LanguagePreferences.LANGUAGES.get(1));assertEquals("Save changes",save.getText());
                save.fire();
                assertEquals("ar",preferences.code());assertEquals("حفظ التغييرات",save.getText());
                assertEquals("جارٍ التحميل",status.getText());assertEquals("Downloading",statusSource.get());
                assertEquals("https://example.com/Video.mp4?token=a%2Fb",field.getText());
                assertEquals(javafx.geometry.NodeOrientation.RIGHT_TO_LEFT,wrapper.getNodeOrientation());
                wrapper.getChildren().add(detached);assertEquals("التحميلات",detached.getText());
                choice.setValue(LanguagePreferences.LANGUAGES.get(0));save.fire();
                assertEquals("en",preferences.code());assertEquals("Save changes",save.getText());
                assertEquals("Downloading",statusSource.get(), "source must remain original");
                assertEquals("Downloading",com.grabx.app.grabx.util.I18n.text(statusSource.get()), "active locale");
                assertTrue(status.textProperty().isBound(), "binding preserved");
                assertEquals("Downloading",status.getText());assertEquals("Downloads",detached.getText());
                assertEquals(javafx.geometry.NodeOrientation.LEFT_TO_RIGHT,detached.getEffectiveNodeOrientation());
                assertEquals(javafx.geometry.NodeOrientation.LEFT_TO_RIGHT,wrapper.getNodeOrientation());
                assertFalse(page.isDirty());
            } finally {com.grabx.app.grabx.util.I18n.install("en");}
        });
    }
    @Test void connectionSettingsParticipateInDraftSaveAndDiscard() throws Exception {
        fx(() -> {
            try (var sound = new SoundFeedbackService(new SoundFeedbackServiceTest.MemoryPreferences())) {
                var applied = new java.util.concurrent.atomic.AtomicReference<com.grabx.app.grabx.ui.components.SettingsPage.Values>();
                var initial = new com.grabx.app.grabx.ui.components.SettingsPage.Values(
                        dir.toString(), true, 0, 0, false, .3, 30);
                var page = new com.grabx.app.grabx.ui.components.SettingsPage(initial, sound, applied::set);
                new javafx.scene.Scene(page, 1000, 750);
                page.applyCss(); page.layout();
                ((javafx.scene.control.ToggleButton) page.lookup("#settings-nav-network")).fire();
                page.applyCss(); page.layout();
                @SuppressWarnings("unchecked")
                var choice = (javafx.scene.control.ComboBox<Integer>) page.lookup("#settings-direct-connections");
                assertEquals(65, choice.getItems().size());
                assertEquals(0, choice.getValue());
                assertFalse(page.isDirty());
                choice.setValue(64); assertTrue(page.isDirty()); assertNull(applied.get());
                ((javafx.scene.control.Button) page.lookup("#settings-discard")).fire();
                assertEquals(0, choice.getValue()); assertFalse(page.isDirty());
                choice.setValue(64); assertTrue(page.saveChanges());
                assertEquals(64, applied.get().directConnections()); assertFalse(page.isDirty());
                choice.setValue(0); assertTrue(page.saveChanges());
                assertEquals(0, applied.get().directConnections()); assertFalse(page.isDirty());
            }
        });
    }
    @Test void automaticModeRaisesWorkersOnAConnectionLimitedServerAndKeepsExactBytes() throws Exception {
        try (Fixture server = new Fixture(16 * 1024 * 1024)) {
            server.slow = true; server.chunkDelayMillis = 40;
            runtime.runner().configureDirectConnections(0);
            DownloadRow row = row(server); start(row);
            await(() -> row.status.get().contains("Auto") && row.status.get().contains("8 connections"), 15);
            await(() -> row.getState() == DownloadRow.State.COMPLETED, 30);
            assertEquals(64, row.resumeSegments);
            assertArrayEquals(server.bytes, Files.readAllBytes(row.outputFile.get()));
        }
    }
    @Test void sixtyFourSegmentsDownloadAndResumeWithALowerConnectionLimit() throws Exception {
        try (Fixture server = new Fixture(16 * 1024 * 1024)) {
            runtime.runner().configureDirectConnections(64);
            DownloadRow row = row(server); start(row);
            await(() -> row.getState() == DownloadRow.State.COMPLETED, 20);
            assertEquals(64, row.resumeSegments);
            assertArrayEquals(server.bytes, Files.readAllBytes(row.outputFile.get()));
            assertEquals(64, new HashSet<>(server.offsets).size());
            DownloadRow restored = seeded(server, 0, "resume64.bin");
            restored.resumeSegments = 64;
            DirectDownloadState.save(restored, restored.outputFile.get());
            int piece = server.bytes.length / 64;
            for (int i = 0; i < 64; i++)
                Files.write(DirectPartialFiles.part(restored.outputFile.get(), i),
                        Arrays.copyOfRange(server.bytes, i * piece, i * piece + piece / 2));
            assertEquals(server.bytes.length / 2, DirectDownloadState.downloaded(restored.outputFile.get()));
            runtime.runner().configureDirectConnections(1);
            start(restored); await(() -> restored.getState() == DownloadRow.State.COMPLETED, 20);
            assertEquals(64, restored.resumeSegments);
            assertArrayEquals(server.bytes, Files.readAllBytes(restored.outputFile.get()));
        }
    }
    @Test void segmentedDownloadAndInterruptedAssemblyKeepOriginalLayout() throws Exception {
        try (Fixture server = new Fixture(5 * 1024 * 1024)) {
            DownloadRow row = row(server); start(row);
            await(() -> row.getState() == DownloadRow.State.COMPLETED, 10);
            assertEquals(20, row.resumeSegments);
            assertArrayEquals(server.bytes, Files.readAllBytes(row.outputFile.get()));
            DownloadRow restored = seeded(server, 0, "segmented.bin");
            restored.resumeSegments = 2;
            DirectDownloadState.save(restored, restored.outputFile.get());
            int half = server.bytes.length / 2;
            Files.write(DirectPartialFiles.part(restored.outputFile.get(), 0), Arrays.copyOfRange(server.bytes, 0, half));
            Files.write(DirectPartialFiles.part(restored.outputFile.get(), 1), Arrays.copyOfRange(server.bytes, half, half + half * 99 / 100));
            Files.write(DirectPartialFiles.part(restored.outputFile.get(), -1), Arrays.copyOf(server.bytes, 1024));
            start(restored); await(() -> restored.getState() == DownloadRow.State.COMPLETED, 10);
            assertArrayEquals(server.bytes, Files.readAllBytes(restored.outputFile.get()));
        }
    }
    @Test void registeredResolverVerifiesAndResumesExpiredLink() throws Exception {
        try (Fixture expired = new Fixture(256000); Fixture fresh = new Fixture(256000)) {
            expired.status = 403;
            DownloadRow row = seeded(expired, 100000, "resolver.bin");
            runtime.runner().linkResolvers().register(new DownloadLinkResolvers.Resolver() {
                public boolean supports(String url) { return url.equals(expired.url()); }
                public DownloadLinkResolvers.Link resolve(DownloadLinkResolvers.Context context) { return new DownloadLinkResolvers.Link(fresh.url(), null); }
            });
            start(row); await(() -> row.getState() == DownloadRow.State.COMPLETED, 5);
            assertEquals(expired.url(), row.originalUrl); assertEquals(fresh.url(), row.url);
            assertArrayEquals(fresh.bytes, Files.readAllBytes(row.outputFile.get()));
        }
    }
    @Test void concurrentDownloadsWithTheSameNameCannotSharePartialFiles() throws Exception {
        try (Fixture first = new Fixture(256000); Fixture second = new Fixture(256000)) {
            first.slow = true; second.slow = true; second.bytes[100] ^= 1;
            DownloadRow a = row(first), b = row(second);
            start(a); start(b);
            await(() -> a.getState() == DownloadRow.State.COMPLETED && b.getState() == DownloadRow.State.COMPLETED, 10);
            assertNotEquals(a.outputFile.get(), b.outputFile.get());
            assertArrayEquals(first.bytes, Files.readAllBytes(a.outputFile.get()));
            assertArrayEquals(second.bytes, Files.readAllBytes(b.outputFile.get()));
        }
    }
    @Test void resolverCannotAdoptADifferentResource() throws Exception {
        try (Fixture expired = new Fixture(128000); Fixture wrong = new Fixture(128000)) {
            expired.status = 403; wrong.bytes[123] ^= 1;
            DownloadRow row = seeded(expired, 120000, "wrong-link");
            runtime.runner().linkResolvers().register(new DownloadLinkResolvers.Resolver() {
                public boolean supports(String url) { return url.equals(expired.url()); }
                public DownloadLinkResolvers.Link resolve(DownloadLinkResolvers.Context context) { return new DownloadLinkResolvers.Link(wrong.url(), null); }
            });
            start(row); await(() -> row.getState() == DownloadRow.State.FAILED, 5);
            assertEquals(expired.url(), row.url);
            assertArrayEquals(Arrays.copyOf(expired.bytes, 120000), Files.readAllBytes(DirectPartialFiles.part(row.outputFile.get(), -1)));
        }
    }
    @Test void browserUserAgentSurvivesInitialProbeTransferRetryAndPersistence() throws Exception {
        try (Fixture server = new Fixture(128000)) {
            server.cuts.set(1);
            DownloadRow row = row(server); row.requestUserAgent = "FixtureBrowser/145";
            start(row); await(() -> row.getState() == DownloadRow.State.COMPLETED, 8);
            assertTrue(server.agents.size() >= 4);
            assertTrue(server.agents.stream().allMatch("FixtureBrowser/145"::equals));
            DownloadRow loaded = row(server);
            assertTrue(DirectDownloadState.load(loaded, row.outputFile.get()));
            assertEquals("FixtureBrowser/145", loaded.requestUserAgent);
            assertArrayEquals(server.bytes, Files.readAllBytes(row.outputFile.get()));
        }
    }
    @Test void allRedirectCodesAreProbedBeforeResuming() throws Exception {
        try (Fixture server = new Fixture(64000)) {
            for (int code : new int[]{301, 302, 307, 308}) {
                server.redirect = code;
                DownloadRow row = seeded(server, 10000, "redirect" + code);
                row.url = server.url() + "?redirect"; DirectDownloadState.save(row, row.outputFile.get());
                start(row); await(() -> row.getState() == DownloadRow.State.COMPLETED, 5);
                assertArrayEquals(server.bytes, Files.readAllBytes(row.outputFile.get()));
            }
        }
    }
    @Test void socketReadTimeoutReconnectsInsteadOfRemainingAtZeroSpeed() throws Exception {
        try (Fixture server = new Fixture(128000)) {
            server.stalls.set(1);
            DownloadRow row = seeded(server, 120000, "stalled"); start(row);
            await(() -> row.getState() == DownloadRow.State.COMPLETED, 25);
            assertEquals(List.of(120000, 120000), server.offsets);
            assertArrayEquals(server.bytes, Files.readAllBytes(row.outputFile.get()));
        }
    }
    @Test void temporaryHttpErrorsRetryAndRateLimitRetainsAControllableWaitingTask() throws Exception {
        try (Fixture server = new Fixture(128000)) {
            for (int code : new int[]{408, 500, 503}) {
                server.temporaryStatus = code; server.temporary.set(1);
                DownloadRow row = seeded(server, 120000, "temporary" + code); start(row);
                await(() -> row.getState() == DownloadRow.State.COMPLETED, 5);
                assertArrayEquals(server.bytes, Files.readAllBytes(row.outputFile.get()));
            }
            server.status = 429;
            DownloadRow row = seeded(server, 120000, "rate-limit"); start(row);
            await(() -> row.status.get().contains("server 429"), 5);
            assertEquals(120000, diskBytes(row));
            assertEquals(DownloadRow.State.DOWNLOADING, row.getState());
            assertTrue(row.status.get().contains("30s"));
        }
    }
    @Test void actualHistoryRoundTripRestoresOldIncompleteTaskAndOnDiskProgress() throws Exception {
        try (Fixture server = new Fixture(128000)) {
            DownloadRow row = seeded(server, 121000, "persistent");
            Path history = dir.resolve("history.tsv");
            var items = FXCollections.observableArrayList(row);
            var saver = new HistoryService(items, new java.util.concurrent.atomic.AtomicLong(), null, () -> 1,
                    null, null, null, null, history);
            fx(() -> { row.setState(DownloadRow.State.PENDING); saver.saveNow(); });
            String[] columns = Files.readString(history).stripTrailing().split("\\t", -1);
            columns[7] = "1"; // Incomplete tasks must survive the normal history age cutoff.
            Files.writeString(history, String.join("\t", columns) + "\n");
            var restored = FXCollections.<DownloadRow>observableArrayList();
            var loader = new HistoryService(restored, new java.util.concurrent.atomic.AtomicLong(), null, () -> 1,
                    null, null, null, null, history);
            loader.loadOnce(); await(() -> restored.size() == 1, 5);
            fx(() -> {
                DownloadRow loaded = restored.getFirst();
                assertEquals(DownloadRow.State.PAUSED, loaded.getState());
                assertEquals(121000, loaded.downloadedBytes.get());
                assertEquals(128000, loaded.totalBytes.get());
                assertEquals(row.originalUrl, loaded.originalUrl);
                assertEquals("\"v1\"", loaded.resumeEtag);
                assertEquals(121000.0 / 128000, loaded.progress.get());
            });
            start(restored.getFirst());
            await(() -> restored.getFirst().getState() == DownloadRow.State.COMPLETED, 5);
            assertArrayEquals(server.bytes, Files.readAllBytes(restored.getFirst().outputFile.get()));
        }
    }
    @Test void oldPausedRejectionAndNewFailureSurviveHistoryWithSavedBytesAndReason() throws Exception {
        try (Fixture server = new Fixture(128000)) {
            for (var previousState : new DownloadRow.State[]{DownloadRow.State.PAUSED, DownloadRow.State.FAILED}) {
                DownloadRow row = seeded(server, 121000, "blocked-" + previousState);
                String message = "Download link/session rejected (HTTP 498); get a fresh link in the browser • saved data kept";
                Path history = dir.resolve(previousState + ".tsv");
                var saver = new HistoryService(FXCollections.observableArrayList(row), new java.util.concurrent.atomic.AtomicLong(),
                        null, () -> 1, null, null, null, null, history);
                fx(() -> { row.setState(previousState); row.status.set(message); saver.saveNow(); });
                String[] columns = Files.readString(history).stripTrailing().split("\\t", -1);
                columns[7] = "1";
                Files.writeString(history, String.join("\t", columns) + "\n");
                var restored = FXCollections.<DownloadRow>observableArrayList();
                var loader = new HistoryService(restored, new java.util.concurrent.atomic.AtomicLong(), null, () -> 1,
                        null, null, null, null, history);
                loader.loadOnce(); await(() -> restored.size() == 1, 5);
                fx(() -> {
                    var loaded = restored.getFirst();
                    assertEquals(DownloadRow.State.FAILED, loaded.getState());
                    assertEquals(message, loaded.status.get());
                    assertEquals(121000, loaded.downloadedBytes.get());
                    assertEquals(121000.0 / 128000, loaded.progress.get(), 0.000001);
                    var service = new DownloadService(restored);
                    service.setSidebarKey("CANCELLED");
                    assertEquals(List.of(loaded), List.copyOf(service.view()));
                    assertTrue(service.activeView().isEmpty());
                });
                assertArrayEquals(Arrays.copyOf(server.bytes, 121000), Files.readAllBytes(DirectPartialFiles.part(row.outputFile.get(), -1)));
            }
        }
    }

    @Test void rejectedTaskCanRetryWithoutDiscardingItsPartialData() throws Exception {
        try (Fixture server = new Fixture(128000)) {
            server.status = 498;
            DownloadRow row = seeded(server, 121000, "rejected-retry"); start(row);
            await(() -> row.getState() == DownloadRow.State.FAILED, 5);
            assertTrue(row.status.get().endsWith("saved data kept"));
            Process process = processes.get(row);
            if (process != null) assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertEquals(121000, diskBytes(row));
            server.status = 0;
            fx(() -> runtime.stateCoordinator().retry(row));
            await(() -> row.getState() == DownloadRow.State.COMPLETED, 5);
            assertEquals(List.of(121000), server.offsets);
            assertArrayEquals(server.bytes, Files.readAllBytes(row.outputFile.get()));
        }
    }

    @Test void restartedDownloadWithoutValidatorsVerifiesPrefixThenFinishesSameResponse() throws Exception {
        try (Fixture server = new Fixture(1024 * 1024)) {
            server.etag = null; server.slow = true;
            DownloadRow old = row(server); start(old);
            await(() -> diskBytes(old) >= 128 * 1024, 5);
            Process process = processes.get(old);
            reasons.put(old, "PAUSE"); process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            long saved = diskBytes(old); assertTrue(saved > 0);
            server.slow = false; server.offsets.clear();
            DownloadRow restored = row(server); restored.outputFile.set(old.outputFile.get());
            start(restored);
            await(() -> restored.getState() == DownloadRow.State.COMPLETED, 10);
            assertNull(restored.resumeEtag); assertNull(restored.resumeLastModified);
            assertEquals(List.of(0), server.offsets); // One full payload, no second unvalidated range.
            assertArrayEquals(server.bytes, Files.readAllBytes(restored.outputFile.get()));
        }
    }

    @Test void quickResumeChecksSampleThenDownloadOnlyRemainingBytes() throws Exception {
        try (Fixture server = new Fixture(3 * 1024 * 1024)) {
            server.etag = null;
            runtime.runner().configureQuickResumeChecks(true);
            int saved = 2 * 1024 * 1024;
            DownloadRow row = seededWithoutValidator(server, saved, "quick-resume");
            start(row); await(() -> row.getState() == DownloadRow.State.COMPLETED, 10);
            assertEquals(6, server.offsets.size()); // Five samples, then saved offset.
            assertEquals(0, server.offsets.getFirst());
            assertEquals(saved - QuickResumeVerifier.BLOCK, server.offsets.get(4));
            assertEquals(saved, server.offsets.getLast());
            assertArrayEquals(server.bytes, Files.readAllBytes(row.outputFile.get()));
        }
    }
    @Test void quickResumeRejectsChangedSampleWithoutWritingAnySavedBytes() throws Exception {
        try (Fixture server = new Fixture(3 * 1024 * 1024)) {
            server.etag = null; runtime.runner().configureQuickResumeChecks(true);
            int saved = 2 * 1024 * 1024;
            DownloadRow row = seededWithoutValidator(server, saved, "quick-changed");
            byte[] before = Files.readAllBytes(DirectPartialFiles.part(row.outputFile.get(), -1));
            server.bytes[saved - 1] ^= 1;
            start(row); await(() -> row.getState() == DownloadRow.State.FAILED, 10);
            assertTrue(row.status.get().contains("does not match"));
            assertArrayEquals(before, Files.readAllBytes(DirectPartialFiles.part(row.outputFile.get(), -1)));
            assertFalse(Files.exists(row.outputFile.get()));
        }
    }
    @Test void quickResumeFallsBackToFullComparisonWhenSampleRangesAreIgnored() throws Exception {
        try (Fixture server = new Fixture(1024 * 1024)) {
            server.etag = null; server.ignoreDataRange = true;
            runtime.runner().configureQuickResumeChecks(true);
            DownloadRow row = seededWithoutValidator(server, 800000, "quick-fallback");
            start(row); await(() -> row.getState() == DownloadRow.State.COMPLETED, 10);
            assertEquals(List.of(0, 0), server.offsets);
            assertArrayEquals(server.bytes, Files.readAllBytes(row.outputFile.get()));
        }
    }
    @Test void quickResumeSettingSavesAndDiscardsAsPartOfDraft() throws Exception {
        fx(() -> {
            try (var sound = new SoundFeedbackService(new SoundFeedbackServiceTest.MemoryPreferences())) {
                var stored = new TransferPreferences(new SoundFeedbackServiceTest.MemoryPreferences());
                assertFalse(stored.quickResumeChecks());
                var initial = new com.grabx.app.grabx.ui.components.SettingsPage.Values(dir.toString(),true,0,0,false,.3,30);
                var page = new com.grabx.app.grabx.ui.components.SettingsPage(initial, sound, v -> stored.saveQuickResumeChecks(v.quickResumeChecks()));
                new javafx.scene.Scene(page, 1000, 800); page.applyCss(); page.layout();
                ((javafx.scene.control.ToggleButton)page.lookup("#settings-nav-network")).fire(); page.applyCss(); page.layout();
                var toggle = (javafx.scene.control.CheckBox)page.lookup("#settings-quick-resume-checks");
                toggle.setSelected(true); assertTrue(page.isDirty());
                ((javafx.scene.control.Button)page.lookup("#settings-discard")).fire();
                assertFalse(toggle.isSelected()); assertFalse(stored.quickResumeChecks());
                toggle.setSelected(true); ((javafx.scene.control.Button)page.lookup("#settings-save")).fire();
                assertTrue(stored.quickResumeChecks()); assertFalse(page.isDirty());
            }
        });
    }
    @Test void weakOrAbsentValidatorRequiresEverySavedByteToMatch() throws Exception {
        try (Fixture server = new Fixture(256000)) {
            for (String etag : new String[]{null, "W/\"v1\""}) {
                server.etag = etag;
                DownloadRow row = seededWithoutValidator(server, 120000, "unverified-" + (etag == null));
                byte[] before = Files.readAllBytes(DirectPartialFiles.part(row.outputFile.get(), -1));
                server.bytes[60001] ^= 1; // Difference inside prefix, not just a sampled boundary.
                start(row); await(() -> row.getState() == DownloadRow.State.FAILED, 5);
                assertTrue(row.status.get().contains("does not match"));
                assertArrayEquals(before, Files.readAllBytes(DirectPartialFiles.part(row.outputFile.get(), -1)));
                assertFalse(Files.exists(row.outputFile.get()));
                server.bytes[60001] ^= 1;
            }
        }
    }

    @Test void completePartWithoutValidatorStillNeedsContentVerification() throws Exception {
        try (Fixture server = new Fixture(128000)) {
            server.etag = null;
            DownloadRow row = seededWithoutValidator(server, server.bytes.length, "complete-no-validator");
            start(row); await(() -> row.getState() == DownloadRow.State.COMPLETED, 5);
            assertEquals(List.of(0), server.offsets);
            assertArrayEquals(server.bytes, Files.readAllBytes(row.outputFile.get()));
            DownloadRow changed = seededWithoutValidator(server, server.bytes.length, "complete-but-changed");
            server.bytes[123] ^= 1;
            start(changed); await(() -> changed.getState() == DownloadRow.State.FAILED, 5);
            assertFalse(Files.exists(changed.outputFile.get()));
            assertEquals(server.bytes.length, diskBytes(changed));
        }
    }

    @Test void interruptionDuringPrefixVerificationRetriesWithoutAppendingDuplicateBytes() throws Exception {
        try (Fixture server = new Fixture(128000)) {
            server.etag = null; server.cuts.set(1); server.cutAt = 32000;
            DownloadRow row = seededWithoutValidator(server, 100000, "verify-retry");
            start(row); await(() -> row.status.get().startsWith("Waiting for network"), 5);
            assertEquals(100000, diskBytes(row));
            await(() -> row.getState() == DownloadRow.State.COMPLETED, 8);
            assertEquals(List.of(0, 0), server.offsets);
            assertArrayEquals(server.bytes, Files.readAllBytes(row.outputFile.get()));
        }
    }

    @Test void prefixVerificationCanPauseResumeAndCancelWithoutWritingToSavedData() throws Exception {
        try (Fixture server = new Fixture(1024 * 1024)) {
            server.etag = null; server.slow = true;
            for (boolean cancel : new boolean[]{false, true}) {
                DownloadRow row = seededWithoutValidator(server, 800000, "verify-pause-" + cancel);
                byte[] before = Files.readAllBytes(DirectPartialFiles.part(row.outputFile.get(), -1));
                int previousPayloads = server.offsets.size();
                start(row); await(() -> row.status.get().startsWith("Verifying saved data")
                        && GlobalSpeedService.parseBytesPerSecond(row.speed.get()) > 0 && !row.eta.get().isBlank(), 5);
                fx(() -> {
                    assertEquals(800000, row.downloadedBytes.get());
                    assertEquals(800000.0 / server.bytes.length, row.progress.get(), 0.000001);
                    assertTrue(row.eta.get().matches("\\d+:\\d{2}"));
                });
                fx(() -> runtime.stateCoordinator().pause(row));
                Thread.sleep(250);
                assertEquals(DownloadRow.State.PAUSED, row.getState());
                assertEquals(800000, diskBytes(row));
                fx(() -> { assertEquals("", row.speed.get()); assertEquals("", row.eta.get()); });
                if (cancel) {
                    fx(() -> runtime.stateCoordinator().cancel(row));
                    await(() -> !processes.containsKey(row), 5);
                    assertArrayEquals(before, Files.readAllBytes(DirectPartialFiles.part(row.outputFile.get(), -1)));
                } else {
                    fx(() -> runtime.stateCoordinator().resume(row));
                    await(() -> row.getState() == DownloadRow.State.COMPLETED, 10);
                    assertArrayEquals(server.bytes, Files.readAllBytes(row.outputFile.get()));
                    assertEquals(previousPayloads + 1, server.offsets.size()); // Pause reuses this verified response.
                    fx(() -> { assertEquals("", row.speed.get()); assertEquals("", row.eta.get()); });
                }
            }
        }
    }

    @Test void fullPrefixComparisonAlsoWorksWhenUnvalidatedServerStopsSupportingRanges() throws Exception {
        try (Fixture server = new Fixture(128000)) {
            server.etag = "W/\"v1\""; server.noRanges = true;
            DownloadRow row = seededWithoutValidator(server, 100000, "no-range-no-validator");
            start(row); await(() -> row.getState() == DownloadRow.State.COMPLETED, 5);
            assertArrayEquals(server.bytes, Files.readAllBytes(row.outputFile.get()));
            assertEquals(List.of(0), server.offsets);
        }
    }

    @Test void missingRemoteValidatorDoesNotBypassAnExistingStrongIdentity() throws Exception {
        try (Fixture server = new Fixture(128000)) {
            DownloadRow row = seeded(server, 100000, "lost-remote-validator"); server.etag = null;
            start(row); await(() -> row.getState() == DownloadRow.State.FAILED, 5);
            assertTrue(server.offsets.isEmpty()); assertEquals(100000, diskBytes(row));
        }
    }

    @Test void unvalidatedResumeStillRejectsChangedSizeBeforeTransferringPayload() throws Exception {
        try (Fixture server = new Fixture(128000)) {
            server.etag = null;
            DownloadRow row = seededWithoutValidator(server, 100000, "changed-size-no-validator");
            row.resumeTotal++;
            DirectDownloadState.save(row, row.outputFile.get());
            start(row); await(() -> row.getState() == DownloadRow.State.FAILED, 5);
            assertTrue(row.status.get().contains("size changed"));
            assertTrue(server.offsets.isEmpty()); assertEquals(100000, diskBytes(row));
        }
    }

    @Test void previouslyFailedUnknownIdentityTaskCanRetryWithItsSavedPrefix() throws Exception {
        try (Fixture server = new Fixture(128000)) {
            server.etag = null;
            DownloadRow row = seededWithoutValidator(server, 100000, "previously-failed");
            fx(() -> {
                row.setState(DownloadRow.State.FAILED);
                row.status.set("Cannot verify remote identity; provide a verifiable replacement link • saved data kept");
                runtime.stateCoordinator().retry(row);
            });
            await(() -> row.getState() == DownloadRow.State.COMPLETED, 5);
            assertEquals(List.of(0), server.offsets);
            assertArrayEquals(server.bytes, Files.readAllBytes(row.outputFile.get()));
        }
    }

    DownloadRow seededWithoutValidator(Fixture server, int count, String name) throws Exception {
        DownloadRow row = seeded(server, count, name);
        row.resumeEtag = server.etag; row.resumeLastModified = null;
        DirectDownloadState.save(row, row.outputFile.get()); return row;
    }
    DownloadRow row(Fixture server) { return new DownloadRow(server.url(), "file.bin", 1, dir.toString(), "Direct", "Auto"); }
    DownloadRow seeded(Fixture server, int count, String name) throws Exception {
        DownloadRow row = row(server); row.outputFile.set(dir.resolve(name)); row.resumeEtag = "\"v1\"";
        row.resumeTotal = server.bytes.length; row.totalBytes.set(server.bytes.length); row.resumeSupport = "Yes";
        Files.write(DirectPartialFiles.part(row.outputFile.get(), -1), Arrays.copyOf(server.bytes, count));
        DirectDownloadState.save(row, row.outputFile.get()); return row;
    }
    void start(DownloadRow row) throws Exception { fx(() -> runtime.runner().start(row, row.outputFile.get() != null)); }
    long diskBytes(DownloadRow row) { try { return row.outputFile.get() == null ? 0 : DirectDownloadState.downloaded(row.outputFile.get()); } catch (Exception e) { return 0; } }
    static void fx(Runnable action) throws Exception { FutureTask<Void> task = new FutureTask<>(action, null); Platform.runLater(task); task.get(10, TimeUnit.SECONDS); }
    static void await(BooleanSupplier condition, int seconds) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < end) {
            FutureTask<Boolean> task = new FutureTask<>(condition::getAsBoolean); Platform.runLater(task);
            if (task.get(5, TimeUnit.SECONDS)) return;
            Thread.sleep(20);
        }
        fail("Timed out waiting for download state");
    }
    static class Fixture implements AutoCloseable {
        final byte[] bytes;
        final ServerSocket server = new ServerSocket(0, 20, InetAddress.getLoopbackAddress());
        final ExecutorService pool = Executors.newCachedThreadPool();
        final List<Integer> offsets = new CopyOnWriteArrayList<>();
        final List<String> conditions = new CopyOnWriteArrayList<>();
        final List<String> agents = new CopyOnWriteArrayList<>();
        final AtomicInteger cuts = new AtomicInteger(), requests = new AtomicInteger(), stalls = new AtomicInteger(), temporary = new AtomicInteger();
        volatile boolean slow, noRanges, ignoreDataRange;
        volatile int chunkDelayMillis = 10;
        volatile int status, redirect, temporaryStatus, cutAt;
        volatile String etag = "\"v1\"";
        Fixture(int length) throws IOException {
            bytes = new byte[length]; new Random(77).nextBytes(bytes);
            pool.submit(() -> { while (!server.isClosed()) { try { Socket socket = server.accept(); pool.submit(() -> serve(socket)); } catch (IOException ignored) {} } });
        }
        String url() { return "http://localhost:" + server.getLocalPort() + "/file.bin"; }
        void serve(Socket socket) {
            try (socket) {
                socket.setSoTimeout(3000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                String first = reader.readLine(), line;
                Map<String, String> headers = new HashMap<>();
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    int colon = line.indexOf(':'); if (colon > 0) headers.put(line.substring(0, colon).toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
                }
                agents.add(headers.getOrDefault("user-agent", ""));
                requests.incrementAndGet();
                OutputStream out = socket.getOutputStream();
                if (first != null && first.contains("?redirect")) {
                    out.write(("HTTP/1.1 " + redirect + " Redirect\r\nLocation: /file.bin\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); return;
                }
                int start = 0, end = bytes.length - 1;
                String range = headers.get("range"); boolean probe = "bytes=0-0".equals(range);
                if (range != null && !range.isBlank()) { String[] values = range.substring(6).split("-"); start = Integer.parseInt(values[0]); end = values.length > 1 ? Integer.parseInt(values[1]) : end; }
                int activeStatus = temporary.getAndUpdate(n -> Math.max(0, n - 1)) > 0 ? temporaryStatus : status;
                if (activeStatus != 0 || bytes.length == 0) {
                    int code = activeStatus == 0 ? 416 : activeStatus;
                    out.write(("HTTP/1.1 " + code + " Error\r\nContent-Range: bytes */" + bytes.length + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); return;
                }
                boolean partial = range != null && !noRanges && !(ignoreDataRange && !probe);
                if (!partial) { start = 0; end = bytes.length - 1; }
                if (!probe) { offsets.add(start); conditions.add(headers.getOrDefault("if-range", "")); }
                int length = end - start + 1;
                out.write(("HTTP/1.1 " + (partial ? "206 Partial Content" : "200 OK")
                        + (etag == null ? "" : "\r\nETag: " + etag) + "\r\nContent-Type: application/octet-stream\r\nAccept-Ranges: bytes"
                        + (partial ? "\r\nContent-Range: bytes " + start + "-" + end + "/" + bytes.length : "")
                        + "\r\nContent-Length: " + length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                if (!probe && stalls.getAndUpdate(n -> Math.max(0, n - 1)) > 0) { Thread.sleep(20000); return; }
                int actual = length;
                if (!probe && cuts.getAndUpdate(n -> Math.max(0, n - 1)) > 0)
                    actual = cutAt > 0 ? Math.min(length, cutAt) : start == 0 ? length * 95 / 100 : length / 2;
                for (int written = 0; written < actual; ) {
                    int n = Math.min(8192, actual - written); out.write(bytes, start + written, n); out.flush(); written += n;
                    if (slow && !probe) Thread.sleep(chunkDelayMillis);
                }
            } catch (IOException ignored) {} catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        public void close() throws Exception { server.close(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }
}
