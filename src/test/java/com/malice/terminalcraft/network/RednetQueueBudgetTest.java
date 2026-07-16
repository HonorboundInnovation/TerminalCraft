package com.malice.terminalcraft.network;

/** Deterministic per-queue and scope-wide count/byte admission coverage. */
public final class RednetQueueBudgetTest {
    private RednetQueueBudgetTest() {}

    public static void main(String[] args) {
        RednetQueueBudget budget = new RednetQueueBudget();
        Object application = "application";
        for (int i = 0; i < RednetQueueBudget.MAX_ENTRIES_PER_QUEUE; i++) {
            check(budget.admit(application, 1), "entry within queue count budget must pass");
        }
        check(!budget.admit(application, 1), "queue count overflow must fail");
        budget.release(application, 1);
        check(budget.admit(application, 1), "released count and bytes must be reusable");

        RednetQueueBudget bytes = new RednetQueueBudget();
        check(bytes.admit(application, RednetQueueBudget.MAX_BYTES_PER_QUEUE),
                "exact queue byte budget must pass");
        check(!bytes.admit(application, 1), "queue byte overflow must fail");
        bytes.release(application, RednetQueueBudget.MAX_BYTES_PER_QUEUE);
        check(bytes.scopeUsage().equals(new RednetQueueBudget.Usage(0, 0)),
                "release must restore aggregate usage");

        RednetQueueBudget scope = new RednetQueueBudget();
        for (int i = 0; i < RednetQueueBudget.MAX_ENTRIES_PER_SCOPE; i++) {
            check(scope.admit("q-" + (i / RednetQueueBudget.MAX_ENTRIES_PER_QUEUE), 0),
                    "entry within scope budget must pass");
        }
        check(!scope.admit("overflow", 0), "scope entry overflow must fail");
        check(scope.trackedQueues() == RednetQueueBudget.MAX_ENTRIES_PER_SCOPE
                        / RednetQueueBudget.MAX_ENTRIES_PER_QUEUE,
                "queue tracking must reflect admitted queues");

        RednetQueueBudget malformed = new RednetQueueBudget();
        check(!malformed.admit(null, 1) && !malformed.admit("q", -1)
                        && !malformed.admit("q", RednetQueueBudget.MAX_BYTES_PER_QUEUE + 1),
                "malformed admission must fail without consumption");
        expectFailure(() -> malformed.release("missing", 1));
        check(malformed.admit("mismatch", 2), "mismatch fixture must be admitted");
        expectFailure(() -> malformed.release("mismatch", 1));
        check(malformed.usage("mismatch").equals(new RednetQueueBudget.Usage(1, 2))
                        && malformed.scopeUsage().equals(new RednetQueueBudget.Usage(1, 2)),
                "failed terminal release must not corrupt queue or scope usage");
        malformed.release("mismatch", 2);
        malformed.clear();
        check(malformed.scopeUsage().equals(new RednetQueueBudget.Usage(0, 0)),
                "clear must discard all transient accounting");

        System.out.println("RednetQueueBudgetTest: all tests passed");
    }

    private static void expectFailure(Runnable action) {
        try { action.run(); throw new AssertionError("expected release failure"); }
        catch (IllegalStateException expected) { /* Expected. */ }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
