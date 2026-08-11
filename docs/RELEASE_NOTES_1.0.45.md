# TerminalCraft 1.0.45

Build 45 adds a graphical, in-terminal Device Control Center for setup and commissioning.

## Control Center

Run `control`, `devmgr`, or `setup` from any terminal. The full-screen program discovers devices
through the authenticated Device API and provides overview, methods, and PLC template tabs.

- Navigate with arrows, mouse wheel, tab clicks, or `Tab`/`Shift+Tab`.
- Use `Left`/`Right` to choose the device list or detail pane and `Enter` to activate.
- Press `F2` to assign a durable human-friendly DNS alias.
- Press `F5` to refresh discovery and live device state.
- Press `Escape` to cancel text entry or return to the normal shell.

Advertised device methods with parameters open a typed prompt. General and Create PLC templates can
be loaded into a selected remote PLC through `program.set`, subject to the PLC's existing ownership
checks. All navigation and mutation actions are bounded, server-authoritative, and tied to the open,
authenticated terminal menu.

## Compatibility

- Minecraft: 1.20.1
- Forge: 47.4.10
- TerminalCraft: 1.0.45

## Verified artifact

- File: `terminalcraft-1.20.1-47.4.10-1.0.45.jar`
- Size: `1,559,159` bytes
- SHA-256: `6ef7c9736aba73d449322ee98d97dcc7223b9e284c7992fe57017b7f722971fc`
