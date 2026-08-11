package com.malice.terminalcraft.shell;

import com.malice.terminalcraft.device.DeviceAccess;
import com.malice.terminalcraft.device.DeviceCallContext;
import com.malice.terminalcraft.device.DeviceDescriptor;
import com.malice.terminalcraft.device.DeviceEventBatch;
import com.malice.terminalcraft.device.DeviceMethodDescriptor;
import com.malice.terminalcraft.device.DeviceParameterDescriptor;
import com.malice.terminalcraft.device.DeviceResult;
import com.malice.terminalcraft.device.DeviceValue;
import com.malice.terminalcraft.device.DeviceValueType;
import com.malice.terminalcraft.device.TerminalBuffer;
import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Headless interaction coverage for the server-authoritative device Control Center. */
public final class ControlCenterProgramTest {
    private ControlCenterProgramTest() {}

    public static void main(String[] args) {
        FakeAccess access = new FakeAccess();
        TerminalBuffer surface = new TerminalBuffer(40, 16);
        ControlCenterProgram program = new ControlCenterProgram();

        program.open(access, null, surface);
        require(program.active(), "control center opens");
        require(contains(surface, "CONTROL CENTER") && contains(surface, "Factory PLC"),
                "overview renders discovered device state");

        program.handle(ControlCenterProgram.Action.ACTIVATE, -1, -1, "", access, null, surface);
        require(access.calls.isEmpty() && contains(surface, "> METHODS"),
                "Enter on Overview opens the selected device's method pane");
        program.handle(ControlCenterProgram.Action.ACTIVATE, -1, -1, "", access, null, surface);
        require(access.calls.stream().anyMatch(call -> call.method.equals("control.run")),
                "Enter on a zero-argument method invokes through DeviceAccess");

        program.handle(ControlCenterProgram.Action.DOWN, -1, -1, "", access, null, surface);
        program.handle(ControlCenterProgram.Action.DOWN, -1, -1, "", access, null, surface);
        program.handle(ControlCenterProgram.Action.ACTIVATE, -1, -1, "", access, null, surface);
        require(program.inputActive(), "typed method opens bounded argument prompt");
        program.handle(ControlCenterProgram.Action.SUBMIT_TEXT, -1, -1, "12", access, null, surface);
        require(!program.inputActive() && access.calls.stream().anyMatch(call -> call.method.equals("setpoint.set")
                        && call.arguments.get(0) instanceof DeviceValue.NumberValue number
                        && number.value() == 12.0),
                "method prompt applies schema conversion before invocation");

        program.handle(ControlCenterProgram.Action.NEXT_TAB, -1, -1, "", access, null, surface);
        program.handle(ControlCenterProgram.Action.ACTIVATE, -1, -1, "", access, null, surface);
        require(access.calls.stream().anyMatch(call -> call.method.equals("program.set")
                        && call.arguments.get(0) instanceof DeviceValue.StringValue source
                        && source.value().contains("SCAN")),
                "PLC template loads remotely through program.set");

        program.handle(ControlCenterProgram.Action.REFRESH, -1, -1, "", access, null, surface);
        require(contains(surface, "refreshed"), "refresh command reports updated discovery state");
        program.handle(ControlCenterProgram.Action.PREVIOUS_TAB, -1, -1, "", access, null, surface);
        require(contains(surface, "> METHODS"), "Shift+Tab-style action selects previous tab");
        program.handle(ControlCenterProgram.Action.LEFT, -1, -1, "", access, null, surface);
        require(contains(surface, "> DEVICES"), "Left selects the device pane");
        program.handle(ControlCenterProgram.Action.RIGHT, -1, -1, "", access, null, surface);
        require(contains(surface, "> METHODS"), "Right selects the detail pane");
        program.handle(ControlCenterProgram.Action.CLICK, 1, 25, "", access, null, surface);
        require(contains(surface, "PROGRAMS"), "mouse-style tab selection remains functional");

        program.handle(ControlCenterProgram.Action.RENAME, -1, -1, "", access, null, surface);
        require(program.inputActive(), "DNS action opens a text prompt");
        program.handle(ControlCenterProgram.Action.CANCEL_INPUT, -1, -1, "", access, null, surface);
        require(!program.inputActive(), "escape-style cancellation returns to navigation");

        program.handle(ControlCenterProgram.Action.UP, -1, -1, "", access, null, surface);
        program.handle(ControlCenterProgram.Action.DOWN, -1, -1, "", access, null, surface);
        require(program.active(), "up/down navigation remains inside the active program");

        CompoundTag saved = program.save();
        ControlCenterProgram restored = new ControlCenterProgram();
        restored.load(saved);
        require(restored.active(), "full-screen session state survives shell synchronization");
        restored.handle(ControlCenterProgram.Action.CLOSE, -1, -1, "", access, null, surface);
        require(!restored.active(), "close returns ownership of the terminal surface");
        System.out.println("ControlCenterProgramTest: all tests passed");
    }

    private static boolean contains(TerminalBuffer surface, String value) {
        return surface.lines().stream().anyMatch(line -> line.contains(value));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class FakeAccess implements DeviceAccess {
        private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000045");
        private final DeviceCallContext context = DeviceCallContext.readOnly("control-center-test");
        private final List<Call> calls = new ArrayList<>();
        private final DeviceDescriptor descriptor = new DeviceDescriptor(ID, "terminalcraft:plc",
                "programmable_logic_controller", "Factory PLC", "terminalcraft", "test:plc",
                Set.of("plc", "remote_programming"), Map.of("state", DeviceValue.of("stopped")),
                List.of(
                        new DeviceMethodDescriptor("control.run", "Starts the PLC", List.of(),
                                DeviceValueType.NULL, DeviceCallContext.WRITE),
                        new DeviceMethodDescriptor("program.set", "Loads PLC source", List.of(
                                new DeviceParameterDescriptor("source", DeviceValueType.STRING, true, "Source")),
                                DeviceValueType.NULL, DeviceCallContext.WRITE),
                        new DeviceMethodDescriptor("setpoint.set", "Sets a test setpoint", List.of(
                                new DeviceParameterDescriptor("value", DeviceValueType.NUMBER, true, "Value")),
                                DeviceValueType.NULL, DeviceCallContext.WRITE)),
                Set.of("scan"), Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE), true, true);

        @Override public DeviceCallContext context() { return context; }
        @Override public List<DeviceDescriptor> descriptors(int limit) { return List.of(descriptor); }
        @Override public Optional<DeviceDescriptor> descriptor(UUID deviceId) {
            return ID.equals(deviceId) ? Optional.of(descriptor) : Optional.empty();
        }
        @Override public DeviceResult call(UUID deviceId, String method, List<DeviceValue> arguments) {
            calls.add(new Call(method, List.copyOf(arguments)));
            return DeviceResult.success();
        }
        @Override public DeviceEventBatch pollEvents(int limit) { return new DeviceEventBatch(List.of(), 0); }
    }

    private record Call(String method, List<DeviceValue> arguments) {}
}
