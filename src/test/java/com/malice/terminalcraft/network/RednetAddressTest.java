package com.malice.terminalcraft.network;

import java.util.UUID;

/** Focused contract tests for stable RedNet addresses and mutable aliases. */
public final class RednetAddressTest {
    private RednetAddressTest() {}

    public static void main(String[] args) {
        UUID id = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        RednetAddress named = new RednetAddress(id, " Factory-Host ");
        check(named.deviceId().equals(id), "stable identity must be retained");
        check(named.hostname().equals("factory-host"), "hostname must canonicalize");
        check(named.displayName().equals("factory-host"), "alias must be preferred for display");
        check(RednetAddress.parse(named.encoded()).orElseThrow().equals(named),
                "explicit address must round-trip");

        RednetAddress anonymous = new RednetAddress(id, "");
        check(anonymous.displayName().equals(id.toString()), "UUID must back an absent alias");
        check(RednetAddress.parse(anonymous.encoded()).orElseThrow().equals(anonymous),
                "anonymous explicit address must round-trip");

        RednetAddress renamed = new RednetAddress(id, "warehouse-host");
        check(renamed.deviceId().equals(named.deviceId()) && !renamed.hostname().equals(named.hostname()),
                "renaming must not change identity");

        check(RednetAddress.parse(null).isEmpty(), "null must fail closed");
        check(RednetAddress.parse("factory-host").isEmpty(), "ambiguous aliases must not parse as identity");
        check(RednetAddress.parse("rednet:not-a-uuid/name").isEmpty(), "malformed UUID must fail closed");
        check(RednetAddress.parse("rednet:" + id + "/").isEmpty(), "empty encoded alias must fail closed");
        expectFailure(() -> new RednetAddress(id, "invalid.name"));
        expectFailure(() -> new RednetAddress(null, "host"));

        System.out.println("RednetAddressTest: all tests passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expectFailure(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected failure");
        } catch (IllegalArgumentException | NullPointerException expected) {
            // Expected.
        }
    }
}
