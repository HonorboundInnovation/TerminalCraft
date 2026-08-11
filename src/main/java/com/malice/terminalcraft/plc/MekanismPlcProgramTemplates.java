package com.malice.terminalcraft.plc;

import java.util.List;

/** Compile-checked PLC starters for Mekanism's standard redstone control and comparator surfaces. */
final class MekanismPlcProgramTemplates {
    private static final List<PlcProgramTemplates.Template> TEMPLATES = List.of(
            template("mekanism-machine-starter", "Mekanism machine start/stop",
                    "Latched HIGH-mode machine control with stop, E-stop, and process permissive.", """
                            # Configure the Mekanism machine Redstone Control to HIGH.
                            # EAST drives the machine directly: signal present means enabled.
                            SCAN 1
                            IN START REDSTONE NORTH
                            IN STOP REDSTONE SOUTH
                            IN ESTOP REDSTONE WEST
                            IN PERMISSIVE REDSTONE UP
                            OUT MACHINE_ENABLE REDSTONE EAST
                            LATCH RUN SET START RESET STOP OR ESTOP OR NOT PERMISSIVE
                            RUNG MACHINE_ENABLE = RUN AND PERMISSIVE AND NOT ESTOP
                            """),
            template("mekanism-ore-line", "Mekanism ore-processing line",
                    "Starts downstream processing before upstream feed and stops the complete line on a fault.", """
                            # Configure every controlled machine for HIGH redstone mode.
                            # EAST is downstream processing; DOWN is the upstream feeder.
                            SCAN 1
                            IN START REDSTONE NORTH
                            IN STOP REDSTONE SOUTH
                            IN LINE_FAULT REDSTONE WEST
                            IN ESTOP REDSTONE UP
                            OUT DOWNSTREAM_ENABLE REDSTONE EAST
                            OUT FEED_ENABLE REDSTONE DOWN
                            LATCH LINE_RUN SET START RESET STOP OR LINE_FAULT OR ESTOP
                            TIMER PRIME_DELAY 40 = LINE_RUN AND NOT LINE_FAULT AND NOT ESTOP
                            RUNG DOWNSTREAM_ENABLE = LINE_RUN AND NOT LINE_FAULT AND NOT ESTOP
                            RUNG FEED_ENABLE = LINE_RUN AND PRIME_DELAY.DONE AND NOT LINE_FAULT AND NOT ESTOP
                            """),
            template("mekanism-chemical-refill", "Mekanism chemical refill",
                    "Maintains a chemical buffer between calibrated low and high sensor contacts.", """
                            # Configure Chemical Sensor channels for the actual runtime resource ID.
                            # The selector may name any add-on chemical exposed by a registered provider.
                            SCAN 2
                            IN CHEMICAL_LOW REDSTONE NORTH
                            IN CHEMICAL_HIGH REDSTONE SOUTH
                            IN SUPPLY_READY REDSTONE WEST
                            IN ESTOP REDSTONE UP
                            OUT REFILL_ENABLE REDSTONE EAST
                            LATCH REFILL SET CHEMICAL_LOW RESET CHEMICAL_HIGH OR ESTOP OR NOT SUPPLY_READY
                            RUNG REFILL_ENABLE = REFILL AND SUPPLY_READY AND NOT ESTOP
                            """),
            template("mekanism-energy-load-shed", "Mekanism energy load shed",
                    "Drops nonessential HIGH-mode loads on low reserve and restores them after recovery.", """
                            # Feed LOW_RESERVE and RESERVE_RECOVERED from calibrated Energy Sensors.
                            # EAST controls essential loads; DOWN controls nonessential loads.
                            SCAN 2
                            IN MASTER_ENABLE REDSTONE NORTH
                            IN LOW_RESERVE REDSTONE SOUTH
                            IN RESERVE_RECOVERED REDSTONE WEST
                            IN ESTOP REDSTONE UP
                            OUT ESSENTIAL_LOADS REDSTONE EAST
                            OUT NONESSENTIAL_LOADS REDSTONE DOWN
                            LATCH SHED SET LOW_RESERVE RESET RESERVE_RECOVERED
                            RUNG ESSENTIAL_LOADS = MASTER_ENABLE AND NOT ESTOP
                            RUNG NONESSENTIAL_LOADS = MASTER_ENABLE AND NOT SHED AND NOT ESTOP
                            """),
            template("mekanism-factory-jam", "Mekanism factory jam trip",
                    "Latches a jam after a bounded blocked-output dwell and stops feed before the factory.", """
                            # OUTPUT_BLOCKED may come from an Inventory Sensor watching the output side.
                            # EAST controls the factory; DOWN controls the feeder, both in HIGH mode.
                            SCAN 1
                            IN START REDSTONE NORTH
                            IN STOP REDSTONE SOUTH
                            IN OUTPUT_BLOCKED REDSTONE WEST
                            IN RESET REDSTONE UP
                            OUT FACTORY_ENABLE REDSTONE EAST
                            OUT FEED_ENABLE REDSTONE DOWN
                            TIMER JAM_DELAY 80 = OUTPUT_BLOCKED AND RUN
                            LATCH JAM SET JAM_DELAY.DONE RESET RESET
                            LATCH RUN SET START RESET STOP OR JAM
                            RUNG FACTORY_ENABLE = RUN AND NOT JAM
                            RUNG FEED_ENABLE = RUN AND NOT OUTPUT_BLOCKED AND NOT JAM
                            """),
            template("mekanism-fission-scram", "Mekanism fission reactor scram",
                    "Reset-dominant reactor enable with coolant, waste, temperature, and operator trips.", """
                            # Use fail-safe sensor contacts: each permissive is on only while healthy.
                            # Configure the reactor logic adapter or control surface for the intended input.
                            SCAN 1
                            IN ARM REDSTONE NORTH
                            IN MANUAL_SCRAM REDSTONE SOUTH
                            IN COOLANT_OK BUNDLED WEST 0
                            IN WASTE_OK BUNDLED WEST 1
                            IN TEMPERATURE_OK BUNDLED WEST 2
                            IN CONTAINMENT_OK BUNDLED WEST 3
                            OUT REACTOR_ENABLE REDSTONE EAST
                            OUT SCRAM_ALARM REDSTONE DOWN
                            LATCH RUN SET ARM AND COOLANT_OK AND WASTE_OK AND TEMPERATURE_OK AND CONTAINMENT_OK RESET MANUAL_SCRAM OR NOT COOLANT_OK OR NOT WASTE_OK OR NOT TEMPERATURE_OK OR NOT CONTAINMENT_OK
                            RUNG REACTOR_ENABLE = RUN AND COOLANT_OK AND WASTE_OK AND TEMPERATURE_OK AND CONTAINMENT_OK
                            RUNG SCRAM_ALARM = MANUAL_SCRAM OR NOT COOLANT_OK OR NOT WASTE_OK OR NOT TEMPERATURE_OK OR NOT CONTAINMENT_OK
                            """),
            template("mekanism-turbine-trip", "Mekanism turbine-generator trip",
                    "Interlocks turbine admission with condenser, storage, and overspeed permissives.", """
                            # Configure sensor outputs for the real multiblock thresholds before commissioning.
                            SCAN 1
                            IN START REDSTONE NORTH
                            IN STOP REDSTONE SOUTH
                            IN CONDENSER_OK BUNDLED WEST 0
                            IN ENERGY_SPACE BUNDLED WEST 1
                            IN SPEED_OK BUNDLED WEST 2
                            IN ESTOP BUNDLED WEST 3
                            OUT ADMISSION_ENABLE REDSTONE EAST
                            OUT TRIP_ALARM REDSTONE DOWN
                            LATCH RUN SET START AND CONDENSER_OK AND ENERGY_SPACE AND SPEED_OK RESET STOP OR ESTOP OR NOT CONDENSER_OK OR NOT ENERGY_SPACE OR NOT SPEED_OK
                            RUNG ADMISSION_ENABLE = RUN AND CONDENSER_OK AND ENERGY_SPACE AND SPEED_OK AND NOT ESTOP
                            RUNG TRIP_ALARM = ESTOP OR NOT CONDENSER_OK OR NOT SPEED_OK
                            """),
            template("mekanism-digital-miner-window", "Mekanism Digital Miner schedule",
                    "Runs a HIGH-mode Digital Miner only inside an authorization and storage window.", """
                            # STORAGE_SPACE should fail low when the destination cannot accept more items.
                            # AUTHORIZED_WINDOW may come from a schedule script, key switch, or SCADA command.
                            SCAN 2
                            IN AUTHORIZED_WINDOW REDSTONE NORTH
                            IN STORAGE_SPACE REDSTONE SOUTH
                            IN POWER_OK REDSTONE WEST
                            IN ESTOP REDSTONE UP
                            OUT MINER_ENABLE REDSTONE EAST
                            OUT ATTENTION REDSTONE DOWN
                            RUNG MINER_ENABLE = AUTHORIZED_WINDOW AND STORAGE_SPACE AND POWER_OK AND NOT ESTOP
                            RUNG ATTENTION = ESTOP OR NOT STORAGE_SPACE OR NOT POWER_OK
                            """)
    );

    private MekanismPlcProgramTemplates() {}

    static List<PlcProgramTemplates.Template> all() { return TEMPLATES; }

    private static PlcProgramTemplates.Template template(
            String id, String title, String description, String source) {
        return PlcProgramTemplates.template("mekanism", id, title, description, source);
    }
}
