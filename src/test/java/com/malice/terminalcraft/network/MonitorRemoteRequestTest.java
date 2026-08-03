package com.malice.terminalcraft.network;

import java.util.List;

public final class MonitorRemoteRequestTest {
    private MonitorRemoteRequestTest() {}

    public static void main(String[] args) {
        List<MonitorRemoteRequest> requests = List.of(
                MonitorRemoteRequest.clear(),
                MonitorRemoteRequest.write("Factory online"),
                MonitorRemoteRequest.set(17, "Power: 98% ⚡"),
                MonitorRemoteRequest.title("Main dashboard"),
                MonitorRemoteRequest.palette(0x12AB34, 0x050A05));
        for (MonitorRemoteRequest request : requests) {
            assertEquals(request, MonitorRemoteRequest.decode(request.encode()).orElseThrow(), "round trip");
        }

        assertEmpty(null);
        assertEmpty("2|clear|0|0|0|");
        assertEmpty("1|set|-1|0|0|");
        assertEmpty("1|palette|0|16777216|0|");
        assertEmpty("1|write|0|0|0|not+url+base64");
        assertThrows(() -> MonitorRemoteRequest.write("x".repeat(MonitorRemoteRequest.MAX_TEXT_CHARS + 1)));
        assertThrows(() -> MonitorRemoteRequest.write("first\nsecond"));
        System.out.println("Remote monitor protocol tests: OK");
    }

    private static void assertEmpty(String payload) {
        if (MonitorRemoteRequest.decode(payload).isPresent()) {
            throw new AssertionError("malformed payload was accepted: " + payload);
        }
    }

    private static void assertThrows(Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) throw new AssertionError(message + ": " + actual);
    }
}
