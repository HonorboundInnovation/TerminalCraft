# TerminalCraft 1.0.37

TerminalCraft 1.0.37 fixes the reported missing cable/modem item textures, the Redstone Sensor
crash, and router/PLC rendering artifacts.

## Fixes

- Restored valid inventory parents for the Wireless Modem, Network Cable, and Bundled Control Cable.
- Added a re-entrant Redstone Sensor probe guard. A sensor network that loops back into itself now
  returns a partial/unavailable reading instead of overflowing the server stack.
- Made failed sensor redstone output fail closed at level 0.
- Removed translucent router casing from the router, PLC, and Server Rack router model surfaces.
  Their side and casing faces now use opaque themed pixel-art textures.

## Verification

- `./gradlew build --no-daemon --console=plain`
- Model-parent and texture-reference validation
- JSON/resource validation
- JAR archive integrity validation
- `git diff --check`

## Verification artifact

- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.37.jar`
- Size: `1,494,763` bytes
- SHA-256: `78639cf5b97d5390ed4b79baa3d6e280cc809f25b271aa5d16667034502f5bc9`
