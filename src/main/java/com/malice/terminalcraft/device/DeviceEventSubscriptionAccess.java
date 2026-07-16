package com.malice.terminalcraft.device;

import java.util.Optional;
import java.util.UUID;

/** Caller-bound capability for owned, bounded event subscriptions. */
public interface DeviceEventSubscriptionAccess {
    DeviceResult subscribeEvents(DeviceEventSubscription subscription);

    DeviceEventBatch pollSubscription(UUID subscriptionId, int limit);

    Optional<DeviceEventDiagnostics> eventDiagnostics(UUID subscriptionId);

    boolean unsubscribeEvents(UUID subscriptionId);
}
