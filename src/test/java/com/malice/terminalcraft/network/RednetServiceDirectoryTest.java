package com.malice.terminalcraft.network;

import java.util.List;
import java.util.UUID;

/** Headless coverage for bounded unique RedNet service registration. */
public final class RednetServiceDirectoryTest {
    private RednetServiceDirectoryTest() {}

    public static void main(String[] args) {
        RednetServiceDirectory directory = new RednetServiceDirectory();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        check(directory.registerDetailed(first, "Status-API", 80,
                        RednetServiceDirectory.LEGACY_PROTOCOL).status()
                        == RednetRegistrationResult.Status.CREATED,
                "first service must report creation");
        check(directory.registerDetailed(first, "status-api", 80,
                        RednetServiceDirectory.LEGACY_PROTOCOL).status()
                        == RednetRegistrationResult.Status.UNCHANGED,
                "identical service registration must report unchanged");
        RednetServiceDirectory.Service service = directory.resolve("status-api").orElseThrow();
        check(service.modemId().equals(first) && service.port() == 80,
                "service resolution must preserve owner and port");
        check(directory.registerDetailed(second, "STATUS-API", 81,
                        RednetServiceDirectory.LEGACY_PROTOCOL).status()
                        == RednetRegistrationResult.Status.NAME_CONFLICT,
                "canonical duplicate owned by another modem must report conflict");
        check(directory.registerDetailed(first, "status-api", 82,
                        RednetServiceDirectory.LEGACY_PROTOCOL).status()
                        == RednetRegistrationResult.Status.UPDATED,
                "owner service change must report update");
        check(directory.resolve("status-api").orElseThrow().port() == 82,
                "updated port must replace the old registration");
        RednetProtocol protocol = new RednetProtocol("terminalcraft:status", 2, "application/json");
        check(directory.register(first, "status-api", 83, protocol),
                "owner must be able to add a typed protocol contract");
        RednetServiceDirectory.Service typed = directory.resolve("status-api").orElseThrow();
        check(typed.port() == 83 && typed.protocol().equals(protocol),
                "typed service resolution must preserve protocol metadata");
        check(!directory.register(first, "broken", 84, null),
                "null protocol metadata must fail closed");
        check(directory.register(second, "inventory", 90), "second unique service must register");
        check(directory.services(1).stream().map(RednetServiceDirectory.Service::name).toList()
                        .equals(List.of("inventory")),
                "service listing must be sorted and bounded");
        check(!directory.unregister(second, "status-api"), "non-owner must not unregister a service");
        check(directory.unregister(first, "status-api"), "owner must unregister its service");
        directory.unregisterAll(second);
        check(directory.resolve("inventory").isEmpty(), "unregisterAll must remove owned services");
        check(!directory.register(first, "invalid.service", 80), "invalid names must fail");
        check(!directory.register(first, "valid", -1), "invalid ports must fail");

        System.out.println("RednetServiceDirectoryTest: all tests passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
