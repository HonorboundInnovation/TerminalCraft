package com.malice.terminalcraft.network;

import com.malice.terminalcraft.scada.ScadaTag;

import java.util.Locale;
import java.util.Optional;

/** Bounded, read-only SCADA request protocol for an adjacent server-rack gateway. */
public record ScadaRemoteRequest(Operation operation, String selector, int limit) {
    public static final RednetProtocol PROTOCOL = new RednetProtocol(
            "terminalcraft:scada", 1, "application/x-terminalcraft-scada");
    public static final int MAX_LIMIT = 128;

    public enum Operation { STATUS, TAGS, READ, HISTORY, ALARMS }

    public ScadaRemoteRequest {
        if (operation == null) throw new IllegalArgumentException("SCADA operation is required");
        selector = selector == null ? "" : selector.trim().toLowerCase(Locale.ROOT);
        if (selector.length() > ScadaTag.MAX_NAME_CHARS || selector.indexOf('|') >= 0
                || selector.indexOf('\n') >= 0 || selector.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("invalid SCADA selector");
        }
        if ((operation == Operation.READ || operation == Operation.HISTORY) && selector.isBlank()) {
            throw new IllegalArgumentException("SCADA operation requires a tag");
        }
        if (limit < 1 || limit > MAX_LIMIT) throw new IllegalArgumentException("SCADA limit is outside bounds");
    }

    public String encode() {
        return "1|" + operation.name().toLowerCase(Locale.ROOT) + "|" + selector + "|" + limit;
    }

    public static Optional<ScadaRemoteRequest> decode(String payload) {
        if (payload == null || payload.length() > NetworkEnvelope.MAX_PAYLOAD_LENGTH) return Optional.empty();
        String[] fields = payload.split("\\|", -1);
        if (fields.length != 4 || !"1".equals(fields[0])) return Optional.empty();
        try {
            return Optional.of(new ScadaRemoteRequest(Operation.valueOf(fields[1].toUpperCase(Locale.ROOT)),
                    fields[2], Integer.parseInt(fields[3])));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }
}
