# TerminalCraft 1.0.80 — Large-filesystem screen updates

- Screen snapshots carry display state and active editor text, not the entire filesystem, private environment or media write token. Full persistence is unchanged.
- Terminal, turtle and server rack update paths build display snapshots directly. Recent output/history are bounded without deleting server files.
- Tab completion queries the live menu's server computer. Requests are rate/size/menu checked, replies are bounded, and stale input responses are ignored.
- Network protocol advances to 7; use matching client/server mod versions.

All 85 headless checks and 120 required world tests passed. A live isolated client displayed output, completed filenames, saved/read an editor file on a 2.4 MB filesystem, and reopened it after a full client-process restart. Both sampled large files remained 60,000 characters and the new note remained intact.

This is a development checkpoint, not complete dedicated multiplayer/crash or editor-boundary qualification. Original CurseForge install/worlds and approved textures are unchanged.
