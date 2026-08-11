package com.malice.terminalcraft.integration.securitycraft;

import com.malice.terminalcraft.device.ContextualDeviceEndpoint;
import com.malice.terminalcraft.device.DeviceCallContext;
import com.malice.terminalcraft.device.DeviceDescriptor;
import com.malice.terminalcraft.device.DeviceErrorCode;
import com.malice.terminalcraft.device.DeviceMethodDescriptor;
import com.malice.terminalcraft.device.DeviceParameterDescriptor;
import com.malice.terminalcraft.device.DeviceResult;
import com.malice.terminalcraft.device.DeviceValue;
import com.malice.terminalcraft.device.DeviceValueType;
import net.geforcemods.securitycraft.api.Option;
import net.geforcemods.securitycraft.entity.SecuritySeaBoat;
import net.geforcemods.securitycraft.misc.ModuleType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Nearby entity projection for SecurityCraft's mobile Security Sea Boat. */
final class SecurityCraftSeaBoatEndpoint implements ContextualDeviceEndpoint {
    private static final DeviceMethodDescriptor STATUS = read("securitycraft.status",
            "Secret-safe Security Sea Boat operational status", DeviceValueType.MAP);
    private static final DeviceMethodDescriptor DETAILS = read("securitycraft.details",
            "Owner-only Security Sea Boat details", DeviceValueType.MAP);
    private static final DeviceMethodDescriptor INVENTORY = read("securitycraft.inventory",
            "Owner-only bounded Security Sea Boat chest contents", DeviceValueType.LIST);
    private static final DeviceMethodDescriptor OPTIONS = read("securitycraft.options",
            "Owner-only Security Sea Boat custom options", DeviceValueType.LIST);
    private static final DeviceMethodDescriptor MODULES = read("securitycraft.modules",
            "Owner-only Security Sea Boat module states", DeviceValueType.LIST);
    private static final DeviceMethodDescriptor OPTION_SET = write("securitycraft.option.set",
            "Sets a Security Sea Boat option",
            stringParameter("name", "option name"), stringParameter("value", "new value"));
    private static final DeviceMethodDescriptor MODULE_SET = write("securitycraft.module.set_enabled",
            "Toggles an already-installed Security Sea Boat module",
            stringParameter("module", "module type"),
            new DeviceParameterDescriptor("enabled", DeviceValueType.BOOLEAN, true, "new state"));

    private final ServerLevel level;
    private final BlockPos hostPosition;
    private final SecuritySeaBoat boat;

    SecurityCraftSeaBoatEndpoint(ServerLevel level, BlockPos hostPosition, SecuritySeaBoat boat) {
        this.level = level;
        this.hostPosition = hostPosition.immutable();
        this.boat = boat;
    }

