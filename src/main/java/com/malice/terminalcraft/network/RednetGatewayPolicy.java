package com.malice.terminalcraft.network;

import java.util.Objects;

/**
 * Authoritative cross-dimensional RedNet transport policy.
 *
 * <p>RedNet state, discovery, queues, reliable delivery, and routes are dimension-local. No
 * gateway block or trusted gateway service exists in the current protocol, so crossing a
 * dimension boundary always fails closed. A future gateway must be an explicit authenticated,
 * bounded transport implementation; wireless range and matching names never imply a gateway.</p>
 */
public final class RednetGatewayPolicy {
    public static final String POLICY_ID = "dimension-local-no-gateway-v1";

    private RednetGatewayPolicy() {}

    /** Returns true only when both interfaces belong to the same dimension. */
    public static boolean permits(RednetInterface source, RednetInterface destination) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        return source.dimension().equals(destination.dimension());
    }

    /** No cross-dimensional gateway transport is registered in this protocol version. */
    public static boolean gatewayAvailable() {
        return false;
    }
}
