package com.malice.terminalcraft.shell;

import net.minecraft.nbt.CompoundTag;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Headless coverage for the terminal-to-bundled-control-cable shell bridge. */
public final class BundledWireShellCommandTest {
    private BundledWireShellCommandTest() {}

    public static void main(String[] args) {
        FakeHost host = new FakeHost();
        BashShell shell = new BashShell();
        shell.setHost(host);

        assertResult(shell.executeForResult("wire help"), 0, List.of(
                "wire get <side|any> <channel 0-15|all>",
                "wire input <side|any> <channel 0-15|all>",
                "wire output <side|any> <channel 0-15|all>",
                "wire set <side|any> <channel 0-15> <strength 0-15>",
                "wire status <side|any>"), "help");
        host.input.put(3, 15);
        assertResult(shell.executeForResult("wire set left 3 12"), 0, List.of(), "set channel");
        assertResult(shell.executeForResult("wire output left 3"), 0, List.of("12"), "local output");
        assertResult(shell.executeForResult("wire input left 3"), 0, List.of("15"),
                "external input does not echo local output");

        host.effective.put(3, 15);
        assertResult(shell.executeForResult("wire get left 3"), 0, List.of("15"), "effective signal");
        assertResult(shell.executeForResult("bundled get any 3"), 0, List.of("15"), "command alias");

        assertResult(shell.executeForResult("rs set left 7 9"), 0, List.of(),
                "redstone alias sets one bundled channel");
        host.input.put(7, 4);
        assertResult(shell.executeForResult("redstone input left 7"), 0, List.of("4"),
                "redstone reads one bundled input channel");
        assertResult(shell.executeForResult("redstone output left 7"), 0, List.of("9"),
                "redstone reads one bundled output channel");
        require(host.output.getOrDefault(6, 0) == 0 && host.output.getOrDefault(8, 0) == 0,
                "setting channel 7 must not alter neighboring channels");

        ShellCommandResult status = shell.executeForResult("rs channels left");
        require(status.exitCode() == 0 && status.outputLines().size() == 17,
                "channel status must expose one header and all 16 channels");
        require(status.outputLines().get(1).startsWith("00 white")
                        && status.outputLines().get(8).startsWith("07 gray")
                        && status.outputLines().get(16).startsWith("15 black"),
                "status rows must preserve dye/channel identity");

        ShellCommandResult allInputs = shell.executeForResult("wire input left all");
        require(allInputs.exitCode() == 0 && allInputs.outputLines().size() == 17
                        && allInputs.outputLines().get(4).endsWith("15"),
                "all-input view must list every independent channel");
        assertResult(shell.executeForResult("wire set left 16 1"), 1,
                List.of("wire: channel must be an integer from 0 to 15"), "channel bound");
        assertResult(shell.executeForResult("wire set left 4 20"), 1,
                List.of("wire: strength must be an integer from 0 to 15"), "strength bound");
        assertResult(shell.executeForResult("wire get right 3"), 1,
                List.of("wire: no bundled cable on side 'right'"), "missing side");

        System.out.println("Bundled wire shell command tests: OK");
    }

    private static void assertResult(ShellCommandResult actual, int exitCode,
                                     List<String> output, String message) {
        if (actual.exitCode() != exitCode || !actual.outputLines().equals(output)) {
            throw new AssertionError(message + ": expected exit=" + exitCode + " output=" + output
                    + ", actual exit=" + actual.exitCode() + " output=" + actual.outputLines());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class FakeHost implements TerminalHost {
        private final Map<Integer, Integer> output = new HashMap<>();
        private final Map<Integer, Integer> input = new HashMap<>();
        private final Map<Integer, Integer> effective = new HashMap<>();

        @Override public boolean hasBundledCable(String side) {
            return "left".equals(side) || "any".equals(side);
        }
        @Override public int bundledSignal(String side, int channel) {
            return hasBundledCable(side) ? effective.getOrDefault(channel,
                    Math.max(input.getOrDefault(channel, 0), output.getOrDefault(channel, 0))) : -1;
        }
        @Override public int bundledInput(String side, int channel) {
            return hasBundledCable(side) ? input.getOrDefault(channel, 0) : -1;
        }
        @Override public int bundledOutput(String side, int channel) {
            return hasBundledCable(side) ? output.getOrDefault(channel, 0) : -1;
        }
        @Override public boolean setBundledOutput(String side, int channel, int strength) {
            if (!hasBundledCable(side) || channel < 0 || channel > 15 || strength < 0 || strength > 15) return false;
            output.put(channel, strength);
            effective.put(channel, Math.max(input.getOrDefault(channel, 0), strength));
            return true;
        }

        @Override public int getRedstoneInput(String side) { return 0; }
        @Override public int getRedstoneOutput(String side) { return 0; }
        @Override public boolean setRedstoneOutput(String side, int power) { return false; }
        @Override public List<String> redstoneSides() { return List.of(); }
        @Override public List<String> listPeripherals() { return List.of(); }
        @Override public String getLabel() { return "test"; }
        @Override public void setLabel(String label) {}
        @Override public CompoundTag readDiskMedia() { return null; }
    }
}
