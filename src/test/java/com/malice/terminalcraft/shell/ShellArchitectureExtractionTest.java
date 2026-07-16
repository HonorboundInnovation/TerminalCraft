package com.malice.terminalcraft.shell;

import java.util.List;

/** Headless characterization for composed host services and external command modules. */
public final class ShellArchitectureExtractionTest {
    private ShellArchitectureExtractionTest() {}

    public static void main(String[] args) {
        assertEquals(List.of("echo", "two words"), ShellSyntax.tokenize("echo \"two words\""),
                "quote-aware tokenization");
        assertEquals(List.of("echo \"a|b\" ", " cat"),
                ShellSyntax.splitTopLevel("echo \"a|b\" | cat", '|'),
                "top-level pipeline splitting");
        assertEquals("echo '# kept'", ShellSyntax.stripComment("echo '# kept' # removed"),
                "quote-aware comment stripping");

        FakeHost host = new FakeHost();
        TerminalHostServices services = host.services();
        assertEquals("alpha", services.identity().label(), "legacy identity adapter");
        assertEquals(7, services.redstone().input("left"), "legacy redstone adapter");
        assertTrue(services.turtle().available(), "legacy turtle adapter");
        assertTrue(services.disk().hasMedia(), "legacy disk adapter");

        BashShell shell = new BashShell();
        shell.setHost(host);
        assertOutcome(shell.executeForResult("peripheral list"), 0,
                List.of("left:monitor"), "extracted peripheral command");
        assertOutcome(shell.executeForResult("label beta"), 0,
                List.of("beta"), "extracted label command");
        assertEquals("beta", host.label, "label forwarded through composed identity service");

        shell.installCommandModule(registrar -> registrar.register("probe", (context, arguments) -> {
            TerminalHostServices current = context.hostServices();
            context.printLine(current.identity().label() + ":" + arguments.size());
            context.setExitCode(23);
        }));
        assertOutcome(shell.executeForResult("probe one two"), 23,
                List.of("beta:2"), "independent module dispatch");

        boolean duplicateRejected = false;
        try {
            shell.installCommandModule(registrar -> registrar.register("probe", (context, arguments) -> {}));
        } catch (IllegalArgumentException expected) {
            duplicateRejected = true;
        }
        assertTrue(duplicateRejected, "duplicate module commands fail closed");

        System.out.println("Shell architecture extraction tests: OK");
    }

    private static final class FakeHost implements TerminalHost {
        private String label = "alpha";

        @Override public int getRedstoneInput(String side) { return 7; }
        @Override public int getRedstoneOutput(String side) { return 3; }
        @Override public boolean setRedstoneOutput(String side, int power) { return true; }
        @Override public List<String> redstoneSides() { return List.of("left"); }
        @Override public List<String> listPeripherals() { return List.of("left:monitor"); }
        @Override public String getLabel() { return label; }
        @Override public void setLabel(String label) { this.label = label; }
        @Override public boolean isTurtle() { return true; }
        @Override public boolean hasDiskMedia() { return true; }
    }

    private static void assertOutcome(ShellCommandResult actual, int exitCode,
                                      List<String> lines, String message) {
        assertEquals(exitCode, actual.exitCode(), message + " exit code");
        assertEquals(lines, actual.outputLines(), message + " output");
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
