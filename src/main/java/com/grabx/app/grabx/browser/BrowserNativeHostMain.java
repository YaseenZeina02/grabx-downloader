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
                BrowserCapture capture = protocol.parse(payload);
                byte[] normalized = protocol.serialize(capture);
                inbox.enqueue(capture.requestId(), normalized);
                boolean appRunning = isGrabXRunning(inbox.directory().getParent().resolve("app.pid"));
                if (!appRunning) requestApplicationLaunch();
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
