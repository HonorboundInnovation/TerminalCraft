package com.malice.terminalcraft.scada;

import java.util.Locale;
import java.util.Objects;

/** One bounded widget placed on the normalized 12 by 12 advanced-HMI canvas. */
public record ScadaHmiWidget(String id, Type type, int x, int y, int width, int height,
                             String source, String label, double minimum, double maximum,
                             ScadaScalar actionValue) {
    public static final int GRID_WIDTH = 12;
    public static final int GRID_HEIGHT = 12;
    public static final int MAX_ID_CHARS = 32;
    public static final int MAX_LABEL_CHARS = 40;

    public enum Type {
        TEXT, VALUE, GAUGE, TREND, ALARMS, BUTTON, PAGE_LINK;

        public static Type parse(String requested) {
            return valueOf(Objects.requireNonNullElse(requested, "").trim()
                    .toUpperCase(Locale.ROOT).replace('-', '_'));
        }

        public boolean interactive() { return this == BUTTON || this == PAGE_LINK; }
        public boolean tagBound() { return this == VALUE || this == GAUGE || this == TREND || this == BUTTON; }
    }

    public ScadaHmiWidget {
        id = canonicalId(id);
        type = Objects.requireNonNull(type, "type");
        if (x < 0 || y < 0 || width < 1 || height < 1
                || x + width > GRID_WIDTH || y + height > GRID_HEIGHT) {
            throw new IllegalArgumentException("HMI widget bounds must fit the 12x12 grid");
        }
        source = Objects.requireNonNullElse(source, "").trim().toLowerCase(Locale.ROOT);
        if (type.tagBound()) {
            source = ScadaTag.canonicalName(source);
        } else if (type == Type.PAGE_LINK) {
            source = ScadaHmiPage.canonicalName(source);
        } else if (type == Type.ALARMS && !source.isBlank() && !"*".equals(source) && !"-".equals(source)) {
            source = ScadaTag.canonicalName(source);
        } else if (type == Type.TEXT) {
            source = "";
        }
        if ("*".equals(source) || "-".equals(source)) source = "";
        label = boundedLabel(label == null || label.isBlank() ? id : label);
        if (!Double.isFinite(minimum) || !Double.isFinite(maximum)) {
            throw new IllegalArgumentException("HMI widget range must be finite");
        }
        if (type == Type.GAUGE && minimum >= maximum) {
            throw new IllegalArgumentException("HMI gauge maximum must exceed minimum");
        }
        if (type != Type.BUTTON && actionValue != null) {
            throw new IllegalArgumentException("only HMI button widgets accept an action value");
        }
    }

    public static String canonicalId(String requested) {
        String value = Objects.requireNonNullElse(requested, "").trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || value.length() > MAX_ID_CHARS || !value.matches("[a-z0-9_-]+")) {
            throw new IllegalArgumentException("HMI widget id must use letters, numbers, '_' or '-'");
        }
        return value;
    }

    public ScadaHmiWidget withBounds(int nextX, int nextY, int nextWidth, int nextHeight) {
        return new ScadaHmiWidget(id, type, nextX, nextY, nextWidth, nextHeight,
                source, label, minimum, maximum, actionValue);
    }

    private static String boundedLabel(String requested) {
        String value = Objects.requireNonNullElse(requested, "").trim().replace('\n', ' ').replace('\r', ' ');
        if (value.isEmpty()) throw new IllegalArgumentException("HMI widget label is required");
        return value.length() <= MAX_LABEL_CHARS ? value : value.substring(0, MAX_LABEL_CHARS);
    }
}
