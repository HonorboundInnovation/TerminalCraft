package com.malice.terminalcraft.integration;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Headless tests for optional-mod absence safety, isolation, and idempotence. */
public final class OptionalIntegrationsTest {
    private OptionalIntegrationsTest() {}

    public static void main(String[] args) {
        absentAdapterIsNeverResolved();
        installedAdaptersInitializeOnce();
        brokenAdapterDoesNotBlockOthers();
        invalidDefinitionsAreRejected();
        System.out.println("Optional integration registry tests: OK");
    }

    private static void absentAdapterIsNeverResolved() {
        AtomicInteger resolutions = new AtomicInteger();
        OptionalIntegrations.initialize(modId -> false,
                List.of(definition("test_absent", "missingmod")), className -> {
                    resolutions.incrementAndGet();
                    throw new AssertionError("absent adapter must not be resolved");
                });
        assertEquals(0, resolutions.get(), "absent adapter resolution count");
    }

    private static void installedAdaptersInitializeOnce() {
        AtomicInteger resolutions = new AtomicInteger();
        AtomicInteger initializations = new AtomicInteger();
        OptionalIntegrations.Definition definition = definition("test_present", "presentmod");
        OptionalIntegrations.IntegrationResolver resolver = className -> {
            resolutions.incrementAndGet();
            return initializations::incrementAndGet;
        };

        OptionalIntegrations.initialize("presentmod"::equals, List.of(definition), resolver);
        OptionalIntegrations.initialize("presentmod"::equals, List.of(definition), resolver);

        assertEquals(1, resolutions.get(), "present adapter resolved once");
        assertEquals(1, initializations.get(), "present adapter initialized once");
    }

    private static void brokenAdapterDoesNotBlockOthers() {
        AtomicInteger healthyInitializations = new AtomicInteger();
        List<OptionalIntegrations.Definition> definitions = List.of(
                definition("test_broken", "installedmod"),
                definition("test_healthy", "installedmod"));

        OptionalIntegrations.initialize(modId -> true, definitions, className -> {
            if (className.endsWith("Broken")) return () -> { throw new Exception("expected failure"); };
            return healthyInitializations::incrementAndGet;
        });

        assertEquals(1, healthyInitializations.get(), "healthy adapter runs after a failure");
    }

    private static void invalidDefinitionsAreRejected() {
        assertThrows(() -> new OptionalIntegrations.Definition("Bad Id", "mod", "test.Adapter"),
                "integration id validation");
        assertThrows(() -> new OptionalIntegrations.Definition("valid", "Bad Mod", "test.Adapter"),
                "mod id validation");
        assertThrows(() -> new OptionalIntegrations.Definition("valid", "mod", " "),
                "implementation class validation");
    }

    private static OptionalIntegrations.Definition definition(String id, String modId) {
        String suffix = id.equals("test_broken") ? "Broken" : "Healthy";
        return new OptionalIntegrations.Definition(id, modId, "test.integration." + suffix);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertThrows(Runnable action, String message) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError(message + ": expected exception");
    }
}
