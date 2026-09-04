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
