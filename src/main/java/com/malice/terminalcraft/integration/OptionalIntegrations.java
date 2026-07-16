package com.malice.terminalcraft.integration;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** Central, absence-safe registration boundary for optional technology-mod adapters. */
public final class OptionalIntegrations {
    private static final Logger LOGGER = LogUtils.getLogger();

    /* Adapter classes are named rather than linked directly so optional API types are not loaded
     * until Forge confirms the corresponding mod is present. */
    private static final List<Definition> DEFINITIONS = List.of(
            new Definition("sophisticated_storage_metadata", "sophisticatedstorage",
                    "com.malice.terminalcraft.integration.sophisticated.SophisticatedStorageIntegration"),
            new Definition("sophisticated_backpacks_metadata", "sophisticatedbackpacks",
                    "com.malice.terminalcraft.integration.sophisticated.SophisticatedBackpacksIntegration"),
            new Definition("storage_drawers_metadata", "storagedrawers",
                    "com.malice.terminalcraft.integration.storagedrawers.StorageDrawersIntegration"),
            new Definition("refined_storage_bridge", "refinedstorage",
                    "com.malice.terminalcraft.integration.refinedstorage.RefinedStorageIntegration")
    );
    private static final Set<String> INITIALIZED = new HashSet<>();

    private OptionalIntegrations() {}

    /** Initializes every installed adapter once without allowing one broken adapter to stop startup. */
    public static void initialize() {
        initialize(ModList.get()::isLoaded, DEFINITIONS, OptionalIntegrations::instantiate);
    }

    static void initialize(Predicate<String> modPresent, List<Definition> definitions,
                           IntegrationResolver resolver) {
        Objects.requireNonNull(modPresent, "modPresent");
        Objects.requireNonNull(definitions, "definitions");
        Objects.requireNonNull(resolver, "resolver");

        for (Definition definition : List.copyOf(definitions)) {
            Objects.requireNonNull(definition, "definition");
            if (!modPresent.test(definition.requiredModId())) {
                LOGGER.debug("Skipping optional integration {}: mod {} is absent",
                        definition.id(), definition.requiredModId());
                continue;
            }
            synchronized (INITIALIZED) {
                if (INITIALIZED.contains(definition.id())) continue;
            }
            try {
                resolver.resolve(definition.implementationClass()).initialize();
                synchronized (INITIALIZED) {
                    INITIALIZED.add(definition.id());
                }
                LOGGER.info("Initialized optional integration {} for mod {}",
                        definition.id(), definition.requiredModId());
            } catch (Exception | LinkageError exception) {
                LOGGER.error("Optional integration {} for mod {} failed; TerminalCraft will continue without it",
                        definition.id(), definition.requiredModId(), exception);
            }
        }
    }

    private static OptionalIntegration instantiate(String className) throws Exception {
        try {
            Class<?> type = Class.forName(className, true, OptionalIntegrations.class.getClassLoader());
            if (!OptionalIntegration.class.isAssignableFrom(type)) {
                throw new IllegalArgumentException(className + " does not implement OptionalIntegration");
            }
            return (OptionalIntegration) type.getDeclaredConstructor().newInstance();
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) throw checked;
            if (cause instanceof LinkageError linkage) throw linkage;
            throw exception;
        }
    }

    /** Metadata contains no optional API types and is therefore safe to load in a minimal profile. */
    public record Definition(String id, String requiredModId, String implementationClass) {
        public Definition {
            id = requireIdentifier(id, "integration id");
            requiredModId = requireIdentifier(requiredModId, "required mod id");
            implementationClass = Objects.requireNonNull(implementationClass, "implementationClass").trim();
            if (implementationClass.isEmpty()) {
                throw new IllegalArgumentException("implementation class must not be blank");
            }
        }

        private static String requireIdentifier(String value, String label) {
            String checked = Objects.requireNonNull(value, label).trim();
            if (!checked.matches("[a-z][a-z0-9_.-]*")) {
                throw new IllegalArgumentException(label + " must be a lowercase identifier");
            }
            return checked;
        }
    }

    @FunctionalInterface
    interface IntegrationResolver {
        OptionalIntegration resolve(String implementationClass) throws Exception;
    }
}
