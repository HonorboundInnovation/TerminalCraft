package com.malice.terminalcraft.device;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Friendly, bounded terminal program for item-storage integrations.
 *
 * <p>The program resolves only devices carrying the requested integration capability and delegates
 * all reads and mutations to the caller-bound {@link DeviceAccess}. Consequently, normal Device API
 * permission checks remain authoritative for every operation.</p>
 */
public final class IntegratedStorageShellCommand {
    private static final int DEVICE_LIMIT = 64;
    private static final int DEFAULT_ITEM_LIMIT = 32;

    private IntegratedStorageShellCommand() {}

    public enum Profile {
        SOPHISTICATED("sophisticated", List.of("sophisticated_storage", "sophisticated_backpack")),
        STORAGE_DRAWERS("drawers", List.of("storage_drawers"));

        private final String command;
        private final List<String> capabilities;

        Profile(String command, List<String> capabilities) {
            this.command = command;
            this.capabilities = List.copyOf(capabilities);
        }

        String command() {
            return command;
        }

        boolean matches(DeviceDescriptor descriptor) {
            return capabilities.stream().anyMatch(descriptor.capabilities()::contains);
        }
    }

    public static Outcome execute(DeviceAccess access, Profile profile, List<String> arguments) {
        Objects.requireNonNull(profile, "profile");
        List<String> args = arguments == null ? List.of() : List.copyOf(arguments);
        if (!args.isEmpty() && ("help".equals(args.get(0)) || "--help".equals(args.get(0)))) {
            return usage(profile, 0);
        }
        if (access == null) return failure(profile.command() + ": device registry unavailable");
        if (args.isEmpty() || "list".equals(args.get(0))) return list(access, profile, args);

        return switch (args.get(0)) {
            case "info" -> info(access, profile, args);
            case "items" -> items(access, profile, args);
            case "count" -> count(access, profile, args);
            case "insert", "extract", "simulate-insert", "simulate-extract" ->
                    transfer(access, profile, args);
            default -> usage(profile, 1);
        };
    }

    private static Outcome list(DeviceAccess access, Profile profile, List<String> args) {
        if (args.size() > 1) return usage(profile, 1);
        List<DeviceDescriptor> devices = devices(access, profile);
        if (devices.isEmpty()) return success("(no " + profile.command() + " storage devices)");
        List<String> lines = new ArrayList<>(devices.size());
        for (int index = 0; index < devices.size(); index++) {
            DeviceDescriptor device = devices.get(index);
            lines.add(index + "  " + device.deviceId() + "  " + status(device) + "  "
                    + device.address() + "  " + device.displayName());
        }
        return new Outcome(0, lines);
    }

    private static Outcome info(DeviceAccess access, Profile profile, List<String> args) {
        if (args.size() != 2) return usage(profile, 1);
        Resolution resolution = resolve(access, profile, args.get(1));
        if (resolution.error() != null) return failure(resolution.error());
        DeviceDescriptor device = resolution.device();
        List<String> lines = new ArrayList<>();
        lines.add("id: " + device.deviceId());
        lines.add("type: " + device.typeName());
        lines.add("name: " + device.displayName());
        lines.add("address: " + device.address());
        lines.add("status: " + status(device));
        lines.add("properties: " + DeviceShellCommand.formatValue(DeviceValue.map(device.properties())));
        return new Outcome(0, lines);
    }

    private static Outcome items(DeviceAccess access, Profile profile, List<String> args) {
        if (args.size() < 2 || args.size() > 3) return usage(profile, 1);
        Resolution resolution = resolve(access, profile, args.get(1));
        if (resolution.error() != null) return failure(resolution.error());
        int limit = DEFAULT_ITEM_LIMIT;
        if (args.size() == 3) {
            Integer parsed = positiveInteger(args.get(2), GenericItemStorage.MAX_PAGE_SIZE);
            if (parsed == null) {
                return failure(profile.command() + ": limit must be an integer from 1 to "
                        + GenericItemStorage.MAX_PAGE_SIZE);
            }
            limit = parsed;
        }
        return call(access, profile, resolution.device(), "storage.query", List.of(
                DeviceValue.of(""), DeviceValue.of(""), DeviceValue.of(""),
                DeviceValue.of(""), DeviceValue.of(limit)));
    }

    private static Outcome count(DeviceAccess access, Profile profile, List<String> args) {
        if (args.size() != 3) return usage(profile, 1);
        Resolution resolution = resolve(access, profile, args.get(1));
        if (resolution.error() != null) return failure(resolution.error());
        return call(access, profile, resolution.device(), "inventory.count",
                List.of(DeviceValue.of(args.get(2))));
    }

