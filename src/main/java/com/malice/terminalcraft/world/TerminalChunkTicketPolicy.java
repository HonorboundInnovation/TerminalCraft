package com.malice.terminalcraft.world;

/**
 * Immutable admission rules for TerminalCraft-owned chunk tickets.
 *
 * <p>The policy counts actual TerminalCraft tickets rather than terminals or chunks already
 * force-loaded by another owner. A zero quota therefore denies every new ticket.</p>
 */
public record TerminalChunkTicketPolicy(boolean enabled, int maxPerDimension, int maxPerServer) {
    public TerminalChunkTicketPolicy {
        maxPerDimension = Math.max(0, maxPerDimension);
        maxPerServer = Math.max(0, maxPerServer);
    }

    public boolean allows(int dimensionTickets, int serverTickets) {
        return enabled
                && dimensionTickets >= 0
                && serverTickets >= 0
                && dimensionTickets < maxPerDimension
                && serverTickets < maxPerServer;
    }
}
