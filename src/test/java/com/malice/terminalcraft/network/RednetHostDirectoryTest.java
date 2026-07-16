package com.malice.terminalcraft.network;

import java.util.List;
import java.util.UUID;

/** Headless coverage for RedNet host validation, duplicate handling, rename, and bounds. */
public final class RednetHostDirectoryTest {
    private RednetHostDirectoryTest() {}

    public static void main(String[] args) {
        check(RednetHostName.normalize("  Factory-01 ").orElseThrow().equals("factory-01"),
                "host names must canonicalize to lower case");
        check(RednetHostName.normalize("-invalid").isEmpty(), "leading hyphen must fail");
        check(RednetHostName.normalize("invalid.name").isEmpty(), "punctuation must fail");
        check(RednetHostName.normalize("x".repeat(RednetHostName.MAX_LENGTH + 1)).isEmpty(),
                "oversized name must fail");

        RednetHostDirectory directory = new RednetHostDirectory();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        check(directory.registerDetailed(first, "factory").status()
                        == RednetRegistrationResult.Status.CREATED,
                "first registration must report creation");
        check(directory.registerDetailed(first, "FACTORY").status()
                        == RednetRegistrationResult.Status.UNCHANGED,
                "identical canonical registration must report unchanged");
        RednetRegistrationResult conflict = directory.registerDetailed(second, "FACTORY");
        check(conflict.status() == RednetRegistrationResult.Status.NAME_CONFLICT
                        && conflict.name().equals("factory") && !conflict.accepted(),
                "canonical duplicate must report a non-accepted conflict");
        check(directory.registerDetailed(null, "valid").status()
                        == RednetRegistrationResult.Status.INVALID,
                "invalid host registration must be structured");
        check(directory.resolve("Factory").orElseThrow().equals(first), "lookup must be canonical");
        check(directory.registerDetailed(first, "warehouse").status()
                        == RednetRegistrationResult.Status.UPDATED,
                "owner rename must report update");
        check(directory.resolve("factory").isEmpty(), "rename must release old name");
        check(directory.name(first).equals("warehouse"), "reverse lookup must track rename");
        check(directory.register(second, "alpha"), "released unrelated name must register");
        check(directory.names(1).equals(List.of("alpha")), "listing must be sorted and bounded");
        directory.unregister(first);
        check(directory.resolve("warehouse").isEmpty(), "unregister must remove both indexes");

        System.out.println("RednetHostDirectoryTest: all tests passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
