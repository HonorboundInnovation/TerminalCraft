package com.malice.terminalcraft.device;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Device API adapter for a bounded TerminalCraft rednet modem. */
public final class ModemDeviceEndpoint implements DeviceEndpoint {
    private static final DeviceParameterDescriptor CHANNEL = new DeviceParameterDescriptor(
            "channel", DeviceValueType.NUMBER, true, "Channel from 0 to 65535");
    private static final DeviceMethodDescriptor LABEL_GET = method("label.get", "Returns the modem label", List.of(), DeviceValueType.STRING);
    private static final DeviceMethodDescriptor LABEL_SET = writeMethod("label.set", "Sets the modem label",
            List.of(new DeviceParameterDescriptor("label", DeviceValueType.STRING, true, "New label")));
    private static final DeviceMethodDescriptor CHANNELS_GET = method("channels.get", "Returns open channels", List.of(), DeviceValueType.LIST);
    private static final DeviceMethodDescriptor OPEN = writeMethod("channel.open", "Opens a channel", List.of(CHANNEL));
    private static final DeviceMethodDescriptor CLOSE = writeMethod("channel.close", "Closes a channel", List.of(CHANNEL));
    private static final DeviceMethodDescriptor CLOSE_ALL = writeMethod("channels.close_all", "Closes all channels", List.of());
    private static final DeviceMethodDescriptor TRANSMIT = writeMethod("transmit", "Transmits a bounded rednet message", List.of(
            CHANNEL,
            new DeviceParameterDescriptor("reply_channel", DeviceValueType.NUMBER, true, "Reply channel from 0 to 65535"),
            new DeviceParameterDescriptor("message", DeviceValueType.STRING, true, "Message payload")));
    private static final DeviceMethodDescriptor RECEIVE = new DeviceMethodDescriptor("receive", "Receives and removes pending messages",
            List.of(new DeviceParameterDescriptor("limit", DeviceValueType.NUMBER, false, "Maximum messages")),
            DeviceValueType.LIST, DeviceCallContext.WRITE);

    private final UUID deviceId;
    private final String address;
    private final ModemDevice modem;
    private final BooleanSupplier online;
    private final BooleanSupplier loaded;
    private String lastLabel = "modem";

    public ModemDeviceEndpoint(UUID deviceId, String address, ModemDevice modem,
                               BooleanSupplier online, BooleanSupplier loaded) {
        this.deviceId = Objects.requireNonNull(deviceId, "deviceId");
        this.address = requireAddress(address);
        this.modem = Objects.requireNonNull(modem, "modem");
        this.online = Objects.requireNonNull(online, "online");
        this.loaded = Objects.requireNonNull(loaded, "loaded");
        if (loaded.getAsBoolean() && online.getAsBoolean()) lastLabel = safeLabel(modem.label());
    }

