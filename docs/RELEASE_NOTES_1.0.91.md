# TerminalCraft 1.0.91 — development rack synchronization checkpoint

Not a release candidate. Back up the entire world before testing. This checkpoint does not install into the owner's CurseForge instance.

- Routine rack display updates no longer include full private scheduler snapshots (commands, principals, permissions and continuations).
- Client display loading ignores included scheduler data. Ordinary world saves and recovery tickets retain the queue unchanged.
- Coverage includes 128 maximum-length queued commands across four owners, bounded update size, incremental packets and exact saved queue restoration.
- Persistence tests now exercise an actual operating-system write denial against an existing pocket file, preserving previous disk bytes and pending changes before a successful retry. The denial check explicitly reports unsupported or permission-bypassing environments rather than claiming coverage there.
- Protocol remains 8. No intended texture changes or new saved-data format.

Verification: all 85 baseline headless tasks and 158 required world tests passed with assembly (exit 0, 2m 8s). Packaged version, updated rack class and exact bundled guide verified; all 73 textures match 1.0.90 byte-for-byte. SHA-256: `beea85aca20027d5b376aa03f36999764a4a46f05afee671a6d43d56fb14d869`.

All existing world-data backup and downgrade restrictions remain. This is not full multiplayer authorization, live rack transport, crash atomicity, optional-integration, performance or visual qualification. Milestone 2 remains open. This new package has not yet been launched or installed; earlier 1.0.90 runtime checks do not qualify its changed rack behavior.
