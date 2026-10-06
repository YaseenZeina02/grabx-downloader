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
    @Test void segmentedDownloadAndInterruptedAssemblyKeepOriginalLayout() throws Exception {
        try (Fixture server = new Fixture(5 * 1024 * 1024)) {
            DownloadRow row = row(server); start(row);
            await(() -> row.getState() == DownloadRow.State.COMPLETED, 10);
            assertEquals(2, row.resumeSegments);
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
                    if (slow && !probe) Thread.sleep(10);
                }
            } catch (IOException ignored) {} catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        public void close() throws Exception { server.close(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }
}
