# TerminalCraft 1.0.35

TerminalCraft 1.0.35 replaces the placeholder SSD item icons with custom storage hardware visuals.

## Solid-State Drive tiers

Each drive tier now has its own 16×16 pixel-art face texture and individually authored 3D item model:

- Basic: cyan and graphite casing with four contacts
- Advanced: violet and magenta casing with six contacts and a raised housing
- Quantum: emerald and obsidian casing with eight contacts and the largest housing profile

The textures are stored in `assets/terminalcraft/textures/item`, and the custom geometry is defined in
the three corresponding item model JSON files.

## Verification

- `./gradlew build --no-daemon`
- JSON/resource validation
- Texture dimension validation
- JAR archive integrity validation
- `git diff --check`

## Verification artifact

- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.35.jar`
- Size: `1,452,455` bytes
- SHA-256: `85fff40e5e3c6e223d39b32db958a6aefb8e65fe67ee4f03376b4642eacb3c7d`
