package com.malice.terminalcraft.server;

import com.malice.terminalcraft.device.DeviceCallContext;
import com.malice.terminalcraft.device.DeviceDescriptor;
import com.malice.terminalcraft.device.DeviceEndpoint;
import com.malice.terminalcraft.device.DeviceEventWaitAccess;
import com.malice.terminalcraft.device.DeviceEventWaitResult;
import com.malice.terminalcraft.device.DeviceMethodDescriptor;
import com.malice.terminalcraft.device.DeviceRegistry;
import com.malice.terminalcraft.device.DeviceResult;
import com.malice.terminalcraft.device.DeviceValue;
import com.malice.terminalcraft.device.DeviceValueType;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Headless integration coverage for event completion waking a parked scheduler job. */
public final class DeviceEventSchedulerBridgeTest {
    private DeviceEventSchedulerBridgeTest() {}

    public static void main(String[] args) {
        UUID source = UUID.randomUUID();
        DeviceRegistry registry = new DeviceRegistry();
        registry.register(new EventEndpoint(source));
        DeviceCallContext owner = DeviceCallContext.service(UUID.randomUUID(), "scheduler-owner",
                Set.of(DeviceCallContext.READ));
        DeviceResult subscriptionResult = registry.subscribeEvents(owner,
                new com.malice.terminalcraft.device.DeviceEventSubscription(
                        source, Set.of("changed"), 0, false));
        UUID subscription = UUID.fromString(((DeviceValue.StringValue)
                subscriptionResult.value().orElseThrow()).value());
        DeviceEventWaitAccess waits = (DeviceEventWaitAccess) registry.access(owner);
        ServerJobScheduler scheduler = new ServerJobScheduler();
        ServerJobScheduler.Job job = scheduler.submit(owner, "wait-for-change", 10);
        DeviceEventSchedulerBridge bridge = new DeviceEventSchedulerBridge(waits, scheduler, owner);

        int parked = scheduler.tickSteps(10, 1, current -> {
            DeviceResult started = bridge.begin(current.id(), subscription, 10, 20);
            require(started.isSuccess(), "scheduler can start an owned event wait");
            DeviceValue.MapValue value = (DeviceValue.MapValue) started.value().orElseThrow();
            String waitId = string(value, "wait_id");
            long wakeAt = number(value, "wake_at");
            require("waiting".equals(string(value, "status")), "wait starts pending");
            return ServerJobScheduler.StepResult.waitUntil(wakeAt, 1, waitId);
        });
        require(parked == 1 && scheduler.get(job.id()).state() == ServerJobScheduler.State.QUEUED
                        && scheduler.get(job.id()).eligibleAt() == 30,
                "scheduler parks the job at the bounded wait deadline");

        require(registry.publishEvent(source, "changed", 15,
                (DeviceValue.MapValue) DeviceValue.map(Map.of("value", DeviceValue.of(7)))).isSuccess(),
                "matching event publishes through the registry");
        require(scheduler.get(job.id()).eligibleAt() == 15,
                "event publication wakes the queued job before timeout");

        int resumed = scheduler.tickSteps(15, 1, current -> {
            UUID waitId = UUID.fromString(current.continuation());
            Optional<DeviceEventWaitResult> result = bridge.poll(waitId, 15);
            require(result.isPresent() && result.get().status() == DeviceEventWaitResult.Status.EVENT,
                    "resumed job observes the matching event");
            return ServerJobScheduler.StepResult.completed(0);
        });
        require(resumed == 1 && scheduler.get(job.id()).state() == ServerJobScheduler.State.COMPLETED,
                "event-woken job completes through the normal scheduler budget");

        require(DeviceEventSchedulerBridge.step(
                        DeviceEventWaitResult.waiting(UUID.randomUUID(), subscription, 20, 0), 2, "next")
                        .disposition() == ServerJobScheduler.StepDisposition.WAIT_UNTIL,
                "pending wait maps to scheduler WAIT_UNTIL");
        require(DeviceEventSchedulerBridge.step(
                        DeviceEventWaitResult.timeout(UUID.randomUUID(), subscription, 20, 0), 2, "next")
                        .disposition() == ServerJobScheduler.StepDisposition.YIELD,
                "resolved wait returns control to the caller continuation");
        System.out.println("Device event scheduler bridge tests: OK");
    }

    private static String string(DeviceValue.MapValue map, String key) {
        return ((DeviceValue.StringValue) map.values().get(key)).value();
    }

    private static long number(DeviceValue.MapValue map, String key) {
        return (long) ((DeviceValue.NumberValue) map.values().get(key)).value();
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class EventEndpoint implements DeviceEndpoint {
        private final DeviceDescriptor descriptor;

        private EventEndpoint(UUID id) {
            descriptor = new DeviceDescriptor(id, "terminalcraft:test", "event_source",
                    "Event Source", "terminalcraft", "test:event-source", Set.of(), Map.of(),
                    List.of(new DeviceMethodDescriptor("ping", "No-op", List.of(),
                            DeviceValueType.NULL)), Set.of("changed"),
                    Set.of(DeviceCallContext.READ), true, true);
        }

        @Override public DeviceDescriptor descriptor() { return descriptor; }
        @Override public DeviceResult call(String method, List<DeviceValue> arguments) {
            return DeviceResult.success();
        }
    }
}
