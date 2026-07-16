package com.malice.terminalcraft.network;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Bounded logical RedNet address independent of world position.
 *
 * <p>A stable device UUID is authoritative. A hostname is an optional, mutable directory alias and
 * must never replace or redefine that identity.</p>
 */
public record RednetAddress(UUID deviceId, String hostname) {
    public static final int MAX_ENCODED_LENGTH = NetworkEnvelope.MAX_ADDRESS_LENGTH;
    private static final String PREFIX = "rednet:";

    public RednetAddress {
        Objects.requireNonNull(deviceId, "deviceId");
        if (hostname == null || hostname.isEmpty()) {
            hostname = "";
        } else {
            hostname = RednetHostName.normalize(hostname)
                    .orElseThrow(() -> new IllegalArgumentException("invalid RedNet hostname"));
        }
    }

    /** Returns the alias when present, otherwise the stable UUID text used by legacy envelopes. */
    public String displayName() {
        return hostname.isEmpty() ? deviceId.toString() : hostname;
    }

    /** Explicit representation that preserves identity even when the alias later changes. */
    public String encoded() {
        return PREFIX + deviceId + (hostname.isEmpty() ? "" : "/" + hostname);
    }

    /** Parses only the explicit identity-preserving representation emitted by {@link #encoded()}. */
    public static Optional<RednetAddress> parse(String value) {
        if (value == null || value.length() > MAX_ENCODED_LENGTH
                || !value.toLowerCase(Locale.ROOT).startsWith(PREFIX)) {
            return Optional.empty();
        }
        String body = value.substring(PREFIX.length());
        int separator = body.indexOf('/');
        String idText = separator < 0 ? body : body.substring(0, separator);
        String alias = separator < 0 ? "" : body.substring(separator + 1);
        if (separator >= 0 && alias.isEmpty()) return Optional.empty();
        try {
            return Optional.of(new RednetAddress(UUID.fromString(idText), alias));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }
}
