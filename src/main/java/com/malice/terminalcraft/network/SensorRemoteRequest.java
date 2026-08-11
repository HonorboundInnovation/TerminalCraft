package com.malice.terminalcraft.network;

import java.util.Locale;
import java.util.Optional;

/** Bounded request format for a wireless Sensor telemetry service. */
public record SensorRemoteRequest(Operation operation, String channel) {
    public static final RednetProtocol PROTOCOL = new RednetProtocol(
            "terminalcraft:sensor-telemetry", 1, "application/x-terminalcraft-sensor");
    public static final int MAX_CHANNEL_CHARS = 32;

    public enum Operation { LIST, SNAPSHOT, READ }

    public SensorRemoteRequest {
        if (operation == null) throw new IllegalArgumentException("sensor operation is required");
        channel = channel == null ? "" : channel.trim().toLowerCase(Locale.ROOT);
        if (channel.length() > MAX_CHANNEL_CHARS || channel.indexOf('|') >= 0 || channel.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("sensor channel is invalid");
        }
        if (operation == Operation.READ && channel.isBlank()) {
            throw new IllegalArgumentException("sensor read requires a channel");
        }
    }

    public static SensorRemoteRequest list() { return new SensorRemoteRequest(Operation.LIST, ""); }
    public static SensorRemoteRequest snapshot() { return new SensorRemoteRequest(Operation.SNAPSHOT, ""); }
    public static SensorRemoteRequest read(String channel) { return new SensorRemoteRequest(Operation.READ, channel); }

    public String encode() {
        return "1|" + operation.name().toLowerCase(Locale.ROOT) + (channel.isBlank() ? "" : "|" + channel);
    }

    public static Optional<SensorRemoteRequest> decode(String payload) {
        if (payload == null || payload.length() > NetworkEnvelope.MAX_PAYLOAD_LENGTH) return Optional.empty();
        String[] fields = payload.split("\\|", -1);
        if (fields.length < 2 || fields.length > 3 || !"1".equals(fields[0])) return Optional.empty();
        try {
            Operation operation = Operation.valueOf(fields[1].toUpperCase(Locale.ROOT));
            return Optional.of(new SensorRemoteRequest(operation, fields.length == 3 ? fields[2] : ""));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }
}
