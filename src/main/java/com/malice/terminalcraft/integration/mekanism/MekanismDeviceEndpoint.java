package com.malice.terminalcraft.integration.mekanism;

import com.malice.terminalcraft.device.ContextualDeviceEndpoint;
import com.malice.terminalcraft.device.DeviceCallContext;
import com.malice.terminalcraft.device.DeviceDescriptor;
import com.malice.terminalcraft.device.DeviceErrorCode;
import com.malice.terminalcraft.device.DeviceMethodDescriptor;
import com.malice.terminalcraft.device.DeviceParameterDescriptor;
import com.malice.terminalcraft.device.DeviceResult;
import com.malice.terminalcraft.device.DeviceValue;
import com.malice.terminalcraft.device.DeviceValueType;
import com.malice.terminalcraft.integration.OptionalDeviceEndpointRegistry;
import mekanism.api.energy.IStrictEnergyHandler;
import mekanism.api.heat.IHeatHandler;
import mekanism.api.math.FloatingLong;
import mekanism.api.security.SecurityMode;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.interfaces.IRedstoneControl;
import mekanism.common.tile.prefab.TileEntityProgressMachine;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Native Mekanism telemetry and redstone-control configuration for one adjacent machine. */
final class MekanismDeviceEndpoint implements ContextualDeviceEndpoint {
    private static final int MAX_TANKS_PER_TYPE = 64;
    private static final int MAX_ENERGY_CONTAINERS = 64;
    private static final int MAX_HEAT_CAPACITORS = 64;

    private static final DeviceParameterDescriptor CONTROL_MODE = new DeviceParameterDescriptor(
            "mode", DeviceValueType.STRING, true, "disabled, high, low, or pulse when supported");
    private static final DeviceMethodDescriptor STATUS = read("mekanism.status",
            "Returns machine activity, redstone, progress, security, and supported-substance telemetry",
            DeviceValueType.MAP);
    private static final DeviceMethodDescriptor ENERGY = read("mekanism.energy.containers",
            "Returns exact native Joule container telemetry", DeviceValueType.LIST);
    private static final DeviceMethodDescriptor HEAT = read("mekanism.heat.capacitors",
            "Returns temperature and heat-capacity telemetry", DeviceValueType.LIST);
    private static final DeviceMethodDescriptor REDSTONE_SET = new DeviceMethodDescriptor(
            "mekanism.redstone_control.set", "Sets the machine's native Mekanism redstone control mode",
            List.of(CONTROL_MODE), DeviceValueType.STRING, DeviceCallContext.WRITE);

    private final OptionalDeviceEndpointRegistry.Context context;
    private final TileEntityMekanism tile;

    MekanismDeviceEndpoint(OptionalDeviceEndpointRegistry.Context context, TileEntityMekanism tile) {
        this.context = context;
        this.tile = tile;
    }

