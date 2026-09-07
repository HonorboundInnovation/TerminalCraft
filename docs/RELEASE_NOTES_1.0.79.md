# TerminalCraft 1.0.79 — Saved computer pickup recovery

- Normal terminal/turtle drops preserve computer identity, files, label and settings through a small world-backed recovery reference. Turtle fuel survives; inventory drops separately without being restored twice.
- Recovered computer items are non-stackable and must be placed in their original world. Backups must include that world's data directory. Missing or used references reject placement without consuming the item.
- A ticket is consumed only after accepted restoration, preventing another placement from a copied ticket. Blocked placement retains the usable item.
- Large shell payloads stay in server SavedData, not item packets. The test with forty 60,000-character files saves/reads the actual data file and restores every file, while the dropped item's packet remains below 512 bytes.

All 85 headless checks and 120 required world tests passed. This is not an atomic crash-recovery system or full player/creative/multiplayer qualification. The guide documents world-bound recovery and backup requirements. No CurseForge installation or texture change was made.
