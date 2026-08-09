package com.malice.terminalcraft.device;

import java.util.List;

/** Bounded text widgets that work on every TerminalCraft monitor and monitor wall. */
public final class MonitorWidgets {
    private static final char[] SPARKS = {'▁', '▂', '▃', '▄', '▅', '▆', '▇', '█'};

    private MonitorWidgets() {}

    public static String bar(String label, int value, int width) {
        int boundedValue = Math.max(0, Math.min(100, value));
        int boundedWidth = Math.max(3, Math.min(32, width));
        int filled = (boundedValue * boundedWidth + 50) / 100;
        return fit(label) + " [" + "#".repeat(filled) + ".".repeat(boundedWidth - filled) + "] " + boundedValue + "%";
    }

    public static String led(String label, boolean on) {
        return (on ? "● ON  " : "○ OFF ") + fit(label);
    }

    public static String sparkline(String label, List<Integer> values) {
        StringBuilder result = new StringBuilder(fit(label)).append(' ');
        if (values == null || values.isEmpty()) return result.append("(no data)").toString();
        int minimum = values.stream().mapToInt(value -> value).min().orElse(0);
        int maximum = values.stream().mapToInt(value -> value).max().orElse(0);
        int range = Math.max(1, maximum - minimum);
        for (int value : values) {
            int index = Math.max(0, Math.min(SPARKS.length - 1, (value - minimum) * (SPARKS.length - 1) / range));
            result.append(SPARKS[index]);
        }
        return result.toString();
    }

    private static String fit(String value) {
        String safe = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
        return safe.length() <= 24 ? safe : safe.substring(0, 24);
    }
}
