# TerminalCraft 1.0.32

TerminalCraft 1.0.32 is a visual asset correction release. The new electronics blocks no longer
share the same placeholder cube or router-casing texture.

## Individually modeled blocks

The following blocks now have dedicated geometry and material assignments:

- Universal Sensor Array
- Redstone, Block State, Inventory, Fluid, Energy, Entity, Machine, Environment, Network, Kinetic,
  and Chemical Sensors
- Network Router
- Programmable Logic Controller
- Wireless Display Link
- Applied Energistics Bridge
- Network Access Storage
- Materializer

Sensor blockstates also rotate the model with the block's facing direction. NAS drive items now use
distinct tier visuals for Basic, Advanced, and Quantum storage.

## Verification

- `./gradlew clean check --no-daemon`
- `./gradlew build --no-daemon`
- JSON/resource validation
- `git diff --check`

## Verification artifact

- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.32.jar`
- Size: `1,447,223` bytes
- SHA-256: `63dd4c5e5bef7d0bb90b8d40607492f37fe9c2e9dea230f00e993f235ca3ffe8`
