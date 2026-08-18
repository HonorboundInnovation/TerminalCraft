package com.malice.terminalcraft.plc;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Headless characterization tests for PLC compilation, scan timing, state, and fail-safe I/O. */
public final class PlcProgramTest {
    private PlcProgramTest() {}

    public static void main(String[] args) {
        compileAndRunTimerLatchLogic();
        counterCountsRisingEdgesOnly();
        malformedProgramsFailClosed();
        unavailableIoStopsAndZerosOutputs();
        pureEvaluationDefersAllOutputWrites();
        analogTransferScalesSignals();
        sensorPercentComparisonsDriveMutuallyExclusivePumps();
        retainedRuntimeStateSurvivesControllerReload();
        pidLoopProducesBoundedOutput();
        sensorBindingsCompileAsInputsOnly();
        builtInTemplatesCompile();
        createTemplatesExecuteControlPatterns();
        System.out.println("PLC program tests: OK");
    }

    private static void builtInTemplatesCompile() {
        require(PlcProgramTemplates.all().size() >= 45,
                "template catalog has general, Create, Mekanism, and SecurityCraft starter programs");
        Set<String> ids = new HashSet<>();
        for (PlcProgramTemplates.Template template : PlcProgramTemplates.all()) {
            require(ids.add(template.id()), "template id is unique: " + template.id());
            PlcProgram.CompileResult result = PlcProgram.compile(template.source());
            require(result.successful(), "template compiles: " + template.id() + " — " + result.error());
        }
        require(PlcProgramTemplates.find("MOTOR-START-STOP").isPresent(),
                "template lookup is case-insensitive");
        require(PlcProgramTemplates.categories().equals(java.util.List.of(
                        "general", "create", "mekanism", "securitycraft")),
                "template categories are stable and discoverable");
        require(PlcProgramTemplates.byCategory("CREATE").size() == 14,
                "Create category contains the complete Create PLC library");
        require(PlcProgramTemplates.byCategory("create").stream()
                        .allMatch(template -> template.id().startsWith("create-")),
                "Create templates use a recognizable id prefix");
        require(PlcProgramTemplates.byCategory("MEKANISM").size() == 8,
                "Mekanism category contains the complete Mekanism PLC starter library");
        require(PlcProgramTemplates.byCategory("mekanism").stream()
                        .allMatch(template -> template.id().startsWith("mekanism-")),
                "Mekanism templates use a recognizable id prefix");
        require(PlcProgramTemplates.byCategory("SECURITYCRAFT").size() == 10,
                "SecurityCraft category contains the complete security PLC starter library");
        require(PlcProgramTemplates.byCategory("securitycraft").stream()
                        .allMatch(template -> template.id().startsWith("securitycraft-")),
                "SecurityCraft templates use a recognizable id prefix");
    }

