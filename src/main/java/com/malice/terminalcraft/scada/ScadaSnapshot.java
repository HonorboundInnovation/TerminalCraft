package com.malice.terminalcraft.scada;

import java.util.Objects;

/** Latest value and quality for a process tag. */
public record ScadaSnapshot(ScadaScalar value, ScadaQuality quality, long sampledAt,
                            long lastGoodAt, String detail) {
    public static final int MAX_DETAIL_CHARS = 256;

    public ScadaSnapshot {
        quality = Objects.requireNonNull(quality, "quality");
        if (sampledAt < 0 || lastGoodAt < -1 || lastGoodAt > sampledAt) {
            throw new IllegalArgumentException("invalid SCADA snapshot time");
        }
        detail = Objects.requireNonNullElse(detail, "");
        if (detail.length() > MAX_DETAIL_CHARS) detail = detail.substring(0, MAX_DETAIL_CHARS);
        if (quality == ScadaQuality.GOOD && value == null) {
            throw new IllegalArgumentException("good SCADA snapshots require a value");
        }
    }

    public ScadaSnapshot effective(long now, int staleAfterTicks) {
        if (quality != ScadaQuality.GOOD || now - sampledAt <= staleAfterTicks) return this;
        return new ScadaSnapshot(value, ScadaQuality.STALE, sampledAt, lastGoodAt,
                "last update exceeded " + staleAfterTicks + " ticks");
    }

    public String display(String unit) {
        String rendered = value == null ? "(no value)" : value.display() + (unit == null || unit.isBlank() ? "" : " " + unit);
        return rendered + " [" + quality.id() + "]";
    }
}
