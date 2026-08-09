package com.malice.terminalcraft.plc;

import java.util.HashMap;
import java.util.Map;

/** Headless characterization tests for PLC compilation, scan timing, state, and fail-safe I/O. */
public final class PlcProgramTest {
    private PlcProgramTest() {}

    public static void main(String[] args) {
        compileAndRunTimerLatchLogic();
        counterCountsRisingEdgesOnly();
        malformedProgramsFailClosed();
        unavailableIoStopsAndZerosOutputs();
        analogTransferScalesSignals();
        pidLoopProducesBoundedOutput();
        sensorBindingsCompileAsInputsOnly();
        System.out.println("PLC program tests: OK");
    }

    private static void compileAndRunTimerLatchLogic() {
        PlcProgram.CompileResult result = PlcProgram.compile("""
                SCAN 1
                IN START REDSTONE NORTH
                IN STOP REDSTONE SOUTH
                OUT MOTOR REDSTONE EAST
                TIMER DELAY 3 = START
                LATCH ENABLE SET START RESET STOP
                RUNG MOTOR = ENABLE AND DELAY.DONE
                """);
        require(result.successful(), "valid timer/latch program compiles: " + result.error());
        PlcProgram.Controller controller = new PlcProgram.Controller();
        controller.load(result.program());
        controller.start();
        FakeIo io = new FakeIo();
        io.inputs.put("NORTH", 15);
        for (int i = 0; i < 2; i++) {
            require(controller.scan(io).success(), "pre-delay scan succeeds");
            require(io.outputs.getOrDefault("EAST", -1) == 0, "timer holds motor before preset");
        }
        require(controller.scan(io).success(), "timer completion scan succeeds");
        require(io.outputs.getOrDefault("EAST", 0) == 15, "completed timer enables motor");
        io.inputs.put("SOUTH", 15);
        controller.scan(io);
        require(io.outputs.getOrDefault("EAST", 15) == 0, "reset input drops latched motor");
    }

    private static void counterCountsRisingEdgesOnly() {
        PlcProgram.CompileResult result = PlcProgram.compile("""
                IN PULSE REDSTONE NORTH
                OUT DONE REDSTONE EAST
                COUNTER C0 2 = PULSE
                RUNG DONE = C0.DONE
                """);
        require(result.successful(), "counter program compiles");
        PlcProgram.Controller controller = new PlcProgram.Controller();
        controller.load(result.program());
        controller.start();
        FakeIo io = new FakeIo();
        io.inputs.put("NORTH", 15);
        controller.scan(io);
        controller.scan(io);
        require(io.outputs.getOrDefault("EAST", 15) == 0, "held input is one rising edge");
        io.inputs.put("NORTH", 0);
        controller.scan(io);
        io.inputs.put("NORTH", 15);
        controller.scan(io);
        require(io.outputs.getOrDefault("EAST", 0) == 15, "second rising edge completes counter");
    }

    private static void malformedProgramsFailClosed() {
        require(!PlcProgram.compile("OUT LAMP REDSTONE EAST\nRUNG LAMP = UNKNOWN").successful(),
                "unknown signals are rejected");
        require(!PlcProgram.compile("SCAN 0").successful(), "scan interval is bounded");
        require(!PlcProgram.compile("IN X BUNDLED NORTH 16").successful(), "bundled channel is bounded");
        require(!PlcProgram.compile("RUNG X = (ON").successful(), "unbalanced expressions are rejected");
    }

    private static void unavailableIoStopsAndZerosOutputs() {
        PlcProgram.CompileResult result = PlcProgram.compile("""
                IN START REDSTONE NORTH
                OUT LAMP REDSTONE EAST
                RUNG LAMP = START
                """);
        PlcProgram.Controller controller = new PlcProgram.Controller();
        controller.load(result.program());
        controller.start();
        FakeIo io = new FakeIo();
        io.failReads = true;
        PlcProgram.ScanResult scan = controller.scan(io);
        require(!scan.success(), "unavailable input faults scan");
        require(!controller.running(), "fault stops controller");
        require(io.outputs.getOrDefault("EAST", -1) == 0, "fault writes safe output");
    }

    private static void analogTransferScalesSignals() {
        PlcProgram.CompileResult result = PlcProgram.compile("""
                SCAN 1
                AIN SENSOR REDSTONE NORTH
                AOUT VALVE REDSTONE EAST
                SCALE VALVE = SENSOR 0 15 0 15
                """);
        require(result.successful(), "analog scaling program compiles: " + result.error());
        PlcProgram.Controller controller = new PlcProgram.Controller();
        controller.load(result.program());
        controller.start();
        FakeIo io = new FakeIo();
        io.inputs.put("NORTH", 9);
        require(controller.scan(io).success(), "analog scan succeeds");
        require(io.outputs.getOrDefault("EAST", -1) == 9, "analog value transfers to output");
        require(controller.analogValues().getOrDefault("SENSOR", -1) == 9, "analog input is observable");
    }

    private static void pidLoopProducesBoundedOutput() {
        PlcProgram.CompileResult result = PlcProgram.compile("""
                SCAN 1
                AIN PROCESS REDSTONE NORTH
                AOUT HEATER REDSTONE EAST
                PID LOOP SETPOINT 12 PROCESS PROCESS OUTPUT HEATER KP 2 KI 0 KD 0
                """);
        require(result.successful(), "PID program compiles: " + result.error());
        PlcProgram.Controller controller = new PlcProgram.Controller();
        controller.load(result.program());
        controller.start();
        FakeIo io = new FakeIo();
        io.inputs.put("NORTH", 4);
        require(controller.scan(io).success(), "PID scan succeeds");
        require(io.outputs.getOrDefault("EAST", -1) == 15, "PID output is bounded to signal maximum");
        io.inputs.put("NORTH", 11);
        require(controller.scan(io).success(), "second PID scan succeeds");
        require(io.outputs.getOrDefault("EAST", -1) == 2, "PID proportional correction is applied");
    }

    private static void sensorBindingsCompileAsInputsOnly() {
        PlcProgram.CompileResult result = PlcProgram.compile("""
                AIN TANK SENSOR tank_level
                OUT PUMP REDSTONE EAST
                RUNG PUMP = TANK
                """);
        require(result.successful(), "sensor bindings compile as PLC inputs: " + result.error());
        require(result.program().inputs().get(0).kind() == PlcProgram.BindingKind.SENSOR,
                "sensor input retains its source kind");
        require(!PlcProgram.compile("OUT BAD SENSOR tank_level\nRUNG BAD = ON").successful(),
                "sensor bindings are rejected on outputs");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class FakeIo implements PlcProgram.Io {
        private final Map<String, Integer> inputs = new HashMap<>();
        private final Map<String, Integer> outputs = new HashMap<>();
        private boolean failReads;

        @Override public int read(PlcProgram.Binding binding) {
            return failReads ? -1 : inputs.getOrDefault(binding.side().toUpperCase(), 0);
        }

        @Override public boolean write(PlcProgram.Binding binding, int strength) {
            outputs.put(binding.side().toUpperCase(), strength);
            return true;
        }
    }
}
