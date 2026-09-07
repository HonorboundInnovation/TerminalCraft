# TerminalCraft 1.0.90 — development placed-computer recovery checkpoint

Not a release candidate. Back up the entire world before testing. No CurseForge installation is part of this checkpoint.

- Terminals, turtles and racks preserve complete raw shell snapshots when saved files cannot be loaded exactly. Commands, scripts and GUI editor saves are refused while recovery is required.
- Missing filesystems in compound saved shells and present wrong-type outer Shell payloads are preserved. Valid explicit restoration resumes execution. An absent Shell field retains legacy/new-block behavior.
- Client display tags and incremental updates use a separate loading route and do not expose retained raw files.
- Operator command /tcpocket recover-block <x> <y> <z> rescues exact readable files from a loaded terminal, turtle or rack into a blank pocket, with a skipped-entry report and no source-machine changes.
- Pocket recovery pressure coverage verifies report retention at character/node limits and bounded diagnostics; omission counts no longer include the schema marker.
- Racks now drop nonstackable world-local recovery items retaining identity, files and scheduler state. Blades drop separately. An explicit empty module list prevents legacy migration from creating a duplicate blade on restoration.
- Actual damaged terminal/turtle break and replacement tests retain execution refusal and raw data, while copied recovery tickets are rejected.
- Network protocol remains 8. Approved textures are unchanged.

## Compatibility and limits

Verification: 85 baseline headless tasks and 157 required world tests passed with successful assembly. Packaged version, recovery classes and exact bundled guide verified; all 73 texture entries match 1.0.89 byte-for-byte. SHA-256: a6d2df77efdf46200b1a0ebe3f771d187200270645f42d110c38ed5d7046a2e0.

Keep complete world-data backups. Saved rack items require this version or newer; do not downgrade them. Existing migrated pocket, chunked-file and bulk-SSD downgrade restrictions still apply.

This is not packaged-JAR runtime acceptance, live recovery UI/command qualification, physical restart/crash atomicity, arbitrary malformed-NBT encoding recovery, or exactly-once external job execution. Full cabinet lifecycle, optional integrations, multiplayer, performance and visual qualification remain open. Milestone 2 is not complete.
