package com.malice.terminalcraft.network;

import java.util.Locale;
import java.util.UUID;

/**
 * Beginner-friendly defaults shared by every RedNet modem.
 *
 * <p>The values in this class are deliberately protocol-level defaults rather than block state.
 * A modem can opt out and use the existing explicit hostname, channel, and logical-network
 * controls, while a newly placed modem has a useful identity and port immediately.</p>
 */
public final class RednetAutoConfiguration {
    public static final int DEFAULT_CHANNEL = 42;
    public static final int DEFAULT_REPLY_CHANNEL = 43;
    public static final String HOST_PREFIX = "node-";
    public static final String CHANNEL_PROTOCOL_ID = "terminalcraft:rednet-channel";
    public static final int CHANNEL_PROTOCOL_VERSION = 1;

    private RednetAutoConfiguration() {}

    /** Returns a deterministic, human-readable alias that remains stable for the modem UUID. */
    public static String hostname(UUID modemId) {
        if (modemId == null) return HOST_PREFIX + "unknown";
        String compact = modemId.toString().replace("-", "").toLowerCase(Locale.ROOT);
        // Twelve hexadecimal characters provide a useful display name while making accidental
        // collisions negligible. The UUID remains the authoritative identity in every envelope.
        return HOST_PREFIX + compact.substring(0, Math.min(12, compact.length()));
    }

    public static boolean isDefaultChannel(int channel) {
        return channel == DEFAULT_CHANNEL;
    }
}
