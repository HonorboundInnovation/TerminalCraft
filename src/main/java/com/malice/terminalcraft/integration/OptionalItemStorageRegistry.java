package com.malice.terminalcraft.integration;

import com.malice.terminalcraft.device.GenericItemStorage;
import com.mojang.logging.LogUtils;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** Absence-safe registry for optional logical item-storage query implementations. */
public final class OptionalItemStorageRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

    private OptionalItemStorageRegistry() {}

    public static void register(Provider provider) {
        Objects.requireNonNull(provider, "provider");
        if (!PROVIDERS.contains(provider)) PROVIDERS.add(provider);
    }

    public static Optional<GenericItemStorage> resolve(BlockEntity blockEntity) {
        for (Provider provider : PROVIDERS) {
            try {
                Optional<GenericItemStorage> storage = provider.resolve(blockEntity);
                if (storage.isPresent()) return storage;
            } catch (RuntimeException | LinkageError exception) {
                LOGGER.error("Optional logical storage provider failed for {}; using generic Forge storage",
                        blockEntity.getType(), exception);
            }
        }
        return Optional.empty();
    }

    @FunctionalInterface
    public interface Provider {
        Optional<GenericItemStorage> resolve(BlockEntity blockEntity);
    }
}
