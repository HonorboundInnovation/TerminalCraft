package com.malice.terminalcraft.integration;

import com.mojang.logging.LogUtils;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Provider-neutral chemical telemetry contract. Resource IDs are discovered from live tanks; the
 * registry never contains a static chemical allowlist.
 */
public final class OptionalChemicalStorageRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

    private OptionalChemicalStorageRegistry() {}

    public static void register(Provider provider) {
        Objects.requireNonNull(provider, "provider");
        if (!PROVIDERS.contains(provider)) PROVIDERS.add(provider);
    }

    public static Optional<ChemicalStorage> resolve(BlockEntity blockEntity, Direction side) {
        Objects.requireNonNull(blockEntity, "blockEntity");
        for (Provider provider : PROVIDERS) {
            try {
                Optional<ChemicalStorage> storage = Objects.requireNonNull(
                        provider.resolve(blockEntity, side), "optional chemical storage");
                if (storage.isPresent()) return storage;
            } catch (RuntimeException | LinkageError exception) {
                LOGGER.error("Optional chemical provider failed for {}; continuing without it",
                        blockEntity.getType(), exception);
            }
        }
        return Optional.empty();
    }

    @FunctionalInterface
    public interface ChemicalStorage {
        List<Tank> snapshot();
    }

    @FunctionalInterface
    public interface Provider {
        Optional<ChemicalStorage> resolve(BlockEntity blockEntity, Direction side);
    }

    /** One immutable live-tank sample. Empty tanks use an empty resource ID. */
    public record Tank(String family, int tank, String resourceId, long amount,
                       long capacity, String unit) {
        public Tank {
            family = identifier(family, "family");
            if (tank < 0) throw new IllegalArgumentException("chemical tank index must not be negative");
            resourceId = Objects.requireNonNull(resourceId, "resourceId").trim().toLowerCase(Locale.ROOT);
            if (!resourceId.isEmpty() && !resourceId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                throw new IllegalArgumentException("invalid chemical resource id");
            }
            amount = Math.max(0, amount);
            capacity = Math.max(0, capacity);
            unit = identifier(unit, "unit");
        }

        private static String identifier(String value, String label) {
            String normalized = Objects.requireNonNull(value, label).trim().toLowerCase(Locale.ROOT);
            if (!normalized.matches("[a-z][a-z0-9_.-]{0,31}")) {
                throw new IllegalArgumentException("invalid chemical " + label);
            }
            return normalized;
        }
    }
}
