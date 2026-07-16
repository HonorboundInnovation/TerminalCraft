package com.malice.terminalcraft.network;

import java.util.UUID;

/** Deterministic logical-tick message, UTF-8 byte, sender isolation, and capacity coverage. */
public final class RednetTrafficQuotaTest {
    private RednetTrafficQuotaTest() {}

    public static void main(String[] args) {
        RednetTrafficQuota quota = new RednetTrafficQuota();
        UUID sender = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        for (int i = 0; i < RednetTrafficQuota.MAX_MESSAGES_PER_TICK; i++) {
            check(quota.admit(sender, "x", 10), "message within tick quota must be admitted");
        }
        check(!quota.admit(sender, "x", 10), "message count quota must reject excess traffic");
        check(quota.admit(other, "x", 10), "sender quotas must be isolated");
        check(quota.admit(sender, "x", 11), "a new logical tick must reset sender usage");

        RednetTrafficQuota bytes = new RednetTrafficQuota();
        String multibyte = "€".repeat(1_365); // 4,095 UTF-8 bytes, fewer Java chars.
        for (int i = 0; i < 8; i++) {
            check(bytes.admit(sender, multibyte, 20), "UTF-8 payload within byte quota must pass");
        }
        check(!bytes.admit(sender, multibyte, 20),
                "UTF-8 byte quota must reject traffic even when character counts are smaller");
        check(!bytes.admit(sender, "€".repeat(1_366), 21),
                "payloads beyond the envelope UTF-8 budget must fail closed");
        check(!bytes.admit(null, "x", 0) && !bytes.admit(sender, null, 0)
                        && !bytes.admit(sender, "x", -1),
                "malformed admission input must fail closed without throwing");

        RednetTrafficQuota bounded = new RednetTrafficQuota();
        for (int i = 0; i < RednetTrafficQuota.MAX_TRACKED_SENDERS + 8; i++) {
            check(bounded.admit(new UUID(0, i + 1L), "x", 0), "distinct sender must be admitted");
        }
        check(bounded.trackedSenders() == RednetTrafficQuota.MAX_TRACKED_SENDERS,
                "tracked sender state must remain bounded");
        bounded.remove(other);
        bounded.clear();
        check(bounded.trackedSenders() == 0, "clear must discard transient quota state");

        System.out.println("RednetTrafficQuotaTest: all tests passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
