package com.malice.terminalcraft.device;

import com.malice.terminalcraft.blockentity.StandaloneSensorBlockEntity;
import com.malice.terminalcraft.sensor.SensorChannel;
import com.malice.terminalcraft.sensor.SensorReading;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Device API adapter for one fixed-family sensor block. */
public final class StandaloneSensorDeviceEndpoint implements ContextualDeviceEndpoint {
    private static final DeviceParameterDescriptor CHANNEL = new DeviceParameterDescriptor(
            "channel", DeviceValueType.STRING, true, "The fixed channel name: value");
    private static final DeviceParameterDescriptor METRIC = new DeviceParameterDescriptor(
            "metric", DeviceValueType.STRING, true, "Metric exposed by this sensor family");
    private static final DeviceParameterDescriptor SELECTOR = new DeviceParameterDescriptor(
            "selector", DeviceValueType.STRING, false, "Optional item, fluid, entity, or network selector");
    private static final DeviceParameterDescriptor INTERVAL = new DeviceParameterDescriptor(
            "interval", DeviceValueType.NUMBER, false, "Sample interval in ticks");
    private static final DeviceParameterDescriptor ENABLED = new DeviceParameterDescriptor(
            "enabled", DeviceValueType.BOOLEAN, true, "Whether the sensor samples");
    private static final DeviceParameterDescriptor MINIMUM = new DeviceParameterDescriptor(
            "minimum", DeviceValueType.NUMBER, true, "PLC normalization minimum");
    private static final DeviceParameterDescriptor MAXIMUM = new DeviceParameterDescriptor(
            "maximum", DeviceValueType.NUMBER, true, "PLC normalization maximum");
    private static final DeviceParameterDescriptor INVERT = new DeviceParameterDescriptor(
            "invert", DeviceValueType.BOOLEAN, false, "Invert the normalized PLC signal");

    private static final DeviceMethodDescriptor LIST = read("sensor.list", "Describes this fixed sensor channel", List.of(), DeviceValueType.LIST);
    private static final DeviceMethodDescriptor READ = read("sensor.read", "Reads the sampled sensor value", List.of(CHANNEL), DeviceValueType.MAP);
    private static final DeviceMethodDescriptor SNAPSHOT = read("sensor.snapshot", "Returns the sampled sensor value", List.of(), DeviceValueType.LIST);
    private static final DeviceMethodDescriptor CONFIGURE = write("sensor.configure", "Changes the metric, selector, and sample interval", List.of(METRIC, SELECTOR, INTERVAL));
    private static final DeviceMethodDescriptor ENABLE = write("sensor.enable", "Enables or disables this sensor", List.of(CHANNEL, ENABLED));
    private static final DeviceMethodDescriptor CALIBRATE = write("sensor.calibrate", "Sets PLC signal normalization", List.of(CHANNEL, MINIMUM, MAXIMUM, INVERT));
    private static final DeviceMethodDescriptor SET_INTERVAL = write("sensor.interval", "Sets the sample interval", List.of(CHANNEL, INTERVAL));

    private final UUID deviceId;
    private final String address;
    private final StandaloneSensorBlockEntity sensor;
    private final BooleanSupplier online;
    private final BooleanSupplier loaded;

    public StandaloneSensorDeviceEndpoint(UUID deviceId, String address, StandaloneSensorBlockEntity sensor,
                                          BooleanSupplier online, BooleanSupplier loaded) {
        this.deviceId = Objects.requireNonNull(deviceId, "deviceId");
        this.address = Objects.requireNonNull(address, "address");
        this.sensor = Objects.requireNonNull(sensor, "sensor");
        this.online = Objects.requireNonNull(online, "online");
        this.loaded = Objects.requireNonNull(loaded, "loaded");
    }

