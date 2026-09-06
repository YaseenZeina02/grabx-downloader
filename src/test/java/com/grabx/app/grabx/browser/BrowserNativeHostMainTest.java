package com.grabx.app.grabx.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserNativeHostMainTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void statusDoesNotQueueAndCancellationRemovesOnlySelectedRequest() throws Exception {
        Path dir = temporaryDirectory.resolve("browser-inbox");
        var inbox = new BrowserBridgeInbox(dir);
        inbox.enqueue("request-1111", "{}".getBytes(StandardCharsets.UTF_8));
        inbox.enqueue("request-2222", "{}".getBytes(StandardCharsets.UTF_8));
        var input = new ByteArrayOutputStream();
        BrowserNativeHostMain.writeMessage(input, "{\"type\":\"status\"}".getBytes(StandardCharsets.UTF_8));
        BrowserNativeHostMain.writeMessage(input, "{\"type\":\"cancelQueued\",\"requestId\":\"request-1111\"}".getBytes(StandardCharsets.UTF_8));
        var output = new ByteArrayOutputStream();
        BrowserNativeHostMain.run(new ByteArrayInputStream(input.toByteArray()), output, new BrowserBridgeProtocol(), inbox);
        var responses = new ByteArrayInputStream(output.toByteArray());
        assertTrue(new String(BrowserNativeHostMain.readMessage(responses), StandardCharsets.UTF_8).contains("\"running\":false"));
        assertTrue(new String(BrowserNativeHostMain.readMessage(responses), StandardCharsets.UTF_8).contains("\"ok\":true"));
        assertTrue(!Files.exists(dir.resolve("request-1111.json")));
        assertTrue(Files.exists(dir.resolve("request-2222.json")));
    }

    @Test
    void closedAppRequiresApprovalBeforePersistingCapture() throws Exception {
        String capture = "{\"protocolVersion\":1,\"type\":\"capture\",\"requestId\":\"request-9999\","
                + "\"pageUrl\":\"https://example.com/file.zip\",\"mediaKind\":\"file\",\"action\":\"file\"}";
        var inbox = new BrowserBridgeInbox(temporaryDirectory.resolve("browser-inbox"));
        for (boolean approved : new boolean[]{false, true}) {
            var input = new ByteArrayOutputStream();
            String request = approved ? capture.replace("}", ",\"queueApproved\":true}") : capture;
            BrowserNativeHostMain.writeMessage(input, request.getBytes(StandardCharsets.UTF_8));
            var output = new ByteArrayOutputStream();
            BrowserNativeHostMain.run(new ByteArrayInputStream(input.toByteArray()), output, new BrowserBridgeProtocol(), inbox);
            String result = new String(BrowserNativeHostMain.readMessage(new ByteArrayInputStream(output.toByteArray())), StandardCharsets.UTF_8);
            assertTrue(result.contains(approved ? "queued" : "confirmation_required"));
            assertEquals(approved, Files.exists(inbox.directory().resolve("request-9999.json")));
        }
    }

    @Test
    void readsAndWritesNativeEndianFramedMessages() throws Exception {
        byte[] body = "{\"hello\":\"GrabX\"}".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        BrowserNativeHostMain.writeMessage(framed, body);

        assertArrayEquals(body, BrowserNativeHostMain.readMessage(
                new ByteArrayInputStream(framed.toByteArray())));
    }

    @Test
    void validatesQueuesAndAcknowledgesACapture() throws Exception {
        byte[] request = """
                {"protocolVersion":1,"type":"capture","requestId":"request-5678",
                 "pageUrl":"https://example.com/video","mediaUrl":null,"title":"Example",
                 "mimeType":"","mediaKind":"page","action":"ask","createdAt":100}
                """.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream inputFrame = new ByteArrayOutputStream();
        inputFrame.write(ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(request.length).array());
        inputFrame.write(request);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Path inboxDirectory = temporaryDirectory.resolve("browser-inbox");
        Files.writeString(temporaryDirectory.resolve("app.pid"),
                Long.toString(ProcessHandle.current().pid()));

        BrowserNativeHostMain.run(
                new ByteArrayInputStream(inputFrame.toByteArray()), output,
                new BrowserBridgeProtocol(), new BrowserBridgeInbox(inboxDirectory));

        byte[] response = BrowserNativeHostMain.readMessage(new ByteArrayInputStream(output.toByteArray()));
        assertTrue(new String(response, StandardCharsets.UTF_8).contains("\"ok\":true"));
        assertTrue(Files.exists(inboxDirectory.resolve("request-5678.json")));
        assertEquals(1, Files.list(inboxDirectory).count());
    }
}
