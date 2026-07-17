package com.malice.terminalcraft.shell;

import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
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

        assertMonitorDemoPrograms(shell);

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

    private static void assertMonitorDemoPrograms(BashShell shell) {
        FakeMonitorHost host = new FakeMonitorHost();
        shell.setHost(host);

        assertResult(shell.executeForResult("source ~/programs/monitor_wall_horizontal.sh"), 0,
                List.of("Horizontal wall pattern rendered (expected size: 80x20)."),
                "horizontal monitor-wall program");
        assertEquals("A".repeat(40) + "B".repeat(40), host.lines.get(2),
                "horizontal program crosses column-40 tile boundary");

        assertResult(shell.executeForResult("source ~/programs/monitor_wall_vertical.sh"), 0,
                List.of("Vertical wall pattern rendered (expected size: 40x40)."),
                "vertical monitor-wall program");
        assertEquals("TOP TILE row 19 -- last top row", host.lines.get(19),
                "vertical program final upper-tile row");
        assertEquals("BOTTOM row 20 -- first bottom row", host.lines.get(20),
                "vertical program first lower-tile row");

        assertResult(shell.executeForResult("source ~/programs/monitor_demo.sh"), 0,
                List.of("2x2 wall pattern rendered (expected size: 80x40).",
                        "Check that text crosses both seams without mirroring or clipping."),
                "default 2x2 monitor-wall program");
        assertEquals("L".repeat(40) + "R".repeat(40), host.lines.get(1),
                "2x2 program upper horizontal split");
        assertEquals("l".repeat(40) + "r".repeat(40), host.lines.get(21),
                "2x2 program lower horizontal split");

        shell.getVfs().writeFile("/home/player/programs/monitor_demo.sh", "echo player-version\n");
        shell.getVfs().rm("/home/player/programs/monitor_wall_grid.sh");
        BashShell restored = new BashShell();
        restored.load(shell.save());
        assertEquals("echo player-version\n",
                restored.getVfs().readFile("/home/player/programs/monitor_demo.sh"),
                "terminal migration preserves edited bundled program");
        assertEquals(true, restored.getVfs().readFile(
                        "/home/player/programs/monitor_wall_grid.sh") != null,
                "terminal migration installs missing monitor program");
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

    private static final class FakeMonitorHost implements TerminalHost {
        private final List<String> lines = new ArrayList<>();

        @Override public boolean monitorWrite(String side, String text) {
            return monitorSetLine(side, lines.size(), text);
        }
        @Override public boolean monitorClear(String side) {
            lines.clear();
            return true;
        }
        @Override public boolean monitorSetLine(String side, int row, String text) {
            if (row < 0 || row >= 40 || text.length() > 80) return false;
            while (lines.size() <= row) lines.add("");
            lines.set(row, text);
            return true;
        }
        @Override public boolean monitorSetTitle(String side, String title) { return true; }
        @Override public boolean monitorSetPalette(String side, int foreground, int background) { return true; }
        @Override public List<String> monitorLines(String side) { return List.copyOf(lines); }
        @Override public int getRedstoneInput(String side) { return 0; }
        @Override public int getRedstoneOutput(String side) { return 0; }
        @Override public boolean setRedstoneOutput(String side, int power) { return false; }
        @Override public List<String> redstoneSides() { return List.of(); }
        @Override public List<String> listPeripherals() { return List.of("right:monitor"); }
        @Override public String getLabel() { return "monitor-demo-test"; }
        @Override public void setLabel(String label) {}
        @Override public CompoundTag readDiskMedia() { return null; }
    }

}
