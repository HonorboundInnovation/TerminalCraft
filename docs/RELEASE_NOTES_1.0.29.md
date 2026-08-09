# TerminalCraft 1.0.29

TerminalCraft 1.0.29 adds the first provider-neutral sensor subsystem for cross-mod automation.

## Universal Sensor Arrays

- Added a placeable Universal Sensor Array with 16 persistent, named channels.
- Added bounded probes for redstone, block state, inventory, fluid, Forge Energy, entities,
  machine state, environment, TerminalCraft networking, and generic kinetic block-state signals.
- Added explicit quality states so unloaded chunks, absent capabilities, partial reads, and unsupported
  chemical data fail closed instead of becoming misleading automation values.
- Added terminal commands for configuration, sampling, calibration, enable/disable, intervals, and
  channel removal.
- Added the `terminalcraft:sensor_array` device endpoint with read, snapshot, configuration, and
  event surfaces. A value change publishes `sensor_changed` with bounded metadata.
- Added PLC `IN`/`AIN <name> SENSOR <channel>` bindings. Sensor values are calibrated to 0-15 and
  unavailable samples stop the PLC safely.
- Added typed RedNet Sensor Array services through adjacent modems:
  `modem sensor add`, `remove`, `list`, and `request`.

## Compatibility and scope

The sensor engine deliberately uses vanilla state and standard Forge capabilities. Create, Mekanism,
and other mod-specific internals are not guessed or accessed through private APIs; channels report
`unsupported` until a future optional adapter can expose a stable contract.

Headless coverage includes sensor calibration/quality bounds and PLC sensor-binding compilation.

## Verification artifact

- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.29.jar`
- Size: `1,368,718` bytes
- SHA-256: `1a759b68b9c138ccc8c50cf8f502c3663741c50cfd3d04596f16e96389c1f34a`
