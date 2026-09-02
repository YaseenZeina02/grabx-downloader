package com.grabx.app.grabx.core.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AddLinkFlowServiceTest {
    @Test
    void opensWithTrimmedClipboardUrlAndIgnoresInvalidText() {
        List<String> shown = new ArrayList<>();
        AddLinkFlowService service = service(shown, () -> "  https://example.com/video  ", (action, delay) -> action.run());

        service.showFromClipboard();
        assertEquals(List.of("https://example.com/video"), shown);

        AddLinkFlowService invalid = service(shown, () -> "not a url", (action, delay) -> action.run());
        invalid.showFromClipboard();
        assertNull(shown.getLast());
    }

    @Test
    void coalescesPendingOpenRequestsAndCanReturnFromPlaylist() {
        List<String> shown = new ArrayList<>();
        AtomicReference<Runnable> scheduled = new AtomicReference<>();
        AddLinkFlowService service = service(shown, () -> "", (action, delay) -> scheduled.set(action));

        service.openOrUpdate("https://example.com/first");
        service.openOrUpdate("https://example.com/second");
        scheduled.get().run();
        assertEquals(List.of("https://example.com/first"), shown);

        service.beginPlaylist("https://example.com/playlist");
        service.returnFromPlaylist();
        scheduled.get().run();
        assertEquals("https://example.com/playlist", shown.getLast());
    }

    private static AddLinkFlowService service(
            List<String> shown,
            java.util.function.Supplier<String> clipboard,
            java.util.function.BiConsumer<Runnable, Long> scheduler
    ) {
        AtomicBoolean open = new AtomicBoolean(false);
        return new AddLinkFlowService(
                new AddLinkFlowService.DialogGateway() {
                    @Override public boolean isOpen() { return open.get(); }
                    @Override public void show(String prefillUrl) { shown.add(prefillUrl); }
                },
                value -> value != null && value.startsWith("http"),
                clipboard,
                scheduler,
                Runnable::run,
                text -> { }
        );
    }
}
