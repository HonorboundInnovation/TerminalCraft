package com.malice.terminalcraft.device;

import java.util.UUID;

/** Optional server-authenticated administration surface for exact-item escrow custody. */
public interface ExactItemEscrowAccess {
    DeviceResult listItemEscrow(int limit);
    DeviceResult recoverItemEscrow(UUID escrowId, UUID destinationId);
}
