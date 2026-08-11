# TerminalCraft 1.0.47

Build 47 makes the Device Control Center's keyboard and text-entry workflows reliable in large
modpacks and documents them directly inside the terminal.

## Fixes

- Every Control Center action now synchronizes its authoritative state directly to the player with
  the open terminal. F2 DNS naming, prompt activation, Enter submission, refresh, navigation, and
  close no longer depend on delayed placed-block updates.
- `Enter` on Overview opens the selected device's Methods pane. Press `Enter` again to run the
  selected method; methods with parameters open their text prompt.
- `N` is an alternate DNS-name key and `R` is an alternate refresh key when a modpack captures
  `F2` or `F5`.
- General terminal help lists `control`, `devmgr`, and `setup`. Run `help control`, `help setup`, or
  `control --help` for the complete Control Center reference.

## Controls

- Arrow keys or mouse wheel: navigate devices and details
- Left / Right: move between device and detail panes
- Tab / Shift+Tab: switch Overview, Methods, and PLC Templates
- Enter: open Methods, run a method, load a template, or submit active text
- F2 or N: assign a DNS name
- F5 or R: refresh discovery and live state
- Escape: cancel text entry or close the program

## Compatibility

- Minecraft: 1.20.1
- Forge: 47.4.10
- TerminalCraft: 1.0.47

## Verified artifact

- File: `terminalcraft-1.20.1-47.4.10-1.0.47.jar`
- Size: `1,560,309` bytes
- SHA-256: `ee2835037c0dbe03dabc0d3ffa5a82cbaa2d10ff487922112b77c3f4ad3eceac`
