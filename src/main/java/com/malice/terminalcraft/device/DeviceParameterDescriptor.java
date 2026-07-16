package com.malice.terminalcraft.device;

import java.util.Objects;

/** One method argument in an advertised device schema. */
public record DeviceParameterDescriptor(String name, DeviceValueType type, boolean required,
                                        String description) {
    public DeviceParameterDescriptor {
        name = DeviceMethodDescriptor.requireIdentifier(name, "parameter name");
        type = Objects.requireNonNull(type, "type");
        description = Objects.requireNonNull(description, "description");
    }
}
