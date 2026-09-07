# TerminalCraft 1.0.84 — Missing survival drops

- Added missing self-drop loot tables for the sensor array, eleven standalone sensors, NAS and Materializer.
- Completed pickaxe mining tags across the 31 registered blocks.
- Added an in-world regression checking loaded mining tags, iron-pickaxe eligibility and exactly one matching drop from each of the fourteen repaired blocks.

All 85 headless checks and 124 required world tests passed. This covers block items, not full storage-content recovery, explosion behavior or end-to-end survival progression.

Development checkpoint only; broader completion-plan gates remain open. Original CurseForge install/worlds and approved textures are unchanged. Network protocol remains 7; the chunked-file no-downgrade requirement still applies.
