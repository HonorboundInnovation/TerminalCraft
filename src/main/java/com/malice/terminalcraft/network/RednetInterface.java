package com.malice.terminalcraft.network;

import net.minecraft.core.BlockPos;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Immutable snapshot of one live RedNet modem attachment in a dimension. */
public record RednetInterface(RednetAddress address, Transport transport, String dimension,
                              BlockPos position, int range, List<Integer> openPorts) {
    public static final int MAX_OPEN_PORTS = 256;

    public enum Transport { WIRED, WIRELESS }

    public RednetInterface {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(transport, "transport");
        if (dimension == null || dimension.isBlank()
                || dimension.length() > NetworkEnvelope.MAX_ADDRESS_LENGTH) {
            throw new IllegalArgumentException("invalid RedNet interface dimension");
        }
        Objects.requireNonNull(position, "position");
        position = position.immutable();
        if (range < 0) throw new IllegalArgumentException("invalid RedNet interface range");
        if (openPorts == null || openPorts.size() > MAX_OPEN_PORTS
                || openPorts.stream().anyMatch(port -> port == null || port < 0 || port > 65_535)) {
            throw new IllegalArgumentException("invalid RedNet interface ports");
        }
        openPorts = openPorts.stream().distinct().sorted(Comparator.naturalOrder()).toList();
        if (transport == Transport.WIRED && range != 0) {
            throw new IllegalArgumentException("wired RedNet interfaces cannot declare wireless range");
        }
    }
}
