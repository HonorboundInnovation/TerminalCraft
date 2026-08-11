package com.malice.terminalcraft.scada;

import java.util.Locale;
import java.util.Objects;

/** Persistent alarm rule evaluated after every acquired sample. */
public record ScadaAlarmRule(String name, String tagName, Operator operator, ScadaScalar threshold,
                             Severity severity, double deadband, String message) {
    public static final int MAX_NAME_CHARS = 64;
    public static final int MAX_MESSAGE_CHARS = 160;

    public enum Operator {
        ABOVE, BELOW, EQUAL, NOT_EQUAL, BAD_QUALITY;

        public static Operator parse(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        }
    }

    public enum Severity {
        INFO, WARNING, HIGH, CRITICAL;

        public static Severity parse(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    public ScadaAlarmRule {
        name = canonicalRuleName(name);
        tagName = ScadaTag.canonicalName(tagName);
        operator = Objects.requireNonNull(operator, "operator");
        severity = Objects.requireNonNull(severity, "severity");
        if (operator != Operator.BAD_QUALITY && threshold == null) {
            throw new IllegalArgumentException("alarm threshold is required");
        }
        if ((operator == Operator.ABOVE || operator == Operator.BELOW)
                && threshold.type() != ScadaScalar.Type.NUMBER) {
            throw new IllegalArgumentException("above/below alarms require a numeric threshold");
        }
        if (!Double.isFinite(deadband) || deadband < 0) throw new IllegalArgumentException("alarm deadband must be non-negative");
        message = Objects.requireNonNullElse(message, "").trim();
        if (message.length() > MAX_MESSAGE_CHARS) message = message.substring(0, MAX_MESSAGE_CHARS);
    }

    public static String canonicalRuleName(String requested) {
        String value = Objects.requireNonNullElse(requested, "").trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || value.length() > MAX_NAME_CHARS || !value.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("invalid alarm name");
        }
        return value;
    }
}
