package com.malice.terminalcraft.network;

/** Headless validation coverage for persistent logical RedNet network names. */
public final class RednetNetworkNameTest {
    private RednetNetworkNameTest() {}

    public static void main(String[] args) {
        check(RednetNetworkName.normalize("  Factory-LAN-01 ").orElseThrow().equals("factory-lan-01"),
                "network names must trim and canonicalize to lower case");
        check(RednetNetworkName.normalize("a").orElseThrow().equals("a"),
                "single-character names must be accepted");
        check(RednetNetworkName.normalize("").isEmpty(), "blank names must fail validation");
        check(RednetNetworkName.normalize("-factory").isEmpty(), "leading hyphens must fail");
        check(RednetNetworkName.normalize("factory-").isEmpty(), "trailing hyphens must fail");
        check(RednetNetworkName.normalize("factory.lan").isEmpty(), "punctuation must fail");
        check(RednetNetworkName.normalize("x".repeat(RednetNetworkName.MAX_LENGTH + 1)).isEmpty(),
                "oversized names must fail");

        System.out.println("RednetNetworkNameTest: all tests passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
