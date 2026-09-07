# TerminalCraft 1.0.73 — Turtle movement state safety

Development checkpoint; the overall completion plan remains in progress. Approved textures are unchanged.

- Movement validates world limits, loaded destinations and available fuel before placement. It retains the source computer until the destination entity exists and its saved state loads successfully.
- Failed destination validation restores the replaced destination block/fluid without charging movement fuel or deleting the source computer's files, items or identity.
- Successful moves transfer the live shell to the new host, allowing consecutive movement commands and subsequent file writes to continue on the same computer. Fuel is charged once per successful move.
- Added in-world regressions for rejected placement, a destination with no turtle entity, complete saved-state transfer, and a two-move command chain followed by a file write.

Verification: all 85 headless checks and all 97 required in-world tests passed; development JAR built successfully. Not installed into the user's CurseForge instance.

This checkpoint does not establish full turtle inventory, digging/placement, multiplayer screen continuity, protection-mod compatibility or release acceptance.
