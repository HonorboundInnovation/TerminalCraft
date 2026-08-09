# TerminalCraft 1.0.27

TerminalCraft 1.0.27 completes the PLC feature pass: analog control, PID loops, visual ladder editing,
trend history, and remote owner-authorized programming are now integrated with the existing bounded
server-authoritative runtime.

## PLC runtime

- `AIN` and `AOUT` expose 0–15 analog values over redstone or bundled cable.
- `MOVE` transfers an analog signal and `SCALE` performs bounded linear range conversion.
- `PID` adds a discrete proportional/integral/derivative loop with clamped integral and output state.
- Analog input overrides, analog output values, PID values, and scan trends are visible through the
  PLC watch surfaces.

## PLC interfaces

- Press `F7` in the PLC programmer to switch between the source editor and ladder workspace.
- Drag signals onto ladder rungs to add contacts and drag rungs to reorder the control sequence.
- The monitor dashboard adds an oscilloscope/trend page with up to 64 samples per signal.
- PLCs expose authenticated device-network methods for status, program chunks, run/stop/reset,
  I/O, and trend retrieval.
- `plc remote open <x> <y> <z>` opens the same programmer screen from an authorized terminal.

## Compatibility and safety

- Existing boolean rungs, timers, counters, latches, program slots, monitor dashboards, and shell
  commands remain compatible.
- Program writes and remote control calls remain owner-bound; malformed or unavailable PLC I/O
  fails safe and clears driven outputs.

## Verification

- `./gradlew clean check build` passes (80 actionable tasks).
- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.27.jar`
- Size: `1,297,505` bytes
- SHA-256: `6d1bc6dca54ea7c2a92b2964ad94ca1349e15099a8a5968e1d54b5e63847e93f`
