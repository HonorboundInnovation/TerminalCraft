# TerminalCraft 1.0.82 — Filesystem save safety

- Writes, directory creation and mounts now reject operations exceeding persistence capacity before changing the filesystem. Rejected overwrites/appends retain original content; rejected mounts leave no partial tree.
- File-parent paths are rejected, and bundled program installation respects filesystem capacity.
- Empty files survive saving at exactly full content capacity.
- Large ASCII and Unicode files use chunked NBT storage to avoid Minecraft's single-string encoded-size limit. Existing single-string files remain readable.

All 85 headless checks and 120 required world tests passed. Tests include real compressed save/reload cycles for maximum-size ASCII, CJK, NUL and emoji content, plus capacity boundaries and failed-operation rollback.

Do not downgrade worlds or media containing chunked files to older builds; they cannot read continuation chunks. Back up the whole world before upgrading. Previously lost content cannot be recovered by this fix.

Development checkpoint only; dedicated multiplayer/recovery, malformed legacy data and performance qualification remain open. No fresh live-client interaction was performed. Original CurseForge files/worlds and approved textures are unchanged. Network protocol remains 7.
