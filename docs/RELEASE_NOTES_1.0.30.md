# TerminalCraft 1.0.30

TerminalCraft 1.0.30 adds dedicated placeable sensors for every provider-neutral sensor family.

## Individual sensor blocks

- Added Redstone, Block State, Inventory, Fluid, Energy, Entity, Machine, Environment, Network,
  Kinetic, and Chemical Sensor blocks.
- Each block samples in the direction of its facing face and exposes one persistent `value` channel.
- Metrics, selectors, interval, enable state, and PLC calibration remain configurable through the
  adjacent terminal or device endpoint.
- Each sensor provides calibrated 0-15 redstone output and can feed an adjacent PLC with:
  `AIN LEVEL SENSOR value`.
- Modems can publish an individual sensor with the existing typed RedNet sensor service.
- The Chemical Sensor is intentionally present as a clear capability boundary and reports
  `unsupported` until a generic chemical capability is available.

## Universal Sensor Array compatibility

The Universal Sensor Array remains the preferred option for installations that need multiple named
channels at one location. The individual blocks are additive and share its quality-aware, bounded,
provider-neutral probe implementation.

## Verification

- `./gradlew clean check --no-daemon`
- `./gradlew build --no-daemon`
- `./gradlew sensorModelTest --no-daemon`

## Verification artifact

- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.30.jar`
- Size: `1,402,383` bytes
- SHA-256: `feb643e81ec94c9596fbb623f4f26f218539f61714f6d7617946bf399ebde7ec`
