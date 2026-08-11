package com.malice.terminalcraft.plc;

import java.util.List;

/** Compile-checked PLC starters for SecurityCraft's redstone-facing security devices. */
final class SecurityCraftPlcProgramTemplates {
    private static final List<PlcProgramTemplates.Template> TEMPLATES = List.of(
            template("securitycraft-perimeter-alarm", "Latched perimeter alarm",
                    "Latches any SecurityCraft perimeter detector and holds an Alarm until acknowledged safely.", """
                            # Feed ZONE_TRIP from a Laser, Retinal Scanner, Username Logger, or detector.
                            # EAST drives a SecurityCraft Alarm through redstone.
                            SCAN 1
                            IN ZONE_TRIP REDSTONE NORTH
                            IN ACKNOWLEDGE REDSTONE SOUTH
                            IN SYSTEM_ARMED REDSTONE WEST
                            OUT ALARM REDSTONE EAST
                            LATCH INTRUSION SET ZONE_TRIP AND SYSTEM_ARMED RESET ACKNOWLEDGE AND NOT ZONE_TRIP
                            RUNG ALARM = INTRUSION
                            """),
            template("securitycraft-laser-zone", "Laser-zone entry interlock",
                    "Requires a valid access pulse before a Laser Block trip; unauthorized entry latches lockdown.", """
                            # VALID_ACCESS should pulse from the authorized reader path before the laser is crossed.
                            SCAN 1
                            IN LASER_TRIP REDSTONE NORTH
                            IN VALID_ACCESS REDSTONE SOUTH
                            IN RESET REDSTONE WEST
                            OUT DOOR_ENABLE REDSTONE EAST
                            OUT LOCKDOWN_ALARM REDSTONE DOWN
                            TIMER ACCESS_WINDOW 80 = VALID_ACCESS
                            LATCH BREACH SET LASER_TRIP AND NOT ACCESS_WINDOW.DONE RESET RESET AND NOT LASER_TRIP
                            RUNG DOOR_ENABLE = ACCESS_WINDOW.DONE AND NOT BREACH
                            RUNG LOCKDOWN_ALARM = BREACH
                            """),
            template("securitycraft-inventory-checkpoint", "Inventory Scanner checkpoint",
                    "Coordinates an Inventory Scanner, release door, and alarm with a fail-safe scan window.", """
                            # SCAN_CLEAR is on only while the Inventory Scanner finds no prohibited item.
                            SCAN 1
                            IN ENTRY_REQUEST REDSTONE NORTH
                            IN SCAN_CLEAR REDSTONE SOUTH
                            IN ESTOP REDSTONE WEST
                            OUT RELEASE_DOOR REDSTONE EAST
                            OUT CONTRABAND_ALARM REDSTONE DOWN
                            TIMER VERIFY 20 = ENTRY_REQUEST AND SCAN_CLEAR AND NOT ESTOP
                            RUNG RELEASE_DOOR = VERIFY.DONE AND SCAN_CLEAR AND NOT ESTOP
                            RUNG CONTRABAND_ALARM = ENTRY_REQUEST AND NOT SCAN_CLEAR
                            """),
            template("securitycraft-panic-lockdown", "Panic-button lockdown",
                    "Drops every fail-safe release and latches alarms when a SecurityCraft Panic Button is pressed.", """
                            # Use normally-low LOCKDOWN; all controlled doors should fail closed when power is removed.
                            SCAN 1
                            IN PANIC REDSTONE NORTH
                            IN RESET REDSTONE SOUTH
                            IN PERIMETER_CLEAR REDSTONE WEST
                            OUT ACCESS_RELEASE REDSTONE EAST
                            OUT ALARM REDSTONE DOWN
                            LATCH LOCKDOWN SET PANIC RESET RESET AND PERIMETER_CLEAR
                            RUNG ACCESS_RELEASE = NOT LOCKDOWN AND PERIMETER_CLEAR
                            RUNG ALARM = LOCKDOWN
                            """),
            template("securitycraft-camera-zone", "Camera-zone alarm correlation",
                    "Correlates camera-area motion with an armed state and delays nuisance-free alarm reset.", """
                            # MOTION may come from a Motion-Activated Light or Username Logger redstone module.
                            SCAN 2
                            IN MOTION REDSTONE NORTH
                            IN ARMED REDSTONE SOUTH
                            IN ACKNOWLEDGE REDSTONE WEST
                            OUT CAMERA_ZONE_ALARM REDSTONE EAST
                            TIMER CONFIRM 10 = MOTION AND ARMED
                            TIMER CLEAR_DWELL 60 = NOT MOTION
                            LATCH EVENT SET CONFIRM.DONE RESET ACKNOWLEDGE AND CLEAR_DWELL.DONE
                            RUNG CAMERA_ZONE_ALARM = EVENT
                            """),
            template("securitycraft-two-door-airlock", "Two-door secure airlock",
                    "Prevents both SecurityCraft doors from being released simultaneously and enforces a transfer dwell.", """
                            SCAN 1
                            IN OUTER_REQUEST REDSTONE NORTH
                            IN INNER_REQUEST REDSTONE SOUTH
                            IN CHAMBER_CLEAR REDSTONE WEST
                            IN ESTOP REDSTONE UP
                            OUT OUTER_RELEASE REDSTONE EAST
                            OUT INNER_RELEASE REDSTONE DOWN
                            TIMER TRANSFER_DWELL 40 = OUTER_REQUEST AND CHAMBER_CLEAR AND NOT INNER_REQUEST
                            RUNG OUTER_RELEASE = OUTER_REQUEST AND CHAMBER_CLEAR AND NOT INNER_REQUEST AND NOT ESTOP
                            RUNG INNER_RELEASE = INNER_REQUEST AND CHAMBER_CLEAR AND NOT OUTER_REQUEST AND TRANSFER_DWELL.DONE AND NOT ESTOP
                            """),
            template("securitycraft-rift-lockdown", "Rift Stabilizer containment",
                    "Enables containment alarms when teleport activity occurs outside an authorization window.", """
                            # TELEPORT_EVENT comes from the Rift Stabilizer redstone output.
                            SCAN 1
                            IN TELEPORT_EVENT REDSTONE NORTH
                            IN AUTHORIZED_WINDOW REDSTONE SOUTH
                            IN RESET REDSTONE WEST
                            OUT CONTAINMENT_ALARM REDSTONE EAST
                            OUT LOCKDOWN REDSTONE DOWN
                            LATCH RIFT_BREACH SET TELEPORT_EVENT AND NOT AUTHORIZED_WINDOW RESET RESET AND NOT TELEPORT_EVENT
                            RUNG CONTAINMENT_ALARM = RIFT_BREACH
                            RUNG LOCKDOWN = RIFT_BREACH
                            """),
            template("securitycraft-trophy-defense", "Trophy System defense monitor",
                    "Raises a degraded-defense alarm if a protected-zone projectile signal persists.", """
                            SCAN 1
                            IN PROJECTILE_DETECTED REDSTONE NORTH
                            IN DEFENSE_READY REDSTONE SOUTH
                            IN RESET REDSTONE WEST
                            OUT DEFENSE_ALARM REDSTONE EAST
                            OUT SECONDARY_BARRIER REDSTONE DOWN
                            TIMER SUSTAINED_ATTACK 20 = PROJECTILE_DETECTED
                            LATCH DEGRADED SET SUSTAINED_ATTACK.DONE OR NOT DEFENSE_READY RESET RESET AND DEFENSE_READY
                            RUNG DEFENSE_ALARM = DEGRADED
                            RUNG SECONDARY_BARRIER = DEGRADED
                            """),
            template("securitycraft-secure-redstone-backhaul", "Secure Redstone Interface backhaul",
                    "Multiplexes four alarm zones onto fixed bundled channels for a secure wireless backhaul pair.", """
                            # Connect outputs to separate Secure Redstone Interface senders or a bundled breakout.
                            SCAN 1
                            IN ZONE_A BUNDLED NORTH 0
                            IN ZONE_B BUNDLED NORTH 1
                            IN ZONE_C BUNDLED NORTH 2
                            IN ZONE_D BUNDLED NORTH 3
                            OUT BACKHAUL_A BUNDLED EAST 0
                            OUT BACKHAUL_B BUNDLED EAST 1
                            OUT BACKHAUL_C BUNDLED EAST 2
                            OUT BACKHAUL_D BUNDLED EAST 3
                            RUNG BACKHAUL_A = ZONE_A
                            RUNG BACKHAUL_B = ZONE_B
                            RUNG BACKHAUL_C = ZONE_C
                            RUNG BACKHAUL_D = ZONE_D
                            """),
            template("securitycraft-redundant-perimeter", "Two-out-of-three perimeter vote",
                    "Uses three independent SecurityCraft sensors to reject a single nuisance trip while preserving detection.", """
                            SCAN 1
                            IN LASER REDSTONE NORTH
                            IN RETINAL REDSTONE SOUTH
                            IN LOGGER REDSTONE WEST
                            IN RESET REDSTONE UP
                            OUT VERIFIED_ALARM REDSTONE EAST
                            OUT SENSOR_DISAGREEMENT REDSTONE DOWN
                            LATCH VERIFIED SET LASER AND RETINAL OR LASER AND LOGGER OR RETINAL AND LOGGER RESET RESET AND NOT LASER AND NOT RETINAL AND NOT LOGGER
                            RUNG VERIFIED_ALARM = VERIFIED
                            RUNG SENSOR_DISAGREEMENT = (LASER OR RETINAL OR LOGGER) AND NOT VERIFIED
                            """)
    );

    private SecurityCraftPlcProgramTemplates() {}

    static List<PlcProgramTemplates.Template> all() { return TEMPLATES; }

    private static PlcProgramTemplates.Template template(
            String id, String title, String description, String source) {
        return PlcProgramTemplates.template("securitycraft", id, title, description, source);
    }
}
