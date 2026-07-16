package com.malice.terminalcraft.network;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifies one logical server dimension without relying on a globally unique dimension name.
 * Server identity intentionally uses object identity so separate integrated/dedicated servers in
 * the same JVM can never share ephemeral RedNet state.
 */
final class RednetRuntimeScope {
    private final Object serverIdentity;
    private final String dimension;

    RednetRuntimeScope(Object serverIdentity, String dimension) {
        this.serverIdentity = Objects.requireNonNull(serverIdentity, "serverIdentity");
        this.dimension = Objects.requireNonNull(dimension, "dimension");
    }

    boolean belongsTo(Object server) {
        return serverIdentity == server;
    }

    Object serverIdentity() {
        return serverIdentity;
    }

    Endpoint endpoint(UUID modemId) {
        return new Endpoint(this, Objects.requireNonNull(modemId, "modemId"));
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof RednetRuntimeScope scope
                && serverIdentity == scope.serverIdentity && dimension.equals(scope.dimension);
    }

    @Override
    public int hashCode() {
        return 31 * System.identityHashCode(serverIdentity) + dimension.hashCode();
    }

    record Endpoint(RednetRuntimeScope scope, UUID modemId) {
        Endpoint {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(modemId, "modemId");
        }
    }
}
