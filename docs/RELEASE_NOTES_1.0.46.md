# TerminalCraft 1.0.46

Build 46 is a focused visual-correctness update following the Device Control Center release.

## Fixes

- The inset PLC cabinet no longer culls adjacent blocks as though it were a visually solid cube.
  Neighboring faces now remain rendered when a PLC is placed beside any block.
- Closing the Control Center now reconstructs the normal shell surface with the shell's foreground
  and background colors on every cell, removing colored bars and selection remnants.
- The terminal remembers whether it was in log or surface mode before opening the Control Center and
  restores that view when the program closes.

## Compatibility

- Minecraft: 1.20.1
- Forge: 47.4.10
- TerminalCraft: 1.0.46

## Verified artifact

- File: `terminalcraft-1.20.1-47.4.10-1.0.46.jar`
- Size: `1,559,383` bytes
- SHA-256: `bcbb45192b3f7e6597a6c6e898478d26023c1305b5c82566132b6c29657df6b5`
