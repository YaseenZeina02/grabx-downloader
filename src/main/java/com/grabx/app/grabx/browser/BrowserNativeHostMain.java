package com.grabx.app.grabx.browser;

import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** Chrome/Firefox native-messaging process. Stdout is reserved for framed JSON only. */
public final class BrowserNativeHostMain {
    private BrowserNativeHostMain() {
    }

    public static void main(String[] args) {
        BrowserBridgeProtocol protocol = new BrowserBridgeProtocol();
        BrowserBridgeInbox inbox = BrowserBridgeInbox.forCurrentUser();
        try {
            run(System.in, System.out, protocol, inbox);
        } catch (Exception exception) {
            System.err.println("GrabX native host stopped: " + exception.getMessage());
        }
    }

    static void run(InputStream input, OutputStream output,
                    BrowserBridgeProtocol protocol, BrowserBridgeInbox inbox) throws Exception {
        while (true) {
            byte[] payload;
            try {
                payload = readMessage(input);
            } catch (EOFException end) {
                return;
            }

            BrowserBridgeResponse response;
            try {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var control = mapper.readTree(payload);
                String type = control.path("type").asText();
                if ("status".equals(type)) {
                    boolean running = isGrabXRunning(inbox.directory().getParent().resolve("app.pid"));
                    writeMessage(output, mapper.writeValueAsBytes(java.util.Map.of("ok", true, "running", running)));
                    continue;
                }
                if ("listQueued".equals(type)) {
                    var pending = new java.util.ArrayList<java.util.Map<String, Object>>();
                    inbox.ensureDirectory();
                    try (var files = Files.newDirectoryStream(inbox.directory(), "*.json")) {
                        for (Path file : files) {
                            try {
                                var item = protocol.parse(Files.readAllBytes(file));
                                pending.add(java.util.Map.of("requestId", item.requestId(), "title", item.title()));
                            } catch (Exception ignored) { }
                        }
                    }
                    writeMessage(output, mapper.writeValueAsBytes(java.util.Map.of("ok", true, "items", pending)));
                    continue;
                }
                if ("cancelQueued".equals(type)) {
                    String id = control.path("requestId").asText();
                    if (!id.matches("[A-Za-z0-9._:-]{8,100}")) throw new IllegalArgumentException("Invalid request ID");
                    boolean removed = Files.deleteIfExists(inbox.directory().resolve(id.replaceAll("[^A-Za-z0-9._-]", "_") + ".json"));
                    writeMessage(output, mapper.writeValueAsBytes(java.util.Map.of("ok", removed,
                            "message", removed ? "Queued download cancelled" : "Already received by GrabX; cancel it in the app")));
                    continue;
                }
                BrowserCapture capture = protocol.parse(payload);
                boolean appRunning = isGrabXRunning(inbox.directory().getParent().resolve("app.pid"));
                if (!appRunning && !control.path("queueApproved").asBoolean(false)) {
                    writeMessage(output, mapper.writeValueAsBytes(java.util.Map.of("ok", false,
                            "status", "confirmation_required", "message", "GrabX is closed; confirm adding to the waiting list")));
                    continue;
                }
                byte[] normalized = protocol.serialize(capture);
                inbox.enqueue(capture.requestId(), normalized);
                // Closed-app requests stay queued until the user opens GrabX.
                response = BrowserBridgeResponse.accepted(capture.requestId(), appRunning);
            } catch (BrowserBridgeProtocol.ProtocolException exception) {
                response = BrowserBridgeResponse.rejected(null, exception.getMessage());
            } catch (Exception exception) {
                response = BrowserBridgeResponse.rejected(null, "Could not hand off this download");
            }
            writeMessage(output, protocol.serialize(response));
        }
    }

    static byte[] readMessage(InputStream input) throws Exception {
        byte[] lengthBytes = input.readNBytes(4);
        if (lengthBytes.length == 0) throw new EOFException();
        if (lengthBytes.length != 4) throw new EOFException("Incomplete native message header");
        int length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.nativeOrder()).getInt();
        if (length <= 0 || length > BrowserBridgeProtocol.MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("Invalid native message length");
        }
        byte[] payload = input.readNBytes(length);
        if (payload.length != length) throw new EOFException("Incomplete native message");
        return payload;
    }

    static void writeMessage(OutputStream output, byte[] payload) throws Exception {
        if (payload.length > BrowserBridgeProtocol.MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("Native response is too large");
        }
        output.write(ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(payload.length).array());
        output.write(payload);
        output.flush();
    }

    private static boolean isGrabXRunning(Path marker) {
        try {
            long pid = Long.parseLong(Files.readString(marker).trim());
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void requestApplicationLaunch() {
        try {
            String configuredExecutable = System.getenv("GRABX_APP_EXECUTABLE");
            if (configuredExecutable != null && !configuredExecutable.isBlank()) {
                new ProcessBuilder(configuredExecutable).start();
                return;
            }
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("mac")) {
                new ProcessBuilder("open", "-a", "GrabX").start();
            } else if (os.contains("win")) {
                new ProcessBuilder("cmd", "/c", "start", "", "GrabX.exe").start();
            } else {
                new ProcessBuilder("grabx").start();
            }
        } catch (Exception ignored) {
            // The request remains queued and is consumed the next time GrabX opens.
        }
    }
}
