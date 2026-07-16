package com.malice.terminalcraft.integration;

import com.malice.terminalcraft.device.DeviceValue;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bounded descriptor enrichment supplied by an optional integration. */
public record OptionalDeviceMetadata(String adapterId, String typeName,
                                     Set<String> capabilities,
                                     Map<String, DeviceValue> properties) {
    public OptionalDeviceMetadata {
        adapterId = Objects.requireNonNull(adapterId, "adapterId");
        typeName = Objects.requireNonNull(typeName, "typeName");
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        properties = Map.copyOf(Objects.requireNonNull(properties, "properties"));
    }
}
