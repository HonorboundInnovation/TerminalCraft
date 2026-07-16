package com.malice.terminalcraft.network;

import java.util.List;
import java.util.Objects;

/** Immutable result of resolving a route between two live logical RedNet interfaces. */
public record RednetRoute(RednetInterface source, RednetInterface destination, int routerHops,
                          List<WiredNetworkTopology.RouterPass> routerPasses) {
    public RednetRoute {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        if (!RednetGatewayPolicy.permits(source, destination)) {
            throw new IllegalArgumentException("RedNet routes cannot cross dimensions");
        }
        if (source.transport() != destination.transport()) {
            throw new IllegalArgumentException("RedNet routes require matching transports");
        }
        if (routerHops < 0 || routerHops > NetworkEnvelope.MAX_HOPS) {
            throw new IllegalArgumentException("invalid RedNet router hop count");
        }
        if (routerPasses == null) throw new IllegalArgumentException("router passes are required");
        routerPasses = List.copyOf(routerPasses);
        if (source.transport() == RednetInterface.Transport.WIRELESS
                && (!routerPasses.isEmpty() || routerHops != 0)) {
            throw new IllegalArgumentException("wireless routes cannot contain router passes");
        }
        if (source.transport() == RednetInterface.Transport.WIRED
                && routerHops != routerPasses.size()) {
            throw new IllegalArgumentException("wired route hops must match router passes");
        }
    }
}
