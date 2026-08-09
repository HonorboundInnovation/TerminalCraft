# TerminalCraft 1.0.33

TerminalCraft 1.0.33 is a rendering-stability correction for the individually modeled electronics
introduced in 1.0.32.

## Corrections

- Replaced glass and animated water texture references in the new block models with opaque materials.
- Removed confirmed coplanar faces between front panels and their block bases.
- Kept the distinct per-block geometry, palettes, sensor rotations, NAS slot details, and SSD tier
  visuals from the previous release.

## Verification

- `./gradlew build --no-daemon`
- JSON/resource validation
- JAR archive integrity validation
- `git diff --check`

## Verification artifact

- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.33.jar`
- Size: `1,447,258` bytes
- SHA-256: `7277dbab7909b303e6548c8a3f8a4059c847d2c0d46770c42a51554b107d3e1c`
