package com.malice.terminalcraft.network;

import java.util.UUID;

/** Focused contract coverage for versioned protocols and typed service endpoints. */
public final class RednetProtocolTest {
    private RednetProtocolTest() {}

    public static void main(String[] args) {
        RednetProtocol protocol = new RednetProtocol("TerminalCraft:Status/V1", 2, "Application/Json");
        check(protocol.id().equals("terminalcraft:status/v1"), "protocol id must canonicalize");
        check(protocol.payloadType().equals("application/json"), "payload type must canonicalize");
        RednetAddress address = new RednetAddress(UUID.randomUUID(), "factory");
        RednetServiceEndpoint endpoint = new RednetServiceEndpoint(" Status-API ", address, 8080, protocol);
        check(endpoint.name().equals("status-api") && endpoint.address().equals(address)
                && endpoint.protocol().equals(protocol), "typed endpoint must preserve service metadata");

        check(RednetProtocol.parse(null, 1, "text/plain").isEmpty(), "null protocol must fail closed");
        check(RednetProtocol.parse("missing-namespace", 1, "text/plain").isEmpty(),
                "unqualified protocol must fail closed");
        check(RednetProtocol.parse("terminalcraft:test", 0, "text/plain").isEmpty(),
                "zero protocol version must fail closed");
        check(RednetProtocol.parse("terminalcraft:test", 1, "bad type").isEmpty(),
                "whitespace payload type must fail closed");
        expectFailure(() -> new RednetServiceEndpoint("bad.name", address, 1, protocol));
        expectFailure(() -> new RednetServiceEndpoint("valid", address, 65_536, protocol));

        System.out.println("RednetProtocolTest: all tests passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expectFailure(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected failure");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
