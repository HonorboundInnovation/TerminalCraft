package com.malice.terminalcraft.network;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded count and byte admission shared by every queue in one RedNet runtime scope.
 * Callers must release the exact admitted byte cost when an entry leaves its queue.
 */
public final class RednetQueueBudget {
    public static final int MAX_ENTRIES_PER_QUEUE = 64;
    public static final int MAX_BYTES_PER_QUEUE = 256 * 1024;
    public static final int MAX_ENTRIES_PER_SCOPE = 4_096;
    public static final int MAX_BYTES_PER_SCOPE = 8 * 1024 * 1024;
    public static final int MAX_TRACKED_QUEUES = 2_048;

    private final Map<Object, Usage> queues = new LinkedHashMap<>();
    private int scopeEntries;
    private int scopeBytes;

    /** Admits one entry, or returns false without consuming budget. */
    public synchronized boolean admit(Object queueId, int bytes) {
        if (queueId == null || bytes < 0 || bytes > MAX_BYTES_PER_QUEUE) return false;
        Usage current = queues.getOrDefault(queueId, Usage.EMPTY);
        if (current.entries() >= MAX_ENTRIES_PER_QUEUE
                || bytes > MAX_BYTES_PER_QUEUE - current.bytes()
                || scopeEntries >= MAX_ENTRIES_PER_SCOPE
                || bytes > MAX_BYTES_PER_SCOPE - scopeBytes) return false;
        if (!queues.containsKey(queueId) && queues.size() >= MAX_TRACKED_QUEUES) return false;
        queues.put(queueId, new Usage(current.entries() + 1, current.bytes() + bytes));
        scopeEntries++;
        scopeBytes += bytes;
        return true;
    }

    /** Releases one previously admitted entry. Invalid releases fail loudly in tests and production. */
    public synchronized void release(Object queueId, int bytes) {
        Objects.requireNonNull(queueId, "queueId");
        Usage current = queues.get(queueId);
        if (current == null || bytes < 0 || current.entries() == 0 || bytes > current.bytes()) {
            throw new IllegalStateException("RedNet queue budget release does not match admission");
        }
        int entries = current.entries() - 1;
        int remainingBytes = current.bytes() - bytes;
        if (entries == 0 && remainingBytes != 0) {
            throw new IllegalStateException("RedNet terminal queue release leaves byte usage");
        }
        scopeEntries--;
        scopeBytes -= bytes;
        if (entries == 0) queues.remove(queueId);
        else queues.put(queueId, new Usage(entries, remainingBytes));
    }

    public synchronized Usage usage(Object queueId) {
        return queues.getOrDefault(queueId, Usage.EMPTY);
    }

    public synchronized Usage scopeUsage() {
        return new Usage(scopeEntries, scopeBytes);
    }

    public synchronized int trackedQueues() {
        return queues.size();
    }

    public synchronized void clear() {
        queues.clear();
        scopeEntries = 0;
        scopeBytes = 0;
    }

    public record Usage(int entries, int bytes) {
        private static final Usage EMPTY = new Usage(0, 0);
    }
}
