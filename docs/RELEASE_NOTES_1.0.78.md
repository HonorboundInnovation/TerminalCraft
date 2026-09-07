# TerminalCraft 1.0.78 — Turtle collection and item-aware placement

- Successful digging collects newly spawned nearby item drops into available inventory space. Existing loose items remain untouched; overflow stays in the world. Broken-container contents retain exact counts through partial collection.
- Placement uses the selected block item's placement routine instead of assigning a default block state. Automated context supports tested horizontal/vertical orientation, door support/upper-space checks, slab combining and shulker contents/name retention.
- Fixed recursion in the automatic placement context's slab replacement check. Rejected placement still preserves fuel and items.

All 85 headless checks and 117 required world tests passed. Approved textures and original CurseForge install/worlds are unchanged.

Development checkpoint: arbitrary custom block items requiring players, protection hooks, all multi-block boundaries, delayed/distant custom drops and full modpack qualification remain outstanding. This does not complete the release plan.
