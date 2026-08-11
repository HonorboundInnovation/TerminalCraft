package com.malice.terminalcraft.integration.create;

import com.malice.terminalcraft.device.ContextualDeviceEndpoint;
import com.malice.terminalcraft.device.DeviceCallContext;
import com.malice.terminalcraft.device.DeviceDescriptor;
import com.malice.terminalcraft.device.DeviceEndpoint;
import com.malice.terminalcraft.device.DeviceErrorCode;
import com.malice.terminalcraft.device.DeviceMethodDescriptor;
import com.malice.terminalcraft.device.DeviceParameterDescriptor;
import com.malice.terminalcraft.device.DeviceResult;
import com.malice.terminalcraft.device.DeviceValue;
import com.malice.terminalcraft.device.DeviceValueType;
import com.malice.terminalcraft.integration.OptionalDeviceEndpointRegistry;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlockEntity;
import com.simibubi.create.content.redstone.link.RedstoneLinkBlockEntity;
import com.simibubi.create.content.redstone.thresholdSwitch.ThresholdSwitchBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Additive Create projection sharing the UUID of the adjacent generic Forge-capability endpoint. */
final class CreateDeviceEndpoint implements ContextualDeviceEndpoint {
    private static final DeviceParameterDescriptor SIGNAL = new DeviceParameterDescriptor(
            "signal", DeviceValueType.NUMBER, true, "Integer redstone signal from 0 through 15");
    private static final DeviceParameterDescriptor INVERTED = new DeviceParameterDescriptor(
            "inverted", DeviceValueType.BOOLEAN, true, "Whether the threshold output is inverted");

    private static final DeviceMethodDescriptor KINETIC_STATUS = read(
            "create.kinetic.status", "Returns speed, stress, source, and network telemetry", DeviceValueType.MAP);
    private static final DeviceMethodDescriptor STRESS_STATUS = read(
            "create.stress.status", "Returns kinetic network stress and capacity", DeviceValueType.MAP);
    private static final DeviceMethodDescriptor THRESHOLD_STATUS = read(
            "create.threshold.status", "Returns threshold switch levels and output state", DeviceValueType.MAP);
    private static final DeviceMethodDescriptor THRESHOLD_INVERT = write(
            "create.threshold.inverted.set", "Sets threshold switch output inversion", List.of(INVERTED), DeviceValueType.BOOLEAN);
    private static final DeviceMethodDescriptor LINK_STATUS = read(
            "create.redstone_link.status", "Returns local, transmitted, and received redstone link signals", DeviceValueType.MAP);
    private static final DeviceMethodDescriptor LINK_TRANSMIT = write(
            "create.redstone_link.transmit", "Transmits a bounded signal on the configured Create link frequency",
            List.of(SIGNAL), DeviceValueType.NUMBER);
    private static final DeviceMethodDescriptor SEQUENCER_STATUS = read(
            "create.sequencer.status", "Returns configured instruction count and current sequencer state", DeviceValueType.MAP);
    private static final DeviceMethodDescriptor SEQUENCER_TRIGGER = write(
            "create.sequencer.trigger", "Starts the configured sequence when the gearshift is idle",
            List.of(), DeviceValueType.BOOLEAN);

    private final OptionalDeviceEndpointRegistry.Context context;

    CreateDeviceEndpoint(OptionalDeviceEndpointRegistry.Context context) {
        this.context = context;
    }

