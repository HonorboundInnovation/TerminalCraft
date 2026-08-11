package com.malice.terminalcraft.scada;

import com.malice.terminalcraft.device.DeviceValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Persistent binding from a human process tag to one typed device method. */
public record ScadaTag(String name, UUID deviceId, String readMethod, List<DeviceValue> arguments,
                       String valuePath, String unit, int sampleIntervalTicks, int staleAfterTicks,
                       String writeMethod) {
    public static final int MAX_NAME_CHARS = 96;
    public static final int MAX_PATH_CHARS = 128;
    public static final int MAX_UNIT_CHARS = 24;
    public static final int MAX_ARGUMENTS = 8;
    public static final int MIN_SAMPLE_INTERVAL = 1;
    public static final int MAX_SAMPLE_INTERVAL = 20 * 60;
    public static final int MAX_STALE_TICKS = 20 * 60 * 60;

    public ScadaTag {
        name = canonicalName(name);
        deviceId = Objects.requireNonNull(deviceId, "deviceId");
        readMethod = methodName(readMethod, "SCADA read method");
        arguments = List.copyOf(Objects.requireNonNullElse(arguments, List.of()));
        if (arguments.size() > MAX_ARGUMENTS) throw new IllegalArgumentException("too many SCADA arguments");
        for (DeviceValue argument : arguments) {
            if (ScadaScalar.from(argument).isEmpty()) {
                throw new IllegalArgumentException("SCADA arguments must be scalar values");
            }
        }
        valuePath = normalizePath(valuePath);
        unit = Objects.requireNonNullElse(unit, "").trim();
        if (unit.length() > MAX_UNIT_CHARS || unit.indexOf('\n') >= 0 || unit.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("invalid SCADA unit");
        }
        if (sampleIntervalTicks < MIN_SAMPLE_INTERVAL || sampleIntervalTicks > MAX_SAMPLE_INTERVAL) {
            throw new IllegalArgumentException("SCADA sample interval is outside bounds");
        }
        if (staleAfterTicks < sampleIntervalTicks || staleAfterTicks > MAX_STALE_TICKS) {
            throw new IllegalArgumentException("SCADA stale interval is outside bounds");
        }
        writeMethod = Objects.requireNonNullElse(writeMethod, "").trim().toLowerCase(Locale.ROOT);
        if (!writeMethod.isEmpty()) {
            boolean command = writeMethod.startsWith("@");
            String callable = methodName(command ? writeMethod.substring(1) : writeMethod, "SCADA write method");
            writeMethod = command ? "@" + callable : callable;
        }
    }

    public static String canonicalName(String requested) {
        String value = Objects.requireNonNullElse(requested, "").trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || value.length() > MAX_NAME_CHARS
                || !value.matches("[a-z0-9_-]+(?:\\.[a-z0-9_-]+)*")) {
            throw new IllegalArgumentException("tag name must be dot-separated letters, numbers, '_' or '-'");
        }
        return value;
    }

    public static String normalizePath(String requested) {
        String value = Objects.requireNonNullElse(requested, "").trim();
        if ("-".equals(value)) value = "";
        if (value.length() > MAX_PATH_CHARS || value.startsWith("/") || value.endsWith("/")
                || (!value.isEmpty() && !value.matches("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*"))) {
            throw new IllegalArgumentException("value path must use slash-separated map keys or list indexes");
        }
        return value;
    }

    public static List<DeviceValue> copyArguments(List<DeviceValue> values) {
        return List.copyOf(new ArrayList<>(Objects.requireNonNullElse(values, List.of())));
    }

    /** An @method binding invokes the method with static arguments and no appended value. */
    public boolean writeRequiresValue() { return !writeMethod.isEmpty() && !writeMethod.startsWith("@"); }

    public String callableWriteMethod() {
        return writeMethod.startsWith("@") ? writeMethod.substring(1) : writeMethod;
    }

    private static String methodName(String requested, String label) {
        String value = Objects.requireNonNullElse(requested, "").trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z][a-z0-9_.-]{0,63}")) throw new IllegalArgumentException("invalid " + label);
        return value;
    }
}
