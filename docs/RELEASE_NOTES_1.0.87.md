# TerminalCraft 1.0.87 — development pocket-storage checkpoint

Not a release candidate. Back up the entire world before testing; this build has not been installed into the CurseForge instance.

- Pocket terminals migrate embedded shell files to world-backed records when opened, after a verified backup write. Migrated item packets no longer carry the filesystem.
- Sessions check the exact held item, computer identity and storage revision. Stale cloned sessions cannot overwrite newer files.
- Missing, unreadable and unsupported pocket records fail closed. Writes verify lossless NBT encoding and preserve retry state on IO failure.
- Operator commands `/tcpocket export` and `/tcpocket import <UUID>` provide portable archive copies. Import requires a blank unopened pocket and creates an independent identity. See the guide for archive transfer instructions and limits.
- Pocket opening uses an initial bounded shell view and server-backed completion. Network protocol is now 8: matching client/server builds are required.
- Malformed restored editor buffers cannot overwrite original files; recovery state persists through reload. Interactive editor lines reach validation before the normal command-length clamp.
- Approved textures are unchanged.

Verification: 85 headless checks and 137 required world tests passed; the development JAR was assembled and its archive classes/version metadata checked. SHA-256: `8417e0384acea3bda4a7a47d979d91680780553446751dcad13d50d1b8a2b1f0`.

## Save compatibility and remaining gates

Migrated pockets depend on the world's data directory. Item-only copies do not carry files across worlds. Do not downgrade migrated pockets to 1.0.86 or older. Keep backups and use the archive workflow for portable copies.

Unopened legacy oversized inventory synchronization, creative/multiplayer/death/drop workflows, separate-server archive transfer, power-loss recovery, complete visual/model acceptance and the broader completion plan remain open. Small-file live pocket migration/editor/completion and a clean full integrated-client JVM restart passed before this archive addition; new archive commands have automated coverage, not fresh live-client command acceptance.
