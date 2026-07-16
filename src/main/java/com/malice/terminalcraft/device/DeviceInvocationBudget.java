package com.malice.terminalcraft.device;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Logical-tick admission budget for caller-bound device operations.
 *
 * <p>The owner supplies a stable authority key (normally dimension, host position, and
 * principal UUID). Buckets are discarded on tick change, so this limiter cannot retain an
 * unbounded history of callers.</p>
 */
final class DeviceInvocationBudget {
    static final int MAX_CALLS_PER_TICK = 16;
    static final int MAX_WORK_UNITS_PER_TICK = 512;
    static final int MAX_BUCKETS_PER_TICK = 1024;

    private long currentTick = Long.MIN_VALUE;
    private final Map<String, Usage> usage = new LinkedHashMap<>();

    synchronized Admission admit(String authorityKey, long tick, int workUnits) {
        Objects.requireNonNull(authorityKey, "authorityKey");
        if (authorityKey.isBlank()) throw new IllegalArgumentException("authority key is required");
        if (workUnits < 1 || workUnits > MAX_WORK_UNITS_PER_TICK) {
            throw new IllegalArgumentException("work units must be from 1 to " + MAX_WORK_UNITS_PER_TICK);
        }
        if (tick != currentTick) {
            currentTick = tick;
            usage.clear();
        }

        Usage previous = usage.get(authorityKey);
        if (previous == null) {
            if (usage.size() >= MAX_BUCKETS_PER_TICK) return Admission.BUCKET_LIMIT;
            previous = new Usage(0, 0);
        }
        if (previous.calls() >= MAX_CALLS_PER_TICK) return Admission.CALL_LIMIT;
        if (previous.workUnits() > MAX_WORK_UNITS_PER_TICK - workUnits) return Admission.WORK_LIMIT;
        usage.put(authorityKey, new Usage(previous.calls() + 1, previous.workUnits() + workUnits));
        return Admission.ADMITTED;
    }

    enum Admission { ADMITTED, CALL_LIMIT, WORK_LIMIT, BUCKET_LIMIT }

    private record Usage(int calls, int workUnits) {}
}
