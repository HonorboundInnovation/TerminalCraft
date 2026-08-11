package com.malice.terminalcraft.integration;

import com.malice.terminalcraft.device.DeviceEndpoint;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Absence-safe projection point for optional mods whose useful machines do not necessarily expose
 * a standard Forge item, fluid, or energy capability.
 */
public final class OptionalDeviceEndpointRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

    private OptionalDeviceEndpointRegistry() {}

    public static void register(Provider provider) {
        Objects.requireNonNull(provider, "provider");
        if (!PROVIDERS.contains(provider)) PROVIDERS.add(provider);
    }

    public static Optional<DeviceEndpoint> project(Context context) {
        Objects.requireNonNull(context, "context");
        for (Provider provider : PROVIDERS) {
            try {
                Optional<DeviceEndpoint> endpoint = Objects.requireNonNull(
                        provider.project(context), "optional endpoint projection");
                if (endpoint.isPresent()) return endpoint;
            } catch (RuntimeException | LinkageError exception) {
                LOGGER.error("Optional device endpoint provider failed for {}; continuing without its projection",
                        context.blockEntity().getType(), exception);
            }
        }
        return Optional.empty();
    }

    /** TerminalCraft-owned data only, so this record remains safe when every optional mod is absent. */
    public record Context(ServerLevel level, BlockPos hostPosition, BlockPos targetPosition,
                          Direction accessSide, BlockEntity blockEntity, UUID deviceId,
                          String address) {
        public Context {
            level = Objects.requireNonNull(level, "level");
            hostPosition = Objects.requireNonNull(hostPosition, "hostPosition").immutable();
            targetPosition = Objects.requireNonNull(targetPosition, "targetPosition").immutable();
            accessSide = Objects.requireNonNull(accessSide, "accessSide");
            blockEntity = Objects.requireNonNull(blockEntity, "blockEntity");
            deviceId = Objects.requireNonNull(deviceId, "deviceId");
            address = Objects.requireNonNull(address, "address");
        }

        public boolean isCurrent() {
            return level.hasChunkAt(targetPosition) && !blockEntity.isRemoved()
                    && level.getBlockEntity(targetPosition) == blockEntity;
        }
    }

    @FunctionalInterface
    public interface Provider {
        Optional<DeviceEndpoint> project(Context context);
    }
}
