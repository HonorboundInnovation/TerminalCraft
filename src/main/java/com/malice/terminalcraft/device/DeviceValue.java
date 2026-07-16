package com.malice.terminalcraft.device;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded, implementation-neutral data exchanged with devices. */
public sealed interface DeviceValue permits DeviceValue.NullValue, DeviceValue.StringValue,
        DeviceValue.NumberValue, DeviceValue.BooleanValue, DeviceValue.ListValue,
        DeviceValue.MapValue {
    int MAX_COLLECTION_ENTRIES = 256;
    int MAX_STRING_LENGTH = 4096;
    int MAX_NESTING_DEPTH = 16;
    int MAX_TOTAL_NODES = 1024;
    int MAX_TOTAL_TEXT_LENGTH = 32768;

    DeviceValueType type();

    static DeviceValue nullValue() { return NullValue.INSTANCE; }
    static DeviceValue of(String value) { return value == null ? nullValue() : new StringValue(value); }
    static DeviceValue of(long value) { return new NumberValue(value); }
    static DeviceValue of(double value) { return new NumberValue(value); }
    static DeviceValue of(boolean value) { return new BooleanValue(value); }
    static DeviceValue list(List<? extends DeviceValue> values) {
        return new ListValue(new ArrayList<>(Objects.requireNonNull(values, "values")));
    }
    static DeviceValue map(Map<String, ? extends DeviceValue> values) {
        Map<String, DeviceValue> copy = new LinkedHashMap<>();
        Objects.requireNonNull(values, "values").forEach(copy::put);
        return new MapValue(copy);
    }

    /** Validates aggregate traversal and serialization cost without recursive Java calls. */
    static void requireWithinBudget(Iterable<? extends DeviceValue> roots, int maxNodes,
                                    int maxTextLength, String label) {
        Objects.requireNonNull(roots, "roots");
        Objects.requireNonNull(label, "label");
        if (maxNodes < 1 || maxTextLength < 0) throw new IllegalArgumentException("invalid value budget");

        Deque<ValueDepth> pending = new ArrayDeque<>();
        for (DeviceValue root : roots) {
            pending.addLast(new ValueDepth(Objects.requireNonNull(root, label + " value"), 1));
        }

        int nodes = 0;
        long textLength = 0;
        while (!pending.isEmpty()) {
            ValueDepth current = pending.removeLast();
            if (current.depth() > MAX_NESTING_DEPTH) {
                throw new IllegalArgumentException(label + " exceeds maximum nesting depth");
            }
            if (++nodes > maxNodes) throw new IllegalArgumentException(label + " exceeds node limit");

            DeviceValue value = current.value();
            if (value instanceof StringValue string) {
                textLength += string.value().length();
            } else if (value instanceof ListValue list) {
                for (DeviceValue child : list.values()) {
                    pending.addLast(new ValueDepth(child, current.depth() + 1));
                }
            } else if (value instanceof MapValue map) {
                for (Map.Entry<String, DeviceValue> entry : map.values().entrySet()) {
                    textLength += entry.getKey().length();
                    pending.addLast(new ValueDepth(entry.getValue(), current.depth() + 1));
                }
            }
            if (textLength > maxTextLength) {
                throw new IllegalArgumentException(label + " exceeds text limit");
            }
        }
    }

    record ValueDepth(DeviceValue value, int depth) {}

    enum NullValue implements DeviceValue {
        INSTANCE;
        @Override public DeviceValueType type() { return DeviceValueType.NULL; }
    }

    record StringValue(String value) implements DeviceValue {
        public StringValue {
            Objects.requireNonNull(value, "value");
            if (value.length() > MAX_STRING_LENGTH) throw new IllegalArgumentException("device string exceeds limit");
        }
        @Override public DeviceValueType type() { return DeviceValueType.STRING; }
    }

    record NumberValue(double value) implements DeviceValue {
        public NumberValue {
            if (!Double.isFinite(value)) throw new IllegalArgumentException("device number must be finite");
        }
        @Override public DeviceValueType type() { return DeviceValueType.NUMBER; }
    }

    record BooleanValue(boolean value) implements DeviceValue {
        @Override public DeviceValueType type() { return DeviceValueType.BOOLEAN; }
    }

    record ListValue(List<DeviceValue> values) implements DeviceValue {
        public ListValue(List<DeviceValue> values) {
            Objects.requireNonNull(values, "values");
            if (values.size() > MAX_COLLECTION_ENTRIES) throw new IllegalArgumentException("device list exceeds limit");
            List<DeviceValue> copy = new ArrayList<>(values.size());
            for (DeviceValue value : values) copy.add(Objects.requireNonNull(value, "list value"));
            this.values = Collections.unmodifiableList(copy);
            DeviceValue.requireWithinBudget(List.of(this), MAX_TOTAL_NODES,
                    MAX_TOTAL_TEXT_LENGTH, "device value");
        }
        @Override public DeviceValueType type() { return DeviceValueType.LIST; }
    }

    record MapValue(Map<String, DeviceValue> values) implements DeviceValue {
        public MapValue(Map<String, DeviceValue> values) {
            Objects.requireNonNull(values, "values");
            if (values.size() > MAX_COLLECTION_ENTRIES) throw new IllegalArgumentException("device map exceeds limit");
            Map<String, DeviceValue> copy = new LinkedHashMap<>();
            for (Map.Entry<String, DeviceValue> entry : values.entrySet()) {
                String key = Objects.requireNonNull(entry.getKey(), "map key");
                if (key.isBlank() || key.length() > MAX_STRING_LENGTH) throw new IllegalArgumentException("invalid map key");
                copy.put(key, Objects.requireNonNull(entry.getValue(), "map value"));
            }
            this.values = Collections.unmodifiableMap(copy);
            DeviceValue.requireWithinBudget(List.of(this), MAX_TOTAL_NODES,
                    MAX_TOTAL_TEXT_LENGTH, "device value");
        }
        @Override public DeviceValueType type() { return DeviceValueType.MAP; }
    }
}
