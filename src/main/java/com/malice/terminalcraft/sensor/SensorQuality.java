package com.malice.terminalcraft.sensor;

import java.util.Locale;

/** Explains why a channel value is usable, stale, or unavailable. */
public enum SensorQuality {
    OK,
    UNAVAILABLE,
    STALE,
    CHUNK_UNLOADED,
    AMBIGUOUS,
    PARTIAL,
    UNSUPPORTED;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
