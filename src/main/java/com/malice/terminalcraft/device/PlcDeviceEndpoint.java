package com.malice.terminalcraft.device;

import com.malice.terminalcraft.blockentity.ProgrammableLogicControllerBlockEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Authenticated device-network surface for remote PLC commissioning and telemetry.
 * Program writes are owner-bound; read-only status and trend data can be shared normally.
 */
public final class PlcDeviceEndpoint implements ContextualDeviceEndpoint {
    private static final DeviceMethodDescriptor STATUS = read("status", "Returns PLC state and fault information", List.of(), DeviceValueType.MAP);
    private static final DeviceMethodDescriptor PROGRAM_GET = read("program.get", "Reads a bounded program chunk",
            List.of(optionalNumber("offset", "Zero-based character offset"), optionalNumber("length", "Maximum characters")), DeviceValueType.STRING);
    private static final DeviceMethodDescriptor PROGRAM_SET = write("program.set", "Compiles and loads a replacement program",
            List.of(new DeviceParameterDescriptor("source", DeviceValueType.STRING, true, "PLC source, up to the device string limit")));
    private static final DeviceMethodDescriptor PROGRAM_CLEAR = write("program.clear", "Clears the PLC program", List.of());
    private static final DeviceMethodDescriptor RUN = write("control.run", "Starts the loaded PLC program", List.of());
    private static final DeviceMethodDescriptor STOP = write("control.stop", "Stops the PLC program", List.of());
    private static final DeviceMethodDescriptor RESET = write("control.reset", "Resets PLC runtime state", List.of());
    private static final DeviceMethodDescriptor IO = read("io.get", "Returns current digital and analog signal values", List.of(), DeviceValueType.MAP);
    private static final DeviceMethodDescriptor TREND = read("trend.get", "Returns the last 64 scan samples", List.of(), DeviceValueType.MAP);

    private final UUID deviceId;
    private final String address;
    private final ProgrammableLogicControllerBlockEntity plc;
    private final BooleanSupplier online;
    private final BooleanSupplier loaded;

    public PlcDeviceEndpoint(UUID deviceId, String address, ProgrammableLogicControllerBlockEntity plc,
                             BooleanSupplier online, BooleanSupplier loaded) {
        this.deviceId = Objects.requireNonNull(deviceId, "deviceId");
        this.address = Objects.requireNonNull(address, "address");
        this.plc = Objects.requireNonNull(plc, "plc");
        this.online = Objects.requireNonNull(online, "online");
        this.loaded = Objects.requireNonNull(loaded, "loaded");
    }

