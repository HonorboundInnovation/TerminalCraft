package com.malice.terminalcraft.device;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Pure command parser/formatter for the read-only unified device shell surface. */
public final class DeviceShellCommand {
    private static final int LIST_LIMIT = 64;

    private DeviceShellCommand() {}

    public static Outcome execute(DeviceAccess access, List<String> arguments) {
        if (access == null) return failure("device: server device registry unavailable");
        List<String> args = arguments == null ? List.of() : List.copyOf(arguments);
        if (args.isEmpty() || "list".equals(args.get(0))) return list(access, args);
        return switch (args.get(0)) {
            case "events" -> DeviceEventShellCommand.execute(access, args);
            case "info" -> info(access, args);
            case "call" -> call(access, args);
            case "transfer" -> transfer(access, args);
            case "fluid-transfer" -> fluidTransfer(access, args);
            case "escrow" -> escrow(access, args);
            case "fluid-escrow" -> fluidEscrow(access, args);
            default -> usage();
        };
    }


    private static Outcome events(DeviceAccess access, List<String> args) {
        if (args.size() > 2) return usage();
        int limit = DeviceRegistry.MAX_EVENT_POLL_RESULTS;
        if (args.size() == 2) {
            try {
                limit = Integer.parseInt(args.get(1));
            } catch (NumberFormatException exception) {
                return failure("device: invalid_argument: event limit must be an integer");
            }
            if (limit < 1 || limit > DeviceRegistry.MAX_EVENT_POLL_RESULTS) {
                return failure("device: invalid_argument: event limit must be from 1 to "
                        + DeviceRegistry.MAX_EVENT_POLL_RESULTS);
            }
        }
        DeviceEventBatch batch = access.pollEvents(limit);
        List<String> lines = new ArrayList<>();
        if (batch.dropped() > 0) lines.add("dropped=" + batch.dropped());
        for (DeviceEvent event : batch.events()) {
            lines.add(event.sequence() + " " + event.sourceDeviceId() + " " + event.type()
                    + " " + formatValue(event.payload()));
        }
        if (lines.isEmpty()) lines.add("(no events)");
        return new Outcome(0, lines);
    }

    private static Outcome list(DeviceAccess access, List<String> args) {
        if (args.size() > 1) return usage();
        List<DeviceDescriptor> descriptors = access.descriptors(LIST_LIMIT);
        if (descriptors.isEmpty()) return success("(no devices)");
        List<String> lines = new ArrayList<>(descriptors.size());
        for (DeviceDescriptor descriptor : descriptors) {
            lines.add(descriptor.deviceId() + "  " + descriptor.typeName() + "  "
                    + status(descriptor) + "  " + descriptor.address() + "  "
                    + descriptor.displayName());
        }
        return new Outcome(0, lines);
    }

    private static Outcome info(DeviceAccess access, List<String> args) {
        if (args.size() != 2) return usage();
        UUID id = parseUuid(args.get(1));
        if (id == null) return failure("device: invalid UUID: " + args.get(1));
        return access.descriptor(id)
                .map(DeviceShellCommand::describe)
                .orElseGet(() -> failure("device: device not found: " + id));
    }

    private static Outcome call(DeviceAccess access, List<String> args) {
        if (args.size() < 3) return usage();
        UUID id = parseUuid(args.get(1));
        if (id == null) return failure("device: invalid UUID: " + args.get(1));

        DeviceDescriptor descriptor = access.descriptor(id).orElse(null);
        if (descriptor == null) return failure("device: device not found: " + id);

        String methodName = args.get(2);
        DeviceMethodDescriptor method = descriptor.methods().stream()
                .filter(candidate -> candidate.name().equals(methodName))
                .findFirst()
                .orElse(null);
        if (method == null) {
            return failure("device: unsupported: method is unsupported: " + methodName);
        }

        List<String> rawArguments = args.subList(3, args.size());
        Outcome arityFailure = validateArity(method, rawArguments.size());
        if (arityFailure != null) return arityFailure;

        List<DeviceValue> values = new ArrayList<>(rawArguments.size());
        for (int index = 0; index < rawArguments.size(); index++) {
            DeviceParameterDescriptor parameter = method.parameters().get(index);
            ParsedValue parsed = parseValue(rawArguments.get(index), parameter);
            if (parsed.error() != null) return failure(parsed.error());
            values.add(parsed.value());
        }

        return outcome(access.call(id, methodName, values));
    }

    private static Outcome transfer(DeviceAccess access, List<String> args) {
        if (args.size() != 6) return usage();
        if (!(access instanceof ExactItemTransferAccess transfers)) {
            return failure("device: unsupported: exact item transfer is unavailable");
        }

        UUID operationId = parseUuid(args.get(1));
        if (operationId == null) return failure("device: invalid operation UUID: " + args.get(1));
        UUID sourceId = parseUuid(args.get(2));
        if (sourceId == null) return failure("device: invalid source UUID: " + args.get(2));
        UUID destinationId = parseUuid(args.get(3));
        if (destinationId == null) return failure("device: invalid destination UUID: " + args.get(3));

        int count;
        try {
            count = Integer.parseInt(args.get(5));
        } catch (NumberFormatException exception) {
            return failure("device: invalid_argument: transfer count must be an integer");
        }

        DeviceResult result = transfers.transferExactItems(operationId, sourceId, destinationId,
                args.get(4), count);
        return outcome(result);
    }

