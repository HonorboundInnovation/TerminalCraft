# TerminalCraft 1.0.89 — development pocket recovery checkpoint

Not a release candidate. Back up the entire world before testing. No installation into the CurseForge instance is part of this checkpoint.

- Operator command `/tcpocket recover-original <computer-UUID>` copies a verified migration-time snapshot into a blank pocket with a fresh identity. Current source files and backups are unchanged.
- Operator command `/tcpocket recover-files <computer-UUID>` copies individually readable current files into a blank pocket, with a report of skipped entries. Recovery never publishes truncated fragments; shell/editor/session state is reset. Unknown formats, unreadable records and unsent edits are not recovered.
- Advanced pocket tooltips show the computer UUID used by recovery commands.
- Opening a damaged pocket refuses a writable session when loading would discard, truncate or normalize saved filesystem entries. The raw record remains available for recovery.
- Retained player and ender inventories migrate during the clone/restore callback before later vanilla respawn synchronization.
- Automated coverage now includes actual server inventory dropping and pickup, actual ServerPlayer death/restore routines with both keepInventory settings, original-snapshot recovery, malformed-session save refusal and independent readable-file recovery.
- Network protocol remains 8. All earlier lifecycle, data-integrity and automation repairs remain included. Approved textures are unchanged.

## Compatibility and remaining work

Verification: all 85 baseline headless checks and 150 required world tests passed with successful assembly. Packaged version, recovery classes and exact bundled guide were verified; all 73 textures match 1.0.88 byte-for-byte. SHA-256: `a2a259aac79b43af24636d52ef83a3aff7109eb3333820da91ae937fd5a2e0d9`. This is archive inspection, not packaged-JAR runtime acceptance.

Migrated pockets require their world's data directory. Preserve whole-world backups and use export/import for portable copies. Do not downgrade migrated pocket data to 1.0.86 or older.

Recovery is deliberately conservative and bounded, not exhaustive repair. Report reservation, capacity limits, unsupported entries and parent conflicts may leave files only in the original. Live recovery command/report acceptance, full PlayerList respawn/network negotiation, mod-specific death retention, recovery pressure/fault injection, malformed-payload quarantine and block-computer recovery remain open.

Milestone 2 is still active. This package does not establish full core, model/UI, optional integration, survival, multiplayer, performance or release acceptance.
