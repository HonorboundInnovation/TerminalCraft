# TerminalCraft 1.0.26

TerminalCraft 1.0.26 makes PLCs programmable in-world and makes monitor dashboards genuinely interactive.

## PLC programming interface

- Right-clicking a PLC opens a dedicated programming and commissioning screen instead of the generic terminal.
- Edit bounded PLC source with line numbers, cursor navigation, selection, clipboard, undo/redo, scrolling, and Ctrl+S compile/load.
- The programmer scales to the available Minecraft GUI viewport and switches to a compact single-column layout when needed.
- Run, stop, reset, acknowledge alarms, and save/load four program slots from the same interface.
- The live panel shows state, scan count, cycle interval, alarms, inputs, outputs, force markers, and compile/runtime faults.
- PLC actions remain server-authoritative and respect PLC ownership/operator permissions.

## PLC monitor dashboards

- Dashboards now have clearer operations/status headers, alarm indicators, separated I/O/control-logic sections, live-watch diagnostics, and wider touch hitboxes.
- Touch routing binds each wall to the source that rendered it, covering direct, wireless, and video-cable display paths.
- A monitor touch regression GameTest verifies that pressing the RUN control starts the attached PLC.

## Verification

- Full Gradle check and production build pass.
- Existing headless PLC, monitor, terminal, networking, persistence, and integration tests remain green.

The release artifact is:

`terminalcraft-1.20.1-47.4.10-1.0.26.jar`

Size: `1,271,994` bytes

SHA-256: `4339de25856ed52b2d6934e9bd97a7ea8564fc32496cacef0549d99b1eb4bf82`
