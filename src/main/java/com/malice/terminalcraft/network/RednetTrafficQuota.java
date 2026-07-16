package com.malice.terminalcraft.network;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Logical-tick sender admission for RedNet traffic.
 *
 * <p>One admission represents one submitted envelope, independent of broadcast fan-out. State is
 * transient, server-scope owned by the caller, and bounded by deterministic least-recently-used
 * sender eviction.</p>
 */
public final class RednetTrafficQuota {
    public static final int MAX_MESSAGES_PER_TICK = 32;
    public static final int MAX_BYTES_PER_TICK = 32 * 1024;
    public static final int MAX_TRACKED_SENDERS = 1024;

    private final LinkedHashMap<UUID, Usage> usage = new LinkedHashMap<>(16, 0.75f, true);

    /** Returns false without consuming quota when the request is malformed or over budget. */
    public synchronized boolean admit(UUID senderId, String payload, long gameTime) {
        if (senderId == null || payload == null || gameTime < 0) return false;
        int bytes = payload.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > NetworkEnvelope.MAX_PAYLOAD_LENGTH) return false;

        Usage current = usage.get(senderId);
        if (current == null || current.gameTime() != gameTime) current = new Usage(gameTime, 0, 0);
        if (current.messages() >= MAX_MESSAGES_PER_TICK
                || bytes > MAX_BYTES_PER_TICK - current.bytes()) return false;

        if (!usage.containsKey(senderId) && usage.size() >= MAX_TRACKED_SENDERS) {
            UUID eldest = usage.keySet().iterator().next();
            usage.remove(eldest);
        }
        usage.put(senderId, new Usage(gameTime, current.messages() + 1, current.bytes() + bytes));
        return true;
    }

    public synchronized Usage usage(UUID senderId, long gameTime) {
        Objects.requireNonNull(senderId, "senderId");
        Usage current = usage.get(senderId);
        return current == null || current.gameTime() != gameTime
                ? new Usage(gameTime, 0, 0) : current;
    }

    public synchronized int trackedSenders() {
        return usage.size();
    }

    public synchronized void remove(UUID senderId) {
        if (senderId != null) usage.remove(senderId);
    }

    public synchronized void clear() {
        usage.clear();
    }

    public record Usage(long gameTime, int messages, int bytes) {}
}
