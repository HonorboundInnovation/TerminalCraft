# TerminalCraft 1.0.59

TerminalCraft 1.0.59 fixes surface cable placement so the visible 4×4 lattice is also the real
placement grid. One Shielded Red Alloy Wire or Network Cable item now fills only the exact point
under the crosshair. It no longer fills an entire row.

Build a route point-by-point: four horizontal points form a row, four vertical points form a column,
and four diagonally adjacent points form a corner-to-corner diagonal. Short turns use only the points
you place. Matching colors connect across adjacent points and continue through the corresponding edge
point of the neighboring block; different colors remain isolated.

Existing 1.0.58 cable data is migrated automatically. Its saved rows and turns are expanded into
individual points so existing builds retain their occupied paths.

The placement preview, selection and removal shapes, drops, wrench diagnostics, bundled-network
breakouts, tooltips, and in-game Guide Book now use the same exact-point model.

Artifact: `terminalcraft-1.20.1-47.4.10-1.0.59.jar`
