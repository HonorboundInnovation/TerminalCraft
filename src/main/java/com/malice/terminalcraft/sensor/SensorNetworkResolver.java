package com.malice.terminalcraft.sensor;

import com.malice.terminalcraft.blockentity.ModemBlockEntity;
import com.malice.terminalcraft.blockentity.SensorArrayBlockEntity;
import com.malice.terminalcraft.blockentity.StandaloneSensorBlockEntity;
import com.malice.terminalcraft.item.PocketTerminalItem;
import com.malice.terminalcraft.network.RednetInterface;
import com.malice.terminalcraft.network.RednetNetwork;
import com.malice.terminalcraft.network.WiredNetworkTopology;
import com.malice.terminalcraft.shell.PocketShellComputer;
import com.malice.terminalcraft.shell.TerminalHost;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.UUID;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Resolves only loaded sensors that are reachable over an actual cable or wireless link. */
public final class SensorNetworkResolver {
    private SensorNetworkResolver() {}

    public static BlockEntity resolve(TerminalHost host, String selector) {
        if (host == null || !(host.getLevel() instanceof ServerLevel level)
                || host.getBlockPos() == null || selector == null || selector.isBlank()) return null;
        return resolve(level, host.getBlockPos(), host, selector);
    }

    public static BlockEntity resolve(ServerLevel level, BlockPos source, String selector) {
        return resolve(level, source, null, selector);
    }

    /** Returns the sensor only when exactly one loaded network sensor is reachable. */
    public static BlockEntity resolveSingle(TerminalHost host) {
        if (host == null || !(host.getLevel() instanceof ServerLevel level)
                || host.getBlockPos() == null) return null;
        List<BlockEntity> sensors = reachable(level, host.getBlockPos(), host);
        return sensors.size() == 1 ? sensors.get(0) : null;
    }

    /** PLC-friendly automatic discovery when no explicit sensor name was supplied. */
    public static BlockEntity resolveSingle(ServerLevel level, BlockPos source) {
        List<BlockEntity> sensors = reachable(level, source, null);
        return sensors.size() == 1 ? sensors.get(0) : null;
    }

    private static BlockEntity resolve(ServerLevel level, BlockPos source, TerminalHost host, String selector) {
        List<BlockEntity> named = reachable(level, source, host).stream()
                .filter(sensor -> sensorLabel(sensor).equalsIgnoreCase(selector.trim())).toList();
        if (named.size() == 1) return named.get(0);
        if (named.size() > 1) return null;
        UUID targetId = RednetNetwork.resolveAddress(level, selector)
                .map(address -> address.deviceId()).orElse(null);
        if (targetId == null) return null;

        for (RednetInterface target : RednetNetwork.interfaces(level, targetId)) {
            BlockEntity entity = loadedBlockEntity(level, target.position());
            if (!(entity instanceof SensorArrayBlockEntity)
                    && !(entity instanceof StandaloneSensorBlockEntity)) continue;
            if (target.transport() == RednetInterface.Transport.WIRED
                    && WiredNetworkTopology.connected(level, source, target.position())) return entity;
            if (target.transport() == RednetInterface.Transport.WIRELESS
                    && wirelessReachable(level, source, host, target)) return entity;
        }
        return null;
    }

    private static List<BlockEntity> reachable(ServerLevel level, BlockPos source, TerminalHost host) {
        Set<BlockPos> found = new LinkedHashSet<>();
        for (var address : RednetNetwork.addresses(level, 128)) {
            for (RednetInterface target : RednetNetwork.interfaces(level, address.deviceId())) {
                BlockEntity entity = loadedBlockEntity(level, target.position());
                if (!(entity instanceof SensorArrayBlockEntity)
                        && !(entity instanceof StandaloneSensorBlockEntity)) continue;
                boolean reachable = target.transport() == RednetInterface.Transport.WIRED
                        ? WiredNetworkTopology.connected(level, source, target.position())
                        : wirelessReachable(level, source, host, target);
                if (reachable) found.add(target.position());
            }
        }
        List<BlockEntity> result = new ArrayList<>();
        found.stream().sorted(Comparator.<BlockPos>comparingInt(BlockPos::getX)
                        .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ))
                .map(position -> loadedBlockEntity(level, position)).filter(entity -> entity instanceof SensorArrayBlockEntity
                        || entity instanceof StandaloneSensorBlockEntity).forEach(result::add);
        return List.copyOf(result);
    }

    public static List<String> describeReachable(TerminalHost host) {
        if (host == null || !(host.getLevel() instanceof ServerLevel level)
                || host.getBlockPos() == null) return List.of();
        List<BlockEntity> sensors = reachable(level, host.getBlockPos(), host);
        List<String> result = new ArrayList<>();
        for (BlockEntity sensor : sensors) {
            BlockPos pos = sensor.getBlockPos();
            String type = sensor instanceof SensorArrayBlockEntity ? "array" : "standalone";
            long duplicates = sensors.stream().filter(other -> sensorLabel(other)
                    .equalsIgnoreCase(sensorLabel(sensor))).count();
            result.add("name=" + sensorLabel(sensor) + " type=" + type + " pos="
                    + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                    + (duplicates > 1 ? " duplicate-name" : ""));
        }
        return List.copyOf(result);
    }

    private static String sensorLabel(BlockEntity sensor) {
        if (sensor instanceof SensorArrayBlockEntity array) return array.getLabel();
        if (sensor instanceof StandaloneSensorBlockEntity standalone) return standalone.getLabel();
        return "";
    }

    private static BlockEntity loadedBlockEntity(ServerLevel level, BlockPos position) {
        var chunk = level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
        return chunk == null ? null : chunk.getBlockEntity(position);
    }

    private static boolean wirelessReachable(ServerLevel level, BlockPos source, TerminalHost host,
                                             RednetInterface target) {
        if (host instanceof PocketShellComputer pocket && pocket.isStillHolding()) {
            int range = Math.min(PocketTerminalItem.MODEM_RANGE, target.range());
            return source.closerThan(target.position(), Math.max(1, range) + 0.5);
        }
        for (Direction direction : Direction.values()) {
            BlockEntity adjacent = level.getBlockEntity(source.relative(direction));
            if (!(adjacent instanceof ModemBlockEntity modem) || !modem.isWireless()) continue;
            int range = Math.min(modem.getRange(), target.range());
            if (modem.getBlockPos().closerThan(target.position(), Math.max(1, range) + 0.5)) return true;
        }
        return false;
    }
}
