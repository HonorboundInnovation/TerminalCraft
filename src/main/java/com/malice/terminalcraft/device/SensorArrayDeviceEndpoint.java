package com.malice.terminalcraft.device;

import com.malice.terminalcraft.blockentity.SensorArrayBlockEntity;
import com.malice.terminalcraft.sensor.SensorChannel;
import com.malice.terminalcraft.sensor.SensorKind;
import com.malice.terminalcraft.sensor.SensorReading;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Device API adapter for a universal Sensor Array. */
public final class SensorArrayDeviceEndpoint implements ContextualDeviceEndpoint {
    private static final DeviceParameterDescriptor CHANNEL = new DeviceParameterDescriptor(
            "channel", DeviceValueType.STRING, true, "Sensor channel name");
    private static final DeviceParameterDescriptor KIND = new DeviceParameterDescriptor(
            "kind", DeviceValueType.STRING, true, "Sensor kind");
    private static final DeviceParameterDescriptor TARGET = new DeviceParameterDescriptor(
            "target", DeviceValueType.STRING, true, "self or adjacent block face");
    private static final DeviceParameterDescriptor METRIC = new DeviceParameterDescriptor(
            "metric", DeviceValueType.STRING, true, "Bounded metric name");
    private static final DeviceParameterDescriptor SELECTOR = new DeviceParameterDescriptor(
            "selector", DeviceValueType.STRING, false, "Optional namespaced resource or entity selector");
    private static final DeviceParameterDescriptor INTERVAL = new DeviceParameterDescriptor(
            "interval", DeviceValueType.NUMBER, false, "Sample interval in ticks");
    private static final DeviceParameterDescriptor ENABLED = new DeviceParameterDescriptor(
            "enabled", DeviceValueType.BOOLEAN, true, "Whether the channel samples");
    private static final DeviceParameterDescriptor MINIMUM = new DeviceParameterDescriptor(
            "minimum", DeviceValueType.NUMBER, true, "PLC normalization minimum");
    private static final DeviceParameterDescriptor MAXIMUM = new DeviceParameterDescriptor(
            "maximum", DeviceValueType.NUMBER, true, "PLC normalization maximum");
    private static final DeviceParameterDescriptor INVERT = new DeviceParameterDescriptor(
            "invert", DeviceValueType.BOOLEAN, false, "Invert the normalized PLC signal");

    private static final DeviceMethodDescriptor LIST = read("sensor.list",
            "Lists configured sensor channels", List.of(), DeviceValueType.LIST);
    private static final DeviceMethodDescriptor READ = read("sensor.read",
            "Reads one sampled sensor channel", List.of(CHANNEL), DeviceValueType.MAP);
    private static final DeviceMethodDescriptor SNAPSHOT = read("sensor.snapshot",
            "Returns all sampled sensor values", List.of(), DeviceValueType.LIST);
    private static final DeviceMethodDescriptor CONFIGURE = write("sensor.configure",
            "Creates or replaces one sensor channel", List.of(CHANNEL, KIND, TARGET, METRIC, SELECTOR, INTERVAL));
    private static final DeviceMethodDescriptor ENABLE = write("sensor.enable",
            "Enables or disables one channel", List.of(CHANNEL, ENABLED));
    private static final DeviceMethodDescriptor REMOVE = write("sensor.remove",
            "Removes one channel", List.of(CHANNEL));
    private static final DeviceMethodDescriptor CALIBRATE = write("sensor.calibrate",
            "Sets PLC signal normalization for one channel", List.of(CHANNEL, MINIMUM, MAXIMUM, INVERT));
    private static final DeviceMethodDescriptor SET_INTERVAL = write("sensor.interval",
            "Sets one channel's sample interval", List.of(CHANNEL, INTERVAL));

    private final UUID deviceId;
    private final String address;
    private final SensorArrayBlockEntity array;
    private final BooleanSupplier online;
    private final BooleanSupplier loaded;

    public SensorArrayDeviceEndpoint(UUID deviceId, String address, SensorArrayBlockEntity array,
                                     BooleanSupplier online, BooleanSupplier loaded) {
        this.deviceId = Objects.requireNonNull(deviceId, "deviceId");
        this.address = Objects.requireNonNull(address, "address");
        this.array = Objects.requireNonNull(array, "array");
        this.online = Objects.requireNonNull(online, "online");
        this.loaded = Objects.requireNonNull(loaded, "loaded");
    }

