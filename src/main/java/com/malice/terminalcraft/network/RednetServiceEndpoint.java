package com.malice.terminalcraft.network;

import java.util.Objects;

/** Resolved, identity-preserving endpoint for one dimension-local named RedNet service. */
public record RednetServiceEndpoint(String name, RednetAddress address, int port,
                                    RednetProtocol protocol) {
    public RednetServiceEndpoint {
        name = RednetHostName.normalize(name)
                .orElseThrow(() -> new IllegalArgumentException("invalid RedNet service name"));
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(protocol, "protocol");
        if (port < 0 || port > 65_535) throw new IllegalArgumentException("invalid RedNet service port");
    }
}
