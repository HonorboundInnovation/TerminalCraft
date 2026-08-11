package com.malice.terminalcraft.scada;

import java.util.Locale;

/** Quality carried with every SCADA value; the last known value is retained when quality degrades. */
public enum ScadaQuality {
    GOOD(true),
    STALE(false),
    OFFLINE(false),
    ACCESS_DENIED(false),
    BAD_RESPONSE(false),
    CONFIG_ERROR(false);

    private final boolean usable;

    ScadaQuality(boolean usable) {
        this.usable = usable;
    }

    public boolean usable() { return usable; }

    public String id() { return name().toLowerCase(Locale.ROOT); }

    public String category() {
        return this == GOOD ? "good" : this == STALE ? "stale" : "bad";
    }
}
