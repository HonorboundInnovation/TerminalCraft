package com.malice.terminalcraft.device;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Bounded filter and delivery policy for one caller-owned event subscription. */
public record DeviceEventSubscription(UUID sourceDeviceId, Set<String> eventTypes,
                                      long debounceTicks, boolean coalesce) {
    public static final int MAX_EVENT_TYPES = 32;
    public static final long MAX_DEBOUNCE_TICKS = 20L * 60L;

    public DeviceEventSubscription {
        eventTypes = Set.copyOf(Objects.requireNonNull(eventTypes, "eventTypes"));
        if (eventTypes.size() > MAX_EVENT_TYPES) {
            throw new IllegalArgumentException("event subscription type limit exceeded");
        }
        eventTypes.forEach(type -> DeviceMethodDescriptor.requireIdentifier(type, "event type"));
        if (debounceTicks < 0 || debounceTicks > MAX_DEBOUNCE_TICKS) {
            throw new IllegalArgumentException("event debounce is outside the supported range");
        }
    }

    public boolean matches(DeviceEvent event) {
        Objects.requireNonNull(event, "event");
        return (sourceDeviceId == null || sourceDeviceId.equals(event.sourceDeviceId()))
                && (eventTypes.isEmpty() || eventTypes.contains(event.type()));
    }
}
