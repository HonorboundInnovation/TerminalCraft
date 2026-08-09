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
import java.util.function.LongConsumer;

/** Cooperative bounded subscription runtime. All methods are deterministic and thread-safe. */
public final class DeviceEventRuntime {
    public static final int MAX_SUBSCRIPTIONS = 256;
    public static final int MAX_SUBSCRIPTIONS_PER_OWNER = 32;
    public static final int MAX_QUEUED_PER_SUBSCRIPTION = 64;
    public static final int MAX_POLL_RESULTS = 64;
    public static final int MAX_QUEUED_EVENTS = 4096;
    public static final int MAX_WAITS = 256;
    public static final int MAX_WAITS_PER_OWNER = 32;
    public static final long MAX_WAIT_TICKS = 20L * 60L;

    private final Map<UUID, State> subscriptions = new LinkedHashMap<>();
    private final Map<UUID, WaitState> waits = new LinkedHashMap<>();
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
        subscriptions.put(id, new State(id, owner, specification));
        return id;
    }

    /** Ownership mismatch is concealed as absence. */
    public synchronized boolean unsubscribe(PrincipalIdentity owner, UUID subscriptionId) {
        State state = owned(owner, subscriptionId).orElse(null);
        if (state == null || !subscriptions.remove(subscriptionId, state)) return false;
        queuedEvents -= state.queue.size();
        waits.entrySet().removeIf(entry -> entry.getValue().subscriptionId.equals(subscriptionId));
        return true;
    }

    public synchronized void publish(DeviceEvent event) {
        Objects.requireNonNull(event, "event");
        List<Wakeup> wakeups = new ArrayList<>();
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

            WaitState wait = waits.values().stream()
                    .filter(candidate -> candidate.subscriptionId.equals(state.id))
                    .findFirst().orElse(null);
            if (wait != null && wait.event != null && previous == wait.event
                    && state.specification.coalesce()) {
                wait.event = event;
                state.latestByKey.put(key, event);
                state.coalesced++;
                continue;
            }
            if (wait != null && wait.event == null) {
                wait.event = event;
                state.latestByKey.put(key, event);
                if (wait.wakeup != null) wakeups.add(new Wakeup(wait.wakeup, event.gameTime()));
                continue;
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
        for (Wakeup wakeup : wakeups) {
            try {
                wakeup.callback.accept(wakeup.gameTime);
            } catch (RuntimeException ignored) {
                // A wake callback is advisory; the token remains available for polling.
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

    public synchronized int waitCount() { return waits.size(); }

    /** Starts a bounded wait. This method never blocks and may complete immediately. */
    public synchronized Optional<DeviceEventWaitResult> beginWait(PrincipalIdentity owner,
                                                                    UUID subscriptionId,
                                                                    long gameTime,
                                                                    long timeoutTicks) {
        return beginWait(owner, subscriptionId, gameTime, timeoutTicks, null);
    }

    /** Starts a wait and invokes the optional scheduler callback when an event completes it. */
    public synchronized Optional<DeviceEventWaitResult> beginWait(PrincipalIdentity owner,
                                                                    UUID subscriptionId,
                                                                    long gameTime,
                                                                    long timeoutTicks,
                                                                    LongConsumer wakeup) {
        State state = owned(owner, subscriptionId).orElse(null);
        if (state == null) return Optional.empty();
        validateWaitClock(gameTime, timeoutTicks);
        UUID waitId = newWaitId();
        if (!state.queue.isEmpty()) {
            DeviceEvent event = state.queue.removeFirst();
            queuedEvents--;
            removeLatestIfSame(state, event);
            state.delivered++;
            return Optional.of(DeviceEventWaitResult.event(waitId, subscriptionId,
                    gameTime, event, takeDropped(state)));
        }
        if (timeoutTicks == 0) {
            return Optional.of(DeviceEventWaitResult.timeout(waitId, subscriptionId,
                    gameTime, takeDropped(state)));
        }
        if (waits.size() >= MAX_WAITS) {
            throw new IllegalStateException("event wait capacity exceeded");
        }
        long owned = waits.values().stream().filter(wait -> wait.owner.equals(owner)).count();
        if (owned >= MAX_WAITS_PER_OWNER) {
            throw new IllegalStateException("owner event wait quota exceeded");
        }
        if (waits.values().stream().anyMatch(wait -> wait.subscriptionId.equals(subscriptionId))) {
            throw new IllegalStateException("subscription already has an active event wait");
        }
        long wakeAt = Math.addExact(gameTime, timeoutTicks);
        waits.put(waitId, new WaitState(waitId, owner, subscriptionId, wakeAt, wakeup));
        return Optional.of(DeviceEventWaitResult.waiting(waitId, subscriptionId, wakeAt, takeDropped(state)));
    }

    /** Resolves a wait only when polled by its owner; timeout is logical game time, not wall time. */
    public synchronized Optional<DeviceEventWaitResult> pollWait(PrincipalIdentity owner,
                                                                  UUID waitId,
                                                                  long gameTime) {
        WaitState wait = ownedWait(owner, waitId).orElse(null);
        if (wait == null) return Optional.empty();
        validateGameTime(gameTime);
        State subscription = subscriptions.get(wait.subscriptionId);
        if (subscription == null || !subscription.owner.equals(owner)) {
            waits.remove(waitId);
            return Optional.empty();
        }
        if (wait.event != null) {
            waits.remove(waitId);
            subscription.delivered++;
            removeLatestIfSame(subscription, wait.event);
            return Optional.of(DeviceEventWaitResult.event(wait.id, wait.subscriptionId,
                    wait.wakeAt, wait.event, takeDropped(subscription)));
        }
        if (gameTime >= wait.wakeAt) {
            waits.remove(waitId);
            return Optional.of(DeviceEventWaitResult.timeout(wait.id, wait.subscriptionId,
                    wait.wakeAt, takeDropped(subscription)));
        }
        return Optional.of(DeviceEventWaitResult.waiting(wait.id, wait.subscriptionId,
                wait.wakeAt, takeDropped(subscription)));
    }

    public synchronized boolean cancelWait(PrincipalIdentity owner, UUID waitId) {
        WaitState wait = ownedWait(owner, waitId).orElse(null);
        return wait != null && waits.remove(waitId, wait);
    }

    private Optional<State> owned(PrincipalIdentity owner, UUID id) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(id, "subscriptionId");
        State state = subscriptions.get(id);
        return state != null && state.owner.equals(owner) ? Optional.of(state) : Optional.empty();
    }

    private Optional<WaitState> ownedWait(PrincipalIdentity owner, UUID id) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(id, "waitId");
        WaitState wait = waits.get(id);
        return wait != null && wait.owner.equals(owner) ? Optional.of(wait) : Optional.empty();
    }

    private UUID newWaitId() {
        UUID id;
        do id = UUID.randomUUID(); while (waits.containsKey(id));
        return id;
    }

    private static void validateWaitClock(long gameTime, long timeoutTicks) {
        validateGameTime(gameTime);
        if (timeoutTicks < 0 || timeoutTicks > MAX_WAIT_TICKS
                || gameTime > Long.MAX_VALUE - timeoutTicks) {
            throw new IllegalArgumentException("event wait timeout must be from 0 to "
                    + MAX_WAIT_TICKS + " ticks");
        }
    }

    private static void validateGameTime(long gameTime) {
        if (gameTime < 0) throw new IllegalArgumentException("event wait game time must not be negative");
    }

    private static long takeDropped(State state) {
        long dropped = state.droppedSincePoll;
        state.droppedSincePoll = 0;
        return dropped;
    }

    private static void removeLatestIfSame(State state, DeviceEvent event) {
        String key = event.sourceDeviceId() + "\u0000" + event.type();
        if (state.latestByKey.get(key) == event) state.latestByKey.remove(key);
    }

    private static final class State {
        private final UUID id;
        private final PrincipalIdentity owner;
        private final DeviceEventSubscription specification;
        private final Deque<DeviceEvent> queue = new ArrayDeque<>();
        private final Map<String, DeviceEvent> latestByKey = new LinkedHashMap<>();
        private long delivered;
        private long dropped;
        private long droppedSincePoll;
        private long debounced;
        private long coalesced;

        private State(UUID id, PrincipalIdentity owner, DeviceEventSubscription specification) {
            this.id = id;
            this.owner = owner;
            this.specification = specification;
        }
    }

    private static final class WaitState {
        private final UUID id;
        private final PrincipalIdentity owner;
        private final UUID subscriptionId;
        private final long wakeAt;
        private final LongConsumer wakeup;
        private DeviceEvent event;

        private WaitState(UUID id, PrincipalIdentity owner, UUID subscriptionId,
                          long wakeAt, LongConsumer wakeup) {
            this.id = id;
            this.owner = owner;
            this.subscriptionId = subscriptionId;
            this.wakeAt = wakeAt;
            this.wakeup = wakeup;
        }
    }

    private record Wakeup(LongConsumer callback, long gameTime) {}
}
