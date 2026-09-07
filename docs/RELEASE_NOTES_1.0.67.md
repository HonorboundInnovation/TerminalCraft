# TerminalCraft 1.0.67 — Development startup and test-harness repairs

This is a diagnostic development build; full release qualification remains open.

- Red Alloy wire no longer duplicates signal power in its blockstate. This reduces its precomputed state table from 393,216 to 24,576 entries. Actual output still reads the independently persisted power of each wire run.
- Chunk-load network discovery is now queued even when the chunk event runs on the server thread. This prevents discovery from immediately requesting a chunk whose loading callback has not completed.
- Restored missing GameTest structures for PLC redstone, network monitor publication, Red Alloy capacitors, and the optional AE2 bridge profile.
- Added an in-world migration/round-trip test for wire color, mounting faces, insulation, routes and per-run power, including the original `FacePower` save format.
- Added neighbor-shape notifications for block-entity signal changes, with an observer regression test for rising and falling signals.
- Corrected directional redstone input queries so an observer does not feed power back into the wire it watches.
- Aligned terminal redstone outputs with vanilla's receiver-to-source queries and corrected the repeater fixture to check both orientations.
- Removed an accidental GameTest annotation from a private bundled-cable fixture helper and corrected the wire/lamp test to allow vanilla's four-tick lamp shutoff delay.

## Compatibility

Wire registry IDs and block-entity NBT layouts are unchanged. Existing blockstate palettes load while ignoring the removed `power` field; valid `RunPower` and legacy `FacePower` data continue to load and circuits reconcile on world load. Missing/corrupt run data starts unpowered until reconciliation.

Commands or external code that directly inspect or assign `red_alloy_wire[power=…]` must use wire diagnostics or actual circuit signals instead. Face, lane, color and connection blockstate fields are retained. The shell command interface is unchanged.

## Qualification

The original baseline has a failing cable texture contract and the newly executable world tests expose additional routing, monitor and redstone failures. This build is not a claim that those systems or the full modpack are complete.

Verification: 84/85 headless tasks and 66/90 live GameTests pass. Wire save migration, observer rise/fall, both repeater orientations, terminal-to-wire output, and wire-to-lamp output pass. The overall verification gate remains failed; the JAR is packaged only for diagnostic use and has not replaced the owner's CurseForge installation.
