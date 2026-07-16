package com.malice.terminalcraft.device;

import java.util.UUID;

/** Optional caller-bound capability for exact, replay-safe fluid movement between visible endpoints. */
public interface ExactFluidTransferAccess {
    DeviceResult transferExactFluid(UUID operationId, UUID sourceId, UUID destinationId,
                                    String resourceId, int amountMb);
}
