# TerminalCraft 1.0.18

TerminalCraft 1.0.18 is a consolidation build for Minecraft 1.20.1, Forge 47.4.10, and Java 21. It strengthens the compact-wiring lifecycle and makes the Bash-inspired shell substantially more useful for real automation scripts.

## Highlights

- Standardizes development and runtime execution on Java 21 and reports the packaged mod version at runtime instead of a hard-coded shell version.
- Adds wrench inspection for red alloy wire and bundled cable, including mounted faces, selected-face state, connectivity, power, and active bundled channels. Forge/Common-tagged wrenches such as Create's wrench are recognized without claiming empty-hand interactions from Carry On.
- Makes red alloy wire render connections to adjacent Forge-compatible redstone devices rather than only to other wire segments.
- Hardens multipart wire persistence against stale arrays, corrupt opposing faces, and power retained on missing parts.
- Adds bounded command substitution with `$(command)`, arithmetic expansion with `$((expression))`, and arithmetic assignment with `let NAME=EXPR`.
- Adds numeric test operators, script-local `exit`, and functional `break` and `continue` control flow.
- Corrects single-quoted strings so they remain literal while double-quoted strings continue to expand variables and substitutions.
- Refreshes the advanced example library to capture command results and use explicit arithmetic and loop control.
- Adds `production-cell-check.sh`, a combined storage, wire, monitor, and bundled-output production check.

## Shell safety bounds

The new language features are intentionally bounded for in-game execution:

- arithmetic expressions: 256 characters and 16 levels of nesting;
- signed 64-bit arithmetic with overflow and division-by-zero errors;
- command substitution: 4 nested substitutions and 4,096 captured output characters;
- substitutions retain command side effects but capture their displayed output.

TerminalCraft remains a Bash-inspired shell rather than GNU Bash. Process spawning, background jobs, unrestricted host filesystem access, and unbounded evaluation are not provided.

## Compatibility

| Component | Version |
|---|---|
| Minecraft | 1.20.1 |
| Minecraft Forge | 47.4.10 |
| Java | 21 |
| TerminalCraft | 1.0.18 |

The production artifact is `terminalcraft-1.20.1-47.4.10-1.0.18.jar`.

## Verification note

The complete headless `check` suite and production `build` pass on Java 21. The Forge GameTest server loads TerminalCraft 1.0.18 successfully under Forge 47.4.10, but this workspace still encounters the previously recorded spawn-region preparation stall before the GameTests execute. Live GameTest qualification therefore remains an explicit follow-up rather than an asserted pass for this build.
