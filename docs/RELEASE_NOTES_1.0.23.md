# TerminalCraft 1.0.23

TerminalCraft 1.0.23 adds the first programmable logic controller implementation.

## Programmable Logic Controller

- Added a placeable PLC block with a terminal-style programming interface.
- Added bounded PLC source compilation: 128 lines, 16 KiB, 32 I/O bindings, and 64 logic rungs.
- Added `REDSTONE` and `BUNDLED` input/output bindings.
- Added scan intervals, boolean expressions, timers, rising-edge counters, and reset-dominant
  latches.
- Added `plc status`, `show`, `load`, `set`, `append`, `start`, `stop`, `reset`, and `clear`.
- Programs and labels persist with the PLC; compile faults and unavailable wiring fail safe by
  stopping execution and clearing outputs.
- Added headless compiler/runtime regression coverage.

The artifact produced by the release build is:

`terminalcraft-1.20.1-47.4.10-1.0.23.jar`

Size: `1,189,822` bytes

SHA-256: `8bd0255a0de27e30969188cab25272d7f5859a04965196bec2abae8db2362dd3`
