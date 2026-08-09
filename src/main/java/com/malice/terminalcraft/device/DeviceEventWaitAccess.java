package com.malice.terminalcraft.device;

import java.util.Optional;
import java.util.UUID;
import java.util.function.LongConsumer;

/** Caller-bound capability for bounded, cooperative event waits. */
public interface DeviceEventWaitAccess {
    /** Starts a wait without blocking; the returned value is either ready or a wait token. */
    DeviceResult beginEventWait(UUID subscriptionId, long gameTime, long timeoutTicks);

    /** Internal scheduler hook: wakes the parked owner when the wait receives an event. */
    DeviceResult beginEventWait(UUID subscriptionId, long gameTime, long timeoutTicks,
                                LongConsumer wakeup);

    /** Polls a token and resolves it on the first matching event or at its deadline. */
    Optional<DeviceEventWaitResult> pollEventWait(UUID waitId, long gameTime);

    /** Cancels an owned wait token. */
    boolean cancelEventWait(UUID waitId);
}
