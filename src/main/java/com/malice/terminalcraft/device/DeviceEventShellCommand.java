package com.malice.terminalcraft.device;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Parser and formatter for caller-owned event subscriptions. */
final class DeviceEventShellCommand {
    private DeviceEventShellCommand() {}

    static DeviceShellCommand.Outcome execute(DeviceAccess access, List<String> args) {
        if (args.size() < 2) return legacyPoll(access, args);
        String operation = args.get(1);
        if (!Set.of("subscribe", "poll", "diagnostics", "unsubscribe").contains(operation)) {
            return legacyPoll(access, args);
        }
        if (!(access instanceof DeviceEventSubscriptionAccess subscriptions)) {
            return failure("device: unsupported: event subscriptions are unavailable");
        }
        return switch (operation) {
            case "subscribe" -> subscribe(subscriptions, args);
            case "poll" -> poll(subscriptions, args);
            case "diagnostics" -> diagnostics(subscriptions, args);
            case "unsubscribe" -> unsubscribe(subscriptions, args);
            default -> legacyPoll(access, args);
        };
    }

    private static DeviceShellCommand.Outcome subscribe(DeviceEventSubscriptionAccess access,
                                                         List<String> args) {
        if (args.size() < 3 || args.size() > 6) return usage();
        UUID source = null;
        if (!"*".equals(args.get(2))) {
            source = uuid(args.get(2));
            if (source == null) return failure("device: invalid source UUID: " + args.get(2));
        }
        Set<String> types = Set.of();
        if (args.size() >= 4 && !"*".equals(args.get(3))) {
            types = new LinkedHashSet<>(Arrays.asList(args.get(3).split(",", -1)));
        }
        long debounce = 0;
        if (args.size() >= 5) {
            try { debounce = Long.parseLong(args.get(4)); }
            catch (NumberFormatException exception) {
                return failure("device: invalid_argument: debounce must be an integer tick count");
            }
        }
        boolean coalesce = false;
        if (args.size() == 6) {
            if (!"true".equalsIgnoreCase(args.get(5)) && !"false".equalsIgnoreCase(args.get(5))) {
                return failure("device: invalid_argument: coalesce must be true or false");
            }
            coalesce = Boolean.parseBoolean(args.get(5));
        }
        try {
            DeviceResult result = access.subscribeEvents(
                    new DeviceEventSubscription(source, types, debounce, coalesce));
            return DeviceShellCommand.outcome(result);
        } catch (IllegalArgumentException exception) {
            return failure("device: invalid_argument: " + exception.getMessage());
        }
    }

    private static DeviceShellCommand.Outcome poll(DeviceEventSubscriptionAccess access,
                                                    List<String> args) {
        if (args.size() < 3 || args.size() > 4) return usage();
        UUID id = uuid(args.get(2));
        if (id == null) return failure("device: invalid subscription UUID: " + args.get(2));
        int limit = DeviceEventRuntime.MAX_POLL_RESULTS;
        if (args.size() == 4) {
            try { limit = Integer.parseInt(args.get(3)); }
            catch (NumberFormatException exception) {
                return failure("device: invalid_argument: event limit must be an integer");
            }
            if (limit < 1 || limit > DeviceEventRuntime.MAX_POLL_RESULTS) {
                return failure("device: invalid_argument: event limit must be from 1 to "
                        + DeviceEventRuntime.MAX_POLL_RESULTS);
            }
        }
        return formatBatch(access.pollSubscription(id, limit));
    }

    private static DeviceShellCommand.Outcome diagnostics(DeviceEventSubscriptionAccess access,
                                                           List<String> args) {
        if (args.size() != 3) return usage();
        UUID id = uuid(args.get(2));
        if (id == null) return failure("device: invalid subscription UUID: " + args.get(2));
        return access.eventDiagnostics(id)
                .map(value -> new DeviceShellCommand.Outcome(0, List.of(
                        "queued=" + value.queued() + " delivered=" + value.delivered()
                                + " dropped=" + value.dropped() + " debounced=" + value.debounced()
                                + " coalesced=" + value.coalesced())))
                .orElseGet(() -> failure("device: subscription not found"));
    }

    private static DeviceShellCommand.Outcome unsubscribe(DeviceEventSubscriptionAccess access,
                                                           List<String> args) {
        if (args.size() != 3) return usage();
        UUID id = uuid(args.get(2));
        if (id == null) return failure("device: invalid subscription UUID: " + args.get(2));
        return access.unsubscribeEvents(id)
                ? new DeviceShellCommand.Outcome(0, List.of("unsubscribed=" + id))
                : failure("device: subscription not found");
    }

    private static DeviceShellCommand.Outcome legacyPoll(DeviceAccess access, List<String> args) {
        if (args.size() > 2) return usage();
        int limit = DeviceRegistry.MAX_EVENT_POLL_RESULTS;
        if (args.size() == 2) {
            try { limit = Integer.parseInt(args.get(1)); }
            catch (NumberFormatException exception) {
                return failure("device: invalid_argument: event limit must be an integer");
            }
            if (limit < 1 || limit > DeviceRegistry.MAX_EVENT_POLL_RESULTS) {
                return failure("device: invalid_argument: event limit must be from 1 to "
                        + DeviceRegistry.MAX_EVENT_POLL_RESULTS);
            }
        }
        return formatBatch(access.pollEvents(limit));
    }

    private static DeviceShellCommand.Outcome formatBatch(DeviceEventBatch batch) {
        List<String> lines = new ArrayList<>();
        if (batch.dropped() > 0) lines.add("dropped=" + batch.dropped());
        for (DeviceEvent event : batch.events()) {
            lines.add(event.sequence() + " " + event.sourceDeviceId() + " " + event.type()
                    + " " + DeviceShellCommand.formatValue(event.payload()));
        }
        if (lines.isEmpty()) lines.add("(no events)");
        return new DeviceShellCommand.Outcome(0, lines);
    }

    private static UUID uuid(String value) {
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException exception) { return null; }
    }

    private static DeviceShellCommand.Outcome usage() {
        return failure("device: usage: device events [limit] | events subscribe <source-uuid|*> <types-csv|*> [debounce-ticks] [coalesce] | events poll <subscription-uuid> [limit] | events diagnostics <subscription-uuid> | events unsubscribe <subscription-uuid>");
    }

    private static DeviceShellCommand.Outcome failure(String line) {
        return new DeviceShellCommand.Outcome(1, List.of(line));
    }
}