    @Override
    public DeviceDescriptor descriptor() {
        Map<String, DeviceValue> properties = new LinkedHashMap<>();
        properties.put("channel_count", DeviceValue.of(array.channels().size()));
        properties.put("max_channels", DeviceValue.of(SensorArrayBlockEntity.MAX_CHANNELS));
        properties.put("label", DeviceValue.of(array.getLabel()));
        return new DeviceDescriptor(deviceId, "terminalcraft:sensor_array", "sensor_array",
                array.getLabel(), "terminalcraft", address,
                Set.of("sensor_array", "telemetry", "plc_input", "forge_capabilities"), properties,
                List.of(LIST, READ, SNAPSHOT, CONFIGURE, ENABLE, REMOVE, CALIBRATE, SET_INTERVAL),
                Set.of("sensor_changed"), Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE),
                online.getAsBoolean(), loaded.getAsBoolean());
    }

    @Override
    public DeviceResult call(DeviceCallContext context, String method, List<DeviceValue> arguments) {
        List<DeviceValue> args = arguments == null ? List.of() : arguments;
        try {
            return switch (method == null ? "" : method) {
                case "sensor.list" -> noArguments(args, () -> DeviceResult.success(channelValues()));
                case "sensor.read" -> read(args);
                case "sensor.snapshot" -> noArguments(args, () -> DeviceResult.success(snapshot()));
                case "sensor.configure" -> configure(args);
                case "sensor.enable" -> enable(args);
                case "sensor.remove" -> remove(args);
                case "sensor.calibrate" -> calibrate(args);
                case "sensor.interval" -> interval(args);
                default -> DeviceResult.failure(DeviceErrorCode.UNSUPPORTED, "method is unsupported", false);
            };
        } catch (IllegalArgumentException invalid) {
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT, invalid.getMessage(), false);
        }
    }

    private DeviceResult read(List<DeviceValue> args) {
        if (args.size() != 1) return invalid("sensor.read requires channel");
        SensorReading reading = array.reading(string(args.get(0), "channel"));
        return reading == null ? DeviceResult.failure(DeviceErrorCode.NOT_FOUND,
                "sensor channel has no sample", true) : DeviceResult.success(readingValue(reading));
    }

    private DeviceResult configure(List<DeviceValue> args) {
        if (args.size() < 4 || args.size() > 6) return invalid("sensor.configure requires channel, kind, target, metric, [selector], [interval]");
        String channel = string(args.get(0), "channel");
        SensorKind kind = SensorKind.parse(string(args.get(1), "kind"));
        String target = string(args.get(2), "target");
        String metric = string(args.get(3), "metric");
        String selector = args.size() > 4 ? string(args.get(4), "selector") : "";
        int interval = args.size() > 5 ? integer(args.get(5), 1, SensorChannel.MAX_INTERVAL, "interval") : 1;
        if (kind == null || !array.configure(channel, kind, target, metric, selector, interval)) {
            return invalid("sensor configuration rejected");
        }
        return DeviceResult.success();
    }

    private DeviceResult enable(List<DeviceValue> args) {
        if (args.size() != 2) return invalid("sensor.enable requires channel and enabled");
        if (!(args.get(1) instanceof DeviceValue.BooleanValue enabled)) return invalid("enabled must be boolean");
        return array.setEnabled(string(args.get(0), "channel"), enabled.value())
                ? DeviceResult.success() : DeviceResult.failure(DeviceErrorCode.NOT_FOUND, "sensor channel not found", false);
    }

    private DeviceResult remove(List<DeviceValue> args) {
        if (args.size() != 1) return invalid("sensor.remove requires channel");
        return array.remove(string(args.get(0), "channel"))
                ? DeviceResult.success() : DeviceResult.failure(DeviceErrorCode.NOT_FOUND, "sensor channel not found", false);
    }

    private DeviceResult calibrate(List<DeviceValue> args) {
        if (args.size() < 3 || args.size() > 4) return invalid("sensor.calibrate requires channel, minimum, maximum, [invert]");
        double minimum = number(args.get(1), "minimum");
        double maximum = number(args.get(2), "maximum");
        boolean invert = args.size() == 4 && booleanValue(args.get(3), "invert");
        return array.calibrate(string(args.get(0), "channel"), minimum, maximum, invert)
                ? DeviceResult.success() : invalid("sensor calibration rejected");
    }

    private DeviceResult interval(List<DeviceValue> args) {
        if (args.size() != 2) return invalid("sensor.interval requires channel and ticks");
        return array.setInterval(string(args.get(0), "channel"), integer(args.get(1), 1,
                SensorChannel.MAX_INTERVAL, "interval"))
                ? DeviceResult.success() : invalid("sensor interval rejected");
    }

    private DeviceValue channelValues() {
        List<DeviceValue> result = new ArrayList<>();
        for (SensorChannel channel : array.channels()) {
            Map<String, DeviceValue> value = new LinkedHashMap<>();
            value.put("channel", DeviceValue.of(channel.name()));
            value.put("kind", DeviceValue.of(channel.kind().id()));
            value.put("target", DeviceValue.of(channel.target()));
            value.put("metric", DeviceValue.of(channel.metric()));
            value.put("selector", DeviceValue.of(channel.selector()));
            value.put("interval", DeviceValue.of(channel.interval()));
            value.put("enabled", DeviceValue.of(channel.enabled()));
            value.put("minimum", DeviceValue.of(channel.minimum()));
            value.put("maximum", DeviceValue.of(channel.maximum()));
            value.put("invert", DeviceValue.of(channel.invert()));
            result.add(DeviceValue.map(value));
        }
        return DeviceValue.list(result);
    }

    private DeviceValue snapshot() {
        List<DeviceValue> result = new ArrayList<>();
        for (SensorReading reading : array.readings().values()) result.add(readingValue(reading));
        return DeviceValue.list(result);
    }

    private static DeviceValue readingValue(SensorReading reading) {
        Map<String, DeviceValue> value = new LinkedHashMap<>();
        value.put("channel", DeviceValue.of(reading.channel()));
        value.put("kind", DeviceValue.of(reading.kind().id()));
        value.put("metric", DeviceValue.of(reading.metric()));
        value.put("quality", DeviceValue.of(reading.quality().id()));
        value.put("numeric", DeviceValue.of(reading.numeric()));
        value.put("value", reading.numeric() ? DeviceValue.of(reading.numericValue()) : DeviceValue.of(reading.textValue()));
        value.put("text", DeviceValue.of(reading.textValue()));
        value.put("unit", DeviceValue.of(reading.unit()));
        value.put("game_time", DeviceValue.of(reading.gameTime()));
        value.put("detail", DeviceValue.of(reading.detail()));
        return DeviceValue.map(value);
    }

    private static DeviceResult noArguments(List<DeviceValue> args,
                                            java.util.function.Supplier<DeviceResult> operation) {
        return args.isEmpty() ? operation.get() : invalid("method takes no arguments");
    }

    private static DeviceResult invalid(String message) {
        return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT, message, false);
    }

    private static DeviceMethodDescriptor read(String name, String description,
                                               List<DeviceParameterDescriptor> parameters,
                                               DeviceValueType type) {
        return new DeviceMethodDescriptor(name, description, parameters, type, DeviceCallContext.READ);
    }

    private static DeviceMethodDescriptor write(String name, String description,
                                                List<DeviceParameterDescriptor> parameters) {
        return new DeviceMethodDescriptor(name, description, parameters, DeviceValueType.NULL, DeviceCallContext.WRITE);
    }

    private static String string(DeviceValue value, String name) {
        if (!(value instanceof DeviceValue.StringValue string) || string.value().isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-empty string");
        }
        return string.value();
    }

    private static double number(DeviceValue value, String name) {
        if (!(value instanceof DeviceValue.NumberValue number) || !Double.isFinite(number.value())) {
            throw new IllegalArgumentException(name + " must be a finite number");
        }
        return number.value();
    }

    private static int integer(DeviceValue value, int minimum, int maximum, String name) {
        double number = number(value, name);
        if (number != Math.rint(number) || number < minimum || number > maximum) {
            throw new IllegalArgumentException(name + " must be an integer from " + minimum + " to " + maximum);
        }
        return (int) number;
    }

    private static boolean booleanValue(DeviceValue value, String name) {
        if (!(value instanceof DeviceValue.BooleanValue bool)) throw new IllegalArgumentException(name + " must be boolean");
        return bool.value();
    }
}
