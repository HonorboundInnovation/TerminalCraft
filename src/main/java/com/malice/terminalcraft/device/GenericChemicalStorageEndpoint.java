package com.malice.terminalcraft.device;

import com.malice.terminalcraft.integration.OptionalChemicalStorageRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Read-only dynamic chemical-tank projection shared by every optional chemical provider. */
public final class GenericChemicalStorageEndpoint implements DeviceEndpoint {
    private static final DeviceParameterDescriptor RESOURCE = new DeviceParameterDescriptor(
            "resource", DeviceValueType.STRING, true, "Namespaced chemical identifier");
    private static final DeviceParameterDescriptor FAMILY = new DeviceParameterDescriptor(
            "family", DeviceValueType.STRING, false, "Optional provider chemical family");
    private static final DeviceMethodDescriptor TANKS = new DeviceMethodDescriptor(
            "chemical.tanks", "Returns live tanks and dynamically discovered chemical resource IDs",
            List.of(), DeviceValueType.LIST, DeviceCallContext.READ);
    private static final DeviceMethodDescriptor COUNT = new DeviceMethodDescriptor(
            "chemical.count", "Returns the exact aggregate amount for one live chemical resource",
            List.of(RESOURCE, FAMILY), DeviceValueType.STRING, DeviceCallContext.READ);

    private final UUID deviceId;
    private final String displayName;
    private final String modSource;
    private final String address;
    private final OptionalChemicalStorageRegistry.ChemicalStorage storage;
    private final BooleanSupplier online;
    private final BooleanSupplier loaded;

    public GenericChemicalStorageEndpoint(UUID deviceId, String displayName, String modSource,
                                          String address,
                                          OptionalChemicalStorageRegistry.ChemicalStorage storage,
                                          BooleanSupplier online, BooleanSupplier loaded) {
        this.deviceId = Objects.requireNonNull(deviceId, "deviceId");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.modSource = Objects.requireNonNull(modSource, "modSource");
        this.address = Objects.requireNonNull(address, "address");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.online = Objects.requireNonNull(online, "online");
        this.loaded = Objects.requireNonNull(loaded, "loaded");
    }

    @Override
    public DeviceDescriptor descriptor() {
        List<OptionalChemicalStorageRegistry.Tank> tanks = boundedSnapshot();
        Set<String> capabilities = new LinkedHashSet<>();
        capabilities.add("chemical_storage");
        Set<String> families = new LinkedHashSet<>();
        for (OptionalChemicalStorageRegistry.Tank tank : tanks) families.add(tank.family());
        for (String family : families) capabilities.add("chemical_" + family);
        Map<String, DeviceValue> properties = new LinkedHashMap<>();
        properties.put("chemical_dynamic_resources", DeviceValue.of(true));
        properties.put("chemical_read_only", DeviceValue.of(true));
        properties.put("chemical_tank_limit", DeviceValue.of(DeviceValue.MAX_COLLECTION_ENTRIES));
        properties.put("chemical_families", DeviceValue.list(families.stream().map(DeviceValue::of).toList()));
        properties.put("chemical_tanks_visible", DeviceValue.of(tanks.size()));
        return new DeviceDescriptor(deviceId, "terminalcraft:chemical_storage", "chemical_storage",
                displayName, modSource, address, capabilities, properties, List.of(TANKS, COUNT),
                Set.of(), Set.of(DeviceCallContext.READ), online.getAsBoolean(), loaded.getAsBoolean());
    }

    @Override
    public DeviceResult call(String method, List<DeviceValue> arguments) {
        try {
            return switch (method == null ? "" : method) {
                case "chemical.tanks" -> DeviceResult.success(tankValues(boundedSnapshot()));
                case "chemical.count" -> DeviceResult.success(DeviceValue.of(Long.toString(
                        count(arguments == null ? List.of() : arguments))));
                default -> DeviceResult.failure(DeviceErrorCode.UNSUPPORTED,
                        "method is unsupported", false);
            };
        } catch (IllegalArgumentException exception) {
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT, exception.getMessage(), false);
        }
    }

    private long count(List<DeviceValue> arguments) {
        if (arguments.isEmpty() || !(arguments.get(0) instanceof DeviceValue.StringValue resource)) {
            throw new IllegalArgumentException("resource must be a string");
        }
        String family = "";
        if (arguments.size() > 1) {
            if (!(arguments.get(1) instanceof DeviceValue.StringValue value)) {
                throw new IllegalArgumentException("family must be a string");
            }
            family = value.value().trim().toLowerCase(java.util.Locale.ROOT);
        }
        String resourceId = resource.value().trim().toLowerCase(java.util.Locale.ROOT);
        long amount = 0;
        for (OptionalChemicalStorageRegistry.Tank tank : boundedSnapshot()) {
            if (!tank.resourceId().equals(resourceId) || (!family.isEmpty() && !tank.family().equals(family))) continue;
            amount = Long.MAX_VALUE - amount < tank.amount() ? Long.MAX_VALUE : amount + tank.amount();
        }
        return amount;
    }

    private List<OptionalChemicalStorageRegistry.Tank> boundedSnapshot() {
        List<OptionalChemicalStorageRegistry.Tank> snapshot = List.copyOf(storage.snapshot());
        return snapshot.subList(0, Math.min(snapshot.size(), DeviceValue.MAX_COLLECTION_ENTRIES));
    }

    private static DeviceValue tankValues(List<OptionalChemicalStorageRegistry.Tank> tanks) {
        List<DeviceValue> values = new ArrayList<>(tanks.size());
        for (OptionalChemicalStorageRegistry.Tank tank : tanks) {
            values.add(DeviceValue.map(Map.of(
                    "family", DeviceValue.of(tank.family()),
                    "tank", DeviceValue.of(tank.tank()),
                    "resource", DeviceValue.of(tank.resourceId()),
                    "amount", DeviceValue.of(Long.toString(tank.amount())),
                    "capacity", DeviceValue.of(Long.toString(tank.capacity())),
                    "fill_percent", DeviceValue.of(tank.capacity() <= 0 ? 0
                            : tank.amount() * 100.0 / tank.capacity()),
                    "unit", DeviceValue.of(tank.unit()))));
        }
        return DeviceValue.list(values);
    }
}
