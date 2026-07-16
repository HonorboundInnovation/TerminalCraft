package com.malice.terminalcraft.device;

import java.util.UUID;

/** Optional caller-bound capability for exact, replay-safe item movement between visible endpoints. */
public interface ExactItemTransferAccess {
    DeviceResult transferExactItems(UUID operationId, UUID sourceId, UUID destinationId,
                                    String resourceId, int count);
}