    private static void createTemplatesExecuteControlPatterns() {
        PlcProgram.Controller clutch = controllerFor("create-clutch-safety");
        FakeIo clutchIo = new FakeIo();
        clutchIo.inputs.put("NORTH", 15);
        clutchIo.inputs.put("UP", 15);
        require(clutch.scan(clutchIo).success(), "Create clutch starter scan succeeds");
        require(clutchIo.outputs.getOrDefault("EAST", 0) == 15,
                "start and permissive assert fail-safe run-enable output");
        clutchIo.inputs.put("SOUTH", 15);
        clutch.scan(clutchIo);
        require(clutchIo.outputs.getOrDefault("EAST", 15) == 0,
                "stop drops the fail-safe run-enable output");

        PlcProgram.Controller reversing = controllerFor("create-reversing-drive");
        FakeIo reversingIo = new FakeIo();
        reversingIo.inputs.put("NORTH", 15);
        reversing.scan(reversingIo);
        reversingIo.inputs.put("WEST", 15);
        reversing.scan(reversingIo);
        require(reversingIo.outputs.getOrDefault("DOWN", 15) == 0,
                "Gearshift direction cannot change while the drive is running");
        reversingIo.inputs.put("SOUTH", 15);
        reversing.scan(reversingIo);
        require(reversingIo.outputs.getOrDefault("EAST", 15) == 0
                        && reversingIo.outputs.getOrDefault("DOWN", 0) == 15,
                "stopping the drive permits the selected Gearshift direction change");

        PlcProgram.Controller speed = controllerFor("create-chain-speed-control");
        FakeIo speedIo = new FakeIo();
        speedIo.inputs.put("NORTH", 11);
        require(speed.scan(speedIo).success(), "Create chain speed scan succeeds");
        require(speedIo.outputs.getOrDefault("EAST", -1) == 11,
                "analog speed command reaches the Adjustable Chain Gearshift output");

        PlcProgram.Controller jam = controllerFor("create-belt-jam-stop");
        FakeIo jamIo = new FakeIo();
        jamIo.inputs.put("NORTH", 15);
        jamIo.inputs.put("WEST", 15);
        require(jam.scan(jamIo).success(), "Create jam controller starts");
        for (int scan = 0; scan < 60; scan++) require(jam.scan(jamIo).success(),
                "Create jam dwell scan succeeds");
        require(jamIo.outputs.getOrDefault("EAST", 15) == 0,
                "sustained Content Observer signal stops the belt");
        require(jamIo.outputs.getOrDefault("DOWN", 0) == 15,
                "sustained Content Observer signal latches the jam alarm");

        PlcProgram.Controller sequencer = controllerFor("create-sequenced-gearshift");
        FakeIo sequencerIo = new FakeIo();
        sequencerIo.inputs.put("NORTH", 15);
        for (int scan = 0; scan < 3; scan++) {
            require(sequencer.scan(sequencerIo).success(), "Sequenced Gearshift pulse scan succeeds");
            require(sequencerIo.outputs.getOrDefault("EAST", 0) == 15,
                    "Sequenced Gearshift trigger remains high during pulse window");
        }
        sequencer.scan(sequencerIo);
        require(sequencerIo.outputs.getOrDefault("EAST", 15) == 0,
                "Sequenced Gearshift trigger ends after its bounded pulse");
    }

