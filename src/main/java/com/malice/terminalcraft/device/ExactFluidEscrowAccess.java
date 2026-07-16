package com.malice.terminalcraft.device;

import java.util.UUID;

/** Optional server-authenticated administration surface for exact-fluid escrow custody. */
public interface ExactFluidEscrowAccess {
    DeviceResult listFluidEscrow(int limit);
    DeviceResult recoverFluidEscrow(UUID escrowId, UUID destinationId);
}
