package com.malice.terminalcraft.device;

import java.util.List;

/** Bounded event poll result. A positive dropped count means the caller fell behind retention. */
public record DeviceEventBatch(List<DeviceEvent> events, long dropped) {
    public DeviceEventBatch {
        events = List.copyOf(events);
        if (dropped < 0) throw new IllegalArgumentException("dropped event count must not be negative");
    }
}
