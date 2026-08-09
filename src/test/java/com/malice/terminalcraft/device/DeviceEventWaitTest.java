package com.malice.terminalcraft.device;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Headless coverage for bounded, non-blocking event wait tokens. */
public final class DeviceEventWaitTest {
    private DeviceEventWaitTest() {}

    public static void main(String[] args) {
        PrincipalIdentity owner = PrincipalIdentity.service(UUID.randomUUID(), "wait-owner");
        PrincipalIdentity foreign = PrincipalIdentity.service(UUID.randomUUID(), "foreign");
        UUID source = UUID.randomUUID();
        DeviceEventRuntime runtime = new DeviceEventRuntime();
        UUID subscription = runtime.subscribe(owner,
                new DeviceEventSubscription(source, Set.of("changed"), 0, false));
        AtomicLong wakeAt = new AtomicLong(-1);

        DeviceEventWaitResult started = runtime.beginWait(owner, subscription, 10, 20,
                wakeAt::set).orElseThrow();
        require(started.status() == DeviceEventWaitResult.Status.WAITING,
                "empty wait returns a pending token");
        require(started.wakeAt() == 30 && runtime.waitCount() == 1,
                "wait deadline and active-token accounting are bounded");
        require(runtime.pollWait(foreign, started.waitId(), 10).isEmpty(),
                "foreign principal cannot inspect a wait token");
        require(runtime.pollWait(owner, started.waitId(), 29).orElseThrow().status()
                        == DeviceEventWaitResult.Status.WAITING,
                "polling before deadline remains cooperative");

        runtime.publish(event(1, source, 15));
        require(wakeAt.get() == 15, "matching publication invokes the scheduler wake hook");
        DeviceEventWaitResult delivered = runtime.pollWait(owner, started.waitId(), 15).orElseThrow();
        require(delivered.status() == DeviceEventWaitResult.Status.EVENT
                        && delivered.event().orElseThrow().sequence() == 1,
                "matching publication completes the token");
        require(runtime.waitCount() == 0 && runtime.diagnostics(owner, subscription).orElseThrow().delivered() == 1,
                "completed waits release capacity and count delivery");

        DeviceEventWaitResult timeout = runtime.beginWait(owner, subscription, 40, 5).orElseThrow();
        require(runtime.pollWait(owner, timeout.waitId(), 44).orElseThrow().status()
                        == DeviceEventWaitResult.Status.WAITING,
                "timeout is not wall-clock based");
        require(runtime.pollWait(owner, timeout.waitId(), 45).orElseThrow().status()
                        == DeviceEventWaitResult.Status.TIMEOUT,
                "logical deadline resolves the wait");

        DeviceEventWaitResult cancelled = runtime.beginWait(owner, subscription, 50, 20).orElseThrow();
        require(runtime.cancelWait(owner, cancelled.waitId()), "owner can cancel a pending wait");
        require(runtime.pollWait(owner, cancelled.waitId(), 50).isEmpty(),
                "cancelled token is no longer observable");
        require(!runtime.cancelWait(foreign, UUID.randomUUID()), "foreign cancellation is concealed");

        runtime.publish(event(2, source, 60));
        DeviceEventWaitResult immediate = runtime.beginWait(owner, subscription, 60, 20).orElseThrow();
        require(immediate.status() == DeviceEventWaitResult.Status.EVENT
                        && immediate.event().orElseThrow().sequence() == 2,
                "queued events are consumed immediately when a wait begins");
        require(runtime.waitCount() == 0, "immediate completion does not retain a token");

        requireThrows(() -> runtime.beginWait(owner, subscription, 0, DeviceEventRuntime.MAX_WAIT_TICKS + 1),
                "oversized timeout is rejected");
        requireThrows(() -> runtime.beginWait(owner, subscription, -1, 1),
                "negative game time is rejected");
        System.out.println("Device event wait tests: OK");
    }

    private static DeviceEvent event(long sequence, UUID source, long gameTime) {
        return new DeviceEvent(sequence, source, "changed", gameTime,
                new DeviceValue.MapValue(Map.of("sequence", DeviceValue.of(sequence))));
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void requireThrows(Runnable action, String message) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError(message);
    }
}
