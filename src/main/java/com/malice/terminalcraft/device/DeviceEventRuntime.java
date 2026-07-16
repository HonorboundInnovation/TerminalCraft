package com.malice.terminalcraft.device;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Cooperative bounded subscription runtime. All methods are deterministic and thread-safe. */
public final class DeviceEventRuntime {
    public static final int MAX_SUBSCRIPTIONS = 256;
    public static final int MAX_SUBSCRIPTIONS_PER_OWNER = 32;
    public static final int MAX_QUEUED_PER_SUBSCRIPTION = 64;
    public static final int MAX_POLL_RESULTS = 64;
    public static final int MAX_QUEUED_EVENTS = 4096;

    private final Map<UUID, State> subscriptions = new LinkedHashMap<>();
    private int queuedEvents;

    public synchronized UUID subscribe(PrincipalIdentity owner, DeviceEventSubscription specification) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(specification, "specification");
        if (subscriptions.size() >= MAX_SUBSCRIPTIONS) {
            throw new IllegalStateException("event subscription capacity exceeded");
        }
        long owned = subscriptions.values().stream().filter(state -> state.owner.equals(owner)).count();
        if (owned >= MAX_SUBSCRIPTIONS_PER_OWNER) {
            throw new IllegalStateException("owner event subscription quota exceeded");
        }
        UUID id;
        do id = UUID.randomUUID(); while (subscriptions.containsKey(id));
        subscriptions.put(id, new State(owner, specification));
        return id;
    }

    /** Ownership mismatch is concealed as absence. */
    public synchronized boolean unsubscribe(PrincipalIdentity owner, UUID subscriptionId) {
        State state = owned(owner, subscriptionId).orElse(null);
        if (state == null || !subscriptions.remove(subscriptionId, state)) return false;
        queuedEvents -= state.queue.size();
        return true;
    }

    public synchronized void publish(DeviceEvent event) {
        Objects.requireNonNull(event, "event");
        for (State state : subscriptions.values()) {
            if (!state.specification.matches(event)) continue;
            String key = event.sourceDeviceId() + "\u0000" + event.type();
            DeviceEvent previous = state.latestByKey.get(key);
            boolean insideWindow = previous != null
                    && event.gameTime() >= previous.gameTime()
                    && event.gameTime() - previous.gameTime() < state.specification.debounceTicks();
            if (insideWindow) {
                if (!state.specification.coalesce()) {
                    state.debounced++;
                    continue;
                }
                if (state.queue.remove(previous)) {
                    queuedEvents--;
                    state.coalesced++;
                }
            }
            if (queuedEvents >= MAX_QUEUED_EVENTS) {
                state.dropped++;
                state.droppedSincePoll++;
                continue;
            }
            state.queue.addLast(event);
            queuedEvents++;
            state.latestByKey.put(key, event);
            while (state.queue.size() > MAX_QUEUED_PER_SUBSCRIPTION) {
                DeviceEvent removed = state.queue.removeFirst();
                queuedEvents--;
                removeLatestIfSame(state, removed);
                state.dropped++;
                state.droppedSincePoll++;
            }
        }
    }

    public synchronized DeviceEventBatch poll(PrincipalIdentity owner, UUID subscriptionId, int limit) {
        State state = owned(owner, subscriptionId).orElse(null);
        if (state == null) return new DeviceEventBatch(List.of(), 0);
        int bounded = Math.max(0, Math.min(limit, MAX_POLL_RESULTS));
        List<DeviceEvent> result = new ArrayList<>(bounded);
        while (result.size() < bounded && !state.queue.isEmpty()) {
            DeviceEvent event = state.queue.removeFirst();
            queuedEvents--;
            removeLatestIfSame(state, event);
            result.add(event);
        }
        state.delivered += result.size();
        long dropped = state.droppedSincePoll;
        state.droppedSincePoll = 0;
        return new DeviceEventBatch(result, dropped);
    }

    public synchronized Optional<DeviceEventDiagnostics> diagnostics(PrincipalIdentity owner, UUID subscriptionId) {
        return owned(owner, subscriptionId).map(state -> new DeviceEventDiagnostics(state.queue.size(),
                state.delivered, state.dropped, state.debounced, state.coalesced));
    }

    public synchronized int subscriptionCount() { return subscriptions.size(); }

    public synchronized int queuedEventCount() { return queuedEvents; }

    private Optional<State> owned(PrincipalIdentity owner, UUID id) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(id, "subscriptionId");
        State state = subscriptions.get(id);
        return state != null && state.owner.equals(owner) ? Optional.of(state) : Optional.empty();
    }

    private static void removeLatestIfSame(State state, DeviceEvent event) {
        String key = event.sourceDeviceId() + "\u0000" + event.type();
        if (state.latestByKey.get(key) == event) state.latestByKey.remove(key);
    }

    private static final class State {
        private final PrincipalIdentity owner;
        private final DeviceEventSubscription specification;
        private final Deque<DeviceEvent> queue = new ArrayDeque<>();
        private final Map<String, DeviceEvent> latestByKey = new LinkedHashMap<>();
        private long delivered;
        private long dropped;
        private long droppedSincePoll;
        private long debounced;
        private long coalesced;

        private State(PrincipalIdentity owner, DeviceEventSubscription specification) {
            this.owner = owner;
            this.specification = specification;
        }
    }
}
