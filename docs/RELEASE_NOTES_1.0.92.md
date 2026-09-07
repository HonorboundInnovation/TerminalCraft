# TerminalCraft 1.0.92 — connected automation checkpoint

Development build, not a release candidate. Back up the whole world before testing.

- `device list [filter]` finds devices by type, name, UUID, address or capability before applying the display limit. Reports when discovery/display limits are reached.
- `plc remote load <name|uuid> <file>` transfers a complete multiline program from the terminal filesystem through the existing authorized device API. Remote source retains the 4,096-character device limit; loading does not start the controller.
- Network Cable now attaches to terminals, PLCs, Sensor Arrays and standalone sensors, matching the topology's supported endpoints and visible cable arms.
- PLC run commands report failure when a latched fault or empty program prevents starting. Recovery remains explicit: restore inputs, reset runtime, then run.
- The bundled guide includes a complete wired sensor-to-PLC-to-lamp commissioning example.

Protocol remains 8. No new registry IDs, saved-data format or texture changes are intended. Existing world-data backup and downgrade restrictions remain. This package is not full multiplayer, integration, performance or release qualification; Milestone 3 remains active and deferred Milestone 2 checks remain open.

Verification: all 85 baseline headless checks and 160 required world tests passed with assembly (exit 0, 2m 21s). Version, four updated production classes and exact bundled guide verified; all 73 textures match 1.0.91 byte-for-byte. SHA-256: `7ed716225c816c53272cd8996bc6100e344364b1152c07db824361107f4de0f8`. No client/server launch or CurseForge installation of this package was performed.
