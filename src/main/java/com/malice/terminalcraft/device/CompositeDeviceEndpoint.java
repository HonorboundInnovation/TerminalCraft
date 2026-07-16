package com.malice.terminalcraft.device;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Combines additive endpoint projections that share one physical device identity. */
public final class CompositeDeviceEndpoint implements ContextualDeviceEndpoint {
    private final DeviceEndpoint primary;
    private final List<DeviceEndpoint> delegates;

    public CompositeDeviceEndpoint(DeviceEndpoint primary, DeviceEndpoint... additional) {
        this.primary = Objects.requireNonNull(primary, "primary");
        ArrayList<DeviceEndpoint> endpoints = new ArrayList<>();
        endpoints.add(primary);
        endpoints.addAll(List.of(additional));
        this.delegates = List.copyOf(endpoints);
        validateIdentity();
    }

    @Override
    public DeviceDescriptor descriptor() {
        DeviceDescriptor base = primary.descriptor();
        Set<String> capabilities = new LinkedHashSet<>();
        Map<String, DeviceValue> properties = new LinkedHashMap<>();
        List<DeviceMethodDescriptor> methods = new ArrayList<>();
        Set<String> events = new LinkedHashSet<>();
        Set<String> permissions = new LinkedHashSet<>();
        boolean online = true;
        boolean loaded = true;
        for (DeviceEndpoint delegate : delegates) {
            DeviceDescriptor descriptor = delegate.descriptor();
            capabilities.addAll(descriptor.capabilities());
            properties.putAll(descriptor.properties());
            for (DeviceMethodDescriptor method : descriptor.methods()) {
                if (methods.stream().anyMatch(existing -> existing.name().equals(method.name()))) {
                    throw new IllegalStateException("duplicate composite device method: " + method.name());
                }
                methods.add(method);
            }
            events.addAll(descriptor.eventTypes());
            permissions.addAll(descriptor.permissions());
            online &= descriptor.online();
            loaded &= descriptor.loaded();
        }
        return new DeviceDescriptor(base.deviceId(), base.adapterId(), base.typeName(), base.displayName(),
                base.modSource(), base.address(), capabilities, properties, methods, events, permissions,
                online, loaded);
    }

    @Override
    public DeviceResult call(DeviceCallContext context, String method, List<DeviceValue> arguments) {
        for (DeviceEndpoint delegate : delegates) {
            boolean owns = delegate.descriptor().methods().stream()
                    .anyMatch(candidate -> candidate.name().equals(method));
            if (!owns) continue;
            return delegate instanceof ContextualDeviceEndpoint contextual
                    ? contextual.call(context, method, arguments)
                    : delegate.call(method, arguments);
        }
        return DeviceResult.failure(DeviceErrorCode.UNSUPPORTED, "method is unsupported", false);
    }

    private void validateIdentity() {
        DeviceDescriptor expected = primary.descriptor();
        for (DeviceEndpoint delegate : delegates) {
            DeviceDescriptor actual = delegate.descriptor();
            if (!expected.deviceId().equals(actual.deviceId()) || !expected.address().equals(actual.address())) {
                throw new IllegalArgumentException("composite endpoint delegates must share identity and address");
            }
        }
    }
}
