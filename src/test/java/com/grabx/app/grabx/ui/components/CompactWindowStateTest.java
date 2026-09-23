package com.grabx.app.grabx.ui.components;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.junit.jupiter.api.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Native-window regression checks: opt in with GRABX_TEST_FX=true. */
class CompactWindowStateTest {
    private Stage stage;
    private static boolean started;

    @BeforeAll static void toolkit() throws Exception {
        assumeTrue("true".equals(System.getenv("GRABX_TEST_FX")));
        var ready = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); });
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        started = true;
    }

    @AfterAll static void stopToolkit() { if (started) Platform.exit(); }

    @BeforeEach void window() throws Exception {
        fx(() -> {
            stage = new Stage();
            var root = new BorderPane();
            root.setPrefSize(1280, 820);
            stage.setScene(new Scene(root));
            stage.setMinWidth(900); stage.setMinHeight(500);
            stage.setWidth(1000); stage.setHeight(650);
            stage.setX(130); stage.setY(95);
            stage.show();
        });
        settle();
    }

    @AfterEach void closeWindow() throws Exception { if (stage != null) fx(stage::close); }

    @Test void repeatedCompactReturnsKeepPositionSizeAndNormalModeDespiteLateNativeBounds() throws Exception {
        for (int cycle = 0; cycle < 8; cycle++) {
            int index = cycle;
            fx(() -> {
                stage.setX(120 + index * 5); stage.setY(80 + index * 3);
                stage.setWidth(980 + index * 10); stage.setHeight(620 + index * 5);
            });
            settle();
            CompactWindowState snapshot = snapshot();
            var restored = new CountDownLatch(1);
            fx(() -> {
                stage.hide();
                snapshot.restore(stage, restored::countDown);
                // Simulate the reported late screen-sized callback (without full-screen).
                var late = new PauseTransition(Duration.millis(70));
                late.setOnFinished(e -> {
                    var screen = Screen.getPrimary().getVisualBounds();
                    stage.setX(screen.getMinX()); stage.setY(screen.getMinY());
                    stage.setWidth(screen.getWidth()); stage.setHeight(screen.getHeight());
                });
                late.play();
            });
            assertTrue(restored.await(3, TimeUnit.SECONDS), "Restoration must complete");
            fx(() -> {
                assertTrue(snapshot.matches(stage), "Cycle " + index + " changed the saved frame");
                assertFalse(stage.isMaximized()); assertFalse(stage.isFullScreen());
            });
        }
    }

    @Test void intentionalMaximizeIsPreservedAndResizingIsReleasedAfterRestoration() throws Exception {
        fx(() -> stage.setMaximized(true));
        settle();
        var maximized = snapshot();
        assertTrue(maximized.maximized());
        var done = new CountDownLatch(1);
        fx(() -> { stage.hide(); maximized.restore(stage, done::countDown); });
        assertTrue(done.await(3, TimeUnit.SECONDS));
        fx(() -> assertTrue(stage.isMaximized()));
        fx(() -> stage.setMaximized(false));
        settle();
        fx(() -> { stage.setWidth(950); stage.setHeight(610); });
        settle();
        fx(() -> {
            assertEquals(950, stage.getWidth(), 2);
            assertEquals(610, stage.getHeight(), 2);
            assertFalse(stage.isFullScreen());
        });
    }

    @Test void closingDuringRestorationDoesNotReopenTheWindow() throws Exception {
        var saved = snapshot();
        var done = new CountDownLatch(1);
        fx(() -> {
            stage.hide(); saved.restore(stage, done::countDown);
            stage.hide();
        });
        assertTrue(done.await(3, TimeUnit.SECONDS));
        fx(() -> assertFalse(stage.isShowing()));
    }

    @Test void controllerBlocksAnotherToggleUntilTheOriginalFrameIsRestored() throws Exception {
        var controller = new com.grabx.app.grabx.MainController();
        var type = controller.getClass();
        var rootField = type.getDeclaredField("root"); rootField.setAccessible(true);
        var compactRoot = type.getDeclaredField("compactRoot"); compactRoot.setAccessible(true);
        var compactStage = type.getDeclaredField("compactStage"); compactStage.setAccessible(true);
        var transitioning = type.getDeclaredField("compactTransitioning"); transitioning.setAccessible(true);
        var enter = type.getDeclaredMethod("enterCompactView"); enter.setAccessible(true);
        var exit = type.getDeclaredMethod("exitCompactView"); exit.setAccessible(true);
        fx(() -> {
            try {
                rootField.set(controller, stage.getScene().getRoot());
                compactRoot.set(controller, new javafx.scene.layout.VBox());
            } catch (Exception e) { throw new AssertionError(e); }
        });
        var saved = snapshot();
        try {
            for (int cycle = 0; cycle < 3; cycle++) {
                fx(() -> {
                    try {
                        enter.invoke(controller);
                        assertFalse(stage.isShowing());
                        assertTrue(((Stage) compactStage.get(controller)).isShowing());
                        exit.invoke(controller);
                        enter.invoke(controller); // A second fast click must not recapture transient bounds.
                        assertTrue(transitioning.getBoolean(controller));
                        assertFalse(((Stage) compactStage.get(controller)).isShowing());
                    } catch (Exception e) { throw new AssertionError(e); }
                });
                settle(); settle();
                fx(() -> {
                    try { assertFalse(transitioning.getBoolean(controller)); }
                    catch (IllegalAccessException e) { throw new AssertionError(e); }
                    assertTrue(saved.matches(stage));
                });
            }
        } finally {
            fx(() -> {
                try { if (compactStage.get(controller) instanceof Stage small) small.close(); }
                catch (IllegalAccessException e) { throw new AssertionError(e); }
            });
        }
    }

    private CompactWindowState snapshot() throws Exception {
        var result = new AtomicReference<CompactWindowState>();
        fx(() -> result.set(CompactWindowState.capture(stage)));
        return result.get();
    }

    private static void settle() throws Exception {
        var done = new CountDownLatch(1);
        fx(() -> {
            var delay = new PauseTransition(Duration.millis(250));
            delay.setOnFinished(e -> done.countDown()); delay.play();
        });
        assertTrue(done.await(3, TimeUnit.SECONDS));
    }

    private static void fx(Runnable action) throws Exception {
        var task = new FutureTask<Void>(() -> { action.run(); return null; });
        Platform.runLater(task);
        task.get(5, TimeUnit.SECONDS);
    }
}
