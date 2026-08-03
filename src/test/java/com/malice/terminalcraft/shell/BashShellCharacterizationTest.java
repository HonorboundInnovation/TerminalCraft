package com.malice.terminalcraft.shell;

import com.malice.terminalcraft.network.MonitorRemoteRequest;
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

        assertResult(shell.executeForResult("echo '$USER'"), 0, List.of("$USER"),
                "single quotes suppress expansion");
        assertResult(shell.executeForResult("JOB=$(echo job-42); echo $JOB"), 0, List.of("job-42"),
                "command substitution captures identifiers without leaking output");
        assertResult(shell.executeForResult("echo $((2 + 3 * 4))"), 0, List.of("14"),
                "bounded arithmetic expansion");
        assertResult(shell.executeForResult("MIN=-9223372036854775808; arith MIN / -1"), 2,
                List.of("arith: long overflow"), "arithmetic overflow fails closed");
        assertResult(shell.executeForResult(
                        "I=0; while [ \"$I\" -lt 3 ]; do echo $I; let I=$I+1; done"),
                0, List.of("0", "1", "2"), "arithmetic while loop");
        assertResult(shell.executeForResult(
                        "for item in one two three; do echo $item; break; echo never; done"),
                0, List.of("one"), "break exits nearest loop");
        assertResult(shell.executeForResult(
                        "for item in one two; do continue; echo never; done\necho continued"),
                0, List.of("continued"), "continue advances nearest loop");
        assertResult(shell.executeForResult("echo before; exit 7; echo after"),
                7, List.of("before"), "exit terminates the current script");

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
        FakeMonitorHost host = new FakeMonitorHost(120, 40);
        shell.setHost(host);

        assertResult(shell.executeForResult("monitor size any"), 0,
                List.of("columns=120 rows=40 tiles=3x2"),
                "monitor geometry detection");
        assertResult(shell.executeForResult("source ~/programs/monitor_demo.sh"), 0,
                List.of("Detected 3x2 monitor wall (120x40 characters).",
                        "Each physical tile should show one uniquely labeled bordered segment."),
                "adaptive monitor-wall program");
        assertEquals(40, host.lines.size(), "adaptive program uses every detected row");
        assertEquals("+" + "-".repeat(38) + "+"
                        + "+" + "-".repeat(38) + "+"
                        + "+" + "-".repeat(38) + "+",
                host.lines.get(0), "adaptive program draws three top-row tile borders");
        assertEquals(true, host.lines.get(3).startsWith("|" + "A".repeat(38) + "|"),
                "first tile gets its own diagnostic symbol");
        assertEquals(true, host.lines.get(3).contains("|" + "B".repeat(38) + "|"),
                "second tile gets a distinct diagnostic symbol");
        assertEquals(true, host.lines.get(23).contains("|" + "D".repeat(38) + "|"),
                "second tile row is detected rather than assumed");

        assertResult(shell.executeForResult("monitor service add dashboard 42"), 0,
                List.of("monitor service dashboard 42"), "monitor service registration");
        assertResult(shell.executeForResult("monitor service list"), 0,
                List.of("dashboard 42"), "monitor service listing");
        assertResult(shell.executeForResult("monitor remote dashboard set 2 'Factory online'"), 0,
                List.of("accepted service=dashboard operation=set"), "remote monitor publication");
        assertEquals(MonitorRemoteRequest.set(2, "Factory online"),
                MonitorRemoteRequest.decode(host.remotePayload).orElseThrow(),
                "remote monitor payload is typed and round-trippable");

        shell.getVfs().writeFile("/home/player/programs/monitor_demo.sh", "echo player-version\n");
        shell.getVfs().writeFile("/home/player/programs/monitor_wall_grid.sh",
                "#!/bin/bash\n# Full 2x2 wall test: legacy stock program\n");
        BashShell restored = new BashShell();
        restored.load(shell.save());
        assertEquals("echo player-version\n",
                restored.getVfs().readFile("/home/player/programs/monitor_demo.sh"),
                "terminal migration preserves edited bundled program");
        assertEquals(true, restored.getVfs().readFile(
                        "/home/player/programs/monitor_wall_grid.sh").contains("monitor demo any"),
                "terminal migration upgrades recognizable fixed-grid program");
        assertEquals(true, restored.getVfs().readFile(
                        "/home/player/programs/monitor_wall_auto.sh") != null,
                "terminal migration installs adaptive monitor program");
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
        private final int columns;
        private final int rows;
        private String remoteService = "";
        private String remotePayload = "";

        private FakeMonitorHost(int columns, int rows) {
            this.columns = columns;
            this.rows = rows;
        }

        @Override public boolean monitorWrite(String side, String text) {
            return monitorSetLine(side, lines.size(), text);
        }
        @Override public boolean monitorClear(String side) {
            lines.clear();
            return true;
        }
        @Override public boolean monitorSetLine(String side, int row, String text) {
            if (row < 0 || row >= rows || text.length() > columns) return false;
            while (lines.size() <= row) lines.add("");
            lines.set(row, text);
            return true;
        }
        @Override public boolean monitorSetTitle(String side, String title) { return true; }
        @Override public boolean monitorSetPalette(String side, int foreground, int background) { return true; }
        @Override public List<String> monitorLines(String side) { return List.copyOf(lines); }
        @Override public int monitorColumns(String side) { return columns; }
        @Override public int monitorRows(String side) { return rows; }
        @Override public boolean monitorRegisterService(String service, int port) {
            remoteService = service.toLowerCase(java.util.Locale.ROOT) + " " + port;
            return true;
        }
        @Override public boolean monitorUnregisterService(String service) {
            if (!remoteService.startsWith(service.toLowerCase(java.util.Locale.ROOT) + " ")) return false;
            remoteService = "";
            return true;
        }
        @Override public List<String> monitorServices() {
            return remoteService.isEmpty() ? List.of() : List.of(remoteService);
        }
        @Override public boolean monitorRemote(String service, String encodedRequest) {
            if (!remoteService.startsWith(service.toLowerCase(java.util.Locale.ROOT) + " ")) return false;
            remotePayload = encodedRequest;
            return true;
        }
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
