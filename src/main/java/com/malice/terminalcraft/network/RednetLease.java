package com.malice.terminalcraft.network;

import java.util.Objects;
import java.util.UUID;

/** One bounded, in-memory RedNet address lease issued by a router or link-local fallback. */
public record RednetLease(UUID modemId, String networkId, String address, UUID routerId,
                          long issuedAt, long expiresAt) {
    public static final int MAX_NETWORK_ID_LENGTH = 96;
    public static final int MAX_ADDRESS_LENGTH = 32;

    public RednetLease {
        Objects.requireNonNull(modemId, "modemId");
        if (networkId == null || networkId.isBlank() || networkId.length() > MAX_NETWORK_ID_LENGTH) {
            throw new IllegalArgumentException("invalid RedNet lease network");
        }
        if (address == null || address.isBlank() || address.length() > MAX_ADDRESS_LENGTH) {
            throw new IllegalArgumentException("invalid RedNet lease address");
        }
        if (issuedAt < 0 || expiresAt < issuedAt) {
            throw new IllegalArgumentException("invalid RedNet lease lifetime");
        }
    }

    public boolean routerIssued() {
        return routerId != null;
    }

    public String source() {
        return routerIssued() ? "router" : "link-local";
    }
}
