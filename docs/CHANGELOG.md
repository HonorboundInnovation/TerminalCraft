# TerminalCraft Changelog

All notable player-facing changes are recorded here. TerminalCraft build numbers increase with the
final component of the version: `1.0.17` is build 17, `1.0.18` is build 18, and so on.

## 1.0.19 — 2026-07-17

### Added

- Added named, typed RedNet monitor services for publishing bounded monitor operations from a
  pocket terminal or another networked computer.
- Added both requested receiver layouts: modem directly beside a monitor wall, and modem beside a
  terminal/turtle gateway that is beside the wall.
- Added `monitor service` registration/list/removal and `monitor remote` clear, write, set, title,
  and color operations.
- Added optional Create 6.0.8 Display Link targeting for complete TerminalCraft monitor walls.
- Added protocol characterization and live GameTests for both remote-monitor layouts.

### Safety and compatibility

- Monitor payloads are versioned, typed, bounded, non-executable, and consumed separately from
  ordinary modem receive traffic.
- A receiver resolves exactly one wall; ambiguous layouts fail closed.
- Monitor services are opt-in and persistent, but service names are routing aliases rather than
  authentication credentials. Reachable peers within the same RedNet trust boundary may publish.
- Create remains optional and is not bundled. The adapter is only loaded when Create is installed.

### Compatibility

- Minecraft: 1.20.1
- Minecraft Forge: 47.4.10
- Java: 17
- Create integration: 6.0.8 (optional)

### Verification

- The complete Java 17 headless check and production build pass: 71 actionable tasks with zero
  failures.
- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.19.jar`
- SHA-256: `355fdec621df63812a7f70426f681bb86b85fe86e8833cef9ea4242d325df3df`
- Direct and gateway world-behavior GameTests are packaged for in-game qualification; the existing
  standalone GameTest runner spawn-region stall remains a separate open gate.

## 1.0.18 — 2026-07-17

### Added

- Added bounded shell command substitution with `$(command)`.
- Added signed 64-bit arithmetic through `$((expression))`, `let NAME=EXPRESSION`, and
  `arith EXPRESSION`.
- Added numeric test operators: `-eq`, `-ne`, `-lt`, `-le`, `-gt`, and `-ge`.
- Added functional `break`, `continue`, and script-local `exit [status]` control flow.
- Added wrench inspection for red alloy wire and bundled cable. Right-clicking with a
  Forge/Common-tagged wrench, including Create's wrench, reports the selected face, connections,
  power, multipart occupancy, and active bundled channels as applicable.
- Added the advanced `production-cell-check.sh` example combining storage, wire, monitor, and
  bundled-output diagnostics.
- Added characterization coverage for the new shell behavior and wrench-recognition contract.

### Changed

- Updated the build and development runtime to Java 21.
- Updated the supported Forge baseline and packaged dependency range to Forge 47.4.10 through the
  47.x line.
- Runtime shell identity now reports the version from the packaged JAR manifest instead of a
  hard-coded development version.
- Single-quoted shell text is now literal; double-quoted text continues to allow expansion.
- Command substitutions preserve command side effects while capturing displayed output, with a
  four-level nesting limit and a 4,096-character output limit.
- Arithmetic is limited to 256-character expressions and 16 levels of nesting, with explicit
  overflow and division-by-zero failures.
- Updated the advanced automation examples to capture returned identifiers and use explicit loop
  and arithmetic control.
- Empty-hand wire interactions are no longer consumed, avoiding conflicts with Carry On and
  similar interaction mods.

### Fixed

- Fixed red alloy wire failing to render an arm toward adjacent terminals and other
  Forge-compatible redstone devices.
- Fixed red alloy wire notifying neighboring receivers while feedback suppression was still
  active, which could make lamps, repeaters, terminals, or other devices read zero instead of the
  newly computed wire power.
- Fixed multipart red alloy wire loading corrupt opposing-face combinations.
- Fixed removed red alloy wire faces retaining stale saved power.
- Fixed bundled cable loading shorter or missing signal arrays without clearing stale values.
- Fixed bundled cable loading corrupt opposing-face combinations.
- Fixed several direct headless tests failing under Java 21 because vanilla registry bootstrap was
  running without ModLauncher's Forge transformation environment.

### Compatibility

- Minecraft: 1.20.1
- Minecraft Forge: 47.4.10
- Java: 21
- Required on both client and server.
- Optional storage integrations remain absence-safe and are not bundled.

### Verification

- The complete Java 21 headless check and production build pass: 69 actionable tasks with zero
  failures.
- Corrective artifact:
  `terminalcraft-1.20.1-47.4.10-1.0.18.jar`
- SHA-256:
  `19a5d66fb212af46a75f1151e4990e64eb66557c811d2efb8e02133fe7c334f0`
- The standalone Forge GameTest runner still has a recorded spawn-region preparation stall before
  tests execute. Full-pack Java 21 startup passes; final P7.1 in-game requalification of the
  corrective wire build remains pending.

## 1.0.17

- Previous development build and baseline for the 1.0.18 consolidation work.
