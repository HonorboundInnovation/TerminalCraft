package com.malice.terminalcraft.device;

import java.util.List;
import java.util.Objects;

/** Immutable callable method schema advertised by a device. */
public record DeviceMethodDescriptor(String name, String description,
                                     List<DeviceParameterDescriptor> parameters,
                                     DeviceValueType returnType, String requiredPermission) {
    public DeviceMethodDescriptor(String name, String description,
                                  List<DeviceParameterDescriptor> parameters,
                                  DeviceValueType returnType) {
        this(name, description, parameters, returnType, DeviceCallContext.READ);
    }

    public DeviceMethodDescriptor {
        name = requireIdentifier(name, "method name");
        description = Objects.requireNonNull(description, "description");
        if (description.length() > DeviceValue.MAX_STRING_LENGTH) {
            throw new IllegalArgumentException("method description exceeds limit");
        }
        parameters = List.copyOf(parameters);
        returnType = Objects.requireNonNull(returnType, "returnType");
        requiredPermission = requireIdentifier(requiredPermission, "required permission");
        if (parameters.size() > 32) throw new IllegalArgumentException("too many method parameters");

        java.util.Set<String> names = new java.util.HashSet<>();
        boolean optionalSeen = false;
        for (DeviceParameterDescriptor parameter : parameters) {
            Objects.requireNonNull(parameter, "parameter");
            if (!names.add(parameter.name())) {
                throw new IllegalArgumentException("duplicate method parameter: " + parameter.name());
            }
            if (!parameter.required()) {
                optionalSeen = true;
            } else if (optionalSeen) {
                throw new IllegalArgumentException("required parameters must precede optional parameters");
            }
        }
    }

    static String requireIdentifier(String value, String label) {
        Objects.requireNonNull(value, label);
        if (!value.matches("[a-z][a-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("invalid " + label + ": " + value);
        }
        return value;
    }
}
