# TerminalCraft 1.0.38

TerminalCraft 1.0.38 hardens world startup around the Redstone Sensor family and fixes the invalid
Network Sensor crafting ingredient reported by the latest instance log.

## Fixes

- Redstone Sensors no longer query another TerminalCraft Redstone Sensor as an input. This prevents
  sensor feedback graphs from entering vanilla redstone propagation while a world is loading.
- The re-entrant probe guard and clamped fail-closed output remain active for safety around other
  redstone-capable blocks.
- The Network Sensor recipe now consumes `terminalcraft:network_cable`.

## Verification

- `./gradlew build --no-daemon --console=plain`
- Model-parent and texture-reference validation
- Recipe identifier validation
- JSON/resource validation
- JAR archive integrity validation

## Verification artifact

- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.38.jar`
- Size: `1,494,812` bytes
- SHA-256: `8c7b0e05537918868e56d4ecd14b45ce3dafdee008a790d0ef10356debeac288`