    @Override
    public synchronized DeviceDescriptor descriptor() {
        boolean isLoaded = loaded.getAsBoolean();
        boolean isOnline = online.getAsBoolean();
        if (isLoaded && isOnline) lastLabel = safeLabel(modem.label());
        Map<String, DeviceValue> properties = new LinkedHashMap<>();
        properties.put("label", DeviceValue.of(lastLabel));
        properties.put("wireless", DeviceValue.of(isLoaded && isOnline && modem.wireless()));
        properties.put("range", DeviceValue.of(isLoaded && isOnline ? modem.range() : 0));
        properties.put("open_channel_count", DeviceValue.of(isLoaded && isOnline ? modem.openChannels().size() : 0));
        properties.put("pending_count", DeviceValue.of(isLoaded && isOnline ? modem.pendingCount() : 0));
        return new DeviceDescriptor(deviceId, "terminalcraft:modem", "modem", lastLabel,
                "terminalcraft", address, Set.of("network_interface"), properties,
                List.of(LABEL_GET, LABEL_SET, CHANNELS_GET, OPEN, CLOSE, CLOSE_ALL, TRANSMIT, RECEIVE),
                Set.of("message_received"), Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE),
                isOnline, isLoaded);
    }

    @Override
    public synchronized DeviceResult call(String method, List<DeviceValue> arguments) {
        List<DeviceValue> args = arguments == null ? List.of() : arguments;
        return switch (method == null ? "" : method) {
            case "label.get" -> DeviceResult.success(DeviceValue.of(safeLabel(modem.label())));
            case "label.set" -> setLabel(args);
            case "channels.get" -> DeviceResult.success(channelValues(modem.openChannels()));
            case "channel.open" -> changeChannel(args, true);
            case "channel.close" -> changeChannel(args, false);
            case "channels.close_all" -> { modem.closeAll(); yield DeviceResult.success(); }
            case "transmit" -> transmit(args);
            case "receive" -> receive(args);
            default -> DeviceResult.failure(DeviceErrorCode.UNSUPPORTED, "method is unsupported", false);
        };
    }

    private DeviceResult setLabel(List<DeviceValue> args) {
        String value = ((DeviceValue.StringValue) args.get(0)).value().trim();
        if (value.isEmpty() || value.length() > 64) return invalid("label must contain 1 to 64 characters");
        modem.setLabel(value);
        return DeviceResult.success();
    }

    private DeviceResult changeChannel(List<DeviceValue> args, boolean open) {
        Integer channel = channel(args.get(0));
        if (channel == null) return invalid("channel must be an integer from 0 to 65535");
        if (open && !modem.open(channel)) return DeviceResult.failure(DeviceErrorCode.CAPACITY_EXCEEDED, "open channel limit reached", false);
        if (!open && !modem.close(channel)) return DeviceResult.failure(DeviceErrorCode.NOT_FOUND, "channel is not open", false);
        return DeviceResult.success();
    }

    private DeviceResult transmit(List<DeviceValue> args) {
        Integer channel = channel(args.get(0));
        Integer reply = channel(args.get(1));
        String message = ((DeviceValue.StringValue) args.get(2)).value();
        if (channel == null || reply == null) return invalid("channels must be integers from 0 to 65535");
        if (message.length() > 4096) return invalid("message exceeds 4096 characters");
        return modem.transmit(channel, reply, message) ? DeviceResult.success()
                : DeviceResult.failure(DeviceErrorCode.OFFLINE, "modem requires an open channel", true);
    }

    private DeviceResult receive(List<DeviceValue> args) {
        int limit = modem.maxReceiveBatch();
        if (!args.isEmpty()) {
            Double raw = ((DeviceValue.NumberValue) args.get(0)).value();
            if (raw != Math.rint(raw) || raw < 1 || raw > modem.maxReceiveBatch())
                return invalid("limit must be an integer from 1 to " + modem.maxReceiveBatch());
            limit = raw.intValue();
        }
        List<DeviceValue> values = new ArrayList<>();
        for (String message : modem.receive(limit)) values.add(DeviceValue.of(message));
        return DeviceResult.success(DeviceValue.list(values));
    }

    private static Integer channel(DeviceValue value) {
        double raw = ((DeviceValue.NumberValue) value).value();
        return raw == Math.rint(raw) && raw >= 0 && raw <= 65535 ? (int) raw : null;
    }

    private static DeviceValue channelValues(List<Integer> channels) {
        List<DeviceValue> values = new ArrayList<>();
        for (int channel : channels) values.add(DeviceValue.of(channel));
        return DeviceValue.list(values);
    }

    private static DeviceMethodDescriptor method(String name, String description, List<DeviceParameterDescriptor> parameters, DeviceValueType type) {
        return new DeviceMethodDescriptor(name, description, parameters, type);
    }
    private static DeviceMethodDescriptor writeMethod(String name, String description, List<DeviceParameterDescriptor> parameters) {
        return new DeviceMethodDescriptor(name, description, parameters, DeviceValueType.NULL, DeviceCallContext.WRITE);
    }
    private static DeviceResult invalid(String message) { return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT, message, false); }
    private static String safeLabel(String label) { return label == null || label.isBlank() ? "modem" : label.substring(0, Math.min(64, label.length())); }
    private static String requireAddress(String address) {
        Objects.requireNonNull(address, "address");
        if (address.isBlank() || address.length() > DeviceValue.MAX_STRING_LENGTH) throw new IllegalArgumentException("invalid address");
        return address;
    }
}
