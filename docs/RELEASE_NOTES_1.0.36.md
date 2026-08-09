# TerminalCraft 1.0.36

TerminalCraft 1.0.36 completes a cohesive custom pixel-art asset pass for the electronics, sensor,
storage, bridge, and cable families.

## Visual coverage

- Main electronics: Terminal, Monitor, Modem, Network Router, Programmable Logic Controller, Disk
  Drive, Turtle, and Server Rack.
- Storage and integration hardware: Network Access Storage, Materializer, Wireless Display Link,
  Refined Storage Bridge, and Applied Energistics Bridge.
- Sensors: Universal Sensor Array plus Redstone, Block State, Inventory, Fluid, Energy, Entity,
  Machine, Environment, Network, Kinetic, and Chemical Sensors.
- Routed cables: Red Alloy Wire, Network Cable, Bundled Cable, and Video Cable, including their
  directional, junction, and inventory model variants.

The new 16×16 textures use a shared graphite chassis language with cyan, violet, amber, green, and
red/copper signal accents. The generated art was cropped into deterministic pixel-art tiles and
bound through the existing model families. Cable model geometry and connection routing were kept
unchanged.

## Verification

- `./gradlew build --no-daemon --console=plain`
- JSON/resource reference validation
- 16×16 texture dimension validation
- JAR archive integrity validation
- `git diff --check`

## Verification artifact

- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.36.jar`
- Size: `1,494,292` bytes
- SHA-256: `a5dd03e24def89f6dfc4d68471726a48bccbece73d478d84557955abf22147f5`
