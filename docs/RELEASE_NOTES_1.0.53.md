# TerminalCraft 1.0.53 — Optional Create and Mekanism Automation

TerminalCraft 1.0.53 adds native, absence-safe Create 6.0.8 and Mekanism 10.4.16 adapters while
keeping both mods optional. Neither mod nor its libraries are bundled in the TerminalCraft JAR.

## Create

- Adjacent kinetic machines are discoverable even without standard Forge storage capabilities.
- Native device telemetry covers speed, direction, theoretical speed, source/network identity,
  stress impact/capacity, and overstress state.
- Stressometer, Threshold Switch, Redstone Link, and Sequenced Gearshift projections add their
  specialized telemetry and permission-gated safe controls.
- Native Kinetic and Machine sensor metrics feed the existing PLC and network-sensor paths.
- Existing Create Display Link support and fourteen Create PLC templates remain available.

## Mekanism and dynamic chemicals

- Adjacent Mekanism machines expose status, progress, exact native Joule containers, heat,
  redstone-control mode, supported substance families, and bounded security status.
- Authenticated redstone-mode changes require both TerminalCraft `device.write` and a Mekanism-public
  target. Private/trusted machines fail closed because TerminalCraft never impersonates an owner.
- Chemical telemetry now uses a provider-neutral runtime contract. Tank families and resource IDs
  come from live capabilities, so add-on namespaces are accepted without a static allowlist.
- `chemical.tanks` and `chemical.count` preserve exact long quantities as decimal strings; Chemical
  Sensors provide bounded numeric amount/capacity/fill/tank/presence signals.
- Added eight compile-checked Mekanism PLC starters for machines, ore lines, chemical refill, load
  shedding, factories, fission scram, turbines, and Digital Miners.

## Compatibility and verification

- Target: Minecraft 1.20.1, Forge 47.4.10, Create 6.0.8 when installed, Mekanism 10.4.16 when installed.
- Minimal installations continue without loading any Create or Mekanism adapter class.
- Verified artifact: `terminalcraft-1.20.1-47.4.10-1.0.53.jar`, 2,670,936 bytes,
  SHA-256 `f2c785a1819c379380cbc2b833fc28b2041e3d74cfae4828ad380368de7b0d78`.
