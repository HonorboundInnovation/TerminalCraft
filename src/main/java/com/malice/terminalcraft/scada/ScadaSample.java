package com.malice.terminalcraft.scada;

import java.util.Objects;

/** One historian point. */
public record ScadaSample(long gameTime, ScadaScalar value, ScadaQuality quality) {
    public ScadaSample {
        if (gameTime < 0) throw new IllegalArgumentException("historian time must not be negative");
        quality = Objects.requireNonNull(quality, "quality");
    }
}
