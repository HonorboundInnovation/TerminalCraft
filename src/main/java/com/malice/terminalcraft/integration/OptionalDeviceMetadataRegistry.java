package com.malice.terminalcraft.integration;

import com.mojang.logging.LogUtils;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** Absence-safe registry for optional descriptor metadata; providers never own device mutation. */
public final class OptionalDeviceMetadataRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

    private OptionalDeviceMetadataRegistry() {}

    public static void register(Provider provider) {
        if (!PROVIDERS.contains(provider)) PROVIDERS.add(provider);
    }

    public static Optional<OptionalDeviceMetadata> describe(BlockEntity blockEntity) {
        for (Provider provider : PROVIDERS) {
            try {
                Optional<OptionalDeviceMetadata> metadata = provider.describe(blockEntity);
                if (metadata.isPresent()) return metadata;
            } catch (RuntimeException | LinkageError exception) {
                LOGGER.error("Optional device metadata provider failed for {}; continuing with generic capabilities",
                        blockEntity.getType(), exception);
            }
        }
        return Optional.empty();
    }

    @FunctionalInterface
    public interface Provider {
        Optional<OptionalDeviceMetadata> describe(BlockEntity blockEntity);
    }
}
