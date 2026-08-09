package com.malice.terminalcraft.device;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** One non-blocking result from a caller-owned event wait token. */
public record DeviceEventWaitResult(UUID waitId, UUID subscriptionId, Status status,
                                    long wakeAt, Optional<DeviceEvent> event, long dropped) {
    public enum Status { WAITING, EVENT, TIMEOUT }

    public DeviceEventWaitResult {
        waitId = Objects.requireNonNull(waitId, "waitId");
        subscriptionId = Objects.requireNonNull(subscriptionId, "subscriptionId");
        status = Objects.requireNonNull(status, "status");
        event = Objects.requireNonNull(event, "event");
        if (wakeAt < 0) throw new IllegalArgumentException("event wait wake tick must not be negative");
        if (dropped < 0) throw new IllegalArgumentException("event wait dropped count must not be negative");
        if (status == Status.EVENT && event.isEmpty()) {
            throw new IllegalArgumentException("event wait event result requires an event");
        }
        if (status != Status.EVENT && event.isPresent()) {
            throw new IllegalArgumentException("non-event wait result must not carry an event");
        }
    }

    public static DeviceEventWaitResult waiting(UUID waitId, UUID subscriptionId,
                                                long wakeAt, long dropped) {
        return new DeviceEventWaitResult(waitId, subscriptionId, Status.WAITING,
                wakeAt, Optional.empty(), dropped);
    }

    public static DeviceEventWaitResult event(UUID waitId, UUID subscriptionId,
                                              long wakeAt, DeviceEvent event, long dropped) {
        return new DeviceEventWaitResult(waitId, subscriptionId, Status.EVENT,
                wakeAt, Optional.of(Objects.requireNonNull(event, "event")), dropped);
    }

    public static DeviceEventWaitResult timeout(UUID waitId, UUID subscriptionId,
                                                long wakeAt, long dropped) {
        return new DeviceEventWaitResult(waitId, subscriptionId, Status.TIMEOUT,
                wakeAt, Optional.empty(), dropped);
    }
}
