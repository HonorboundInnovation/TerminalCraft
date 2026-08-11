package com.malice.terminalcraft.plc;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Built-in, compile-checked starter programs for common PLC control patterns. */
public final class PlcProgramTemplates {
    private static final List<Template> GENERAL_TEMPLATES = List.of(
            template("motor-start-stop", "Latched motor start/stop",
                    "Seal-in motor control with stop and emergency-stop interlocks.", """
                            # Basic three-wire motor starter.
                            SCAN 1
                            IN START REDSTONE NORTH
                            IN STOP REDSTONE SOUTH
                            IN ESTOP REDSTONE WEST
                            OUT MOTOR REDSTONE EAST
                            LATCH RUN SET START RESET STOP OR ESTOP
                            RUNG MOTOR = RUN AND NOT ESTOP
                            """),
            template("motor-start-delay", "Delayed motor start",
                    "Starts a motor only after the start command has been held for 20 ticks.", """
                            # Debounced/delayed start with a hard stop.
                            SCAN 1
                            IN START REDSTONE NORTH
                            IN STOP REDSTONE SOUTH
                            OUT MOTOR REDSTONE EAST
                            TIMER START_DELAY 20 = START AND NOT STOP
                            RUNG MOTOR = START_DELAY.DONE AND NOT STOP
                            """),
            template("conveyor-interlock", "Conveyor safety interlock",
                    "Runs a conveyor while the guard is closed and the jam input is clear.", """
                            # Guard and jam inputs are fail-safe permissives.
                            SCAN 1
                            IN START REDSTONE NORTH
                            IN STOP REDSTONE SOUTH
                            IN JAM REDSTONE WEST
                            IN GUARD REDSTONE UP
                            OUT CONVEYOR REDSTONE EAST
                            OUT FAULT BUNDLED DOWN 0
                            LATCH RUN SET START RESET STOP OR JAM OR GUARD
                            RUNG CONVEYOR = RUN AND NOT JAM AND NOT GUARD
                            RUNG FAULT = JAM OR GUARD
                            """),
            template("pump-level-switch", "Pump level controller",
                    "Latches a pump on at LOW and off at HIGH or emergency stop.", """
                            # Use LOW and HIGH float switches on the two inputs.
                            SCAN 1
                            IN LOW REDSTONE NORTH
                            IN HIGH REDSTONE SOUTH
                            IN ESTOP REDSTONE WEST
                            OUT PUMP REDSTONE EAST
                            LATCH DEMAND SET LOW RESET HIGH OR ESTOP
                            RUNG PUMP = DEMAND AND NOT ESTOP
                            """),
            template("alarm-latch", "Acknowledged alarm latch",
                    "Holds an alarm until an acknowledge input is received.", """
                            # TRIP turns the alarm on; ACK clears it.
                            SCAN 1
                            IN TRIP REDSTONE NORTH
                            IN ACK REDSTONE SOUTH
                            OUT ALARM REDSTONE EAST
                            LATCH ALARM SET TRIP RESET ACK
                            RUNG ALARM = ALARM
                            """),
            template("batch-counter", "Batch counter",
                    "Turns on COMPLETE after ten rising edges on the PULSE input.", """
                            # Counters count rising edges, not held-high scans.
                            SCAN 1
                            IN PULSE REDSTONE NORTH
                            OUT COMPLETE REDSTONE EAST
                            COUNTER BATCH 10 = PULSE
                            RUNG COMPLETE = BATCH.DONE
                            """),
            template("bundled-gate", "Bundled channel gate",
                    "Routes an enable/trip pair from bundled inputs to load and alarm outputs.", """
                            # Channels are numbered 0..15 on each bundled face.
                            SCAN 1
                            IN ENABLE BUNDLED WEST 0
                            IN TRIP BUNDLED WEST 1
                            OUT LOAD BUNDLED EAST 0
                            OUT ALARM BUNDLED EAST 1
                            LATCH RUN SET ENABLE RESET TRIP
                            RUNG LOAD = RUN AND NOT TRIP
                            RUNG ALARM = TRIP
                            """),
            template("sensor-scale", "Sensor analog scaler",
                    "Copies a calibrated sensor value to a 0..15 analog redstone output.", """
                            # Sensor channel 'value' is the standard calibrated reading.
                            SCAN 2
                            AIN LEVEL SENSOR value
                            AOUT VALVE REDSTONE EAST
                            SCALE VALVE = LEVEL 0 15 0 15
                            """),
            template("sensor-valve-bundled", "Sensor to bundled valve",
                    "Maps a sensor reading onto a bundled cable channel.", """
                            # Change the output face/channel to match the machine.
                            SCAN 2
                            AIN LEVEL SENSOR value
                            AOUT VALVE BUNDLED EAST 3
                            SCALE VALVE = LEVEL 0 15 0 15
                            """),
            template("pid-temperature", "PID temperature controller",
                    "A bounded proportional controller for a 0..15 temperature signal.", """
                            # Tune KP/KI/KD for the attached machine response.
                            SCAN 1
                            AIN TEMP SENSOR value
                            AOUT HEATER REDSTONE EAST
                            PID TEMP_LOOP SETPOINT 12 PROCESS TEMP OUTPUT HEATER KP 2 KI 0.1 KD 0.2
                            """),
            template("dual-pump-duty", "Dual pump duty controller",
                    "Uses two analog pumps with complementary scaled output levels.", """
                            # Both outputs track the same calibrated demand signal.
                            SCAN 2
                            AIN DEMAND SENSOR value
                            AOUT PUMP_A REDSTONE EAST
                            AOUT PUMP_B BUNDLED WEST 4
                            SCALE PUMP_A = DEMAND 0 15 0 15
                            MOVE PUMP_B = DEMAND
                            """),
            template("maintenance-permissive", "Maintenance permissive",
                    "Enables a machine only when start, guard, and maintenance inputs allow it.", """
                            # MAINT must be low for the machine to run.
                            SCAN 1
                            IN START REDSTONE NORTH
                            IN STOP REDSTONE SOUTH
                            IN GUARD REDSTONE WEST
                            IN MAINT REDSTONE UP
                            OUT MACHINE REDSTONE EAST
                            LATCH ENABLE SET START RESET STOP OR MAINT OR NOT GUARD
                            RUNG MACHINE = ENABLE AND NOT MAINT AND GUARD
                            """),
            template("watchdog-alarm", "Watchdog alarm",
                    "Raises an alarm when a heartbeat input is absent after a bounded delay.", """
                            # HEARTBEAT should pulse regularly; inspect ALARM on the dashboard.
                            SCAN 1
                            IN HEARTBEAT REDSTONE NORTH
                            IN RESET REDSTONE SOUTH
                            OUT ALARM REDSTONE EAST
                            TIMER HEARTBEAT_TIMER 40 = NOT HEARTBEAT
                            LATCH ALARM_LATCH SET HEARTBEAT_TIMER.DONE RESET RESET
                            RUNG ALARM = ALARM_LATCH
                            """)
    );
    private static final List<Template> TEMPLATES = combine(
            combine(combine(GENERAL_TEMPLATES, CreatePlcProgramTemplates.all()),
                    MekanismPlcProgramTemplates.all()),
            SecurityCraftPlcProgramTemplates.all());

