package com.malice.terminalcraft.device;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Human-first generic item-storage shell surface backed by structured Device API values. */
public final class StorageShellCommand {
    private static final int DEVICE_LIMIT = 64;
    private static final int DEFAULT_LIMIT = 32;

    private StorageShellCommand() {}

    public static Outcome execute(DeviceAccess access, List<String> arguments) {
        List<String> args = arguments == null ? List.of() : List.copyOf(arguments);
        if (!args.isEmpty() && ("help".equals(args.get(0)) || "--help".equals(args.get(0)))) return usage(0);
        if (access == null) return failure("storage: device registry unavailable");
        if (args.isEmpty() || "list".equals(args.get(0))) return list(access, args);
        return switch (args.get(0)) {
            case "info" -> info(access, args);
            case "query" -> query(access, args);
            case "count" -> count(access, args);
            case "insert", "extract", "simulate-insert", "simulate-extract" -> request(access, args);
            default -> usage(1);
        };
    }

    private static Outcome list(DeviceAccess access, List<String> args) {
        boolean json = args.size() == 2 && "--json".equals(args.get(1));
        if (args.size() > (json ? 2 : 1)) return usage(1);
        List<DeviceDescriptor> devices = devices(access);
        if (json) {
            List<DeviceValue> values = devices.stream().map(StorageShellCommand::descriptorValue).toList();
            return success(DeviceShellCommand.formatValue(DeviceValue.list(values)));
        }
        if (devices.isEmpty()) return success("(no item storage devices)");
        List<String> lines = new ArrayList<>();
        for (int index = 0; index < devices.size(); index++) {
            DeviceDescriptor device = devices.get(index);
            lines.add(index + "  " + device.deviceId() + "  " + status(device) + "  "
                    + device.address() + "  " + device.displayName());
        }
        return new Outcome(0, lines);
    }

    private static Outcome info(DeviceAccess access, List<String> args) {
        boolean json = args.size() == 3 && "--json".equals(args.get(2));
        if (args.size() != (json ? 3 : 2)) return usage(1);
        Resolution resolution = resolve(access, args.get(1));
        if (resolution.error() != null) return failure(resolution.error());
        DeviceDescriptor device = resolution.device();
        if (json) return success(DeviceShellCommand.formatValue(descriptorValue(device)));
        List<String> lines = new ArrayList<>();
        lines.add("id: " + device.deviceId());
        lines.add("name: " + device.displayName());
        lines.add("type: " + device.typeName());
        lines.add("adapter: " + device.adapterId());
        lines.add("address: " + device.address());
        lines.add("status: " + status(device));
        Call metadata = callValue(access, device, "storage.metadata", List.of());
        if (metadata.error() != null) return failure(metadata.error());
        lines.add("metadata: " + DeviceShellCommand.formatValue(metadata.value()));
        return new Outcome(0, lines);
    }

    private static Outcome query(DeviceAccess access, List<String> args) {
        if (args.size() < 2) return usage(1);
        Resolution resolution = resolve(access, args.get(1));
        if (resolution.error() != null) return failure(resolution.error());
        String resource = "", namespace = "", text = "", cursor = "", tag = "";
        int limit = DEFAULT_LIMIT;
        boolean json = false;
        for (int index = 2; index < args.size(); index++) {
            String option = args.get(index);
            if ("--json".equals(option)) { json = true; continue; }
            if (index + 1 >= args.size()) return failure("storage: missing value for " + option);
            String value = args.get(++index);
            switch (option) {
                case "--resource" -> resource = value;
                case "--namespace" -> namespace = value;
                case "--text" -> text = value;
                case "--cursor" -> cursor = value;
                case "--tag" -> tag = value;
                case "--limit" -> {
                    Integer parsed = positiveInteger(value, GenericItemStorage.MAX_PAGE_SIZE);
                    if (parsed == null) return failure("storage: limit must be an integer from 1 to "
                            + GenericItemStorage.MAX_PAGE_SIZE);
                    limit = parsed;
                }
                default -> { return failure("storage: unknown query option: " + option); }
            }
        }
        Call call = callValue(access, resolution.device(), "storage.query", List.of(
                DeviceValue.of(resource), DeviceValue.of(namespace), DeviceValue.of(text),
                DeviceValue.of(cursor), DeviceValue.of(limit), DeviceValue.of(tag)));
        if (call.error() != null) return failure(call.error());
        if (json) return success(DeviceShellCommand.formatValue(call.value()));
        DeviceValue.MapValue page = (DeviceValue.MapValue) call.value();
        DeviceValue.ListValue entries = (DeviceValue.ListValue) page.values().get("entries");
        List<String> lines = new ArrayList<>();
        for (DeviceValue value : entries.values()) {
            DeviceValue.MapValue entry = (DeviceValue.MapValue) value;
            lines.add(string(entry, "count") + "  " + string(entry, "resource"));
        }
        if (lines.isEmpty()) lines.add("(no matching items)");
        String next = string(page, "next_cursor");
        if (!next.isEmpty()) lines.add("next cursor: " + next);
        return new Outcome(0, lines);
    }

