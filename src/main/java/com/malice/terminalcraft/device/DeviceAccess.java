package com.malice.terminalcraft.device;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Caller-bound discovery and invocation surface exposed to sandboxed shell hosts. */
public interface DeviceAccess {
    DeviceCallContext context();

    List<DeviceDescriptor> descriptors(int limit);

    Optional<DeviceDescriptor> descriptor(UUID deviceId);

    DeviceResult call(UUID deviceId, String method, List<DeviceValue> arguments);

    /** Polls events emitted since this caller-bound access was created or last polled. */
    DeviceEventBatch pollEvents(int limit);
}