    private static Outcome transfer(DeviceAccess access, Profile profile, List<String> args) {
        if (args.size() != 4) return usage(profile, 1);
        Resolution resolution = resolve(access, profile, args.get(1));
        if (resolution.error() != null) return failure(resolution.error());
        Integer amount = positiveInteger(args.get(3), GenericCapabilityDevice.MAX_TRANSFER_AMOUNT);
        if (amount == null) {
            return failure(profile.command() + ": amount must be an integer from 1 to "
                    + GenericCapabilityDevice.MAX_TRANSFER_AMOUNT);
        }
        String operation = args.get(0);
        String method = switch (operation) {
            case "insert" -> "inventory.insert";
            case "extract" -> "inventory.extract";
            case "simulate-insert" -> "inventory.insert.simulate";
            case "simulate-extract" -> "inventory.extract.simulate";
            default -> throw new IllegalStateException("unreachable storage operation");
        };
        return call(access, profile, resolution.device(), method,
                List.of(DeviceValue.of(args.get(2)), DeviceValue.of(amount)));
    }

    private static Outcome call(DeviceAccess access, Profile profile, DeviceDescriptor device,
                                String method, List<DeviceValue> arguments) {
        boolean supported = device.methods().stream().anyMatch(candidate -> candidate.name().equals(method));
        if (!supported) return failure(profile.command() + ": operation unsupported by " + device.deviceId());
        DeviceResult result = access.call(device.deviceId(), method, arguments);
        if (result.isSuccess()) {
            return success(DeviceShellCommand.formatValue(
                    result.value().orElse(DeviceValue.nullValue())));
        }
        DeviceError error = result.error().orElseThrow();
        return failure(profile.command() + ": " + error.code().name().toLowerCase(Locale.ROOT)
                + ": " + error.message() + (error.retryable() ? " (retryable)" : ""));
    }

    private static Resolution resolve(DeviceAccess access, Profile profile, String selector) {
        List<DeviceDescriptor> devices = devices(access, profile);
        Integer index = nonNegativeInteger(selector);
        if (index != null) {
            if (index < devices.size()) return Resolution.success(devices.get(index));
            return Resolution.failure(profile.command() + ": device index out of range: " + selector);
        }

        UUID id = parseUuid(selector);
        if (id != null) {
            DeviceDescriptor device = access.descriptor(id).orElse(null);
            if (device == null || !profile.matches(device)) {
                return Resolution.failure(profile.command() + ": matching device not found: " + selector);
            }
            return Resolution.success(device);
        }

        List<DeviceDescriptor> matched = devices.stream()
                .filter(device -> device.address().equals(selector)
                        || device.address().endsWith("@" + selector))
                .toList();
        if (matched.size() == 1) return Resolution.success(matched.get(0));
        if (matched.size() > 1) return Resolution.failure(profile.command() + ": ambiguous selector: " + selector);
        return Resolution.failure(profile.command() + ": invalid selector (use list index, UUID, or address): " + selector);
    }

    private static List<DeviceDescriptor> devices(DeviceAccess access, Profile profile) {
        return access.descriptors(DEVICE_LIMIT).stream().filter(profile::matches).toList();
    }

    private static Outcome usage(Profile profile, int exitCode) {
        String command = profile.command();
        return new Outcome(exitCode, List.of(
                "usage: " + command + " list",
                "       " + command + " info <device>",
                "       " + command + " items <device> [limit]",
                "       " + command + " count <device> <item-id>",
                "       " + command + " insert|extract <device> <item-id> <count>",
                "       " + command + " simulate-insert|simulate-extract <device> <item-id> <count>",
                "device: list index, UUID, full address, or side (north/south/east/west/up/down)"));
    }

    private static String status(DeviceDescriptor descriptor) {
        if (!descriptor.loaded()) return "unloaded";
        return descriptor.online() ? "online" : "offline";
    }

    private static Integer positiveInteger(String value, int maximum) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= 1 && parsed <= maximum ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Integer nonNegativeInteger(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static Outcome success(String line) {
        return new Outcome(0, List.of(line));
    }

    private static Outcome failure(String line) {
        return new Outcome(1, List.of(line));
    }

    private record Resolution(DeviceDescriptor device, String error) {
        private static Resolution success(DeviceDescriptor device) {
            return new Resolution(Objects.requireNonNull(device, "device"), null);
        }

        private static Resolution failure(String error) {
            return new Resolution(null, Objects.requireNonNull(error, "error"));
        }
    }

    public record Outcome(int exitCode, List<String> lines) {
        public Outcome {
            if (exitCode < 0 || exitCode > 255) throw new IllegalArgumentException("invalid exit code");
            lines = List.copyOf(lines);
        }
    }
}
