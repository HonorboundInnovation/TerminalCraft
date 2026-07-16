package com.malice.terminalcraft.network;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Bounded logical-time delivery state for acknowledged RedNet messages.
 *
 * <p>The runtime provides at-least-once attempts, not exactly-once execution. A receiver must
 * suppress duplicate message IDs before applying a non-idempotent payload. All time is logical
 * server game time and callbacks are invoked outside the runtime monitor.</p>
 */
public final class RednetDeliveryRuntime {
    public static final String ACK_PROTOCOL = "terminalcraft:rednet-ack";
    public static final String ACK_PAYLOAD = "accepted";
    public static final int MAX_ACTIVE = 256;
    public static final int MAX_RETAINED = 256;
    public static final int MAX_RETRIES = 3;
    public static final long MIN_TIMEOUT_TICKS = 1;
    public static final long MAX_TIMEOUT_TICKS = 1_200;
    public static final int MAX_TICK_ATTEMPTS = 64;

    private final Map<UUID, Delivery> deliveries = new LinkedHashMap<>();

    public Delivery submit(NetworkEnvelope envelope, long now, long timeoutTicks, int maxRetries,
                           Attempt attempt) {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(attempt, "attempt");
        validate(now, timeoutTicks, maxRetries);
        if (envelope.correlationId() != null) {
            throw new IllegalArgumentException("new delivery must not already be correlated");
        }
        Delivery pending;
        synchronized (this) {
            Delivery existing = deliveries.get(envelope.messageId());
            if (existing != null) return existing;
            long active = deliveries.values().stream().filter(value -> !value.terminal()).count();
            if (active >= MAX_ACTIVE) throw new IllegalStateException("delivery capacity exhausted");
            pending = new Delivery(envelope.messageId(), envelope, State.PENDING, 0, maxRetries,
                    timeoutTicks, now, now, "");
            deliveries.put(envelope.messageId(), pending);
            trimTerminal();
        }
        return runAttempt(pending.messageId(), now, attempt);
    }

    /** Runs due retries, bounded per logical tick. */
    public int tick(long now, Attempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        if (now < 0) throw new IllegalArgumentException("logical time must not be negative");
        List<UUID> due = new ArrayList<>();
        synchronized (this) {
            for (Delivery delivery : deliveries.values()) {
                if (delivery.state() == State.ACCEPTED && now >= delivery.deadline()) {
                    if (delivery.attempts() > delivery.maxRetries()) {
                        deliveries.put(delivery.messageId(), delivery.withState(
                                State.TIMED_OUT, now, "acknowledgement timeout"));
                    } else if (due.size() < MAX_TICK_ATTEMPTS) {
                        due.add(delivery.messageId());
                    }
                }
            }
        }
        for (UUID id : due) runAttempt(id, now, attempt);
        return due.size();
    }

    /** Accepts only an acknowledgement whose correlation ID names an active delivery. */
    public synchronized boolean acknowledge(NetworkEnvelope acknowledgement, long now) {
        if (acknowledgement == null || acknowledgement.correlationId() == null || now < 0) return false;
        Delivery delivery = deliveries.get(acknowledgement.correlationId());
        if (delivery == null || delivery.terminal() || delivery.state() != State.ACCEPTED) return false;
        NetworkEnvelope request = delivery.envelope();
        if (!ACK_PROTOCOL.equals(acknowledgement.protocol())
                || !NetworkEnvelope.TEXT_PAYLOAD.equals(acknowledgement.payloadType())
                || !ACK_PAYLOAD.equals(acknowledgement.payload())
                || request.replyPort() != acknowledgement.port()
                || request.port() != acknowledgement.replyPort()
                || !request.destination().equals(acknowledgement.source())
                || !request.source().equals(acknowledgement.destination())) return false;
        deliveries.put(delivery.messageId(), delivery.withState(State.ACKNOWLEDGED, now, ""));
        trimTerminal();
        return true;
    }

    public synchronized Optional<Delivery> delivery(UUID messageId) {
        return Optional.ofNullable(deliveries.get(messageId));
    }

    public synchronized List<Delivery> deliveries(int maximum) {
        int limit = Math.max(0, Math.min(maximum, MAX_RETAINED));
        return deliveries.values().stream().limit(limit).toList();
    }

    private Delivery runAttempt(UUID id, long now, Attempt callback) {
        NetworkEnvelope envelope;
        Delivery claimed;
        synchronized (this) {
            Delivery current = deliveries.get(id);
            if (current == null || current.terminal()) return current;
            claimed = new Delivery(current.messageId(), current.envelope(), State.ATTEMPTING,
                    current.attempts() + 1, current.maxRetries(), current.timeoutTicks(),
                    current.submittedAt(), current.deadline(), "");
            deliveries.put(id, claimed);
            envelope = claimed.envelope();
        }
        boolean accepted;
        try {
            accepted = callback.accept(envelope);
        } catch (RuntimeException failure) {
            accepted = false;
        }
        synchronized (this) {
            Delivery current = deliveries.get(id);
            if (current == null || current.state() != State.ATTEMPTING
                    || current.attempts() != claimed.attempts()) return current;
            State state = accepted ? State.ACCEPTED
                    : (current.attempts() > current.maxRetries() ? State.REJECTED : State.ACCEPTED);
            String error = accepted ? "" : (state == State.REJECTED
                    ? "delivery rejected" : "delivery attempt rejected; retry pending");
            Delivery result = new Delivery(current.messageId(), current.envelope(), state,
                    current.attempts(), current.maxRetries(), current.timeoutTicks(),
                    current.submittedAt(), saturatedAdd(now, current.timeoutTicks()), error);
            deliveries.put(id, result);
            trimTerminal();
            return result;
        }
    }

    private static void validate(long now, long timeoutTicks, int maxRetries) {
        if (now < 0) throw new IllegalArgumentException("logical time must not be negative");
        if (timeoutTicks < MIN_TIMEOUT_TICKS || timeoutTicks > MAX_TIMEOUT_TICKS) {
            throw new IllegalArgumentException("timeout outside supported range");
        }
        if (maxRetries < 0 || maxRetries > MAX_RETRIES) {
            throw new IllegalArgumentException("retry count outside supported range");
        }
    }

    private void trimTerminal() {
        while (deliveries.size() > MAX_RETAINED) {
            UUID removable = deliveries.values().stream().filter(Delivery::terminal)
                    .map(Delivery::messageId).findFirst().orElse(null);
            if (removable == null) return;
            deliveries.remove(removable);
        }
    }

    private static long saturatedAdd(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    @FunctionalInterface
    public interface Attempt { boolean accept(NetworkEnvelope envelope); }

    public enum State { PENDING, ATTEMPTING, ACCEPTED, ACKNOWLEDGED, REJECTED, TIMED_OUT }

    public record Delivery(UUID messageId, NetworkEnvelope envelope, State state, int attempts,
                           int maxRetries, long timeoutTicks, long submittedAt, long deadline,
                           String lastError) {
        public Delivery {
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(envelope, "envelope");
            Objects.requireNonNull(state, "state");
            lastError = lastError == null ? "" : lastError;
        }
        public boolean terminal() {
            return state == State.ACKNOWLEDGED || state == State.REJECTED || state == State.TIMED_OUT;
        }
        private Delivery withState(State replacement, long time, String error) {
            return new Delivery(messageId, envelope, replacement, attempts, maxRetries, timeoutTicks,
                    submittedAt, time, error);
        }
    }
}
