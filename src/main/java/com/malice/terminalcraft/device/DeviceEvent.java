package com.malice.terminalcraft.device;

import java.util.Objects;
import java.util.UUID;

/** One immutable, bounded state-change notification emitted by a registered device. */
public record DeviceEvent(long sequence, UUID sourceDeviceId, String type, long gameTime,
                          DeviceValue.MapValue payload) {
    public DeviceEvent {
        if (sequence < 1) throw new IllegalArgumentException("event sequence must be positive");
        sourceDeviceId = Objects.requireNonNull(sourceDeviceId, "sourceDeviceId");
        type = DeviceMethodDescriptor.requireIdentifier(type, "event type");
        if (gameTime < 0) throw new IllegalArgumentException("event game time must not be negative");
        payload = Objects.requireNonNull(payload, "payload");
    }
}
