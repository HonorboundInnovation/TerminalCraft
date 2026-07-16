package com.malice.terminalcraft.network;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Bounded, versioned application protocol identifier carried by RedNet envelopes and services. */
public record RednetProtocol(String id, int version, String payloadType) {
    public static final int MAX_VERSION = 65_535;
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/_.-]+");

    public RednetProtocol {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(payloadType, "payloadType");
        id = id.toLowerCase(Locale.ROOT);
        payloadType = payloadType.toLowerCase(Locale.ROOT);
        if (id.length() > NetworkEnvelope.MAX_PROTOCOL_LENGTH || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("invalid RedNet protocol identifier");
        }
        if (version < 1 || version > MAX_VERSION) {
            throw new IllegalArgumentException("invalid RedNet protocol version");
        }
        if (payloadType.isBlank() || payloadType.length() > NetworkEnvelope.MAX_PAYLOAD_TYPE_LENGTH
                || payloadType.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("invalid RedNet payload type");
        }
    }

    public static Optional<RednetProtocol> parse(String id, int version, String payloadType) {
        try {
            return Optional.of(new RednetProtocol(id, version, payloadType));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return Optional.empty();
        }
    }
}