    @Override
    public DeviceDescriptor descriptor() {
        Set<String> capabilities = new LinkedHashSet<>(Set.of(
                "securitycraft_owned", "securitycraft_passcode_protected", "securitycraft_options",
                "securitycraft_modules", "securitycraft_inventory", "securitycraft_security_sea_boat"));
        return new DeviceDescriptor(boat.getUUID(), "terminalcraft:securitycraft_native",
                "securitycraft_sea_boat", boat.getDisplayName().getString(), "securitycraft",
                "entity/" + boat.getUUID(), capabilities, publicStatus(),
                List.of(STATUS, DETAILS, INVENTORY, OPTIONS, MODULES, OPTION_SET, MODULE_SET), Set.of(),
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE), boat.isAlive(),
                level.hasChunkAt(boat.blockPosition()));
    }

    @Override
    public DeviceResult call(DeviceCallContext caller, String method, List<DeviceValue> arguments) {
        try {
            return switch (method == null ? "" : method) {
                case "securitycraft.status" -> DeviceResult.success(DeviceValue.map(publicStatus()));
                case "securitycraft.details" -> ownerRead(caller, DeviceValue.map(details()));
                case "securitycraft.inventory" -> ownerRead(caller, inventory());
                case "securitycraft.options" -> ownerRead(caller, options());
                case "securitycraft.modules" -> ownerRead(caller, modules());
                case "securitycraft.option.set" -> setOption(caller, arguments);
                case "securitycraft.module.set_enabled" -> setModule(caller, arguments);
                default -> DeviceResult.failure(DeviceErrorCode.UNSUPPORTED, "method is unsupported", false);
            };
        } catch (IllegalArgumentException exception) {
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT, exception.getMessage(), false);
        } catch (RuntimeException exception) {
            return DeviceResult.failure(DeviceErrorCode.ADAPTER_ERROR,
                    "SecurityCraft rejected the sea-boat operation", true);
        }
    }

    private Map<String, DeviceValue> publicStatus() {
        Map<String, DeviceValue> values = new LinkedHashMap<>();
        values.put("securitycraft_family", DeviceValue.of("security_sea_boat"));
        values.put("securitycraft_owner_present", DeviceValue.of(!boat.getOwner().isDefaultOwner()));
        values.put("securitycraft_passcode_configured", DeviceValue.of(boat.getPasscode() != null));
        values.put("securitycraft_module_count", DeviceValue.of(boat.getInsertedModules().size()));
        values.put("securitycraft_inventory_slots", DeviceValue.of(boat.getContainerSize()));
        values.put("securitycraft_inventory_items", DeviceValue.of(totalItems()));
        values.put("securitycraft_distance", DeviceValue.of(Math.sqrt(boat.distanceToSqr(
                hostPosition.getX() + 0.5, hostPosition.getY() + 0.5, hostPosition.getZ() + 0.5))));
        return values;
    }

    private Map<String, DeviceValue> details() {
        Map<String, DeviceValue> values = new LinkedHashMap<>(publicStatus());
        values.put("owner_name", DeviceValue.of(boat.getOwner().getName()));
        values.put("cooldown", DeviceValue.of(boat.isOnCooldown()));
        values.put("x", DeviceValue.of(boat.getX()));
        values.put("y", DeviceValue.of(boat.getY()));
        values.put("z", DeviceValue.of(boat.getZ()));
        return values;
    }

    private DeviceValue inventory() {
        List<DeviceValue> values = new ArrayList<>();
        for (int slot = 0; slot < boat.getContainerSize(); slot++) {
            ItemStack stack = boat.getItem(slot);
            if (stack.isEmpty()) continue;
            values.add(DeviceValue.map(Map.of(
                    "slot", DeviceValue.of(slot),
                    "item", DeviceValue.of(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()),
                    "count", DeviceValue.of(stack.getCount()))));
        }
        return DeviceValue.list(values);
    }

    private DeviceValue options() {
        List<DeviceValue> values = new ArrayList<>();
        for (Option<?> option : boat.customOptions()) {
            Map<String, DeviceValue> value = new LinkedHashMap<>();
            value.put("name", DeviceValue.of(option.getName()));
            value.put("value", toValue(option.get()));
            value.put("minimum", toValue(option.getMin()));
            value.put("maximum", toValue(option.getMax()));
            values.add(DeviceValue.map(value));
        }
        return DeviceValue.list(values);
    }

    private DeviceValue modules() {
        List<DeviceValue> values = new ArrayList<>();
        for (ModuleType module : boat.acceptedModules()) {
            ItemStack stack = boat.getModule(module);
            values.add(DeviceValue.map(Map.of(
                    "module", DeviceValue.of(module.name().toLowerCase(Locale.ROOT)),
                    "installed", DeviceValue.of(!stack.isEmpty()),
                    "enabled", DeviceValue.of(boat.isModuleEnabled(module)))));
        }
        return DeviceValue.list(values);
    }

    private DeviceResult setOption(DeviceCallContext caller, List<DeviceValue> arguments) {
        DeviceResult denied = requireOwner(caller);
        if (denied != null) return denied;
        String name = stringArg(arguments, 0, "name");
        String value = stringArg(arguments, 1, "value");
        for (Option<?> option : boat.customOptions()) {
            if (!option.getName().equalsIgnoreCase(name)) continue;
            setOptionValue(option, value);
            notifyOptionChanged(option);
            return DeviceResult.success(DeviceValue.map(Map.of(
                    "name", DeviceValue.of(option.getName()), "value", toValue(option.get()))));
        }
        throw new IllegalArgumentException("unknown SecurityCraft option: " + name);
    }

    private DeviceResult setModule(DeviceCallContext caller, List<DeviceValue> arguments) {
        DeviceResult denied = requireOwner(caller);
        if (denied != null) return denied;
        ModuleType module = enumValue(ModuleType.class, stringArg(arguments, 0, "module"), "module");
        boolean enabled = booleanArg(arguments, 1, "enabled");
        ItemStack stack = boat.getModule(module);
        if (stack.isEmpty()) throw new IllegalArgumentException("module is not installed");
        if (boat.isModuleEnabled(module) != enabled) {
            if (enabled) boat.insertModule(stack, true); else boat.removeModule(module, true);
        }
        return DeviceResult.success(DeviceValue.map(Map.of(
                "module", DeviceValue.of(module.name().toLowerCase(Locale.ROOT)),
                "enabled", DeviceValue.of(boat.isModuleEnabled(module)))));
    }

    private DeviceResult ownerRead(DeviceCallContext caller, DeviceValue value) {
        DeviceResult denied = requireOwner(caller);
        return denied == null ? DeviceResult.success(value) : denied;
    }

    private DeviceResult requireOwner(DeviceCallContext caller) {
        ServerPlayer player = SecurityCraftIntegration.authenticatedPlayer(level, caller);
        if (player == null || !boat.isOwnedBy(player, false)) {
            return DeviceResult.failure(DeviceErrorCode.PERMISSION_DENIED,
                    "Security Sea Boat details and controls require the online owning player", false);
        }
        return null;
    }

    private int totalItems() {
        int count = 0;
        for (int slot = 0; slot < boat.getContainerSize(); slot++) count += boat.getItem(slot).getCount();
        return count;
    }

    private static String stringArg(List<DeviceValue> arguments, int index, String name) {
        if (arguments == null || index >= arguments.size() || !(arguments.get(index) instanceof DeviceValue.StringValue value)) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        return value.value().trim();
    }

    private static boolean booleanArg(List<DeviceValue> arguments, int index, String name) {
        if (arguments == null || index >= arguments.size()) throw new IllegalArgumentException(name + " must be boolean");
        DeviceValue value = arguments.get(index);
        if (value instanceof DeviceValue.BooleanValue bool) return bool.value();
        if (value instanceof DeviceValue.StringValue string) return parseBoolean(string.value(), name);
        throw new IllegalArgumentException(name + " must be boolean");
    }

    private static boolean parseBoolean(String value, String name) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "on", "yes", "1", "enabled" -> true;
            case "false", "off", "no", "0", "disabled" -> false;
            default -> throw new IllegalArgumentException(name + " must be true or false");
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void setOptionValue(Option option, String raw) {
        Object current = option.get();
        if (current instanceof Boolean) option.setValue(parseBoolean(raw, option.getName()));
        else if (current instanceof Integer) {
            int parsed;
            try { parsed = Integer.parseInt(raw); }
            catch (NumberFormatException exception) { throw new IllegalArgumentException(option.getName() + " must be an integer"); }
            int min = option.getMin() instanceof Number value ? value.intValue() : Integer.MIN_VALUE;
            int max = option.getMax() instanceof Number value ? value.intValue() : Integer.MAX_VALUE;
            if (parsed < Math.min(min, max) || parsed > Math.max(min, max)) throw new IllegalArgumentException(option.getName() + " is outside its valid range");
            option.setValue(parsed);
        } else throw new IllegalArgumentException("unsupported option type for " + option.getName());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void notifyOptionChanged(Option option) {
        boat.onOptionChanged(option);
    }

    private static DeviceValue toValue(Object value) {
        if (value == null) return DeviceValue.nullValue();
        if (value instanceof Boolean bool) return DeviceValue.of(bool);
        if (value instanceof Number number) return DeviceValue.of(number.doubleValue());
        return DeviceValue.of(String.valueOf(value));
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String name) {
        try { return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT).replace('-', '_')); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("unknown " + name + ": " + value); }
    }

    private static DeviceMethodDescriptor read(String name, String description, DeviceValueType type) {
        return new DeviceMethodDescriptor(name, description, List.of(), type, DeviceCallContext.READ);
    }

    private static DeviceMethodDescriptor write(String name, String description,
                                                DeviceParameterDescriptor... parameters) {
        return new DeviceMethodDescriptor(name, description, List.of(parameters),
                DeviceValueType.MAP, DeviceCallContext.WRITE);
    }

    private static DeviceParameterDescriptor stringParameter(String name, String description) {
        return new DeviceParameterDescriptor(name, DeviceValueType.STRING, true, description);
    }
}