    @Override
    public DeviceDescriptor descriptor() {
        BlockEntity blockEntity = context.blockEntity();
        Set<String> capabilities = new LinkedHashSet<>();
        List<DeviceMethodDescriptor> methods = new ArrayList<>();
        Map<String, DeviceValue> properties = new LinkedHashMap<>();
        capabilities.add("create_machine");
        properties.put("create_native_adapter", DeviceValue.of(true));
        properties.put("create_control_model", DeviceValue.of("authenticated_adjacent"));

        if (blockEntity instanceof KineticBlockEntity kinetic) {
            capabilities.add("create_kinetic");
            methods.add(KINETIC_STATUS);
            addKineticProperties(properties, kinetic);
        }
        if (isStressGauge(blockEntity)) {
            capabilities.add("create_stressometer");
            methods.add(STRESS_STATUS);
            properties.put("create_network_stress", DeviceValue.of(stressValue(blockEntity, "getNetworkStress")));
            properties.put("create_network_capacity", DeviceValue.of(stressValue(blockEntity, "getNetworkCapacity")));
        }
        if (blockEntity instanceof ThresholdSwitchBlockEntity threshold) {
            capabilities.add("create_threshold_switch");
            methods.add(THRESHOLD_STATUS);
            methods.add(THRESHOLD_INVERT);
            properties.put("create_threshold_current", DeviceValue.of(threshold.getStockLevel()));
            properties.put("create_threshold_min", DeviceValue.of(threshold.getMinLevel()));
            properties.put("create_threshold_max", DeviceValue.of(threshold.getMaxLevel()));
            properties.put("create_threshold_powered", DeviceValue.of(threshold.isPowered()));
            properties.put("create_threshold_inverted", DeviceValue.of(threshold.isInverted()));
        }
        if (blockEntity instanceof RedstoneLinkBlockEntity link) {
            capabilities.add("create_redstone_link");
            methods.add(LINK_STATUS);
            methods.add(LINK_TRANSMIT);
            properties.put("create_link_signal", DeviceValue.of(link.getSignal()));
            properties.put("create_link_received_signal", DeviceValue.of(link.getReceivedSignal()));
        }
        if (blockEntity instanceof SequencedGearshiftBlockEntity sequencer) {
            capabilities.add("create_sequencer");
            methods.add(SEQUENCER_STATUS);
            methods.add(SEQUENCER_TRIGGER);
            properties.put("create_sequencer_idle", DeviceValue.of(sequencer.isIdle()));
            properties.put("create_sequencer_modifier", DeviceValue.of(sequencer.getModifier()));
            properties.put("create_sequencer_instructions", DeviceValue.of(sequencer.getInstructions().size()));
        }

        String blockId = BuiltInRegistries.BLOCK.getKey(blockEntity.getBlockState().getBlock()).toString();
        boolean writable = methods.stream().anyMatch(method ->
                DeviceCallContext.WRITE.equals(method.requiredPermission()));
        return new DeviceDescriptor(context.deviceId(), "terminalcraft:create_native", "create_machine",
                blockId, "create", context.address(), Set.copyOf(capabilities), properties,
                methods, Set.of(), writable
                ? Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE)
                : Set.of(DeviceCallContext.READ), context.isCurrent(), context.level().hasChunkAt(context.targetPosition()));
    }

    @Override
    public DeviceResult call(DeviceCallContext caller, String method, List<DeviceValue> arguments) {
        BlockEntity blockEntity = context.blockEntity();
        try {
            return switch (method == null ? "" : method) {
                case "create.kinetic.status" -> blockEntity instanceof KineticBlockEntity kinetic
                        ? DeviceResult.success(kineticStatus(kinetic)) : unsupported();
                case "create.stress.status" -> isStressGauge(blockEntity)
                        ? DeviceResult.success(stressStatus((KineticBlockEntity) blockEntity)) : unsupported();
                case "create.threshold.status" -> blockEntity instanceof ThresholdSwitchBlockEntity threshold
                        ? DeviceResult.success(thresholdStatus(threshold)) : unsupported();
                case "create.threshold.inverted.set" -> blockEntity instanceof ThresholdSwitchBlockEntity threshold
                        ? setThresholdInverted(threshold, arguments) : unsupported();
                case "create.redstone_link.status" -> blockEntity instanceof RedstoneLinkBlockEntity link
                        ? DeviceResult.success(linkStatus(link)) : unsupported();
                case "create.redstone_link.transmit" -> blockEntity instanceof RedstoneLinkBlockEntity link
                        ? transmit(link, arguments) : unsupported();
                case "create.sequencer.status" -> blockEntity instanceof SequencedGearshiftBlockEntity sequencer
                        ? DeviceResult.success(sequencerStatus(sequencer)) : unsupported();
                case "create.sequencer.trigger" -> blockEntity instanceof SequencedGearshiftBlockEntity sequencer
                        ? trigger(sequencer) : unsupported();
                default -> unsupported();
            };
        } catch (IllegalArgumentException exception) {
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT, exception.getMessage(), false);
        } catch (RuntimeException exception) {
            return DeviceResult.failure(DeviceErrorCode.ADAPTER_ERROR,
                    "Create rejected the device operation", true);
        }
    }

    private static void addKineticProperties(Map<String, DeviceValue> properties,
                                             KineticBlockEntity kinetic) {
        properties.put("create_speed_rpm", DeviceValue.of(kinetic.getSpeed()));
        properties.put("create_theoretical_speed_rpm", DeviceValue.of(kinetic.getTheoreticalSpeed()));
        properties.put("create_direction", DeviceValue.of(direction(kinetic.getSpeed())));
        properties.put("create_overstressed", DeviceValue.of(kinetic.isOverStressed()));
        properties.put("create_stress_impact", DeviceValue.of(kinetic.calculateStressApplied()));
        properties.put("create_added_stress_capacity", DeviceValue.of(kinetic.calculateAddedStressCapacity()));
        properties.put("create_has_source", DeviceValue.of(kinetic.hasSource()));
        properties.put("create_network_id", DeviceValue.of(kinetic.network == null ? "" : kinetic.network.toString()));
        properties.put("create_source", DeviceValue.of(kinetic.source == null ? "" : position(kinetic.source)));
    }

    private static DeviceValue kineticStatus(KineticBlockEntity kinetic) {
        Map<String, DeviceValue> values = new LinkedHashMap<>();
        values.put("speed_rpm", DeviceValue.of(kinetic.getSpeed()));
        values.put("absolute_speed_rpm", DeviceValue.of(Math.abs(kinetic.getSpeed())));
        values.put("theoretical_speed_rpm", DeviceValue.of(kinetic.getTheoreticalSpeed()));
        values.put("direction", DeviceValue.of(direction(kinetic.getSpeed())));
        values.put("overstressed", DeviceValue.of(kinetic.isOverStressed()));
        values.put("stress_impact", DeviceValue.of(kinetic.calculateStressApplied()));
        values.put("added_stress_capacity", DeviceValue.of(kinetic.calculateAddedStressCapacity()));
        values.put("has_source", DeviceValue.of(kinetic.hasSource()));
        values.put("source", DeviceValue.of(kinetic.source == null ? "" : position(kinetic.source)));
        values.put("network_id", DeviceValue.of(kinetic.network == null ? "" : kinetic.network.toString()));
        return DeviceValue.map(values);
    }

    private static DeviceValue stressStatus(KineticBlockEntity stress) {
        float used = stressValue(stress, "getNetworkStress");
        float capacity = stressValue(stress, "getNetworkCapacity");
        return DeviceValue.map(Map.of(
                "stress", DeviceValue.of(used),
                "capacity", DeviceValue.of(capacity),
                "usage_percent", DeviceValue.of(capacity <= 0 ? 0 : used * 100.0 / capacity),
                "overstressed", DeviceValue.of(stress.isOverStressed())));
    }

    private static DeviceValue thresholdStatus(ThresholdSwitchBlockEntity threshold) {
        return DeviceValue.map(Map.of(
                "current", DeviceValue.of(threshold.getStockLevel()),
                "minimum", DeviceValue.of(threshold.getMinLevel()),
                "maximum", DeviceValue.of(threshold.getMaxLevel()),
                "display_level", DeviceValue.of(threshold.getLevelForDisplay()),
                "state", DeviceValue.of(threshold.getState()),
                "powered", DeviceValue.of(threshold.isPowered()),
                "inverted", DeviceValue.of(threshold.isInverted()),
                "unit", DeviceValue.of(threshold.inStacks ? "items" : "percent")));
    }

    private static DeviceResult setThresholdInverted(ThresholdSwitchBlockEntity threshold,
                                                     List<DeviceValue> arguments) {
        boolean inverted = booleanArgument(arguments, 0, "inverted");
        threshold.setInverted(inverted);
        return DeviceResult.success(DeviceValue.of(threshold.isInverted()));
    }

    private static DeviceValue linkStatus(RedstoneLinkBlockEntity link) {
        return DeviceValue.map(Map.of(
                "signal", DeviceValue.of(link.getSignal()),
                "received_signal", DeviceValue.of(link.getReceivedSignal())));
    }

    private static DeviceResult transmit(RedstoneLinkBlockEntity link, List<DeviceValue> arguments) {
        int signal = integerArgument(arguments, 0, 0, 15, "signal");
        link.transmit(signal);
        return DeviceResult.success(DeviceValue.of(signal));
    }

    private static DeviceValue sequencerStatus(SequencedGearshiftBlockEntity sequencer) {
        return DeviceValue.map(Map.of(
                "idle", DeviceValue.of(sequencer.isIdle()),
                "running", DeviceValue.of(!sequencer.isIdle()),
                "modifier", DeviceValue.of(sequencer.getModifier()),
                "instructions", DeviceValue.of(sequencer.getInstructions().size()),
                "speed_rpm", DeviceValue.of(sequencer.getSpeed())));
    }

    private static DeviceResult trigger(SequencedGearshiftBlockEntity sequencer) {
        if (!sequencer.isIdle()) {
            return DeviceResult.failure(DeviceErrorCode.BUSY,
                    "Create sequenced gearshift is already running", true);
        }
        sequencer.risingFlank();
        return DeviceResult.success(DeviceValue.of(true));
    }

    private static int integerArgument(List<DeviceValue> arguments, int index, int minimum,
                                       int maximum, String label) {
        if (arguments == null || index >= arguments.size()
                || !(arguments.get(index) instanceof DeviceValue.NumberValue number)
                || number.value() != Math.rint(number.value())) {
            throw new IllegalArgumentException(label + " must be an integer");
        }
        int value = (int) number.value();
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(label + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private static boolean booleanArgument(List<DeviceValue> arguments, int index, String label) {
        if (arguments == null || index >= arguments.size()
                || !(arguments.get(index) instanceof DeviceValue.BooleanValue bool)) {
            throw new IllegalArgumentException(label + " must be boolean");
        }
        return bool.value();
    }

    private static String direction(float speed) {
        return speed > 0 ? "positive" : speed < 0 ? "negative" : "stopped";
    }

    private static String position(BlockPos position) {
        return position.getX() + "," + position.getY() + "," + position.getZ();
    }

    /** Avoids a compile-time link from Create's gauge hierarchy to its nested Ponder runtime. */
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

    private static DeviceResult unsupported() {
        return DeviceResult.failure(DeviceErrorCode.UNSUPPORTED, "method is unsupported", false);
    }

    private static DeviceMethodDescriptor read(String name, String description, DeviceValueType type) {
        return new DeviceMethodDescriptor(name, description, List.of(), type, DeviceCallContext.READ);
    }

    private static DeviceMethodDescriptor write(String name, String description,
                                                List<DeviceParameterDescriptor> parameters,
                                                DeviceValueType type) {
        return new DeviceMethodDescriptor(name, description, parameters, type, DeviceCallContext.WRITE);
    }
}
