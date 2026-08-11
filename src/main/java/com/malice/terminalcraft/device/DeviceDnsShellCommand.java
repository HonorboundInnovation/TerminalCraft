package com.malice.terminalcraft.device;

import com.malice.terminalcraft.network.RednetAddress;
import com.malice.terminalcraft.network.RednetNetwork;
import com.malice.terminalcraft.network.RednetRegistrationResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.UUID;

/** Shell surface for durable UUID-backed names on the unified device registry. */
public final class DeviceDnsShellCommand {
    private static final int LIST_LIMIT = 128;

    private DeviceDnsShellCommand() {}

    public static DeviceShellCommand.Outcome execute(Level level, DeviceAccess access, List<String> arguments) {
        if (!(level instanceof ServerLevel)) return failure("device: DNS requires a server-side device host");
        List<String> args = arguments == null ? List.of() : List.copyOf(arguments);
        String action = args.isEmpty() ? "list" : args.get(0).toLowerCase(java.util.Locale.ROOT);
        return switch (action) {
            case "list" -> list(level, args);
            case "resolve" -> resolve(level, args);
            case "add", "register" -> add(level, access, args);
            case "remove", "unregister" -> remove(level, args);
            case "clear" -> clear(level, args);
            default -> usage();
        };
    }

    private static DeviceShellCommand.Outcome list(Level level, List<String> args) {
        if (args.size() > 1) return usage();
        List<RednetAddress> records = RednetNetwork.addresses(level, LIST_LIMIT);
        if (records.isEmpty()) return success("(no DNS records)");
        return new DeviceShellCommand.Outcome(0, records.stream()
                .map(address -> address.displayName() + " -> " + address.encoded()).toList());
    }

    private static DeviceShellCommand.Outcome resolve(Level level, List<String> args) {
        if (args.size() != 2) return usage();
        return RednetNetwork.resolveAddress(level, args.get(1))
                .map(address -> success("name=" + address.displayName() + " id=" + address.deviceId()
                        + " address=" + address.encoded()))
                .orElseGet(() -> failure("device: DNS name not found: " + args.get(1)));
    }

    private static DeviceShellCommand.Outcome add(Level level, DeviceAccess access, List<String> args) {
        if (args.size() != 3) return usage();
        UUID deviceId = selector(level, args.get(1));
        if (deviceId == null) return failure("device: DNS target not found: " + args.get(1));
        if (access == null || access.descriptor(deviceId).isEmpty()) {
            return failure("device: target is not a live discoverable device: " + deviceId);
        }
        RednetRegistrationResult result = RednetNetwork.registerDeviceAlias(level, deviceId, args.get(2));
        if (!result.accepted()) return failure("device: DNS registration failed: " + result.status().name().toLowerCase());
        return success("device DNS " + result.name() + " -> " + deviceId);
    }

    private static DeviceShellCommand.Outcome remove(Level level, List<String> args) {
        if (args.size() != 2) return usage();
        UUID deviceId = selector(level, args.get(1));
        if (deviceId == null || !RednetNetwork.unregisterDeviceAlias(level, deviceId, args.get(1))) {
            return failure("device: DNS alias not found: " + args.get(1));
        }
        return success("device DNS alias removed " + args.get(1));
    }

    private static DeviceShellCommand.Outcome clear(Level level, List<String> args) {
        if (args.size() != 2) return usage();
        UUID deviceId = selector(level, args.get(1));
        if (deviceId == null) return failure("device: DNS target not found: " + args.get(1));
        int removed = RednetNetwork.unregisterDeviceAliases(level, deviceId);
        return removed == 0 ? failure("device: no durable DNS aliases for " + args.get(1))
                : success("device DNS aliases cleared " + removed);
    }

    private static UUID selector(Level level, String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return RednetNetwork.resolveAddress(level, value).map(RednetAddress::deviceId).orElse(null);
        }
    }

    private static DeviceShellCommand.Outcome usage() {
        return failure("device: usage: device dns list|resolve <name|uuid>|add <name|uuid> <alias>|remove <alias>|clear <name|uuid>");
    }

    private static DeviceShellCommand.Outcome success(String line) {
        return new DeviceShellCommand.Outcome(0, List.of(line));
    }

    private static DeviceShellCommand.Outcome failure(String line) {
        return new DeviceShellCommand.Outcome(1, List.of(line));
    }
}
