package com.malice.terminalcraft.integration;

import com.malice.terminalcraft.sensor.SensorChannel;
import com.malice.terminalcraft.sensor.SensorReading;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** Optional-mod telemetry hook used before the implementation-neutral sensor fallbacks. */
public final class OptionalSensorProbeRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

    private OptionalSensorProbeRegistry() {}

    public static void register(Provider provider) {
        Objects.requireNonNull(provider, "provider");
        if (!PROVIDERS.contains(provider)) PROVIDERS.add(provider);
    }

    public static Optional<SensorReading> read(ServerLevel level, BlockPos target,
                                               Direction accessSide, SensorChannel channel,
                                               long gameTime) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(channel, "channel");
        for (Provider provider : PROVIDERS) {
            try {
                Optional<SensorReading> reading = Objects.requireNonNull(
                        provider.read(level, target, accessSide, channel, gameTime),
                        "optional sensor reading");
                if (reading.isPresent()) return reading;
            } catch (RuntimeException | LinkageError exception) {
                LOGGER.error("Optional sensor provider failed at {}; continuing with generic probing",
                        target, exception);
            }
        }
        return Optional.empty();
    }

    @FunctionalInterface
    public interface Provider {
        Optional<SensorReading> read(ServerLevel level, BlockPos target, Direction accessSide,
                                     SensorChannel channel, long gameTime);
    }
}
