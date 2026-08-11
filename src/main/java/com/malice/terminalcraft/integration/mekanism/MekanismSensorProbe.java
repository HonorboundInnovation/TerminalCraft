package com.malice.terminalcraft.integration.mekanism;

import com.malice.terminalcraft.sensor.SensorChannel;
import com.malice.terminalcraft.sensor.SensorKind;
import com.malice.terminalcraft.sensor.SensorQuality;
import com.malice.terminalcraft.sensor.SensorReading;
import mekanism.api.energy.IStrictEnergyHandler;
import mekanism.api.heat.IHeatHandler;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.prefab.TileEntityProgressMachine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Optional;

/** PLC/SCADA sensor translation for Mekanism native energy, heat, and machine state. */
final class MekanismSensorProbe {
    private static final int MAX_CONTAINERS = 64;

    private MekanismSensorProbe() {}

    static Optional<SensorReading> read(ServerLevel level, BlockPos target, Direction side,
                                        SensorChannel channel, long time) {
        BlockEntity blockEntity = level.getBlockEntity(target);
        if (!(blockEntity instanceof TileEntityMekanism tile)) return Optional.empty();
        return switch (channel.kind()) {
            case ENERGY -> Optional.ofNullable(energy(tile, side, channel, time));
            case MACHINE -> Optional.ofNullable(machine(tile, side, channel, time));
            default -> Optional.empty();
        };
    }

    private static SensorReading energy(TileEntityMekanism tile, Direction side,
                                        SensorChannel channel, long time) {
        IStrictEnergyHandler handler = MekanismCapabilityView.energy(tile, side);
        if (handler == null) return null;
        double stored = 0;
        double capacity = 0;
        int count = Math.min(handler.getEnergyContainerCount(), MAX_CONTAINERS);
        for (int container = 0; container < count; container++) {
            stored = saturatingDouble(stored, handler.getEnergy(container).doubleValue());
            capacity = saturatingDouble(capacity, handler.getMaxEnergy(container).doubleValue());
        }
        return switch (channel.metric()) {
            case "amount", "stored", "energy" -> numeric(channel, stored, "J", time);
            case "capacity", "max" -> numeric(channel, capacity, "J", time);
            case "fill", "fill_percent" -> numeric(channel,
                    capacity <= 0 ? 0 : stored * 100.0 / capacity, "percent", time);
            case "containers" -> numeric(channel, count, "containers", time);
            case "present" -> numeric(channel, stored > 0 ? 1 : 0, "boolean", time);
            default -> null;
        };
    }

    private static SensorReading machine(TileEntityMekanism tile, Direction side,
                                         SensorChannel channel, long time) {
        if (tile instanceof TileEntityProgressMachine progress) {
            switch (channel.metric()) {
                case "progress" -> { return numeric(channel, progress.getScaledProgress() * 100.0, "percent", time); }
                case "operating_ticks" -> { return numeric(channel, progress.getOperatingTicks(), "ticks", time); }
                case "ticks_required" -> { return numeric(channel, progress.getTicksRequired(), "ticks", time); }
            }
        }
        if (channel.metric().equals("temperature") || channel.metric().equals("heat_capacity")) {
            IHeatHandler heat = MekanismCapabilityView.heat(tile, side);
            if (heat == null) return unavailable(channel, "Mekanism heat capability is unavailable on this side", time);
            return channel.metric().equals("temperature")
                    ? numeric(channel, heat.getTotalTemperature(), "K", time)
                    : numeric(channel, heat.getTotalHeatCapacity(), "J/K", time);
        }
        return switch (channel.metric()) {
            case "active", "running" -> numeric(channel, tile.getActive() ? 1 : 0, "boolean", time);
            case "powered" -> numeric(channel, tile.isPowered() ? 1 : 0, "boolean", time);
            case "redstone", "redstone_level" -> numeric(channel, tile.getCurrentRedstoneLevel(), "signal", time);
            case "fault" -> numeric(channel, 0, "boolean", time);
            default -> null;
        };
    }

    private static double saturatingDouble(double left, double right) {
        double value = left + Math.max(0, right);
        return Double.isFinite(value) ? value : Double.MAX_VALUE;
    }

    private static SensorReading numeric(SensorChannel channel, double value, String unit, long time) {
        return SensorReading.numeric(channel.name(), channel.kind(), channel.metric(), value, unit, time);
    }

    private static SensorReading unavailable(SensorChannel channel, String detail, long time) {
        return SensorReading.unavailable(channel.name(), channel.kind(), channel.metric(),
                SensorQuality.UNSUPPORTED, detail, time);
    }
}
