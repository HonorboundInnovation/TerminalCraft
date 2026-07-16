package com.malice.terminalcraft.network;

/** Headless checks for authoritative command and editor packet admission rules. */
public final class CommandSubmissionGuardTest {
    private CommandSubmissionGuardTest() {}

    public static void main(String[] args) {
        commandAdmissionIsBoundedAndPerPlayer();
        editorAdmissionRequiresBoundServerStateAndValidPayload();
        commandAndEditorMutationsShareOneQuota();
        System.out.println("Command submission guard tests: OK");
    }

    private static void commandAdmissionIsBoundedAndPerPlayer() {
        CommandSubmissionGuard guard = new CommandSubmissionGuard();
        Object alice = new Object();
        Object bob = new Object();

        assertDecision(CommandSubmissionGuard.Decision.INVALID_MENU,
                guard.evaluate(alice, 100, "echo no", false, 32, 2),
                "invalid menu rejected");
        assertDecision(CommandSubmissionGuard.Decision.PAYLOAD_TOO_LONG,
                guard.evaluate(alice, 100, "12345", true, 4, 2),
                "oversized command rejected");
        assertDecision(CommandSubmissionGuard.Decision.PAYLOAD_TOO_LONG,
                guard.evaluate(alice, 100, null, true, 32, 2),
                "null command rejected");

        assertDecision(CommandSubmissionGuard.Decision.ACCEPT,
                guard.evaluate(alice, 100, "one", true, 32, 2),
                "first command accepted");
        assertDecision(CommandSubmissionGuard.Decision.ACCEPT,
                guard.evaluate(alice, 101, "two", true, 32, 2),
                "second command accepted");
        assertDecision(CommandSubmissionGuard.Decision.RATE_LIMITED,
                guard.evaluate(alice, 119, "three", true, 32, 2),
                "same-window overflow rejected");
        assertDecision(CommandSubmissionGuard.Decision.ACCEPT,
                guard.evaluate(alice, 120, "new-window", true, 32, 2),
                "new window accepted");

        assertDecision(CommandSubmissionGuard.Decision.ACCEPT,
                guard.evaluate(bob, 101, "independent", true, 32, 2),
                "players have independent quotas");
        assertDecision(CommandSubmissionGuard.Decision.ACCEPT,
                guard.evaluate(alice, 90, "clock-reset", true, 32, 2),
                "backward tick resets window");

        guard.forget(alice);
        assertDecision(CommandSubmissionGuard.Decision.ACCEPT,
                guard.evaluate(alice, 91, "after-forget", true, 32, 1),
                "forgotten sender starts fresh");
    }

    private static void editorAdmissionRequiresBoundServerStateAndValidPayload() {
        CommandSubmissionGuard guard = new CommandSubmissionGuard();
        Object player = new Object();

        assertDecision(CommandSubmissionGuard.Decision.INVALID_MENU,
                guard.evaluateEditor(player, 10, "text", false, true, false, 8, 2),
                "editor requires valid menu");
        assertDecision(CommandSubmissionGuard.Decision.INVALID_STATE,
                guard.evaluateEditor(player, 10, "text", true, false, false, 8, 2),
                "editor requires active server editor");
        assertDecision(CommandSubmissionGuard.Decision.PAYLOAD_TOO_LONG,
                guard.evaluateEditor(player, 10, "123456789", true, true, false, 8, 2),
                "oversized editor save rejected");
        assertDecision(CommandSubmissionGuard.Decision.PAYLOAD_TOO_LONG,
                guard.evaluateEditor(player, 10, null, true, true, false, 8, 2),
                "null editor payload rejected");
        assertDecision(CommandSubmissionGuard.Decision.INVALID_ACTION,
                guard.evaluateEditor(player, 10, "smuggled", true, true, true, 8, 2),
                "close action rejects unused content");
        assertDecision(CommandSubmissionGuard.Decision.ACCEPT,
                guard.evaluateEditor(player, 10, "12345678", true, true, false, 8, 2),
                "editor boundary payload accepted");
        assertDecision(CommandSubmissionGuard.Decision.ACCEPT,
                guard.evaluateEditor(player, 11, "", true, true, true, 8, 2),
                "empty close action accepted");
        assertDecision(CommandSubmissionGuard.Decision.RATE_LIMITED,
                guard.evaluateEditor(player, 12, "next", true, true, false, 8, 2),
                "editor actions are rate limited");
    }

    private static void commandAndEditorMutationsShareOneQuota() {
        CommandSubmissionGuard guard = new CommandSubmissionGuard();
        Object player = new Object();
        assertDecision(CommandSubmissionGuard.Decision.ACCEPT,
                guard.evaluate(player, 200, "edit file", true, 32, 2),
                "command consumes first shared slot");
        assertDecision(CommandSubmissionGuard.Decision.ACCEPT,
                guard.evaluateEditor(player, 201, "contents", true, true, false, 32, 2),
                "editor consumes second shared slot");
        assertDecision(CommandSubmissionGuard.Decision.RATE_LIMITED,
                guard.evaluate(player, 202, "echo overflow", true, 32, 2),
                "command cannot bypass quota after editor action");
    }

    private static void assertDecision(CommandSubmissionGuard.Decision expected,
                                       CommandSubmissionGuard.Decision actual,
                                       String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
