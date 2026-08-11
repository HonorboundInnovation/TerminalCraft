package com.malice.terminalcraft.integration;

import com.malice.terminalcraft.device.DeviceEndpoint;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/** Absence-safe projection point for bounded nearby entity devices supplied by optional mods. */
public final class OptionalNearbyDeviceEndpointRegistry {
    public static final int MAX_ENDPOINTS = 32;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

    private OptionalNearbyDeviceEndpointRegistry() {}

    public static void register(Provider provider) {
        Objects.requireNonNull(provider, "provider");
        if (!PROVIDERS.contains(provider)) PROVIDERS.add(provider);
    }

    public static List<DeviceEndpoint> project(ServerLevel level, BlockPos hostPosition) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(hostPosition, "hostPosition");
        List<DeviceEndpoint> endpoints = new ArrayList<>();
        for (Provider provider : PROVIDERS) {
            try {
                for (DeviceEndpoint endpoint : List.copyOf(provider.project(level, hostPosition.immutable()))) {
                    if (endpoint == null || endpoints.size() >= MAX_ENDPOINTS) break;
                    endpoints.add(endpoint);
                }
            } catch (RuntimeException | LinkageError exception) {
                LOGGER.error("Optional nearby endpoint provider failed at {}; continuing", hostPosition, exception);
            }
            if (endpoints.size() >= MAX_ENDPOINTS) break;
        }
        return List.copyOf(endpoints);
    }

    @FunctionalInterface
    public interface Provider {
        List<DeviceEndpoint> project(ServerLevel level, BlockPos hostPosition);
    }
}