    @Override
    public DeviceDescriptor descriptor() {
        List<MekanismCapabilityView.ChemicalHandler> chemicals =
                MekanismCapabilityView.chemicals(tile, context.accessSide());
        IStrictEnergyHandler energy = MekanismCapabilityView.energy(tile, context.accessSide());
        IHeatHandler heat = MekanismCapabilityView.heat(tile, context.accessSide());

        Set<String> capabilities = new LinkedHashSet<>();
        capabilities.add("mekanism_machine");
        for (MekanismCapabilityView.ChemicalHandler chemical : chemicals) {
            capabilities.add("mekanism_" + chemical.type());
        }
        if (energy != null) capabilities.add("mekanism_energy");
        if (heat != null) capabilities.add("mekanism_heat");
        if (tile instanceof TileEntityProgressMachine) capabilities.add("mekanism_progress");
        if (tile.supportsRedstone()) capabilities.add("mekanism_redstone_control");
        if (tile.hasSecurity()) capabilities.add("mekanism_security");

        List<DeviceMethodDescriptor> methods = new ArrayList<>();
        methods.add(STATUS);
        if (energy != null) methods.add(ENERGY);
        if (heat != null) methods.add(HEAT);
        if (tile.supportsRedstone()) methods.add(REDSTONE_SET);

        Map<String, DeviceValue> properties = baseProperties();
        properties.put("mekanism_chemical_tank_limit", DeviceValue.of(MAX_TANKS_PER_TYPE));
        properties.put("mekanism_energy_container_limit", DeviceValue.of(MAX_ENERGY_CONTAINERS));
        properties.put("mekanism_heat_capacitor_limit", DeviceValue.of(MAX_HEAT_CAPACITORS));
        properties.put("mekanism_native_energy_unit", DeviceValue.of("J"));
        properties.put("mekanism_chemical_unit", DeviceValue.of("mB"));
        properties.put("mekanism_security_bridge", DeviceValue.of("public_mutation_only"));

        String blockId = BuiltInRegistries.BLOCK.getKey(tile.getBlockState().getBlock()).toString();
        return new DeviceDescriptor(context.deviceId(), "terminalcraft:mekanism_native",
                "mekanism_machine", blockId, "mekanism", context.address(), capabilities,
                properties, methods, Set.of(), tile.supportsRedstone()
                ? Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE)
                : Set.of(DeviceCallContext.READ), context.isCurrent(),
                context.level().hasChunkAt(context.targetPosition()));
    }

    @Override
    public DeviceResult call(DeviceCallContext caller, String method, List<DeviceValue> arguments) {
        try {
            return switch (method == null ? "" : method) {
                case "mekanism.status" -> DeviceResult.success(DeviceValue.map(baseProperties()));
                case "mekanism.energy.containers" -> DeviceResult.success(energyContainers());
                case "mekanism.heat.capacitors" -> DeviceResult.success(heatCapacitors());
                case "mekanism.redstone_control.set" -> setRedstoneControl(arguments);
                default -> DeviceResult.failure(DeviceErrorCode.UNSUPPORTED,
                        "method is unsupported", false);
            };
        } catch (IllegalArgumentException exception) {
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT, exception.getMessage(), false);
        } catch (RuntimeException exception) {
            return DeviceResult.failure(DeviceErrorCode.ADAPTER_ERROR,
                    "Mekanism rejected the device operation", true);
        }
    }

    private Map<String, DeviceValue> baseProperties() {
        Map<String, DeviceValue> properties = new LinkedHashMap<>();
        properties.put("mekanism_active", DeviceValue.of(tile.getActive()));
        properties.put("mekanism_powered", DeviceValue.of(tile.isPowered()));
        properties.put("mekanism_redstone_level", DeviceValue.of(tile.getCurrentRedstoneLevel()));
        properties.put("mekanism_supports_redstone", DeviceValue.of(tile.supportsRedstone()));
        properties.put("mekanism_redstone_control", DeviceValue.of(tile.supportsRedstone()
                ? tile.getControlType().name().toLowerCase(Locale.ROOT) : "unsupported"));
        properties.put("mekanism_handles_items", DeviceValue.of(tile.hasInventory()));
        properties.put("mekanism_handles_fluids", DeviceValue.of(tile.canHandleFluid()));
        properties.put("mekanism_handles_energy", DeviceValue.of(tile.canHandleEnergy()));
        properties.put("mekanism_handles_heat", DeviceValue.of(tile.canHandleHeat()));
        properties.put("mekanism_handles_gas", DeviceValue.of(tile.canHandleGas()));
        properties.put("mekanism_handles_infusion", DeviceValue.of(tile.canHandleInfusion()));
        properties.put("mekanism_handles_pigment", DeviceValue.of(tile.canHandlePigment()));
        properties.put("mekanism_handles_slurry", DeviceValue.of(tile.canHandleSlurry()));
        properties.put("mekanism_computer_name", DeviceValue.of(tile.hasComputerSupport()
                ? tile.getComputerName() : ""));
        if (tile instanceof TileEntityProgressMachine progress) {
            properties.put("mekanism_progress", DeviceValue.of(progress.getScaledProgress()));
            properties.put("mekanism_operating_ticks", DeviceValue.of(progress.getOperatingTicks()));
            properties.put("mekanism_ticks_required", DeviceValue.of(progress.getTicksRequired()));
        }
        if (tile.hasSecurity() && tile.getSecurity() != null) {
            SecurityMode mode = tile.getSecurity().getMode();
            properties.put("mekanism_security_mode", DeviceValue.of(mode == null
                    ? "unknown" : mode.name().toLowerCase(Locale.ROOT)));
            properties.put("mekanism_owner_present", DeviceValue.of(tile.getSecurity().getOwnerUUID() != null));
        } else {
            properties.put("mekanism_security_mode", DeviceValue.of("unsupported"));
            properties.put("mekanism_owner_present", DeviceValue.of(false));
        }
        return properties;
    }

    private DeviceValue energyContainers() {
        IStrictEnergyHandler handler = MekanismCapabilityView.energy(tile, context.accessSide());
        if (handler == null) return DeviceValue.list(List.of());
        List<DeviceValue> result = new ArrayList<>();
        int count = Math.min(handler.getEnergyContainerCount(), MAX_ENERGY_CONTAINERS);
        for (int container = 0; container < count; container++) {
            FloatingLong stored = handler.getEnergy(container);
            FloatingLong capacity = handler.getMaxEnergy(container);
            FloatingLong needed = handler.getNeededEnergy(container);
            result.add(DeviceValue.map(Map.of(
                    "container", DeviceValue.of(container),
                    "stored", DeviceValue.of(exact(stored)),
                    "capacity", DeviceValue.of(exact(capacity)),
                    "needed", DeviceValue.of(exact(needed)),
                    "fill_percent", DeviceValue.of(capacity == null || capacity.isZero()
                            ? 0 : stored.divideToLevel(capacity) * 100.0),
                    "unit", DeviceValue.of("J"))));
        }
        return DeviceValue.list(result);
    }

    private DeviceValue heatCapacitors() {
        IHeatHandler handler = MekanismCapabilityView.heat(tile, context.accessSide());
        if (handler == null) return DeviceValue.list(List.of());
        List<DeviceValue> result = new ArrayList<>();
        int count = Math.min(handler.getHeatCapacitorCount(), MAX_HEAT_CAPACITORS);
        for (int capacitor = 0; capacitor < count; capacitor++) {
            result.add(DeviceValue.map(Map.of(
                    "capacitor", DeviceValue.of(capacitor),
                    "temperature", DeviceValue.of(handler.getTemperature(capacitor)),
                    "heat_capacity", DeviceValue.of(handler.getHeatCapacity(capacitor)),
                    "inverse_conduction", DeviceValue.of(handler.getInverseConduction(capacitor)),
                    "temperature_unit", DeviceValue.of("K"))));
        }
        return DeviceValue.list(result);
    }

    private DeviceResult setRedstoneControl(List<DeviceValue> arguments) {
        if (!tile.supportsRedstone()) {
            return DeviceResult.failure(DeviceErrorCode.UNSUPPORTED,
                    "Mekanism machine does not support redstone control", false);
        }
        if (arguments == null || arguments.isEmpty()
                || !(arguments.get(0) instanceof DeviceValue.StringValue string)) {
            throw new IllegalArgumentException("mode must be a string");
        }
        String normalized = string.value().trim().toUpperCase(Locale.ROOT);
        IRedstoneControl.RedstoneControl control;
        try {
            control = IRedstoneControl.RedstoneControl.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("mode must be disabled, high, low, or pulse");
        }
        if (control == IRedstoneControl.RedstoneControl.PULSE
                && !tile.canPulse()) {
            throw new IllegalArgumentException("this Mekanism machine does not support pulse mode");
        }
        tile.setControlType(control);
        return DeviceResult.success(DeviceValue.of(tile.getControlType().name().toLowerCase(Locale.ROOT)));
    }

    private static String exact(FloatingLong value) {
        return value == null ? "0" : value.toString();
    }

    private static DeviceMethodDescriptor read(String name, String description, DeviceValueType type) {
        return new DeviceMethodDescriptor(name, description, List.of(), type, DeviceCallContext.READ);
    }
}
