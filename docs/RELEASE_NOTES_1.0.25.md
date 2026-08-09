# TerminalCraft 1.0.25

TerminalCraft 1.0.25 expands the display and PLC systems into an operator-ready automation layer.

## Display diagnostics

- Right-click Wireless Display Links for a configuration GUI with role, channel, attachment, pairing,
  and clear controls.
- Right-click Video Cable for local routing diagnostics. Branches work as splitters and the component
  remains bounded to 2,048 nodes.
- Display frame failures are contained and recorded in runtime diagnostics instead of propagating
  into the server tick.

## PLC operations

- Added `plc watch` for live inputs, outputs, timers, counters, latches, and forces.
- Added `plc force input|output` for bounded commissioning overrides.
- Added alarm history and acknowledgement commands.
- Added dashboard pages for overview, live watch, and program/fault history.
- Added four persisted program slots for rollback.
- PLC ownership is assigned at placement; owners and server operators can control dashboards.

## Monitor and terminal usability

- Added bounded text widgets: `monitor bar`, `monitor led`, and `monitor spark`.
- Added `Ctrl+R` history search and `Ctrl+L` command-line clearing.
- Active PLCs participate in the existing opt-in, quota-bounded chunk-loading policy.

The release artifact is:

`terminalcraft-1.20.1-47.4.10-1.0.25.jar`

Size: `1,250,470` bytes

SHA-256: `4749cecefef071351e719d241f9e084513b32d6fc8029ae6c53a2623a84cccc2`
