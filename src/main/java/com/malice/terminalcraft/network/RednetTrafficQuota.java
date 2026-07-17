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
 * transient and server-scope owned by the caller. Per-sender limits are backed by a scope-wide
 * logical-tick ceiling so rotating sender identities cannot bypass congestion control.</p>
 */
public final class RednetTrafficQuota {
    public static final int MAX_MESSAGES_PER_TICK = 32;
    public static final int MAX_BYTES_PER_TICK = 32 * 1024;
    public static final int MAX_TRACKED_SENDERS = 1024;
    public static final int MAX_MESSAGES_PER_SCOPE_PER_TICK = 1024;
    public static final int MAX_BYTES_PER_SCOPE_PER_TICK = 1024 * 1024;

    private final LinkedHashMap<UUID, Usage> usage = new LinkedHashMap<>();
    private long scopeGameTime = -1;
    private int scopeMessages;
    private int scopeBytes;

    /** Returns false without consuming quota when the request is malformed or over budget. */
    public synchronized boolean admit(UUID senderId, String payload, long gameTime) {
        if (senderId == null || payload == null || gameTime < 0
                || payload.length() > NetworkEnvelope.MAX_PAYLOAD_LENGTH) return false;
        if (scopeGameTime > gameTime) return false;
        if (scopeGameTime != gameTime) {
            usage.clear();
            scopeGameTime = gameTime;
            scopeMessages = 0;
            scopeBytes = 0;
        }
        int bytes = payload.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > NetworkEnvelope.MAX_PAYLOAD_LENGTH) return false;

        Usage current = usage.getOrDefault(senderId, new Usage(gameTime, 0, 0));
        if (current.messages() >= MAX_MESSAGES_PER_TICK
                || bytes > MAX_BYTES_PER_TICK - current.bytes()
                || scopeMessages >= MAX_MESSAGES_PER_SCOPE_PER_TICK
                || bytes > MAX_BYTES_PER_SCOPE_PER_TICK - scopeBytes
                || !usage.containsKey(senderId) && usage.size() >= MAX_TRACKED_SENDERS) return false;

        usage.put(senderId, new Usage(gameTime, current.messages() + 1, current.bytes() + bytes));
        scopeMessages++;
        scopeBytes += bytes;
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
        scopeGameTime = -1;
        scopeMessages = 0;
        scopeBytes = 0;
    }

    public record Usage(long gameTime, int messages, int bytes) {}
}
