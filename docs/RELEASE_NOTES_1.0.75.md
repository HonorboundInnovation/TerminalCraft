# TerminalCraft 1.0.75 — Mobile turtle screen sessions

- An open turtle menu follows the exact live shell and computer identity after movement, instead of retaining the old position.
- Menu validity checks the actual live block entity, world and distance. A replacement block cannot revive a stale menu, even if its saved identity was copied.
- Commands and editor saves send bounded shell snapshots directly to valid turtle viewers, so a client screen bound before movement continues receiving results.
- Added in-world tests for two viewers, subsequent commands, distance rejection and turtle/terminal replacement.

All 85 headless checks and 105 required world tests passed. An isolated single-player client retained its open screen through two moves, wrote/read a file afterward, and reopened the moved computer with history intact. This does not replace a real two-client multiplayer or full modpack qualification.

Approved textures are unchanged; no installation into the user's CurseForge profile was performed. Inventory/refueling and full placement behavior remain unfinished.
