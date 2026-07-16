package com.malice.terminalcraft.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.Optional;
import java.util.UUID;

/** Immutable, bounded transport envelope for TerminalCraft in-world networks. */
public record NetworkEnvelope(
        int version,
        UUID messageId,
        String source,
        String destination,
        int port,
        int replyPort,
        String protocol,
        String payloadType,
        String payload,
        long gameTime,
        int hopLimit,
        String replyTo,
        UUID correlationId) {
    public static final int CURRENT_VERSION = 2;
    public static final int LEGACY_VERSION = 1;
    public static final String TEXT_PAYLOAD = "text/plain";
    public static final int MAX_ADDRESS_LENGTH = 128;
    public static final int MAX_PROTOCOL_LENGTH = 64;
    public static final int MAX_PAYLOAD_TYPE_LENGTH = 64;
    public static final int MAX_PAYLOAD_LENGTH = 4096;
    public static final int MAX_HOPS = 32;

    public NetworkEnvelope {
        if (version != CURRENT_VERSION) throw new IllegalArgumentException("unsupported version");
        if (messageId == null) throw new IllegalArgumentException("messageId is required");
        source = bounded(source, MAX_ADDRESS_LENGTH, "source");
        destination = bounded(destination, MAX_ADDRESS_LENGTH, "destination");
        protocol = required(protocol, MAX_PROTOCOL_LENGTH, "protocol");
        payloadType = required(payloadType, MAX_PAYLOAD_TYPE_LENGTH, "payload type");
        payload = bounded(payload, MAX_PAYLOAD_LENGTH, "payload");
        replyTo = bounded(replyTo, MAX_ADDRESS_LENGTH, "reply-to");
        if (port < 0 || port > 65535 || replyPort < 0 || replyPort > 65535) {
            throw new IllegalArgumentException("port outside 0..65535");
        }
        if (gameTime < 0) throw new IllegalArgumentException("game time must not be negative");
        if (hopLimit < 0 || hopLimit > MAX_HOPS) {
            throw new IllegalArgumentException("hop limit outside 0.." + MAX_HOPS);
        }
    }

    /** Source-compatible constructor for the version-1 text envelope API. */
    public NetworkEnvelope(int version, UUID messageId, String source, String destination,
                           int port, int replyPort, String protocol, String payload,
                           long gameTime, int hopLimit, UUID correlationId) {
        this(CURRENT_VERSION, messageId, source, destination, port, replyPort, protocol,
                TEXT_PAYLOAD, payload, gameTime, hopLimit, "", correlationId);
        if (version != LEGACY_VERSION && version != CURRENT_VERSION) {
            throw new IllegalArgumentException("unsupported version");
        }
    }

    public static NetworkEnvelope channel(UUID messageId, String source, int port, int replyPort,
                                          String payload, long gameTime) {
        return NumericChannelCompatibilityAdapter.encode(
                messageId, source, port, replyPort, payload, gameTime);
    }

    public NetworkEnvelope forwarded() { return forwarded(1); }

    /** Returns an immutable copy after the requested number of router transitions. */
    public NetworkEnvelope forwarded(int hops) {
        if (hops < 0) throw new IllegalArgumentException("forwarding hops must be non-negative");
        if (hops > hopLimit) throw new IllegalStateException("hop limit exhausted");
        if (hops == 0) return this;
        return new NetworkEnvelope(version, messageId, source, destination, port, replyPort,
                protocol, payloadType, payload, gameTime, hopLimit - hops, replyTo, correlationId);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Version", version);
        tag.putUUID("MessageId", messageId);
        tag.putString("Source", source);
        tag.putString("Destination", destination);
        tag.putInt("Port", port);
        tag.putInt("ReplyPort", replyPort);
        tag.putString("Protocol", protocol);
        tag.putString("PayloadType", payloadType);
        tag.putString("Payload", payload);
        tag.putLong("GameTime", gameTime);
        tag.putInt("HopLimit", hopLimit);
        tag.putString("ReplyTo", replyTo);
        if (correlationId != null) tag.putUUID("CorrelationId", correlationId);
        return tag;
    }

    /** Rejects unsupported, missing, malformed, or oversized records without throwing. */
    public static Optional<NetworkEnvelope> load(CompoundTag tag) {
        if (tag == null || !tag.contains("Version", Tag.TAG_INT) || !tag.hasUUID("MessageId")
                || !tag.contains("Source", Tag.TAG_STRING) || !tag.contains("Destination", Tag.TAG_STRING)
                || !tag.contains("Port", Tag.TAG_INT) || !tag.contains("ReplyPort", Tag.TAG_INT)
                || !tag.contains("Protocol", Tag.TAG_STRING) || !tag.contains("Payload", Tag.TAG_STRING)
                || !tag.contains("GameTime", Tag.TAG_LONG) || !tag.contains("HopLimit", Tag.TAG_INT)) {
            return Optional.empty();
        }
        int storedVersion = tag.getInt("Version");
        if (storedVersion != LEGACY_VERSION && storedVersion != CURRENT_VERSION) return Optional.empty();
        if (storedVersion == CURRENT_VERSION && (!tag.contains("PayloadType", Tag.TAG_STRING)
                || !tag.contains("ReplyTo", Tag.TAG_STRING))) return Optional.empty();
        try {
            return Optional.of(new NetworkEnvelope(
                    CURRENT_VERSION, tag.getUUID("MessageId"), tag.getString("Source"),
                    tag.getString("Destination"), tag.getInt("Port"), tag.getInt("ReplyPort"),
                    tag.getString("Protocol"), storedVersion == LEGACY_VERSION ? TEXT_PAYLOAD
                            : tag.getString("PayloadType"), tag.getString("Payload"),
                    tag.getLong("GameTime"), tag.getInt("HopLimit"),
                    storedVersion == LEGACY_VERSION ? "" : tag.getString("ReplyTo"),
                    tag.hasUUID("CorrelationId") ? tag.getUUID("CorrelationId") : null));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static String required(String value, int maximum, String field) {
        String bounded = bounded(value, maximum, field);
        if (bounded.isBlank()) throw new IllegalArgumentException(field + " is required");
        return bounded;
    }

    private static String bounded(String value, int maximum, String field) {
        String safe = value == null ? "" : value;
        if (safe.length() > maximum) throw new IllegalArgumentException(field + " is too long");
        return safe;
    }
}
