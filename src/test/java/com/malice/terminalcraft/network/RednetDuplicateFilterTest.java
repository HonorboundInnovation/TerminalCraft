package com.malice.terminalcraft.network;

import java.util.UUID;

/** Deterministic recipient isolation, expiry, capacity, and overflow coverage. */
public final class RednetDuplicateFilterTest {
    private RednetDuplicateFilterTest() {}

    public static void main(String[] args) {
        RednetDuplicateFilter filter = new RednetDuplicateFilter();
        UUID recipient = UUID.randomUUID();
        UUID otherRecipient = UUID.randomUUID();
        UUID message = UUID.randomUUID();

        check(filter.admit(recipient, message, 10, 5), "first delivery must be admitted");
        check(!filter.admit(recipient, message, 14, 5), "duplicate must be suppressed before expiry");
        check(filter.admit(otherRecipient, message, 14, 5),
                "the same message ID must remain isolated by recipient");
        check(filter.admit(recipient, message, 15, 5), "expired message ID must be admitted again");

        for (int i = 0; i < RednetDuplicateFilter.MAX_IDS_PER_RECIPIENT + 8; i++) {
            check(filter.admit(recipient, new UUID(0, i + 1L), 20, 100),
                    "distinct message IDs must be admitted");
        }
        check(filter.retainedCount(recipient, 20) == RednetDuplicateFilter.MAX_IDS_PER_RECIPIENT,
                "per-recipient retention must remain bounded");

        RednetDuplicateFilter recipients = new RednetDuplicateFilter();
        for (int i = 0; i < RednetDuplicateFilter.MAX_RECIPIENTS + 4; i++) {
            recipients.admit(new UUID(1, i + 1L), UUID.randomUUID(), 0, 100);
        }
        check(recipients.recipientCount() == RednetDuplicateFilter.MAX_RECIPIENTS,
                "recipient retention must remain bounded");

        UUID overflow = UUID.randomUUID();
        check(filter.admit(overflow, UUID.randomUUID(), Long.MAX_VALUE - 1, 10),
                "overflow-safe retention must accept a valid delivery");
        check(filter.retainedCount(overflow, Long.MAX_VALUE - 1) == 1,
                "saturated expiry must remain retained");

        expectFailure(() -> filter.admit(recipient, UUID.randomUUID(), -1, 1));
        expectFailure(() -> filter.admit(recipient, UUID.randomUUID(), 0, 0));
        expectFailure(() -> filter.admit(recipient, UUID.randomUUID(), 0,
                RednetDuplicateFilter.MAX_RETENTION_TICKS + 1));

        filter.removeRecipient(recipient);
        check(filter.retainedCount(recipient, 20) == 0, "recipient removal must discard its IDs");
        filter.clear();
        check(filter.recipientCount() == 0, "clear must discard all transient state");
        System.out.println("RednetDuplicateFilterTest: all tests passed");
    }

    private static void expectFailure(Runnable action) {
        try { action.run(); throw new AssertionError("expected validation failure"); }
        catch (IllegalArgumentException expected) { /* Expected. */ }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
