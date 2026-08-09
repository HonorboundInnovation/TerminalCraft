package com.malice.terminalcraft.sensor;

import net.minecraft.core.Direction;

import java.util.Locale;
import java.util.Objects;

/** Persistent configuration for one Sensor Array channel. */
public record SensorChannel(String name, SensorKind kind, String target, String metric,
                            String selector, int interval, double minimum, double maximum,
                            boolean invert, boolean enabled) {
    public static final int MAX_NAME = 32;
    public static final int MAX_TARGET = 16;
    public static final int MAX_METRIC = 48;
    public static final int MAX_SELECTOR = 128;
    public static final int MAX_CHANNELS = 16;
    public static final int MIN_INTERVAL = 1;
    public static final int MAX_INTERVAL = 20;

    public SensorChannel {
        name = normalizeName(name);
        kind = Objects.requireNonNull(kind, "kind");
        target = normalizeTarget(target);
        metric = normalize(metric, "metric", MAX_METRIC, "value");
        selector = selector == null ? "" : bounded(selector.trim().toLowerCase(Locale.ROOT), MAX_SELECTOR);
        interval = Math.max(MIN_INTERVAL, Math.min(MAX_INTERVAL, interval));
        if (!Double.isFinite(minimum) || !Double.isFinite(maximum) || maximum <= minimum) {
            throw new IllegalArgumentException("sensor calibration range must be finite and increasing");
        }
    }

    public static SensorChannel create(String name, SensorKind kind, String target, String metric,
                                       String selector, int interval, boolean enabled) {
        String normalizedMetric = normalize(metric, "metric", MAX_METRIC, defaultMetric(kind));
        double[] range = defaultRange(kind, normalizedMetric);
        return new SensorChannel(name, kind, target, normalizedMetric, selector, interval,
                range[0], range[1], false, enabled);
    }

    public SensorChannel withEnabled(boolean value) {
        return new SensorChannel(name, kind, target, metric, selector, interval,
                minimum, maximum, invert, value);
    }

    public SensorChannel withCalibration(double min, double max, boolean reverse) {
        return new SensorChannel(name, kind, target, metric, selector, interval,
                min, max, reverse, enabled);
    }

    public SensorChannel withInterval(int ticks) {
        return new SensorChannel(name, kind, target, metric, selector, ticks,
                minimum, maximum, invert, enabled);
    }

    public static String canonicalName(String value) {
        if (value == null || value.isBlank()) return "";
        String candidate = value.trim().toLowerCase(Locale.ROOT);
        if (candidate.matches("[0-9]{1,2}")) return "ch" + candidate;
        return candidate;
    }

    private static String normalizeName(String value) {
        String normalized = canonicalName(value);
        if (normalized.isEmpty() || normalized.length() > MAX_NAME
                || !normalized.matches("[a-z][a-z0-9_.-]*")) {
            throw new IllegalArgumentException("invalid sensor channel name");
        }
        return normalized;
    }

    private static String normalizeTarget(String value) {
        String target = value == null || value.isBlank() ? "self" : value.trim().toLowerCase(Locale.ROOT);
        if (!"self".equals(target) && Direction.byName(target) == null) {
            throw new IllegalArgumentException("sensor target must be self or a block face");
        }
        return target;
    }

    private static String normalize(String value, String label, int max, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > max || !normalized.matches("[a-z0-9_.:-]+")) {
            throw new IllegalArgumentException("invalid sensor " + label);
        }
        return normalized;
    }

    private static String bounded(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String defaultMetric(SensorKind kind) {
        return switch (kind) {
            case REDSTONE -> "level";
            case BLOCK_STATE -> "powered";
            case INVENTORY -> "fill_percent";
            case FLUID -> "fill_percent";
            case ENERGY -> "fill_percent";
            case ENTITY -> "count";
            case MACHINE -> "active";
            case ENVIRONMENT -> "light";
            case NETWORK -> "online";
            case KINETIC -> "speed";
            case CHEMICAL -> "amount";
        };
    }

    private static double[] defaultRange(SensorKind kind, String metric) {
        if (metric.contains("percent") || metric.equals("fill")) return new double[]{0, 100};
        if (metric.equals("powered") || metric.equals("active") || metric.equals("lit")
                || metric.equals("online") || metric.equals("present") || metric.startsWith("can_")) {
            return new double[]{0, 1};
        }
        if (kind == SensorKind.REDSTONE || metric.contains("light") || metric.equals("level")) {
            return new double[]{0, 15};
        }
        return new double[]{0, 100};
    }
}