    private static PlcProgram.Controller controllerFor(String templateId) {
        PlcProgramTemplates.Template template = PlcProgramTemplates.find(templateId)
                .orElseThrow(() -> new AssertionError("missing template: " + templateId));
        PlcProgram.CompileResult compiled = PlcProgram.compile(template.source());
        if (!compiled.successful()) throw new AssertionError("template did not compile: " + templateId);
        PlcProgram.Controller controller = new PlcProgram.Controller();
        controller.load(compiled.program());
        controller.start();
        return controller;
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

    private static void pureEvaluationDefersAllOutputWrites() {
        PlcProgram.CompileResult result = PlcProgram.compile("""
                IN ENABLE REDSTONE NORTH
                OUT FIRST BUNDLED WEST 0
                OUT SECOND BUNDLED WEST 1
                RUNG FIRST = ENABLE
                RUNG SECOND = NOT ENABLE
                """);
        require(result.successful(), "transactional output program compiles");
        PlcProgram.Controller controller = new PlcProgram.Controller();
        controller.load(result.program());
        controller.start();
        FakeIo io = new FakeIo();
        io.inputs.put("NORTH", 15);

        PlcProgram.ScanResult scan = controller.evaluate(io);
        require(scan.success(), "pure PLC evaluation succeeds");
        require(io.outputs.isEmpty(), "pure PLC evaluation performs no outside writes");
        require(scan.desiredOutputs().size() == 2, "evaluation returns one complete output snapshot");
        require(scan.desiredOutputs().entrySet().stream()
                        .anyMatch(entry -> entry.getKey().channel() == 0 && entry.getValue() == 15),
                "output snapshot contains asserted channel");
        require(scan.desiredOutputs().entrySet().stream()
                        .anyMatch(entry -> entry.getKey().channel() == 1 && entry.getValue() == 0),
                "output snapshot contains cleared channel");
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

    private static void sensorPercentComparisonsDriveMutuallyExclusivePumps() {
        PlcProgram.CompileResult result = PlcProgram.compile("""
                SCAN 1
                AIN LEVEL SENSOR value
                OUT FILL_STOP BUNDLED WEST 0
                OUT DRAIN_STOP BUNDLED WEST 1
                LATCH FILLING SET LEVEL == 0 RESET LEVEL == 100
                RUNG FILL_STOP = NOT FILLING
                RUNG DRAIN_STOP = FILLING
                """);
        require(result.successful(), "percentage sensor pump program compiles: " + result.error());
        PlcProgram.Controller controller = new PlcProgram.Controller();
        controller.load(result.program());
        controller.start();
        FakeIo io = new FakeIo();

        io.analogInputs.put("LEVEL", 0.0);
        require(controller.scan(io).success(), "empty percentage scan succeeds");
        require(io.outputs.getOrDefault("WEST:0", -1) == 0 && io.outputs.getOrDefault("WEST:1", -1) == 15,
                "empty tank runs fill pump only");
        require(!io.bothPumpStopsOff, "empty transition never leaves both pump-stop channels off");

        io.analogInputs.put("LEVEL", 50.0);
        require(controller.scan(io).success(), "mid-level percentage scan succeeds");
        require(io.outputs.getOrDefault("WEST:0", -1) == 0 && io.outputs.getOrDefault("WEST:1", -1) == 15,
                "mid-level holds fill state");

        io.analogInputs.put("LEVEL", 100.0);
        require(controller.scan(io).success(), "full percentage scan succeeds");
        require(io.outputs.getOrDefault("WEST:0", -1) == 15 && io.outputs.getOrDefault("WEST:1", -1) == 0,
                "full tank runs drain pump only");
        require(!io.bothPumpStopsOff, "full transition never leaves both pump-stop channels off");
    }

    private static void retainedRuntimeStateSurvivesControllerReload() {
        PlcProgram.CompileResult result = PlcProgram.compile("""
                AIN LEVEL SENSOR value
                OUT LEFT BUNDLED WEST 0
                OUT RIGHT BUNDLED WEST 1
                LATCH MODE SET LEVEL == 0 RESET LEVEL == 100
                RUNG LEFT = NOT MODE
                RUNG RIGHT = MODE
                """);
        require(result.successful(), "retained-state program compiles");
        FakeIo io = new FakeIo();
        io.analogInputs.put("LEVEL", 0.0);
        PlcProgram.Controller original = new PlcProgram.Controller();
        original.load(result.program());
        original.start();
        require(original.evaluate(io).success(), "latch is set before simulated chunk unload");

        PlcProgram.RuntimeState saved = original.runtimeState();
        PlcProgram.Controller reloaded = new PlcProgram.Controller();
        reloaded.load(result.program());
        reloaded.restoreRuntimeState(saved);
        reloaded.start();
        io.analogInputs.put("LEVEL", 50.0);
        PlcProgram.ScanResult scan = reloaded.evaluate(io);
        require(scan.success(), "controller evaluates after runtime restore");
        require(reloaded.latchValues().getOrDefault("MODE", false),
                "latch memory survives a Minecraft chunk/world reload");
        require(scan.desiredOutputs().entrySet().stream()
                        .anyMatch(entry -> entry.getKey().channel() == 1 && entry.getValue() == 15),
                "restored controller holds its previous mode between thresholds");
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
        PlcProgram.CompileResult remote = PlcProgram.compile("""
                AIN TANK SENSOR sensor-a1b2c3 value
                OUT PUMP REDSTONE EAST
                RUNG PUMP = TANK >= 100
                """);
        require(remote.successful(), "network sensor hostname and channel compile: " + remote.error());
        require("sensor-a1b2c3/value".equals(remote.program().inputs().get(0).side()),
                "network sensor binding retains hostname and channel");
        require(!PlcProgram.compile("OUT BAD SENSOR tank_level\nRUNG BAD = ON").successful(),
                "sensor bindings are rejected on outputs");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class FakeIo implements PlcProgram.Io {
        private final Map<String, Integer> inputs = new HashMap<>();
        private final Map<String, Double> analogInputs = new HashMap<>();
        private final Map<String, Integer> outputs = new HashMap<>();
        private boolean bothPumpStopsOff;
        private boolean failReads;

        @Override public int read(PlcProgram.Binding binding) {
            return failReads ? -1 : inputs.getOrDefault(binding.side().toUpperCase(), 0);
        }

        @Override public double readAnalog(PlcProgram.Binding binding) {
            if (failReads) return -1;
            return analogInputs.getOrDefault(binding.name(), (double) read(binding));
        }

        @Override public boolean write(PlcProgram.Binding binding, int strength) {
            outputs.put(binding.side().toUpperCase(), strength);
            if (binding.kind() == PlcProgram.BindingKind.BUNDLED) {
                outputs.put(binding.side().toUpperCase() + ":" + binding.channel(), strength);
                bothPumpStopsOff |= outputs.getOrDefault("WEST:0", 0) == 0
                        && outputs.getOrDefault("WEST:1", 0) == 0;
            }
            return true;
        }
    }
}
