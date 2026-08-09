package com.malice.terminalcraft.sensor;

import java.util.Objects;

/** One bounded sensor sample. Numeric samples are suitable for PLC normalization. */
public record SensorReading(String channel, SensorKind kind, String metric,
                            boolean numeric, double numericValue, String textValue,
                            String unit, long gameTime, SensorQuality quality, String detail) {
    public static final int MAX_TEXT = 256;
    public static final int MAX_DETAIL = 256;

    public SensorReading {
        channel = normalize(channel, "channel", 32);
        kind = Objects.requireNonNull(kind, "kind");
        metric = normalize(metric, "metric", 48);
        if (!Double.isFinite(numericValue)) throw new IllegalArgumentException("sensor number must be finite");
        textValue = textValue == null ? "" : bounded(textValue, MAX_TEXT);
        unit = unit == null ? "" : bounded(unit, 24);
        quality = Objects.requireNonNull(quality, "quality");
        detail = detail == null ? "" : bounded(detail, MAX_DETAIL);
        if (gameTime < 0) throw new IllegalArgumentException("sensor game time must not be negative");
    }

    public static SensorReading numeric(String channel, SensorKind kind, String metric,
                                        double value, String unit, long gameTime) {
        return new SensorReading(channel, kind, metric, true, value, format(value), unit,
                gameTime, SensorQuality.OK, "");
    }

    public static SensorReading text(String channel, SensorKind kind, String metric,
                                     String value, long gameTime) {
        return new SensorReading(channel, kind, metric, false, 0, value, "", gameTime,
                SensorQuality.OK, "");
    }

    public static SensorReading unavailable(String channel, SensorKind kind, String metric,
                                            SensorQuality quality, String detail, long gameTime) {
        if (quality == SensorQuality.OK) throw new IllegalArgumentException("unavailable reading must not be OK");
        return new SensorReading(channel, kind, metric, false, 0, "", "", gameTime, quality, detail);
    }

    /** Returns a PLC-compatible 0..15 value after channel calibration, or -1 when unusable. */
    public int signal(SensorChannel configuration) {
        if (configuration == null || !numeric || quality != SensorQuality.OK) return -1;
        double span = configuration.maximum() - configuration.minimum();
        if (!(span > 0.0)) return -1;
        double normalized = (numericValue - configuration.minimum()) * 15.0 / span;
        int result = (int) Math.round(Math.max(0.0, Math.min(15.0, normalized)));
        return configuration.invert() ? 15 - result : result;
    }

    private static String normalize(String value, String label, int max) {
        Objects.requireNonNull(value, label);
        String trimmed = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (trimmed.isEmpty() || trimmed.length() > max || !trimmed.matches("[a-z0-9_.:-]+")) {
            throw new IllegalArgumentException("invalid sensor " + label);
        }
        return trimmed;
    }

    private static String bounded(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String format(double value) {
        if (value == Math.rint(value)) return Long.toString((long) value);
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }
}
