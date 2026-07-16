package com.malice.terminalcraft.integration;

import com.malice.terminalcraft.device.GenericCraftingService;
import com.mojang.logging.LogUtils;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** Absence-safe registry for optional native crafting-service adapters. */
public final class OptionalCraftingServiceRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

    private OptionalCraftingServiceRegistry() {}

    public static void register(Provider provider) {
        Objects.requireNonNull(provider, "provider");
        if (!PROVIDERS.contains(provider)) PROVIDERS.add(provider);
    }

    public static Optional<GenericCraftingService> resolve(BlockEntity blockEntity) {
        for (Provider provider : PROVIDERS) {
            try {
                Optional<GenericCraftingService> service = provider.resolve(blockEntity);
                if (service.isPresent()) return service;
            } catch (RuntimeException | LinkageError exception) {
                LOGGER.error("Optional crafting provider failed for {}; omitting crafting service",
                        blockEntity.getType(), exception);
            }
        }
        return Optional.empty();
    }

    @FunctionalInterface
    public interface Provider {
        Optional<GenericCraftingService> resolve(BlockEntity blockEntity);
    }
}
