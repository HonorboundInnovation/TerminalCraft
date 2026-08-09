package com.malice.terminalcraft.server;

import com.malice.terminalcraft.device.DeviceCallContext;
import com.malice.terminalcraft.device.DeviceEventWaitAccess;
import com.malice.terminalcraft.device.DeviceEventWaitResult;
import com.malice.terminalcraft.device.DeviceResult;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Small adapter joining caller-owned event waits to the persistent cooperative job scheduler.
 * Starting or polling a wait performs no command work; the job remains subject to normal
 * scheduler fairness, budget, cancellation, and continuation limits.
 */
public final class DeviceEventSchedulerBridge {
    private final DeviceEventWaitAccess waits;
    private final ServerJobScheduler scheduler;
    private final DeviceCallContext caller;

    public DeviceEventSchedulerBridge(DeviceEventWaitAccess waits, ServerJobScheduler scheduler,
                                      DeviceCallContext caller) {
        this.waits = Objects.requireNonNull(waits, "waits");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.caller = Objects.requireNonNull(caller, "caller");
    }

    /** Starts an owned wait and wakes the specified queued job when an event arrives. */
    public DeviceResult begin(UUID jobId, UUID subscriptionId, long gameTime, long timeoutTicks) {
        return waits.beginEventWait(Objects.requireNonNull(subscriptionId, "subscriptionId"),
                gameTime, timeoutTicks,
                wakeAt -> scheduler.wake(Objects.requireNonNull(jobId, "jobId"), caller, wakeAt));
    }

    public Optional<DeviceEventWaitResult> poll(UUID waitId, long gameTime) {
        return waits.pollEventWait(Objects.requireNonNull(waitId, "waitId"), gameTime);
    }

    public boolean cancel(UUID waitId) {
        return waits.cancelEventWait(Objects.requireNonNull(waitId, "waitId"));
    }

    /** Converts a still-pending result into the scheduler’s bounded continuation state. */
    public static ServerJobScheduler.StepResult step(DeviceEventWaitResult result,
                                                      int continuationVersion,
                                                      String continuation) {
        Objects.requireNonNull(result, "result");
        return result.status() == DeviceEventWaitResult.Status.WAITING
                ? ServerJobScheduler.StepResult.waitUntil(result.wakeAt(), continuationVersion, continuation)
                : ServerJobScheduler.StepResult.yield(continuationVersion, continuation);
    }
}
