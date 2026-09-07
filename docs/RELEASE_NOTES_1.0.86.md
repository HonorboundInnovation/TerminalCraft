# TerminalCraft 1.0.86 — Storage lifecycle and capacity

- Removed NAS and Materializer instances refuse transfers through cached automation references; NAS revival restores valid access.
- Amount-only NAS/Materializer fluid draining preserves tags and keeps variants separate.
- Malformed SSD fluid quantities are bounded by tier capacity and entry limits to prevent overflow in capacity calculations.
- Added exact-capacity, excess rejection and real compressed save/reload tests for every SSD tier with one and eight drives. The largest fixture verifies 2,097,152 items and 8,192,000 mB without loss or duplication.

All 85 headless checks and 131 required world tests passed. Full-variety network payloads, natural unload/restart, dedicated multiplayer and broader completion-plan gates remain open.

Development build only; not installed or published. Prior no-downgrade requirements for chunked files and SSD integer counts remain in force. Original CurseForge worlds/install and approved textures are unchanged.
