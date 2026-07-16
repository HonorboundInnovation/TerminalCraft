package com.malice.terminalcraft.device;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Stable, bounded metadata exposed by every TerminalCraft device adapter. */
public record DeviceDescriptor(UUID deviceId, String adapterId, String typeName,
                               String displayName, String modSource, String address,
                               Set<String> capabilities, Map<String, DeviceValue> properties,
                               List<DeviceMethodDescriptor> methods, Set<String> eventTypes,
                               Set<String> permissions, boolean online, boolean loaded) {
    public static final int MAX_CAPABILITIES = 64;
    public static final int MAX_PROPERTIES = 128;
    public static final int MAX_METHODS = 128;
    public static final int MAX_EVENT_TYPES = 64;
    public static final int MAX_PERMISSIONS = 64;

    public DeviceDescriptor {
        deviceId = Objects.requireNonNull(deviceId, "deviceId");
        adapterId = requireNamespacedId(adapterId, "adapterId");
        typeName = DeviceMethodDescriptor.requireIdentifier(typeName, "typeName");
        displayName = requireText(displayName, "displayName");
        modSource = DeviceMethodDescriptor.requireIdentifier(modSource, "modSource");
        address = requireText(address, "address");
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        properties = Map.copyOf(Objects.requireNonNull(properties, "properties"));
        methods = List.copyOf(Objects.requireNonNull(methods, "methods"));
        eventTypes = Set.copyOf(Objects.requireNonNull(eventTypes, "eventTypes"));
        permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions"));
        if (capabilities.size() > MAX_CAPABILITIES) throw new IllegalArgumentException("too many capabilities");
        if (properties.size() > MAX_PROPERTIES) throw new IllegalArgumentException("too many properties");
        if (methods.size() > MAX_METHODS) throw new IllegalArgumentException("too many methods");
        if (eventTypes.size() > MAX_EVENT_TYPES) throw new IllegalArgumentException("too many event types");
        if (permissions.size() > MAX_PERMISSIONS) throw new IllegalArgumentException("too many permissions");
        capabilities.forEach(v -> DeviceMethodDescriptor.requireIdentifier(v, "capability"));
        eventTypes.forEach(v -> DeviceMethodDescriptor.requireIdentifier(v, "event type"));
        permissions.forEach(v -> DeviceMethodDescriptor.requireIdentifier(v, "permission"));
        for (Map.Entry<String, DeviceValue> property : properties.entrySet()) {
            DeviceMethodDescriptor.requireIdentifier(property.getKey(), "property name");
            Objects.requireNonNull(property.getValue(), "property value");
        }
        DeviceValue.requireWithinBudget(properties.values(), DeviceValue.MAX_TOTAL_NODES,
                DeviceValue.MAX_TOTAL_TEXT_LENGTH, "device properties");

        Set<String> methodNames = new HashSet<>();
        for (DeviceMethodDescriptor method : methods) {
            Objects.requireNonNull(method, "method");
            if (!methodNames.add(method.name())) {
                throw new IllegalArgumentException("duplicate device method: " + method.name());
            }
        }
    }

    private static String requireNamespacedId(String value, String label) {
        Objects.requireNonNull(value, label);
        if (!value.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+")) throw new IllegalArgumentException("invalid " + label);
        return value;
    }

    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank() || value.length() > DeviceValue.MAX_STRING_LENGTH) throw new IllegalArgumentException("invalid " + label);
        return value;
    }
}
