package com.malice.terminalcraft.plc;

import java.util.List;

/** Compile-checked PLC starters for Create 6 redstone and kinetic components. */
final class CreatePlcProgramTemplates {
    private static final List<PlcProgramTemplates.Template> TEMPLATES = List.of(
            createTemplate("create-clutch-safety", "Fail-safe Create clutch starter",
                    "Runs a kinetic line through an inverted Clutch control with stop, E-stop, and permissive interlocks.", """
                            # Create Clutches transmit rotation while unpowered and stop while powered.
                            # Wire EAST through a redstone-torch inverter into the Clutch.
                            # RUN_ENABLE on removes Clutch power; a PLC stop/fault restores Clutch power.
                            SCAN 1
                            IN START REDSTONE NORTH
                            IN STOP REDSTONE SOUTH
                            IN ESTOP REDSTONE WEST
                            IN PERMISSIVE REDSTONE UP
                            OUT RUN_ENABLE REDSTONE EAST
                            LATCH RUN SET START RESET STOP OR ESTOP OR NOT PERMISSIVE
                            RUNG RUN_ENABLE = RUN AND PERMISSIVE AND NOT ESTOP
                            """),
            createTemplate("create-reversing-drive", "Clutch and Gearshift reversing drive",
                    "Controls a fail-safe Clutch and a Gearshift for manually selected forward/reverse operation.", """
                            # EAST goes through a redstone-torch inverter to the Clutch.
                            # DOWN drives the Gearshift directly: off=normal direction, on=reversed.
                            # Direction selection is latched only while the drive is stopped.
                            SCAN 1
                            IN START REDSTONE NORTH
                            IN STOP REDSTONE SOUTH
                            IN REVERSE_SELECT REDSTONE WEST
                            IN ESTOP REDSTONE UP
                            OUT RUN_ENABLE REDSTONE EAST
                            OUT GEAR_REVERSE REDSTONE DOWN
                            LATCH RUN SET START RESET STOP OR ESTOP
                            LATCH REVERSE_MODE SET REVERSE_SELECT AND NOT RUN RESET NOT REVERSE_SELECT AND NOT RUN
                            RUNG RUN_ENABLE = RUN AND NOT ESTOP
                            RUNG GEAR_REVERSE = REVERSE_MODE
                            """),
            createTemplate("create-chain-speed-control", "Adjustable Chain Gearshift control",
                    "Copies a 0..15 command signal to an Adjustable Chain Gearshift for analog ratio control.", """
                            # NORTH is the 0..15 speed-ratio command.
                            # EAST connects to the Adjustable Chain Gearshift redstone input.
                            # Signal 0 is 1x; signal 15 is 2x as an input or 0.5x as an output.
                            SCAN 2
                            AIN SPEED_COMMAND REDSTONE NORTH
                            AOUT GEAR_RATIO REDSTONE EAST
                            MOVE GEAR_RATIO = SPEED_COMMAND
                            """),
            createTemplate("create-speed-regulator", "Speedometer feedback regulator",
                    "Uses a Create Speedometer comparator signal to regulate an input-side Adjustable Chain Gearshift.", """
                            # WEST channel 0 carries the desired 0..15 Speedometer scale value.
                            # NORTH reads the comparator output of a Create Speedometer.
                            # EAST drives an Adjustable Chain Gearshift used on the input side.
                            # Tune KP/KI/KD for the inertia and ratio range of the kinetic network.
                            SCAN 2
                            AIN SPEED_SETPOINT BUNDLED WEST 0
                            AIN SPEED_FEEDBACK REDSTONE NORTH
                            AOUT SPEED_TRIM REDSTONE EAST
                            PID SPEED_LOOP SETPOINT SPEED_SETPOINT PROCESS SPEED_FEEDBACK OUTPUT SPEED_TRIM KP 1 KI 0.02 KD 0.1
                            """),
            createTemplate("create-belt-jam-stop", "Belt jam detector and stop",
                    "Stops a clutch-driven belt after a filtered Content Observer remains active for 60 ticks.", """
                            # WEST reads a Content Observer aimed at the watched belt position.
                            # EAST goes through a redstone-torch inverter to the belt Clutch.
                            # DOWN is a direct jam alarm output. RESET clears the latched jam.
                            SCAN 1
                            IN START REDSTONE NORTH
                            IN STOP REDSTONE SOUTH
                            IN ITEM_PRESENT REDSTONE WEST
                            IN RESET REDSTONE UP
                            OUT RUN_ENABLE REDSTONE EAST
                            OUT JAM_ALARM REDSTONE DOWN
                            TIMER JAM_DELAY 60 = ITEM_PRESENT AND RUN
                            LATCH JAM SET JAM_DELAY.DONE RESET RESET
                            LATCH RUN SET START RESET STOP OR JAM
                            RUNG RUN_ENABLE = RUN AND NOT JAM
                            RUNG JAM_ALARM = JAM
                            """),
            createTemplate("create-threshold-refill", "Threshold-controlled stock refill",
                    "Maintains stock between low/high inputs and fail-closes a Funnel or feeder through an inverter.", """
                            # Feed STOCK_LOW and STOCK_HIGH from configured Create Threshold Switches.
                            # EAST goes through a redstone-torch inverter to a Funnel or feeder Clutch.
                            # TRANSFER_ENABLE on opens/runs the feeder; PLC loss powers the Create device closed.
                            SCAN 2
                            IN STOCK_LOW REDSTONE NORTH
                            IN STOCK_HIGH REDSTONE SOUTH
                            IN PAUSE REDSTONE WEST
                            OUT TRANSFER_ENABLE REDSTONE EAST
                            LATCH REFILL SET STOCK_LOW RESET STOCK_HIGH OR PAUSE
                            RUNG TRANSFER_ENABLE = REFILL AND NOT PAUSE
                            """),
            createTemplate("create-sequenced-gearshift", "Sequenced Gearshift trigger",
                    "Converts a held cycle request into a bounded pulse for a preconfigured Sequenced Gearshift.", """
                            # Configure the movement sequence on the Create Sequenced Gearshift first.
                            # NORTH may be held; EAST emits only the first four ticks of the request.
                            # Release CYCLE before requesting another sequence.
                            SCAN 1
                            IN CYCLE REDSTONE NORTH
                            IN INHIBIT REDSTONE SOUTH
                            OUT SEQUENCE_TRIGGER REDSTONE EAST
                            TIMER TRIGGER_PULSE 4 = CYCLE AND NOT INHIBIT
                            RUNG SEQUENCE_TRIGGER = CYCLE AND NOT TRIGGER_PULSE.DONE AND NOT INHIBIT
                            """),
            createTemplate("create-deployer-workcell", "Timed Deployer workcell",
                    "Runs a clutch-fed Deployer for a tunable work window while a part and tool are present.", """
                            # NORTH comes from a Content Observer detecting the workpiece.
                            # SOUTH is a tool/material permissive; EAST uses an inverter into the Clutch.
                            # Adjust WORK_WINDOW for the Deployer RPM and recipe animation.
                            SCAN 1
                            IN PART_PRESENT REDSTONE NORTH
                            IN TOOL_READY REDSTONE SOUTH
                            IN ESTOP REDSTONE WEST
                            OUT RUN_ENABLE REDSTONE EAST
                            TIMER WORK_WINDOW 40 = PART_PRESENT AND TOOL_READY AND NOT ESTOP
                            RUNG RUN_ENABLE = WORK_WINDOW.ACTIVE AND NOT WORK_WINDOW.DONE AND NOT ESTOP
                            """),
            createTemplate("create-mixer-batch", "Basin mixer batch sequence",
                    "Runs a Mixer for a fixed batch time, then opens a fail-closed output Funnel when discharge is clear.", """
                            # EAST uses a redstone-torch inverter into the Mixer drive Clutch.
                            # DOWN uses another inverter into the output Funnel: enabled means unpowered/open.
                            # BASIN_READY can come from a filtered observer; OUTPUT_CLEAR is a permissive.
                            SCAN 1
                            IN START REDSTONE NORTH
                            IN BASIN_READY REDSTONE SOUTH
                            IN OUTPUT_CLEAR REDSTONE WEST
                            IN ESTOP REDSTONE UP
                            OUT MIXER_ENABLE REDSTONE EAST
                            OUT DISCHARGE_ENABLE REDSTONE DOWN
                            LATCH BATCH SET START AND BASIN_READY RESET NOT BASIN_READY OR ESTOP
                            TIMER MIX_TIME 100 = BATCH AND BASIN_READY AND NOT ESTOP
                            RUNG MIXER_ENABLE = BATCH AND NOT MIX_TIME.DONE AND NOT ESTOP
                            RUNG DISCHARGE_ENABLE = BATCH AND MIX_TIME.DONE AND OUTPUT_CLEAR AND NOT ESTOP
                            """),
            createTemplate("create-crushing-line", "Staged crushing line startup",
                    "Starts the downstream belt before enabling the upstream crusher feeder and stops both on a jam.", """
                            # EAST and DOWN use torch-inverted Clutch controls for fail-safe stopping.
                            # Start downstream first so material has somewhere to go before feed begins.
                            SCAN 1
                            IN START REDSTONE NORTH
                            IN STOP REDSTONE SOUTH
                            IN JAM REDSTONE WEST
                            IN ESTOP REDSTONE UP
                            OUT DOWNSTREAM_ENABLE REDSTONE EAST
                            OUT UPSTREAM_ENABLE REDSTONE DOWN
                            LATCH LINE_RUN SET START RESET STOP OR JAM OR ESTOP
                            TIMER STARTUP_DELAY 40 = LINE_RUN AND NOT JAM AND NOT ESTOP
                            RUNG DOWNSTREAM_ENABLE = LINE_RUN AND NOT JAM AND NOT ESTOP
                            RUNG UPSTREAM_ENABLE = LINE_RUN AND STARTUP_DELAY.DONE AND NOT JAM AND NOT ESTOP
                            """),
            createTemplate("create-contraption-indexer", "Redstone Contact contraption indexer",
                    "Counts four Redstone Contact arrivals, then stops a clutch-driven indexed contraption.", """
                            # SOUTH receives pulses from a Create Redstone Contact at each index.
                            # EAST uses a redstone-torch inverter into the drive Clutch.
                            # This is a one-shot batch; use plc reset before starting another four indexes.
                            SCAN 1
                            IN START REDSTONE NORTH
                            IN CONTACT REDSTONE SOUTH
                            IN ESTOP REDSTONE WEST
                            OUT DRIVE_ENABLE REDSTONE EAST
                            OUT COMPLETE REDSTONE DOWN
                            COUNTER INDEX_COUNT 4 = CONTACT AND RUN
                            LATCH RUN SET START RESET INDEX_COUNT.DONE OR ESTOP
                            RUNG DRIVE_ENABLE = RUN AND NOT INDEX_COUNT.DONE AND NOT ESTOP
                            RUNG COMPLETE = INDEX_COUNT.DONE
                            """),
            createTemplate("create-portable-interface-dock", "Portable Storage Interface dock",
                    "Stops a moving contraption at a contact, waits for cargo readiness, releases the interface, and departs.", """
                            # NORTH is the docking Redstone Contact; SOUTH means cargo transfer is complete.
                            # EAST uses an inverter into the drive Clutch; DOWN powers the PSI to disconnect it.
                            # DEPART must stay on through the short release delay.
                            SCAN 1
                            IN DOCKED REDSTONE NORTH
                            IN CARGO_READY REDSTONE SOUTH
                            IN DEPART REDSTONE WEST
                            IN ESTOP REDSTONE UP
                            OUT DRIVE_ENABLE REDSTONE EAST
                            OUT PSI_RELEASE REDSTONE DOWN
                            TIMER RELEASE_DELAY 20 = DOCKED AND CARGO_READY AND DEPART AND NOT ESTOP
                            RUNG PSI_RELEASE = RELEASE_DELAY.DONE AND NOT ESTOP
                            RUNG DRIVE_ENABLE = (NOT DOCKED OR RELEASE_DELAY.DONE) AND NOT ESTOP
                            """),
            createTemplate("create-elevator-two-stop", "Two-stop pulley or gantry controller",
                    "Controls direction and a fail-safe Clutch for a two-stop Rope Pulley, Gantry, or piston lift.", """
                            # WEST bundled channels: 0 call up, 1 call down, 2 top, 3 bottom, 4 E-stop.
                            # EAST uses a torch inverter into the Clutch; DOWN drives the Gearshift.
                            # If both calls arrive together, UP has deterministic priority.
                            SCAN 1
                            IN CALL_UP BUNDLED WEST 0
                            IN CALL_DOWN BUNDLED WEST 1
                            IN TOP_LIMIT BUNDLED WEST 2
                            IN BOTTOM_LIMIT BUNDLED WEST 3
                            IN ESTOP BUNDLED WEST 4
                            OUT RUN_ENABLE REDSTONE EAST
                            OUT GEAR_REVERSE REDSTONE DOWN
                            LATCH UP_RUN SET CALL_UP AND NOT DOWN_RUN RESET TOP_LIMIT OR ESTOP
                            LATCH DOWN_RUN SET CALL_DOWN AND NOT UP_RUN RESET BOTTOM_LIMIT OR ESTOP
                            RUNG RUN_ENABLE = ((UP_RUN AND NOT TOP_LIMIT) OR (DOWN_RUN AND NOT BOTTOM_LIMIT)) AND NOT ESTOP
                            RUNG GEAR_REVERSE = DOWN_RUN
                            """),
            createTemplate("create-item-batch-counter", "Content Observer item batch",
                    "Passes items until a filtered Funnel observer reports sixteen transfers, then closes the feed.", """
                            # SOUTH receives one pulse per transfer from a Content Observer watching a Funnel.
                            # EAST uses a torch inverter into the feed Funnel so a PLC fault closes it.
                            # This is a one-shot batch; use plc reset before starting the next batch.
                            SCAN 1
                            IN START REDSTONE NORTH
                            IN ITEM_PULSE REDSTONE SOUTH
                            IN ESTOP REDSTONE WEST
                            OUT TRANSFER_ENABLE REDSTONE EAST
                            OUT BATCH_COMPLETE REDSTONE DOWN
                            COUNTER BATCH_COUNT 16 = ITEM_PULSE AND RUN
                            LATCH RUN SET START RESET BATCH_COUNT.DONE OR ESTOP
                            RUNG TRANSFER_ENABLE = RUN AND NOT BATCH_COUNT.DONE AND NOT ESTOP
                            RUNG BATCH_COMPLETE = BATCH_COUNT.DONE
                            """)
    );

    private CreatePlcProgramTemplates() {}

    static List<PlcProgramTemplates.Template> all() { return TEMPLATES; }

    private static PlcProgramTemplates.Template createTemplate(
            String id, String title, String description, String source) {
        return PlcProgramTemplates.template("create", id, title, description, source);
    }
}