    private static Outcome fluidTransfer(DeviceAccess access, List<String> args) {
        if (args.size() != 6) return usage();
        if (!(access instanceof ExactFluidTransferAccess transfers)) {
            return failure("device: unsupported: exact fluid transfer is unavailable");
        }

        UUID operationId = parseUuid(args.get(1));
        if (operationId == null) return failure("device: invalid operation UUID: " + args.get(1));
        UUID sourceId = parseUuid(args.get(2));
        if (sourceId == null) return failure("device: invalid source UUID: " + args.get(2));
        UUID destinationId = parseUuid(args.get(3));
        if (destinationId == null) return failure("device: invalid destination UUID: " + args.get(3));

        int amountMb;
        try {
            amountMb = Integer.parseInt(args.get(5));
        } catch (NumberFormatException exception) {
            return failure("device: invalid_argument: fluid amount must be an integer in mB");
        }
        return outcome(transfers.transferExactFluid(operationId, sourceId, destinationId,
                args.get(4), amountMb));
    }

    private static Outcome fluidEscrow(DeviceAccess access, List<String> args) {
        if (!(access instanceof ExactFluidEscrowAccess escrow)) {
            return failure("device: unsupported: fluid escrow administration is unavailable");
        }
        if (args.size() == 2 && "list".equals(args.get(1))) {
            return outcome(escrow.listFluidEscrow(LIST_LIMIT));
        }
        if (args.size() == 4 && "recover".equals(args.get(1))) {
            UUID escrowId = parseUuid(args.get(2));
            if (escrowId == null) return failure("device: invalid escrow UUID: " + args.get(2));
            UUID destinationId = parseUuid(args.get(3));
            if (destinationId == null) return failure("device: invalid destination UUID: " + args.get(3));
            return outcome(escrow.recoverFluidEscrow(escrowId, destinationId));
        }
        return usage();
    }

    private static Outcome escrow(DeviceAccess access, List<String> args) {
        if (!(access instanceof ExactItemEscrowAccess escrow)) {
            return failure("device: unsupported: item escrow administration is unavailable");
        }
        if (args.size() == 2 && "list".equals(args.get(1))) {
            return outcome(escrow.listItemEscrow(LIST_LIMIT));
        }
        if (args.size() == 4 && "recover".equals(args.get(1))) {
            UUID escrowId = parseUuid(args.get(2));
            if (escrowId == null) return failure("device: invalid escrow UUID: " + args.get(2));
            UUID destinationId = parseUuid(args.get(3));
            if (destinationId == null) return failure("device: invalid destination UUID: " + args.get(3));
            return outcome(escrow.recoverItemEscrow(escrowId, destinationId));
        }
        return usage();
    }

    static Outcome outcome(DeviceResult result) {
        if (result.isSuccess()) {
            return success(formatValue(result.value().orElse(DeviceValue.nullValue())));
        }
        DeviceError error = result.error().orElseThrow();
        return failure("device: " + error.code().name().toLowerCase() + ": " + error.message()
                + (error.retryable() ? " (retryable)" : ""));
    }

    private static Outcome validateArity(DeviceMethodDescriptor method, int supplied) {
        int required = 0;
        for (DeviceParameterDescriptor parameter : method.parameters()) {
            if (parameter.required()) required++;
        }
        int maximum = method.parameters().size();
        if (supplied < required || supplied > maximum) {
            String expected = required == maximum
                    ? Integer.toString(required)
                    : required + ".." + maximum;
            return failure("device: invalid_argument: " + method.name() + " expects "
                    + expected + " argument(s), got " + supplied);
        }
        return null;
    }

    private static ParsedValue parseValue(String raw, DeviceParameterDescriptor parameter) {
        try {
            return switch (parameter.type()) {
                case STRING -> ParsedValue.success(DeviceValue.of(raw));
                case NUMBER -> parseNumber(raw, parameter.name());
                case BOOLEAN -> parseBoolean(raw, parameter.name());
                case NULL -> "null".equalsIgnoreCase(raw)
                        ? ParsedValue.success(DeviceValue.nullValue())
                        : ParsedValue.failure(typeError(parameter, raw));
                case LIST, MAP -> ParsedValue.failure("device: invalid_argument: parameter '"
                        + parameter.name() + "' has type " + parameter.type().name().toLowerCase()
                        + ", which is not supported by shell literals");
            };
        } catch (IllegalArgumentException exception) {
            return ParsedValue.failure("device: invalid_argument: parameter '" + parameter.name()
                    + "' is invalid: " + exception.getMessage());
        }
    }

