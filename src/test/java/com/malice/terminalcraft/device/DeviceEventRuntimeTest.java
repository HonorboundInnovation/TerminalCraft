package com.malice.terminalcraft.device;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Deterministic quota, ownership, filtering, debounce, coalescing, and overflow coverage. */
public final class DeviceEventRuntimeTest {
    private DeviceEventRuntimeTest() {}

    public static void main(String[] args) {
        PrincipalIdentity alice = PrincipalIdentity.player(UUID.randomUUID(), "alice");
        PrincipalIdentity sameUuidService = PrincipalIdentity.service(alice.id(), "alice-service");
        UUID source = UUID.randomUUID();
        DeviceEventRuntime runtime = new DeviceEventRuntime();
        UUID filtered = runtime.subscribe(alice,
                new DeviceEventSubscription(source, Set.of("changed"), 5, false));
        runtime.publish(event(1, source, "other", 10));
        runtime.publish(event(2, UUID.randomUUID(), "changed", 10));
        runtime.publish(event(3, source, "changed", 10));
        runtime.publish(event(4, source, "changed", 12));
        require(runtime.poll(alice, filtered, 64).events().size() == 1,
                "source/type filters and debounce are enforced");
        require(runtime.diagnostics(alice, filtered).orElseThrow().debounced() == 1,
                "debounced event is observable");
        require(runtime.diagnostics(sameUuidService, filtered).isEmpty(),
                "same UUID with another principal kind cannot inspect subscription");
        require(!runtime.unsubscribe(sameUuidService, filtered),
                "same UUID with another principal kind cannot remove subscription");

        UUID coalesced = runtime.subscribe(alice,
                new DeviceEventSubscription(source, Set.of("changed"), 20, true));
        runtime.publish(event(5, source, "changed", 30));
        runtime.publish(event(6, source, "changed", 31));
        DeviceEventBatch latest = runtime.poll(alice, coalesced, 64);
        require(latest.events().size() == 1 && latest.events().get(0).sequence() == 6,
                "coalescing retains the latest event");
        require(runtime.diagnostics(alice, coalesced).orElseThrow().coalesced() == 1,
                "coalescing is observable");

        UUID overflow = runtime.subscribe(alice,
                new DeviceEventSubscription(null, Set.of(), 0, false));
        for (int i = 0; i < DeviceEventRuntime.MAX_QUEUED_PER_SUBSCRIPTION + 3; i++) {
            runtime.publish(event(100 + i, source, "changed", 100 + i));
        }
        DeviceEventBatch bounded = runtime.poll(alice, overflow, Integer.MAX_VALUE);
        require(bounded.events().size() == DeviceEventRuntime.MAX_POLL_RESULTS,
                "poll result is bounded");
        require(bounded.dropped() == 3, "queue overflow is reported once");
        require(runtime.poll(alice, overflow, 1).dropped() == 0,
                "drop delta is consumed independently from cumulative diagnostics");
        require(runtime.diagnostics(alice, overflow).orElseThrow().dropped() == 3,
                "cumulative drops remain observable");

        DeviceEventRuntime quota = new DeviceEventRuntime();
        for (int i = 0; i < DeviceEventRuntime.MAX_SUBSCRIPTIONS_PER_OWNER; i++) {
            quota.subscribe(alice, new DeviceEventSubscription(null, Set.of(), 0, false));
        }
        assertThrows(() -> quota.subscribe(alice,
                new DeviceEventSubscription(null, Set.of(), 0, false)),
                "per-owner subscription quota");
        assertThrows(() -> new DeviceEventSubscription(null, Set.of(),
                DeviceEventSubscription.MAX_DEBOUNCE_TICKS + 1, false),
                "debounce bound");
        require(runtime.unsubscribe(alice, filtered), "owner can unsubscribe");

        DeviceEventRuntime aggregate = new DeviceEventRuntime();
        java.util.List<java.util.Map.Entry<PrincipalIdentity, UUID>> aggregateSubscriptions =
                new java.util.ArrayList<>();
        for (int i = 0; i < 65; i++) {
            PrincipalIdentity owner = PrincipalIdentity.service(UUID.randomUUID(), "aggregate-" + i);
            UUID subscription = aggregate.subscribe(owner,
                    new DeviceEventSubscription(null, Set.of(), 0, false));
            aggregateSubscriptions.add(java.util.Map.entry(owner, subscription));
        }
        for (int i = 0; i < DeviceEventRuntime.MAX_QUEUED_PER_SUBSCRIPTION; i++) {
            aggregate.publish(event(1000 + i, source, "changed", 1000 + i));
        }
        require(aggregate.queuedEventCount() == DeviceEventRuntime.MAX_QUEUED_EVENTS,
                "aggregate queue accounting enforces the runtime-wide bound");
        long aggregateDrops = aggregateSubscriptions.stream()
                .mapToLong(entry -> aggregate.diagnostics(entry.getKey(), entry.getValue())
                        .orElseThrow().dropped()).sum();
        require(aggregateDrops == 64,
                "events rejected by the aggregate queue bound are observable as drops");
        java.util.Map.Entry<PrincipalIdentity, UUID> first = aggregateSubscriptions.get(0);
        int drained = aggregate.poll(first.getKey(), first.getValue(), 10).events().size();
        require(aggregate.queuedEventCount() == DeviceEventRuntime.MAX_QUEUED_EVENTS - drained,
                "polling releases aggregate queue capacity");
        require(aggregate.unsubscribe(first.getKey(), first.getValue()),
                "aggregate test subscription can be removed");
        require(aggregate.queuedEventCount() == DeviceEventRuntime.MAX_QUEUED_EVENTS - 64,
                "unsubscribe releases all remaining aggregate queue capacity");

        DeviceEventRuntime restarted = new DeviceEventRuntime();
        require(restarted.subscriptionCount() == 0 && restarted.queuedEventCount() == 0,
                "subscriptions and queued events are intentionally in-memory across restart");
        require(restarted.poll(alice, coalesced, 64).events().isEmpty(),
                "a new runtime cannot disclose pre-restart queued events");
        System.out.println("Device event runtime tests: OK");
    }

    private static DeviceEvent event(long sequence, UUID source, String type, long tick) {
        return new DeviceEvent(sequence, source, type, tick,
                new DeviceValue.MapValue(Map.of("sequence", DeviceValue.of(sequence))));
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void assertThrows(Runnable action, String message) {
        try { action.run(); } catch (RuntimeException expected) { return; }
        throw new AssertionError(message + ": expected exception");
    }
}
