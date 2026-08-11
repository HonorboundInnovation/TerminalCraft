package com.malice.terminalcraft.integration.create;

import com.malice.terminalcraft.sensor.SensorChannel;
import com.malice.terminalcraft.sensor.SensorKind;
import com.malice.terminalcraft.sensor.SensorQuality;
import com.malice.terminalcraft.sensor.SensorReading;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlockEntity;
import com.simibubi.create.content.redstone.link.RedstoneLinkBlockEntity;
import com.simibubi.create.content.redstone.thresholdSwitch.ThresholdSwitchBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Optional;

/** PLC/SCADA sensor translation for Create's public block-entity telemetry. */
final class CreateSensorProbe {
    private CreateSensorProbe() {}

    static Optional<SensorReading> read(ServerLevel level, BlockPos target, Direction accessSide,
                                        SensorChannel channel, long time) {
        BlockEntity blockEntity = level.getBlockEntity(target);
        if (channel.kind() == SensorKind.KINETIC && blockEntity instanceof KineticBlockEntity kinetic) {
            return Optional.ofNullable(kinetic(channel, kinetic, time));
        }
        if (channel.kind() == SensorKind.MACHINE) {
            if (blockEntity instanceof ThresholdSwitchBlockEntity threshold) {
                SensorReading reading = threshold(channel, threshold, time);
                if (reading != null) return Optional.of(reading);
            }
            if (blockEntity instanceof RedstoneLinkBlockEntity link) {
                SensorReading reading = link(channel, link, time);
                if (reading != null) return Optional.of(reading);
            }
            if (blockEntity instanceof SequencedGearshiftBlockEntity sequencer) {
                SensorReading reading = sequencer(channel, sequencer, time);
                if (reading != null) return Optional.of(reading);
            }
            if (blockEntity instanceof KineticBlockEntity kinetic) {
                return Optional.ofNullable(machineKinetic(channel, kinetic, time));
            }
        }
        return Optional.empty();
    }

    private static SensorReading kinetic(SensorChannel channel, KineticBlockEntity kinetic, long time) {
        float speed = kinetic.getSpeed();
        return switch (channel.metric()) {
            case "speed", "rotation_speed", "rpm" -> numeric(channel, speed, "rpm", time);
            case "absolute_speed", "abs_speed" -> numeric(channel, Math.abs(speed), "rpm", time);
            case "theoretical_speed" -> numeric(channel, kinetic.getTheoreticalSpeed(), "rpm", time);
            case "direction" -> numeric(channel, Float.compare(speed, 0), "direction", time);
            case "overstressed", "fault" -> bool(channel, kinetic.isOverStressed(), time);
            case "stress", "stress_impact" -> numeric(channel, kinetic.calculateStressApplied(), "su", time);
            case "capacity", "added_capacity" -> numeric(channel, kinetic.calculateAddedStressCapacity(), "su", time);
            case "network", "network_id" -> SensorReading.text(channel.name(), channel.kind(), channel.metric(),
                    kinetic.network == null ? "" : kinetic.network.toString(), time);
            case "network_stress" -> isStressGauge(kinetic)
                    ? numeric(channel, stressValue(kinetic, "getNetworkStress"), "su", time)
                    : unavailable(channel, "target is not a Create Stressometer", time);
            case "network_capacity" -> isStressGauge(kinetic)
                    ? numeric(channel, stressValue(kinetic, "getNetworkCapacity"), "su", time)
                    : unavailable(channel, "target is not a Create Stressometer", time);
            case "stress_percent" -> isStressGauge(kinetic)
                    ? numeric(channel, stressValue(kinetic, "getNetworkCapacity") <= 0 ? 0
                    : stressValue(kinetic, "getNetworkStress") * 100.0
                    / stressValue(kinetic, "getNetworkCapacity"), "percent", time)
                    : unavailable(channel, "target is not a Create Stressometer", time);
            default -> null;
        };
    }

    private static SensorReading threshold(SensorChannel channel, ThresholdSwitchBlockEntity threshold,
                                           long time) {
        return switch (channel.metric()) {
            case "amount", "current", "stock" -> numeric(channel, threshold.getStockLevel(),
                    threshold.inStacks ? "items" : "value", time);
            case "minimum", "min" -> numeric(channel, threshold.getMinLevel(), "value", time);
            case "maximum", "max", "capacity" -> numeric(channel, threshold.getMaxLevel(), "value", time);
            case "fill", "fill_percent", "progress" -> numeric(channel,
                    threshold.getLevelForDisplay() * 100.0, "percent", time);
            case "powered", "active", "running" -> bool(channel, threshold.isPowered(), time);
            case "inverted" -> bool(channel, threshold.isInverted(), time);
            default -> null;
        };
    }

    private static SensorReading link(SensorChannel channel, RedstoneLinkBlockEntity link, long time) {
        return switch (channel.metric()) {
            case "signal", "level" -> numeric(channel, link.getSignal(), "signal", time);
            case "received", "received_signal" -> numeric(channel, link.getReceivedSignal(), "signal", time);
            case "active", "powered" -> bool(channel, link.getSignal() > 0 || link.getReceivedSignal() > 0, time);
            default -> null;
        };
    }

    private static SensorReading sequencer(SensorChannel channel,
                                           SequencedGearshiftBlockEntity sequencer, long time) {
        return switch (channel.metric()) {
            case "active", "running" -> bool(channel, !sequencer.isIdle(), time);
            case "idle" -> bool(channel, sequencer.isIdle(), time);
            case "modifier" -> numeric(channel, sequencer.getModifier(), "ratio", time);
            case "instructions" -> numeric(channel, sequencer.getInstructions().size(), "steps", time);
            case "speed", "rpm" -> numeric(channel, sequencer.getSpeed(), "rpm", time);
            default -> null;
        };
    }

    private static SensorReading machineKinetic(SensorChannel channel, KineticBlockEntity kinetic,
                                                long time) {
        return switch (channel.metric()) {
            case "active", "running" -> bool(channel, kinetic.getSpeed() != 0 && !kinetic.isOverStressed(), time);
            case "fault", "overstressed" -> bool(channel, kinetic.isOverStressed(), time);
            case "speed", "rpm" -> numeric(channel, kinetic.getSpeed(), "rpm", time);
            default -> null;
        };
    }

    private static SensorReading numeric(SensorChannel channel, double value, String unit, long time) {
        return SensorReading.numeric(channel.name(), channel.kind(), channel.metric(), value, unit, time);
    }

    private static SensorReading bool(SensorChannel channel, boolean value, long time) {
        return numeric(channel, value ? 1 : 0, "boolean", time);
    }

    private static SensorReading unavailable(SensorChannel channel, String detail, long time) {
        return SensorReading.unavailable(channel.name(), channel.kind(), channel.metric(),
                SensorQuality.UNSUPPORTED, detail, time);
    }

    private static boolean isStressGauge(Object value) {
        return value != null && "com.simibubi.create.content.kinetics.gauge.StressGaugeBlockEntity"
                .equals(value.getClass().getName());
    }

    private static float stressValue(Object gauge, String method) {
        try {
            Object value = gauge.getClass().getMethod(method).invoke(gauge);
            return value instanceof Number number ? number.floatValue() : 0;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Create stress gauge telemetry is unavailable", exception);
        }
    }
}
