# TerminalCraft 1.0.88 — development pocket lifecycle checkpoint

Not a release candidate. Back up the entire world before testing. This build has not been installed into the CurseForge instance.

- Legacy pockets in saved player and ender inventories migrate before normal login synchronization.
- Vanilla container interaction migrates eligible pockets before initial menu contents are sent, including both halves of double chests. Existing cancellations, denied block use, locks and pending loot tables are respected. Failed migration keeps the container closed and retains originals.
- Loose legacy pockets migrate before entity tracking on the server thread. Failure does not cancel the spawn or delete a drop already removed from its source inventory.
- These paths also handle pockets inside vanilla shulker-box items, with bounded nesting and preservation of unrelated contents and metadata.
- Nested migration validates the entire supported tree before writing descendant records. Malformed/future data and unencodable NBT strings or keys cannot leave earlier descendant records behind through a predictable validation failure.
- World-backed storage, operator export/import and editor recovery protections from 1.0.87 remain included. Network protocol remains 8. Approved textures are unchanged.

Verification: all 85 headless checks and 144 required world tests passed, followed by successful assembly. The JAR's version, migration classes and exact bundled guide were verified; all 73 texture files match 1.0.87 byte-for-byte. SHA-256: `82994c019d6fc8199d12ff75accaf217c264418959c288f9ecd276b6ea5ca9bc`.

## Compatibility and remaining work

Migrated pockets need the world's data directory. Do not downgrade them to 1.0.86 or older. Archive export/import remains the supported operator-mediated portable-copy workflow; preserve whole-world backups as well.

Mod-specific containers/capabilities, off-thread producers, remotely opened menus, live first-packet acceptance, creative/death lifecycle cases, malformed-data quarantine/recovery and mid-write multi-file recovery remain open. Pending loot-table containers are intentionally not unpacked by preflight. A real IO failure during nested migration can leave unused verified recovery records; automatic deletion is not implemented.

The completion plan remains in Milestone 2. This checkpoint is not full core, visual, integration, multiplayer or release acceptance.
