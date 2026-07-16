package com.malice.terminalcraft.world;

/** Headless boundary checks for terminal chunk-ticket admission policy. */
public final class TerminalChunkTicketPolicyTest {
    private TerminalChunkTicketPolicyTest() {}

    public static void main(String[] args) {
        TerminalChunkTicketPolicy disabled = new TerminalChunkTicketPolicy(false, 16, 64);
        assertTrue(!disabled.allows(0, 0), "disabled policy always denies");

        TerminalChunkTicketPolicy bounded = new TerminalChunkTicketPolicy(true, 2, 3);
        assertTrue(bounded.allows(0, 0), "empty scopes admit");
        assertTrue(bounded.allows(1, 2), "capacity below both limits admits");
        assertTrue(!bounded.allows(2, 2), "dimension quota denies");
        assertTrue(!bounded.allows(1, 3), "server quota denies");

        assertTrue(!new TerminalChunkTicketPolicy(true, 0, 10).allows(0, 0),
                "zero dimension quota denies");
        assertTrue(!new TerminalChunkTicketPolicy(true, 10, 0).allows(0, 0),
                "zero server quota denies");
        assertTrue(!new TerminalChunkTicketPolicy(true, -1, -1).allows(0, 0),
                "negative limits normalize to zero");
        assertTrue(!bounded.allows(-1, 0) && !bounded.allows(0, -1),
                "invalid counters fail closed");

        System.out.println("Terminal chunk-ticket policy tests: OK");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