    private static Outcome count(DeviceAccess access, List<String> args) {
        boolean json = args.size() == 4 && "--json".equals(args.get(3));
        if (args.size() != (json ? 4 : 3)) return usage(1);
        Resolution resolution = resolve(access, args.get(1));
        if (resolution.error() != null) return failure(resolution.error());
        Call call = callValue(access, resolution.device(), "storage.count", List.of(DeviceValue.of(args.get(2))));
        if (call.error() != null) return failure(call.error());
        String value = ((DeviceValue.StringValue) call.value()).value();
        if (json) return success(DeviceShellCommand.formatValue(DeviceValue.map(Map.of(
                "resource", DeviceValue.of(args.get(2)), "count", DeviceValue.of(value),
                "unit", DeviceValue.of("items")))));
        return success(value + " items  " + args.get(2));
    }

    private static Outcome request(DeviceAccess access, List<String> args) {
        boolean json = args.size() == 5 && "--json".equals(args.get(4));
        if (args.size() != (json ? 5 : 4)) return usage(1);
        Resolution resolution = resolve(access, args.get(1));
        if (resolution.error() != null) return failure(resolution.error());
        Integer amount = positiveInteger(args.get(3), GenericCapabilityDevice.MAX_TRANSFER_AMOUNT);
        if (amount == null) return failure("storage: amount must be an integer from 1 to "
                + GenericCapabilityDevice.MAX_TRANSFER_AMOUNT);
        String method = switch (args.get(0)) {
            case "insert" -> "storage.insert";
            case "extract" -> "storage.extract";
            case "simulate-insert" -> "storage.insert.simulate";
            case "simulate-extract" -> "storage.extract.simulate";
            default -> throw new IllegalStateException("unreachable storage request");
        };
        Call call = callValue(access, resolution.device(), method,
                List.of(DeviceValue.of(args.get(2)), DeviceValue.of(amount)));
        if (call.error() != null) return failure(call.error());
        if (json) return success(DeviceShellCommand.formatValue(call.value()));
        DeviceValue.MapValue result = (DeviceValue.MapValue) call.value();
        String actualKey = method.endsWith("simulate") ? "accepted" : "executed";
        long actual = number(result, actualKey);
        long requested = number(result, "requested");
        return success(args.get(0) + ": " + actual + "/" + requested + " items "
                + string(result, "status") + "  " + args.get(2));
    }

    private static Call callValue(DeviceAccess access, DeviceDescriptor device, String method,
                                  List<DeviceValue> arguments) {
        if (device.methods().stream().noneMatch(candidate -> candidate.name().equals(method))) {
            return Call.failure("storage: operation unsupported by " + device.deviceId());
        }
        DeviceResult result = access.call(device.deviceId(), method, arguments);
        if (result.isSuccess()) return Call.success(result.value().orElse(DeviceValue.nullValue()));
        DeviceError error = result.error().orElseThrow();
        return Call.failure("storage: " + error.code().name().toLowerCase(Locale.ROOT) + ": "
                + error.message() + (error.retryable() ? " (retryable)" : ""));
    }

