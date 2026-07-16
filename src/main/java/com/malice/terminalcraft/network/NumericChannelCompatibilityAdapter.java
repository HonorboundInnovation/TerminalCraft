package com.malice.terminalcraft.network;

import java.util.UUID;

/**
 * Compatibility boundary for the original numeric-channel modem API.
 *
 * <p>Channels map directly to envelope ports, reply channels map to reply ports, payloads remain
 * bounded UTF-8-compatible text, and broadcast destination remains {@code *}. The adapter does not
 * claim acknowledgement or durable delivery.</p>
 */
public final class NumericChannelCompatibilityAdapter {
    public static final String PROTOCOL = "terminalcraft:rednet-channel";
    public static final String BROADCAST_DESTINATION = "*";

    private NumericChannelCompatibilityAdapter() {}

    public static NetworkEnvelope encode(UUID messageId, String source, int channel,
                                         int replyChannel, String message, long gameTime) {
        return new NetworkEnvelope(NetworkEnvelope.CURRENT_VERSION, messageId, source,
                BROADCAST_DESTINATION, channel, replyChannel, PROTOCOL,
                NetworkEnvelope.TEXT_PAYLOAD, message, gameTime, NetworkEnvelope.MAX_HOPS,
                "", null);
    }

    public static ChannelMessage decode(NetworkEnvelope envelope) {
        if (envelope == null || !PROTOCOL.equals(envelope.protocol())
                || !NetworkEnvelope.TEXT_PAYLOAD.equals(envelope.payloadType())
                || !BROADCAST_DESTINATION.equals(envelope.destination())) {
            throw new IllegalArgumentException("not a numeric-channel compatibility envelope");
        }
        return new ChannelMessage(envelope.port(), envelope.replyPort(), envelope.payload());
    }

    public record ChannelMessage(int channel, int replyChannel, String message) {}
}
