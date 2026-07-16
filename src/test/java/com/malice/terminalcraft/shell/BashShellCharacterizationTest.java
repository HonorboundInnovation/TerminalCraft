package com.malice.terminalcraft.shell;

import net.minecraft.nbt.CompoundTag;

import java.util.List;

/**
 * External characterization tests for the public shell execution and persistence surfaces.
 */
public final class BashShellCharacterizationTest {
    private BashShellCharacterizationTest() {}

    public static void main(String[] args) {
        BashShell shell = new BashShell();

        assertResult(shell.executeForResult("echo hello"), 0, List.of("hello"), "echo result");
        assertResult(shell.executeForResult("false"), 1, List.of(), "false exit status");
        assertResult(shell.executeForResult("missing-command"), 127,
                List.of("bash: missing-command: command not found"), "unknown command");

        ShellCommandResult chained = shell.executeForResult("false || echo recovered");
        assertResult(chained, 0, List.of("recovered"), "or-chain behavior");

        ShellCommandResult alias = shell.executeForResult("printenv USER");
        assertResult(alias, 0, List.of("player"), "builtin alias");

        ShellCommandResult sophisticatedHelp = shell.executeForResult("sophisticated help");
        assertEquals(0, sophisticatedHelp.exitCode(), "Sophisticated program registered");
        assertEquals("usage: sophisticated list", sophisticatedHelp.outputLines().get(0),
                "Sophisticated program help");
        ShellCommandResult drawerAliasHelp = shell.executeForResult("drawer help");
        assertEquals(0, drawerAliasHelp.exitCode(), "Storage Drawers alias registered");
        assertEquals("usage: drawers list", drawerAliasHelp.outputLines().get(0),
                "Storage Drawers program help");

        ShellCommandResult redirected = shell.executeForResult("echo stored > /tmp/characterized.txt");
        assertResult(redirected, 0, List.of(), "redirected output");
        assertEquals("stored\n", shell.getVfs().readFile("/tmp/characterized.txt"), "redirected file");

        shell.runScriptText("cd /tmp; CHARACTERIZED=yes; false");
        CompoundTag saved = shell.save();
        BashShell restored = new BashShell();
        restored.load(saved);
        assertEquals("/tmp", restored.getCwd(), "saved cwd");
        assertEquals(1, restored.getLastExitCode(), "saved exit status");
        assertEquals("stored\n", restored.getVfs().readFile("/tmp/characterized.txt"), "saved VFS");
        assertResult(restored.executeForResult("echo $CHARACTERIZED"), 0, List.of("yes"), "saved environment");

        System.out.println("BashShell characterization tests: OK");
    }

    private static void assertResult(ShellCommandResult actual, int exitCode,
                                     List<String> output, String message) {
        assertEquals(exitCode, actual.exitCode(), message + " exit code");
        assertEquals(output, actual.outputLines(), message + " output");
        assertEquals(exitCode == 0, actual.succeeded(), message + " success flag");
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