    @Override
    public DeviceDescriptor descriptor() {
        SensorChannel channel = sensor.configuration();
        Map<String, DeviceValue> properties = new LinkedHashMap<>();
        properties.put("kind", DeviceValue.of(sensor.sensorKind().id()));
        properties.put("channel", DeviceValue.of(channel.name()));
        properties.put("metric", DeviceValue.of(channel.metric()));
        properties.put("target", DeviceValue.of(channel.target()));
        properties.put("enabled", DeviceValue.of(channel.enabled()));
        return new DeviceDescriptor(deviceId, "terminalcraft:standalone_sensor", "standalone_sensor",
                sensor.getLabel(), "terminalcraft", address,
                Set.of("sensor", "telemetry", "plc_input", "forge_capabilities"), properties,
                List.of(LIST, READ, SNAPSHOT, CONFIGURE, ENABLE, CALIBRATE, SET_INTERVAL),
                Set.of("sensor_changed"), Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE),
                online.getAsBoolean(), loaded.getAsBoolean());
    }

    @Override
    public DeviceResult call(DeviceCallContext context, String method, List<DeviceValue> arguments) {
        List<DeviceValue> args = arguments == null ? List.of() : arguments;
        try {
            return switch (method == null ? "" : method) {
                case "sensor.list" -> noArguments(args, () -> DeviceResult.success(listValue()));
                case "sensor.read" -> read(args);
                case "sensor.snapshot" -> noArguments(args, () -> DeviceResult.success(snapshotValue()));
                case "sensor.configure" -> configure(args);
                case "sensor.enable" -> enable(args);
                case "sensor.calibrate" -> calibrate(args);
                case "sensor.interval" -> interval(args);
                default -> DeviceResult.failure(DeviceErrorCode.UNSUPPORTED, "method is unsupported", false);
            };
        } catch (IllegalArgumentException invalid) {
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT, invalid.getMessage(), false);
        }
    }

    private DeviceResult read(List<DeviceValue> args) {
        if (args.size() > 1) return invalid("sensor.read accepts an optional channel");
        if (!args.isEmpty()) requireChannel(args.get(0));
        SensorReading sample = sensor.reading();
        return sample == null ? DeviceResult.failure(DeviceErrorCode.NOT_FOUND, "sensor has no sample", true)
                : DeviceResult.success(readingValue(sample));
    }

    private DeviceResult configure(List<DeviceValue> args) {
        if (args.isEmpty() || args.size() > 3) return invalid("sensor.configure requires metric, [selector], [interval]");
        String metric = string(args.get(0), "metric");
        String selector = args.size() > 1 ? string(args.get(1), "selector") : "";
        int interval = args.size() > 2 ? integer(args.get(2), 1, SensorChannel.MAX_INTERVAL, "interval") : 1;
        return sensor.configure(metric, selector, interval) ? DeviceResult.success() : invalid("sensor configuration rejected");
    }

    private DeviceResult enable(List<DeviceValue> args) {
        if (args.size() != 2) return invalid("sensor.enable requires channel and enabled");
        requireChannel(args.get(0));
        return sensor.setEnabled(booleanValue(args.get(1), "enabled")) ? DeviceResult.success() : invalid("sensor enable rejected");
    }

    private DeviceResult calibrate(List<DeviceValue> args) {
        if (args.size() < 3 || args.size() > 4) return invalid("sensor.calibrate requires channel, minimum, maximum, [invert]");
        requireChannel(args.get(0));
        double minimum = number(args.get(1), "minimum");
        double maximum = number(args.get(2), "maximum");
        boolean invert = args.size() == 4 && booleanValue(args.get(3), "invert");
        return sensor.calibrate(minimum, maximum, invert) ? DeviceResult.success() : invalid("sensor calibration rejected");
    }

    private DeviceResult interval(List<DeviceValue> args) {
        if (args.size() != 2) return invalid("sensor.interval requires channel and ticks");
        requireChannel(args.get(0));
        return sensor.setInterval(integer(args.get(1), 1, SensorChannel.MAX_INTERVAL, "interval"))
                ? DeviceResult.success() : invalid("sensor interval rejected");
    }

    private DeviceValue listValue() {
        SensorChannel channel = sensor.configuration();
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
        return DeviceValue.list(List.of(DeviceValue.map(value)));
    }

    private DeviceValue snapshotValue() {
        SensorReading sample = sensor.reading();
        return sample == null ? DeviceValue.list(List.of()) : DeviceValue.list(List.of(readingValue(sample)));
    }

    private static DeviceValue readingValue(SensorReading sample) {
        Map<String, DeviceValue> value = new LinkedHashMap<>();
        value.put("channel", DeviceValue.of(sample.channel()));
        value.put("kind", DeviceValue.of(sample.kind().id()));
        value.put("metric", DeviceValue.of(sample.metric()));
        value.put("quality", DeviceValue.of(sample.quality().id()));
        value.put("numeric", DeviceValue.of(sample.numeric()));
        value.put("value", sample.numeric() ? DeviceValue.of(sample.numericValue()) : DeviceValue.of(sample.textValue()));
        value.put("text", DeviceValue.of(sample.textValue()));
        value.put("unit", DeviceValue.of(sample.unit()));
        value.put("game_time", DeviceValue.of(sample.gameTime()));
        value.put("detail", DeviceValue.of(sample.detail()));
        return DeviceValue.map(value);
    }

    private void requireChannel(DeviceValue value) {
        if (!"value".equalsIgnoreCase(string(value, "channel"))) throw new IllegalArgumentException("channel must be value");
    }

    private static DeviceResult noArguments(List<DeviceValue> args, java.util.function.Supplier<DeviceResult> operation) {
        return args.isEmpty() ? operation.get() : invalid("method takes no arguments");
    }

    private static DeviceResult invalid(String message) {
        return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT, message, false);
    }

    private static DeviceMethodDescriptor read(String name, String description, List<DeviceParameterDescriptor> parameters, DeviceValueType type) {
        return new DeviceMethodDescriptor(name, description, parameters, type, DeviceCallContext.READ);
    }

    private static DeviceMethodDescriptor write(String name, String description, List<DeviceParameterDescriptor> parameters) {
        return new DeviceMethodDescriptor(name, description, parameters, DeviceValueType.NULL, DeviceCallContext.WRITE);
    }

    private static String string(DeviceValue value, String name) {
        if (!(value instanceof DeviceValue.StringValue text) || text.value().isBlank()) throw new IllegalArgumentException(name + " must be a non-empty string");
        return text.value();
    }

    private static double number(DeviceValue value, String name) {
        if (!(value instanceof DeviceValue.NumberValue number) || !Double.isFinite(number.value())) throw new IllegalArgumentException(name + " must be a finite number");
        return number.value();
    }

    private static int integer(DeviceValue value, int minimum, int maximum, String name) {
        double number = number(value, name);
        if (number != Math.rint(number) || number < minimum || number > maximum) throw new IllegalArgumentException(name + " must be an integer from " + minimum + " to " + maximum);
        return (int) number;
    }

    private static boolean booleanValue(DeviceValue value, String name) {
        if (!(value instanceof DeviceValue.BooleanValue bool)) throw new IllegalArgumentException(name + " must be boolean");
        return bool.value();
    }
}
