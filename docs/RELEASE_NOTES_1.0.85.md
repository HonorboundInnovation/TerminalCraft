# TerminalCraft 1.0.85 — NAS quantity and recovery safety

- Fixed NAS item and fluid extraction returning the stored quantity plus the removed quantity, duplicating resources. Simulated and executed extraction now report exact amounts.
- Bulk SSD item counts now persist as integers instead of overflowing Minecraft's byte-sized stack count.
- NAS loading retains exactly eight physical drive slots and rejects invalid media, duplicate/out-of-range entries and oversized drive stacks.
- Added world tests for labeled SSD recovery with items and fluid, exact extraction after reinsertion, large-count persistence and malformed slot data.

All 85 headless checks and 127 required world tests passed. Packaged development checkpoint only; no installation or publication.

Do not downgrade SSDs/worlds saved with the new item-count encoding. Older builds cannot read those quantities correctly. Previously corrupted quantities cannot be recovered automatically. Dedicated restart/crash, full-capacity network payloads and broader completion gates remain open. Approved textures and original CurseForge worlds are unchanged.
