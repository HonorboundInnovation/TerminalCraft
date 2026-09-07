# TerminalCraft 1.0.76 — Turtle inventory and furnace fuel

- Sixteen persistent slots with Forge item-handler access; `turtle inventory`, `select <1-16>`, `fuel`, and `refuel [1-64]` commands.
- New turtles start empty with zero finite fuel. One coal supplies 80 actions; lava supplies 1,000 and returns its bucket. Invalid refueling does not consume items.
- Legacy single-stack saves migrate to slot 1 and retain their saved fuel mode and balance.
- Movement transfers all slots without dropping duplicates; normal removal drops contents once. Removed handlers cannot extract transferred items.
- Inventory loading fixes slot count at sixteen and bounds malformed persisted entries.

Verification: all 85 headless checks and all 109 required world tests passed, followed by successful gated packaging.

This remains a development checkpoint. Dedicated inventory-slot UI, automatic mined-item collection, full block-item placement, save-lifecycle and multiplayer qualification remain incomplete. The guide documents available automation access and migration behavior. Approved textures and the user's CurseForge install/worlds are unchanged.