    private PlcProgramTemplates() {}

    public static List<Template> all() { return TEMPLATES; }

    public static List<String> categories() {
        return TEMPLATES.stream().map(Template::category).distinct().toList();
    }

    public static List<Template> byCategory(String category) {
        if (category == null || category.isBlank()) return all();
        String normalized = category.trim().toLowerCase(Locale.ROOT);
        return TEMPLATES.stream().filter(template -> template.category().equals(normalized)).toList();
    }

    public static Optional<Template> find(String id) {
        if (id == null) return Optional.empty();
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        return TEMPLATES.stream().filter(template -> template.id().equals(normalized)).findFirst();
    }

    private static Template template(String id, String title, String description, String source) {
        return template("general", id, title, description, source);
    }

    static Template template(String category, String id, String title, String description, String source) {
        return new Template(category, id, title, description, source);
    }

    private static List<Template> combine(List<Template> first, List<Template> second) {
        java.util.ArrayList<Template> combined = new java.util.ArrayList<>(first.size() + second.size());
        combined.addAll(first);
        combined.addAll(second);
        return List.copyOf(combined);
    }

    private static String normalize(String source) {
        String value = source == null ? "" : source.stripIndent().strip();
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    public record Template(String category, String id, String title, String description, String source) {
        public Template {
            category = category == null ? "general" : category.trim().toLowerCase(Locale.ROOT);
            if (category.isEmpty()) category = "general";
            id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
            title = title == null ? "" : title.trim();
            description = description == null ? "" : description.trim();
            source = normalize(source);
        }

        public Template(String id, String title, String description, String source) {
            this("general", id, title, description, source);
        }
    }
}