    private static ParsedValue parseNumber(String raw, String name) {
        try {
            double value = Double.parseDouble(raw);
            if (!Double.isFinite(value)) throw new NumberFormatException("non-finite");
            return ParsedValue.success(DeviceValue.of(value));
        } catch (NumberFormatException exception) {
            return ParsedValue.failure("device: invalid_argument: parameter '" + name
                    + "' expects number, got " + quote(raw));
        }
    }

    private static ParsedValue parseBoolean(String raw, String name) {
        if ("true".equalsIgnoreCase(raw)) return ParsedValue.success(DeviceValue.of(true));
        if ("false".equalsIgnoreCase(raw)) return ParsedValue.success(DeviceValue.of(false));
        return ParsedValue.failure("device: invalid_argument: parameter '" + name
                + "' expects boolean, got " + quote(raw));
    }

    private static String typeError(DeviceParameterDescriptor parameter, String raw) {
        return "device: invalid_argument: parameter '" + parameter.name() + "' expects "
                + parameter.type().name().toLowerCase() + ", got " + quote(raw);
    }

    private static Outcome describe(DeviceDescriptor descriptor) {
        List<String> lines = new ArrayList<>();
        lines.add("id: " + descriptor.deviceId());
        lines.add("type: " + descriptor.typeName());
        lines.add("name: " + descriptor.displayName());
        lines.add("adapter: " + descriptor.adapterId());
        lines.add("source: " + descriptor.modSource());
        lines.add("address: " + descriptor.address());
        lines.add("status: " + status(descriptor));
        lines.add("capabilities: " + sorted(descriptor.capabilities()));
        lines.add("permissions: " + sorted(descriptor.permissions()));
        lines.add("events: " + sorted(descriptor.eventTypes()));
        lines.add("properties: " + formatMap(descriptor.properties()));
        List<String> methods = descriptor.methods().stream()
                .map(DeviceShellCommand::formatMethod)
                .sorted()
                .toList();
        lines.add("methods: " + (methods.isEmpty() ? "(none)" : String.join(", ", methods)));
        return new Outcome(0, lines);
    }

    private static String formatMethod(DeviceMethodDescriptor method) {
        String parameters = method.parameters().stream()
                .map(parameter -> parameter.name() + ":" + parameter.type().name().toLowerCase()
                        + (parameter.required() ? "" : "?"))
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return method.name() + "(" + parameters + ")->" + method.returnType().name().toLowerCase()
                + " [" + method.requiredPermission() + "]";
    }

    public static String formatValue(DeviceValue value) {
        Objects.requireNonNull(value, "value");
        if (value instanceof DeviceValue.NullValue) return "null";
        if (value instanceof DeviceValue.StringValue string) return quote(string.value());
        if (value instanceof DeviceValue.NumberValue number) {
            return BigDecimal.valueOf(number.value()).stripTrailingZeros().toPlainString();
        }
        if (value instanceof DeviceValue.BooleanValue bool) return Boolean.toString(bool.value());
        if (value instanceof DeviceValue.ListValue list) {
            return "[" + list.values().stream().map(DeviceShellCommand::formatValue)
                    .reduce((left, right) -> left + ", " + right).orElse("") + "]";
        }
        return formatMap(((DeviceValue.MapValue) value).values());
    }

    private static String formatMap(Map<String, DeviceValue> values) {
        return "{" + values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> quote(entry.getKey()) + ": " + formatValue(entry.getValue()))
                .reduce((left, right) -> left + ", " + right).orElse("") + "}";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private static String sorted(java.util.Set<String> values) {
        return values.isEmpty() ? "(none)" : values.stream().sorted().reduce((a, b) -> a + ", " + b).orElse("");
    }

    private static String status(DeviceDescriptor descriptor) {
        if (!descriptor.loaded()) return "unloaded";
        return descriptor.online() ? "online" : "offline";
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static Outcome usage() {
        return failure("device: usage: device list | events [limit] | info <uuid> | call <uuid> <method> [args...] | transfer <operation-uuid> <source-uuid> <destination-uuid> <item-id> <count> | fluid-transfer <operation-uuid> <source-uuid> <destination-uuid> <fluid-id> <amount-mB> | escrow list|recover ... | fluid-escrow list|recover ...");
    }

    private static Outcome success(String line) {
        return new Outcome(0, List.of(line));
    }

    private static Outcome failure(String line) {
        return new Outcome(1, List.of(line));
    }

    private record ParsedValue(DeviceValue value, String error) {
        private static ParsedValue success(DeviceValue value) {
            return new ParsedValue(Objects.requireNonNull(value, "value"), null);
        }

        private static ParsedValue failure(String error) {
            return new ParsedValue(null, Objects.requireNonNull(error, "error"));
        }
    }

    public record Outcome(int exitCode, List<String> lines) {
        public Outcome {
            if (exitCode < 0 || exitCode > 255) throw new IllegalArgumentException("invalid exit code");
            lines = List.copyOf(lines);
        }
    }
}