    private static Resolution resolve(DeviceAccess access, String selector) {
        List<DeviceDescriptor> devices = devices(access);
        Integer index = nonNegativeInteger(selector);
        if (index != null) return index < devices.size() ? Resolution.success(devices.get(index))
                : Resolution.failure("storage: device index out of range: " + selector);
        try {
            UUID id = UUID.fromString(selector);
            DeviceDescriptor device = access.descriptor(id).orElse(null);
            return device != null && device.capabilities().contains("inventory")
                    ? Resolution.success(device) : Resolution.failure("storage: matching device not found: " + selector);
        } catch (IllegalArgumentException ignored) {
            List<DeviceDescriptor> matches = devices.stream().filter(device -> device.address().equals(selector)
                    || device.address().endsWith("@" + selector)).toList();
            if (matches.size() == 1) return Resolution.success(matches.get(0));
            if (matches.size() > 1) return Resolution.failure("storage: ambiguous selector: " + selector);
            return Resolution.failure("storage: invalid selector (use list index, UUID, or address): " + selector);
        }
    }

    private static List<DeviceDescriptor> devices(DeviceAccess access) {
        return access.descriptors(DEVICE_LIMIT).stream()
                .filter(device -> device.capabilities().contains("inventory"))
                .filter(device -> device.methods().stream().anyMatch(method -> method.name().equals("storage.query")))
                .toList();
    }

    private static DeviceValue descriptorValue(DeviceDescriptor device) {
        return DeviceValue.map(Map.of(
                "id", DeviceValue.of(device.deviceId().toString()),
                "name", DeviceValue.of(device.displayName()),
                "type", DeviceValue.of(device.typeName()),
                "adapter", DeviceValue.of(device.adapterId()),
                "address", DeviceValue.of(device.address()),
                "status", DeviceValue.of(status(device)),
                "properties", DeviceValue.map(device.properties())));
    }

    private static String string(DeviceValue.MapValue map, String key) {
        return ((DeviceValue.StringValue) map.values().get(key)).value();
    }

    private static long number(DeviceValue.MapValue map, String key) {
        return (long) ((DeviceValue.NumberValue) map.values().get(key)).value();
    }

    private static String status(DeviceDescriptor descriptor) {
        if (!descriptor.loaded()) return "unloaded";
        return descriptor.online() ? "online" : "offline";
    }

    private static Integer positiveInteger(String value, int maximum) {
        try { int parsed = Integer.parseInt(value); return parsed >= 1 && parsed <= maximum ? parsed : null; }
        catch (NumberFormatException exception) { return null; }
    }

    private static Integer nonNegativeInteger(String value) {
        try { int parsed = Integer.parseInt(value); return parsed >= 0 ? parsed : null; }
        catch (NumberFormatException exception) { return null; }
    }

    private static Outcome usage(int exitCode) {
        return new Outcome(exitCode, List.of(
                "usage: storage list [--json]",
                "       storage info <device> [--json]",
                "       storage query <device> [--resource id] [--namespace ns] [--tag #id] [--text text] [--cursor token] [--limit n] [--json]",
                "       storage count <device> <item-id> [--json]",
                "       storage insert|extract|simulate-insert|simulate-extract <device> <item-id> <count> [--json]"));
    }

    private static Outcome success(String line) { return new Outcome(0, List.of(line)); }
    private static Outcome failure(String line) { return new Outcome(1, List.of(line)); }

    private record Resolution(DeviceDescriptor device, String error) {
        static Resolution success(DeviceDescriptor device) { return new Resolution(Objects.requireNonNull(device), null); }
        static Resolution failure(String error) { return new Resolution(null, Objects.requireNonNull(error)); }
    }
    private record Call(DeviceValue value, String error) {
        static Call success(DeviceValue value) { return new Call(Objects.requireNonNull(value), null); }
        static Call failure(String error) { return new Call(null, Objects.requireNonNull(error)); }
    }
    public record Outcome(int exitCode, List<String> lines) {
        public Outcome { lines = List.copyOf(lines); }
    }
}
