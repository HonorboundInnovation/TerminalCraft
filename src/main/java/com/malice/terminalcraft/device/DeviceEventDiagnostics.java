package com.malice.terminalcraft.device;

/** Bounded operational counters for one event subscription. */
public record DeviceEventDiagnostics(int queued, long delivered, long dropped,
                                     long debounced, long coalesced) {
    public DeviceEventDiagnostics {
        if (queued < 0 || delivered < 0 || dropped < 0 || debounced < 0 || coalesced < 0) {
            throw new IllegalArgumentException("event diagnostics must not be negative");
        }
    }
}
