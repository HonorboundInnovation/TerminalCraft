# TerminalCraft 1.0.74 — Turtle action resource accounting

- Rejected digging no longer spends fuel.
- Rejected placement now reports failure and preserves fuel and selected items.
- Successful finite-mode actions spend one fuel; successful placement also spends one selected item.
- Dig/place calls from removed turtles or targeting unloaded, out-of-bounds or out-of-border positions are rejected before mutation.
- Added six in-world tests for rejection, success and exhausted fuel, asserting exact saved state and target results.

Verification: all 85 headless checks and all 103 required in-world tests passed. Approved textures are unchanged.

This is a development checkpoint. The guide now explicitly identifies unfinished inventory/refueling and full block-item placement semantics. Unlimited-mode behavior is unchanged. Multiplayer GUI continuity and protection-mod compatibility remain unqualified.