    @Override
    public DeviceDescriptor descriptor() {
        String state = !plc.compileError().isEmpty() ? "fault" : plc.isRunning() ? "running" : "stopped";
        Map<String, DeviceValue> properties = new LinkedHashMap<>();
        properties.put("state", DeviceValue.of(state));
        properties.put("scan_count", DeviceValue.of(plc.dashboardScanCount()));
        properties.put("scan_interval", DeviceValue.of(plc.dashboardProgram().scanIntervalTicks()));
        properties.put("alarm", DeviceValue.of(plc.alarmLatched()));
        properties.put("analog_inputs", DeviceValue.of(plc.dashboardProgram().analogInputs().size()));
        properties.put("analog_outputs", DeviceValue.of(plc.dashboardProgram().analogOutputs().size()));
        return new DeviceDescriptor(deviceId, "terminalcraft:plc", "programmable_logic_controller",
                plc.getLabel(), "terminalcraft", address,
                Set.of("plc", "remote_programming", "analog_io", "pid", "trend"), properties,
                List.of(STATUS, PROGRAM_GET, PROGRAM_SET, PROGRAM_CLEAR, RUN, STOP, RESET, IO, TREND),
                Set.of("scan", "fault"), Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE),
                online.getAsBoolean(), loaded.getAsBoolean());
    }

    @Override
    public DeviceResult call(DeviceCallContext context, String method, List<DeviceValue> arguments) {
        List<DeviceValue> args = arguments == null ? List.of() : arguments;
        return switch (method == null ? "" : method) {
            case "status" -> noArguments(args, () -> DeviceResult.success(status()));
            case "program.get" -> programGet(args);
            case "program.set" -> programSet(context, args);
            case "program.clear" -> mutate(context, args, () -> { plc.clearProgram(); return DeviceResult.success(); });
            case "control.run" -> mutate(context, args, () -> {
                if (!plc.compileError().isEmpty()) return failure(plc.compileError());
                plc.start();
                return DeviceResult.success();
            });
            case "control.stop" -> mutate(context, args, () -> { plc.stop(); return DeviceResult.success(); });
            case "control.reset" -> mutate(context, args, () -> { plc.resetController(); return DeviceResult.success(); });
            case "io.get" -> noArguments(args, () -> DeviceResult.success(ioValues()));
            case "trend.get" -> noArguments(args, () -> DeviceResult.success(trendValues()));
            default -> DeviceResult.failure(DeviceErrorCode.UNSUPPORTED, "method is unsupported", false);
        };
    }

    private DeviceResult programGet(List<DeviceValue> args) {
        int offset = args.size() > 0 ? integer(args.get(0), 0, Integer.MAX_VALUE, "offset") : 0;
        int length = args.size() > 1 ? integer(args.get(1), 1, DeviceValue.MAX_STRING_LENGTH, "length")
                : DeviceValue.MAX_STRING_LENGTH;
        String source = plc.programSource();
        if (offset >= source.length()) return DeviceResult.success(DeviceValue.of(""));
        return DeviceResult.success(DeviceValue.of(source.substring(offset, Math.min(source.length(), offset + length))));
    }

    private DeviceResult programSet(DeviceCallContext context, List<DeviceValue> args) {
        if (args.size() != 1 || !(args.get(0) instanceof DeviceValue.StringValue source)) {
            return invalid("program.set requires one source string");
        }
        if (!authorized(context)) return permissionDenied();
        return plc.loadProgram(source.value())
                ? DeviceResult.success() : failure(plc.compileError());
    }

    private DeviceResult mutate(DeviceCallContext context, List<DeviceValue> args,
                                java.util.function.Supplier<DeviceResult> operation) {
        if (!args.isEmpty()) return invalid("method takes no arguments");
        if (!authorized(context)) return permissionDenied();
        return operation.get();
    }

    private boolean authorized(DeviceCallContext context) {
        return context != null && plc.canControl(context.principalId());
    }

    private static DeviceResult permissionDenied() {
        return DeviceResult.failure(DeviceErrorCode.PERMISSION_DENIED,
                "PLC remote programming requires the PLC owner", false);
    }

    private DeviceValue status() {
        Map<String, DeviceValue> value = new LinkedHashMap<>();
        value.put("state", DeviceValue.of(!plc.compileError().isEmpty() ? "fault" : plc.isRunning() ? "running" : "stopped"));
        value.put("scan_count", DeviceValue.of(plc.dashboardScanCount()));
        value.put("interval", DeviceValue.of(plc.dashboardProgram().scanIntervalTicks()));
        value.put("alarm", DeviceValue.of(plc.alarmLatched()));
        value.put("fault", DeviceValue.of(plc.compileError().isEmpty() ? plc.controllerFault() : plc.compileError()));
        value.put("owner", DeviceValue.of(plc.ownerId() == null ? "unclaimed" : plc.ownerId().toString()));
        return DeviceValue.map(value);
    }

    private DeviceValue ioValues() {
        Map<String, DeviceValue> value = new LinkedHashMap<>();
        Map<String, Integer> analog = plc.dashboardAnalogValues();
        plc.dashboardProgram().inputs().forEach(binding -> value.put("in." + binding.name(), DeviceValue.of(
                analog.getOrDefault(binding.name(), plc.dashboardSignals().getOrDefault(binding.name(), false) ? 15 : 0))));
        plc.dashboardProgram().outputs().forEach(binding -> value.put("out." + binding.name(), DeviceValue.of(
                plc.dashboardProgram().analogOutputs().contains(binding.name())
                        ? analog.getOrDefault(binding.name(), 0)
                        : plc.dashboardSignals().getOrDefault(binding.name(), false) ? 15 : 0)));
        return DeviceValue.map(value);
    }

    private DeviceValue trendValues() {
        Map<String, DeviceValue> value = new LinkedHashMap<>();
        plc.dashboardTrend().forEach((name, samples) -> value.put(name,
                DeviceValue.list(samples.stream().map(DeviceValue::of).toList())));
        return DeviceValue.map(value);
    }

    private static DeviceResult noArguments(List<DeviceValue> args,
                                            java.util.function.Supplier<DeviceResult> operation) {
        return args.isEmpty() ? operation.get() : invalid("method takes no arguments");
    }

    private static DeviceResult invalid(String message) {
        return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT, message, false);
    }

    private static DeviceResult failure(String message) {
        return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT,
                message == null || message.isBlank() ? "PLC rejected operation" : message, false);
    }

    private static int integer(DeviceValue value, int minimum, int maximum, String name) {
        if (!(value instanceof DeviceValue.NumberValue number)
                || number.value() != Math.rint(number.value())
                || number.value() < minimum || number.value() > maximum) {
            throw new IllegalArgumentException(name + " must be an integer from " + minimum + " to " + maximum);
        }
        return (int) number.value();
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

    private static DeviceParameterDescriptor optionalNumber(String name, String description) {
        return new DeviceParameterDescriptor(name, DeviceValueType.NUMBER, false, description);
    }
}
